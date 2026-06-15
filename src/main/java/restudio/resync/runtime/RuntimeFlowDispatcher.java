package restudio.resync.runtime;

import com.google.gson.JsonObject;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import restudio.flow.data.FlowConnection;
import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowNode;
import restudio.resync.flow.FlowExecutor;
import restudio.resync.flow.FlowStorage;
import restudio.resync.flow.FunctionCallSupport;

import java.util.HashMap;
import java.util.Map;

public class RuntimeFlowDispatcher {
    private final FlowStorage flowStorage;
    private final FlowExecutor executor;

    public RuntimeFlowDispatcher(FlowStorage flowStorage, FlowExecutor executor) {
        this.flowStorage = flowStorage;
        this.executor = executor;
    }

    public boolean dispatch(String flowId, Player player, Event event, Map<String, Object> variables) {
        if (flowId == null || flowId.isBlank() || flowStorage == null || executor == null) {
            return false;
        }
        FlowGraph graph = flowStorage.getGraph(flowId);
        if (graph == null || graph.getNodes() == null || graph.getNodes().isEmpty()) {
            return false;
        }
        String startNode = findStartNode(graph);
        if (startNode == null) {
            return false;
        }
        Map<String, Object> safeVariables = variables != null ? new HashMap<>(variables) : new HashMap<>();
        executor.execute(graph, startNode, player, event, safeVariables);
        return true;
    }

    public boolean dispatchFunction(JsonObject call, Player player, Event event, Map<String, Object> variables) {
        if (call == null || call.isEmpty() || flowStorage == null || executor == null) {
            return false;
        }
        FunctionCallSupport.execute(flowStorage, executor, call, player, event, variables != null ? new HashMap<>(variables) : new HashMap<>());
        return true;
    }

    private String findStartNode(FlowGraph graph) {
        for (Map.Entry<String, FlowNode> entry : graph.getNodes().entrySet()) {
            boolean hasIncomingFlow = false;
            for (FlowConnection connection : graph.getConnections()) {
                if (entry.getKey().equals(connection.getTargetNodeId()) && ("flow".equals(connection.getTargetPin()) || "next".equals(connection.getTargetPin()))) {
                    hasIncomingFlow = true;
                    break;
                }
            }
            if (!hasIncomingFlow) {
                return entry.getKey();
            }
        }
        return graph.getNodes().keySet().stream().sorted(String.CASE_INSENSITIVE_ORDER).findFirst().orElse(null);
    }
}
