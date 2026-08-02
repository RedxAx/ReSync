package restudio.resync.replacement.evidence.fixture.persistence;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowResourceReference;
import restudio.flow.data.FlowSerializer;
import restudio.resync.customization.ReSyncJsonResourceStorage;
import restudio.resync.flow.FlowStorage;
import restudio.resync.flow.migration.FlowGraphMigrator;
import restudio.resync.flow.migration.TypedAutomationGraphMigrator;
import restudio.resync.resources.ReSyncResourceCatalog;
import restudio.resync.storage.AssetTransactionManager;
import restudio.resync.storage.MigrationLedger;
import restudio.resync.storage.StorageSafety;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersistenceMigrationEvidenceTest {
    private static final Gson GSON = new Gson();

    @TempDir
    Path temporaryDirectory;

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void legacyAliasAndPinFixtureProvesCurrentLiveGraphMigration() {
        JsonObject fixture = fixture("legacy-graph-alias-pins.json");
        FlowGraph graph = FlowSerializer.deserialize(GSON.toJson(fixture));

        assertTrue(new FlowGraphMigrator(null).migrateGraph(graph));
        JsonObject expected = fixture.getAsJsonObject("currentExpectation");
        assertEquals(expected.get("loopType").getAsString(), graph.getNodes().get("loop").getType());
        assertEquals(expected.get("firstSourcePin").getAsString(), graph.getConnections().getFirst().getSourcePin());
        assertEquals(expected.get("secondSourcePin").getAsString(), graph.getConnections().get(1).getSourcePin());
        assertEquals(expected.get("secondTargetPin").getAsString(), graph.getConnections().get(1).getTargetPin());
    }

    @Test
    void typedAutomationFixtureProvesCurrentStartupConverterCreatesResource() {
        MockBukkit.mock();
        JavaPlugin plugin = MockBukkit.createMockPlugin();
        FlowStorage flows = new FlowStorage(plugin);
        ReSyncJsonResourceStorage resources = new ReSyncJsonResourceStorage(plugin);
        JsonObject fixture = fixture("typed-automation.json");
        FlowGraph graph = FlowSerializer.deserialize(GSON.toJson(fixture));
        flows.saveGraph(graph);

        new TypedAutomationGraphMigrator(flows, resources).migrateStoredFlows();

        JsonObject expected = fixture.getAsJsonObject("currentExpectation");
        var migrated = flows.getGraph("typed-automation").getNodes().get("variable");
        assertEquals(expected.get("nodeType").getAsString(), migrated.getType());
        FlowResourceReference reference = (FlowResourceReference) migrated.getInputValues().get("variable");
        assertEquals(expected.get("resourceType").getAsString(), reference.kind());
        assertEquals(expected.get("action").getAsString(), migrated.getInputValues().get("action"));
        assertNotNull(resources.get(ReSyncResourceCatalog.VARIABLE_DEFINITION, reference.id()));
    }

    @Test
    void graphDeletionCreatesTypedTombstoneButNotAWholeFolderSnapshot() {
        FlowStorage storage = new FlowStorage(temporaryDirectory.toFile());
        FlowGraph graph = new FlowGraph();
        graph.setId("tombstone-flow");
        storage.saveGraph(graph);
        storage.deleteGraph("flow", "tombstone-flow");

        Path assets = temporaryDirectory.resolve("assets");
        assertTrue(Files.isRegularFile(assets.resolve(".tombstones/flow/tombstone-flow.json")));
        assertFalse(Files.exists(temporaryDirectory.resolve("world-management")));
        assertFalse(Files.exists(temporaryDirectory.resolve("snapshot-manifest.json")));
    }

    @Test
    void assetTransactionRecoversPreparedWorkAndRestoresOnlyItsAssetTargets() throws Exception {
        Path assets = temporaryDirectory.resolve("assets");
        AssetTransactionManager manager = new AssetTransactionManager(assets, GSON);
        Path target = assets.resolve("project.json");
        String first = manager.commit(Map.of(target, "{\"state\":\"first\"}"), "first");
        String second = manager.commit(Map.of(target, "{\"state\":\"second\"}"), "second");
        assertEquals(Set.of("project.json"), Set.copyOf(manager.previewRestore(second).files()));
        manager.restore(second, "restore-second");
        assertEquals("{\"state\":\"first\"}", Files.readString(target));

        String prepared = "prepared-fixture";
        Path transaction = assets.resolve(".transactions").resolve(prepared);
        Files.createDirectories(transaction);
        String content = "{\"state\":\"recovered\"}";
        StorageSafety.writeUtf8Atomic(transaction.resolve("content-0.json"), content);
        StorageSafety.writeUtf8Atomic(transaction.resolve("journal.json"), """
            {"version":2,"id":"prepared-fixture","mutationId":"prepared","state":"PREPARED","entries":[{"target":"recovered.json","staged":"content-0.json","hash":"%s","delete":false,"existed":false}]}
            """.formatted(StorageSafety.sha256(content)));

        AssetTransactionManager recovered = new AssetTransactionManager(assets, GSON);
        assertEquals(1, recovered.getRecoveredTransactions());
        assertEquals(content, Files.readString(assets.resolve("recovered.json")));
        assertTrue(Files.readString(transaction.resolve("journal.json")).contains("COMMITTED"));
        assertFalse(Files.exists(temporaryDirectory.resolve("world-management/recovered.json")));
        assertNotNull(first);
    }

    @Test
    void migrationLedgerOnlyExposesCurrentComponentStates() throws Exception {
        Path assets = temporaryDirectory.resolve("assets");
        MigrationLedger ledger = new MigrationLedger(assets);
        ledger.prepare("fixture", "graph", "flow", 1L, "source-a", 2, 1L);
        assertTrue(Files.readString(assets.resolve(".durability/migrations.json")).contains("PREPARED"));
        ledger.commit("fixture", "graph", "source-a");
        ledger.prepare("fixture", "failed", "flow", 1L, "source-b", 2, 1L);
        ledger.fail("fixture", "failed", "source-b", "fixture failure");

        String states = Files.readString(assets.resolve(".durability/migrations.json"));
        assertTrue(states.contains("COMMITTED"));
        assertTrue(states.contains("FAILED"));
        assertFalse(states.contains("TRANSFORMING"));
        assertFalse(states.contains("VALIDATING"));
        assertFalse(states.contains("STAGED"));
        assertFalse(states.contains("ACTIVATED"));
        assertFalse(states.contains("ROLLED_BACK"));
    }

    @Test
    void goldenParticipantAndJournalFixturesRecordEveryCurrentAbsenceBoundary() {
        JsonObject participants = fixture("persistence-participants.json");
        assertEquals(List.of(
            "A3-PP-01", "A3-PP-02", "A3-PP-03", "A3-PP-04", "A3-PP-05", "A3-PP-06", "A3-PP-07", "A3-PP-08",
            "A3-PP-09", "A3-PP-10", "A3-PP-11", "A3-PP-12", "A3-PP-13", "A3-PP-14", "A3-PP-15", "A3-PP-16"
        ), participants.getAsJsonArray("currentRoots").asList().stream()
            .map(value -> value.getAsJsonObject().get("id").getAsString())
            .toList());
        assertTrue(participants.getAsJsonArray("currentRoots").asList().stream()
            .map(value -> value.getAsJsonObject())
            .allMatch(root -> !root.get("manifest").getAsBoolean() && !root.get("atomicWholeFolder").getAsBoolean()));
        JsonObject journal = fixture("current-journal-states.json");
        assertEquals("absent", journal.getAsJsonObject("planJournalStates").get("TRANSFORMING").getAsString());
        assertEquals("absent", journal.getAsJsonObject("planJournalStates").get("ROLLED_BACK").getAsString());
    }

    private JsonObject fixture(String name) {
        String path = "fixtures/node-replacement/migration/" + name;
        try (var stream = getClass().getClassLoader().getResourceAsStream(path)) {
            if (stream == null) {
                throw new IllegalStateException("Missing fixture: " + path);
            }
            return GSON.fromJson(new InputStreamReader(stream, StandardCharsets.UTF_8), JsonObject.class);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read fixture: " + path, exception);
        }
    }
}
