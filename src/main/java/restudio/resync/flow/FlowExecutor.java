package restudio.resync.flow;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.scheduler.BukkitTask;
import restudio.flow.data.FlowConnection;
import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowNode;
import restudio.flow.data.FlowSerializer;
import restudio.resync.Log;
import restudio.resync.ReSync;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.NodeHandler;
import restudio.resync.flow.diagnostics.FlowDebugService;
import restudio.resync.flow.diagnostics.FlowTraceRecord;
import restudio.resync.flow.diagnostics.FlowTraceService;
import restudio.resync.flow.migration.IdCompatibilityLayer;
import restudio.resync.flow.registry.NodeDefinition;
import restudio.resync.flow.registry.NodeDefinitionRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

public class FlowExecutor {
    private final HandlerRegistry handlerRegistry;
    private final NodeDefinitionRegistry nodeDefinitionRegistry;
    private final TypeAdapterRegistry typeAdapter;
    private final Map<String, Object> globalVariables;
    private final int maxExecutionSteps;
    private final boolean enableDebug;
    private final IdCompatibilityLayer idCompatibility;
    private final Map<String, Object> eventVariables = new java.util.concurrent.ConcurrentHashMap<>();
    private Map<String, BukkitTask> pendingTasks = new java.util.concurrent.ConcurrentHashMap<>();
    private final List<FlowExecutionListener> executionListeners = new CopyOnWriteArrayList<>();
    private FlowTraceService traceService;
    private FlowDebugService debugService;

    public FlowExecutor(HandlerRegistry handlerRegistry, TypeAdapterRegistry typeAdapter, Map<String, Object> globalVariables) {
        this(handlerRegistry, null, typeAdapter, globalVariables, 10000, false);
    }

    public FlowExecutor(HandlerRegistry handlerRegistry, NodeDefinitionRegistry nodeDefinitionRegistry, TypeAdapterRegistry typeAdapter, Map<String, Object> globalVariables) {
        this(handlerRegistry, nodeDefinitionRegistry, typeAdapter, globalVariables, 10000, false);
    }

    public FlowExecutor(HandlerRegistry handlerRegistry, TypeAdapterRegistry typeAdapter, Map<String, Object> globalVariables,
                       int maxExecutionSteps, boolean enableDebug) {
        this(handlerRegistry, null, typeAdapter, globalVariables, maxExecutionSteps, enableDebug);
    }

    public FlowExecutor(HandlerRegistry handlerRegistry, NodeDefinitionRegistry nodeDefinitionRegistry, TypeAdapterRegistry typeAdapter, Map<String, Object> globalVariables,
                        int maxExecutionSteps, boolean enableDebug) {
        this.handlerRegistry = handlerRegistry;
        this.nodeDefinitionRegistry = nodeDefinitionRegistry;
        this.typeAdapter = typeAdapter;
        this.globalVariables = globalVariables != null ? globalVariables : new HashMap<>();
        this.maxExecutionSteps = maxExecutionSteps;
        this.enableDebug = enableDebug;
        this.idCompatibility = new IdCompatibilityLayer();
    }

    public CompletableFuture<Void> execute(FlowGraph graph, String startNodeId, Player player, Event event) {
        return execute(graph, startNodeId, player, event, new HashMap<>());
    }

    public CompletableFuture<Void> execute(FlowGraph graph, String startNodeId, Player player, Event event,
                                           Map<String, Object> eventVars) {
        notifyExecutionListeners(graph, startNodeId, player, event);
        FlowRuntime runtime = new FlowRuntime(graph, typeAdapter, globalVariables, eventVars);
        CompletableFuture<Void> future = execute(runtime, startNodeId, player, event, 0);
        future.whenComplete((result, ex) -> runtime.cleanupThreadLocals());
        return future;
    }

    public CompletableFuture<Object> executeSubFlow(FlowGraph subGraph, String startNodeId, String outputNodeId, String outputPin,
                                                     Player player, Event event, Map<String, Object> localInputs) {
        FlowRuntime runtime = new FlowRuntime(subGraph, typeAdapter, globalVariables);
        if (localInputs != null) {
            runtime.getLocalVariables().putAll(localInputs);
        }
        return execute(runtime, startNodeId, player, event, 0)
            .thenApply(v -> runtime.getNodeOutput(outputNodeId, outputPin));
    }

    public void addExecutionListener(FlowExecutionListener listener) {
        if (listener != null) {
            executionListeners.add(listener);
        }
    }

    public void removeExecutionListener(FlowExecutionListener listener) {
        executionListeners.remove(listener);
    }

    public void setTraceService(FlowTraceService traceService) {
        this.traceService = traceService;
    }

    public void setDebugService(FlowDebugService debugService) {
        this.debugService = debugService;
    }

