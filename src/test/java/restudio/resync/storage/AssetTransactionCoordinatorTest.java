package restudio.resync.storage;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssetTransactionCoordinatorTest {
    private static final Gson GSON = new Gson();

    @TempDir
    Path tempDir;

    @Test
    void typedCasSequenceReplayAndListenerIsolationRemainAtomic() throws Exception {
        Path root = tempDir.resolve("assets");
        AssetTransactionCoordinator.AssetKey key = new AssetTransactionCoordinator.AssetKey("flow", "main");
        AtomicInteger delivered = new AtomicInteger();
        try (AssetTransactionCoordinator coordinator = AssetTransactionCoordinator.open(root, GSON)) {
            AssetTransactionCoordinator.ExpectedProject project = coordinator.read(AssetTransactionCoordinator.Snapshot::project);
            coordinator.addListener(result -> {
                throw new IllegalStateException("listener failure");
            });
            coordinator.addListener(result -> delivered.incrementAndGet());
            UUID mutationId = UUID.fromString("11111111-1111-4111-8111-111111111111");
            AssetTransactionCoordinator.TransactionRequest request = new AssetTransactionCoordinator.TransactionRequest(
                mutationId, project, List.of(AssetTransactionCoordinator.AssetDelta.write(key, Path.of("Blueprints/Flows/main.json"),
                AssetTransactionCoordinator.Missing.INSTANCE, "payload".getBytes(StandardCharsets.UTF_8))), List.of());

            AssetTransactionCoordinator.TransactionResult committed = coordinator.transact(request);
            AssetTransactionCoordinator.TransactionResult replayed = coordinator.transact(request);

            assertEquals(1L, committed.rootSequence());
            assertEquals(committed.rootSequence(), replayed.rootSequence());
            assertTrue(replayed.replay());
            assertEquals(1, delivered.get());
            assertEquals(1, coordinator.listenerFailures().size());
            assertInstanceOf(AssetTransactionCoordinator.Live.class, committed.states().get(key));
            assertEquals("payload", Files.readString(root.resolve("Blueprints/Flows/main.json")));
            assertTrue(Files.isRegularFile(root.resolve(".asset-coordinator/state.json")));
            assertTrue(Files.isRegularFile(root.resolve("project.json")));
            assertThrows(AssetTransactionCoordinator.MutationConflictException.class, () -> coordinator.transact(
                new AssetTransactionCoordinator.TransactionRequest(mutationId, project,
                    List.of(AssetTransactionCoordinator.AssetDelta.write(key, Path.of("Blueprints/Flows/main.json"),
                        AssetTransactionCoordinator.Missing.INSTANCE, "different".getBytes(StandardCharsets.UTF_8))), List.of())));
        }
    }

    @Test
    void fairRootLockAllowsOnlyOneConcurrentMissingCas() throws Exception {
        Path root = tempDir.resolve("concurrent-assets");
        try (AssetTransactionCoordinator first = AssetTransactionCoordinator.open(root, GSON);
             AssetTransactionCoordinator second = AssetTransactionCoordinator.open(root.resolve("."), GSON);
             var executor = Executors.newFixedThreadPool(2)) {
            AssetTransactionCoordinator.ExpectedProject project = first.read(AssetTransactionCoordinator.Snapshot::project);
            AssetTransactionCoordinator.AssetKey key = new AssetTransactionCoordinator.AssetKey("function", "shared");
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            Future<Boolean> left = executor.submit(() -> compete(first, project, key,
                UUID.fromString("22222222-2222-4222-8222-222222222222"), ready, start));
            Future<Boolean> right = executor.submit(() -> compete(second, project, key,
                UUID.fromString("33333333-3333-4333-8333-333333333333"), ready, start));

            ready.await();
            start.countDown();

            assertTrue(left.get() ^ right.get());
            long rootSequence = first.read(AssetTransactionCoordinator.Snapshot::rootSequence);
            assertEquals(1L, rootSequence);
        }
    }

    @Test
    void projectDeltasMutateTheCanonicalProjectWithoutDroppingUnknownFields() throws Exception {
        Path root = tempDir.resolve("project-assets");
        try (AssetTransactionCoordinator coordinator = AssetTransactionCoordinator.open(root, GSON)) {
            AssetTransactionCoordinator.ExpectedProject project = coordinator.read(AssetTransactionCoordinator.Snapshot::project);
            coordinator.transact(new AssetTransactionCoordinator.TransactionRequest(
                UUID.fromString("44444444-4444-4444-8444-444444444444"), project, List.of(),
                List.of(AssetTransactionCoordinator.ProjectDelta.set(List.of("serverId"), JsonParser.parseString("\"project\"")),
                    AssetTransactionCoordinator.ProjectDelta.set(List.of("unknown"), JsonParser.parseString("{\"keep\":true}")),
                    AssetTransactionCoordinator.ProjectDelta.set(List.of("folders"), JsonParser.parseString("[]")))));
            project = coordinator.read(AssetTransactionCoordinator.Snapshot::project);
            coordinator.transact(new AssetTransactionCoordinator.TransactionRequest(
                UUID.fromString("55555555-5555-4555-8555-555555555555"), project, List.of(),
                List.of(AssetTransactionCoordinator.ProjectDelta.set(List.of("folders"),
                    JsonParser.parseString("[{\"path\":\"Blueprints\"}]")))));

            String projectJson = Files.readString(root.resolve("project.json"));
            assertTrue(projectJson.contains("\"keep\":true"));
            assertTrue(projectJson.contains("\"Blueprints\""));
        }
    }

    @Test
    void orphanedJournalDirectoryBlocksRecoveryWithoutApplyingAnything() throws Exception {
        Path root = tempDir.resolve("orphan-assets");
        Path orphan = Files.createDirectories(root.resolve(".transactions/orphan"));
        Files.writeString(orphan.resolve("content-0.json"), "unowned");

        assertThrows(IOException.class, () -> AssetTransactionCoordinator.open(root, GSON));
        assertTrue(Files.notExists(root.resolve("project.json")));
        assertTrue(Files.notExists(root.resolve(".asset-coordinator/state.json")));
    }

    @Test
    void deleteRecreateAbaAndSameByteStaleRevisionAreRejected() throws Exception {
        Path root = tempDir.resolve("aba-assets");
        AssetTransactionCoordinator.AssetKey key = new AssetTransactionCoordinator.AssetKey("flow", "aba");
        byte[] payload = "same".getBytes(StandardCharsets.UTF_8);
        try (AssetTransactionCoordinator coordinator = AssetTransactionCoordinator.open(root, GSON)) {
            AssetTransactionCoordinator.ExpectedProject project = coordinator.read(AssetTransactionCoordinator.Snapshot::project);
            AssetTransactionCoordinator.TransactionResult created = coordinator.transact(request("60000000-0000-4000-8000-000000000001",
                project, AssetTransactionCoordinator.AssetDelta.write(key, Path.of("Blueprints/Flows/aba.json"),
                    AssetTransactionCoordinator.Missing.INSTANCE, payload)));
            AssetTransactionCoordinator.Live first = (AssetTransactionCoordinator.Live) created.states().get(key);
            AssetTransactionCoordinator.TransactionResult saved = coordinator.transact(request("60000000-0000-4000-8000-000000000002",
                project, AssetTransactionCoordinator.AssetDelta.write(key, Path.of("Blueprints/Flows/aba.json"), first, payload)));
            AssetTransactionCoordinator.Live second = (AssetTransactionCoordinator.Live) saved.states().get(key);

            assertEquals(first.hash(), second.hash());
            assertEquals(first.revision() + 1L, second.revision());
            assertThrows(AssetTransactionCoordinator.StateConflictException.class, () -> coordinator.transact(request(
                "60000000-0000-4000-8000-000000000003", project,
                AssetTransactionCoordinator.AssetDelta.write(key, Path.of("Blueprints/Flows/aba.json"), first, payload))));

            AssetTransactionCoordinator.TransactionResult deleted = coordinator.transact(request("60000000-0000-4000-8000-000000000004",
                project, AssetTransactionCoordinator.AssetDelta.delete(key, Path.of("Blueprints/Flows/aba.json"), second)));
            AssetTransactionCoordinator.Deleted tombstone = (AssetTransactionCoordinator.Deleted) deleted.states().get(key);
            AssetTransactionCoordinator.TransactionResult recreated = coordinator.transact(request("60000000-0000-4000-8000-000000000005",
                project, AssetTransactionCoordinator.AssetDelta.write(key, Path.of("Blueprints/Flows/aba.json"), tombstone, payload)));

            assertEquals(tombstone.revision() + 1L, recreated.states().get(key).revision());
        }
    }

    @Test
    void restartReplaysTheOriginalMutationAndPreservesRootSequence() throws Exception {
        Path root = tempDir.resolve("restart-assets");
        AssetTransactionCoordinator.AssetKey key = new AssetTransactionCoordinator.AssetKey("flow", "restart");
        AssetTransactionCoordinator.TransactionRequest request;
        try (AssetTransactionCoordinator coordinator = AssetTransactionCoordinator.open(root, GSON)) {
            request = request("70000000-0000-4000-8000-000000000001",
                coordinator.read(AssetTransactionCoordinator.Snapshot::project),
                AssetTransactionCoordinator.AssetDelta.write(key, Path.of("Blueprints/Flows/restart.json"),
                    AssetTransactionCoordinator.Missing.INSTANCE, "restart".getBytes(StandardCharsets.UTF_8)));
            assertEquals(1L, coordinator.transact(request).rootSequence());
        }

        try (AssetTransactionCoordinator coordinator = AssetTransactionCoordinator.open(root, GSON)) {
            AssetTransactionCoordinator.TransactionResult replay = coordinator.transact(request);
            assertTrue(replay.replay());
            assertEquals(1L, replay.rootSequence());
            long rootSequence = coordinator.read(AssetTransactionCoordinator.Snapshot::rootSequence);
            assertEquals(1L, rootSequence);
        }
    }

    @Test
    void restartValidatesAndCompletesAnExactPreparedCoordinatorJournal() throws Exception {
        Path root = tempDir.resolve("prepared-restart-assets");
        AssetTransactionCoordinator.AssetKey key = new AssetTransactionCoordinator.AssetKey("flow", "prepared-restart");
        AssetTransactionCoordinator.TransactionRequest request;
        try (AssetTransactionCoordinator coordinator = AssetTransactionCoordinator.open(root, GSON)) {
            request = request("71000000-0000-4000-8000-000000000001",
                coordinator.read(AssetTransactionCoordinator.Snapshot::project),
                AssetTransactionCoordinator.AssetDelta.write(key, Path.of("prepared-restart.json"),
                    AssetTransactionCoordinator.Missing.INSTANCE, "restart".getBytes(StandardCharsets.UTF_8)));
            coordinator.transact(request);
        }
        Path transaction;
        try (var transactions = Files.list(root.resolve(".transactions"))) {
            transaction = transactions.filter(Files::isDirectory).findFirst().orElseThrow();
        }
        Path journalFile = transaction.resolve("journal.json");
        JsonObject journal = JsonParser.parseString(Files.readString(journalFile)).getAsJsonObject();
        journal.addProperty("state", "PREPARED");
        Files.writeString(journalFile, journal.toString());
        Files.delete(root.resolve("prepared-restart.json"));

        try (AssetTransactionCoordinator coordinator = AssetTransactionCoordinator.open(root, GSON)) {
            assertEquals("restart", Files.readString(root.resolve("prepared-restart.json")));
            long rootSequence = coordinator.read(AssetTransactionCoordinator.Snapshot::rootSequence);
            assertEquals(1L, rootSequence);
            assertTrue(coordinator.transact(request).replay());
        }
    }

    @Test
    void missingStateAndUnregisteredPhysicalEvidenceFailClosed() throws Exception {
        Path preexisting = tempDir.resolve("preexisting-assets");
        Files.createDirectories(preexisting.resolve("Blueprints/Flows"));
        Files.writeString(preexisting.resolve("Blueprints/Flows/rogue.json"), "rogue");
        assertThrows(IOException.class, () -> AssetTransactionCoordinator.open(preexisting, GSON));

        Path managed = tempDir.resolve("managed-assets");
        try (AssetTransactionCoordinator coordinator = AssetTransactionCoordinator.open(managed, GSON)) {
            coordinator.transact(new AssetTransactionCoordinator.TransactionRequest(
                UUID.fromString("80000000-0000-4000-8000-000000000001"),
                coordinator.read(AssetTransactionCoordinator.Snapshot::project), List.of(),
                List.of(AssetTransactionCoordinator.ProjectDelta.set(List.of("serverId"), JsonParser.parseString("\"project\"")))));
        }
        Files.createDirectories(managed.resolve("Blueprints/Flows"));
        Files.writeString(managed.resolve("Blueprints/Flows/unregistered.json"), "rogue");
        assertThrows(IOException.class, () -> AssetTransactionCoordinator.open(managed, GSON));

        Path missingState = tempDir.resolve("missing-state-assets");
        Files.createDirectories(missingState);
        Files.writeString(missingState.resolve("project.json"), "{}");
        assertThrows(IOException.class, () -> AssetTransactionCoordinator.open(missingState, GSON));
    }

    @Test
    void committedHistoryRejectsRehashedLiveResourceTamperingWithMatchingPhysicalBytes() throws Exception {
        Path root = tempDir.resolve("history-live-tamper-assets");
        AssetTransactionCoordinator.AssetKey key = new AssetTransactionCoordinator.AssetKey("flow", "history-live");
        try (AssetTransactionCoordinator coordinator = AssetTransactionCoordinator.open(root, GSON)) {
            coordinator.transact(request("81000000-0000-4000-8000-000000000001",
                coordinator.read(AssetTransactionCoordinator.Snapshot::project),
                AssetTransactionCoordinator.AssetDelta.write(key, Path.of("live.json"),
                    AssetTransactionCoordinator.Missing.INSTANCE, "original".getBytes(StandardCharsets.UTF_8))));
        }
        byte[] tampered = "tampered".getBytes(StandardCharsets.UTF_8);
        Files.write(root.resolve("live.json"), tampered);
        Path stateFile = root.resolve(".asset-coordinator/state.json");
        JsonObject state = JsonParser.parseString(Files.readString(stateFile)).getAsJsonObject();
        state.getAsJsonArray("resources").get(0).getAsJsonObject().getAsJsonObject("state")
            .addProperty("hash", StorageSafety.sha256(tampered));
        writeRehashedState(stateFile, state);

        assertThrows(IOException.class, () -> AssetTransactionCoordinator.open(root, GSON));
    }

    @Test
    void committedHistoryRejectsRehashedProjectTamperingWithMatchingPhysicalBytes() throws Exception {
        Path root = tempDir.resolve("history-project-tamper-assets");
        try (AssetTransactionCoordinator coordinator = AssetTransactionCoordinator.open(root, GSON)) {
            coordinator.transact(new AssetTransactionCoordinator.TransactionRequest(
                UUID.fromString("82000000-0000-4000-8000-000000000001"),
                coordinator.read(AssetTransactionCoordinator.Snapshot::project), List.of(),
                List.of(AssetTransactionCoordinator.ProjectDelta.set(List.of("serverId"),
                    JsonParser.parseString("\"original\"")))));
        }
        byte[] projectBytes = "{\"serverId\":\"tampered\"}".getBytes(StandardCharsets.UTF_8);
        Files.write(root.resolve("project.json"), projectBytes);
        String projectHash = StorageSafety.sha256(projectBytes);
        Path stateFile = root.resolve(".asset-coordinator/state.json");
        JsonObject state = JsonParser.parseString(Files.readString(stateFile)).getAsJsonObject();
        state.getAsJsonObject("project").addProperty("hash", projectHash);
        state.getAsJsonArray("mutations").get(0).getAsJsonObject().getAsJsonObject("result")
            .getAsJsonObject("project").addProperty("hash", projectHash);
        writeRehashedState(stateFile, state);

        assertThrows(IOException.class, () -> AssetTransactionCoordinator.open(root, GSON));
    }

    @Test
    void committedHistoryRejectsRehashedReplayResultTampering() throws Exception {
        Path root = tempDir.resolve("history-replay-tamper-assets");
        AssetTransactionCoordinator.AssetKey key = new AssetTransactionCoordinator.AssetKey("flow", "history-replay");
        try (AssetTransactionCoordinator coordinator = AssetTransactionCoordinator.open(root, GSON)) {
            coordinator.transact(request("83000000-0000-4000-8000-000000000001",
                coordinator.read(AssetTransactionCoordinator.Snapshot::project),
                AssetTransactionCoordinator.AssetDelta.write(key, Path.of("replay.json"),
                    AssetTransactionCoordinator.Missing.INSTANCE, "original".getBytes(StandardCharsets.UTF_8))));
        }
        Path stateFile = root.resolve(".asset-coordinator/state.json");
        JsonObject state = JsonParser.parseString(Files.readString(stateFile)).getAsJsonObject();
        state.getAsJsonArray("mutations").get(0).getAsJsonObject().getAsJsonObject("result")
            .getAsJsonArray("states").get(0).getAsJsonObject().getAsJsonObject("state")
            .addProperty("hash", "0".repeat(64));
        writeRehashedState(stateFile, state);

        assertThrows(IOException.class, () -> AssetTransactionCoordinator.open(root, GSON));
    }

    @Test
    void activeHistoryCacheInvalidatesWhenCommittedEvidenceChanges() throws Exception {
        Path root = tempDir.resolve("history-cache-tamper-assets");
        AssetTransactionCoordinator.AssetKey key = new AssetTransactionCoordinator.AssetKey("flow", "history-cache");
        try (AssetTransactionCoordinator coordinator = AssetTransactionCoordinator.open(root, GSON)) {
            coordinator.transact(request("84000000-0000-4000-8000-000000000001",
                coordinator.read(AssetTransactionCoordinator.Snapshot::project),
                AssetTransactionCoordinator.AssetDelta.write(key, Path.of("history-cache.json"),
                    AssetTransactionCoordinator.Missing.INSTANCE, "original".getBytes(StandardCharsets.UTF_8))));
            coordinator.healthCheck();

            Path transaction;
            try (var transactions = Files.list(root.resolve(".transactions"))) {
                transaction = transactions.filter(Files::isDirectory).findFirst().orElseThrow();
            }
            JsonObject journal = JsonParser.parseString(Files.readString(transaction.resolve("journal.json"))).getAsJsonObject();
            String stagedName = journal.getAsJsonArray("entries").asList().stream()
                .map(JsonElement::getAsJsonObject)
                .filter(entry -> entry.get("target").getAsString().equals("history-cache.json"))
                .map(entry -> entry.get("staged").getAsString())
                .findFirst().orElseThrow();
            Path staged = transaction.resolve(stagedName);
            FileTime modified = Files.getLastModifiedTime(staged);
            Files.writeString(staged, "tampered");
            Files.setLastModifiedTime(staged, FileTime.fromMillis(modified.toMillis() + 2_000L));

            assertThrows(IOException.class, coordinator::healthCheck);
        }
    }

    @Test
    void normalizedIntentOrderingIsIndependentOfDeltaOrder() throws Exception {
        Path leftRoot = tempDir.resolve("ordered-left");
        Path rightRoot = tempDir.resolve("ordered-right");
        UUID mutationId = UUID.fromString("90000000-0000-4000-8000-000000000001");
        AssetTransactionCoordinator.AssetKey firstKey = new AssetTransactionCoordinator.AssetKey("flow", "first");
        AssetTransactionCoordinator.AssetKey secondKey = new AssetTransactionCoordinator.AssetKey("function", "second");
        String leftHash;
        String rightHash;
        try (AssetTransactionCoordinator coordinator = AssetTransactionCoordinator.open(leftRoot, GSON)) {
            AssetTransactionCoordinator.ExpectedProject project = coordinator.read(AssetTransactionCoordinator.Snapshot::project);
            leftHash = coordinator.transact(new AssetTransactionCoordinator.TransactionRequest(mutationId, project,
                List.of(AssetTransactionCoordinator.AssetDelta.write(secondKey, Path.of("b.json"), AssetTransactionCoordinator.Missing.INSTANCE,
                        "b".getBytes(StandardCharsets.UTF_8)),
                    AssetTransactionCoordinator.AssetDelta.write(firstKey, Path.of("a.json"), AssetTransactionCoordinator.Missing.INSTANCE,
                        "a".getBytes(StandardCharsets.UTF_8))),
                List.of(AssetTransactionCoordinator.ProjectDelta.set(List.of("z"), JsonParser.parseString("1")),
                    AssetTransactionCoordinator.ProjectDelta.set(List.of("a"), JsonParser.parseString("2"))))).intentHash();
        }
        try (AssetTransactionCoordinator coordinator = AssetTransactionCoordinator.open(rightRoot, GSON)) {
            AssetTransactionCoordinator.ExpectedProject project = coordinator.read(AssetTransactionCoordinator.Snapshot::project);
            rightHash = coordinator.transact(new AssetTransactionCoordinator.TransactionRequest(mutationId, project,
                List.of(AssetTransactionCoordinator.AssetDelta.write(firstKey, Path.of("a.json"), AssetTransactionCoordinator.Missing.INSTANCE,
                        "a".getBytes(StandardCharsets.UTF_8)),
                    AssetTransactionCoordinator.AssetDelta.write(secondKey, Path.of("b.json"), AssetTransactionCoordinator.Missing.INSTANCE,
                        "b".getBytes(StandardCharsets.UTF_8))),
                List.of(AssetTransactionCoordinator.ProjectDelta.set(List.of("a"), JsonParser.parseString("2")),
                    AssetTransactionCoordinator.ProjectDelta.set(List.of("z"), JsonParser.parseString("1"))))).intentHash();
        }
        assertEquals(leftHash, rightHash);
    }

    @Test
    void closeWaitsForListenerDeliveryAndFinalCloseReleasesTheOsLock() throws Exception {
        Path root = tempDir.resolve("close-assets");
        AssetTransactionCoordinator first = AssetTransactionCoordinator.open(root, GSON);
        AssetTransactionCoordinator second = AssetTransactionCoordinator.open(root, GSON);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        first.addListener(result -> {
            entered.countDown();
            try {
                if (!release.await(5L, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Listener release timed out");
                }
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(failure);
            }
        });
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<?> transaction = executor.submit(() -> {
                return first.transact(new AssetTransactionCoordinator.TransactionRequest(
                    UUID.fromString("a0000000-0000-4000-8000-000000000001"),
                    first.read(AssetTransactionCoordinator.Snapshot::project), List.of(),
                    List.of(AssetTransactionCoordinator.ProjectDelta.set(List.of("serverId"), JsonParser.parseString("\"project\"")))));
            });
            assertTrue(entered.await(5L, TimeUnit.SECONDS));
            Future<?> closing = executor.submit(() -> {
                first.close();
                return null;
            });
            try {
                assertThrows(TimeoutException.class, () -> closing.get(50L, TimeUnit.MILLISECONDS));
            } finally {
                release.countDown();
            }
            transaction.get();
            closing.get();
        }

        Path lockPath = root.resolve(".asset-coordinator/root.lock");
        try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.WRITE)) {
            assertThrows(OverlappingFileLockException.class, channel::tryLock);
            second.close();
            try (FileLock lock = channel.tryLock()) {
                assertNotNull(lock);
            }
        } finally {
            second.close();
        }
    }

    @Test
    void listenersKeepRegistrationOrderAndClosedRegistrationsReceiveNothing() throws Exception {
        Path root = tempDir.resolve("listener-order-assets");
        List<Integer> order = new ArrayList<>();
        try (AssetTransactionCoordinator coordinator = AssetTransactionCoordinator.open(root, GSON)) {
            coordinator.addListener(result -> order.add(1));
            AssetTransactionCoordinator.ListenerRegistration removed = coordinator.addListener(result -> order.add(2));
            coordinator.addListener(result -> order.add(3));
            removed.close();

            coordinator.transact(new AssetTransactionCoordinator.TransactionRequest(
                UUID.fromString("a1000000-0000-4000-8000-000000000001"),
                coordinator.read(AssetTransactionCoordinator.Snapshot::project), List.of(),
                List.of(AssetTransactionCoordinator.ProjectDelta.set(List.of("serverId"), JsonParser.parseString("\"project\"")))));

            assertEquals(List.of(1, 3), order);
        }
    }

    @Test
    void listenerTriggeredFinalCloseDefersOsLockReleaseUntilDeliveryCompletes() throws Exception {
        Path root = tempDir.resolve("listener-close-assets");
        AssetTransactionCoordinator coordinator = AssetTransactionCoordinator.open(root, GSON);
        AtomicInteger lockChecks = new AtomicInteger();
        coordinator.addListener(result -> {
            try {
                coordinator.close();
            } catch (IOException failure) {
                throw new IllegalStateException(failure);
            }
        });
        coordinator.addListener(result -> {
            try (FileChannel channel = FileChannel.open(root.resolve(".asset-coordinator/root.lock"), StandardOpenOption.WRITE)) {
                assertThrows(OverlappingFileLockException.class, channel::tryLock);
                lockChecks.incrementAndGet();
            } catch (IOException failure) {
                throw new IllegalStateException(failure);
            }
        });

        coordinator.transact(new AssetTransactionCoordinator.TransactionRequest(
            UUID.fromString("a2000000-0000-4000-8000-000000000001"),
            coordinator.read(AssetTransactionCoordinator.Snapshot::project), List.of(),
            List.of(AssetTransactionCoordinator.ProjectDelta.set(List.of("serverId"), JsonParser.parseString("\"project\"")))));

        assertEquals(1, lockChecks.get());
        try (FileChannel channel = FileChannel.open(root.resolve(".asset-coordinator/root.lock"), StandardOpenOption.WRITE);
             FileLock lock = channel.tryLock()) {
            assertNotNull(lock);
        } finally {
            coordinator.close();
        }
    }

    @Test
    void semanticallyTamperedPreparedBindingFailsBeforeRecoveryCanApplyIt() throws Exception {
        Path root = tempDir.resolve("prepared-assets");
        AssetTransactionCoordinator.AssetKey key = new AssetTransactionCoordinator.AssetKey("flow", "prepared");
        try (AssetTransactionCoordinator coordinator = AssetTransactionCoordinator.open(root, GSON)) {
            coordinator.transact(request("b0000000-0000-4000-8000-000000000001",
                coordinator.read(AssetTransactionCoordinator.Snapshot::project),
                AssetTransactionCoordinator.AssetDelta.write(key, Path.of("prepared.json"),
                    AssetTransactionCoordinator.Missing.INSTANCE, "committed".getBytes(StandardCharsets.UTF_8))));
        }
        Path transaction;
        try (var transactions = Files.list(root.resolve(".transactions"))) {
            transaction = transactions.filter(Files::isDirectory).findFirst().orElseThrow();
        }
        Path journalFile = transaction.resolve("journal.json");
        JsonObject journal = JsonParser.parseString(Files.readString(journalFile)).getAsJsonObject();
        journal.addProperty("state", "PREPARED");
        JsonArray entries = journal.getAsJsonArray("entries");
        JsonObject binding = null;
        for (var element : entries) {
            JsonObject entry = element.getAsJsonObject();
            if (entry.get("target").getAsString().startsWith(".asset-coordinator/bindings/")) {
                binding = entry;
                break;
            }
        }
        assertNotNull(binding);
        Path stagedBinding = transaction.resolve(binding.get("staged").getAsString());
        JsonObject bindingDocument = JsonParser.parseString(Files.readString(stagedBinding)).getAsJsonObject();
        JsonObject intent = bindingDocument.getAsJsonObject("intent");
        intent.getAsJsonArray("assets").get(0).getAsJsonObject().addProperty("operation", "UPSERT");
        String intentHash = StorageSafety.sha256(AssetProjectMetadata.of(intent).canonicalJson());
        bindingDocument.addProperty("intentHash", intentHash);
        JsonObject stateEntry = entries.asList().stream().map(element -> element.getAsJsonObject())
            .filter(entry -> entry.get("target").getAsString().equals(".asset-coordinator/state.json"))
            .findFirst().orElseThrow();
        Path stagedState = transaction.resolve(stateEntry.get("staged").getAsString());
        JsonObject stateDocument = JsonParser.parseString(Files.readString(stagedState)).getAsJsonObject();
        JsonObject mutation = stateDocument.getAsJsonArray("mutations").get(0).getAsJsonObject();
        mutation.addProperty("intentHash", intentHash);
        mutation.getAsJsonObject("result").addProperty("intentHash", intentHash);
        stateDocument.remove("stateHash");
        stateDocument.addProperty("stateHash", StorageSafety.sha256(AssetProjectMetadata.of(stateDocument).canonicalJson()));
        byte[] stateBytes = stateDocument.toString().getBytes(StandardCharsets.UTF_8);
        Files.write(stagedState, stateBytes);
        stateEntry.addProperty("hash", StorageSafety.sha256(stateBytes));
        stateEntry.addProperty("size", stateBytes.length);
        JsonArray baseOperations = bindingDocument.getAsJsonArray("baseOperations");
        baseOperations.asList().stream().map(element -> element.getAsJsonObject())
            .filter(operation -> operation.get("resource").getAsString().equals(".asset-coordinator/state.json"))
            .findFirst().orElseThrow().addProperty("payloadHash", StorageSafety.sha256(stateBytes));
        baseOperations.asList().stream().map(element -> element.getAsJsonObject())
            .filter(operation -> operation.get("resource").getAsString().equals(".asset-coordinator/state.json"))
            .findFirst().orElseThrow().addProperty("payloadSize", stateBytes.length);
        bindingDocument.addProperty("baseFingerprint", descriptorFingerprint(baseOperations));
        byte[] bindingBytes = bindingDocument.toString().getBytes(StandardCharsets.UTF_8);
        Files.write(stagedBinding, bindingBytes);
        binding.addProperty("hash", StorageSafety.sha256(bindingBytes));
        binding.addProperty("size", bindingBytes.length);
        List<JsonObject> sortedEntries = new ArrayList<>();
        entries.forEach(element -> sortedEntries.add(element.getAsJsonObject()));
        sortedEntries.sort(Comparator.comparing(entry -> entry.get("target").getAsString()));
        JsonArray operations = new JsonArray();
        for (JsonObject entry : sortedEntries) {
            JsonObject operation = new JsonObject();
            operation.addProperty("resource", entry.get("target").getAsString());
            operation.addProperty("operation", entry.get("operation").getAsString());
            operation.addProperty("payloadHash", entry.get("hash").getAsString());
            operation.addProperty("payloadSize", entry.get("size").getAsLong());
            operations.add(operation);
        }
        journal.addProperty("fingerprint", descriptorFingerprint(operations));
        Files.writeString(journalFile, journal.toString());
        Files.delete(root.resolve("prepared.json"));

        assertThrows(IOException.class, () -> AssetTransactionCoordinator.open(root, GSON));
        assertTrue(Files.notExists(root.resolve("prepared.json")));
    }

    @Test
    void preparedJournalRejectsAnUnexpectedPhysicalThirdState() throws Exception {
        Path root = tempDir.resolve("prepared-third-state-assets");
        AssetTransactionCoordinator.AssetKey key = new AssetTransactionCoordinator.AssetKey("flow", "prepared-third-state");
        try (AssetTransactionCoordinator coordinator = AssetTransactionCoordinator.open(root, GSON)) {
            coordinator.transact(request("b1000000-0000-4000-8000-000000000001",
                coordinator.read(AssetTransactionCoordinator.Snapshot::project),
                AssetTransactionCoordinator.AssetDelta.write(key, Path.of("prepared.json"),
                    AssetTransactionCoordinator.Missing.INSTANCE, "committed".getBytes(StandardCharsets.UTF_8))));
        }
        Path transaction;
        try (var transactions = Files.list(root.resolve(".transactions"))) {
            transaction = transactions.filter(Files::isDirectory).findFirst().orElseThrow();
        }
        Path journalFile = transaction.resolve("journal.json");
        JsonObject journal = JsonParser.parseString(Files.readString(journalFile)).getAsJsonObject();
        journal.addProperty("state", "PREPARED");
        Files.writeString(journalFile, journal.toString());
        Files.writeString(root.resolve("prepared.json"), "third-state");

        assertThrows(IOException.class, () -> AssetTransactionCoordinator.open(root, GSON));
        assertEquals("third-state", Files.readString(root.resolve("prepared.json")));
    }

    @Test
    void preparedJournalWithRogueEvidenceFailsBeforeApplyingAnything() throws Exception {
        Path root = tempDir.resolve("prepared-rogue-assets");
        AssetTransactionCoordinator.AssetKey key = new AssetTransactionCoordinator.AssetKey("flow", "prepared-rogue");
        try (AssetTransactionCoordinator coordinator = AssetTransactionCoordinator.open(root, GSON)) {
            coordinator.transact(request("b2000000-0000-4000-8000-000000000001",
                coordinator.read(AssetTransactionCoordinator.Snapshot::project),
                AssetTransactionCoordinator.AssetDelta.write(key, Path.of("prepared.json"),
                    AssetTransactionCoordinator.Missing.INSTANCE, "committed".getBytes(StandardCharsets.UTF_8))));
        }
        Path journalFile = onlyJournal(root);
        JsonObject journal = JsonParser.parseString(Files.readString(journalFile)).getAsJsonObject();
        journal.addProperty("state", "PREPARED");
        Files.writeString(journalFile, journal.toString());
        Files.delete(root.resolve("prepared.json"));
        Files.writeString(root.resolve("rogue.json"), "rogue");

        assertThrows(IOException.class, () -> AssetTransactionCoordinator.open(root, GSON));
        assertTrue(Files.notExists(root.resolve("prepared.json")));
        assertEquals("PREPARED", JsonParser.parseString(Files.readString(journalFile)).getAsJsonObject()
            .get("state").getAsString());
    }

    @Test
    void rogueInternalArtifactsFailClosed() throws Exception {
        Path snapshotRoot = tempDir.resolve("rogue-snapshot-assets");
        try (AssetTransactionCoordinator ignored = AssetTransactionCoordinator.open(snapshotRoot, GSON)) {
        }
        Files.writeString(Files.createDirectories(snapshotRoot.resolve(".snapshots/rogue")).resolve("content.bin"), "rogue");
        assertThrows(IOException.class, () -> AssetTransactionCoordinator.open(snapshotRoot, GSON));

        Path bindingRoot = tempDir.resolve("rogue-binding-assets");
        try (AssetTransactionCoordinator ignored = AssetTransactionCoordinator.open(bindingRoot, GSON)) {
        }
        Files.writeString(bindingRoot.resolve(".asset-coordinator/bindings/rogue.json"), "{}");
        assertThrows(IOException.class, () -> AssetTransactionCoordinator.open(bindingRoot, GSON));
    }

    @Test
    void activeDeferredRecoveryRejectsRogueEvidenceWithoutApplying() throws Exception {
        Path root = tempDir.resolve("active-prepared-rogue-assets");
        AssetTransactionCoordinator.AssetKey key = new AssetTransactionCoordinator.AssetKey("flow", "active-prepared");
        try (AssetTransactionCoordinator coordinator = AssetTransactionCoordinator.open(root, GSON)) {
            coordinator.transact(request("b3000000-0000-4000-8000-000000000001",
                coordinator.read(AssetTransactionCoordinator.Snapshot::project),
                AssetTransactionCoordinator.AssetDelta.write(key, Path.of("prepared.json"),
                    AssetTransactionCoordinator.Missing.INSTANCE, "committed".getBytes(StandardCharsets.UTF_8))));
            Path journalFile = onlyJournal(root);
            JsonObject journal = JsonParser.parseString(Files.readString(journalFile)).getAsJsonObject();
            journal.addProperty("state", "PREPARED");
            Files.writeString(journalFile, journal.toString());
            Files.writeString(root.resolve("rogue.json"), "rogue");

            assertThrows(IOException.class, () -> coordinator.transact(new AssetTransactionCoordinator.TransactionRequest(
                UUID.fromString("b3000000-0000-4000-8000-000000000002"),
                coordinator.read(AssetTransactionCoordinator.Snapshot::project), List.of(),
                List.of(AssetTransactionCoordinator.ProjectDelta.set(List.of("serverId"), JsonParser.parseString("\"blocked\""))))));
            assertEquals("PREPARED", JsonParser.parseString(Files.readString(journalFile)).getAsJsonObject()
                .get("state").getAsString());
        }
    }

    @Test
    void assetPathsRejectControlCharacters() throws Exception {
        Path root = tempDir.resolve("control-path-assets");
        try (AssetTransactionCoordinator coordinator = AssetTransactionCoordinator.open(root, GSON)) {
            AssetTransactionCoordinator.AssetKey key = new AssetTransactionCoordinator.AssetKey("flow", "control");
            assertThrows(IOException.class, () -> coordinator.transact(request("b4000000-0000-4000-8000-000000000001",
                coordinator.read(AssetTransactionCoordinator.Snapshot::project),
                AssetTransactionCoordinator.AssetDelta.write(key, Path.of("bad\u007f.json"),
                    AssetTransactionCoordinator.Missing.INSTANCE, new byte[]{1}))));
        }
    }

    @Test
    void explicitAdoptionPreservesOpaqueLineageDeletedEvidenceAndProjectBytes() throws Exception {
        Path root = tempDir.resolve("adopted-assets");
        Path livePath = root.resolve("Blueprints/Flows/live.json");
        Files.createDirectories(livePath.getParent());
        byte[] liveBytes = "legacy-live".getBytes(StandardCharsets.UTF_8);
        Files.write(livePath, liveBytes);
        String projectJson = "{ \"unknown\" : { \"keep\" : true }, \"resources\" : [] }";
        Files.writeString(root.resolve("project.json"), projectJson);
        AssetTransactionCoordinator.AssetKey liveKey = new AssetTransactionCoordinator.AssetKey("flow", "live");
        AssetTransactionCoordinator.AssetKey deletedKey = new AssetTransactionCoordinator.AssetKey("flow", "deleted");
        AssetTransactionCoordinator.AssetMutationId liveMutation = new AssetTransactionCoordinator.AssetMutationId("legacy:live:7");
        AssetTransactionCoordinator.AssetMutationId deletedMutation = new AssetTransactionCoordinator.AssetMutationId("legacy:deleted:9");
        String tombstoneHash = StorageSafety.sha256("legacy-tombstone");
        AssetTransactionCoordinator.LegacyDeletionProvenance deletion =
            new AssetTransactionCoordinator.LegacyDeletionProvenance(StorageSafety.sha256("prior"),
                Path.of("legacy/deleted.json"), Path.of("Blueprints/Flows/deleted.json"), tombstoneHash,
                "flow-storage", deletedKey.canonical(), 9L, deletedMutation);
        AssetTransactionCoordinator.AdoptionInventory inventory = new AssetTransactionCoordinator.AdoptionInventory(
            "replacement-manifest-v1", projectJson, List.of(
                new AssetTransactionCoordinator.AdoptedAsset(liveKey, Path.of("Blueprints/Flows/live.json"),
                    new AssetTransactionCoordinator.Live(7L, StorageSafety.sha256(liveBytes)), liveMutation, liveBytes),
                new AssetTransactionCoordinator.AdoptedAsset(deletedKey, Path.of("Blueprints/Flows/deleted.json"),
                    new AssetTransactionCoordinator.Deleted(9L, tombstoneHash), deletedMutation, new byte[0], deletion)),
            List.of(new AssetTransactionCoordinator.BlockedAdoption("flow-storage", "legacy/unknown.tombstone",
                StorageSafety.sha256("blocked-evidence"), 3L,
                new AssetTransactionCoordinator.AssetMutationId("legacy:blocked:3"), null,
                Path.of("legacy/unknown.json"), null, "Canonical identity is not authoritative")));
        AssetTransactionCoordinator.AdoptionBinding binding = new AssetTransactionCoordinator.AdoptionBinding(
            StorageSafety.sha256("artifact"), StorageSafety.sha256("manifest"));

        try (AssetTransactionCoordinator coordinator = AssetTransactionCoordinator.openOrAdopt(root, GSON, inventory, binding)) {
            assertEquals(liveMutation, coordinator.read(snapshot -> snapshot.lineage(liveKey).orElseThrow()));
            assertEquals(deletedMutation, coordinator.read(snapshot -> snapshot.lineage(deletedKey).orElseThrow()));
            boolean deletedMutationAbsent = coordinator.read(snapshot -> snapshot.mutationId(deletedKey).isEmpty());
            assertTrue(deletedMutationAbsent);
            int blockedCount = coordinator.read(snapshot -> snapshot.blocked().size());
            assertEquals(1, blockedCount);
            assertEquals(projectJson, Files.readString(root.resolve("project.json")));
        }
        Files.writeString(root.resolve("project.json"), "{\"resources\":[],\"unknown\":{\"keep\":true}}");
        assertThrows(IOException.class, () -> AssetTransactionCoordinator.openOrAdopt(root, GSON, inventory, binding));
        Files.writeString(root.resolve("project.json"), projectJson);
        try (AssetTransactionCoordinator reopened = AssetTransactionCoordinator.openOrAdopt(root, GSON, inventory, binding)) {
            long deletedRevision = reopened.read(snapshot -> snapshot.state(deletedKey).orElseThrow().revision());
            assertEquals(9L, deletedRevision);
        }
        assertThrows(IOException.class, () -> AssetTransactionCoordinator.openOrAdopt(root, GSON, inventory,
            new AssetTransactionCoordinator.AdoptionBinding(StorageSafety.sha256("different"),
                StorageSafety.sha256("manifest"))));
    }

    @Test
    void adoptionRejectsUnmanagedEvidenceBeforePublishingGenesis() throws Exception {
        Path root = tempDir.resolve("unmanaged-adoption-assets");
        Files.createDirectories(root.resolve("Blueprints"));
        Files.writeString(root.resolve("Blueprints/rogue.json"), "rogue");
        AssetTransactionCoordinator.AdoptionInventory inventory = new AssetTransactionCoordinator.AdoptionInventory(
            "replacement-manifest-v1", "{}", List.of());

        assertThrows(IOException.class, () -> AssetTransactionCoordinator.openOrAdopt(root, GSON, inventory,
            new AssetTransactionCoordinator.AdoptionBinding(StorageSafety.sha256("artifact"),
                StorageSafety.sha256("manifest"))));
        assertTrue(Files.notExists(root.resolve(".asset-coordinator/genesis.json")));
    }

    @Test
    void adoptionRetainsRuntimeTombstonesAndPreparesOnlyAfterDurableAuthority() throws Exception {
        Path root = tempDir.resolve("adopted-tombstone-assets");
        Path tombstone = root.resolve(".tombstones/flow/deleted.json");
        Path control = root.resolve(".migrations/legacy.bin");
        Path preparedControl = root.resolve(".migrations/generated/authority.json");
        Files.createDirectories(tombstone.getParent());
        Files.createDirectories(control.getParent());
        byte[] tombstoneBytes = "legacy-tombstone-bytes".getBytes(StandardCharsets.UTF_8);
        byte[] controlBytes = new byte[]{1, 2, 3, 4};
        Files.write(tombstone, tombstoneBytes);
        Files.write(control, controlBytes);
        AssetTransactionCoordinator.AssetKey primary = new AssetTransactionCoordinator.AssetKey("flow", "deleted");
        AssetTransactionCoordinator.AssetKey auxiliary = new AssetTransactionCoordinator.AssetKey("flow.tombstone", "deleted");
        AssetTransactionCoordinator.AssetMutationId mutation = new AssetTransactionCoordinator.AssetMutationId("legacy:delete:9");
        String tombstoneHash = StorageSafety.sha256(tombstoneBytes);
        AssetTransactionCoordinator.LegacyDeletionProvenance deletion =
            new AssetTransactionCoordinator.LegacyDeletionProvenance(null, Path.of("legacy/deleted.json"),
                Path.of("Blueprints/Flows/deleted.json"), tombstoneHash, "flow-storage",
                "assets/.tombstones/flow/deleted.json", 9L, mutation);
        AssetTransactionCoordinator.AdoptionInventory inventory = new AssetTransactionCoordinator.AdoptionInventory(
            "replacement-tombstone-v1", "{}", List.of(
                new AssetTransactionCoordinator.AdoptedAsset(primary, Path.of("Blueprints/Flows/deleted.json"),
                    new AssetTransactionCoordinator.Deleted(9L, tombstoneHash), mutation, new byte[0], deletion),
                new AssetTransactionCoordinator.AdoptedAsset(auxiliary, Path.of(".tombstones/flow/deleted.json"),
                    new AssetTransactionCoordinator.Live(9L, tombstoneHash), mutation, tombstoneBytes)),
            List.of(), List.of(
                new AssetTransactionCoordinator.AdoptionEvidence(".tombstones/flow/deleted.json", tombstoneHash,
                    tombstoneBytes.length),
                new AssetTransactionCoordinator.AdoptionEvidence(".migrations/legacy.bin",
                    StorageSafety.sha256(controlBytes), controlBytes.length)));
        AssetTransactionCoordinator.AdoptionBinding binding = new AssetTransactionCoordinator.AdoptionBinding(
            StorageSafety.sha256("tombstone-artifact"), StorageSafety.sha256("tombstone-manifest"));
        AtomicBoolean prepared = new AtomicBoolean();

        try (AssetTransactionCoordinator coordinator = AssetTransactionCoordinator.openOrAdopt(root, GSON, inventory, binding,
            canonicalRoot -> {
                prepared.set(true);
                assertTrue(Files.isRegularFile(canonicalRoot.resolve(".asset-coordinator/genesis.json")));
                assertTrue(Files.isRegularFile(canonicalRoot.resolve(".asset-coordinator/state.json")));
                Files.delete(canonicalRoot.resolve(".migrations/legacy.bin"));
                Files.createDirectories(preparedControl.getParent());
                Files.writeString(preparedControl, "authorized");
            })) {
            assertTrue(prepared.get());
            assertTrue(Files.isRegularFile(tombstone));
            assertEquals("authorized", Files.readString(preparedControl));
            boolean primaryDeleted = coordinator.read(snapshot -> snapshot.state(primary).orElseThrow() instanceof AssetTransactionCoordinator.Deleted);
            assertTrue(primaryDeleted);
            boolean auxiliaryLive = coordinator.read(snapshot -> snapshot.state(auxiliary).orElseThrow() instanceof AssetTransactionCoordinator.Live);
            assertTrue(auxiliaryLive);
        }

        try (AssetTransactionCoordinator reopened = AssetTransactionCoordinator.openOrAdopt(root, GSON, inventory, binding)) {
            assertTrue(Files.isRegularFile(tombstone));
            assertEquals(mutation, reopened.read(snapshot -> snapshot.lineage(auxiliary).orElseThrow()));
        }

        RecordingEvidenceObserver modifyObserver = new RecordingEvidenceObserver() {
            @Override
            public void beforeFinalFence(Path ignored) throws IOException {
                Files.writeString(preparedControl, "unauthorized");
            }
        };
        assertThrows(IOException.class, () -> AssetTransactionCoordinator.openObserved(root, GSON,
            Clock.systemUTC(), modifyObserver));
        Files.writeString(preparedControl, "authorized");

        RecordingEvidenceObserver deleteObserver = new RecordingEvidenceObserver() {
            @Override
            public void beforeFinalFence(Path ignored) throws IOException {
                Files.delete(preparedControl);
            }
        };
        assertThrows(IOException.class, () -> AssetTransactionCoordinator.openObserved(root, GSON,
            Clock.systemUTC(), deleteObserver));
        Files.writeString(preparedControl, "authorized");

        RecordingEvidenceObserver createObserver = new RecordingEvidenceObserver() {
            @Override
            public void beforeFinalFence(Path ignored) throws IOException {
                Files.writeString(preparedControl.getParent().resolve("unexpected.json"), "unauthorized");
            }
        };
        assertThrows(IOException.class, () -> AssetTransactionCoordinator.openObserved(root, GSON,
            Clock.systemUTC(), createObserver));
    }

    @Test
    void relocateAndTransformedReclassificationPreserveAtomicLineage() throws Exception {
        Path root = tempDir.resolve("relocate-reclassify-assets");
        AssetTransactionCoordinator.AssetKey sourceKey = new AssetTransactionCoordinator.AssetKey("legacy-flow", "main");
        AssetTransactionCoordinator.AssetKey targetKey = new AssetTransactionCoordinator.AssetKey("flow", "main");
        byte[] original = "legacy".getBytes(StandardCharsets.UTF_8);
        byte[] normalized = "normalized".getBytes(StandardCharsets.UTF_8);
        byte[] transformed = "typed".getBytes(StandardCharsets.UTF_8);
        try (AssetTransactionCoordinator coordinator = AssetTransactionCoordinator.open(root, GSON)) {
            AssetTransactionCoordinator.ExpectedProject project = coordinator.read(AssetTransactionCoordinator.Snapshot::project);
            AssetTransactionCoordinator.Live created = (AssetTransactionCoordinator.Live) coordinator.transact(request(
                "c1000000-0000-4000-8000-000000000001", project,
                AssetTransactionCoordinator.AssetDelta.write(sourceKey, Path.of("legacy/main.json"),
                    AssetTransactionCoordinator.Missing.INSTANCE, original))).states().get(sourceKey);
            assertThrows(IllegalArgumentException.class, () -> new AssetTransactionCoordinator.AssetDelta(sourceKey,
                Path.of("canonical/main.json"), Path.of("legacy/main.json"), created,
                "changed".getBytes(StandardCharsets.UTF_8), false, null, null, false));
            AssetTransactionCoordinator.Live relocated = (AssetTransactionCoordinator.Live) coordinator.transact(request(
                "c1000000-0000-4000-8000-000000000002", project,
                AssetTransactionCoordinator.AssetDelta.relocate(sourceKey, Path.of("legacy/main.json"),
                    Path.of("canonical/main.json"), created, original))).states().get(sourceKey);

            assertTrue(Files.notExists(root.resolve("legacy/main.json")));
            assertEquals(root.resolve("canonical/main.json").toAbsolutePath().normalize(),
                coordinator.read(snapshot -> snapshot.path(sourceKey).orElseThrow()));
            AssetTransactionCoordinator.Live normalizedState = (AssetTransactionCoordinator.Live) coordinator.transact(request(
                "c1000000-0000-4000-8000-000000000004", project,
                AssetTransactionCoordinator.AssetDelta.relocateTransformed(sourceKey, Path.of("canonical/main.json"),
                    Path.of("normalized/main.json"), relocated, normalized))).states().get(sourceKey);
            AssetTransactionCoordinator.Reclassification reclassification = AssetTransactionCoordinator.Reclassification.of(
                sourceKey, Path.of("normalized/main.json"), normalizedState, targetKey, Path.of("typed/main.json"),
                AssetTransactionCoordinator.Missing.INSTANCE, transformed);
            AssetTransactionCoordinator.TransactionResult result = coordinator.transact(
                AssetTransactionCoordinator.TransactionRequest.reclassify(
                    UUID.fromString("c1000000-0000-4000-8000-000000000003"), project, reclassification,
                    List.of(AssetTransactionCoordinator.ProjectDelta.set(List.of("unknown", "keep"),
                        JsonParser.parseString("true")))));

            assertInstanceOf(AssetTransactionCoordinator.Deleted.class, result.states().get(sourceKey));
            assertEquals(normalizedState.revision() + 1L, result.states().get(targetKey).revision());
            assertEquals("typed", Files.readString(root.resolve("typed/main.json")));
            assertTrue(Files.notExists(root.resolve("normalized/main.json")));
            assertTrue(Files.readString(root.resolve("project.json")).contains("\"keep\":true"));
        }
    }

    @Test
    void preparedRelocationRejectsLossOfBothSourceAndTarget() throws Exception {
        Path root = tempDir.resolve("prepared-relocation-assets");
        AssetTransactionCoordinator.AssetKey key = new AssetTransactionCoordinator.AssetKey("flow", "move");
        byte[] content = "move".getBytes(StandardCharsets.UTF_8);
        UUID relocationId = UUID.fromString("c2000000-0000-4000-8000-000000000002");
        try (AssetTransactionCoordinator coordinator = AssetTransactionCoordinator.open(root, GSON)) {
            AssetTransactionCoordinator.ExpectedProject project = coordinator.read(AssetTransactionCoordinator.Snapshot::project);
            AssetTransactionCoordinator.Live live = (AssetTransactionCoordinator.Live) coordinator.transact(request(
                "c2000000-0000-4000-8000-000000000001", project,
                AssetTransactionCoordinator.AssetDelta.write(key, Path.of("source.json"),
                    AssetTransactionCoordinator.Missing.INSTANCE, content))).states().get(key);
            coordinator.transact(request(relocationId.toString(), project,
                AssetTransactionCoordinator.AssetDelta.relocate(key, Path.of("source.json"), Path.of("target.json"),
                    live, content)));
        }
        Path relocationJournal;
        try (var transactions = Files.list(root.resolve(".transactions"))) {
            relocationJournal = transactions.map(path -> path.resolve("journal.json")).filter(path -> {
                try {
                    return JsonParser.parseString(Files.readString(path)).getAsJsonObject().get("mutationId")
                        .getAsString().equals(relocationId.toString());
                } catch (IOException exception) {
                    throw new IllegalStateException(exception);
                }
            }).findFirst().orElseThrow();
        }
        JsonObject journal = JsonParser.parseString(Files.readString(relocationJournal)).getAsJsonObject();
        journal.addProperty("state", "PREPARED");
        Files.writeString(relocationJournal, journal.toString());
        Files.delete(root.resolve("target.json"));
        Files.delete(root.resolve(".snapshots").resolve(relocationJournal.getParent().getFileName()).resolve("source.json"));

        assertThrows(IOException.class, () -> AssetTransactionCoordinator.open(root, GSON));
        assertTrue(Files.notExists(root.resolve("source.json")));
        assertTrue(Files.notExists(root.resolve("target.json")));
    }

    @Test
    void successfulCommitsAdvanceTheTrustedTipWithoutReplayingHistory() throws Exception {
        Path root = tempDir.resolve("incremental-history-assets");
        try (AssetTransactionCoordinator coordinator = AssetTransactionCoordinator.open(root, GSON)) {
            AssetTransactionCoordinator.ValidationMetrics opened = coordinator.validationMetrics();
            AssetTransactionCoordinator.AssetKey first = new AssetTransactionCoordinator.AssetKey("flow", "first");
            coordinator.transact(request("d1000000-0000-4000-8000-000000000001",
                coordinator.read(AssetTransactionCoordinator.Snapshot::project),
                AssetTransactionCoordinator.AssetDelta.write(first, Path.of("first.json"),
                    AssetTransactionCoordinator.Missing.INSTANCE, "first".getBytes(StandardCharsets.UTF_8))));
            AssetTransactionCoordinator.ValidationMetrics committed = coordinator.validationMetrics();
            AssetTransactionCoordinator.AssetKey second = new AssetTransactionCoordinator.AssetKey("flow", "second");
            coordinator.transact(request("d1000000-0000-4000-8000-000000000002",
                coordinator.read(AssetTransactionCoordinator.Snapshot::project),
                AssetTransactionCoordinator.AssetDelta.write(second, Path.of("second.json"),
                    AssetTransactionCoordinator.Missing.INSTANCE, "second".getBytes(StandardCharsets.UTF_8))));
            AssetTransactionCoordinator.ValidationMetrics appended = coordinator.validationMetrics();

            assertEquals(opened.fullValidationPasses(), appended.fullValidationPasses());
            assertEquals(opened.historyEvidenceScans(), appended.historyEvidenceScans());
            assertEquals(opened.managerFullJournalPasses(), appended.managerFullJournalPasses());
            assertEquals(opened.managerJournalReads(), appended.managerJournalReads());
            assertEquals(opened.managerStagedPayloadReads(), appended.managerStagedPayloadReads());
            assertEquals(8L, appended.managerAssetPathCanonicalizations()
                - committed.managerAssetPathCanonicalizations());
            assertEquals(committed.incrementalValidationPasses() + 1L, appended.incrementalValidationPasses());
            assertEquals(committed.managerIndexedMutationLookups() + 1L, appended.managerIndexedMutationLookups());
            assertEquals(opened.watcherFullRegistrationPasses(), appended.watcherFullRegistrationPasses());
            assertEquals(committed.watcherIncrementalRegistrationPasses() + 1L,
                appended.watcherIncrementalRegistrationPasses());
            assertEquals(committed.watcherIncrementalDirectoryVisits() + 5L,
                appended.watcherIncrementalDirectoryVisits());
            assertEquals(committed.watcherRegisteredDirectories() + 3L,
                appended.watcherRegisteredDirectories());
            assertTrue(appended.watcherAcceptedEvidencePaths() <= 32L);
            assertEquals(opened.watcherInvalidations(), appended.watcherInvalidations());
            AssetTransactionCoordinator.PhaseTiming beforeTiming = committed.phaseTiming();
            AssetTransactionCoordinator.PhaseTiming afterTiming = appended.phaseTiming();
            assertEquals(beforeTiming.transactionAttemptCount() + 1L, afterTiming.transactionAttemptCount());
            assertEquals(beforeTiming.recoveryAttemptCount() + 1L, afterTiming.recoveryAttemptCount());
            assertEquals(beforeTiming.normalizationAttemptCount() + 1L, afterTiming.normalizationAttemptCount());
            assertEquals(beforeTiming.metadataValidationAttemptCount() + 1L,
                afterTiming.metadataValidationAttemptCount());
            assertEquals(beforeTiming.trackedAssetHashAttemptCount() + 1L,
                afterTiming.trackedAssetHashAttemptCount());
            assertEquals(beforeTiming.externalTraversalAttemptCount() + 1L,
                afterTiming.externalTraversalAttemptCount());
            assertEquals(beforeTiming.stateWorkAttemptCount() + 1L, afterTiming.stateWorkAttemptCount());
            assertEquals(beforeTiming.acceptedTipAttemptCount() + 1L, afterTiming.acceptedTipAttemptCount());
            assertTrue(afterTiming.transactionNanos() > beforeTiming.transactionNanos());
            assertTrue(afterTiming.recoveryNanos() > beforeTiming.recoveryNanos());
            assertTrue(afterTiming.normalizationNanos() > beforeTiming.normalizationNanos());
            assertTrue(afterTiming.metadataValidationNanos() > beforeTiming.metadataValidationNanos());
            assertTrue(afterTiming.trackedAssetHashNanos() > beforeTiming.trackedAssetHashNanos());
            assertTrue(afterTiming.externalTraversalNanos() > beforeTiming.externalTraversalNanos());
            assertTrue(afterTiming.stateWorkNanos() > beforeTiming.stateWorkNanos());
            assertTrue(afterTiming.acceptedTipNanos() > beforeTiming.acceptedTipNanos());
            assertTrue(afterTiming.residualNanos() >= beforeTiming.residualNanos());
            AssetTransactionManager.PhaseTiming beforeManager = committed.managerPhaseTiming();
            AssetTransactionManager.PhaseTiming afterManager = appended.managerPhaseTiming();
            assertEquals(beforeManager.commitAttemptCount() + 1L, afterManager.commitAttemptCount());
            assertEquals(beforeManager.prepareAttemptCount() + 1L, afterManager.prepareAttemptCount());
            assertEquals(beforeManager.applyAttemptCount() + 1L, afterManager.applyAttemptCount());
            assertEquals(beforeManager.journalDurabilityAttemptCount() + 2L,
                afterManager.journalDurabilityAttemptCount());
            assertTrue(afterManager.commitNanos() > beforeManager.commitNanos());
            assertTrue(afterManager.prepareNanos() > beforeManager.prepareNanos());
            assertTrue(afterManager.applyNanos() > beforeManager.applyNanos());
            assertTrue(afterManager.journalDurabilityNanos() > beforeManager.journalDurabilityNanos());
            assertTrue(afterManager.residualNanos() >= beforeManager.residualNanos());
        }
    }

    @Test
    void restartAcceptsTheExactHistoryCheckpointWithoutReadingRetainedJournals() throws Exception {
        Path root = tempDir.resolve("checkpoint-restart-assets");
        AssetTransactionCoordinator.AssetKey key = new AssetTransactionCoordinator.AssetKey("flow", "checkpoint");
        AssetTransactionCoordinator.TransactionRequest request;
        try (AssetTransactionCoordinator coordinator = AssetTransactionCoordinator.open(root, GSON)) {
            request = request("d2000000-0000-4000-8000-000000000001",
                coordinator.read(AssetTransactionCoordinator.Snapshot::project),
                AssetTransactionCoordinator.AssetDelta.write(key, Path.of("checkpoint.json"),
                    AssetTransactionCoordinator.Missing.INSTANCE, "checkpoint".getBytes(StandardCharsets.UTF_8)));
            coordinator.transact(request);
        }
        Path checkpointFile = root.resolve(".asset-coordinator/history-checkpoint.json");
        assertTrue(Files.isRegularFile(checkpointFile));
        JsonObject checkpoint = JsonParser.parseString(Files.readString(checkpointFile)).getAsJsonObject();
        assertEquals("asset-history-checkpoint-v2", checkpoint.get("format").getAsString());
        assertTrue(checkpoint.get("evidenceCount").getAsInt() > 0);
        assertEquals(64, checkpoint.get("evidenceHash").getAsString().length());
        assertFalse(checkpoint.has("evidence"));

        try (AssetTransactionCoordinator coordinator = AssetTransactionCoordinator.open(root, GSON)) {
            AssetTransactionCoordinator.ValidationMetrics metrics = coordinator.validationMetrics();
            assertEquals(0L, metrics.managerFullJournalPasses());
            assertEquals(0L, metrics.managerJournalReads());
            assertEquals(0L, metrics.managerStagedPayloadReads());
            assertEquals(0L, metrics.watcherFullDirectoryWalks());
            long evidenceDirectories = 0L;
            for (String directory : List.of(".transactions", ".snapshots", ".asset-coordinator")) {
                try (var paths = Files.walk(root.resolve(directory))) {
                    evidenceDirectories += paths.filter(Files::isDirectory).count();
                }
            }
            assertEquals(evidenceDirectories, metrics.watcherRegisteredDirectories());
            assertTrue(coordinator.transact(request).replay());
            assertEquals(1L, coordinator.committedSequence());
            AssetTransactionCoordinator.TransactionRequest next = request("d2000000-0000-4000-8000-000000000004",
                coordinator.read(AssetTransactionCoordinator.Snapshot::project),
                AssetTransactionCoordinator.AssetDelta.write(new AssetTransactionCoordinator.AssetKey("flow", "next"),
                    Path.of("next.json"), AssetTransactionCoordinator.Missing.INSTANCE,
                    "next".getBytes(StandardCharsets.UTF_8)));
            assertFalse(coordinator.transact(next).replay());
            assertEquals(2L, coordinator.committedSequence());
            assertTrue(coordinator.validationMetrics().managerIndexedMutationLookups() > 0L);
        }
    }

    @Test
    void legacyV1HistoryCheckpointRemainsReadable() throws Exception {
        JsonObject checkpoint = new JsonObject();
        checkpoint.addProperty("format", "asset-history-checkpoint-v1");
        checkpoint.addProperty("rootSequence", 0L);
        checkpoint.addProperty("stateHash", "0".repeat(64));
        checkpoint.addProperty("genesisHash", "1".repeat(64));
        checkpoint.add("committedIndex", new JsonArray());
        checkpoint.add("evidence", new JsonArray());
        checkpoint.addProperty("markerHash", StorageSafety.sha256(AssetProjectMetadata.of(checkpoint).canonicalJson()));
        byte[] bytes = AssetProjectMetadata.of(checkpoint).canonicalJson().getBytes(StandardCharsets.UTF_8);
        Method read = AssetTransactionCoordinator.class.getDeclaredMethod("readHistoryCheckpoint", byte[].class,
            Path.class);
        read.setAccessible(true);

        assertDoesNotThrow(() -> read.invoke(null, bytes, tempDir.resolve("history-checkpoint.json")));
    }

    @Test
    void checkpointHashRejectsRetainedEvidenceTamperingWithRestoredMetadata() throws Exception {
        Path root = tempDir.resolve("checkpoint-hash-assets");
        try (AssetTransactionCoordinator coordinator = AssetTransactionCoordinator.open(root, GSON)) {
            coordinator.transact(request("d2000000-0000-4000-8000-000000000005",
                coordinator.read(AssetTransactionCoordinator.Snapshot::project),
                AssetTransactionCoordinator.AssetDelta.write(new AssetTransactionCoordinator.AssetKey("flow", "hash"),
                    Path.of("hash.json"), AssetTransactionCoordinator.Missing.INSTANCE,
                    "hash".getBytes(StandardCharsets.UTF_8))));
        }
        Path journal;
        try (var transactions = Files.list(root.resolve(".transactions"))) {
            journal = transactions.filter(Files::isDirectory).map(path -> path.resolve("journal.json"))
                .findFirst().orElseThrow();
        }
        FileTime modified = Files.getLastModifiedTime(journal);
        byte[] original = Files.readAllBytes(journal);
        byte[] tampered = original.clone();
        tampered[tampered.length / 2] ^= 1;
        Files.write(journal, tampered);
        Files.setLastModifiedTime(journal, modified);

        assertThrows(IOException.class, () -> AssetTransactionCoordinator.open(root, GSON));
    }

    @Test
    void checkpointPromotionRejectsContentChangedAfterHashing() throws Exception {
        Path root = tempDir.resolve("checkpoint-promotion-assets");
        try (AssetTransactionCoordinator coordinator = AssetTransactionCoordinator.open(root, GSON)) {
            coordinator.transact(request("d2000000-0000-4000-8000-000000000007",
                coordinator.read(AssetTransactionCoordinator.Snapshot::project),
                AssetTransactionCoordinator.AssetDelta.write(new AssetTransactionCoordinator.AssetKey("flow", "promotion"),
                    Path.of("promotion.json"), AssetTransactionCoordinator.Missing.INSTANCE,
                    "promotion".getBytes(StandardCharsets.UTF_8))));
        }
        Method capture = AssetTransactionCoordinator.class.getDeclaredMethod("captureCheckpointEvidenceSnapshot", Path.class);
        capture.setAccessible(true);
        Object snapshot = capture.invoke(null, root);
        Class<?> watcherType = Class.forName(AssetTransactionCoordinator.class.getName() + "$EvidenceWatcher");
        Method namespace = watcherType.getDeclaredMethod("openNamespace", Path.class, snapshot.getClass());
        namespace.setAccessible(true);
        Method hash = AssetTransactionCoordinator.class.getDeclaredMethod("checkpointEvidence", Path.class, snapshot.getClass());
        hash.setAccessible(true);
        Method promote = watcherType.getDeclaredMethod("openTrusted", watcherType, snapshot.getClass(), Map.class);
        promote.setAccessible(true);
        Object watcher = namespace.invoke(null, root, snapshot);
        try (AutoCloseable ignored = (AutoCloseable) watcher) {
            Object trusted = hash.invoke(null, root, snapshot);
            Path journal;
            try (var transactions = Files.list(root.resolve(".transactions"))) {
                journal = transactions.filter(Files::isDirectory).map(path -> path.resolve("journal.json"))
                    .findFirst().orElseThrow();
            }
            FileTime modified = Files.getLastModifiedTime(journal);
            byte[] bytes = Files.readAllBytes(journal);
            bytes[bytes.length / 2] ^= 1;
            Files.write(journal, bytes);
            Files.setLastModifiedTime(journal, modified);
            awaitWatcherEvent(watcher);
            InvocationTargetException failure = assertThrows(InvocationTargetException.class,
                () -> promote.invoke(null, watcher, snapshot, trusted));
            assertInstanceOf(IOException.class, failure.getCause());
        }
    }

    @Test
    void checkpointRegistersRetainedSubdirectoriesForContentTampering() throws Exception {
        Path root = tempDir.resolve("checkpoint-watcher-assets");
        try (AssetTransactionCoordinator coordinator = AssetTransactionCoordinator.open(root, GSON)) {
            coordinator.transact(request("d2000000-0000-4000-8000-000000000006",
                coordinator.read(AssetTransactionCoordinator.Snapshot::project),
                AssetTransactionCoordinator.AssetDelta.write(new AssetTransactionCoordinator.AssetKey("flow", "watched"),
                    Path.of("watched.json"), AssetTransactionCoordinator.Missing.INSTANCE,
                    "watched".getBytes(StandardCharsets.UTF_8))));
        }
        Path journal;
        try (var transactions = Files.list(root.resolve(".transactions"))) {
            journal = transactions.filter(Files::isDirectory).map(path -> path.resolve("journal.json"))
                .findFirst().orElseThrow();
        }
        try (AssetTransactionCoordinator coordinator = AssetTransactionCoordinator.open(root, GSON)) {
            assertEquals(0L, coordinator.validationMetrics().watcherFullDirectoryWalks());
            FileTime modified = Files.getLastModifiedTime(journal);
            byte[] bytes = Files.readAllBytes(journal);
            bytes[bytes.length / 2] ^= 1;
            Files.write(journal, bytes);
            Files.setLastModifiedTime(journal, modified);
            Field contextField = AssetTransactionCoordinator.class.getDeclaredField("context");
            contextField.setAccessible(true);
            Object context = contextField.get(coordinator);
            Field watcherField = context.getClass().getDeclaredField("evidenceWatcher");
            watcherField.setAccessible(true);
            awaitWatcherEvent(watcherField.get(context));
            assertThrows(IOException.class, coordinator::healthCheck);
        }
    }

    private static void awaitWatcherEvent(Object watcher) throws Exception {
        Field serviceField = watcher.getClass().getDeclaredField("service");
        serviceField.setAccessible(true);
        WatchService service = (WatchService) serviceField.get(watcher);
        WatchKey queued = service.poll(5L, TimeUnit.SECONDS);
        assertNotNull(queued);
        assertTrue(queued.reset());
    }

    @Test
    void staleCheckpointFallsBackToFullValidationAndPublishesTheRecoveredTip() throws Exception {
        Path root = tempDir.resolve("checkpoint-fallback-assets");
        Path checkpoint = root.resolve(".asset-coordinator/history-checkpoint.json");
        byte[] stale;
        try (AssetTransactionCoordinator coordinator = AssetTransactionCoordinator.open(root, GSON)) {
            coordinator.transact(request("d2000000-0000-4000-8000-000000000002",
                coordinator.read(AssetTransactionCoordinator.Snapshot::project),
                AssetTransactionCoordinator.AssetDelta.write(new AssetTransactionCoordinator.AssetKey("flow", "first"),
                    Path.of("first.json"), AssetTransactionCoordinator.Missing.INSTANCE,
                    "first".getBytes(StandardCharsets.UTF_8))));
            stale = Files.readAllBytes(checkpoint);
            coordinator.transact(request("d2000000-0000-4000-8000-000000000003",
                coordinator.read(AssetTransactionCoordinator.Snapshot::project),
                AssetTransactionCoordinator.AssetDelta.write(new AssetTransactionCoordinator.AssetKey("flow", "second"),
                    Path.of("second.json"), AssetTransactionCoordinator.Missing.INSTANCE,
                    "second".getBytes(StandardCharsets.UTF_8))));
        }
        Files.write(checkpoint, stale);

        try (AssetTransactionCoordinator coordinator = AssetTransactionCoordinator.open(root, GSON)) {
            assertEquals(2L, coordinator.committedSequence());
            assertTrue(coordinator.validationMetrics().managerJournalReads() > 0L);
        }

        try (AssetTransactionCoordinator coordinator = AssetTransactionCoordinator.open(root, GSON)) {
            assertEquals(2L, coordinator.committedSequence());
            assertEquals(0L, coordinator.validationMetrics().managerJournalReads());
        }
    }

    @Test
    void failedTransactionsStillAttributeStartedPhaseAttempts() throws Exception {
        Path root = tempDir.resolve("failed-phase-timing-assets");
        try (AssetTransactionCoordinator coordinator = AssetTransactionCoordinator.open(root, GSON)) {
            AssetTransactionCoordinator.AssetKey key = new AssetTransactionCoordinator.AssetKey("flow", "rejected");
            coordinator.transact(request("d3000000-0000-4000-8000-000000000001",
                coordinator.read(AssetTransactionCoordinator.Snapshot::project),
                AssetTransactionCoordinator.AssetDelta.write(key, Path.of("rejected.json"),
                    AssetTransactionCoordinator.Missing.INSTANCE, "original".getBytes(StandardCharsets.UTF_8))));
            AssetTransactionCoordinator.ValidationMetrics before = coordinator.validationMetrics();
            AssetTransactionCoordinator.ExpectedProject project = coordinator.read(
                AssetTransactionCoordinator.Snapshot::project);

            assertThrows(AssetTransactionCoordinator.StateConflictException.class, () -> coordinator.transact(
                request("d3000000-0000-4000-8000-000000000002", project,
                    AssetTransactionCoordinator.AssetDelta.write(key, Path.of("rejected.json"),
                        AssetTransactionCoordinator.Missing.INSTANCE, "replacement".getBytes(StandardCharsets.UTF_8)))));

            AssetTransactionCoordinator.ValidationMetrics after = coordinator.validationMetrics();
            assertEquals(before.phaseTiming().transactionAttemptCount() + 1L,
                after.phaseTiming().transactionAttemptCount());
            assertEquals(before.phaseTiming().recoveryAttemptCount() + 1L,
                after.phaseTiming().recoveryAttemptCount());
            assertEquals(before.phaseTiming().normalizationAttemptCount() + 1L,
                after.phaseTiming().normalizationAttemptCount());
            assertEquals(before.phaseTiming().metadataValidationAttemptCount() + 1L,
                after.phaseTiming().metadataValidationAttemptCount());
            assertEquals(before.phaseTiming().externalTraversalAttemptCount() + 1L,
                after.phaseTiming().externalTraversalAttemptCount());
            assertEquals(before.phaseTiming().stateWorkAttemptCount() + 1L,
                after.phaseTiming().stateWorkAttemptCount());
            assertEquals(before.managerPhaseTiming().commitAttemptCount(),
                after.managerPhaseTiming().commitAttemptCount());
            assertTrue(after.phaseTiming().transactionNanos() > before.phaseTiming().transactionNanos());
            assertTrue(after.phaseTiming().stateWorkNanos() > before.phaseTiming().stateWorkNanos());
            assertTrue(after.phaseTiming().residualNanos() >= before.phaseTiming().residualNanos());
            assertEquals("original", Files.readString(root.resolve("rejected.json")));
        }
    }

    @Test
    void externalJournalChangesFailClosedWithoutRetrustingHistory() throws Exception {
        for (String variant : List.of("whitespace", "corrupt", "delete", "replace")) {
            Path root = tempDir.resolve("fresh-watcher-journal-" + variant);
            try (AssetTransactionCoordinator coordinator = AssetTransactionCoordinator.open(root, GSON)) {
                coordinator.transact(request(UUID.nameUUIDFromBytes(variant.getBytes(StandardCharsets.UTF_8)).toString(),
                    coordinator.read(AssetTransactionCoordinator.Snapshot::project),
                    AssetTransactionCoordinator.AssetDelta.write(new AssetTransactionCoordinator.AssetKey("flow", variant),
                        Path.of(variant + ".json"), AssetTransactionCoordinator.Missing.INSTANCE,
                        variant.getBytes(StandardCharsets.UTF_8))));
            }

            Path journal = onlyJournal(root);
            String original = Files.readString(journal);
            Path state = root.resolve(".asset-coordinator/state.json");
            byte[] stateBefore = Files.readAllBytes(state);
            try (AssetTransactionCoordinator coordinator = AssetTransactionCoordinator.open(root, GSON)) {
                assertTrue(coordinator.validationMetrics().watcherAcceptedEvidencePaths() > 0L);
                long validationsBefore = coordinator.validationMetrics().fullValidationPasses();
                switch (variant) {
                    case "whitespace" -> Files.writeString(journal, original + " ");
                    case "corrupt" -> {
                        JsonObject document = JsonParser.parseString(original).getAsJsonObject();
                        document.addProperty("state", "BROKEN");
                        Files.writeString(journal, document.toString());
                    }
                    case "delete" -> Files.delete(journal);
                    case "replace" -> StorageSafety.writeUtf8Atomic(journal, original);
                    default -> throw new IllegalStateException("Unknown journal mutation variant: " + variant);
                }

                assertNotNull(awaitHealthFailure(coordinator));
                assertThrows(IOException.class, coordinator::healthCheck);
                try (AssetTransactionCoordinator shared = AssetTransactionCoordinator.open(root, GSON)) {
                    assertThrows(IOException.class, shared::healthCheck);
                }
                assertEquals(validationsBefore, coordinator.validationMetrics().fullValidationPasses());
                assertArrayEquals(stateBefore, Files.readAllBytes(state));
                if (variant.equals("delete")) {
                    assertTrue(Files.notExists(journal));
                } else if (variant.equals("corrupt")) {
                    assertEquals("BROKEN", JsonParser.parseString(Files.readString(journal)).getAsJsonObject()
                        .get("state").getAsString());
                } else {
                    assertEquals(original, Files.readString(journal).stripTrailing());
                }
                if (variant.equals("whitespace")) {
                    assertThrows(IOException.class, () -> coordinator.transact(request(
                        "d1000000-0000-4000-8000-000000000004",
                        coordinator.read(AssetTransactionCoordinator.Snapshot::project),
                        AssetTransactionCoordinator.AssetDelta.write(
                            new AssetTransactionCoordinator.AssetKey("flow", "poisoned"), Path.of("poisoned.json"),
                            AssetTransactionCoordinator.Missing.INSTANCE, "poisoned".getBytes(StandardCharsets.UTF_8)))));
                    assertTrue(Files.notExists(root.resolve("poisoned.json")));
                }
            }
        }
    }

    @Test
    void delayedAuthorizedCommitEventsRemainAccepted() throws Exception {
        Path root = tempDir.resolve("authorized-watcher-events");
        try (AssetTransactionCoordinator coordinator = AssetTransactionCoordinator.open(root, GSON)) {
            for (int index = 0; index < 2; index++) {
                String id = "authorized-" + index;
                coordinator.transact(request(UUID.nameUUIDFromBytes(id.getBytes(StandardCharsets.UTF_8)).toString(),
                    coordinator.read(AssetTransactionCoordinator.Snapshot::project),
                    AssetTransactionCoordinator.AssetDelta.write(new AssetTransactionCoordinator.AssetKey("flow", id),
                        Path.of(id + ".json"), AssetTransactionCoordinator.Missing.INSTANCE,
                        id.getBytes(StandardCharsets.UTF_8))));
            }
            long validationsBefore = coordinator.validationMetrics().fullValidationPasses();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1L);
            while (System.nanoTime() < deadline) {
                coordinator.healthCheck();
                Thread.sleep(10L);
            }
            assertEquals(validationsBefore, coordinator.validationMetrics().fullValidationPasses());
        }
    }

    @Test
    void adoptionNeverRebaselinesAQueuedExternalJournalRewrite() throws Exception {
        Path root = tempDir.resolve("adoption-external-rewrite");
        AssetTransactionCoordinator.AdoptionInventory inventory = new AssetTransactionCoordinator.AdoptionInventory(
            "watcher-adoption-v1", "{}", List.of());
        AssetTransactionCoordinator.AdoptionBinding binding = new AssetTransactionCoordinator.AdoptionBinding(
            StorageSafety.sha256("watcher-adoption-artifact"), StorageSafety.sha256("watcher-adoption-manifest"));
        try (AssetTransactionCoordinator coordinator = AssetTransactionCoordinator.openOrAdopt(root, GSON, inventory,
            binding)) {
            coordinator.transact(request("d1000000-0000-4000-8000-000000000006",
                coordinator.read(AssetTransactionCoordinator.Snapshot::project),
                AssetTransactionCoordinator.AssetDelta.write(new AssetTransactionCoordinator.AssetKey("flow", "adoption"),
                    Path.of("adoption.json"), AssetTransactionCoordinator.Missing.INSTANCE,
                    "adoption".getBytes(StandardCharsets.UTF_8))));
            Path journal = onlyJournal(root);
            String original = Files.readString(journal);
            Path state = root.resolve(".asset-coordinator/state.json");
            byte[] stateBefore = Files.readAllBytes(state);
            Files.writeString(journal, original + " ");

            IOException rejected = null;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3L);
            while (rejected == null && System.nanoTime() < deadline) {
                try (AssetTransactionCoordinator ignored = AssetTransactionCoordinator.openOrAdopt(root, GSON,
                    inventory, binding)) {
                } catch (IOException failure) {
                    rejected = failure;
                }
                if (rejected == null) {
                    Thread.sleep(10L);
                }
            }

            assertNotNull(rejected);
            assertThrows(IOException.class, coordinator::healthCheck);
            assertArrayEquals(stateBefore, Files.readAllBytes(state));
            assertEquals(original, Files.readString(journal).stripTrailing());
        }
    }

    @Test
    void fullAdoptionValidationKeepsAuthorizedEvidenceAccepted() throws Exception {
        Path root = tempDir.resolve("refreshed-watcher-events");
        AssetTransactionCoordinator.AdoptionInventory inventory = new AssetTransactionCoordinator.AdoptionInventory(
            "watcher-refresh-v1", "{}", List.of());
        AssetTransactionCoordinator.AdoptionBinding binding = new AssetTransactionCoordinator.AdoptionBinding(
            StorageSafety.sha256("watcher-refresh-artifact"), StorageSafety.sha256("watcher-refresh-manifest"));
        try (AssetTransactionCoordinator coordinator = AssetTransactionCoordinator.openOrAdopt(root, GSON, inventory,
            binding)) {
            coordinator.transact(request("d1000000-0000-4000-8000-000000000005",
                coordinator.read(AssetTransactionCoordinator.Snapshot::project),
                AssetTransactionCoordinator.AssetDelta.write(new AssetTransactionCoordinator.AssetKey("flow", "refresh"),
                    Path.of("refresh.json"), AssetTransactionCoordinator.Missing.INSTANCE,
                    "refresh".getBytes(StandardCharsets.UTF_8))));
            try (AssetTransactionCoordinator shared = AssetTransactionCoordinator.openOrAdopt(root, GSON, inventory,
                binding)) {
                assertTrue(shared.validationMetrics().watcherAcceptedEvidencePaths() > 0L);
                long validationsBefore = shared.validationMetrics().fullValidationPasses();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1L);
                while (System.nanoTime() < deadline) {
                    shared.healthCheck();
                    Thread.sleep(10L);
                }
                assertEquals(validationsBefore, shared.validationMetrics().fullValidationPasses());
            }
        }
    }

    @Test
    void unchangedHealthCheckReusesTheTrustedHistoryTip() throws Exception {
        Path root = tempDir.resolve("health-history-assets");
        try (AssetTransactionCoordinator coordinator = AssetTransactionCoordinator.open(root, GSON)) {
            AssetTransactionCoordinator.AssetKey key = new AssetTransactionCoordinator.AssetKey("flow", "health");
            coordinator.transact(request("d2000000-0000-4000-8000-000000000001",
                coordinator.read(AssetTransactionCoordinator.Snapshot::project),
                AssetTransactionCoordinator.AssetDelta.write(key, Path.of("health.json"),
                    AssetTransactionCoordinator.Missing.INSTANCE, "health".getBytes(StandardCharsets.UTF_8))));
            AssetTransactionCoordinator.ValidationMetrics before = coordinator.validationMetrics();

            coordinator.healthCheck();

            AssetTransactionCoordinator.ValidationMetrics after = coordinator.validationMetrics();
            assertEquals(before.fullValidationPasses(), after.fullValidationPasses());
            assertEquals(before.incrementalValidationPasses(), after.incrementalValidationPasses());
            assertEquals(before.historyEvidenceScans(), after.historyEvidenceScans());
            assertEquals(before.managerFullJournalPasses(), after.managerFullJournalPasses());
            assertEquals(before.managerJournalReads(), after.managerJournalReads());
            assertEquals(before.managerStagedPayloadReads(), after.managerStagedPayloadReads());
            assertEquals(before.managerIndexedMutationLookups(), after.managerIndexedMutationLookups());
            assertEquals(before.managerAssetPathCanonicalizations(), after.managerAssetPathCanonicalizations());
            assertEquals(before.watcherFullRegistrationPasses(), after.watcherFullRegistrationPasses());
            assertEquals(before.watcherIncrementalRegistrationPasses(), after.watcherIncrementalRegistrationPasses());
            assertEquals(before.watcherIncrementalDirectoryVisits(), after.watcherIncrementalDirectoryVisits());
            assertEquals(before.watcherRegisteredDirectories(), after.watcherRegisteredDirectories());
            assertEquals(before.watcherAcceptedEvidencePaths(), after.watcherAcceptedEvidencePaths());
            assertEquals(before.watcherInvalidations(), after.watcherInvalidations());
        }
    }

    @Test
    void externalAssetTraversalSkipsInternalEvidenceWithoutSkippingManagedAssets() throws Exception {
        Path root = tempDir.resolve("bounded-health-assets");
        Path asset = root.resolve("Blueprints/Flows/main.json").toAbsolutePath().normalize();
        try (AssetTransactionCoordinator coordinator = AssetTransactionCoordinator.open(root, GSON)) {
            coordinator.transact(request("d3000000-0000-4000-8000-000000000001",
                coordinator.read(AssetTransactionCoordinator.Snapshot::project),
                AssetTransactionCoordinator.AssetDelta.write(new AssetTransactionCoordinator.AssetKey("flow", "main"),
                    Path.of("Blueprints/Flows/main.json"), AssetTransactionCoordinator.Missing.INSTANCE,
                    "payload".getBytes(StandardCharsets.UTF_8))));

            List<Path> traversed = AssetTransactionCoordinator.externalAssetPaths(root);

            assertTrue(traversed.contains(asset));
            assertFalse(traversed.stream().anyMatch(path -> path.startsWith(root.resolve(".transactions"))));
            assertFalse(traversed.stream().anyMatch(path -> path.startsWith(root.resolve(".snapshots"))));
            assertFalse(traversed.stream().anyMatch(path -> path.startsWith(root.resolve(".asset-coordinator"))));
            assertFalse(traversed.stream().anyMatch(path -> path.startsWith(root.resolve(".migrations"))));
            assertFalse(traversed.stream().anyMatch(path -> path.startsWith(root.resolve(".quarantine"))));

            Files.writeString(asset, "tampered");
            assertThrows(IOException.class, coordinator::healthCheck);
        }
    }

    @Test
    void onePassAssetValidationHashesEveryLiveAndRejectsMissingOrPresentDeletedAssets() throws Exception {
        Path root = tempDir.resolve("one-pass-validation-assets");
        AssetTransactionCoordinator.AssetKey first = new AssetTransactionCoordinator.AssetKey("flow", "first");
        AssetTransactionCoordinator.AssetKey second = new AssetTransactionCoordinator.AssetKey("flow", "second");
        Path firstPath = root.resolve("tree/first.json");
        Path secondPath = root.resolve("tree/second.json");
        try (AssetTransactionCoordinator coordinator = AssetTransactionCoordinator.open(root, GSON)) {
            coordinator.transact(new AssetTransactionCoordinator.TransactionRequest(
                UUID.fromString("d4000000-0000-4000-8000-000000000001"),
                coordinator.read(AssetTransactionCoordinator.Snapshot::project),
                List.of(
                    AssetTransactionCoordinator.AssetDelta.write(first, Path.of("tree/first.json"),
                        AssetTransactionCoordinator.Missing.INSTANCE, "first".getBytes(StandardCharsets.UTF_8)),
                    AssetTransactionCoordinator.AssetDelta.write(second, Path.of("tree/second.json"),
                        AssetTransactionCoordinator.Missing.INSTANCE, "second".getBytes(StandardCharsets.UTF_8))),
                List.of()));
            AssetTransactionCoordinator.ValidationMetrics before = coordinator.validationMetrics();

            coordinator.healthCheck();

            AssetTransactionCoordinator.ValidationMetrics after = coordinator.validationMetrics();
            assertEquals(before.phaseTiming().trackedAssetHashAttemptCount() + 2L,
                after.phaseTiming().trackedAssetHashAttemptCount());
            assertEquals(before.phaseTiming().externalTraversalAttemptCount() + 1L,
                after.phaseTiming().externalTraversalAttemptCount());

            Files.delete(firstPath);
            assertThrows(IOException.class, coordinator::healthCheck);
            Files.writeString(firstPath, "first");

            AssetTransactionCoordinator.ExpectedState secondState = coordinator.read(
                snapshot -> snapshot.state(second).orElseThrow());
            coordinator.transact(request("d4000000-0000-4000-8000-000000000002",
                coordinator.read(AssetTransactionCoordinator.Snapshot::project),
                AssetTransactionCoordinator.AssetDelta.delete(second, Path.of("tree/second.json"), secondState)));
            Files.writeString(secondPath, "unexpected");
            assertThrows(IOException.class, coordinator::healthCheck);
        }
    }

    @Test
    void onePassAssetValidationKeepsDuplicateAndInternalPathsClosed() throws Exception {
        Path root = tempDir.resolve("one-pass-path-safety-assets");
        try (AssetTransactionCoordinator coordinator = AssetTransactionCoordinator.open(root, GSON)) {
            AssetTransactionCoordinator.ExpectedProject project = coordinator.read(
                AssetTransactionCoordinator.Snapshot::project);
            assertThrows(IllegalArgumentException.class, () -> coordinator.transact(
                new AssetTransactionCoordinator.TransactionRequest(
                    UUID.fromString("d4000000-0000-4000-8000-000000000003"), project,
                    List.of(
                        AssetTransactionCoordinator.AssetDelta.write(
                            new AssetTransactionCoordinator.AssetKey("flow", "alias-one"),
                            Path.of("aliases/../same.json"), AssetTransactionCoordinator.Missing.INSTANCE,
                            "one".getBytes(StandardCharsets.UTF_8)),
                        AssetTransactionCoordinator.AssetDelta.write(
                            new AssetTransactionCoordinator.AssetKey("flow", "alias-two"), Path.of("same.json"),
                            AssetTransactionCoordinator.Missing.INSTANCE, "two".getBytes(StandardCharsets.UTF_8))),
                    List.of())));
            assertThrows(IOException.class, () -> coordinator.transact(request(
                "d4000000-0000-4000-8000-000000000004", project,
                AssetTransactionCoordinator.AssetDelta.write(
                    new AssetTransactionCoordinator.AssetKey("flow", "internal"),
                    Path.of(".transactions/forbidden.json"), AssetTransactionCoordinator.Missing.INSTANCE,
                    "forbidden".getBytes(StandardCharsets.UTF_8)))));
        }
    }

    @Test
    void onePassAssetValidationPreservesQuarantineSkippingForManagedAndUnregisteredFiles() throws Exception {
        Path root = tempDir.resolve("one-pass-quarantine-assets");
        try (AssetTransactionCoordinator coordinator = AssetTransactionCoordinator.open(root, GSON)) {
            coordinator.transact(request("d4000000-0000-4000-8000-000000000005",
                coordinator.read(AssetTransactionCoordinator.Snapshot::project),
                AssetTransactionCoordinator.AssetDelta.write(
                    new AssetTransactionCoordinator.AssetKey("flow", "quarantined"),
                    Path.of(".quarantine/managed.json"), AssetTransactionCoordinator.Missing.INSTANCE,
                    "managed".getBytes(StandardCharsets.UTF_8))));
            Files.writeString(root.resolve(".quarantine/unregistered.json"), "unregistered");

            coordinator.healthCheck();

            assertEquals("managed", Files.readString(root.resolve(".quarantine/managed.json")));
        }
    }

    @Test
    void boundedAssetTraversalStillRejectsUnregisteredExternalFiles() throws Exception {
        Path root = tempDir.resolve("bounded-unregistered-assets");
        try (AssetTransactionCoordinator coordinator = AssetTransactionCoordinator.open(root, GSON)) {
            Path rogue = root.resolve("Blueprints/Flows/rogue.json");
            Files.createDirectories(rogue.getParent());
            Files.writeString(rogue, "rogue");

            assertThrows(IOException.class, coordinator::healthCheck);
        }
    }

    @Test
    void boundedAssetTraversalStillRejectsExternalSymbolicLinks() throws Exception {
        Path root = tempDir.resolve("bounded-symlink-assets");
        Path outside = tempDir.resolve("outside.json");
        Files.writeString(outside, "outside");
        try (AssetTransactionCoordinator coordinator = AssetTransactionCoordinator.open(root, GSON)) {
            Path link = root.resolve("linked.json");
            try {
                Files.createSymbolicLink(link, outside);
            } catch (IOException | UnsupportedOperationException | SecurityException exception) {
                Assumptions.assumeTrue(false, "Symbolic links are unavailable");
                return;
            }

            assertThrows(IOException.class, coordinator::healthCheck);
        }
    }

    @Test
    void freshRootHistoryFenceNormalizesDirectorySizeEvidence() throws Exception {
        Path root = tempDir.resolve("fresh-history-fence-assets");

        try (AssetTransactionCoordinator coordinator = AssetTransactionCoordinator.openObserved(root, GSON,
            Clock.systemUTC(), new RecordingEvidenceObserver())) {
            assertEquals(0L, coordinator.read(AssetTransactionCoordinator.Snapshot::rootSequence));
            assertTrue(Files.isDirectory(root.resolve(".asset-coordinator")));
        }
    }

    @Test
    void startupFinalFenceNormalizesDirectoryMetadataButRejectsDirectoryReplacement() throws Exception {
        Path settlingRoot = tempDir.resolve("settling-directory-assets");
        try (AssetTransactionCoordinator ignored = AssetTransactionCoordinator.open(settlingRoot, GSON)) {
        }
        Path settlingDirectory = settlingRoot.resolve(".migrations/generated");
        Files.createDirectories(settlingDirectory);
        RecordingEvidenceObserver settlingObserver = new RecordingEvidenceObserver() {
            @Override
            public void beforeFinalFence(Path ignored) throws IOException {
                FileTime captured = Files.getLastModifiedTime(settlingDirectory);
                Files.setLastModifiedTime(settlingDirectory, FileTime.fromMillis(captured.toMillis() + 10_000L));
            }
        };
        try (AssetTransactionCoordinator ignored = AssetTransactionCoordinator.openObserved(settlingRoot, GSON,
            Clock.systemUTC(), settlingObserver)) {
            assertTrue(Files.isDirectory(settlingDirectory));
        }

        Path replacementRoot = tempDir.resolve("replaced-directory-assets");
        try (AssetTransactionCoordinator ignored = AssetTransactionCoordinator.open(replacementRoot, GSON)) {
        }
        Path replacedDirectory = replacementRoot.resolve(".migrations/generated");
        Files.createDirectories(replacedDirectory);
        RecordingEvidenceObserver replacementObserver = new RecordingEvidenceObserver() {
            @Override
            public void beforeFinalFence(Path ignored) throws IOException {
                Files.delete(replacedDirectory);
                Files.createDirectory(replacedDirectory);
            }
        };
        assertThrows(IOException.class, () -> AssetTransactionCoordinator.openObserved(replacementRoot, GSON,
            Clock.systemUTC(), replacementObserver));
    }

    @Test
    void startupEvidenceSnapshotAuthenticatesLivePayloadsAndControlsAtTheFinalFence() throws Exception {
        Path root = tempDir.resolve("startup-evidence-assets");
        Path asset = root.resolve("live.json").toAbsolutePath().normalize();
        try (AssetTransactionCoordinator coordinator = AssetTransactionCoordinator.open(root, GSON)) {
            coordinator.transact(request("e1000000-0000-4000-8000-000000000001",
                coordinator.read(AssetTransactionCoordinator.Snapshot::project),
                AssetTransactionCoordinator.AssetDelta.write(new AssetTransactionCoordinator.AssetKey("flow", "live"),
                    Path.of("live.json"), AssetTransactionCoordinator.Missing.INSTANCE,
                    "live".getBytes(StandardCharsets.UTF_8))));
        }
        Path journalPath = onlyJournal(root).toAbsolutePath().normalize();
        JsonObject journal = JsonParser.parseString(Files.readString(journalPath)).getAsJsonObject();
        long stagedWrites = journal.getAsJsonArray("entries").asList().stream()
            .map(JsonElement::getAsJsonObject).filter(entry -> !entry.get("delete").getAsBoolean()).count();
        List<Path> stagedPaths = journal.getAsJsonArray("entries").asList().stream()
            .map(JsonElement::getAsJsonObject).filter(entry -> !entry.get("delete").getAsBoolean())
            .map(entry -> journalPath.getParent().resolve(entry.get("staged").getAsString()).toAbsolutePath().normalize())
            .toList();
        List<Path> stagedControlPaths = journal.getAsJsonArray("entries").asList().stream()
            .map(JsonElement::getAsJsonObject).filter(entry -> !entry.get("delete").getAsBoolean())
            .filter(entry -> entry.get("target").getAsString().equals("project.json")
                || entry.get("target").getAsString().startsWith(".asset-coordinator/"))
            .map(entry -> journalPath.getParent().resolve(entry.get("staged").getAsString()).toAbsolutePath().normalize())
            .toList();
        List<Path> snapshotPaths = journal.getAsJsonArray("entries").asList().stream()
            .map(JsonElement::getAsJsonObject).filter(entry -> entry.get("existed").getAsBoolean())
            .map(entry -> root.resolve(".snapshots").resolve(journalPath.getParent().getFileName())
                .resolve(entry.get("target").getAsString()).toAbsolutePath().normalize())
            .toList();
        Path bindingPath = root.resolve(journal.getAsJsonArray("entries").asList().stream()
            .map(JsonElement::getAsJsonObject).map(entry -> entry.get("target").getAsString())
            .filter(target -> target.startsWith(".asset-coordinator/bindings/")).findFirst().orElseThrow())
            .toAbsolutePath().normalize();
        RecordingEvidenceObserver observer = new RecordingEvidenceObserver();

        try (AssetTransactionCoordinator coordinator = AssetTransactionCoordinator.openObserved(root, GSON,
            Clock.systemUTC(), observer)) {
            AssetTransactionCoordinator.ValidationMetrics metrics = coordinator.validationMetrics();
            assertEquals(1, observer.walks.get());
            assertEquals(2, observer.reads.get(asset).get());
            assertEquals(2, observer.reads.get(journalPath).get());
            stagedPaths.forEach(path -> assertEquals(stagedControlPaths.contains(path) ? 2 : 1,
                observer.reads.get(path).get()));
            snapshotPaths.forEach(path -> assertEquals(1, observer.reads.get(path).get()));
            assertEquals(2, observer.reads.get(bindingPath).get());
            assertTrue(observer.reads.values().stream().allMatch(count -> count.get() == 1 || count.get() == 2));
            assertEquals(1L, metrics.managerFullJournalPasses());
            assertEquals(1L, metrics.managerJournalReads());
            assertEquals(stagedWrites, metrics.managerStagedPayloadReads());
        }
    }

    @Test
    void startupFinalFenceRejectsLiveManagedAssetChange() throws Exception {
        Path root = tempDir.resolve("live-asset-final-fence");
        Path asset = root.resolve("live.json");
        try (AssetTransactionCoordinator coordinator = AssetTransactionCoordinator.open(root, GSON)) {
            coordinator.transact(request("e1000000-0000-4000-8000-000000000002",
                coordinator.read(AssetTransactionCoordinator.Snapshot::project),
                AssetTransactionCoordinator.AssetDelta.write(new AssetTransactionCoordinator.AssetKey("flow", "live"),
                    Path.of("live.json"), AssetTransactionCoordinator.Missing.INSTANCE,
                    "alpha".getBytes(StandardCharsets.UTF_8))));
        }
        FileTime originalTime = Files.getLastModifiedTime(asset);
        RecordingEvidenceObserver observer = new RecordingEvidenceObserver() {
            @Override
            public void beforeFinalFence(Path ignored) throws IOException {
                Files.writeString(asset, "omega");
                Files.setLastModifiedTime(asset, FileTime.fromMillis(originalTime.toMillis() + 10_000L));
            }
        };

        assertThrows(IOException.class, () -> AssetTransactionCoordinator.openObserved(root, GSON,
            Clock.systemUTC(), observer));
    }

    @Test
    void startupFinalFenceRejectsInterFileLiveAssetChange() throws Exception {
        Path root = tempDir.resolve("inter-file-final-fence");
        Path first = root.resolve("first.json").toAbsolutePath().normalize();
        Path second = root.resolve("second.json").toAbsolutePath().normalize();
        try (AssetTransactionCoordinator coordinator = AssetTransactionCoordinator.open(root, GSON)) {
            coordinator.transact(request("e1000000-0000-4000-8000-000000000003",
                coordinator.read(AssetTransactionCoordinator.Snapshot::project),
                AssetTransactionCoordinator.AssetDelta.write(new AssetTransactionCoordinator.AssetKey("flow", "first"),
                    Path.of("first.json"), AssetTransactionCoordinator.Missing.INSTANCE,
                    "alpha".getBytes(StandardCharsets.UTF_8))));
            coordinator.transact(request("e1000000-0000-4000-8000-000000000004",
                coordinator.read(AssetTransactionCoordinator.Snapshot::project),
                AssetTransactionCoordinator.AssetDelta.write(new AssetTransactionCoordinator.AssetKey("flow", "second"),
                    Path.of("second.json"), AssetTransactionCoordinator.Missing.INSTANCE,
                    "bravo".getBytes(StandardCharsets.UTF_8))));
        }
        AtomicBoolean armed = new AtomicBoolean();
        AtomicBoolean changed = new AtomicBoolean();
        RecordingEvidenceObserver observer = new RecordingEvidenceObserver() {
            @Override
            public void beforeFinalFence(Path ignored) {
                armed.set(true);
            }

            @Override
            public void contentRead(Path path) {
                super.contentRead(path);
                Path normalized = path.toAbsolutePath().normalize();
                if (armed.get() && normalized.equals(second) && changed.compareAndSet(false, true)) {
                    try {
                        Files.writeString(first, "gamma");
                    } catch (IOException failure) {
                        throw new IllegalStateException(failure);
                    }
                }
            }
        };

        assertThrows(IOException.class, () -> AssetTransactionCoordinator.openObserved(root, GSON,
            Clock.systemUTC(), observer));
        assertTrue(changed.get());
    }

    @Test
    void startupFinalFenceRejectsNewUnregisteredAssetAndLiveDeletion() throws Exception {
        Path createdRoot = tempDir.resolve("new-asset-final-fence");
        try (AssetTransactionCoordinator ignored = AssetTransactionCoordinator.open(createdRoot, GSON)) {
        }
        RecordingEvidenceObserver createObserver = new RecordingEvidenceObserver() {
            @Override
            public void beforeFinalFence(Path root) throws IOException {
                Files.writeString(root.resolve("unregistered.json"), "unregistered");
            }
        };
        assertThrows(IOException.class, () -> AssetTransactionCoordinator.openObserved(createdRoot, GSON,
            Clock.systemUTC(), createObserver));

        Path deletedRoot = tempDir.resolve("deleted-asset-final-fence");
        Path asset = deletedRoot.resolve("live.json");
        try (AssetTransactionCoordinator coordinator = AssetTransactionCoordinator.open(deletedRoot, GSON)) {
            coordinator.transact(request("e1000000-0000-4000-8000-000000000005",
                coordinator.read(AssetTransactionCoordinator.Snapshot::project),
                AssetTransactionCoordinator.AssetDelta.write(new AssetTransactionCoordinator.AssetKey("flow", "live"),
                    Path.of("live.json"), AssetTransactionCoordinator.Missing.INSTANCE,
                    "alive".getBytes(StandardCharsets.UTF_8))));
        }
        RecordingEvidenceObserver deleteObserver = new RecordingEvidenceObserver() {
            @Override
            public void beforeFinalFence(Path ignored) throws IOException {
                Files.delete(asset);
            }
        };
        assertThrows(IOException.class, () -> AssetTransactionCoordinator.openObserved(deletedRoot, GSON,
            Clock.systemUTC(), deleteObserver));
    }

    @Test
    void startupEvidenceRejectsSameSizeAssetTamperAndHistoryFenceMutation() throws Exception {
        Path tamperRoot = tempDir.resolve("same-size-startup-tamper-assets");
        Path asset = tamperRoot.resolve("live.json");
        try (AssetTransactionCoordinator coordinator = AssetTransactionCoordinator.open(tamperRoot, GSON)) {
            coordinator.transact(request("e2000000-0000-4000-8000-000000000001",
                coordinator.read(AssetTransactionCoordinator.Snapshot::project),
                AssetTransactionCoordinator.AssetDelta.write(new AssetTransactionCoordinator.AssetKey("flow", "live"),
                    Path.of("live.json"), AssetTransactionCoordinator.Missing.INSTANCE,
                    "alpha".getBytes(StandardCharsets.UTF_8))));
        }
        Files.writeString(asset, "omega");
        assertThrows(IOException.class, () -> AssetTransactionCoordinator.open(tamperRoot, GSON));

        Path fenceRoot = tempDir.resolve("history-fence-assets");
        try (AssetTransactionCoordinator coordinator = AssetTransactionCoordinator.open(fenceRoot, GSON)) {
            coordinator.transact(new AssetTransactionCoordinator.TransactionRequest(
                UUID.fromString("e2000000-0000-4000-8000-000000000002"),
                coordinator.read(AssetTransactionCoordinator.Snapshot::project), List.of(),
                List.of(AssetTransactionCoordinator.ProjectDelta.set(List.of("serverId"),
                    JsonParser.parseString("\"fence\"")))));
        }
        Path journal = onlyJournal(fenceRoot);
        FileTime originalTime = Files.getLastModifiedTime(journal);
        RecordingEvidenceObserver observer = new RecordingEvidenceObserver() {
            @Override
            public void beforeFinalFence(Path root) throws IOException {
                byte[] bytes = Files.readAllBytes(journal);
                bytes[bytes.length - 2] ^= 1;
                Files.write(journal, bytes);
                Files.setLastModifiedTime(journal, FileTime.fromMillis(originalTime.toMillis() + 10_000L));
            }
        };
        assertThrows(IOException.class, () -> AssetTransactionCoordinator.openObserved(fenceRoot, GSON,
            Clock.systemUTC(), observer));
    }

    @Test
    void startupEvidenceStillRejectsCorruptJournalAndMissingBindingOrSnapshot() throws Exception {
        Path corruptRoot = tempDir.resolve("snapshot-corrupt-journal-assets");
        try (AssetTransactionCoordinator coordinator = AssetTransactionCoordinator.open(corruptRoot, GSON)) {
            coordinator.transact(new AssetTransactionCoordinator.TransactionRequest(
                UUID.fromString("e3000000-0000-4000-8000-000000000001"),
                coordinator.read(AssetTransactionCoordinator.Snapshot::project), List.of(),
                List.of(AssetTransactionCoordinator.ProjectDelta.set(List.of("serverId"),
                    JsonParser.parseString("\"corrupt\"")))));
        }
        Files.writeString(onlyJournal(corruptRoot), "{");
        assertThrows(IOException.class, () -> AssetTransactionCoordinator.open(corruptRoot, GSON));

        Path bindingRoot = tempDir.resolve("snapshot-missing-binding-assets");
        try (AssetTransactionCoordinator coordinator = AssetTransactionCoordinator.open(bindingRoot, GSON)) {
            coordinator.transact(new AssetTransactionCoordinator.TransactionRequest(
                UUID.fromString("e3000000-0000-4000-8000-000000000002"),
                coordinator.read(AssetTransactionCoordinator.Snapshot::project), List.of(),
                List.of(AssetTransactionCoordinator.ProjectDelta.set(List.of("serverId"),
                    JsonParser.parseString("\"binding\"")))));
        }
        try (var bindings = Files.list(bindingRoot.resolve(".asset-coordinator/bindings"))) {
            Files.delete(bindings.filter(Files::isRegularFile).findFirst().orElseThrow());
        }
        assertThrows(IOException.class, () -> AssetTransactionCoordinator.open(bindingRoot, GSON));

        Path snapshotRoot = tempDir.resolve("snapshot-missing-snapshot-assets");
        try (AssetTransactionCoordinator coordinator = AssetTransactionCoordinator.open(snapshotRoot, GSON)) {
            coordinator.transact(new AssetTransactionCoordinator.TransactionRequest(
                UUID.fromString("e3000000-0000-4000-8000-000000000003"),
                coordinator.read(AssetTransactionCoordinator.Snapshot::project), List.of(),
                List.of(AssetTransactionCoordinator.ProjectDelta.set(List.of("serverId"),
                    JsonParser.parseString("\"snapshot\"")))));
        }
        Path snapshot;
        try (var snapshots = Files.walk(snapshotRoot.resolve(".snapshots"))) {
            snapshot = snapshots.filter(Files::isRegularFile).findFirst().orElseThrow();
        }
        Files.delete(snapshot);
        assertThrows(IOException.class, () -> AssetTransactionCoordinator.open(snapshotRoot, GSON));
    }

    @Test
    void preparedRecoveryRejectsIdentityChangesBeforeWriting() throws Exception {
        for (String variant : List.of("mutation", "existed", "staged", "target")) {
            Path root = preparedRecoveryRoot("prepared-identity-" + variant,
                UUID.nameUUIDFromBytes(variant.getBytes(StandardCharsets.UTF_8)));
            Path journal = onlyJournal(root);
            Path target = root.resolve("value.json");
            AssetTransactionCoordinator.EvidenceReadObserver observer = new RecordingEvidenceObserver() {
                @Override
                public void beforePreparedRecovery(Path ignored) throws IOException {
                    if (variant.equals("target")) {
                        Files.writeString(target, "intruder");
                        return;
                    }
                    JsonObject document = JsonParser.parseString(Files.readString(journal)).getAsJsonObject();
                    if (variant.equals("mutation")) {
                        document.addProperty("mutationId", UUID.nameUUIDFromBytes(
                            ("changed-" + variant).getBytes(StandardCharsets.UTF_8)).toString());
                    } else {
                        JsonObject entry = document.getAsJsonArray("entries").asList().stream()
                            .map(JsonElement::getAsJsonObject)
                            .filter(candidate -> candidate.get("target").getAsString().equals("value.json"))
                            .findFirst().orElseThrow();
                        if (variant.equals("existed")) {
                            entry.addProperty("existed", true);
                        } else {
                            Path staged = journal.getParent().resolve(entry.get("staged").getAsString());
                            byte[] bytes = Files.readAllBytes(staged);
                            bytes[0] ^= 1;
                            Files.write(staged, bytes);
                            return;
                        }
                    }
                    Files.writeString(journal, document.toString());
                }
            };

            assertThrows(IOException.class, () -> AssetTransactionCoordinator.openObserved(root, GSON,
                Clock.systemUTC(), observer));
            JsonObject unchanged = JsonParser.parseString(Files.readString(journal)).getAsJsonObject();
            assertEquals("PREPARED", unchanged.get("state").getAsString());
            if (variant.equals("target")) {
                assertTrue(Files.notExists(target) || Files.readString(target).equals("intruder"));
            } else {
                assertTrue(Files.notExists(target));
            }
        }
    }

    @Test
    void preparedRecoveryRejectsCorruptOrMissingPreStateSnapshotBeforeWriting() throws Exception {
        UUID corruptMutation = UUID.fromString("e5000000-0000-4000-8000-000000000001");
        Path corruptRoot = preparedExistingRecoveryRoot("prepared-corrupt-pre-state", corruptMutation);
        Path corruptJournal = journalForMutation(corruptRoot, corruptMutation);
        Path corruptTarget = corruptRoot.resolve("value.json");
        Path corruptSnapshot = corruptRoot.resolve(".snapshots").resolve(corruptJournal.getParent().getFileName())
            .resolve("value.json");
        Files.writeString(corruptTarget, "before");
        Files.writeString(corruptSnapshot, "broken");

        assertThrows(IOException.class, () -> AssetTransactionCoordinator.open(corruptRoot, GSON));
        assertEquals("before", Files.readString(corruptTarget));
        assertEquals("broken", Files.readString(corruptSnapshot));
        assertEquals("PREPARED", JsonParser.parseString(Files.readString(corruptJournal)).getAsJsonObject()
            .get("state").getAsString());

        UUID missingMutation = UUID.fromString("e5000000-0000-4000-8000-000000000002");
        Path missingRoot = preparedExistingRecoveryRoot("prepared-missing-pre-state", missingMutation);
        Path missingJournal = journalForMutation(missingRoot, missingMutation);
        Path missingTarget = missingRoot.resolve("value.json");
        Path missingSnapshot = missingRoot.resolve(".snapshots").resolve(missingJournal.getParent().getFileName())
            .resolve("value.json");
        Files.delete(missingSnapshot);

        assertThrows(IOException.class, () -> AssetTransactionCoordinator.open(missingRoot, GSON));
        assertEquals("after", Files.readString(missingTarget));
        assertTrue(Files.notExists(missingSnapshot));
        assertEquals("PREPARED", JsonParser.parseString(Files.readString(missingJournal)).getAsJsonObject()
            .get("state").getAsString());
    }

    @Test
    void retainedStartupEvidenceHasPerFileAndAggregateBounds() throws Exception {
        Path perFileRoot = tempDir.resolve("evidence-per-file-bound");
        RecordingEvidenceObserver perFile = new RecordingEvidenceObserver() {
            @Override
            public long retainedFileLimit() {
                return 1L;
            }
        };
        assertThrows(IOException.class, () -> AssetTransactionCoordinator.openObserved(perFileRoot, GSON,
            Clock.systemUTC(), perFile));

        Path aggregateRoot = tempDir.resolve("evidence-aggregate-bound");
        RecordingEvidenceObserver aggregate = new RecordingEvidenceObserver() {
            @Override
            public long retainedFileLimit() {
                return 1024L * 1024L;
            }

            @Override
            public long retainedTotalLimit() {
                return 32L;
            }
        };
        assertThrows(IOException.class, () -> AssetTransactionCoordinator.openObserved(aggregateRoot, GSON,
            Clock.systemUTC(), aggregate));
    }

    @Test
    void startupEvidenceHasDepthPathAndStreamedHashBounds() throws Exception {
        RecordingEvidenceObserver depth = new RecordingEvidenceObserver() {
            @Override
            public int maximumDepth() {
                return 1;
            }
        };
        assertThrows(IOException.class, () -> AssetTransactionCoordinator.openObserved(
            tempDir.resolve("evidence-depth-bound"), GSON, Clock.systemUTC(), depth));

        RecordingEvidenceObserver paths = new RecordingEvidenceObserver() {
            @Override
            public int maximumPaths() {
                return 1;
            }
        };
        assertThrows(IOException.class, () -> AssetTransactionCoordinator.openObserved(
            tempDir.resolve("evidence-path-bound"), GSON, Clock.systemUTC(), paths));

        Path hashRoot = tempDir.resolve("evidence-hash-bound");
        try (AssetTransactionCoordinator coordinator = AssetTransactionCoordinator.open(hashRoot, GSON)) {
            coordinator.transact(request("e6000000-0000-4000-8000-000000000001",
                coordinator.read(AssetTransactionCoordinator.Snapshot::project),
                AssetTransactionCoordinator.AssetDelta.write(new AssetTransactionCoordinator.AssetKey("flow", "large"),
                    Path.of("large.json"), AssetTransactionCoordinator.Missing.INSTANCE,
                    "large".getBytes(StandardCharsets.UTF_8))));
        }
        RecordingEvidenceObserver perFileHash = new RecordingEvidenceObserver() {
            @Override
            public long hashedFileLimit() {
                return 4L;
            }
        };
        assertThrows(IOException.class, () -> AssetTransactionCoordinator.openObserved(hashRoot, GSON,
            Clock.systemUTC(), perFileHash));

        RecordingEvidenceObserver aggregateHash = new RecordingEvidenceObserver() {
            @Override
            public long hashedTotalLimit() {
                return 8L;
            }
        };
        assertThrows(IOException.class, () -> AssetTransactionCoordinator.openObserved(hashRoot, GSON,
            Clock.systemUTC(), aggregateHash));
    }

    private static boolean compete(AssetTransactionCoordinator coordinator,
                                   AssetTransactionCoordinator.ExpectedProject project,
                                   AssetTransactionCoordinator.AssetKey key, UUID mutationId,
                                   CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        start.await();
        try {
            coordinator.transact(new AssetTransactionCoordinator.TransactionRequest(mutationId, project,
                List.of(AssetTransactionCoordinator.AssetDelta.write(key, Path.of("Blueprints/Functions/shared.json"),
                    AssetTransactionCoordinator.Missing.INSTANCE, mutationId.toString().getBytes(StandardCharsets.UTF_8))), List.of()));
            return true;
        } catch (AssetTransactionCoordinator.StateConflictException expected) {
            return false;
        } catch (IOException failure) {
            throw failure;
        }
    }

    private static IOException awaitHealthFailure(AssetTransactionCoordinator coordinator) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3L);
        IOException detected = null;
        while (detected == null && System.nanoTime() < deadline) {
            try {
                coordinator.healthCheck();
            } catch (IOException failure) {
                detected = failure;
            }
            if (detected == null) {
                Thread.sleep(10L);
            }
        }
        return detected;
    }

    private static class RecordingEvidenceObserver implements AssetTransactionCoordinator.EvidenceReadObserver {
        private final AtomicInteger walks = new AtomicInteger();
        private final Map<Path, AtomicInteger> reads = new LinkedHashMap<>();

        @Override
        public void walkStarted(Path root) {
            walks.incrementAndGet();
        }

        @Override
        public void contentRead(Path path) {
            reads.computeIfAbsent(path.toAbsolutePath().normalize(), ignored -> new AtomicInteger()).incrementAndGet();
        }
    }

    private static AssetTransactionCoordinator.TransactionRequest request(String mutationId,
                                                                          AssetTransactionCoordinator.ExpectedProject project,
                                                                          AssetTransactionCoordinator.AssetDelta delta) {
        return new AssetTransactionCoordinator.TransactionRequest(UUID.fromString(mutationId), project, List.of(delta), List.of());
    }

    private Path preparedRecoveryRoot(String name, UUID mutationId) throws Exception {
        Path root = tempDir.resolve(name);
        try (AssetTransactionCoordinator coordinator = AssetTransactionCoordinator.open(root, GSON)) {
            coordinator.transact(request(mutationId.toString(),
                coordinator.read(AssetTransactionCoordinator.Snapshot::project),
                AssetTransactionCoordinator.AssetDelta.write(new AssetTransactionCoordinator.AssetKey("flow", name),
                    Path.of("value.json"), AssetTransactionCoordinator.Missing.INSTANCE,
                    "stable".getBytes(StandardCharsets.UTF_8))));
        }
        Path journal = onlyJournal(root);
        JsonObject document = JsonParser.parseString(Files.readString(journal)).getAsJsonObject();
        document.addProperty("state", "PREPARED");
        Files.writeString(journal, document.toString());
        Files.delete(root.resolve("value.json"));
        return root;
    }

    private Path preparedExistingRecoveryRoot(String name, UUID mutationId) throws Exception {
        Path root = tempDir.resolve(name);
        AssetTransactionCoordinator.AssetKey key = new AssetTransactionCoordinator.AssetKey("flow", name);
        try (AssetTransactionCoordinator coordinator = AssetTransactionCoordinator.open(root, GSON)) {
            coordinator.transact(request(UUID.nameUUIDFromBytes((name + "-create").getBytes(StandardCharsets.UTF_8)).toString(),
                coordinator.read(AssetTransactionCoordinator.Snapshot::project),
                AssetTransactionCoordinator.AssetDelta.write(key, Path.of("value.json"),
                    AssetTransactionCoordinator.Missing.INSTANCE, "before".getBytes(StandardCharsets.UTF_8))));
            AssetTransactionCoordinator.ExpectedState expected = coordinator.read(snapshot -> snapshot.state(key).orElseThrow());
            coordinator.transact(request(mutationId.toString(),
                coordinator.read(AssetTransactionCoordinator.Snapshot::project),
                AssetTransactionCoordinator.AssetDelta.write(key, Path.of("value.json"), expected,
                    "after".getBytes(StandardCharsets.UTF_8))));
        }
        Path journal = journalForMutation(root, mutationId);
        JsonObject document = JsonParser.parseString(Files.readString(journal)).getAsJsonObject();
        document.addProperty("state", "PREPARED");
        Files.writeString(journal, document.toString());
        return root;
    }

    private static Path journalForMutation(Path root, UUID mutationId) throws IOException {
        try (var transactions = Files.list(root.resolve(".transactions"))) {
            for (Path transaction : transactions.filter(Files::isDirectory).toList()) {
                Path journal = transaction.resolve("journal.json");
                JsonObject document = JsonParser.parseString(Files.readString(journal)).getAsJsonObject();
                if (mutationId.toString().equals(document.get("mutationId").getAsString())) {
                    return journal;
                }
            }
        }
        throw new IOException("Asset transaction journal was not found: " + mutationId);
    }

    private static Path onlyJournal(Path root) throws IOException {
        try (var transactions = Files.list(root.resolve(".transactions"))) {
            return transactions.filter(Files::isDirectory).findFirst().orElseThrow().resolve("journal.json");
        }
    }

    private static String descriptorFingerprint(JsonArray operations) {
        JsonObject fingerprint = new JsonObject();
        fingerprint.addProperty("version", "asset-transaction-descriptor-v1");
        fingerprint.add("operations", operations.deepCopy());
        return StorageSafety.sha256(fingerprint.toString());
    }

    private static void writeRehashedState(Path stateFile, JsonObject state) throws IOException {
        state.remove("stateHash");
        state.addProperty("stateHash", StorageSafety.sha256(AssetProjectMetadata.of(state).canonicalJson()));
        Files.writeString(stateFile, state.toString());
    }
}
