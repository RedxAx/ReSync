package restudio.resync.storage;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.resync.resources.AssetFileFormat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StorageDurabilityTest {
    @TempDir
    Path tempDir;

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
        AssetTransactionManager transactions = new AssetTransactionManager(assets, new Gson());
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

        transactions.commit(Map.of(target, "{\"value\":2}"), "newer");

        assertEquals("{\"value\":2}", Files.readString(target));
        assertTrue(Files.readString(transaction.resolve("journal.json")).contains("\"COMMITTED\""));
    }

    @Test
    void committedMutationIdsAreIdempotent() throws Exception {
        Path assets = tempDir.resolve("assets");
        AssetTransactionManager transactions = new AssetTransactionManager(assets, new Gson());
        Path target = assets.resolve("Blueprints").resolve("Flows").resolve("idempotent.json");

        String first = transactions.commit(Map.of(target, "{\"value\":1}"), "same-mutation");
        String duplicate = transactions.commit(Map.of(target, "{\"value\":2}"), "same-mutation");

        assertEquals(first, duplicate);
        assertEquals("{\"value\":1}", Files.readString(target));
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
        Files.writeString(assets.resolve(".migrations/command-bindings-v1.json"), "{\"migration\":\"command-bindings-v1\"}");
        Files.writeString(assets.resolve(".tombstones/deleted.json"), "{\"id\":\"deleted\"}");
        Files.writeString(assets.resolve("project.json"), "{\"resources\":[]}");

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
