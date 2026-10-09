package restudio.resync.storage;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.resync.flow.CoreGraphStorageBoundary;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.graph.OpaqueData;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.resources.AssetFileFormat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StorageDurabilityTest {
    @TempDir
    Path tempDir;

    @Test
    void asyncAdmissionIsBoundedAndWholeSnapshotsKeepTheLatestWrite() throws Exception {
        AsyncStorageExecutor writer = new AsyncStorageExecutor();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean runningReleased = new AtomicBoolean();
        AtomicBoolean oldReleased = new AtomicBoolean();
        Path snapshot = tempDir.resolve("players.json");
        writer.submitTracked(() -> {
            started.countDown();
            try {
                release.await();
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(failure);
            }
        }, () -> runningReleased.set(true));
        try {
            assertTrue(started.await(1, TimeUnit.SECONDS));
            writer.submitLatest("players", () -> { throw new AssertionError("old snapshot ran"); },
                () -> oldReleased.set(true));
            for (int revision = 1; revision <= 100; revision++) {
                String latest = Integer.toString(revision);
                writer.submitLatest("players", () -> {
                    try {
                        Files.writeString(snapshot, latest);
                    } catch (IOException failure) {
                        throw new UncheckedIOException(failure);
                    }
                }, () -> {});
            }
            assertTrue(oldReleased.get());
            assertFalse(runningReleased.get());
            for (int queued = 1; queued < 64; queued++) {
                writer.submit(() -> {});
            }
            assertThrows(RejectedExecutionException.class, () -> writer.submit(() -> {}));
            assertFalse(Files.exists(snapshot));
            release.countDown();
            writer.flush();
            assertEquals("100", Files.readString(snapshot));
            assertTrue(runningReleased.get());
        } finally {
            release.countDown();
            writer.shutdown();
        }
        assertThrows(RejectedExecutionException.class, () -> writer.submitLatest("players", () -> {}, () -> {}));
    }

    @Test
    void asyncFailuresRemainObservableAndShutdownSettlesDiscardedWrites() throws Exception {
        AsyncStorageExecutor failed = new AsyncStorageExecutor();
        IllegalStateException cause = new IllegalStateException("write failed");
        CompletableFuture<Void> write = failed.submitTracked(() -> { throw cause; });
        assertThrows(CompletionException.class, write::join);
        assertEquals(cause, assertThrows(IOException.class, failed::flush).getCause());
        assertEquals(cause, assertThrows(IOException.class, failed::shutdown).getCause());
        assertThrows(RejectedExecutionException.class, () -> failed.submit(() -> {}));

        AsyncStorageExecutor blocked = new AsyncStorageExecutor(Duration.ofMillis(100));
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch cleaned = new CountDownLatch(1);
        CompletableFuture<Void> running = blocked.submitTracked(() -> {
            started.countDown();
            try {
                release.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("write interrupted", exception);
            }
        });
        try {
            assertTrue(started.await(1, TimeUnit.SECONDS));
            CompletableFuture<Void> queued = blocked.submitTracked(() -> {
                throw new AssertionError("discarded write ran");
            }, cleaned::countDown);
            assertThrows(IOException.class, blocked::shutdown);
            assertTrue(cleaned.await(1, TimeUnit.SECONDS));
            assertThrows(CompletionException.class, queued::join);
            assertThrows(RejectedExecutionException.class, () -> blocked.submit(() -> {}));
        } finally {
            release.countDown();
            try {
                blocked.shutdown();
            } catch (IOException ignored) {
            }
            running.handle((ignored, failure) -> null).get(1, TimeUnit.SECONDS);
        }
    }

    @Test
    void transactionSnapshotsCanBePreviewedAndExplicitlyRestored() throws Exception {
        Path assets = tempDir.resolve("assets");
        Gson gson = new Gson();
        AssetTransactionManager transactions = new AssetTransactionManager(assets, gson);
        Path graph = assets.resolve("Blueprints").resolve("Flows").resolve("main.json");
        Path project = assets.resolve("project.json");
        transactions.commit(Map.of(graph, "{\"value\":1}", project, "{\"resources\":[1]}"), "first");
        Map<Path, String> update = new LinkedHashMap<>();
        update.put(graph, "{\"value\":2}");
        update.put(project, "{\"resources\":[2]}");
        String transactionId = transactions.commit(update, "second");

        AssetTransactionManager.RestorePreview preview = transactions.previewRestore(transactionId);
        assertEquals(2, preview.files().size());
        assertEquals("{\"value\":2}", Files.readString(graph));

        transactions.restore(transactionId, "restore");

        assertEquals("{\"value\":1}", Files.readString(graph));
        assertEquals("{\"resources\":[1]}", Files.readString(project));
    }

    @Test
    void restoringCreationDeletesTheCreatedAsset() throws Exception {
        Path assets = tempDir.resolve("assets");
        AssetTransactionManager transactions = new AssetTransactionManager(assets, new Gson());
        Path graph = assets.resolve("Blueprints").resolve("Flows").resolve("created.json");
        String transactionId = transactions.commit(Map.of(graph, "{\"value\":1}"), "create");

        assertTrue(Files.isRegularFile(graph));
        assertEquals(1, transactions.previewRestore(transactionId).files().size());

        transactions.restore(transactionId, "restore-create");

        assertFalse(Files.exists(graph));
    }

    @Test
    void preparedTransactionsAreRecoveredBeforeNewerCommits() throws Exception {
        Path assets = tempDir.resolve("assets");
        Path target = assets.resolve("Blueprints").resolve("Flows").resolve("ordered.json");
        Path transaction = assets.resolve(".transactions").resolve("prepared");
        Files.createDirectories(transaction);
        byte[] older = "{\"value\":1}".getBytes(StandardCharsets.UTF_8);
        Files.write(transaction.resolve("content-0.json"), older);
        JsonObject entry = new JsonObject();
        entry.addProperty("target", assets.relativize(target).toString());
        entry.addProperty("staged", "content-0.json");
        entry.addProperty("hash", StorageSafety.sha256(older));
        entry.addProperty("delete", false);
        entry.addProperty("existed", false);
        JsonArray entries = new JsonArray();
        entries.add(entry);
        JsonObject journal = new JsonObject();
        journal.addProperty("version", 2);
        journal.addProperty("id", "prepared");
        journal.addProperty("mutationId", "older");
        journal.addProperty("state", "PREPARED");
        journal.add("entries", entries);
        Files.writeString(transaction.resolve("journal.json"), journal.toString());

        AssetTransactionManager transactions = new AssetTransactionManager(assets, new Gson());
        transactions.commit(Map.of(target, "{\"value\":2}"), "newer");

        assertEquals("{\"value\":2}", Files.readString(target));
        assertTrue(Files.readString(transaction.resolve("journal.json")).contains("\"COMMITTED\""));
    }

    @Test
    void committedMutationIdsRequireExactReplay() throws Exception {
        Path assets = tempDir.resolve("assets");
        AssetTransactionManager transactions = new AssetTransactionManager(assets, new Gson());
        Path target = assets.resolve("Blueprints").resolve("Flows").resolve("idempotent.json");
        Path other = assets.resolve("Blueprints").resolve("Flows").resolve("other.json");
        String mutationId = UUID.fromString("55555555-5555-4555-8555-555555555555").toString();

        String first = transactions.commit(Map.of(target, "{\"value\":1}"), mutationId);
        String duplicate = transactions.commit(Map.of(target, "{\"value\":1}"), mutationId);

        assertEquals(first, duplicate);
        assertThrows(IOException.class,
            () -> transactions.commit(Map.of(target, "{\"value\":2}"), mutationId));
        assertThrows(IOException.class,
            () -> transactions.commit(Map.of(other, "{\"value\":1}"), mutationId));
        assertEquals("{\"value\":1}", Files.readString(target));
        assertFalse(Files.exists(other));
    }

    @Test
    void invalidTransactionTargetsLeaveNoOrphanJournal() throws Exception {
        Path assets = tempDir.resolve("assets");
        AssetTransactionManager transactions = new AssetTransactionManager(assets, new Gson());
        Path target = assets.resolve("Blueprints").resolve("Flows").resolve("duplicate.json");
        Map<Path, String> writes = new LinkedHashMap<>();
        writes.put(target, "{\"value\":1}");
        writes.put(target.getParent().resolve("nested").resolve("..").resolve(target.getFileName()), "{\"value\":2}");

        assertThrows(IOException.class, () -> transactions.commit(writes, "duplicate"));
        assertThrows(IOException.class, () -> transactions.commit(Map.of(assets.resolve(".transactions").resolve("journal.json"), "{}"), "reserved"));

        try (var transactionsOnDisk = Files.list(assets.resolve(".transactions"))) {
            assertTrue(transactionsOnDisk.findAny().isEmpty());
        }
    }

    @Test
    void corruptJournalFallsBackToPreviousAndQuarantinesEvidence() throws Exception {
        Path file = tempDir.resolve("automation-tasks.json");
        RecoverableJsonStore store = new RecoverableJsonStore(file, new Gson());
        store.save(JsonParser.parseString("[{\"id\":\"first\"}]"));
        store.save(JsonParser.parseString("[{\"id\":\"second\"}]"));
        Files.writeString(file, "{broken");

        assertEquals("first", store.load().getAsJsonArray().get(0).getAsJsonObject().get("id").getAsString());
        store.save(JsonParser.parseString("[{\"id\":\"third\"}]"));
        Files.writeString(file, "{broken-again");
        assertEquals("first", store.load().getAsJsonArray().get(0).getAsJsonObject().get("id").getAsString());
        Path quarantine = tempDir.resolve(".quarantine").resolve("journals");
        assertTrue(Files.isDirectory(quarantine));
        try (var files = Files.list(quarantine)) {
            assertFalse(files.toList().isEmpty());
        }
    }

    @Test
    void integrityScanReportsDuplicateIdentitiesAndHashDamage() throws Exception {
        Path assets = tempDir.resolve("assets");
        Path first = assets.resolve("Blueprints").resolve("Flows").resolve("shared.json");
        Path second = assets.resolve("Blueprints").resolve("Commands").resolve("shared.json");
        Files.createDirectories(first.getParent());
        Files.createDirectories(second.getParent());
        Files.writeString(first, AssetFileFormat.withResourceIdentity("{\"id\":\"shared\"}", "command", 1L, "first"));
        Files.writeString(second, AssetFileFormat.withResourceIdentity("{\"id\":\"shared\"}", "command", 1L, "second"));
        Files.writeString(assets.resolve("project.json"), "{\"resources\":[]}");
        Files.writeString(first, Files.readString(first).replace("\"assetMutationId\":\"first\"", "\"assetMutationId\":\"tampered\""));

        AssetIntegrityService.HealthReport report = new AssetIntegrityService(assets).scan(0);

        assertEquals(AssetIntegrityService.Status.CRITICAL, report.status());
        assertTrue(report.issues().stream().anyMatch(issue -> issue.code().equals("DUPLICATE_IDENTITY")));
        assertTrue(report.issues().stream().anyMatch(issue -> issue.code().equals("HASH_MISMATCH")));
    }

    @Test
    void integrityScanIgnoresInternalMigrationAndDurabilityState() throws Exception {
        Path assets = tempDir.resolve("assets");
        Files.createDirectories(assets.resolve(".migrations"));
        Files.createDirectories(assets.resolve(".tombstones"));
        Files.createDirectories(assets.resolve(".asset-coordinator/bindings"));
        Files.createDirectories(assets.resolve(".mutation-intents/command"));
        Files.writeString(assets.resolve(".migrations/command-bindings-v1.json"), "{\"migration\":\"command-bindings-v1\"}");
        Files.writeString(assets.resolve(".tombstones/deleted.json"), "{\"id\":\"deleted\"}");
        Files.writeString(assets.resolve(".asset-coordinator/bindings/transaction.json"), "{\"id\":\"transaction\"}");
        Files.writeString(assets.resolve(".mutation-intents/command/main.json"), "{\"id\":\"main\"}");
        Files.writeString(assets.resolve("project.json"), "{\"resources\":[]}");

        AssetIntegrityService.HealthReport report = new AssetIntegrityService(assets).scan(0);

        assertEquals(AssetIntegrityService.Status.HEALTHY, report.status());
        assertTrue(report.issues().isEmpty());
    }

    @Test
    void integrityScanUsesCoreGraphIntegrityForCanonicalCommandAssets() throws Exception {
        Path assets = tempDir.resolve("assets");
        Path command = assets.resolve("Blueprints/Commands/main.json");
        Files.createDirectories(command.getParent());
        ServerResourceLocator resource = new ServerResourceLocator(
            new ServerId(UUID.fromString("11111111-1111-4111-8111-111111111111")),
            ContractRef.of(new OwnerId("restudio.resync"), new ResourceTypeId("command")), "main");
        GraphDocument graph = new GraphDocument(new CatalogVersion(1, 0), resource, 1,
            new CatalogBinding(1, new ContentHash("0".repeat(64)), new ContentHash("1".repeat(64))), Set.of(),
            List.of(), List.of(), List.of(), List.of(), OpaqueData.empty());
        CoreGraphStorageBoundary boundary = new CoreGraphStorageBoundary();
        Files.write(command, boundary.encode(graph,
            new CoreGraphStorageBoundary.AssetMetadata("command", 1,
                "22222222-2222-4222-8222-222222222222")));
        Files.writeString(assets.resolve("project.json"), """
            {"resources":[{"type":"command","id":"main","path":"Blueprints/Commands"}]}
            """);

        AssetIntegrityService.HealthReport report = new AssetIntegrityService(assets).scan(0);

        assertEquals(AssetIntegrityService.Status.HEALTHY, report.status());
        assertTrue(report.issues().isEmpty());
    }

    @Test
    void integrityScanUsesProjectPathsToDistinguishRecoverableCopies() throws Exception {
        Path assets = tempDir.resolve("assets");
        Path canonical = assets.resolve("Blueprints").resolve("Commands").resolve("shared.json");
        Path stale = assets.resolve("Blueprints").resolve("Flows").resolve("shared.json");
        Files.createDirectories(canonical.getParent());
        Files.createDirectories(stale.getParent());
        Files.writeString(canonical, AssetFileFormat.withResourceIdentity("{\"id\":\"shared\"}", "command", 1L, "canonical"));
        Files.writeString(stale, AssetFileFormat.withResourceIdentity("{\"id\":\"shared\"}", "command", 1L, "stale"));
        Files.writeString(assets.resolve("project.json"), """
            {
              "resources": [
                {"type":"command","id":"shared","path":"Blueprints/Commands"},
                {"type":"world","id":"world","path":"Worlds"}
              ]
            }
            """);

        AssetIntegrityService.HealthReport report = new AssetIntegrityService(assets).scan(0);

        assertEquals(AssetIntegrityService.Status.DEGRADED, report.status());
        assertTrue(report.issues().stream().anyMatch(issue -> issue.code().equals("ORPHANED_RESOURCE_COPY")));
        assertFalse(report.issues().stream().anyMatch(issue -> issue.code().equals("DUPLICATE_IDENTITY")));
        assertFalse(report.issues().stream().anyMatch(issue -> issue.code().equals("MISSING_RESOURCE_FILE") && issue.resourceId().equals("world")));
    }

    @Test
    void integrityScanAllowsDeclaredGraphTypesToShareAnId() throws Exception {
        Path assets = tempDir.resolve("assets");
        Path flow = assets.resolve("Blueprints").resolve("Flows").resolve("shared.json");
        Path function = assets.resolve("Blueprints").resolve("Functions").resolve("shared.json");
        Files.createDirectories(flow.getParent());
        Files.createDirectories(function.getParent());
        Files.writeString(flow, AssetFileFormat.withResourceIdentity("{\"id\":\"shared\"}", "flow", 1L, "flow"));
        Files.writeString(function, AssetFileFormat.withResourceIdentity("{\"id\":\"shared\"}", "function", 1L, "function"));
        Files.writeString(assets.resolve("project.json"), """
            {
              "resources": [
                {"type":"flow","id":"shared","path":"Blueprints/Flows"},
                {"type":"function","id":"shared","path":"Blueprints/Functions"}
              ]
            }
            """);

        AssetIntegrityService.HealthReport report = new AssetIntegrityService(assets).scan(0);

        assertFalse(report.issues().stream().anyMatch(issue -> issue.code().equals("AMBIGUOUS_GRAPH_IDENTITY")));
    }
}
