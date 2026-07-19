package restudio.request;

import org.junit.jupiter.api.Test;
import restudio.flow.data.FlowConnection;
import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowNode;
import restudio.resync.flow.validation.FlowGraphDiagnostic;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReQuestModuleTest {
    @Test
    void unresolvedLiteralQuestProducesRecoverableGraphDiagnostic() {
        ReQuestModule module = new ReQuestModule(new ReQuestService());
        FlowGraph graph = graph("missing_quest");

        List<FlowGraphDiagnostic> diagnostics = module.validateGraph(graph);

        assertEquals(1, diagnostics.size());
        assertEquals("REQUEST_QUEST_UNRESOLVED", diagnostics.getFirst().code());
        assertEquals("quest", diagnostics.getFirst().pin());
    }

    @Test
    void existingOrConnectedQuestReferencesRemainValid() {
        ReQuestModule module = new ReQuestModule(new ReQuestService());

        assertTrue(module.validateGraph(graph("gather_logs")).isEmpty());
        assertTrue(module.validateGraph(connectedGraph()).isEmpty());
    }

    private FlowGraph graph(String questId) {
        FlowNode node = new FlowNode("request:start_quest", 0, 0, Map.of("quest", questId));
        return new FlowGraph("request:test", Map.of("start", node), List.of(), List.of());
    }

    private FlowGraph connectedGraph() {
        FlowNode source = new FlowNode("request:quest_info", 0, 0, Map.of("quest", "gather_logs"));
        FlowNode target = new FlowNode("request:start_quest", 0, 0, Map.of("quest", "missing_quest"));
        return new FlowGraph("request:test", Map.of("source", source, "target", target),
            List.of(new FlowConnection("source", "quest", "target", "quest")), List.of());
    }
}
