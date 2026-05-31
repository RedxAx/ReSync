package restudio.resync.flow;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import restudio.flow.data.FlowConnection;
import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public final class FlowPredicateSupport {
    private FlowPredicateSupport() {
    }

    public static boolean evaluate(FlowStorage storage, FlowExecutor executor, String flowId, Player player, Event event, Map<String, Object> vars) {
        if (storage == null || executor == null || flowId == null || flowId.isBlank()) {
            return true;
        }
        FlowGraph graph = storage.getGraph(flowId);
        if (graph == null) {
            return false;
        }
        String startNodeId = findStartNodeId(graph);
        String outputNodeId = findOutputNodeId(graph);
        if (startNodeId == null || outputNodeId == null) {
            return false;
        }
        Map<String, Object> inputs = new HashMap<>();
        if (vars != null) {
            inputs.putAll(vars);
        }
        try {
            Object result = executor.executeSubFlow(graph, startNodeId, outputNodeId, "condition", player, event, inputs).get(5, TimeUnit.SECONDS);
            return result instanceof Boolean value ? value : Boolean.parseBoolean(String.valueOf(result));
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String findStartNodeId(FlowGraph graph) {
        if (graph == null || graph.getNodes() == null || graph.getNodes().isEmpty()) {
            return null;
        }
        List<Map.Entry<String, FlowNode>> entries = new ArrayList<>(graph.getNodes().entrySet());
        entries.sort(Map.Entry.comparingByKey(String.CASE_INSENSITIVE_ORDER));
        return entries.getFirst().getKey();
    }

    private static String findOutputNodeId(FlowGraph graph) {
        if (graph == null || graph.getNodes() == null) {
            return null;
        }
        List<Map.Entry<String, FlowNode>> entries = new ArrayList<>(graph.getNodes().entrySet());
        entries.sort(Map.Entry.comparingByKey(String.CASE_INSENSITIVE_ORDER));
        List<String> terminals = new ArrayList<>();
        for (Map.Entry<String, FlowNode> entry : entries) {
            String nodeId = entry.getKey();
            boolean hasFlowOutput = false;
            for (FlowConnection connection : graph.getConnectionsFromSource(nodeId)) {
                if ("flow".equals(connection.getSourcePin())) {
                    hasFlowOutput = true;
                    break;
                }
            }
            if (!hasFlowOutput) {
                terminals.add(nodeId);
            }
        }
        if (terminals.size() == 1) {
            return terminals.getFirst();
        }
        return entries.isEmpty() ? null : entries.getLast().getKey();
    }
}
