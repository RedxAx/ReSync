package restudio.resync.flow.cache;

import org.junit.jupiter.api.Test;
import restudio.resync.flow.catalog.CatalogBindingProof;
import restudio.resync.flow.catalog.CatalogCompiler;
import restudio.resync.flow.catalog.CatalogRuntimeActivation;
import restudio.resync.flow.catalog.CatalogSnapshot;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.runtime.RuntimeBindingRegistry;
import restudio.resync.flow.runtime.RuntimeRegistrySnapshot;
import restudio.resync.flow.identity.ServerId;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CatalogPublicationKeyCoherenceTest {
    private static final ServerId SERVER = new ServerId(UUID.fromString("11111111-1111-4111-8111-111111111111"));

    @Test
    void successfulPublicationStoresItsExactKeyInTheActivationRecord() {
        CatalogRuntimeActivation activation = activation();
        CatalogCachePublicationTransport transport = transport(activation);

        CatalogCachePublication publication = transport.publishFull().orElseThrow();

        assertEquals(publication.key(), activation.activePublicationKey().orElseThrow());
        assertEquals(publication.key(), transport.activeKey().orElseThrow());
        assertEquals(publication.key(), transport.lastValidPublication().orElseThrow().key());
    }

    @Test
    void preparedPublicationDoesNotStoreTheKeyBeforeCommitAndRetryReusesCommittedKey() {
        CatalogRuntimeActivation activation = activation();
        CatalogCachePublicationTransport transport = transport(activation);

        CatalogCachePublication prepared = transport.prepareFull().orElseThrow();

        assertTrue(activation.activePublicationKey().isEmpty());
        assertTrue(transport.lastValidPublication().isEmpty());
        assertTrue(transport.validatesAgainstActive(prepared));

        transport.discardPreparedPublication(prepared);
        CatalogCachePublication retry = transport.prepareFull().orElseThrow();

        assertEquals(prepared.key(), retry.key());
        assertTrue(activation.activePublicationKey().isEmpty());
        assertTrue(transport.commitPreparedPublication(retry));
        assertEquals(retry.key(), activation.activePublicationKey().orElseThrow());
    }

    @Test
    void noOpRefreshKeepsTheExistingRecordAndPublicationKey() {
        CatalogRuntimeActivation activation = activation();
        CatalogCachePublicationTransport transport = transport(activation);
        CatalogCachePublication initial = transport.publishFull().orElseThrow();
        CatalogRuntimeActivation.ActivationRecord before = activation.active();

        CatalogCachePublication refresh = transport.publishRefresh().orElseThrow();

        assertEquals(initial.key(), refresh.key());
        assertSame(before, activation.active());
        assertEquals(initial.key(), activation.activePublicationKey().orElseThrow());
    }

    @Test
    void failedPreSendCreationLeavesThePriorRecordAndKeyUntouched() {
        CatalogRuntimeActivation activation = activation();
        CatalogCachePublicationTransport transport = transport(activation);
        transport.publishFull().orElseThrow();
        CatalogRuntimeActivation.ActivationRecord before = activation.active();
        CatalogSnapshot other = compile(new CatalogVersion(2, 0), 2, RuntimeRegistrySnapshot.empty());
        CatalogCacheKey mismatch = new CatalogCacheKey(SERVER, other.generation(), other.contentChecksum());

        assertTrue(transport.publishFull(mismatch).isEmpty());
        assertSame(before, activation.active());
        assertEquals(before.publicationKey(), activation.activePublicationKey());
    }

    @Test
    void concurrentReadersNeverObserveAKeyFromAnotherActivationRecord() throws Exception {
        RuntimeBindingRegistry registry = new RuntimeBindingRegistry();
        RuntimeRegistrySnapshot baseline = registry.snapshot();
        CatalogRuntimeActivation activation = new CatalogRuntimeActivation(compile(new CatalogVersion(1, 0), 1, baseline), baseline);
        CatalogCachePublicationTransport transport = transport(activation);
        CatalogSnapshot replacementCatalog = compile(new CatalogVersion(2, 0), 2, baseline);
        RuntimeBindingRegistry.RuntimeReplacement replacement = registry.prepareReplacement(List.of(), Set.of());
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            Future<?> writer = executor.submit(() -> {
                await(start);
                try {
                    activation.stageReplacement(replacementCatalog, replacement).commit();
                    transport.prepareFull().orElseThrow();
                } catch (Throwable exception) {
                    failure.compareAndSet(null, exception);
                }
            });
            List<Future<?>> readers = new ArrayList<>();
            for (int readerIndex = 0; readerIndex < 3; readerIndex++) {
                readers.add(executor.submit((Runnable) () -> {
                    await(start);
                    for (int index = 0; index < 10000 && failure.get() == null; index++) {
                        CatalogRuntimeActivation.ActivationRecord record = activation.active();
                        record.publicationKey().ifPresent(key -> {
                            if (!key.snapshotChecksum().equals(record.catalog().contentChecksum())) {
                                failure.compareAndSet(null, new AssertionError("Publication key crossed activation records"));
                            }
                        });
                    }
                }));
            }
            start.countDown();
            writer.get(10, TimeUnit.SECONDS);
            for (Future<?> reader : readers) {
                reader.get(10, TimeUnit.SECONDS);
            }
            assertNull(failure.get());
            assertSame(replacementCatalog, activation.active().catalog());
            assertTrue(activation.activePublicationKey().isEmpty());
            assertTrue(transport.activeKey().isEmpty());
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(10, TimeUnit.SECONDS);
            replacement.close();
        }
    }

    private static CatalogRuntimeActivation activation() {
        RuntimeRegistrySnapshot runtime = RuntimeRegistrySnapshot.empty();
        return new CatalogRuntimeActivation(compile(new CatalogVersion(1, 0), 1, runtime), runtime);
    }

    private static CatalogCachePublicationTransport transport(CatalogRuntimeActivation activation) {
        return new CatalogCachePublicationTransport(SERVER, activation, Set.of());
    }

    private static CatalogSnapshot compile(CatalogVersion version, long generation, RuntimeRegistrySnapshot runtime) {
        return new CatalogCompiler(version, CatalogBindingProof.snapshot(runtime))
            .compile(List.of(), generation)
            .snapshot()
            .orElseThrow();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Concurrent publication-key test was interrupted", exception);
        }
    }
}
