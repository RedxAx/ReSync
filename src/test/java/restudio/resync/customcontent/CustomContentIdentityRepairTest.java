package restudio.resync.customcontent;

import com.google.gson.Gson;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.flow.data.CustomContentDefinition;
import restudio.flow.data.CustomContentGraphAdapter;
import restudio.flow.data.FlowConnection;
import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowNode;
import restudio.resync.flow.migration.LegacyRuntimeActivationGate;
import restudio.resync.storage.AssetPersistenceGate;
import restudio.resync.storage.AssetTransactionCoordinator;
import restudio.resync.storage.CanonicalProjectMetadataFixture;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CustomContentIdentityRepairTest {
    @TempDir
    Path tempDir;
    private AssetPersistenceGate gate;
    private AssetTransactionCoordinator coordinator;

    @BeforeEach
    void setUp() throws Exception {
        gate = new AssetPersistenceGate(tempDir);
        coordinator = AssetTransactionCoordinator.open(tempDir.resolve("assets"), new Gson());
        CanonicalProjectMetadataFixture.seed(coordinator);
    }

    @AfterEach
    void tearDown() throws Exception {
        coordinator.close();
    }

    @Test
    void malformedFlowIdAssetKeepsItsActionsButRecoversTheOriginalItemIdentity() throws Exception {
        CustomContentStorage storage = storage();
        FlowGraph originalGraph = CustomContentGraphAdapter.createContentGraph("blockingSword", "item", "Shielding Sword");
        CustomContentGraphAdapter.setContentProperty(originalGraph, "material", "DIAMOND_SWORD");
        CustomContentGraphAdapter.setContentProperty(originalGraph, "custom_model_data", 42);
        CustomContentDefinition original = CustomContentGraphAdapter.toDefinition(originalGraph);
        storage.save(original);

        FlowGraph malformedGraph = CustomContentGraphAdapter.createContentGraph("placeholder", "item", originalGraph.getId());
        malformedGraph.setId(originalGraph.getId());
        CustomContentGraphAdapter.setContentProperty(malformedGraph, "content_id", originalGraph.getId());
        CustomContentGraphAdapter.setContentProperty(malformedGraph, "name", originalGraph.getId());
        malformedGraph.getNodes().put("latest-action", new FlowNode("server.system_broadcast", 300, 120, Map.of("message", "Latest")));
        CustomContentDefinition malformed = CustomContentGraphAdapter.toDefinition(malformedGraph);
        storage.save(malformed);

        gate.quiesce();
        storage.close();
        gate.resume();
        storage = storage();
        CustomContentDefinition repaired = storage.get("blockingSword");

        assertNotNull(repaired);
        assertEquals("blockingSword", repaired.getId());
        assertEquals("DIAMOND_SWORD", repaired.getMaterial());
        assertEquals(42, repaired.getCustomModelData());
        assertEquals(original.getComponents(), repaired.getComponents());
        assertEquals(originalGraph.getId(), repaired.getGraph().getId());
        assertTrue(repaired.getGraph().getNodes().containsKey("latest-action"));
        assertNull(storage.get(originalGraph.getId()));
        gate.quiesce();
        storage.close();
    }

    @Test
    void malformedItemAliasForBlockKeepsDetachedActionsWithoutReplacingTheBlockGraph() throws Exception {
        CustomContentStorage storage = storage();
        FlowGraph originalGraph = CustomContentGraphAdapter.createContentGraph("reblock", "block", "Berger");
        originalGraph.getNodes().put("original-action", new FlowNode("title.action.bar", 300, 120, Map.of("text", "Original")));
        CustomContentDefinition original = CustomContentGraphAdapter.toDefinition(originalGraph);
        storage.save(original);

        FlowGraph malformedGraph = CustomContentGraphAdapter.createContentGraph("placeholder", "item", originalGraph.getId());
        malformedGraph.setId(originalGraph.getId());
        CustomContentGraphAdapter.setContentProperty(malformedGraph, "content_id", originalGraph.getId());
        malformedGraph.getNodes().put("latest-action", new FlowNode("title.action.bar", 300, 120, Map.of("text", "Latest")));
        String malformedStartId = malformedGraph.findNodeId(CustomContentGraphAdapter.findStartNode(malformedGraph));
        malformedGraph.getConnections().add(new FlowConnection(malformedStartId, "while_holding", "latest-action", "flow"));
        storage.save(CustomContentGraphAdapter.toDefinition(malformedGraph));

        gate.quiesce();
        storage.close();
        gate.resume();
        storage = storage();
        CustomContentDefinition repaired = storage.get("reblock");

        assertNotNull(repaired);
        assertEquals("block", repaired.getType());
        assertEquals("STONE", repaired.getMaterial());
        assertTrue(repaired.getGraph().getNodes().containsKey("original-action"));
        assertTrue(repaired.getGraph().getNodes().containsKey("latest-action"));
        assertNull(storage.get(originalGraph.getId()));
        gate.quiesce();
        storage.close();
    }

    private CustomContentStorage storage() {
        return new CustomContentStorage(null, tempDir, new ItemAttributeSchemaService(),
            LegacyRuntimeActivationGate.compatibility(tempDir), gate, coordinator);
    }
}
