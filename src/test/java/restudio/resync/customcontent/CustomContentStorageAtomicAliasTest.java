package restudio.resync.customcontent;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.flow.data.CustomContentDefinition;
import restudio.flow.data.CustomContentGraphAdapter;
import restudio.flow.data.FlowGraph;
import restudio.resync.resources.AssetFileFormat;
import restudio.resync.resources.JsonAssetStore;
import restudio.resync.resources.ReSyncResourceCatalog;
import restudio.resync.storage.AssetPersistenceGate;
import restudio.resync.storage.AssetTransactionCoordinator;
import restudio.resync.storage.AssetTransactionCoordinator.AssetKey;
import restudio.resync.storage.AssetTransactionCoordinator.AssetDelta;
import restudio.resync.storage.AssetTransactionCoordinator.Deleted;
import restudio.resync.storage.AssetTransactionCoordinator.TransactionRequest;
import restudio.resync.storage.CanonicalProjectMetadataFixture;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CustomContentStorageAtomicAliasTest {
    private static final UUID ALIAS_MUTATION = UUID.fromString("41414141-4141-4141-8141-414141414141");
    private static final UUID REPAIR_MUTATION = UUID.fromString("42424242-4242-4242-8242-424242424242");
    private static final UUID OPAQUE_INJECTION = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID LOSSLESS_SAVE = UUID.fromString("45454545-4545-4545-8545-454545454545");

    @TempDir
    Path tempDir;

    @Test
    void canonicalSaveAndAliasTombstoneCommitWithProjectMetadataAsOneMutation() throws Exception {
        Path assets = tempDir.resolve("assets");
        Files.createDirectories(assets);
        Files.writeString(assets.resolve("project.json"), """
            {
              "unknown": {"keep": true},
              "resources": [
                {"type": "custom_content", "id": "shared_flow", "label": "legacy"},
                {"type": "other", "id": "keep", "extra": 7}
              ]
            }
            """);
        AssetPersistenceGate gate = new AssetPersistenceGate(tempDir);
        String project = Files.readString(assets.resolve("project.json"));
        AssetTransactionCoordinator.AdoptionInventory adoption = new AssetTransactionCoordinator.AdoptionInventory(
            "custom-content-atomic-alias-test", project, List.of());
        try (AssetTransactionCoordinator coordinator = AssetTransactionCoordinator.adoptExisting(assets, new Gson(), adoption)) {
            CanonicalProjectMetadataFixture.seed(coordinator);
            CustomContentStorage storage = new CustomContentStorage(tempDir.toFile(), gate, coordinator);
            storage.save(malformedAlias("shared_flow"), ALIAS_MUTATION, 0L);
            long beforeRepair = coordinator.read(AssetTransactionCoordinator.Snapshot::rootSequence);

            CustomContentDefinition canonical = canonical("canonical_item", "shared_flow");
            storage.save(canonical, REPAIR_MUTATION, 0L);

            AssetTransactionCoordinator.Snapshot committed = coordinator.read(snapshot -> snapshot);
            assertEquals(beforeRepair + 1L, committed.rootSequence());
            assertInstanceOf(Deleted.class,
                committed.state(new AssetKey(ReSyncResourceCatalog.CUSTOM_CONTENT, "shared_flow")).orElseThrow());
            assertEquals(REPAIR_MUTATION, storage.readMutationStamp("canonical_item").mutationId());
            assertEquals(REPAIR_MUTATION, storage.readMutationStamp("shared_flow").mutationId());
            assertTrue(storage.readMutationStamp("shared_flow").deleted());
            assertNull(storage.get("shared_flow"));

            JsonObject committedProject = committed.metadata().document();
            assertTrue(committedProject.getAsJsonObject("unknown").get("keep").getAsBoolean());
            assertEquals(2, committedProject.getAsJsonArray("resources").size());
            JsonObject retained = committedProject.getAsJsonArray("resources").asList().stream()
                .map(element -> element.getAsJsonObject())
                .filter(resource -> "other".equals(resource.get("type").getAsString()))
                .findFirst()
                .orElseThrow();
            assertEquals("keep", retained.get("id").getAsString());
            assertEquals(7, retained.get("extra").getAsInt());
            assertTrue(committedProject.getAsJsonArray("resources").asList().stream()
                .map(element -> element.getAsJsonObject())
                .anyMatch(resource -> "custom_content".equals(resource.get("type").getAsString())
                    && "canonical_item".equals(resource.get("id").getAsString())));
            assertFalse(committedProject.getAsJsonArray("resources").asList().stream()
                .map(element -> element.getAsJsonObject())
                .anyMatch(resource -> "shared_flow".equalsIgnoreCase(resource.get("id").getAsString())));

            storage.save(canonical, REPAIR_MUTATION, 0L);
            assertEquals(committed.rootSequence(), coordinator.read(AssetTransactionCoordinator.Snapshot::rootSequence));
            assertEquals(REPAIR_MUTATION, storage.readMutationStamp("shared_flow").mutationId());

            gate.quiesce();
            storage.close();
            gate.resume();
            CustomContentStorage reopened = new CustomContentStorage(tempDir.toFile(), gate, coordinator);
            assertEquals(committed.rootSequence(), coordinator.read(AssetTransactionCoordinator.Snapshot::rootSequence));
            assertEquals(REPAIR_MUTATION, reopened.readMutationStamp("canonical_item").mutationId());
            assertEquals(REPAIR_MUTATION, reopened.readMutationStamp("shared_flow").mutationId());
            reopened.save(canonical, REPAIR_MUTATION, 0L);
            assertEquals(committed.rootSequence(), coordinator.read(AssetTransactionCoordinator.Snapshot::rootSequence));
            assertEquals(REPAIR_MUTATION, reopened.readMutationStamp("shared_flow").mutationId());
            gate.quiesce();
            reopened.close();
        }
    }

    @Test
    void closedOrQuiescedStorageRejectsReadsAndMutations() throws Exception {
        Path assets = tempDir.resolve("assets");
        AssetPersistenceGate gate = new AssetPersistenceGate(tempDir);
        try (AssetTransactionCoordinator coordinator = AssetTransactionCoordinator.open(assets, new Gson())) {
            CanonicalProjectMetadataFixture.seed(coordinator);
            CustomContentStorage storage = new CustomContentStorage(tempDir.toFile(), gate, coordinator);
            gate.quiesce();
            assertThrows(IllegalStateException.class, storage::listIds);
            assertThrows(IllegalStateException.class, () -> storage.save(canonical("blocked", "blocked_flow")));
            gate.resume();
            gate.quiesce();
            storage.close();
            gate.resume();
            assertThrows(IllegalStateException.class, storage::listIds);
            assertThrows(IllegalStateException.class, () -> storage.save(canonical("closed", "closed_flow")));
            assertFalse(coordinator.read(snapshot -> snapshot).states().containsKey(
                new AssetKey(ReSyncResourceCatalog.CUSTOM_CONTENT, "closed")));
        }
    }

    @Test
    void rebindKeepsStorageClosedUntilTheSharedGatePublishesTheCandidate() throws Exception {
        Path activeAssets = tempDir.resolve("assets");
        Path candidateRoot = Files.createDirectory(tempDir.resolve("candidate"));
        Path candidateAssets = candidateRoot.resolve("assets");
        AssetPersistenceGate gate = new AssetPersistenceGate(tempDir);
        try (AssetTransactionCoordinator active = AssetTransactionCoordinator.open(activeAssets, new Gson());
             AssetTransactionCoordinator candidate = AssetTransactionCoordinator.open(candidateAssets, new Gson())) {
            CanonicalProjectMetadataFixture.seed(active);
            CanonicalProjectMetadataFixture.seed(candidate);
            CustomContentStorage storage = new CustomContentStorage(tempDir.toFile(), gate, active);
            storage.save(canonical("active", "active_flow"));

            gate.quiesce();
            storage.rebindPersistence(candidateRoot, candidate);
            gate.rebind(candidateRoot);
            assertThrows(IllegalStateException.class, storage::listIds);
            assertThrows(IllegalStateException.class, () -> storage.save(canonical("too_early", "candidate_flow")));

            storage.resumePersistence();
            assertThrows(IllegalStateException.class, storage::listIds);
            gate.resume();
            assertNull(storage.get("active"));
            storage.save(canonical("candidate", "candidate_flow"));
            assertNotNull(storage.readMutationStamp("candidate"));

            gate.quiesce();
            storage.close();
            gate.resume();
            assertThrows(IllegalStateException.class, storage::listIds);
        }
    }

    @Test
    void savePreservesUnknownDefinitionAndGraphFieldsFromTheAuthoritativePayload() throws Exception {
        Path assets = tempDir.resolve("assets");
        AssetPersistenceGate gate = new AssetPersistenceGate(tempDir);
        try (AssetTransactionCoordinator coordinator = AssetTransactionCoordinator.open(assets, new Gson())) {
            CanonicalProjectMetadataFixture.seed(coordinator);
            CustomContentStorage storage = new CustomContentStorage(tempDir.toFile(), gate, coordinator);
            storage.save(canonical("lossless", "lossless_flow"), ALIAS_MUTATION, 0L);

            AssetKey key = new AssetKey(ReSyncResourceCatalog.CUSTOM_CONTENT, "lossless");
            AssetTransactionCoordinator.Snapshot snapshot = coordinator.read(current -> current);
            Path path = snapshot.path(key).orElseThrow();
            JsonObject injected = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
            injected.add("futureDefinition", JsonParser.parseString("{\"mode\":\"keep\",\"count\":3}"));
            injected.getAsJsonObject("graph").add("futureGraph", JsonParser.parseString("[\"keep\",9]"));
            String payload = AssetFileFormat.withResourceIdentity(injected.toString(), ReSyncResourceCatalog.CUSTOM_CONTENT,
                snapshot.state(key).orElseThrow().revision() + 1L, OPAQUE_INJECTION.toString());
            coordinator.transact(new TransactionRequest(OPAQUE_INJECTION, snapshot.project(),
                List.of(AssetDelta.write(key, path, snapshot.state(key).orElseThrow(), payload.getBytes(StandardCharsets.UTF_8))), List.of()));

            CustomContentDefinition definition = storage.get("lossless");
            definition.setDisplayName("Changed");
            storage.save(definition, LOSSLESS_SAVE, 2L);

            JsonObject saved = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
            assertEquals("keep", saved.getAsJsonObject("futureDefinition").get("mode").getAsString());
            assertEquals(3, saved.getAsJsonObject("futureDefinition").get("count").getAsInt());
            assertEquals("keep", saved.getAsJsonObject("graph").getAsJsonArray("futureGraph").get(0).getAsString());
            assertEquals(9, saved.getAsJsonObject("graph").getAsJsonArray("futureGraph").get(1).getAsInt());

            gate.quiesce();
            storage.close();
        }
    }

    @Test
    void sameMutationReplayRejectsAnUnrelatedDeletedResource() {
        List<JsonAssetStore.ReplayOperation> operations = List.of(
            new JsonAssetStore.ReplayOperation("canonical_item", false),
            new JsonAssetStore.ReplayOperation("unrelated_item", true));

        assertThrows(IllegalStateException.class,
            () -> CustomContentStorage.historicalFlowAliases("canonical_item", "shared_flow", operations));
    }

    private static CustomContentDefinition malformedAlias(String flowId) {
        FlowGraph graph = CustomContentGraphAdapter.createContentGraph("placeholder", "item", flowId);
        graph.setId(flowId);
        CustomContentGraphAdapter.setContentProperty(graph, "content_id", flowId);
        return CustomContentGraphAdapter.toDefinition(graph);
    }

    private static CustomContentDefinition canonical(String id, String flowId) {
        FlowGraph graph = CustomContentGraphAdapter.createContentGraph(id, "item", "Canonical");
        graph.setId(flowId);
        CustomContentGraphAdapter.setContentProperty(graph, "content_id", id);
        return CustomContentGraphAdapter.toDefinition(graph);
    }
}