    private CompletableFuture<Void> execute(FlowRuntime runtime, String startNodeId, Player player, Event event, int steps) {
        if (steps >= maxExecutionSteps) {
            return CompletableFuture.failedFuture(new FlowExecutionException(
                "Flow execution exceeded maximum steps: " + maxExecutionSteps,
                null,
                startNodeId
            ));
        }

        if (startNodeId == null || !runtime.beginFlowExecution(startNodeId)) {
            return CompletableFuture.completedFuture(null);
        }

        FlowGraph graph = runtime.getGraph();
        FlowNode node = graph.getNodes().get(startNodeId);
        if (node == null) {
            if (enableDebug) {
                Log.warn("[Flow] Node not found: " + startNodeId);
            }
            runtime.endFlowExecution(startNodeId);
            return CompletableFuture.completedFuture(null);
        }

        runtime.consumeTriggeredOutput();
        long traceStarted = System.nanoTime();
        trace(startTraceRecord(graph, node, startNodeId, steps, "started", 0L, null));
        FlowDebugService debugger = debugService;
        if (debugger != null && debugger.isEnabled()) {
            return debugger.beforeNode(runtime, graph, node, startNodeId, steps, summarizeInputs(node))
                .whenComplete((ignored, failure) -> {
                    if (failure != null) {
                        runtime.endFlowExecution(startNodeId);
                    }
                })
                .thenCompose(ignored -> executePreparedNode(runtime, graph, node, startNodeId, player, event, steps, traceStarted));
        }
        return executePreparedNode(runtime, graph, node, startNodeId, player, event, steps, traceStarted);
    }

    private CompletableFuture<Void> executePreparedNode(FlowRuntime runtime, FlowGraph graph, FlowNode node, String startNodeId,
                                                        Player player, Event event, int steps, long traceStarted) {
        FlowContext context = new FlowContext(
                runtime,
                player,
                event,
                outputPin -> dispatchDeferredOutput(runtime, startNodeId, outputPin, player, event, steps),
                this
        );

        String type = node.getType();
        if (isLoopNode(type)) {
            ensureInputNodesReady(runtime, node, player, event);
            CompletableFuture<Void> result = executeLoopNode(runtime, node, player, event, steps);
            runtime.endFlowExecution(startNodeId);
            traceSuccess(runtime, graph, node, startNodeId, steps, traceStarted);
            return result;
        }

        ensureInputNodesReady(runtime, node, player, event);

        if (isCustomFunctionNode(type)) {
            CompletableFuture<Void> result = executeCustomFunctionNode(runtime, node, startNodeId, player, event, steps);
            runtime.endFlowExecution(startNodeId);
            traceSuccess(runtime, graph, node, startNodeId, steps, traceStarted);
            return result;
        }

        NodeHandler handler = resolveHandler(node);
        if (handler == null) {
            NodeDefinition triggerDefinition = resolveTriggerDefinition(node);
            if (triggerDefinition != null) {
                publishTriggerOutputs(runtime, startNodeId, triggerDefinition);
                CompletableFuture<Void> result = findNextAndExecute(runtime, startNodeId, "flow", player, event, steps);
                runtime.endFlowExecution(startNodeId);
                traceSuccess(runtime, graph, node, startNodeId, steps, traceStarted);
                return result;
            }
            runtime.endFlowExecution(startNodeId);
            FlowExecutionException exception = new FlowExecutionException(
                "No handler registered for node type: " + node.getType(),
                null,
                startNodeId
            );
            traceFailure(runtime, graph, node, startNodeId, steps, traceStarted, exception);
            return CompletableFuture.failedFuture(exception);
        }

        try {
            handler.execute(context, node);
            context.finishSynchronousCapture();

            List<String> outputPins = context.consumeTriggeredOutputs();
            String runtimeOutputPin = runtime.consumeTriggeredOutput();
            if (runtimeOutputPin != null && !runtimeOutputPin.isBlank() && !outputPins.contains(runtimeOutputPin)) {
                if (outputPins.isEmpty()) {
                    outputPins = List.of(runtimeOutputPin);
                } else {
                    List<String> merged = new ArrayList<>(outputPins);
                    merged.add(runtimeOutputPin);
                    outputPins = merged;
                }
            }

            CompletableFuture<Void> result;
            if (!outputPins.isEmpty()) {
                result = executeTriggeredOutputs(runtime, startNodeId, outputPins, player, event, steps);
            } else if (context.hasPendingAsyncOperations()) {
                result = CompletableFuture.allOf(
                    context.getAsyncOperations().values().toArray(new CompletableFuture[0])
                );
            } else {
                result = findNextAndExecute(runtime, startNodeId, "flow", player, event, steps);
            }
            runtime.endFlowExecution(startNodeId);
            traceSuccess(runtime, graph, node, startNodeId, steps, traceStarted);
            return result;
        } catch (Exception e) {
            runtime.endFlowExecution(startNodeId);
            traceFailure(runtime, graph, node, startNodeId, steps, traceStarted, e);
            return CompletableFuture.failedFuture(new FlowExecutionException(
                "Error executing node '" + node.getType() + "' (ID: " + startNodeId + ")",
                e,
                startNodeId
            ));
        }
    }

