package restudio.resync.flow;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.flow.data.FlowSerializer;
import restudio.flow.data.TabDefinition;
import restudio.resync.resources.AssetFileFormat;
import restudio.resync.storage.AssetTransactionCoordinator;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowStorageRuntimeTabTest {
    @TempDir
    Path directory;
    private final List<AssetTransactionCoordinator> coordinators = new ArrayList<>();

    @AfterEach
    void closeCoordinators() throws Exception {
        for (AssetTransactionCoordinator coordinator : coordinators.reversed()) {
            coordinator.close();
        }
    }

    @Test
    void warmRuntimeReadsCopyOnlyTheVerifiedDefinition() throws Exception {
        CountingStorage storage = storage(directory);
        storage.saveTab(tab("main", "Original"));
        assertEquals("Original", storage.getRuntimeTab("main").getHeader());
        assertEquals(1, storage.reads);
        storage.reads = 0;

        for (int index = 0; index < 200; index++) {
            TabDefinition definition = storage.getRuntimeTab("main");
            assertEquals("Original", definition.getHeader());
            definition.setHeader("Caller Mutation");
            definition.setId("Changed");
            definition.setEnabled(false);
        }

        assertEquals(0, storage.reads);
        assertTrue(storage.getRuntimeTab("main").isEnabled());
        assertEquals("main", storage.getRuntimeTab("main").getId());
    }

    @Test
    void populatedRefreshIntervalReadDoesNotWaitForStorageMonitor() throws Exception {
        CountingStorage storage = storage(directory);
        storage.saveTab(tab("main", "Populated"));
        storage.setTabRefreshIntervalTicks(37);
        CountDownLatch monitorHeld = new CountDownLatch(1);
        CountDownLatch releaseMonitor = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var holder = executor.submit(() -> {
                synchronized (storage) {
                    monitorHeld.countDown();
                    await(releaseMonitor);
                }
            });
            assertTrue(monitorHeld.await(5L, TimeUnit.SECONDS));

            assertEquals(37, executor.submit(storage::getTabRefreshIntervalTicks).get(1L, TimeUnit.SECONDS));
            releaseMonitor.countDown();
            holder.get(5L, TimeUnit.SECONDS);
        } finally {
            releaseMonitor.countDown();
        }
    }

    @Test
    void directMutationsAndAbsenceRemainAuthoritative() throws Exception {
        CountingStorage storage = storage(directory);
        for (int index = 0; index < 100; index++) {
            assertNull(storage.getRuntimeTab("main"));
        }
        assertEquals(0, storage.reads);
        storage.saveTab(tab("main", "Created"));
        assertEquals("Created", storage.getRuntimeTab("main").getHeader());
        storage.saveTab(tab("main", "Saved"));
        assertEquals("Saved", storage.getRuntimeTab("main").getHeader());
        storage.deleteTab("main");
        storage.reads = 0;

        for (int index = 0; index < 100; index++) {
            assertNull(storage.getRuntimeTab("main"));
        }

        assertEquals(0, storage.reads);
        assertTrue(storage.getTabCache().isEmpty());
    }

    @Test
    void coordinatorMutationsAreVisibleBeforePostCommitCallbacksComplete() throws Exception {
        CountingStorage storage = storage(directory);
        storage.saveTab(tab("main", "Original"));
        storage.getRuntimeTab("main");
        AssetTransactionCoordinator coordinator = coordinators.getFirst();
        AtomicReference<String> observed = new AtomicReference<>();
        try (var registration = coordinator.addListener(result -> {
            TabDefinition definition = storage.getRuntimeTab("main");
            observed.set(definition == null ? "Deleted" : definition.getHeader());
        })) {
            replace(coordinator, tab("main", "Coordinated"));
            assertEquals("Coordinated", observed.get());
            assertEquals("Coordinated", storage.getRuntimeTab("main").getHeader());
            delete(coordinator, "main");
            assertEquals("Deleted", observed.get());
            assertNull(storage.getRuntimeTab("main"));
            assertTrue(coordinator.listenerFailures().isEmpty());
        }
    }

    @Test
    void aPublicationDuringValidationCannotStampTheOldDefinition() throws Exception {
        CountingStorage storage = storage(directory);
        storage.saveTab(tab("main", "Original"));
        AssetTransactionCoordinator coordinator = coordinators.getFirst();
        storage.afterRead = () -> {
            storage.afterRead = null;
            replaceUnchecked(coordinator, tab("main", "Replacement"));
        };

        assertEquals("Replacement", storage.getRuntimeTab("main").getHeader());
        assertEquals(2, storage.reads);
        storage.reads = 0;
        assertEquals("Replacement", storage.getRuntimeTab("main").getHeader());
        assertEquals(0, storage.reads);
    }

    @Test
    void repeatedPublicationDuringValidationFailsClosedAndCanRetry() throws Exception {
        CountingStorage storage = storage(directory);
        storage.saveTab(tab("main", "Original"));
        AssetTransactionCoordinator coordinator = coordinators.getFirst();
        storage.afterRead = () -> replaceUnchecked(coordinator, tab("main", "Replacement"));

        assertThrows(IllegalStateException.class, () -> storage.getRuntimeTab("main"));
        assertEquals(3, storage.reads);
        storage.afterRead = null;
        assertEquals("Replacement", storage.getRuntimeTab("main").getHeader());
    }

    @Test
    void cacheClearQuiesceAndCoordinatorCloseFenceWarmReads() throws Exception {
        CountingStorage storage = storage(directory);
        storage.saveTab(tab("main", "Original"));
        storage.getRuntimeTab("main");
        storage.clearCache();
        storage.reads = 0;
        assertEquals("Original", storage.getRuntimeTab("main").getHeader());
        assertEquals(1, storage.reads);
        storage.quiescePersistence();
        assertThrows(IllegalStateException.class, () -> storage.getRuntimeTab("main"));
        storage.resumePersistence();
        storage.reads = 0;
        assertEquals("Original", storage.getRuntimeTab("main").getHeader());
        assertEquals(1, storage.reads);
        coordinators.getFirst().close();
        assertThrows(IllegalStateException.class, () -> storage.getRuntimeTab("main"));
    }

    @Test
    void rebindDiscardsThePreviousPersistenceProjection() throws Exception {
        CountingStorage storage = storage(directory);
        storage.saveTab(tab("main", "Original"));
        storage.getRuntimeTab("main");
        Path replacement = Files.createDirectory(directory.resolve("replacement"));
        CountingStorage replacementStorage = storage(replacement);
        replacementStorage.saveTab(tab("main", "Rebound"));
        AssetTransactionCoordinator replacementCoordinator = coordinators.getLast();
        storage.quiescePersistence();
        storage.rebindPersistence(replacement.resolve("assets"), replacementCoordinator);
        storage.resumePersistence();

        assertEquals("Rebound", storage.getRuntimeTab("main").getHeader());
        replacementStorage.saveTab(tab("main", "Updated After Rebind"));
        assertEquals("Updated After Rebind", storage.getRuntimeTab("main").getHeader());
    }

    @Test
    void normalReadsStillRejectPhysicalTamperingAndDoNotWarmInvalidRuntimeState() throws Exception {
        CountingStorage storage = storage(directory);
        storage.saveTab(tab("main", "Original"));
        storage.getRuntimeTab("main");
        Path file = coordinators.getFirst().committedAsset(key("main")).orElseThrow().path();
        var payload = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        payload.addProperty("header", "Tampered");
        Files.writeString(file, payload.toString());

        assertThrows(IllegalStateException.class, () -> storage.getTab("main"));
        assertThrows(IllegalStateException.class, () -> storage.getRuntimeTab("main"));
    }

    @Test
    void runtimeObservationsFenceMutationsAndStorageOwners() throws Exception {
        CountingStorage storage = storage(directory);
        FlowStorage.RuntimeObservation empty = storage.observeRuntime().orElseThrow();
        assertTrue(storage.isRuntimeObservationCurrent(empty));

        storage.saveTab(tab("main", "Created"));

        assertFalse(storage.isRuntimeObservationCurrent(empty));
        FlowStorage.RuntimeObservation committed = storage.observeRuntime().orElseThrow();
        assertTrue(storage.isRuntimeObservationCurrent(committed));
        assertTrue(committed.committedSequence() > empty.committedSequence());
        Path otherRoot = Files.createDirectory(directory.resolve("other"));
        CountingStorage other = storage(otherRoot);
        assertFalse(other.isRuntimeObservationCurrent(committed));
    }

    @Test
    void runtimeObservationSeesCommittedMutationBeforeListenerCompletion() throws Exception {
        CountingStorage storage = storage(directory);
        storage.saveTab(tab("main", "Original"));
        FlowStorage.RuntimeObservation before = storage.observeRuntime().orElseThrow();
        AssetTransactionCoordinator coordinator = coordinators.getFirst();
        CountDownLatch listenerEntered = new CountDownLatch(1);
        CountDownLatch releaseListener = new CountDownLatch(1);
        coordinator.addListener(result -> {
            listenerEntered.countDown();
            await(releaseListener);
        });
        try (var executor = Executors.newSingleThreadExecutor()) {
            var mutation = executor.submit(() -> {
                replace(coordinator, tab("main", "Replacement"));
                return null;
            });
            assertTrue(listenerEntered.await(5L, TimeUnit.SECONDS));

            assertFalse(storage.isRuntimeObservationCurrent(before));
            FlowStorage.RuntimeObservation after = storage.observeRuntime().orElseThrow();
            assertTrue(storage.isRuntimeObservationCurrent(after));
            assertTrue(after.committedSequence() > before.committedSequence());
            releaseListener.countDown();
            mutation.get(5L, TimeUnit.SECONDS);
        } finally {
            releaseListener.countDown();
        }
    }

    @Test
    void runtimeObservationsFenceQuiesceResumeRebindAndClose() throws Exception {
        CountingStorage storage = storage(directory);
        storage.saveTab(tab("main", "Original"));
        FlowStorage.RuntimeObservation original = storage.observeRuntime().orElseThrow();
        Path replacementRoot = Files.createDirectory(directory.resolve("observation-replacement"));
        CountingStorage replacement = storage(replacementRoot);
        replacement.saveTab(tab("main", "Replacement"));
        AssetTransactionCoordinator replacementCoordinator = coordinators.getLast();

        storage.quiescePersistence();
        assertTrue(storage.observeRuntime().isEmpty());
        assertFalse(storage.isRuntimeObservationCurrent(original));
        storage.rebindPersistence(replacementRoot.resolve("assets"), replacementCoordinator);
        assertTrue(storage.observeRuntime().isEmpty());
        storage.resumePersistence();

        FlowStorage.RuntimeObservation rebound = storage.observeRuntime().orElseThrow();
        assertTrue(storage.isRuntimeObservationCurrent(rebound));
        assertEquals(replacementCoordinator, rebound.coordinator());
        assertFalse(storage.isRuntimeObservationCurrent(original));
        replacementCoordinator.close();
        assertTrue(storage.observeRuntime().isEmpty());
        assertFalse(storage.isRuntimeObservationCurrent(rebound));
    }

    private CountingStorage storage(Path root) throws Exception {
        AssetTransactionCoordinator coordinator = AssetTransactionCoordinator.open(root.resolve("assets"), new Gson());
        coordinators.add(coordinator);
        return new CountingStorage(root.toFile(), coordinator);
    }

    private static TabDefinition tab(String id, String header) {
        TabDefinition definition = new TabDefinition(id);
        definition.setHeader(header);
        return definition;
    }

    private static AssetTransactionCoordinator.AssetKey key(String id) {
        return new AssetTransactionCoordinator.AssetKey("tab", id);
    }

    private static void replace(AssetTransactionCoordinator coordinator, TabDefinition definition) throws Exception {
        AssetTransactionCoordinator.Snapshot snapshot = coordinator.read(Function.identity());
        AssetTransactionCoordinator.AssetKey key = key(definition.getId());
        AssetTransactionCoordinator.Live live = (AssetTransactionCoordinator.Live) snapshot.state(key).orElseThrow();
        UUID mutationId = UUID.randomUUID();
        String payload = AssetFileFormat.withResourceIdentity(FlowSerializer.serializeTab(definition), "tab",
            live.revision() + 1L, mutationId.toString());
        coordinator.transact(new AssetTransactionCoordinator.TransactionRequest(mutationId, snapshot.project(),
            List.of(AssetTransactionCoordinator.AssetDelta.write(key, snapshot.path(key).orElseThrow(), live,
                payload.getBytes(StandardCharsets.UTF_8))), List.of()));
    }

    private static void replaceUnchecked(AssetTransactionCoordinator coordinator, TabDefinition definition) {
        try {
            replace(coordinator, definition);
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static void delete(AssetTransactionCoordinator coordinator, String id) throws Exception {
        AssetTransactionCoordinator.Snapshot snapshot = coordinator.read(Function.identity());
        AssetTransactionCoordinator.AssetKey key = key(id);
        coordinator.transact(new AssetTransactionCoordinator.TransactionRequest(UUID.randomUUID(), snapshot.project(),
            List.of(AssetTransactionCoordinator.AssetDelta.delete(key, snapshot.path(key).orElseThrow(),
                snapshot.state(key).orElseThrow())), List.of()));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5L, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Latch timed out");
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(failure);
        }
    }

    private static final class CountingStorage extends FlowStorage {
        private int reads;
        private Runnable afterRead;

        private CountingStorage(File directory, AssetTransactionCoordinator coordinator) {
            super(directory, coordinator);
        }

        @Override
        public synchronized TabDefinition getTab(String id) {
            reads++;
            TabDefinition definition = super.getTab(id);
            if (afterRead != null) {
                afterRead.run();
            }
            return definition;
        }
    }
}
