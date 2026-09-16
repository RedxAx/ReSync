package restudio.resync.flow;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowSerializer;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.migration.LegacyRuntimeActivationGate;
import restudio.resync.storage.AssetPersistenceGate;
import restudio.resync.storage.AssetTransactionCoordinator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowStorageProjectMetadataHotPathTest {
    private static final ServerId SERVER = new ServerId(UUID.fromString("ce0d5543-e2cc-4cff-9d97-f61b239c111c"));

    @TempDir
    Path tempDir;

    @Test
    void repeatedSaveAndListNeverTraverseTheAssetTree() throws Exception {
        AtomicInteger traversals = new AtomicInteger();
        try (AssetTransactionCoordinator coordinator = coordinator()) {
            FlowStorage storage = storage(coordinator, traversals);
            FlowGraph graph = graph("hot-path");

            storage.saveGraph(graph);
            for (int attempt = 0; attempt < 5; attempt++) {
                String metadata = storage.getProjectMetadata("project");
                assertEquals(JsonParser.parseString(metadata),
                    JsonParser.parseString(storage.normalizeProjectMetadataPayload(metadata)));
                storage.saveProjectMetadata(metadata);
                assertEquals(List.of("hot-path"), storage.listGraphIds("flow"));
                assertEquals(List.of(), storage.listGuiIds());
                assertEquals(List.of(), storage.listScoreboardIds());
                assertEquals(List.of(), storage.listTabIds());
            }
            storage.deleteGraph("flow", "hot-path");

            assertEquals(0, traversals.get());
            assertEquals(List.of(), storage.listGraphIds("flow"));
        }
    }

    @Test
    void explicitReconciliationReportsMissingDurableAssetsWithoutMutatingCoordinatorState() throws Exception {
        AtomicInteger traversals = new AtomicInteger();
        try (AssetTransactionCoordinator coordinator = coordinator()) {
            FlowStorage storage = storage(coordinator, traversals);
            storage.saveGraph(graph("durable"));
            Path payload = coordinator.read(snapshot -> snapshot.path(
                new AssetTransactionCoordinator.AssetKey("flow", "durable")).orElseThrow());
            Files.delete(payload);

            FlowStorage.ProjectMetadataReconciliation reconciliation = storage.reconcileProjectMetadataAssets();

            assertEquals(0, traversals.get());
            assertEquals(1, reconciliation.inspectedResourceCount());
            assertEquals(Set.of(new AssetTransactionCoordinator.AssetKey("flow", "durable")),
                reconciliation.missingDurableResources());
            assertTrue(reconciliation.removedStaleResources().isEmpty());
            assertTrue(reconciliation.restoredPresentationResources().isEmpty());
            assertFalse(reconciliation.changed());
            assertTrue(hasResource(storage.getProjectMetadata("project"), "flow", "durable"));
            assertEquals(List.of("durable"), storage.listGraphIds("flow"));
        }
    }

    @Test
    void explicitReconciliationRestoresMissingPresentationFromValidDurableAuthority() throws Exception {
        AtomicInteger traversals = new AtomicInteger();
        try (AssetTransactionCoordinator coordinator = coordinator()) {
            FlowStorage storage = storage(coordinator, traversals);
            storage.saveGraph(graph("durable"));
            storage.saveProjectMetadata("{\"serverId\":\"project\",\"resources\":[]}");
            assertFalse(hasResource(storage.getProjectMetadata("project"), "flow", "durable"));

            FlowStorage.ProjectMetadataReconciliation reconciliation = storage.reconcileProjectMetadataAssets();

            assertEquals(0, traversals.get());
            assertEquals(1, reconciliation.inspectedResourceCount());
            assertTrue(reconciliation.missingDurableResources().isEmpty());
            assertEquals(Set.of(new AssetTransactionCoordinator.AssetKey("flow", "durable")),
                reconciliation.restoredPresentationResources());
            assertTrue(reconciliation.changed());
            assertTrue(hasResource(storage.getProjectMetadata("project"), "flow", "durable"));
        }
    }

    @Test
    void explicitReconciliationRemovesOnlyPresentationEntriesWithoutDurableAuthority() throws Exception {
        AtomicInteger traversals = new AtomicInteger();
        try (AssetTransactionCoordinator coordinator = coordinator()) {
            FlowStorage storage = storage(coordinator, traversals);
            storage.saveProjectMetadata("""
                {
                  "serverId": "project",
                  "resources": [
                    {"type":"flow","id":"ghost","displayName":"Ghost","path":"Blueprints/Flows","sortOrder":0}
                  ]
                }
                """);
            assertEquals(List.of(), storage.listGraphIds("flow"));
            assertEquals(0, traversals.get());

            FlowStorage.ProjectMetadataReconciliation reconciliation = storage.reconcileProjectMetadataAssets();

            assertEquals(0, traversals.get());
            assertEquals(0, reconciliation.inspectedResourceCount());
            assertTrue(reconciliation.changed());
            assertEquals(Set.of(new AssetTransactionCoordinator.AssetKey("flow", "ghost")),
                reconciliation.removedStaleResources());
            assertFalse(hasResource(storage.getProjectMetadata("project"), "flow", "ghost"));
        }
    }

    private AssetTransactionCoordinator coordinator() throws Exception {
        return AssetTransactionCoordinator.open(tempDir.resolve("assets"), new Gson());
    }

    private FlowStorage storage(AssetTransactionCoordinator coordinator, AtomicInteger traversals) {
        return new FlowStorage(tempDir.toFile(), LegacyRuntimeActivationGate.runtime(tempDir),
            new AssetPersistenceGate(tempDir), SERVER, coordinator, ignored -> traversals.incrementAndGet());
    }

    private FlowGraph graph(String id) {
        return FlowSerializer.deserialize("""
            {"id":"%s","version":2,"resourceType":"flow","nodes":{},"connections":[],"localVariables":[]}
            """.formatted(id));
    }

    private boolean hasResource(String json, String type, String id) {
        JsonObject metadata = JsonParser.parseString(json).getAsJsonObject();
        return metadata.has("resources") && metadata.getAsJsonArray("resources").asList().stream()
            .filter(element -> element != null && element.isJsonObject())
            .map(element -> element.getAsJsonObject())
            .anyMatch(resource -> type.equals(resource.get("type").getAsString())
                && id.equals(resource.get("id").getAsString()));
    }
}
