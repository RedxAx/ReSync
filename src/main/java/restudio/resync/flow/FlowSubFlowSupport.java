package restudio.resync.flow;

import restudio.flow.data.FlowConnection;
import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowNode;
import restudio.resync.flow.handler.FlowHandlerException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class FlowSubFlowSupport {
    private FlowSubFlowSupport() {
    }

    public static String requireOutputNodeId(FlowGraph graph, String outputPin) {
        if (graph == null || graph.getNodes() == null || graph.getNodes().isEmpty()) {
            throw new FlowHandlerException("SUBFLOW_GRAPH_REQUIRED", "Subflow graph is empty",
                "Connect an executable subflow with one terminal output");
        }
        List<Map.Entry<String, FlowNode>> entries = new ArrayList<>(graph.getNodes().entrySet());
        entries.sort(Map.Entry.comparingByKey(String.CASE_INSENSITIVE_ORDER));
        List<String> terminals = new ArrayList<>();
        for (Map.Entry<String, FlowNode> entry : entries) {
            boolean hasOutgoingExecution = graph.getConnectionsFromSource(entry.getKey()).stream()
                .map(FlowConnection::getSourcePin)
                .anyMatch(FlowSubFlowSupport::isExecutionPin);
            if (!hasOutgoingExecution) {
                terminals.add(entry.getKey());
            }
        }
        if (terminals.isEmpty()) {
            throw new FlowHandlerException("SUBFLOW_OUTPUT_MISSING", "Subflow has no terminal output node",
                "Connect the subflow to one terminal node", Map.of("outputPin", outputPin != null ? outputPin : ""));
        }
        if (terminals.size() > 1) {
            throw new FlowHandlerException("SUBFLOW_OUTPUT_AMBIGUOUS", "Subflow has more than one terminal output node",
                "Connect all branches to one terminal node", Map.of("outputPin", outputPin != null ? outputPin : "", "terminalNodes", terminals));
        }
        return terminals.getFirst();
    }

    private static boolean isExecutionPin(String pin) {
        return "flow".equals(pin) || "next".equals(pin) || "loop".equals(pin) || "done".equals(pin)
            || "true".equals(pin) || "false".equals(pin) || pin != null && pin.startsWith("branch_");
    }
}
