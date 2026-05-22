package restudio.resync.flow;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.scheduler.BukkitTask;
import restudio.flow.data.FlowDataType;
import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowNode;
import restudio.resync.ReSync;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public class FlowContext {
    private final Player player;
    private final Event event;
    private final FlowRuntime runtime;
    private final FlowExecutor executor;
    private final Map<String, CompletableFuture<Void>> asyncOperations = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<Void>> beforeContinuationOperations = new ConcurrentHashMap<>();
    private final List<String> triggeredOutputs = new CopyOnWriteArrayList<>();
    private final Consumer<String> deferredOutputConsumer;
    private final AtomicLong operationCounter = new AtomicLong(0);
    private volatile boolean synchronousCapture = true;

    public FlowContext(FlowRuntime runtime, Player player, Event event) {
        this(runtime, player, event, null, null);
    }

    public FlowContext(FlowRuntime runtime, Player player, Event event, Consumer<String> deferredOutputConsumer) {
        this(runtime, player, event, deferredOutputConsumer, null);
    }

    public FlowContext(FlowRuntime runtime, Player player, Event event, Consumer<String> deferredOutputConsumer, FlowExecutor executor) {
        this.runtime = runtime;
        this.player = player;
        this.event = event;
        this.deferredOutputConsumer = deferredOutputConsumer;
        this.executor = executor;
    }

    public Player getPlayer() {
        return player;
    }

    public Event getEvent() {
        return event;
    }

    public Map<String, Object> getLocalVariables() {
        return runtime.getLocalVariables();
    }

    public Map<String, Object> getGlobalVariables() {
        return runtime.getGlobalVariables();
    }

    public Object getVariable(String name) {
        return runtime.getVariable(name);
    }

    public void setVariable(String name, Object value) {
        runtime.setVariable(name, value);
    }

    public <T> T getVariable(String name, Class<T> type) {
        return runtime.getVariable(name, type);
    }

    public <T> T getVariable(String name, Class<T> type, T defaultValue) {
        return runtime.getVariable(name, type, defaultValue);
    }

    public Object getInputValue(FlowNode node, String pinName) {
        return runtime.resolveInput(node, pinName);
    }

    public <T> T getInputValue(FlowNode node, String pinName, Class<T> type) {
        if (type == null) {
            return (T) runtime.resolveInput(node, pinName);
        }
        Object value = runtime.resolveInput(node, pinName, type);
        if (value == null && type == Player.class && player != null) {
            return type.cast(player);
        }
        return type.cast(value);
    }

    public <T> T getInputValue(FlowNode node, String pinName, Class<T> type, T defaultValue) {
        if (type == null) {
            Object raw = runtime.resolveInput(node, pinName);
            return (T) (raw != null ? raw : defaultValue);
        }
        Object value = runtime.resolveInput(node, pinName, type);
        if (value == null && type == Player.class && player != null) {
            return type.cast(player);
        }
        if (value == null) return defaultValue;
        return type.cast(value);
    }

    public <T> T getInput(FlowNode node, String pinName, FlowDataType type) {
        if (type == null) {
            return (T) runtime.resolveInput(node, pinName);
        }
        Class<?> javaType = type.getJavaType();
        if (javaType == null) {
            return (T) runtime.resolveInput(node, pinName);
        }
        Object value = runtime.resolveInput(node, pinName, javaType);
        if (value == null && type == FlowDataType.PLAYER && player != null) {
            return (T) player;
        }
        return (T) value;
    }

    public <T> T getInput(FlowNode node, String pinName, FlowDataType type, T defaultValue) {
        T value = getInput(node, pinName, type);
        return value != null ? value : defaultValue;
    }

    public void triggerOutput(String pinName) {
        if (pinName == null || pinName.isBlank()) {
            return;
        }

        if (synchronousCapture || deferredOutputConsumer == null) {
            runtime.triggerOutput(pinName);
        }

        if (synchronousCapture) {
            triggeredOutputs.add(pinName);
            return;
        }

        if (deferredOutputConsumer != null) {
            deferredOutputConsumer.accept(pinName);
        }
    }

    public void finishSynchronousCapture() {
        this.synchronousCapture = false;
    }

    public List<String> consumeTriggeredOutputs() {
        List<String> outputs = List.copyOf(triggeredOutputs);
        triggeredOutputs.clear();
        return outputs;
    }

    public boolean hasPendingAsyncOperations() {
        return !asyncOperations.isEmpty();
    }

    private String nextOperationId(String prefix) {
        return prefix + '_' + operationCounter.incrementAndGet() + '_' + System.nanoTime();
    }

    private void trackAsyncOperation(String taskId, CompletableFuture<Void> future) {
        if (taskId == null || future == null) {
            return;
        }
        asyncOperations.put(taskId, future);
        future.whenComplete((v, e) -> asyncOperations.remove(taskId));
    }

    private void trackBeforeContinuationOperation(String taskId, CompletableFuture<Void> future) {
        if (taskId == null || future == null) {
            return;
        }
        beforeContinuationOperations.put(taskId, future);
        future.whenComplete((v, e) -> beforeContinuationOperations.remove(taskId));
    }

    public void setNodeOutput(String nodeId, String pinName, Object value) {
        runtime.setNodeOutput(nodeId, pinName, value);
    }

    public void setOutput(FlowNode node, String pinName, Object value) {
        runtime.setNodeOutput(runtime.findNodeId(node), pinName, value);
    }

    public String resolveNodeId(FlowNode node) {
        return runtime.findNodeId(node);
    }

    public TypeAdapterRegistry getTypeAdapter() {
        return runtime.getTypeAdapter();
    }

    public FlowRuntime getRuntime() {
        return runtime;
    }

    public CompletableFuture<Void> runAsync(Runnable runnable) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        String taskId = nextOperationId("async");
        trackAsyncOperation(taskId, future);
        Bukkit.getScheduler().runTaskAsynchronously(ReSync.getInstance(), () -> {
            try {
                runnable.run();
                future.complete(null);
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    public CompletableFuture<Void> runSync(Runnable runnable) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        String taskId = nextOperationId("sync");
        trackAsyncOperation(taskId, future);
        Bukkit.getScheduler().runTask(ReSync.getInstance(), () -> {
            try {
                runnable.run();
                future.complete(null);
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    public CompletableFuture<Void> runSyncBeforeContinuation(Runnable runnable) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        String taskId = nextOperationId("sync_continue");
        trackAsyncOperation(taskId, future);
        trackBeforeContinuationOperation(taskId, future);
        Bukkit.getScheduler().runTask(ReSync.getInstance(), () -> {
            try {
                runnable.run();
                future.complete(null);
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    public CompletableFuture<Void> runLater(Runnable runnable, long delayTicks) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        String taskId = nextOperationId("later");
        trackAsyncOperation(taskId, future);
        BukkitTask task = Bukkit.getScheduler().runTaskLater(ReSync.getInstance(), () -> {
            try {
                runnable.run();
                future.complete(null);
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        }, delayTicks);
        
        return future;
    }

    public CompletableFuture<Void> runLaterBeforeContinuation(Runnable runnable, long delayTicks) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        String taskId = nextOperationId("later_continue");
        trackAsyncOperation(taskId, future);
        trackBeforeContinuationOperation(taskId, future);
        BukkitTask task = Bukkit.getScheduler().runTaskLater(ReSync.getInstance(), () -> {
            try {
                runnable.run();
                future.complete(null);
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        }, delayTicks);

        return future;
    }

    public Map<String, CompletableFuture<Void>> getAsyncOperations() {
        return asyncOperations;
    }

    public boolean hasPendingBeforeContinuationOperations() {
        return !beforeContinuationOperations.isEmpty();
    }

    public Map<String, CompletableFuture<Void>> getBeforeContinuationOperations() {
        return beforeContinuationOperations;
    }

    public FlowContext createSubContext(Map<String, Object> variables) {
        FlowContext child = new FlowContext(runtime, player, event, deferredOutputConsumer, executor);
        if (variables != null) {
            child.getLocalVariables().putAll(variables);
        }
        return child;
    }

    public FlowGraph extractSubGraph(FlowNode node, String pinName) {
        String nodeId = runtime.findNodeId(node);
        if (nodeId == null || runtime.getGraph() == null) {
            return null;
        }
        return runtime.getGraph().extractSubGraph(nodeId, pinName);
    }

    public Boolean executeSubFlowBoolean(FlowGraph subGraph, FlowNode node) {
        return executeSubFlowBoolean(subGraph, node, null);
    }

    public Boolean executeSubFlowBoolean(FlowGraph subGraph, FlowNode node, Map<String, Object> extraInputs) {
        if (executor == null || subGraph == null) {
            return null;
        }
        String startNodeId = findSubFlowStartNodeId(subGraph);
        if (startNodeId == null) {
            return null;
        }
        String outputNodeId = findSubFlowOutputNodeId(subGraph, "condition");
        Map<String, Object> inputs = new HashMap<>(runtime.getLocalVariables());
        if (extraInputs != null) {
            inputs.putAll(extraInputs);
        }
        try {
            Object result = executor.executeSubFlow(subGraph, startNodeId, outputNodeId, "condition", player, event, inputs)
                .get(5, TimeUnit.SECONDS);
            return result instanceof Boolean b ? b : Boolean.valueOf(String.valueOf(result));
        } catch (Exception e) {
            return null;
        }
    }

    public Object executeSubFlowObject(FlowGraph subGraph, FlowNode node) {
        return executeSubFlowObject(subGraph, node, null);
    }

    public Object executeSubFlowObject(FlowGraph subGraph, FlowNode node, Map<String, Object> extraInputs) {
        if (executor == null || subGraph == null) {
            return null;
        }
        String startNodeId = findSubFlowStartNodeId(subGraph);
        if (startNodeId == null) {
            return null;
        }
        String outputNodeId = findSubFlowOutputNodeId(subGraph, "result");
        Map<String, Object> inputs = new HashMap<>(runtime.getLocalVariables());
        if (extraInputs != null) {
            inputs.putAll(extraInputs);
        }
        try {
            return executor.executeSubFlow(subGraph, startNodeId, outputNodeId, "result", player, event, inputs)
                .get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            return null;
        }
    }

    private static String findSubFlowStartNodeId(FlowGraph subGraph) {
        if (subGraph == null || subGraph.getNodes() == null || subGraph.getNodes().isEmpty()) {
            return null;
        }
        List<Map.Entry<String, FlowNode>> entries = new ArrayList<>(subGraph.getNodes().entrySet());
        entries.sort(Map.Entry.comparingByKey(String.CASE_INSENSITIVE_ORDER));
        return entries.getFirst().getKey();
    }

    private static String findSubFlowOutputNodeId(FlowGraph subGraph, String pinName) {
        if (subGraph == null || subGraph.getNodes() == null) {
            return null;
        }
        List<Map.Entry<String, FlowNode>> entries = new ArrayList<>(subGraph.getNodes().entrySet());
        entries.sort(Map.Entry.comparingByKey(String.CASE_INSENSITIVE_ORDER));

        List<String> terminalNodes = new ArrayList<>();
        for (Map.Entry<String, FlowNode> entry : entries) {
            String nodeId = entry.getKey();
            boolean hasOutgoing = false;
            for (restudio.flow.data.FlowConnection conn : subGraph.getConnectionsFromSource(nodeId)) {
                if (!"flow".equals(conn.getSourcePin())) {
                    continue;
                }
                hasOutgoing = true;
                break;
            }
            if (!hasOutgoing) {
                terminalNodes.add(nodeId);
            }
        }

        if (terminalNodes.size() == 1) {
            return terminalNodes.getFirst();
        }
        return entries.isEmpty() ? null : entries.getLast().getKey();
    }
}