    private void dispatchDeferredOutput(FlowRuntime runtime, String currentNodeId, String outputPin,
                                        Player player, Event event, int steps) {
        if (outputPin == null || outputPin.isBlank()) {
            return;
        }

        Runnable continuation = () -> {
            try {
                executeTriggeredOutputs(runtime, currentNodeId, List.of(outputPin), player, event, steps)
                    .exceptionally(ex -> {
                        if (enableDebug) {
                            Log.warn("[Flow] Deferred output execution failed for node '" + currentNodeId + "': " + ex.getMessage(), ex);
                        }
                        return null;
                    });
            } catch (Exception ex) {
                if (enableDebug) {
                    Log.warn("[Flow] Deferred output dispatch failed for node '" + currentNodeId + "': " + ex.getMessage(), ex);
                }
            }
        };

        if (Bukkit.isPrimaryThread()) {
            continuation.run();
        } else {
            Bukkit.getScheduler().runTask(ReSync.getInstance(), continuation);
        }
    }

    private CompletableFuture<Void> executeTriggeredOutputs(FlowRuntime runtime, String currentNodeId, List<String> outputPins,
                                                            Player player, Event event, int steps) {
        if (outputPins == null || outputPins.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        List<CompletableFuture<Void>> futures = new java.util.ArrayList<>();
        for (String outputPin : outputPins) {
            if (outputPin == null || outputPin.isBlank()) {
                continue;
            }
            futures.add(findNextAndExecute(runtime, currentNodeId, outputPin, player, event, steps));
        }

        if (futures.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
    }

    private CompletableFuture<Void> findNextAndExecute(FlowRuntime runtime, FlowNode currentNode, String outputPin, 
                                                   Player player, Event event, int steps) {
        FlowGraph graph = runtime.getGraph();
        String currentNodeId = findNodeId(graph, currentNode);
        return findNextAndExecute(runtime, currentNodeId, outputPin, player, event, steps);
    }

    private CompletableFuture<Void> findNextAndExecute(FlowRuntime runtime, String currentNodeId, String outputPin,
                                                       Player player, Event event, int steps) {
        if (currentNodeId == null || outputPin == null || outputPin.isBlank()) {
            return CompletableFuture.completedFuture(null);
        }

        FlowGraph graph = runtime.getGraph();
        List<String> nextNodeIds = findTargetNodes(graph, currentNodeId, outputPin);
        traceTraversedConnections(runtime, graph, currentNodeId, outputPin, steps);

        if (nextNodeIds.isEmpty()) {
            if ("next".equals(outputPin)) {
                nextNodeIds = findTargetNodes(graph, currentNodeId, "flow");
                traceTraversedConnections(runtime, graph, currentNodeId, "flow", steps);
            } else if ("flow".equals(outputPin)) {
                nextNodeIds = findTargetNodes(graph, currentNodeId, "next");
                traceTraversedConnections(runtime, graph, currentNodeId, "next", steps);
            }
        }

        return executeTargets(runtime, nextNodeIds, player, event, steps + 1);
    }

    private void traceTraversedConnections(FlowRuntime runtime, FlowGraph graph, String currentNodeId, String outputPin, int steps) {
        FlowDebugService debugger = debugService;
        if (debugger == null || !debugger.isEnabled() || graph == null || currentNodeId == null || outputPin == null) {
            return;
        }
        for (FlowConnection connection : graph.getConnectionsFromSource(currentNodeId)) {
            if (outputPin.equals(connection.getSourcePin())) {
                debugger.connectionTraversed(runtime, graph, connection, steps);
            }
        }
    }

    private CompletableFuture<Void> executeCustomFunctionNode(FlowRuntime runtime, FlowNode node, String startNodeId,
                                                              Player player, Event event, int steps) {
        String functionId = extractCustomFunctionId(node.getType());
        if (functionId == null || functionId.isBlank()) {
            return findNextAndExecute(runtime, node, "flow", player, event, steps);
        }

        FlowStorage storage = FlowRuntimeAccess.getStorage();
        if (storage == null) {
            return findNextAndExecute(runtime, node, "flow", player, event, steps);
        }

        FlowGraph functionGraph = storage.getGraph(functionId);
        if (functionGraph == null || !functionGraph.isFunction()) {
            return findNextAndExecute(runtime, node, "flow", player, event, steps);
        }
        functionGraph = FlowSerializer.deserialize(FlowSerializer.serialize(functionGraph));

        Map<String, Object> callInputs = new HashMap<>();
        if (functionGraph.getFunctionInputs() != null) {
            for (FlowGraph.FunctionParameter input : functionGraph.getFunctionInputs()) {
                if (input == null || input.getName() == null || input.getName().isBlank()) {
                    continue;
                }
                callInputs.put(input.getName(), runtime.resolveInput(node, input.getName()));
            }
        }

        int depthBefore = runtime.getCallDepth();
        runtime.callFunction(functionGraph, startNodeId, callInputs);
        String functionStartNodeId = runtime.findFunctionStartNodeId();
        CompletableFuture<Void> functionExecution;
        if (functionStartNodeId == null) {
            functionExecution = CompletableFuture.completedFuture(null);
        } else {
            functionExecution = execute(runtime, functionStartNodeId, player, event, steps + 1);
        }

        return functionExecution.thenCompose(v -> {
            while (runtime.getCallDepth() > depthBefore) {
                runtime.returnFromFunction(Collections.emptyMap());
            }

            runtime.consumeFunctionReturnRequested();
            String callerNodeId = runtime.consumeReturnedCallerNodeId();
            if (callerNodeId == null) {
                callerNodeId = startNodeId;
            }

            List<String> nextNodeIds = findTargetNodes(runtime.getGraph(), callerNodeId, "flow");
            return executeTargets(runtime, nextNodeIds, player, event, steps + 1);
        });
    }

    private CompletableFuture<Void> executeLoopNode(FlowRuntime runtime, FlowNode loopNode, Player player, Event event, int steps) {
        String type = loopNode.getType();
        if ("loop".equals(type) || "loop_count".equals(type)) {
            return executeLoopCount(runtime, loopNode, player, event, steps);
        }
        if ("loop_for_each".equals(type)) {
            return executeLoopForEach(runtime, loopNode, player, event, steps);
        }
        if ("loop_for_each_player".equals(type)) {
            return executeLoopForEachPlayer(runtime, loopNode, player, event, steps);
        }
        if ("loop_for_each_entity".equals(type)) {
            return executeLoopForEachEntity(runtime, loopNode, player, event, steps);
        }
        if ("loop_interval".equals(type)) {
            return executeLoopInterval(runtime, loopNode, player, event, steps);
        }
        if ("loop_while".equals(type)) {
            return executeLoopWhile(runtime, loopNode, player, event, steps);
        }
        return CompletableFuture.completedFuture(null);
    }

    private CompletableFuture<Void> executeLoopCount(FlowRuntime runtime, FlowNode loopNode, Player player, Event event, int steps) {
        FlowGraph graph = runtime.getGraph();
        String nodeId = findNodeId(graph, loopNode);
        if (nodeId == null) {
            return CompletableFuture.completedFuture(null);
        }
        runtime.resetLoopControl();
        Integer count = (Integer) runtime.resolveInput(loopNode, "count", Integer.class);
        int iterations = count != null ? Math.max(0, count) : 0;

        List<String> loopTargets = findTargetNodes(graph, nodeId, "loop");
        List<String> completedTargets = findTargetNodes(graph, nodeId, "completed");

        CompletableFuture<Void> future = CompletableFuture.completedFuture(null);
        for (int i = 0; i < iterations; i++) {
            int index = i;
            future = future.thenCompose(v -> {
                if (runtime.isBreakLoopRequested()) {
                    return CompletableFuture.completedFuture(null);
                }
                runtime.setNodeOutput(nodeId, "index", index);
                clearFlowDataDependencies(runtime, graph, loopTargets);
                return executeTargets(runtime, loopTargets, player, event, steps + 1);
            });
        }

        return future.thenCompose(v -> executeTargets(runtime, completedTargets, player, event, steps + 1));
    }

    private CompletableFuture<Void> executeLoopForEach(FlowRuntime runtime, FlowNode loopNode, Player player, Event event, int steps) {
        FlowGraph graph = runtime.getGraph();
        String nodeId = findNodeId(graph, loopNode);
        if (nodeId == null) {
            return CompletableFuture.completedFuture(null);
        }
        runtime.resetLoopControl();
        List<?> list = (List<?>) runtime.resolveInput(loopNode, "list", List.class);
        if (list == null) {
            list = List.of();
        }

        List<String> loopTargets = findTargetNodes(graph, nodeId, "loop");
        List<String> completedTargets = findTargetNodes(graph, nodeId, "completed");

        CompletableFuture<Void> future = CompletableFuture.completedFuture(null);
        for (int i = 0; i < list.size(); i++) {
            int index = i;
            Object element = list.get(i);
            future = future.thenCompose(v -> {
                if (runtime.isBreakLoopRequested()) {
                    return CompletableFuture.completedFuture(null);
                }
                runtime.setNodeOutput(nodeId, "index", index);
                runtime.setNodeOutput(nodeId, "element", element);
                clearFlowDataDependencies(runtime, graph, loopTargets);
                return executeTargets(runtime, loopTargets, player, event, steps + 1);
            });
        }

        return future.thenCompose(v -> executeTargets(runtime, completedTargets, player, event, steps + 1));
    }

    private CompletableFuture<Void> executeLoopForEachPlayer(FlowRuntime runtime, FlowNode loopNode, Player player, Event event, int steps) {
        FlowGraph graph = runtime.getGraph();
        String nodeId = findNodeId(graph, loopNode);
        if (nodeId == null) {
            return CompletableFuture.completedFuture(null);
        }
        runtime.resetLoopControl();
        List<Player> players = new java.util.ArrayList<>(org.bukkit.Bukkit.getOnlinePlayers());

        List<String> loopTargets = findTargetNodes(graph, nodeId, "loop");
        List<String> completedTargets = findTargetNodes(graph, nodeId, "completed");

        CompletableFuture<Void> future = CompletableFuture.completedFuture(null);
        for (int i = 0; i < players.size(); i++) {
            int index = i;
            Player loopPlayer = players.get(i);
            future = future.thenCompose(v -> {
                if (runtime.isBreakLoopRequested()) {
                    return CompletableFuture.completedFuture(null);
                }
                runtime.setNodeOutput(nodeId, "index", index);
                runtime.setNodeOutput(nodeId, "player", loopPlayer);
                clearFlowDataDependencies(runtime, graph, loopTargets);
                return executeTargets(runtime, loopTargets, player, event, steps + 1);
            });
        }

        return future.thenCompose(v -> executeTargets(runtime, completedTargets, player, event, steps + 1));
    }

    private CompletableFuture<Void> executeLoopForEachEntity(FlowRuntime runtime, FlowNode loopNode, Player player, Event event, int steps) {
        FlowGraph graph = runtime.getGraph();
        String nodeId = findNodeId(graph, loopNode);
        if (nodeId == null) {
            return CompletableFuture.completedFuture(null);
        }
        runtime.resetLoopControl();
        Double radius = (Double) runtime.resolveInput(loopNode, "radius", Double.class);
        if (radius == null) {
            radius = 10.0;
        }
        org.bukkit.Location center = (org.bukkit.Location) runtime.resolveInput(loopNode, "center", org.bukkit.Location.class);
        if (center == null && player != null) {
            center = player.getLocation();
        }
        if (center == null || center.getWorld() == null) {
            return CompletableFuture.completedFuture(null);
        }

        List<org.bukkit.entity.Entity> entities = new java.util.ArrayList<>(
            center.getWorld().getNearbyEntities(center, radius, radius, radius));
        List<String> loopTargets = findTargetNodes(graph, nodeId, "loop");
        List<String> completedTargets = findTargetNodes(graph, nodeId, "completed");

        CompletableFuture<Void> future = CompletableFuture.completedFuture(null);
        for (int i = 0; i < entities.size(); i++) {
            int index = i;
            org.bukkit.entity.Entity entity = entities.get(i);
            future = future.thenCompose(v -> {
                if (runtime.isBreakLoopRequested()) {
                    return CompletableFuture.completedFuture(null);
                }
                runtime.setNodeOutput(nodeId, "index", index);
                runtime.setNodeOutput(nodeId, "entity", entity);
                clearFlowDataDependencies(runtime, graph, loopTargets);
                return executeTargets(runtime, loopTargets, player, event, steps + 1);
            });
        }

        return future.thenCompose(v -> executeTargets(runtime, completedTargets, player, event, steps + 1));
    }

    private CompletableFuture<Void> executeLoopInterval(FlowRuntime runtime, FlowNode loopNode, Player player, Event event, int steps) {
        FlowGraph graph = runtime.getGraph();
        String nodeId = findNodeId(graph, loopNode);
        if (nodeId == null) {
            return CompletableFuture.completedFuture(null);
        }
        runtime.resetLoopControl();
        Integer intervalValue = (Integer) runtime.resolveInput(loopNode, "interval_ticks", Integer.class);
        Integer maxValue = (Integer) runtime.resolveInput(loopNode, "max_iterations", Integer.class);
        int intervalTicks = intervalValue != null ? Math.max(0, intervalValue) : 20;
        int maxIterations = maxValue != null ? Math.max(0, maxValue) : 0;

        List<String> loopTargets = findTargetNodes(graph, nodeId, "loop");
        List<String> completedTargets = findTargetNodes(graph, nodeId, "completed");
        return executeLoopIntervalIteration(runtime, nodeId, loopTargets, completedTargets, player, event, steps, 0, intervalTicks, maxIterations);
    }

    private CompletableFuture<Void> executeLoopIntervalIteration(FlowRuntime runtime, String nodeId, List<String> loopTargets,
                                                                 List<String> completedTargets, Player player, Event event, int steps, int index,
                                                                 int intervalTicks, int maxIterations) {
        if (runtime.isBreakLoopRequested() || (maxIterations > 0 && index >= maxIterations)) {
            return executeTargets(runtime, completedTargets, player, event, steps);
        }

        runtime.setNodeOutput(nodeId, "index", index);
        return scheduleDelay(intervalTicks)
            .thenCompose(v -> {
                clearFlowDataDependencies(runtime, runtime.getGraph(), loopTargets);
                return executeTargets(runtime, loopTargets, player, event, steps + 1);
            })
            .thenCompose(v -> executeLoopIntervalIteration(runtime, nodeId, loopTargets, completedTargets, player, event, steps, index + 1, intervalTicks, maxIterations));
    }

    private CompletableFuture<Void> executeLoopWhile(FlowRuntime runtime, FlowNode loopNode, Player player, Event event, int steps) {
        FlowGraph graph = runtime.getGraph();
        String nodeId = findNodeId(graph, loopNode);
        if (nodeId == null) {
            return CompletableFuture.completedFuture(null);
        }
        runtime.resetLoopControl();
        Integer intervalValue = (Integer) runtime.resolveInput(loopNode, "interval_ticks", Integer.class);
        Integer maxValue = (Integer) runtime.resolveInput(loopNode, "max_iterations", Integer.class);
        int intervalTicks = intervalValue != null ? Math.max(0, intervalValue) : 1;
        int maxIterations = maxValue != null ? Math.max(0, maxValue) : 0;

        List<String> loopTargets = findTargetNodes(graph, nodeId, "loop");
        List<String> completedTargets = findTargetNodes(graph, nodeId, "completed");
        return executeLoopWhileIteration(runtime, loopNode, nodeId, loopTargets, completedTargets, player, event,
            steps, 0, intervalTicks, maxIterations);
    }

    private CompletableFuture<Void> executeLoopWhileIteration(FlowRuntime runtime, FlowNode loopNode, String nodeId, List<String> loopTargets,
                                                              List<String> completedTargets, Player player, Event event, int steps, int index,
                                                              int intervalTicks, int maxIterations) {
        if (runtime.isBreakLoopRequested() || (maxIterations > 0 && index >= maxIterations)) {
            return executeTargets(runtime, completedTargets, player, event, steps);
        }
        clearDependencyOutputs(runtime, runtime.getGraph(), nodeId, "condition");
        ensureInputNodesReady(runtime, loopNode, player, event);
        Boolean condition = (Boolean) runtime.resolveInput(loopNode, "condition", Boolean.class);
        if (!Boolean.TRUE.equals(condition)) {
            return executeTargets(runtime, completedTargets, player, event, steps);
        }

        runtime.setNodeOutput(nodeId, "index", index);
        return scheduleDelay(intervalTicks)
            .thenCompose(v -> {
                clearFlowDataDependencies(runtime, runtime.getGraph(), loopTargets);
                return executeTargets(runtime, loopTargets, player, event, steps + 1);
            })
            .thenCompose(v -> executeLoopWhileIteration(runtime, loopNode, nodeId, loopTargets, completedTargets, player, event, steps,
                index + 1, intervalTicks, maxIterations));
    }

    private CompletableFuture<Void> scheduleDelay(int ticks) {
        if (ticks <= 0) {
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<Void> future = new CompletableFuture<>();
        Bukkit.getScheduler().runTaskLater(ReSync.getInstance(), () -> future.complete(null), ticks);
        return future;
    }

    private CompletableFuture<Void> executeTargets(FlowRuntime runtime, List<String> targetNodeIds, Player player, Event event, int steps) {
        if (targetNodeIds == null || targetNodeIds.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        List<CompletableFuture<Void>> futures = new java.util.ArrayList<>();
        for (String nodeId : targetNodeIds) {
            futures.add(execute(runtime, nodeId, player, event, steps + 1));
        }
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
    }

    private void clearFlowDataDependencies(FlowRuntime runtime, FlowGraph graph, List<String> targetNodeIds) {
        if (targetNodeIds == null || targetNodeIds.isEmpty()) {
            return;
        }
        for (String targetNodeId : targetNodeIds) {
            for (FlowConnection conn : graph.getConnectionsToTarget(targetNodeId)) {
                if ("flow".equals(conn.getTargetPin())) {
                    continue;
                }
                clearDependencyOutputs(runtime, graph, targetNodeId, conn.getTargetPin());
            }
        }
    }

    private void clearDependencyOutputs(FlowRuntime runtime, FlowGraph graph, String targetNodeId, String pinName) {
        Set<String> visited = new HashSet<>();
        clearDependencyOutputs(runtime, graph, targetNodeId, pinName, visited);
    }

    private void clearDependencyOutputs(FlowRuntime runtime, FlowGraph graph, String targetNodeId, String pinName, Set<String> visited) {
        if (targetNodeId == null) {
            return;
        }
        for (FlowConnection conn : graph.getConnectionsToTarget(targetNodeId)) {
            if (!conn.getTargetPin().equals(pinName)) {
                continue;
            }
            String sourceId = conn.getSourceNodeId();
            if (!visited.add(sourceId)) {
                continue;
            }
            if (!shouldClearNodeOutputs(graph, sourceId)) {
                continue;
            }
            runtime.clearNodeOutputs(sourceId);
            for (FlowConnection sourceConn : graph.getConnectionsToTarget(sourceId)) {
                if (!"flow".equals(sourceConn.getTargetPin())) {
                    clearDependencyOutputs(runtime, graph, sourceId, sourceConn.getTargetPin(), visited);
                }
            }
        }
    }

    private boolean shouldClearNodeOutputs(FlowGraph graph, String nodeId) {
        FlowNode node = graph.getNodes().get(nodeId);
        if (node == null) {
            return false;
        }
        String type = node.getType();
        if (type != null && (type.startsWith("event:") || type.startsWith("event."))) {
            return false;
        }
        return !hasIncomingFlowConnection(graph, nodeId);
    }

    private NodeDefinition resolveTriggerDefinition(FlowNode node) {
        if (nodeDefinitionRegistry == null || node == null || node.getType() == null) {
            return null;
        }
        String mappedType = idCompatibility.mapToNew(node.getType());
        NodeDefinition definition = nodeDefinitionRegistry.get(mappedType);
        return definition != null && definition.isTrigger() ? definition : null;
    }

    private void publishTriggerOutputs(FlowRuntime runtime, String nodeId, NodeDefinition definition) {
        Map<String, Object> eventVars = runtime.getEventVariables();
        for (NodeDefinition.PinDefinition output : definition.getOutputs()) {
            if (output.getType() == NodeDefinition.PinType.FLOW) {
                continue;
            }
            String name = output.getName();
            if (name == null || name.isBlank()) {
                continue;
            }
            if (eventVars.containsKey(name)) {
                runtime.setNodeOutput(nodeId, name, eventVars.get(name));
            }
        }
    }

    private boolean hasIncomingFlowConnection(FlowGraph graph, String nodeId) {
        for (FlowConnection conn : graph.getConnectionsToTarget(nodeId)) {
            if ("flow".equals(conn.getTargetPin())) {
                return true;
            }
        }
        return false;
    }

    private boolean isLoopNode(String type) {
        if (type == null) {
            return false;
        }
        return "loop".equals(type)
            || "loop_count".equals(type)
            || "loop_for_each".equals(type)
            || "loop_for_each_player".equals(type)
            || "loop_for_each_entity".equals(type)
            || "loop_interval".equals(type)
            || "loop_while".equals(type);
    }

    private boolean isCustomFunctionNode(String type) {
        return type != null && type.startsWith("custom_function:");
    }

    private String extractCustomFunctionId(String type) {
        if (!isCustomFunctionNode(type)) {
            return null;
        }
        return type.substring("custom_function:".length());
    }

    private void ensureInputNodesReady(FlowRuntime runtime, FlowNode node, Player player, Event event) {
        FlowGraph graph = runtime.getGraph();
        String nodeId = graph.findNodeId(node);
        if (nodeId == null) {
            return;
        }

        for (FlowConnection conn : graph.getConnectionsToTarget(nodeId)) {
            if ("flow".equals(conn.getTargetPin())) {
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
        NodeHandler handler = resolveHandler(sourceNode);
        if (handler == null) {
            return;
        }

        runtime.beginEvaluating(nodeId);
        String previousPin = runtime.getTriggeredOutputPin();
        runtime.setTriggeredOutputPin(null);

        ensureInputNodesReady(runtime, sourceNode, player, event);
        try {
            handler.execute(new FlowContext(runtime, player, event, null, this), sourceNode);
        } catch (Exception e) {
            if (enableDebug) {
                Log.warn("[Flow] Error evaluating node '" + sourceNode.getType() + "' (ID: " + nodeId + "): " + e.getMessage(), e);
            }
        }

        runtime.setTriggeredOutputPin(previousPin);
        runtime.endEvaluating(nodeId);
    }

    private String findNodeId(FlowGraph graph, FlowNode node) {
        return graph.findNodeId(node);
    }

    private String findTargetNode(FlowGraph graph, String nodeId, String pinName) {
        for (FlowConnection conn : graph.getConnectionsFromSource(nodeId)) {
            if (conn.getSourcePin().equals(pinName)) {
                return conn.getTargetNodeId();
            }
        }
        return null;
    }

    private List<String> findTargetNodes(FlowGraph graph, String nodeId, String pinName) {
        List<String> targets = new java.util.ArrayList<>();
        for (FlowConnection conn : graph.getConnectionsFromSource(nodeId)) {
            if (conn.getSourcePin().equals(pinName)) {
                targets.add(conn.getTargetNodeId());
            }
        }
        return targets;
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

    private void notifyExecutionListeners(FlowGraph graph, String startNodeId, Player player, Event event) {
        for (FlowExecutionListener listener : executionListeners) {
            try {
                listener.onFlowExecution(graph, startNodeId, player, event);
            } catch (Exception e) {
                if (enableDebug) {
                    Log.warn("[Flow] Execution listener failed: " + e.getMessage(), e);
                }
            }
        }
    }

    private NodeHandler resolveHandler(FlowNode node) {
        String nodeType = node.getType();
        if (nodeType == null) {
            return null;
        }

        String mappedType = idCompatibility.mapToNew(nodeType);
        nodeType = mappedType;

        if (nodeDefinitionRegistry != null) {
            NodeDefinition definition = nodeDefinitionRegistry.get(nodeType);
            if (definition != null) {
                node.setType(nodeType);
                node.setHandlerConfig(definition.getHandlerConfig());
                String handlerName = definition.getHandler();
                if (handlerName != null && !handlerName.isBlank()) {
                    NodeHandler handler = handlerRegistry.getHandler(handlerName);
                    if (handler != null) {
                        return handler;
                    }
                }
            }
        }

        return handlerRegistry.getHandler(nodeType);
    }

    private FlowTraceRecord startTraceRecord(FlowGraph graph, FlowNode node, String nodeId, int steps, String status, long durationNanos, Throwable error) {
        FlowTraceRecord record = new FlowTraceRecord();
        record.setGraphId(graph != null ? graph.getId() : "");
        record.setNodeId(nodeId);
        record.setNodeType(node != null ? node.getType() : "");
        record.setStatus(status);
        record.setExecutionDepth(steps);
        record.setDurationNanos(durationNanos);
        record.setErrorText(error != null ? error.getMessage() : "");
        record.setInputSummary(summarizeInputs(node));
        record.setOutputSummary("");
        return record;
    }

    private String summarizeInputs(FlowNode node) {
        if (node == null || node.getInputValues() == null || node.getInputValues().isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, Object> entry : node.getInputValues().entrySet()) {
            if (!builder.isEmpty()) {
                builder.append(", ");
            }
            builder.append(entry.getKey()).append('=').append(summarizeValue(entry.getValue()));
            if (builder.length() > 240) {
                return builder.substring(0, 240);
            }
        }
        return builder.toString();
    }

    private String summarizeValue(Object value) {
        if (value == null) {
            return "null";
        }
        String text = String.valueOf(value).replace('\n', ' ').replace('\r', ' ');
        return text.length() > 48 ? text.substring(0, 48) + "..." : text;
    }

    private void trace(FlowTraceRecord record) {
        FlowTraceService service = traceService;
        if (service != null) {
            service.record(record);
        }
    }

    private void traceSuccess(FlowRuntime runtime, FlowGraph graph, FlowNode node, String nodeId, int steps, long traceStarted) {
        trace(startTraceRecord(graph, node, nodeId, steps, "success", System.nanoTime() - traceStarted, null));
        FlowDebugService debugger = debugService;
        if (debugger != null && debugger.isEnabled()) {
            debugger.afterNode(runtime, graph, node, nodeId, steps, "success", "", summarizeInputs(node), "");
        }
    }

    private void traceFailure(FlowRuntime runtime, FlowGraph graph, FlowNode node, String nodeId, int steps, long traceStarted, Throwable error) {
        trace(startTraceRecord(graph, node, nodeId, steps, "failure", System.nanoTime() - traceStarted, error));
        FlowDebugService debugger = debugService;
        if (debugger != null && debugger.isEnabled()) {
            debugger.afterNode(runtime, graph, node, nodeId, steps, "failure", error != null ? error.getMessage() : "", summarizeInputs(node), "");
        }
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
