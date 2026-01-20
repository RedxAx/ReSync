package restudio.resync.flow;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.scheduler.BukkitTask;
import restudio.flow.data.FlowConnection;
import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowNode;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class FlowExecutor {
    private final FlowRegistry registry;
    private final TypeAdapterRegistry typeAdapter;
    private final Map<String, Object> globalVariables;
    private final int maxExecutionSteps;
    private final boolean enableDebug;
    private final Map<String, Object> eventVariables = new java.util.concurrent.ConcurrentHashMap<>();
    private Map<String, BukkitTask> pendingTasks = new java.util.concurrent.ConcurrentHashMap<>();

    public FlowExecutor(FlowRegistry registry, TypeAdapterRegistry typeAdapter, Map<String, Object> globalVariables) {
        this(registry, typeAdapter, globalVariables, 10000, false);
    }

    public FlowExecutor(FlowRegistry registry, TypeAdapterRegistry typeAdapter, Map<String, Object> globalVariables, 
                       int maxExecutionSteps, boolean enableDebug) {
        this.registry = registry;
        this.typeAdapter = typeAdapter;
        this.globalVariables = globalVariables != null ? globalVariables : new java.util.HashMap<>();
        this.maxExecutionSteps = maxExecutionSteps;
        this.enableDebug = enableDebug;
    }

    public CompletableFuture<Void> execute(FlowGraph graph, String startNodeId, Player player, Event event) {
        FlowRuntime runtime = new FlowRuntime(graph, typeAdapter, globalVariables, eventVariables);
        return execute(runtime, startNodeId, player, event, 0);
    }

    private CompletableFuture<Void> execute(FlowRuntime runtime, String startNodeId, Player player, Event event, int steps) {
        if (steps >= maxExecutionSteps) {
            return CompletableFuture.failedFuture(new FlowExecutionException(
                "Flow execution exceeded maximum steps: " + maxExecutionSteps,
                null,
                startNodeId
            ));
        }

        FlowGraph graph = runtime.getGraph();
        FlowNode node = graph.getNodes().get(startNodeId);
        if (node == null) {
            if (enableDebug) {
                System.err.println("[Flow] Node not found: " + startNodeId);
            }
            return CompletableFuture.completedFuture(null);
        }
        FlowContext context = new FlowContext(runtime, player, event);

        if ("loop".equals(node.getType())) {
            return executeLoop(runtime, node, player, event, steps);
        }

        ensureInputNodesReady(runtime, node, player, event);

        var executor = registry.getExecutor(node.getType());
        if (executor == null) {
            if (enableDebug) {
                System.err.println("[Flow] No executor for node type: " + node.getType());
            }
            return findNextAndExecute(runtime, node, "flow", player, event, steps);
        }

        try {
            executor.accept(context, node);
            
            String outputPin = runtime.consumeTriggeredOutput();
            String pin = outputPin != null ? outputPin : "flow";
            return findNextAndExecute(runtime, node, pin, player, event, steps);
        } catch (Exception e) {
            return CompletableFuture.failedFuture(new FlowExecutionException(
                "Error executing node '" + node.getType() + "' (ID: " + startNodeId + ")", 
                e, 
                startNodeId
            ));
        }
    }

    private CompletableFuture<Void> findNextAndExecute(FlowRuntime runtime, FlowNode currentNode, String outputPin, 
                                                   Player player, Event event, int steps) {
        FlowGraph graph = runtime.getGraph();
        String nextNodeId = findTargetNode(graph, findNodeId(graph, currentNode), outputPin);
        
        if (nextNodeId == null) {
            return CompletableFuture.completedFuture(null);
        }
        
        return execute(runtime, nextNodeId, player, event, steps + 1);
    }

    private CompletableFuture<Void> executeLoop(FlowRuntime runtime, FlowNode loopNode, Player player, Event event, int steps) {
        FlowGraph graph = runtime.getGraph();
        String nodeId = findNodeId(graph, loopNode);
        if (nodeId == null) {
            return CompletableFuture.completedFuture(null);
        }
        Integer count = (Integer) runtime.resolveInput(loopNode, "count", Integer.class);
        int iterations = count != null ? Math.max(0, count) : 0;

        String loopTarget = findTargetNode(graph, nodeId, "loop");
        String completedTarget = findTargetNode(graph, nodeId, "completed");

        CompletableFuture<Void> future = CompletableFuture.completedFuture(null);
        for (int i = 0; i < iterations; i++) {
            int index = i;
            future = future.thenCompose(v -> {
                runtime.setNodeOutput(nodeId, "index", index);
                if (loopTarget == null) {
                    return CompletableFuture.completedFuture(null);
                }
                return execute(runtime, loopTarget, player, event, steps + 1);
            });
        }

        return future.thenCompose(v -> {
            if (completedTarget == null) {
                return CompletableFuture.completedFuture(null);
            }
            return execute(runtime, completedTarget, player, event, steps + 1);
        });
    }

    private void ensureInputNodesReady(FlowRuntime runtime, FlowNode node, Player player, Event event) {
        FlowGraph graph = runtime.getGraph();
        String nodeId = findNodeId(graph, node);
        if (nodeId == null || graph.getConnections() == null) {
            return;
        }

        for (FlowConnection conn : graph.getConnections()) {
            if (!conn.getTargetNodeId().equals(nodeId)) {
                continue;
            }
            if (!runtime.hasNodeOutput(conn.getSourceNodeId(), conn.getSourcePin())) {
                executeDataNode(runtime, conn.getSourceNodeId(), player, event);
            }
        }
    }

    private void executeDataNode(FlowRuntime runtime, String nodeId, Player player, Event event) {
        if (nodeId == null || runtime.isEvaluating(nodeId)) {
            return;
        }
        FlowNode sourceNode = runtime.getGraph().getNodes().get(nodeId);
        if (sourceNode == null) {
            return;
        }
        String type = sourceNode.getType();
        if (type != null && type.startsWith("event:")) {
            return;
        }

        var executor = registry.getExecutor(type);
        if (executor == null) {
            return;
        }

        runtime.beginEvaluating(nodeId);
        String previousPin = runtime.getTriggeredOutputPin();
        runtime.setTriggeredOutputPin(null);

        ensureInputNodesReady(runtime, sourceNode, player, event);
        try {
            executor.accept(new FlowContext(runtime, player, event), sourceNode);
        } catch (Exception e) {
            if (enableDebug) {
                System.err.println("[Flow] Error evaluating node '" + type + "' (ID: " + nodeId + "): " + e.getMessage());
            }
        }

        runtime.setTriggeredOutputPin(previousPin);
        runtime.endEvaluating(nodeId);
    }

    private String findNodeId(FlowGraph graph, FlowNode node) {
        for (var entry : graph.getNodes().entrySet()) {
            if (entry.getValue() == node) {
                return entry.getKey();
            }
        }
        return null;
    }

    private String findTargetNode(FlowGraph graph, String nodeId, String pinName) {
        if (graph.getConnections() == null) return null;
        for (FlowConnection conn : graph.getConnections()) {
            if (conn.getSourceNodeId().equals(nodeId) && conn.getSourcePin().equals(pinName)) {
                return conn.getTargetNodeId();
            }
        }
        return null;
    }

    public void setGlobalVariable(String name, Object value) {
        globalVariables.put(name, value);
    }

    public Object getGlobalVariable(String name) {
        return globalVariables.get(name);
    }

    public Map<String, Object> getGlobalVariables() {
        return globalVariables;
    }

    public Map<String, Object> getEventVariables() {
        return eventVariables;
    }

    public void setEventVariable(String name, Object value) {
        eventVariables.put(name, value);
    }

    public void clearEventVariables() {
        eventVariables.clear();
    }

    public void registerPendingTask(String taskId, BukkitTask task) {
        pendingTasks.put(taskId, task);
    }

    public void cancelPendingTasks() {
        for (BukkitTask task : pendingTasks.values()) {
            if (!task.isCancelled()) {
                task.cancel();
            }
        }
        pendingTasks.clear();
    }

    public static class FlowExecutionException extends Exception {
        private final String nodeId;

        public FlowExecutionException(String message, Throwable cause, String nodeId) {
            super(message, cause);
            this.nodeId = nodeId;
        }

        public String getNodeId() {
            return nodeId;
        }
    }
}
