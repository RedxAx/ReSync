package restudio.resync.flow.cache;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.resync.contract.canonical.CanonicalHash;
import restudio.resync.contract.canonical.JsonValue;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.migration.MigrationFence;
import restudio.resync.migration.PersistenceOwnershipContext;
import restudio.resync.migration.PersistenceOwnershipIndex;
import restudio.resync.migration.ReSyncPersistenceCoordinator;
import restudio.resync.migration.Snapshot;
import restudio.resync.migration.SnapshotMetadata;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CatalogPublicationReceiptPersistenceParticipantTest {
    private static final ServerId SERVER = new ServerId(UUID.fromString("11111111-1111-4111-8111-111111111111"));
    private static final CatalogCacheKey KEY = new CatalogCacheKey(SERVER, 7, ContentHash.of("a".repeat(64)));
    private static final CatalogCachePublication PUBLICATION = CatalogCachePublication.full(CatalogCacheSnapshot.empty(KEY));
    private static final String OWNER = "owner-1";

    @TempDir
    Path temporary;

    @Test
    void createsAndLoadsTheCanonicalEmptyFile() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("data"));
        Path file = root.resolve(CatalogPublicationReceiptStore.FILE_NAME);

        CatalogPublicationReceiptStore store = new CatalogPublicationReceiptStore(root, file);

        assertEquals(CatalogPublicationReceiptStore.OWNER, store.owner());
        assertEquals(file, store.root());
        assertTrue(store.owns(file));
        assertFalse(store.owns(root));
        assertFalse(store.owns(root.resolve("assets")));
        assertTrue(Files.isRegularFile(file));
        assertTrue(store.baselines().isEmpty());
        store.healthCheck();
        assertThrows(IllegalArgumentException.class,
            () -> new CatalogPublicationReceiptStore(root, root.resolve("other-receipts.json")));
    }

    @Test
    void ownershipIndexMatchesExactReceiptOwnership() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("ownership"));
        Path file = root.resolve(CatalogPublicationReceiptStore.FILE_NAME);
        CatalogPublicationReceiptStore store = new CatalogPublicationReceiptStore(root, file);
        PersistenceOwnershipContext context = new PersistenceOwnershipContext(root, store.root());
        PersistenceOwnershipIndex index = store.ownershipIndex(context);
        Path rejected = root.resolve("other-receipts.json");

        assertTrue(store.owns(file));
        assertTrue(index.owns(context.relativeToSource(file)));
        assertFalse(store.owns(root));
        assertFalse(index.owns(context.relativeToSource(root.resolve("other-receipts.json"))));
        assertFalse(store.owns(rejected));
        assertFalse(index.owns(context.relativeToSource(rejected)));
    }

    @Test
    void mutationFlushAndRestartPreserveTheExactReceipt() throws Exception {
        CatalogPublicationReceiptStore store = new CatalogPublicationReceiptStore(
            temporary.resolve(CatalogPublicationReceiptStore.FILE_NAME));

        CatalogPublicationReceipt receipt = store.recordDispatch("session", OWNER, PUBLICATION);
        store.flush();
        assertEquals(receipt, new CatalogPublicationReceiptStore(store.path()).baseline("session").orElseThrow());
        assertEquals(KEY, receipt.publicationKey());
        assertEquals(PUBLICATION.revision(), receipt.revision());
    }

    @Test
    void quiesceRejectsMutationsAndResumeReopensAdmission() throws Exception {
        CatalogPublicationReceiptStore store = new CatalogPublicationReceiptStore(
            temporary.resolve(CatalogPublicationReceiptStore.FILE_NAME));

        store.quiesce();

        assertThrows(IllegalStateException.class, () -> store.recordDispatch("session", OWNER, PUBLICATION));
        assertTrue(store.isQuiesced());
        store.resume();
        assertTrue(store.recordDispatch("session", OWNER, PUBLICATION).serverDispatched());
    }

    @Test
    void successfulRebindSwitchesTheWholeReceiptStateAndGeneration() throws Exception {
        Path sourceRoot = Files.createDirectory(temporary.resolve("source"));
        Path candidateRoot = Files.createDirectory(temporary.resolve("candidate"));
        CatalogPublicationReceiptStore source = new CatalogPublicationReceiptStore(sourceRoot,
            sourceRoot.resolve(CatalogPublicationReceiptStore.FILE_NAME));
        CatalogPublicationReceiptStore candidate = new CatalogPublicationReceiptStore(candidateRoot,
            candidateRoot.resolve(CatalogPublicationReceiptStore.FILE_NAME));
        source.recordDispatch("source", OWNER, PUBLICATION);
        candidate.recordDispatch("candidate", OWNER, PUBLICATION);

        source.quiesce();
        source.rebind(candidateRoot);

        assertEquals(candidateRoot.resolve(CatalogPublicationReceiptStore.FILE_NAME), source.root());
        assertEquals(1L, source.generation());
        assertTrue(source.baseline("candidate").isPresent());
        assertTrue(source.baseline("source").isEmpty());
        assertTrue(source.owns(source.root()));
        candidate.close();
    }

    @Test
    void malformedOrMissingCandidatesLeaveTheOldBindingAndReceiptsUntouched() throws Exception {
        Path sourceRoot = Files.createDirectory(temporary.resolve("source"));
        Path malformedRoot = Files.createDirectory(temporary.resolve("malformed"));
        Path missingRoot = Files.createDirectory(temporary.resolve("missing"));
        CatalogPublicationReceiptStore store = new CatalogPublicationReceiptStore(sourceRoot,
            sourceRoot.resolve(CatalogPublicationReceiptStore.FILE_NAME));
        store.recordDispatch("source", OWNER, PUBLICATION);
        Path oldPath = store.root();
        CatalogPublicationReceipt oldReceipt = store.baseline("source").orElseThrow();
        byte[] oldBytes = Files.readAllBytes(oldPath);
        Files.writeString(malformedRoot.resolve(CatalogPublicationReceiptStore.FILE_NAME), "{}", StandardCharsets.UTF_8);
        store.quiesce();

        assertThrows(RuntimeException.class, () -> store.rebind(malformedRoot));
        assertEquals(oldPath, store.root());
        assertEquals(oldReceipt, store.baseline("source").orElseThrow());
        assertArrayEquals(oldBytes, Files.readAllBytes(oldPath));
        assertThrows(Exception.class, () -> store.rebind(missingRoot));
        assertEquals(oldPath, store.root());
        assertEquals(0L, store.generation());
        assertArrayEquals(oldBytes, Files.readAllBytes(oldPath));
    }

    @Test
    void syntacticallyCanonicalSemanticInvalidCandidatesLeaveTheOldBindingUntouched() throws Exception {
        Path sourceRoot = Files.createDirectory(temporary.resolve("semantic-source"));
        CatalogPublicationReceiptStore store = new CatalogPublicationReceiptStore(sourceRoot,
            sourceRoot.resolve(CatalogPublicationReceiptStore.FILE_NAME));
        store.recordDispatch("source", OWNER, PUBLICATION);
        Path oldPath = store.root();
        CatalogPublicationReceipt oldReceipt = store.baseline("source").orElseThrow();
        byte[] oldBytes = Files.readAllBytes(oldPath);
        store.quiesce();

        List<Map<String, Object>> invalidRows = List.of(
            receiptRow("source", "failed", "not-received", "not-applied", ""),
            receiptRow("source", "not-attempted", "rejected", "not-applied", ""),
            receiptRow("source", "dispatched", "not-received", "rejected", ""),
            receiptRow("source", "dispatched", "not-received", "applied", ""),
            receiptRow("source", "dispatched", "rejected", "rejected", ""),
            receiptRow("source", "failed", "rejected", "rejected", "")
        );
        for (int index = 0; index < invalidRows.size(); index++) {
            Path candidateRoot = Files.createDirectory(temporary.resolve("semantic-candidate-" + index));
            Files.write(candidateRoot.resolve(CatalogPublicationReceiptStore.FILE_NAME),
                receiptDocument(List.of(invalidRows.get(index))));

            assertThrows(RuntimeException.class, () -> store.rebind(candidateRoot));
            assertEquals(oldPath, store.root());
            assertEquals(0L, store.generation());
            assertEquals(oldReceipt, store.baseline("source").orElseThrow());
            assertArrayEquals(oldBytes, Files.readAllBytes(oldPath));
        }

        Path duplicateRoot = Files.createDirectory(temporary.resolve("semantic-duplicates"));
        Map<String, Object> validRow = receiptRow("duplicate", "dispatched", "not-received", "not-applied", "");
        Files.write(duplicateRoot.resolve(CatalogPublicationReceiptStore.FILE_NAME),
            receiptDocument(List.of(validRow, validRow)));

        assertThrows(RuntimeException.class, () -> store.rebind(duplicateRoot));
        assertEquals(oldPath, store.root());
        assertEquals(oldReceipt, store.baseline("source").orElseThrow());
        assertArrayEquals(oldBytes, Files.readAllBytes(oldPath));
    }

    @Test
    void concurrentReadersMutatorsAndRebindNeverExposeMixedReceiptGenerations() throws Exception {
        Path sourceRoot = Files.createDirectory(temporary.resolve("source-concurrent"));
        Path candidateRoot = Files.createDirectory(temporary.resolve("candidate-concurrent"));
        CatalogPublicationReceiptStore store = new CatalogPublicationReceiptStore(sourceRoot,
            sourceRoot.resolve(CatalogPublicationReceiptStore.FILE_NAME));
        CatalogPublicationReceiptStore candidate = new CatalogPublicationReceiptStore(candidateRoot,
            candidateRoot.resolve(CatalogPublicationReceiptStore.FILE_NAME));
        store.recordDispatch("source-seed", OWNER, PUBLICATION);
        candidate.recordDispatch("candidate-seed", OWNER, PUBLICATION);

        ExecutorService executor = Executors.newFixedThreadPool(4);
        CountDownLatch mutationStarted = new CountDownLatch(1);
        CountDownLatch mutatorStopped = new CountDownLatch(1);
        AtomicBoolean running = new AtomicBoolean(true);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Future<?> mutator = executor.submit(() -> {
            try {
                mutationStarted.countDown();
                for (int index = 0; index < 500 && running.get(); index++) {
                    try {
                        store.recordDispatch("source-" + index, OWNER, PUBLICATION);
                    } catch (IllegalStateException exception) {
                        if (!store.isQuiesced() && !store.isClosed()) {
                            failure.compareAndSet(null, exception);
                        }
                        return;
                    }
                }
            } finally {
                mutatorStopped.countDown();
            }
        });
        Future<?> readerOne = reader(executor, store, running, failure);
        Future<?> readerTwo = reader(executor, store, running, failure);
        Future<?> rebind = executor.submit(() -> {
            try {
                mutationStarted.await(5, TimeUnit.SECONDS);
                store.quiesce();
                store.rebind(candidateRoot);
                if (!mutatorStopped.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Receipt mutator did not stop after quiesce");
                }
                store.resume();
            } catch (Throwable exception) {
                failure.compareAndSet(null, exception);
            } finally {
                running.set(false);
            }
        });

        rebind.get(10, TimeUnit.SECONDS);
        mutator.get(10, TimeUnit.SECONDS);
        readerOne.get(10, TimeUnit.SECONDS);
        readerTwo.get(10, TimeUnit.SECONDS);
        executor.shutdownNow();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        assertNull(failure.get());
        assertEquals(candidateRoot.resolve(CatalogPublicationReceiptStore.FILE_NAME), store.root());
        assertEquals(1L, store.generation());
        assertEquals(List.of("candidate-seed"), store.baselines().stream()
            .map(CatalogPublicationReceipt::sessionKey)
            .toList());
        store.close();
        candidate.close();
    }

    private static Future<?> reader(ExecutorService executor, CatalogPublicationReceiptStore store,
                                    AtomicBoolean running, AtomicReference<Throwable> failure) {
        return executor.submit(() -> {
            try {
                while (running.get()) {
                    List<CatalogPublicationReceipt> snapshot = store.baselines();
                    boolean source = snapshot.stream().anyMatch(receipt -> receipt.sessionKey().startsWith("source-"));
                    boolean candidate = snapshot.stream().anyMatch(receipt -> receipt.sessionKey().startsWith("candidate-"));
                    if (source && candidate) {
                        throw new AssertionError("Receipt snapshot mixed source and candidate generations");
                    }
                    store.pendingReplays();
                }
            } catch (Throwable exception) {
                failure.compareAndSet(null, exception);
            }
        });
    }

    private static Map<String, Object> receiptRow(String sessionKey, String dispatch, String clientReceipt,
                                                  String cacheApplication, String diagnosticCode) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("sessionKey", sessionKey);
        row.put("publicationKey", KEY.canonicalText());
        row.put("revision", PUBLICATION.revision());
        row.put("dispatch", dispatch);
        row.put("clientReceipt", clientReceipt);
        row.put("cacheApplication", cacheApplication);
        row.put("diagnosticCode", diagnosticCode);
        return row;
    }

    private static byte[] receiptDocument(List<Map<String, Object>> rows) {
        Map<String, Object> base = new LinkedHashMap<>();
        base.put("kind", "catalog-publication-receipts");
        base.put("version", 1);
        base.put("receipts", rows);
        base.put("contentHash", CanonicalHash.sha256(JsonValue.fromJava(base)));
        return JsonValue.fromJava(base).canonicalBytes();
    }

    @Test
    void snapshotCopiesTheReceiptFileAndShutdownClosesAdmission() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("snapshot-data"));
        CatalogPublicationReceiptStore store = new CatalogPublicationReceiptStore(dataRoot,
            dataRoot.resolve(CatalogPublicationReceiptStore.FILE_NAME));
        store.recordDispatch("session", OWNER, PUBLICATION);
        ReSyncPersistenceCoordinator coordinator = new ReSyncPersistenceCoordinator(dataRoot,
            temporary.resolve("coordination"), new MigrationFence());
        coordinator.register(store);
        coordinator.seal();

        Snapshot snapshot = coordinator.createSnapshot(temporary.resolve("staging"), SnapshotMetadata.preflight());

        assertTrue(snapshot.verified());
        assertArrayEquals(Files.readAllBytes(store.root()),
            Files.readAllBytes(snapshot.root().resolve(CatalogPublicationReceiptStore.FILE_NAME)));
        coordinator.close();
        assertTrue(store.isClosed());
        assertThrows(IllegalStateException.class, () -> store.remove("session", OWNER));
    }
}
