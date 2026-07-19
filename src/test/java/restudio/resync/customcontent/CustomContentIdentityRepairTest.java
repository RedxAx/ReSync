package restudio.resync.customcontent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.flow.data.CustomContentDefinition;
import restudio.flow.data.CustomContentGraphAdapter;
import restudio.flow.data.FlowConnection;
import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowNode;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CustomContentIdentityRepairTest {
    @TempDir
    Path tempDir;

    @Test
    void malformedFlowIdAssetKeepsItsActionsButRecoversTheOriginalItemIdentity() {
        CustomContentStorage storage = new CustomContentStorage(tempDir.toFile());
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

        storage = new CustomContentStorage(tempDir.toFile());
        CustomContentDefinition repaired = storage.get("blockingSword");

        assertNotNull(repaired);
        assertEquals("blockingSword", repaired.getId());
        assertEquals("DIAMOND_SWORD", repaired.getMaterial());
        assertEquals(42, repaired.getCustomModelData());
        assertEquals(original.getComponents(), repaired.getComponents());
        assertEquals(originalGraph.getId(), repaired.getGraph().getId());
        assertTrue(repaired.getGraph().getNodes().containsKey("latest-action"));
        assertNull(storage.get(originalGraph.getId()));
    }

    @Test
    void malformedItemAliasForBlockKeepsDetachedActionsWithoutReplacingTheBlockGraph() {
        CustomContentStorage storage = new CustomContentStorage(tempDir.toFile());
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

        storage = new CustomContentStorage(tempDir.toFile());
        CustomContentDefinition repaired = storage.get("reblock");

        assertNotNull(repaired);
        assertEquals("block", repaired.getType());
        assertEquals("STONE", repaired.getMaterial());
        assertTrue(repaired.getGraph().getNodes().containsKey("original-action"));
        assertTrue(repaired.getGraph().getNodes().containsKey("latest-action"));
        assertNull(storage.get(originalGraph.getId()));
    }
}
