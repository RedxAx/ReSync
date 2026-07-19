package restudio.resync.flow;

import org.junit.jupiter.api.Test;
import restudio.flow.data.FlowConnection;
import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowNode;
import restudio.resync.flow.handler.FlowHandlerException;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FlowSubFlowSupportTest {
    @Test
    void emptySubflowsFailWithAnExplicitContractDiagnostic() {
        FlowHandlerException failure = assertThrows(FlowHandlerException.class,
            () -> FlowSubFlowSupport.requireOutputNodeId(new FlowGraph(), "condition"));

        assertEquals("SUBFLOW_GRAPH_REQUIRED", failure.getCode());
    }

    @Test
    void ambiguousTerminalNodesNeverFallBackToArbitraryOrdering() {
        FlowGraph graph = new FlowGraph();
        graph.getNodes().put("first", new FlowNode("test", 0, 0, Map.of()));
        graph.getNodes().put("second", new FlowNode("test", 0, 0, Map.of()));

        FlowHandlerException failure = assertThrows(FlowHandlerException.class,
            () -> FlowSubFlowSupport.requireOutputNodeId(graph, "result"));

        assertEquals("SUBFLOW_OUTPUT_AMBIGUOUS", failure.getCode());
    }

    @Test
    void executionAliasesParticipateInTerminalResolution() {
        FlowGraph graph = new FlowGraph();
        graph.getNodes().put("entry", new FlowNode("test", 0, 0, Map.of()));
        graph.getNodes().put("terminal", new FlowNode("test", 0, 0, Map.of()));
        graph.getConnections().add(new FlowConnection("entry", "next", "terminal", "flow"));

        assertEquals("terminal", FlowSubFlowSupport.requireOutputNodeId(graph, "result"));
    }
}
