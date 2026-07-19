package restudio.flow.data;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class FlowGraphIndexInvalidationTest {
    @Test
    void multipleConnectionsFromOneSourceShareAStableIndexBucket() {
        FlowGraph graph = new FlowGraph("graph", Map.of(
            "source", node("source"),
            "first", node("first"),
            "second", node("second")
        ), List.of(
            new FlowConnection("source", "first", "first", "flow"),
            new FlowConnection("source", "second", "second", "flow")
        ), List.of());

        assertEquals(2, graph.getConnectionsFromSource("source").size());
        assertEquals(1, graph.getConnectionsToTarget("first").size());
        assertEquals(1, graph.getConnectionsToTarget("second").size());
    }

    @Test
    void sameSizeConnectionReplacementInvalidatesBothDirections() {
        FlowGraph graph = new FlowGraph("graph", Map.of(
            "source", node("source"),
            "first", node("first"),
            "second", node("second")
        ), List.of(new FlowConnection("source", "flow", "first", "flow")), List.of());
        assertEquals(1, graph.getConnectionsToTarget("first").size());

        graph.getConnections().set(0, new FlowConnection("source", "flow", "second", "flow"));

        assertEquals(0, graph.getConnectionsToTarget("first").size());
        assertEquals(1, graph.getConnectionsToTarget("second").size());
    }

    @Test
    void mutableNodeMapInvalidatesIdentityLookup() {
        FlowNode first = node("first");
        FlowNode second = node("second");
        FlowGraph graph = new FlowGraph("graph", Map.of("first", first), List.of(), List.of());
        assertEquals("first", graph.findNodeId(first));

        graph.getNodes().clear();
        graph.getNodes().put("second", second);

        assertNull(graph.findNodeId(first));
        assertEquals("second", graph.findNodeId(second));
    }

    private FlowNode node(String type) {
        return new FlowNode(type, 0, 0, Map.of());
    }
}
