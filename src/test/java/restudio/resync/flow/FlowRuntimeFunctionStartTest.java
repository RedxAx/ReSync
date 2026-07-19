package restudio.resync.flow;

import org.junit.jupiter.api.Test;
import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowNode;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class FlowRuntimeFunctionStartTest {
    @Test
    void functionStartNeverFallsBackToAnArbitraryNode() {
        FlowGraph graph = new FlowGraph();
        graph.getNodes().put("ordinary", new FlowNode("math.add", 0, 0, Map.of()));
        FlowRuntime runtime = new FlowRuntime(graph, new TypeAdapterRegistry(), Map.of());

        assertNull(runtime.findFunctionStartNodeId());
    }

    @Test
    void functionStartSelectionIsStableWhenLegacyAndCanonicalStartsExist() {
        FlowGraph graph = new FlowGraph();
        graph.getNodes().put("z", new FlowNode("function_start", 0, 0, Map.of()));
        graph.getNodes().put("a", new FlowNode("function.start", 0, 0, Map.of()));
        FlowRuntime runtime = new FlowRuntime(graph, new TypeAdapterRegistry(), Map.of());

        assertEquals("a", runtime.findFunctionStartNodeId());
    }
}
