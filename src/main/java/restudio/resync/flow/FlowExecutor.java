package restudio.resync.flow;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.scheduler.BukkitTask;
import restudio.flow.data.FlowConnection;
import restudio.flow.data.FlowDataType;
import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowNode;
import restudio.flow.data.FlowOperationResult;
import restudio.flow.data.FlowSerializer;
import restudio.flow.data.FlowTypeRef;
import restudio.resync.Log;
import restudio.resync.ReSync;
import restudio.resync.flow.handler.FlowHandlerException;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.NodeHandler;
import restudio.resync.flow.diagnostics.FlowDebugService;
import restudio.resync.flow.diagnostics.FlowTraceRecord;
import restudio.resync.flow.diagnostics.FlowTraceService;
import restudio.resync.flow.migration.IdCompatibilityLayer;
import restudio.resync.flow.registry.NodeDefinition;
import restudio.resync.flow.registry.NodeDefinitionRegistry;
import restudio.resync.flow.validation.FlowGraphValidationException;
import restudio.resync.flow.validation.FlowGraphValidationResult;
import restudio.resync.flow.validation.FlowGraphValidator;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.HashSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public class FlowExecutor {
    private static final long DEFAULT_MAX_EXECUTION_DURATION_MILLIS = 300_000L;
    private static final int DEFAULT_MAX_FUNCTION_CALL_DEPTH = 64;
    private static final int MAX_AUDIT_RECORDS = 1_024;
    private static final Set<String> FUNCTION_CALL_RESERVED_INPUTS = Set.of("function", "arguments", "continue_on_failure");
    private static final String CALL_PARAMETERS_KEY = "__call_parameters";
    private final HandlerRegistry handlerRegistry;
    private final NodeDefinitionRegistry nodeDefinitionRegistry;
    private final TypeAdapterRegistry typeAdapter;
    private final Map<String, Object> globalVariables;
    private final int maxExecutionSteps;
    private final boolean enableDebug;
    private final long maxExecutionDurationMillis;
    private final int maxFunctionCallDepth;
    private final IdCompatibilityLayer idCompatibility;
    private final Map<String, Object> eventVariables = new ConcurrentHashMap<>();
    private final Map<String, PendingTask> pendingTasks = new ConcurrentHashMap<>();
    private final Map<String, WallClockTask> wallClockTasks = new ConcurrentHashMap<>();
    private final Map<String, TerminalTask> terminalTasks = new ConcurrentHashMap<>();
    private final ScheduledThreadPoolExecutor wallClockScheduler;
    private final List<FlowExecutionListener> executionListeners = new CopyOnWriteArrayList<>();
    private final Deque<FlowNodeAuditRecord> auditRecords = new ArrayDeque<>();
    private FlowTraceService traceService;
    private FlowDebugService debugService;
    private FlowGraphValidator graphValidator;
    private FlowNodeAuthorizationPolicy authorizationPolicy = (context, node, definition) -> {
        String policy = definition != null ? definition.getAuthorizationPolicy() : "trusted_server_flow";
        return "trusted_server_flow".equals(policy) || "public".equals(policy)
            ? FlowNodeAuthorizationPolicy.AuthorizationDecision.allow()
            : FlowNodeAuthorizationPolicy.AuthorizationDecision.deny(policy, node != null ? node.getType() : "");
    };

    private record PendingTask(String graphId, String runtimeOwner, BukkitTask task, CompletableFuture<Void> completion,
                               long createdAt, long nextFireAt, boolean recurring, String lastFailure) {
    }

    private record TerminalTask(ScheduledTaskSnapshot snapshot, long completedAt) {
    }

    private static final class WallClockTask {
        private final String graphId;
        private final String runtimeOwner;
        private final CompletableFuture<Void> completion;
        private final long createdAt;
        private final long nextFireAt;
        private final boolean recurring;
        private volatile ScheduledFuture<?> timer;
        private volatile boolean cancelled;
        private volatile String lastFailure = "";

        private WallClockTask(String graphId, String runtimeOwner, CompletableFuture<Void> completion, long createdAt, long nextFireAt, boolean recurring) {
            this.graphId = graphId != null ? graphId : "";
            this.runtimeOwner = runtimeOwner != null && !runtimeOwner.isBlank() ? runtimeOwner : "flow_wall_clock";
            this.completion = completion;
            this.createdAt = createdAt;
            this.nextFireAt = nextFireAt;
            this.recurring = recurring;
        }

        private void attach(ScheduledFuture<?> timer) {
            this.timer = timer;
            if (cancelled && timer != null) {
                timer.cancel(false);
            }
        }

        private void cancel() {
            cancelled = true;
            ScheduledFuture<?> activeTimer = timer;
            if (activeTimer != null) {
                activeTimer.cancel(false);
            }
            if (completion != null && !completion.isDone()) {
                completion.cancel(false);
            }
        }
    }

    public record ScheduledTaskSnapshot(String taskId, String runtimeOwner, String graphId, long createdAt, long nextFireAt,
                                        boolean recurring, ScheduledTaskState state, String lastFailure) {
    }

    public enum ScheduledTaskState {
        ACTIVE,
        CANCELLED,
        FINISHED,
        FAILED
    }

    public enum TaskCancellationStatus {
        CANCELLED,
        ALREADY_CANCELLED,
        FINISHED,
        UNKNOWN
    }

    public FlowExecutor(HandlerRegistry handlerRegistry, TypeAdapterRegistry typeAdapter, Map<String, Object> globalVariables) {
        this(handlerRegistry, null, typeAdapter, globalVariables, 10000, false, DEFAULT_MAX_EXECUTION_DURATION_MILLIS);
    }

    public FlowExecutor(HandlerRegistry handlerRegistry, NodeDefinitionRegistry nodeDefinitionRegistry, TypeAdapterRegistry typeAdapter, Map<String, Object> globalVariables) {
        this(handlerRegistry, nodeDefinitionRegistry, typeAdapter, globalVariables, 10000, false, DEFAULT_MAX_EXECUTION_DURATION_MILLIS);
    }

    public FlowExecutor(HandlerRegistry handlerRegistry, TypeAdapterRegistry typeAdapter, Map<String, Object> globalVariables,
                       int maxExecutionSteps, boolean enableDebug) {
        this(handlerRegistry, null, typeAdapter, globalVariables, maxExecutionSteps, enableDebug, DEFAULT_MAX_EXECUTION_DURATION_MILLIS);
    }

    public FlowExecutor(HandlerRegistry handlerRegistry, NodeDefinitionRegistry nodeDefinitionRegistry, TypeAdapterRegistry typeAdapter, Map<String, Object> globalVariables,
                         int maxExecutionSteps, boolean enableDebug) {
        this(handlerRegistry, nodeDefinitionRegistry, typeAdapter, globalVariables, maxExecutionSteps, enableDebug, DEFAULT_MAX_EXECUTION_DURATION_MILLIS);
    }

    public FlowExecutor(HandlerRegistry handlerRegistry, NodeDefinitionRegistry nodeDefinitionRegistry, TypeAdapterRegistry typeAdapter, Map<String, Object> globalVariables,
                        int maxExecutionSteps, boolean enableDebug, long maxExecutionDurationMillis) {
        this(handlerRegistry, nodeDefinitionRegistry, typeAdapter, globalVariables, maxExecutionSteps, enableDebug, maxExecutionDurationMillis,
            DEFAULT_MAX_FUNCTION_CALL_DEPTH);
    }

    public FlowExecutor(HandlerRegistry handlerRegistry, NodeDefinitionRegistry nodeDefinitionRegistry, TypeAdapterRegistry typeAdapter, Map<String, Object> globalVariables,
                        int maxExecutionSteps, boolean enableDebug, long maxExecutionDurationMillis, int maxFunctionCallDepth) {
        this.handlerRegistry = handlerRegistry;
        this.nodeDefinitionRegistry = nodeDefinitionRegistry;
        this.typeAdapter = typeAdapter;
        this.globalVariables = new ConcurrentHashMap<>();
        if (globalVariables != null) {
            globalVariables.forEach((key, value) -> {
                if (key != null && value != null) {
                    this.globalVariables.put(key, value);
                }
            });
        }
        this.maxExecutionSteps = maxExecutionSteps;
        this.enableDebug = enableDebug;
        this.maxExecutionDurationMillis = Math.max(0L, maxExecutionDurationMillis);
        this.maxFunctionCallDepth = Math.max(1, maxFunctionCallDepth);
        this.idCompatibility = new IdCompatibilityLayer();
        this.wallClockScheduler = new ScheduledThreadPoolExecutor(1, Thread.ofPlatform().daemon().name("ReSync-Flow-WallClock").factory());
        this.wallClockScheduler.setRemoveOnCancelPolicy(true);
    }

    public CompletableFuture<Void> execute(FlowGraph graph, String startNodeId, Player player, Event event) {
        return execute(graph, startNodeId, player, event, new HashMap<>());
    }

    public CompletableFuture<Void> execute(FlowGraph graph, Player player, Event event, Map<String, Object> eventVars) {
        FlowGraphValidationException validationFailure = validationFailure(graph);
        if (validationFailure != null) {
            return CompletableFuture.failedFuture(validationFailure);
        }
        String startNodeId = findStartNode(graph);
        if (startNodeId == null) {
            return CompletableFuture.failedFuture(new FlowExecutionException(
                "FLOW_START_MISSING",
                "Flow has no executable start node",
                null,
                null,
                "Add or connect an executable trigger or start node"
            ));
        }
        return executeValidated(graph, startNodeId, player, event, eventVars);
    }

    public CompletableFuture<Void> execute(FlowGraph graph, String startNodeId, Player player, Event event,
                                            Map<String, Object> eventVars) {
        FlowGraphValidationException validationFailure = validationFailure(graph);
        if (validationFailure != null) {
            return CompletableFuture.failedFuture(validationFailure);
        }
        return executeValidated(graph, startNodeId, player, event, eventVars);
    }

    private CompletableFuture<Void> executeValidated(FlowGraph graph, String startNodeId, Player player, Event event, Map<String, Object> eventVars) {
        notifyExecutionListeners(graph, startNodeId, player, event);
        FlowRuntime runtime = new FlowRuntime(graph, typeAdapter, globalVariables, eventVars, nodeDefinitionRegistry);
        runtime.openEventMutationWindow(event != null);
        CompletableFuture<Void> future;
        try {
            future = execute(runtime, startNodeId, player, event, 0);
        } finally {
            runtime.closeEventMutationWindow();
        }
        future.whenComplete((result, ex) -> runtime.cleanupThreadLocals());
        return future;
    }

    public CompletableFuture<Object> executeSubFlow(FlowGraph subGraph, String startNodeId, String outputNodeId, String outputPin,
                                                      Player player, Event event, Map<String, Object> localInputs) {
        FlowGraphValidationException validationFailure = validationFailure(subGraph);
        if (validationFailure != null) {
            return CompletableFuture.failedFuture(validationFailure);
        }
        FlowRuntime runtime = new FlowRuntime(subGraph, typeAdapter, globalVariables, new HashMap<>(), nodeDefinitionRegistry);
        if (localInputs != null) {
            runtime.getLocalVariables().putAll(localInputs);
        }
        runtime.openEventMutationWindow(event != null);
        CompletableFuture<Void> execution;
        try {
            execution = execute(runtime, startNodeId, player, event, 0);
        } finally {
            runtime.closeEventMutationWindow();
        }
        return execution
            .thenApply(v -> runtime.getNodeOutput(outputNodeId, outputPin))
            .whenComplete((result, failure) -> runtime.cleanupThreadLocals());
    }

    public CompletableFuture<Object> executeSubFlow(FlowGraph subGraph, String outputNodeId, String outputPin,
                                                     Player player, Event event, Map<String, Object> localInputs) {
        FlowGraphValidationException validationFailure = validationFailure(subGraph);
        if (validationFailure != null) {
            return CompletableFuture.failedFuture(validationFailure);
        }
        String startNodeId = findStartNode(subGraph);
        if (startNodeId == null) {
            return CompletableFuture.failedFuture(new FlowExecutionException(
                "SUBFLOW_START_MISSING", "Subflow has no executable start node", null, null,
                "Connect an executable subflow entry"
            ));
        }
        return executeSubFlow(subGraph, startNodeId, outputNodeId, outputPin, player, event, localInputs);
    }

    public CompletableFuture<Object> executeSubFlow(FlowRuntime parentRuntime, FlowGraph subGraph, String outputNodeId, String outputPin,
                                                     Player player, Event event, Map<String, Object> localInputs) {
        if (parentRuntime == null) {
            return CompletableFuture.failedFuture(new FlowExecutionException(
                "SUBFLOW_RUNTIME_UNAVAILABLE", "Parent Flow runtime is unavailable", null, null,
                "Execute the subflow from an active Flow context"
            ));
        }
        FlowGraphValidationException validationFailure = validationFailure(subGraph);
        if (validationFailure != null) {
            return CompletableFuture.failedFuture(validationFailure);
        }
        String startNodeId = findStartNode(subGraph);
        if (startNodeId == null) {
            return CompletableFuture.failedFuture(new FlowExecutionException(
                "SUBFLOW_START_MISSING", "Subflow has no executable start node", null, null,
                "Connect an executable subflow entry"
            ));
        }
        FlowRuntime runtime = parentRuntime.createSubRuntime(subGraph);
        if (localInputs != null) {
            runtime.getLocalVariables().putAll(localInputs);
        }
        CompletableFuture<Void> execution = execute(runtime, startNodeId, player, event, 0);
        return execution.thenApply(v -> runtime.getNodeOutput(outputNodeId, outputPin))
            .whenComplete((result, failure) -> runtime.cleanupThreadLocals());
    }

    public CompletableFuture<Map<String, Object>> executeFunction(FlowGraph functionGraph, Player player, Event event,
                                                                   Map<String, Object> inputs, Map<String, Object> eventVars) {
        if (functionGraph == null || !functionGraph.isFunction()) {
            return CompletableFuture.failedFuture(new FlowExecutionException(
                "FUNCTION_INVALID",
                "A callable function graph is required",
                null,
                null,
                "Select an existing Flow function"
            ));
        }
        FlowGraphValidationException validationFailure = validationFailure(functionGraph);
        if (validationFailure != null) {
            return CompletableFuture.failedFuture(validationFailure);
        }
        FlowGraph callable = FlowSerializer.deserialize(FlowSerializer.serialize(functionGraph));
        String startNodeId = FlowRuntime.findFunctionStartNodeId(callable);
        if (startNodeId == null) {
            return CompletableFuture.failedFuture(new FlowExecutionException(
                "FUNCTION_START_MISSING", "Function has no executable start node", null, null,
                "Add a Function Start node"
            ));
        }
        FlowRuntime runtime = new FlowRuntime(new FlowGraph(), typeAdapter, globalVariables, eventVars, nodeDefinitionRegistry);
        String callerNodeId = "__function_call";
        runtime.callFunction(callable, callerNodeId, inputs != null ? inputs : Map.of());
        runtime.openEventMutationWindow(event != null);
        CompletableFuture<Void> future;
        try {
            future = execute(runtime, startNodeId, player, event, 0);
        } finally {
            runtime.closeEventMutationWindow();
        }
        return future.thenApply(v -> {
            while (runtime.getCallDepth() > 0) {
                runtime.returnFromFunction(Collections.emptyMap());
            }
            Map<String, Object> outputs = new HashMap<>();
            if (callable.getFunctionOutputs() != null) {
                for (FlowGraph.FunctionParameter output : callable.getFunctionOutputs()) {
                    if (output != null && output.getName() != null && !output.getName().isBlank()) {
                        outputs.put(output.getName(), runtime.getNodeOutput(callerNodeId, output.getName()));
                    }
                }
            }
            return outputs;
        }).whenComplete((result, ex) -> runtime.cleanupThreadLocals());
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

    public void setGraphValidator(FlowGraphValidator graphValidator) {
        this.graphValidator = graphValidator;
    }

    public void setAuthorizationPolicy(FlowNodeAuthorizationPolicy authorizationPolicy) {
        if (authorizationPolicy != null) {
            this.authorizationPolicy = authorizationPolicy;
        }
    }

    public synchronized List<FlowNodeAuditRecord> auditSnapshot() {
        return List.copyOf(auditRecords);
    }

    private FlowGraphValidationException validationFailure(FlowGraph graph) {
        if (graphValidator == null) {
            return null;
        }
        FlowGraphValidationResult result = graphValidator.validate(graph);
        return result.valid() ? null : new FlowGraphValidationException(result);
    }

    private CompletableFuture<Void> execute(FlowRuntime runtime, String startNodeId, Player player, Event event, int steps) {
        if (!acquireExecutionBudget(runtime)) {
            return executionBudgetFailure(runtime, startNodeId);
        }

        FlowGraph graph = runtime.getGraph();
        if (startNodeId == null || startNodeId.isBlank()) {
            return CompletableFuture.failedFuture(new FlowExecutionException(
                "EXECUTION_NODE_MISSING", "Flow execution target is missing", null, null, "Reconnect the execution path to an existing node"));
        }
        if (!runtime.beginFlowExecution(graph, startNodeId)) {
            return CompletableFuture.failedFuture(new FlowExecutionException(
                "EXECUTION_REENTRANT_NODE", "Execution re-entered an active node: " + startNodeId, null, startNodeId,
                "Use a supported loop node instead of an execution cycle"));
        }

        FlowNode node = graph.getNodes().get(startNodeId);
        if (node == null) {
            runtime.endFlowExecution(graph, startNodeId);
            return CompletableFuture.failedFuture(new FlowExecutionException(
                "EXECUTION_NODE_NOT_FOUND", "Flow node not found: " + startNodeId, null, startNodeId,
                "Reconnect the execution path to an existing node"));
        }

        runtime.consumeTriggeredOutput();
        long traceStarted = System.nanoTime();
        trace(startTraceRecord(runtime, graph, node, startNodeId, steps, "started", 0L, null));
        FlowDebugService debugger = debugService;
        CompletableFuture<Void> execution;
        try {
            if (debugger != null && debugger.isEnabled()) {
                execution = debugger.beforeNode(runtime, graph, node, startNodeId, steps, summarizeInputs(node))
                    .thenCompose(ignored -> executePreparedNode(runtime, node, startNodeId, player, event, steps));
            } else {
                execution = executePreparedNode(runtime, node, startNodeId, player, event, steps);
            }
        } catch (Exception exception) {
            execution = CompletableFuture.failedFuture(exception);
        }
        return completeNodeExecution(runtime, graph, node, startNodeId, steps, traceStarted, execution);
    }

    private CompletableFuture<Void> executePreparedNode(FlowRuntime runtime, FlowNode node, String startNodeId,
                                                         Player player, Event event, int steps) {
        FlowContext context = new FlowContext(
                runtime,
                player,
                event,
                null,
                this
        );
        context.setDeferredOutputDispatcher(outputPin -> dispatchDeferredOutput(runtime, startNodeId, outputPin, player, event, steps));

        return ensureInputNodesReady(runtime, node, player, event)
            .thenCompose(ignored -> executeWithThreadPolicy(runtime, resolveThreadPolicy(node),
                () -> executePreparedNodeWithInputs(runtime, node, startNodeId, player, event, steps, context)));
    }

    private CompletableFuture<Void> executePreparedNodeWithInputs(FlowRuntime runtime, FlowNode node, String startNodeId,
                                                                   Player player, Event event, int steps, FlowContext context) {

        String type = node.getType();
        NodeDefinition definition = resolveDefinition(node);
        FlowNodeAuthorizationPolicy.AuthorizationDecision authorization = authorizationPolicy.authorize(context, node, definition);
        FlowNodeAuthorizationPolicy.AuthorizationDecision decision = authorization != null
            ? authorization
            : FlowNodeAuthorizationPolicy.AuthorizationDecision.deny(definition != null ? definition.getAuthorizationPolicy() : "", type);
        recordNodeAudit(runtime, context, node, startNodeId, definition, decision);
        if (!decision.allowed()) {
            return CompletableFuture.failedFuture(new FlowExecutionException(
                decision.code(),
                decision.message(),
                null,
                startNodeId,
                "Review the capability authorization policy",
                decision.details()
            ));
        }
        String loopOperation = resolveLoopOperation(node);
        if (loopOperation != null) {
            return executeLoopNode(runtime, node, loopOperation, player, event, steps);
        }

        String functionId = resolveFunctionCallId(runtime, node, definition);
        if (functionId != null) {
            int depthBefore = runtime.getCallDepth();
            CompletableFuture<Void> call = executeFunctionCallNode(runtime, node, startNodeId, functionId, player, event, steps);
            if (!isRecoverableFunctionCall(runtime, node)) {
                return call;
            }
            return call.handle((ignored, failure) -> {
                if (failure == null) {
                    return CompletableFuture.<Void>completedFuture(null);
                }
                return recoverFunctionCall(runtime, startNodeId, depthBefore, unwrapCompletionFailure(failure), player, event, steps);
            }).thenCompose(result -> result);
        }

        NodeHandler handler = resolveHandler(node);
        if (handler == null) {
            NodeDefinition triggerDefinition = resolveTriggerDefinition(node);
            if (triggerDefinition != null) {
                publishTriggerOutputs(runtime, startNodeId, triggerDefinition);
                return findNextAndExecute(runtime, startNodeId, "flow", player, event, steps);
            }
            return CompletableFuture.failedFuture(new FlowExecutionException(
                "HANDLER_UNAVAILABLE",
                "No handler registered for node type: " + node.getType(),
                null,
                startNodeId,
                "Install the required capability or replace the unavailable node"
            ));
        }
        FlowExecutionException operationFailure = validateHandlerOperation(node, startNodeId);
        if (operationFailure != null) {
            return CompletableFuture.failedFuture(operationFailure);
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
                CompletableFuture<Void> pending = pendingBeforeContinuationOperations(context);
                List<String> pins = outputPins;
                result = pending.thenCompose(ignored -> executeTriggeredOutputs(runtime, startNodeId, pins, player, event, steps));
            } else if (context.isContinuationHalted()) {
                result = CompletableFuture.completedFuture(null);
            } else if (context.hasPendingAsyncOperations()) {
                result = pendingOperations(context).thenCompose(ignored -> context.hasDeferredOutputTriggered() || context.isContinuationHalted()
                    ? CompletableFuture.completedFuture(null)
                    : findNextAndExecute(runtime, startNodeId, "flow", player, event, steps));
            } else {
                result = findNextAndExecute(runtime, startNodeId, "flow", player, event, steps);
            }
            return result;
        } catch (Exception e) {
            return CompletableFuture.failedFuture(handlerFailure(e, startNodeId, node.getType(), "HANDLER_EXECUTION_FAILED", "executing"));
        }
    }

    private CompletableFuture<Void> completeNodeExecution(FlowRuntime runtime, FlowGraph graph, FlowNode node, String nodeId,
                                                           int steps, long traceStarted, CompletableFuture<Void> execution) {
        return execution.handle((ignored, failure) -> {
            runtime.endFlowExecution(graph, nodeId);
            Throwable effectiveFailure = failure;
            if (effectiveFailure == null && !runtime.isWithinElapsedBudget(maxExecutionDurationMillis)) {
                effectiveFailure = new FlowExecutionException(
                    "EXECUTION_DURATION_BUDGET",
                    "Flow execution exceeded maximum duration: " + maxExecutionDurationMillis + "ms",
                    null,
                    nodeId,
                    "Reduce long-running work, or raise the configured duration budget"
                );
            }
            if (effectiveFailure == null) {
                traceSuccess(runtime, graph, node, nodeId, steps, traceStarted);
                return null;
            }

            Throwable cause = unwrapCompletionFailure(effectiveFailure);
            FlowExecutionException exception;
            if (cause instanceof FlowExecutionException flowExecutionException) {
                exception = flowExecutionException;
            } else if (cause instanceof Exception handlerException) {
                exception = handlerFailure(handlerException, nodeId, node.getType(), "HANDLER_EXECUTION_FAILED", "executing");
            } else {
                exception = new FlowExecutionException(
                    "HANDLER_EXECUTION_FAILED",
                    "Error executing node '" + node.getType() + "' (ID: " + nodeId + ")",
                    cause,
                    nodeId,
                    "Inspect the node inputs and the underlying handler failure"
                );
            }
            traceFailure(runtime, graph, node, nodeId, steps, traceStarted, exception);
            throw new CompletionException(exception);
        });
    }

    private Throwable unwrapCompletionFailure(Throwable failure) {
        Throwable cause = failure;
        while ((cause instanceof CompletionException || cause instanceof ExecutionException) && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    private synchronized void recordNodeAudit(FlowRuntime runtime, FlowContext context, FlowNode node, String nodeId,
                                              NodeDefinition definition, FlowNodeAuthorizationPolicy.AuthorizationDecision decision) {
        String auditPolicy = definition != null ? definition.getAuditPolicy() : "none";
        boolean sensitive = definition != null && definition.isSensitive();
        boolean destructive = definition != null && definition.isDestructive();
        if (!sensitive && !destructive && "none".equals(auditPolicy) && decision.allowed()) return;
        while (auditRecords.size() >= MAX_AUDIT_RECORDS) {
            auditRecords.removeFirst();
        }
        String graphId = runtime != null && runtime.getGraph() != null && runtime.getGraph().getId() != null ? runtime.getGraph().getId() : "";
        String playerId = context != null && context.getPlayer() != null ? context.getPlayer().getUniqueId().toString() : "";
        auditRecords.addLast(new FlowNodeAuditRecord(
            System.currentTimeMillis(),
            runtime != null ? runtime.getExecutionId() : "",
            graphId,
            nodeId != null ? nodeId : "",
            node != null && node.getType() != null ? node.getType() : "",
            playerId,
            definition != null ? definition.getAuthorizationPolicy() : "trusted_server_flow",
            auditPolicy,
            definition != null ? definition.getConfirmationPolicy() : "none",
            sensitive,
            destructive,
            decision.allowed(),
            decision.code()
        ));
    }

    private CompletableFuture<Void> pendingOperations(FlowContext context) {
        return pendingOperations(context, new HashSet<>());
    }

    private CompletableFuture<Void> pendingOperations(FlowContext context, Set<CompletableFuture<Void>> observed) {
        if (context == null) {
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<?>[] pending = context.getAsyncOperations().values().stream()
            .filter(observed::add)
            .toArray(CompletableFuture[]::new);
        if (pending.length == 0) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.allOf(pending).thenCompose(ignored -> pendingOperations(context, observed));
    }

    private CompletableFuture<Void> pendingBeforeContinuationOperations(FlowContext context) {
        return pendingBeforeContinuationOperations(context, new HashSet<>());
    }

    private CompletableFuture<Void> pendingBeforeContinuationOperations(FlowContext context, Set<CompletableFuture<Void>> observed) {
        if (context == null) {
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<?>[] pending = context.getBeforeContinuationOperations().values().stream()
            .filter(observed::add)
            .toArray(CompletableFuture[]::new);
        if (pending.length == 0) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.allOf(pending).thenCompose(ignored -> pendingBeforeContinuationOperations(context, observed));
    }

    private CompletableFuture<Void> dispatchDeferredOutput(FlowRuntime runtime, String currentNodeId, String outputPin,
                                                           Player player, Event event, int steps) {
        if (outputPin == null || outputPin.isBlank()) {
            return CompletableFuture.completedFuture(null);
        }

        CompletableFuture<Void> completion = new CompletableFuture<>();
        Runnable continuation = () -> {
            try {
                executeTriggeredOutputs(runtime, currentNodeId, List.of(outputPin), player, event, steps)
                    .whenComplete((ignored, failure) -> {
                        if (failure == null) {
                            completion.complete(null);
                        } else {
                            completion.completeExceptionally(unwrapCompletionFailure(failure));
                        }
                    });
            } catch (Exception ex) {
                completion.completeExceptionally(ex);
            }
        };

        if (Bukkit.isPrimaryThread()) {
            continuation.run();
        } else {
            String taskId = "deferred_" + UUID.randomUUID();
            BukkitTask task;
            try {
                task = Bukkit.getScheduler().runTask(ReSync.getInstance(), continuation);
            } catch (RuntimeException exception) {
                completion.completeExceptionally(exception);
                return completion;
            }
            registerPendingTask(taskId, graphId(runtime), task, completion);
            completion.whenComplete((ignored, failure) -> finishPendingTask(taskId, failure));
        }
        return completion;
    }

    private NodeHandler.ThreadPolicy resolveThreadPolicy(FlowNode node) {
        NodeHandler handler = resolveHandler(node);
        return handler != null && handler.getThreadPolicy() != null ? handler.getThreadPolicy() : NodeHandler.ThreadPolicy.MAIN;
    }

    private CompletableFuture<Void> executeWithThreadPolicy(FlowRuntime runtime, NodeHandler.ThreadPolicy policy,
                                                            Supplier<CompletableFuture<Void>> action) {
        NodeHandler.ThreadPolicy resolved = policy != null ? policy : NodeHandler.ThreadPolicy.MAIN;
        if (resolved == NodeHandler.ThreadPolicy.CURRENT
            || resolved == NodeHandler.ThreadPolicy.MAIN && Bukkit.isPrimaryThread()
            || resolved == NodeHandler.ThreadPolicy.ASYNC && !Bukkit.isPrimaryThread()) {
            return invokeExecutionAction(action);
        }

        CompletableFuture<Void> completion = new CompletableFuture<>();
        String taskId = "thread_policy_" + UUID.randomUUID();
        Runnable scheduledAction = () -> invokeExecutionAction(action).whenComplete((ignored, failure) -> {
            if (failure == null) {
                completion.complete(null);
            } else {
                completion.completeExceptionally(unwrapCompletionFailure(failure));
            }
        });
        BukkitTask task;
        try {
            task = resolved == NodeHandler.ThreadPolicy.MAIN
                ? Bukkit.getScheduler().runTask(ReSync.getInstance(), scheduledAction)
                : Bukkit.getScheduler().runTaskAsynchronously(ReSync.getInstance(), scheduledAction);
        } catch (RuntimeException exception) {
            completion.completeExceptionally(exception);
            return completion;
        }
        registerPendingTask(taskId, graphId(runtime), task, completion);
        completion.whenComplete((ignored, failure) -> finishPendingTask(taskId, failure));
        return completion;
    }

    private CompletableFuture<Void> invokeExecutionAction(Supplier<CompletableFuture<Void>> action) {
        try {
            CompletableFuture<Void> result = action.get();
            return result != null ? result : CompletableFuture.completedFuture(null);
        } catch (Exception exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    private CompletableFuture<Void> executeTriggeredOutputs(FlowRuntime runtime, String currentNodeId, List<String> outputPins,
                                                            Player player, Event event, int steps) {
        if (outputPins == null || outputPins.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        CompletableFuture<Void> future = CompletableFuture.completedFuture(null);
        for (String outputPin : outputPins) {
            if (outputPin == null || outputPin.isBlank()) {
                continue;
            }
            future = future.thenCompose(ignored -> loopControlRequested(runtime)
                ? CompletableFuture.completedFuture(null)
                : findNextAndExecute(runtime, currentNodeId, outputPin, player, event, steps));
        }
        return future;
    }

    private CompletableFuture<Void> findNextAndExecute(FlowRuntime runtime, FlowNode currentNode, String outputPin, 
                                                   Player player, Event event, int steps) {
        FlowGraph graph = runtime.getGraph();
        String currentNodeId = findNodeId(graph, currentNode);
        return findNextAndExecute(runtime, currentNodeId, outputPin, player, event, steps);
    }

    private CompletableFuture<Void> findNextAndExecute(FlowRuntime runtime, String currentNodeId, String outputPin,
                                                       Player player, Event event, int steps) {
        if (outputPin == null || outputPin.isBlank()) {
            return CompletableFuture.completedFuture(null);
        }
        if (currentNodeId == null || currentNodeId.isBlank()) {
            return CompletableFuture.failedFuture(new FlowExecutionException(
                "EXECUTION_SOURCE_MISSING", "Execution source node is missing", null, null,
                "Reconnect the execution path to an existing node"));
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

    private CompletableFuture<Void> executeFunctionCallNode(FlowRuntime runtime, FlowNode node, String startNodeId, String functionId,
                                                            Player player, Event event, int steps) {
        if (functionId.isBlank()) {
            return CompletableFuture.failedFuture(new FlowExecutionException(
                "FUNCTION_ID_MISSING", "Function call has no function identity", null, startNodeId,
                "Replace the function call with an existing function"));
        }
        FlowExecutionException depthFailure = functionDepthFailure(runtime, startNodeId);
        if (depthFailure != null) {
            return CompletableFuture.failedFuture(depthFailure);
        }

        FlowStorage storage = FlowRuntimeAccess.getStorage();
        if (storage == null) {
            return CompletableFuture.failedFuture(new FlowExecutionException(
                "FUNCTION_STORAGE_UNAVAILABLE", "Function storage is unavailable", null, startNodeId,
                "Restore the Flow storage service before executing this graph"));
        }

        FlowGraph functionGraph = storage.getGraph(functionId);
        if (functionGraph == null || !functionGraph.isFunction()) {
            return CompletableFuture.failedFuture(new FlowExecutionException(
                "FUNCTION_NOT_FOUND", "Function not found: " + functionId, null, startNodeId,
                "Select an existing function or restore the missing function"));
        }
        FlowGraphValidationException functionValidationFailure = validationFailure(functionGraph);
        if (functionValidationFailure != null) {
            return CompletableFuture.failedFuture(functionValidationFailure);
        }
        functionGraph = FlowSerializer.deserialize(FlowSerializer.serialize(functionGraph));
        String functionStartNodeId = FlowRuntime.findFunctionStartNodeId(functionGraph);
        if (functionStartNodeId == null) {
            return CompletableFuture.failedFuture(new FlowExecutionException(
                "FUNCTION_START_MISSING", "Function has no executable start node", null, startNodeId,
                "Add a Function Start node to " + functionId));
        }

        FlowExecutionException contractFailure = validateFunctionCallContract(node, functionGraph, startNodeId);
        if (contractFailure != null) {
            return CompletableFuture.failedFuture(contractFailure);
        }
        Map<String, Object> callInputs;
        try {
            callInputs = resolveFunctionInputs(runtime, node, startNodeId, functionGraph);
        } catch (FlowExecutionException exception) {
            return CompletableFuture.failedFuture(exception);
        }
        FlowExecutionException inputFailure = validateFunctionInputs(functionGraph, callInputs, startNodeId);
        if (inputFailure != null) {
            return CompletableFuture.failedFuture(inputFailure);
        }
        int depthBefore = runtime.getCallDepth();
        List<FlowGraph.FunctionParameter> functionOutputs = functionGraph.getFunctionOutputs() != null ? List.copyOf(functionGraph.getFunctionOutputs()) : List.of();
        runtime.callFunction(functionGraph, startNodeId, callInputs);
        CompletableFuture<Void> functionExecution = execute(runtime, functionStartNodeId, player, event, steps + 1);

        return functionExecution.thenCompose(v -> {
            while (runtime.getCallDepth() > depthBefore) {
                runtime.returnFromFunction(Collections.emptyMap());
            }

            runtime.consumeFunctionReturnRequested();
            String callerNodeId = runtime.consumeReturnedCallerNodeId();
            if (callerNodeId == null) {
                callerNodeId = startNodeId;
            }

            Map<String, Object> results = new HashMap<>();
            for (FlowGraph.FunctionParameter output : functionOutputs) {
                if (output != null && output.getName() != null && !output.getName().isBlank()) {
                    results.put(output.getName(), runtime.getNodeOutput(callerNodeId, output.getName()));
                }
            }
            runtime.setNodeOutput(callerNodeId, "results", results);
            runtime.setNodeOutput(callerNodeId, "result", FlowOperationResult.success(results));

            List<String> nextNodeIds = findTargetNodes(runtime.getGraph(), callerNodeId, "flow");
            return executeTargets(runtime, nextNodeIds, player, event, steps + 1);
        });
    }

    private Map<String, Object> resolveFunctionInputs(FlowRuntime runtime, FlowNode node, String nodeId,
                                                      FlowGraph functionGraph) throws FlowExecutionException {
        Map<String, Object> callInputs = new HashMap<>();
        Object dynamicArguments = runtime.resolveInput(node, "arguments");
        if (dynamicArguments instanceof Map<?, ?> arguments) {
            for (Map.Entry<?, ?> argument : arguments.entrySet()) {
                if (argument.getKey() != null) {
                    callInputs.put(argument.getKey().toString(), argument.getValue());
                }
            }
        } else if (dynamicArguments != null) {
            List<FlowGraph.FunctionParameter> declared = functionGraph.getFunctionInputs() != null
                ? functionGraph.getFunctionInputs().stream()
                    .filter(input -> input != null && input.getName() != null && !input.getName().isBlank())
                    .toList()
                : List.of();
            if (declared.size() != 1) {
                throw new FlowExecutionException("FUNCTION_ARGUMENTS_NEED_NAMES",
                    "This function has multiple inputs, so each value needs an argument name", null, nodeId,
                    "Add named arguments to Call Function",
                    Map.of("function", functionGraph.getId(), "inputCount", declared.size()));
            }
            callInputs.put(declared.getFirst().getName(), dynamicArguments);
        }
        if (functionGraph.getFunctionInputs() != null) {
            for (FlowGraph.FunctionParameter input : functionGraph.getFunctionInputs()) {
                if (input == null || input.getName() == null || input.getName().isBlank()) {
                    continue;
                }
                if (FUNCTION_CALL_RESERVED_INPUTS.contains(input.getName())) {
                    callInputs.putIfAbsent(input.getName(), null);
                    continue;
                }
                boolean hasLiteral = node.getInputValues() != null && node.getInputValues().containsKey(input.getName());
                boolean hasConnection = runtime.getGraph().getConnectionsToTarget(nodeId).stream()
                    .anyMatch(connection -> input.getName().equals(connection.getTargetPin()));
                if (hasLiteral || hasConnection || !callInputs.containsKey(input.getName())) {
                    callInputs.put(input.getName(), runtime.resolveInput(node, input.getName()));
                }
            }
        }
        return callInputs;
    }

    private FlowExecutionException validateFunctionCallContract(FlowNode node, FlowGraph functionGraph, String nodeId) {
        if (node.getInputValues() == null || !(node.getInputValues().get(CALL_PARAMETERS_KEY) instanceof Iterable<?> values)) {
            return null;
        }
        Map<String, FlowTypeRef> declared = new LinkedHashMap<>();
        for (Object value : values) {
            if (!(value instanceof Map<?, ?> entry) || entry.get("name") == null || entry.get("type") == null) {
                return new FlowExecutionException("FUNCTION_CALL_ARGUMENT_INVALID", "Function argument needs a name and type", null, nodeId,
                    "Remove the invalid argument and add it again", Map.of("function", functionGraph.getId()));
            }
            String name = entry.get("name").toString().trim();
            FlowTypeRef type;
            try {
                type = FlowTypeRef.parse(entry.get("type").toString()).normalizedGenerics();
            } catch (IllegalArgumentException exception) {
                return new FlowExecutionException("FUNCTION_CALL_ARGUMENT_TYPE_INVALID", "Function argument type is invalid: " + name, exception, nodeId,
                    "Choose the type expected by the called function", Map.of("argument", name, "function", functionGraph.getId()));
            }
            if (declared.putIfAbsent(name, type) != null) {
                return new FlowExecutionException("FUNCTION_CALL_ARGUMENT_DUPLICATE", "Function argument is declared more than once: " + name, null, nodeId,
                    "Remove or rename the duplicate argument", Map.of("argument", name, "function", functionGraph.getId()));
            }
        }
        if (declared.isEmpty()) {
            return null;
        }
        Map<String, FlowTypeRef> expected = new LinkedHashMap<>();
        if (functionGraph.getFunctionInputs() != null) {
            for (FlowGraph.FunctionParameter parameter : functionGraph.getFunctionInputs()) {
                if (parameter != null && parameter.getName() != null && !parameter.getName().isBlank()) {
                    expected.put(parameter.getName(), parameter.getTypeRef().normalizedGenerics());
                }
            }
        }
        List<String> missing = expected.keySet().stream().filter(name -> !declared.containsKey(name)).sorted(String.CASE_INSENSITIVE_ORDER).toList();
        List<String> unknown = declared.keySet().stream().filter(name -> !expected.containsKey(name)).sorted(String.CASE_INSENSITIVE_ORDER).toList();
        if (!missing.isEmpty() || !unknown.isEmpty()) {
            return new FlowExecutionException("FUNCTION_CALL_ARGUMENTS_DO_NOT_MATCH", "Function arguments do not match the selected function", null, nodeId,
                "Match the argument names to the function inputs", Map.of(
                    "function", functionGraph.getId(),
                    "missing", missing,
                    "unknown", unknown
                ));
        }
        for (Map.Entry<String, FlowTypeRef> argument : declared.entrySet()) {
            FlowTypeRef expectedType = expected.get(argument.getKey());
            if (!expectedType.equals(argument.getValue())) {
                return new FlowExecutionException("FUNCTION_CALL_ARGUMENT_TYPE_MISMATCH",
                    "Argument " + argument.getKey() + " is " + argument.getValue() + " but the function expects " + expectedType, null, nodeId,
                    "Choose the same type as the function input", Map.of(
                        "argument", argument.getKey(),
                        "declaredType", argument.getValue().toString(),
                        "expectedType", expectedType.toString(),
                        "function", functionGraph.getId()
                    ));
            }
        }
        return null;
    }

    private FlowExecutionException validateFunctionInputs(FlowGraph functionGraph, Map<String, Object> inputs, String nodeId) {
        Set<String> declared = new HashSet<>();
        List<FlowGraph.FunctionParameter> parameters = functionGraph.getFunctionInputs() != null ? functionGraph.getFunctionInputs() : List.of();
        for (FlowGraph.FunctionParameter parameter : parameters) {
            if (parameter == null || parameter.getName() == null || parameter.getName().isBlank()) {
                continue;
            }
            String name = parameter.getName();
            declared.add(name);
            Object value = inputs.get(name);
            if (value == null && parameter.getDefaultValue() != null && !parameter.getDefaultValue().isBlank()) {
                value = parameter.getDefaultValue();
            }
            if (value == null) {
                return new FlowExecutionException("FUNCTION_ARGUMENT_REQUIRED", "Function argument is missing: " + name, null, nodeId,
                    "Connect " + name + " on Call Function", Map.of("argument", name, "function", functionGraph.getId()));
            }
            FlowDataType type = parameter.getType();
            Class<?> javaType = type != null ? type.getJavaType() : Object.class;
            Object adapted = javaType == null || Object.class.equals(javaType) ? value : typeAdapter.adapt(value, javaType);
            if (adapted == null) {
                return new FlowExecutionException("FUNCTION_ARGUMENT_TYPE_MISMATCH", "Function argument has the wrong type: " + name, null, nodeId,
                    "Provide a value compatible with " + parameter.getTypeRef(), Map.of(
                        "argument", name,
                        "expectedType", parameter.getTypeRef().toString(),
                        "actualType", value.getClass().getSimpleName(),
                        "function", functionGraph.getId()
                    ));
            }
            inputs.put(name, adapted);
        }
        List<String> unknown = inputs.keySet().stream().filter(name -> !declared.contains(name)).sorted(String.CASE_INSENSITIVE_ORDER).toList();
        if (!unknown.isEmpty()) {
            return new FlowExecutionException("FUNCTION_ARGUMENT_UNKNOWN", "Function arguments are not declared: " + String.join(", ", unknown), null, nodeId,
                "Remove the unknown arguments or update the function signature", Map.of("arguments", unknown, "function", functionGraph.getId()));
        }
        return null;
    }

    private boolean isRecoverableFunctionCall(FlowRuntime runtime, FlowNode node) {
        if (!"call.function".equals(node.getType()) && !"call_function".equals(node.getType())) {
            return false;
        }
        Object value = runtime.resolveInput(node, "continue_on_failure");
        return value instanceof Boolean flag ? flag : Boolean.parseBoolean(String.valueOf(value));
    }

    private CompletableFuture<Void> recoverFunctionCall(FlowRuntime runtime, String nodeId, int depthBefore, Throwable failure,
                                                         Player player, Event event, int steps) {
        while (runtime.getCallDepth() > depthBefore) {
            runtime.returnFromFunction(Collections.emptyMap());
        }
        runtime.consumeFunctionReturnRequested();
        runtime.consumeReturnedCallerNodeId();
        FlowExecutionException executionFailure = failure instanceof FlowExecutionException value
            ? value
            : new FlowExecutionException("FUNCTION_CALL_FAILED", failure != null && failure.getMessage() != null ? failure.getMessage() : "Function Call Failed",
                failure, nodeId, "Review the selected function and its arguments");
        runtime.setNodeOutput(nodeId, "results", Map.of());
        runtime.setNodeOutput(nodeId, "result", FlowOperationResult.failure(
            executionFailure.getCode(),
            executionFailure.getMessage(),
            executionFailure.getDetails()
        ));
        return executeTargets(runtime, findTargetNodes(runtime.getGraph(), nodeId, "flow"), player, event, steps + 1);
    }

    private CompletableFuture<Void> executeLoopNode(FlowRuntime runtime, FlowNode loopNode, String operation, Player player, Event event, int steps) {
        if ("loop".equals(operation) || "loop_count".equals(operation)) {
            return executeLoopCount(runtime, loopNode, player, event, steps);
        }
        if ("loop_for_each".equals(operation)) {
            return executeLoopForEach(runtime, loopNode, player, event, steps);
        }
        if ("loop_for_each_player".equals(operation)) {
            return executeLoopForEachPlayer(runtime, loopNode, player, event, steps);
        }
        if ("loop_for_each_entity".equals(operation)) {
            return executeLoopForEachEntity(runtime, loopNode, player, event, steps);
        }
        if ("loop_interval".equals(operation)) {
            return executeLoopInterval(runtime, loopNode, player, event, steps);
        }
        if ("loop_while".equals(operation)) {
            return executeLoopWhile(runtime, loopNode, player, event, steps);
        }
        return CompletableFuture.failedFuture(new FlowExecutionException(
            "LOOP_OPERATION_UNKNOWN", "Unknown loop operation: " + operation, null, findNodeId(runtime.getGraph(), loopNode),
            "Replace the node with a supported loop operation"));
    }

    private CompletableFuture<Void> executeLoopCount(FlowRuntime runtime, FlowNode loopNode, Player player, Event event, int steps) {
        FlowGraph graph = runtime.getGraph();
        String nodeId = findNodeId(graph, loopNode);
        if (nodeId == null) {
            return missingLoopNode();
        }
        Integer count = (Integer) runtime.resolveInput(loopNode, "count", Integer.class);
        int iterations = count != null ? count : 0;
        if (iterations < 0) return invalidLoopConfiguration(nodeId, "Loop count cannot be negative");
        runtime.beginLoopControl();
        runtime.setNodeOutput(nodeId, "completed", false);

        List<String> loopTargets = findLoopTargets(graph, nodeId);
        List<String> completedTargets = findTargetNodes(graph, nodeId, "completed");

        CompletableFuture<Void> future = CompletableFuture.completedFuture(null);
        for (int i = 0; i < iterations; i++) {
            int index = i;
            future = future.thenCompose(v -> {
                if (runtime.isBreakLoopRequested()) {
                    return CompletableFuture.completedFuture(null);
                }
                if (!acquireExecutionBudget(runtime)) {
                    return executionBudgetFailure(runtime, nodeId);
                }
                runtime.setNodeOutput(nodeId, "index", index);
                clearFlowDataDependencies(runtime, graph, loopTargets);
                return executeTargets(runtime, loopTargets, player, event, steps + 1)
                    .thenRun(runtime::consumeContinueLoopRequested);
            });
        }

        return future.whenComplete((result, failure) -> runtime.endLoopControl())
            .thenCompose(v -> executeLoopCompletion(runtime, nodeId, completedTargets, player, event, steps + 1));
    }

    private CompletableFuture<Void> executeLoopForEach(FlowRuntime runtime, FlowNode loopNode, Player player, Event event, int steps) {
        FlowGraph graph = runtime.getGraph();
        String nodeId = findNodeId(graph, loopNode);
        if (nodeId == null) {
            return missingLoopNode();
        }
        List<?> list = (List<?>) runtime.resolveInput(loopNode, "list", List.class);
        if (list == null) {
            list = List.of();
        } else {
            list = new ArrayList<>(list);
        }
        runtime.beginLoopControl();
        runtime.setNodeOutput(nodeId, "completed", false);

        List<String> loopTargets = findLoopTargets(graph, nodeId);
        List<String> completedTargets = findTargetNodes(graph, nodeId, "completed");

        CompletableFuture<Void> future = CompletableFuture.completedFuture(null);
        for (int i = 0; i < list.size(); i++) {
            int index = i;
            Object element = list.get(i);
            future = future.thenCompose(v -> {
                if (runtime.isBreakLoopRequested()) {
                    return CompletableFuture.completedFuture(null);
                }
                if (!acquireExecutionBudget(runtime)) {
                    return executionBudgetFailure(runtime, nodeId);
                }
                runtime.setNodeOutput(nodeId, "index", index);
                runtime.setNodeOutput(nodeId, "element", element);
                clearFlowDataDependencies(runtime, graph, loopTargets);
                return executeTargets(runtime, loopTargets, player, event, steps + 1)
                    .thenRun(runtime::consumeContinueLoopRequested);
            });
        }

        return future.whenComplete((result, failure) -> runtime.endLoopControl())
            .thenCompose(v -> executeLoopCompletion(runtime, nodeId, completedTargets, player, event, steps + 1));
    }

    private CompletableFuture<Void> executeLoopForEachPlayer(FlowRuntime runtime, FlowNode loopNode, Player player, Event event, int steps) {
        FlowGraph graph = runtime.getGraph();
        String nodeId = findNodeId(graph, loopNode);
        if (nodeId == null) {
            return missingLoopNode();
        }
        List<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());
        runtime.beginLoopControl();
        runtime.setNodeOutput(nodeId, "completed", false);

        List<String> loopTargets = findLoopTargets(graph, nodeId);
        List<String> completedTargets = findTargetNodes(graph, nodeId, "completed");

        CompletableFuture<Void> future = CompletableFuture.completedFuture(null);
        for (int i = 0; i < players.size(); i++) {
            int index = i;
            Player loopPlayer = players.get(i);
            future = future.thenCompose(v -> {
                if (runtime.isBreakLoopRequested()) {
                    return CompletableFuture.completedFuture(null);
                }
                if (!acquireExecutionBudget(runtime)) {
                    return executionBudgetFailure(runtime, nodeId);
                }
                runtime.setNodeOutput(nodeId, "index", index);
                runtime.setNodeOutput(nodeId, "player", loopPlayer);
                clearFlowDataDependencies(runtime, graph, loopTargets);
                return executeTargets(runtime, loopTargets, player, event, steps + 1)
                    .thenRun(runtime::consumeContinueLoopRequested);
            });
        }

        return future.whenComplete((result, failure) -> runtime.endLoopControl())
            .thenCompose(v -> executeLoopCompletion(runtime, nodeId, completedTargets, player, event, steps + 1));
    }

    private CompletableFuture<Void> executeLoopForEachEntity(FlowRuntime runtime, FlowNode loopNode, Player player, Event event, int steps) {
        FlowGraph graph = runtime.getGraph();
        String nodeId = findNodeId(graph, loopNode);
        if (nodeId == null) {
            return missingLoopNode();
        }
        Double radius = (Double) runtime.resolveInput(loopNode, "radius", Double.class);
        if (radius == null) {
            radius = 10.0;
        }
        Location center = (Location) runtime.resolveInput(loopNode, "center", Location.class);
        if (center == null && player != null) {
            center = player.getLocation();
        }
        if (center == null || center.getWorld() == null) {
            return invalidLoopConfiguration(nodeId, "Entity loop center must have a world");
        }
        if (!Double.isFinite(radius) || radius < 0 || radius > 128) return invalidLoopConfiguration(nodeId, "Entity loop radius must be between 0 and 128");
        runtime.beginLoopControl();
        runtime.setNodeOutput(nodeId, "completed", false);

        List<Entity> entities = new ArrayList<>(
            center.getWorld().getNearbyEntities(center, radius, radius, radius));
        List<String> loopTargets = findLoopTargets(graph, nodeId);
        List<String> completedTargets = findTargetNodes(graph, nodeId, "completed");

        CompletableFuture<Void> future = CompletableFuture.completedFuture(null);
        for (int i = 0; i < entities.size(); i++) {
            int index = i;
            Entity entity = entities.get(i);
            future = future.thenCompose(v -> {
                if (runtime.isBreakLoopRequested()) {
                    return CompletableFuture.completedFuture(null);
                }
                if (!acquireExecutionBudget(runtime)) {
                    return executionBudgetFailure(runtime, nodeId);
                }
                runtime.setNodeOutput(nodeId, "index", index);
                runtime.setNodeOutput(nodeId, "entity", entity);
                clearFlowDataDependencies(runtime, graph, loopTargets);
                return executeTargets(runtime, loopTargets, player, event, steps + 1)
                    .thenRun(runtime::consumeContinueLoopRequested);
            });
        }

        return future.whenComplete((result, failure) -> runtime.endLoopControl())
            .thenCompose(v -> executeLoopCompletion(runtime, nodeId, completedTargets, player, event, steps + 1));
    }

    private CompletableFuture<Void> executeLoopInterval(FlowRuntime runtime, FlowNode loopNode, Player player, Event event, int steps) {
        FlowGraph graph = runtime.getGraph();
        String nodeId = findNodeId(graph, loopNode);
        if (nodeId == null) {
            return missingLoopNode();
        }
        Integer intervalValue = (Integer) runtime.resolveInput(loopNode, "interval_ticks", Integer.class);
        Integer maxValue = (Integer) runtime.resolveInput(loopNode, "max_iterations", Integer.class);
        int intervalTicks = intervalValue != null ? intervalValue : 20;
        int maxIterations = maxValue != null ? maxValue : 0;
        if (intervalTicks <= 0) return invalidLoopConfiguration(nodeId, "Loop interval must be positive");
        if (maxIterations < 0) return invalidLoopConfiguration(nodeId, "Maximum loop iterations cannot be negative");
        runtime.beginLoopControl();
        runtime.setNodeOutput(nodeId, "completed", false);

        List<String> loopTargets = findLoopTargets(graph, nodeId);
        List<String> completedTargets = findTargetNodes(graph, nodeId, "completed");
        CompletableFuture<Void> iterations = executeLoopIntervalIteration(runtime, nodeId, loopTargets, player, event, steps, 0, intervalTicks, maxIterations);
        return iterations.whenComplete((result, failure) -> runtime.endLoopControl())
            .thenCompose(v -> executeLoopCompletion(runtime, nodeId, completedTargets, player, event, steps));
    }

    private CompletableFuture<Void> executeLoopIntervalIteration(FlowRuntime runtime, String nodeId, List<String> loopTargets,
                                                                 Player player, Event event, int steps, int index,
                                                                 int intervalTicks, int maxIterations) {
        if (runtime.isBreakLoopRequested() || (maxIterations > 0 && index >= maxIterations)) {
            return CompletableFuture.completedFuture(null);
        }
        if (!acquireExecutionBudget(runtime)) {
            return executionBudgetFailure(runtime, nodeId);
        }

        runtime.setNodeOutput(nodeId, "index", index);
        return scheduleDelay(runtime, intervalTicks)
            .thenCompose(v -> {
                clearFlowDataDependencies(runtime, runtime.getGraph(), loopTargets);
                return executeTargets(runtime, loopTargets, player, event, steps + 1);
            })
            .thenRun(runtime::consumeContinueLoopRequested)
            .thenCompose(v -> executeLoopIntervalIteration(runtime, nodeId, loopTargets, player, event, steps, index + 1, intervalTicks, maxIterations));
    }

    private CompletableFuture<Void> executeLoopWhile(FlowRuntime runtime, FlowNode loopNode, Player player, Event event, int steps) {
        FlowGraph graph = runtime.getGraph();
        String nodeId = findNodeId(graph, loopNode);
        if (nodeId == null) {
            return missingLoopNode();
        }
        Integer intervalValue = (Integer) runtime.resolveInput(loopNode, "interval_ticks", Integer.class);
        Integer maxValue = (Integer) runtime.resolveInput(loopNode, "max_iterations", Integer.class);
        int intervalTicks = intervalValue != null ? intervalValue : 1;
        int maxIterations = maxValue != null ? maxValue : 0;
        if (intervalTicks <= 0) return invalidLoopConfiguration(nodeId, "Loop interval must be positive");
        if (maxIterations < 0) return invalidLoopConfiguration(nodeId, "Maximum loop iterations cannot be negative");
        runtime.beginLoopControl();
        runtime.setNodeOutput(nodeId, "completed", false);

        List<String> loopTargets = findLoopTargets(graph, nodeId);
        List<String> completedTargets = findTargetNodes(graph, nodeId, "completed");
        CompletableFuture<Void> iterations = executeLoopWhileIteration(runtime, loopNode, nodeId, loopTargets, player, event,
            steps, 0, intervalTicks, maxIterations);
        return iterations.whenComplete((result, failure) -> runtime.endLoopControl())
            .thenCompose(v -> executeLoopCompletion(runtime, nodeId, completedTargets, player, event, steps));
    }

    private CompletableFuture<Void> executeLoopWhileIteration(FlowRuntime runtime, FlowNode loopNode, String nodeId, List<String> loopTargets,
                                                              Player player, Event event, int steps, int index,
                                                              int intervalTicks, int maxIterations) {
        if (runtime.isBreakLoopRequested() || (maxIterations > 0 && index >= maxIterations)) {
            return CompletableFuture.completedFuture(null);
        }
        if (!acquireExecutionBudget(runtime)) {
            return executionBudgetFailure(runtime, nodeId);
        }
        clearDependencyOutputs(runtime, runtime.getGraph(), nodeId, "condition");
        return ensureInputNodesReady(runtime, loopNode, player, event).thenCompose(ignored -> {
            Boolean condition = (Boolean) runtime.resolveInput(loopNode, "condition", Boolean.class);
            if (!Boolean.TRUE.equals(condition)) {
                return CompletableFuture.completedFuture(null);
            }

            runtime.setNodeOutput(nodeId, "index", index);
            return scheduleDelay(runtime, intervalTicks)
                .thenCompose(v -> {
                    clearFlowDataDependencies(runtime, runtime.getGraph(), loopTargets);
                    return executeTargets(runtime, loopTargets, player, event, steps + 1);
                })
                .thenRun(runtime::consumeContinueLoopRequested)
                .thenCompose(v -> executeLoopWhileIteration(runtime, loopNode, nodeId, loopTargets, player, event, steps,
                    index + 1, intervalTicks, maxIterations));
        });
    }

    private FlowExecutionException functionDepthFailure(FlowRuntime runtime, String nodeId) {
        int depth = runtime != null ? runtime.getCallDepth() : 0;
        if (depth < maxFunctionCallDepth) {
            return null;
        }
        return new FlowExecutionException(
            "FUNCTION_RECURSION_LIMIT",
            "Function call depth exceeded the configured limit: " + maxFunctionCallDepth,
            null,
            nodeId,
            "Remove unbounded recursion or raise the function call-depth policy",
            Map.of("callDepth", depth, "maximumCallDepth", maxFunctionCallDepth)
        );
    }

    private CompletableFuture<Void> executeLoopCompletion(FlowRuntime runtime, String nodeId, List<String> completedTargets,
                                                          Player player, Event event, int steps) {
        runtime.setNodeOutput(nodeId, "completed", true);
        List<String> doneTargets = findTargetNodes(runtime.getGraph(), nodeId, "done");
        runtime.resetFlowExecutionPath();
        return executeTargets(runtime, doneTargets.isEmpty() ? completedTargets : doneTargets, player, event, steps);
    }

    private boolean acquireExecutionBudget(FlowRuntime runtime) {
        return runtime.isWithinElapsedBudget(maxExecutionDurationMillis) && runtime.acquireExecutionOperation(maxExecutionSteps);
    }

    private CompletableFuture<Void> executionBudgetFailure(FlowRuntime runtime, String nodeId) {
        boolean operationBudgetExceeded = runtime.isWithinElapsedBudget(maxExecutionDurationMillis);
        String message = operationBudgetExceeded
            ? "Flow execution exceeded maximum operations: " + maxExecutionSteps
            : "Flow execution exceeded maximum duration: " + maxExecutionDurationMillis + "ms";
        return CompletableFuture.failedFuture(new FlowExecutionException(
            operationBudgetExceeded ? "EXECUTION_OPERATION_BUDGET" : "EXECUTION_DURATION_BUDGET",
            message,
            null,
            nodeId,
            operationBudgetExceeded
                ? "Reduce loop or fan-out work, or raise the configured operation budget"
                : "Reduce long-running work, or raise the configured duration budget"
        ));
    }

    private CompletableFuture<Void> invalidLoopConfiguration(String nodeId, String message) {
        return CompletableFuture.failedFuture(new FlowExecutionException(
            "LOOP_CONFIGURATION_INVALID",
            message,
            null,
            nodeId,
            "Use a positive tick interval and zero or a positive maximum iteration count"
        ));
    }

    private CompletableFuture<Void> missingLoopNode() {
        return CompletableFuture.failedFuture(new FlowExecutionException(
            "LOOP_NODE_MISSING",
            "Loop node is not part of the active graph",
            null,
            null,
            "Reconnect the loop to an existing node"
        ));
    }

    private CompletableFuture<Void> scheduleDelay(FlowRuntime runtime, int ticks) {
        if (ticks <= 0) {
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<Void> future = new CompletableFuture<>();
        String taskId = "loop_delay_" + UUID.randomUUID();
        BukkitTask task;
        try {
            task = Bukkit.getScheduler().runTaskLater(ReSync.getInstance(), () -> future.complete(null), ticks);
        } catch (RuntimeException exception) {
            future.completeExceptionally(exception);
            return future;
        }
        registerPendingTask(taskId, graphId(runtime), task, future);
        future.whenComplete((result, failure) -> finishPendingTask(taskId, failure));
        return future;
    }

    private CompletableFuture<Void> executeTargets(FlowRuntime runtime, List<String> targetNodeIds, Player player, Event event, int steps) {
        if (targetNodeIds == null || targetNodeIds.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        List<CompletableFuture<Void>> executions = new ArrayList<>(targetNodeIds.size());
        for (String nodeId : targetNodeIds) {
            if (loopControlRequested(runtime)) {
                break;
            }
            executions.add(execute(runtime, nodeId, player, event, steps + 1));
        }
        return CompletableFuture.allOf(executions.toArray(CompletableFuture[]::new));
    }

    private boolean loopControlRequested(FlowRuntime runtime) {
        return runtime != null && (runtime.isBreakLoopRequested() || runtime.isContinueLoopRequested());
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
        NodeDefinition definition = resolveDefinition(node);
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
            if ("flow".equals(conn.getTargetPin()) || "next".equals(conn.getTargetPin())) {
                return true;
            }
        }
        return false;
    }

    private String resolveLoopOperation(FlowNode node) {
        if (node == null) {
            return null;
        }
        String configuredOperation = node.getHandlerConfig().getString("operation");
        if (isLoopOperation(configuredOperation)) {
            return configuredOperation;
        }
        String type = node.getType();
        return isLoopOperation(type) ? type : null;
    }

    private boolean isLoopOperation(String type) {
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

    private String resolveFunctionCallId(FlowRuntime runtime, FlowNode node, NodeDefinition definition) {
        String customFunctionId = extractCustomFunctionId(node.getType());
        if (customFunctionId != null) {
            return customFunctionId;
        }
        String operation = node.getHandlerConfig().getString("operation");
        if ((operation == null || operation.isBlank()) && definition != null) {
            Object configuredOperation = definition.getHandlerConfig() != null ? definition.getHandlerConfig().get("operation") : null;
            operation = configuredOperation instanceof String value ? value : null;
        }
        boolean legacyType = "call.function".equals(node.getType()) || "call_function".equals(node.getType());
        if (!"call_function".equals(operation)
            || !legacyType && (definition == null || !"FunctionHandler".equals(definition.getHandler()))) {
            return null;
        }
        Object configuredFunction = runtime.resolveInput(node, "function");
        return configuredFunction != null ? String.valueOf(configuredFunction).trim() : "";
    }

    private String extractCustomFunctionId(String type) {
        if (!isCustomFunctionNode(type)) {
            return null;
        }
        return type.substring("custom_function:".length());
    }

    private CompletableFuture<Void> ensureInputNodesReady(FlowRuntime runtime, FlowNode node, Player player, Event event) {
        FlowGraph graph = runtime.getGraph();
        String nodeId = graph.findNodeId(node);
        if (nodeId == null) {
            return CompletableFuture.completedFuture(null);
        }

        CompletableFuture<Void> ready = CompletableFuture.completedFuture(null);
        for (FlowConnection conn : graph.getConnectionsToTarget(nodeId)) {
            if ("flow".equals(conn.getTargetPin())) {
                continue;
            }
            ready = ready.thenCompose(ignored -> runtime.hasNodeOutput(conn.getSourceNodeId(), conn.getSourcePin())
                ? CompletableFuture.completedFuture(null)
                : executeDataNode(runtime, conn.getSourceNodeId(), player, event));
        }
        return ready;
    }

    private CompletableFuture<Void> executeDataNode(FlowRuntime runtime, String nodeId, Player player, Event event) {
        if (nodeId == null || runtime.isEvaluating(nodeId)) {
            return CompletableFuture.completedFuture(null);
        }
        FlowNode sourceNode = runtime.getGraph().getNodes().get(nodeId);
        if (sourceNode == null) {
            return CompletableFuture.failedFuture(new FlowExecutionException(
                "DATA_NODE_MISSING",
                "Data dependency node not found: " + nodeId,
                null,
                nodeId,
                "Reconnect the input to an existing data node"
            ));
        }
        NodeHandler handler = resolveHandler(sourceNode);
        if (handler == null) {
            return CompletableFuture.failedFuture(new FlowExecutionException(
                "DATA_HANDLER_UNAVAILABLE",
                "No handler registered for data node type: " + sourceNode.getType(),
                null,
                nodeId,
                "Install the required capability or replace the unavailable data node"
            ));
        }
        FlowExecutionException operationFailure = validateHandlerOperation(sourceNode, nodeId);
        if (operationFailure != null) {
            return CompletableFuture.failedFuture(operationFailure);
        }
        if (!acquireExecutionBudget(runtime)) {
            return executionBudgetFailure(runtime, nodeId);
        }

        runtime.beginEvaluating(nodeId);
        CompletableFuture<Void> evaluation = ensureInputNodesReady(runtime, sourceNode, player, event)
            .thenCompose(ignored -> executeWithThreadPolicy(runtime, handler.getThreadPolicy(),
                () -> executeDataHandler(runtime, sourceNode, nodeId, player, event, handler)));
        return evaluation.handle((ignored, failure) -> {
                if (failure == null) {
                    return (Void) null;
                }
                Throwable cause = unwrapCompletionFailure(failure);
                FlowExecutionException exception = cause instanceof FlowExecutionException flowExecutionException
                    ? flowExecutionException
                    : new FlowExecutionException(
                        "DATA_EVALUATION_FAILED",
                        "Error evaluating data node '" + sourceNode.getType() + "' (ID: " + nodeId + ")",
                        cause,
                        nodeId,
                        "Inspect the data node inputs and the underlying handler failure"
                    );
                throw new CompletionException(exception);
            })
            .whenComplete((ignored, failure) -> runtime.endEvaluating(nodeId));
    }

    private CompletableFuture<Void> executeDataHandler(FlowRuntime runtime, FlowNode sourceNode, String nodeId, Player player,
                                                       Event event, NodeHandler handler) {
        String previousPin = runtime.getTriggeredOutputPin();
        runtime.setTriggeredOutputPin(null);
        FlowContext context = new FlowContext(runtime, player, event, ignored -> {}, this);
        try {
            handler.execute(context, sourceNode);
            context.finishSynchronousCapture();
            context.consumeTriggeredOutputs();
            runtime.consumeTriggeredOutput();
            return pendingOperations(context);
        } catch (Exception e) {
            return CompletableFuture.failedFuture(handlerFailure(e, nodeId, sourceNode.getType(), "DATA_EVALUATION_FAILED", "evaluating"));
        } finally {
            runtime.setTriggeredOutputPin(previousPin);
        }
    }

    private FlowExecutionException handlerFailure(Exception failure, String nodeId, String nodeType, String fallbackCode, String action) {
        if (failure instanceof FlowHandlerException handlerFailure) {
            return new FlowExecutionException(handlerFailure.getCode(), handlerFailure.getMessage(), handlerFailure, nodeId,
                handlerFailure.getRemediation(), handlerFailure.getDetails());
        }
        return new FlowExecutionException(fallbackCode, "Error " + action + " node '" + nodeType + "' (ID: " + nodeId + ")", failure, nodeId,
            "Inspect the node inputs and the underlying handler failure");
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

    private List<String> findLoopTargets(FlowGraph graph, String nodeId) {
        List<String> targets = findTargetNodes(graph, nodeId, "flow");
        if (targets.isEmpty()) {
            targets = findTargetNodes(graph, nodeId, "loop");
        }
        return targets;
    }

    private List<String> findTargetNodes(FlowGraph graph, String nodeId, String pinName) {
        List<String> targets = new ArrayList<>();
        for (FlowConnection conn : graph.getConnectionsFromSource(nodeId)) {
            if (conn.getSourcePin().equals(pinName)) {
                targets.add(conn.getTargetNodeId());
            }
        }
        return targets;
    }

    public void setGlobalVariable(String name, Object value) {
        if (value == null) {
            globalVariables.remove(name);
        } else {
            globalVariables.put(name, value);
        }
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
        registerPendingTask(taskId, "", task, null);
    }

    public void registerPendingTask(String taskId, BukkitTask task, CompletableFuture<Void> completion) {
        registerPendingTask(taskId, "", task, completion);
    }

    public void registerPendingTask(String taskId, String graphId, BukkitTask task) {
        registerPendingTask(taskId, graphId, task, null);
    }

    public void scheduleWallClockTask(String taskId, String graphId, long delayMillis, Runnable action, CompletableFuture<Void> completion) {
        if (taskId == null || taskId.isBlank() || action == null || completion == null) {
            throw new IllegalArgumentException("Wall-clock task ID, action, and completion are required");
        }
        if (delayMillis < 0L) throw new IllegalArgumentException("Wall-clock task delay must be non-negative");
        long normalizedDelay = delayMillis;
        long createdAt = System.currentTimeMillis();
        long nextFireAt = Math.addExact(createdAt, normalizedDelay);
        scheduleWallClockTask(taskId, graphId, "flow_wall_clock", normalizedDelay, createdAt, nextFireAt, false, () -> {
            action.run();
            return CompletableFuture.completedFuture(null);
        }, completion);
    }

    public void scheduleWallClockTask(String taskId, String graphId, String runtimeOwner, long delayMillis, long createdAt, long nextFireAt,
                                      boolean recurring, Supplier<CompletableFuture<Void>> action, CompletableFuture<Void> completion) {
        if (taskId == null || taskId.isBlank() || action == null || completion == null) {
            throw new IllegalArgumentException("Wall-clock task ID, action, and completion are required");
        }
        if (delayMillis < 0L) throw new IllegalArgumentException("Wall-clock task delay must be non-negative");
        if (wallClockScheduler.isShutdown()) {
            throw new IllegalStateException("Flow wall-clock scheduler is unavailable");
        }
        long normalizedDelay = delayMillis;
        WallClockTask pending = new WallClockTask(graphId, runtimeOwner, completion, createdAt, nextFireAt, recurring);
        terminalTasks.remove(taskId);
        WallClockTask previous = wallClockTasks.put(taskId, pending);
        if (previous != null) {
            previous.cancel();
        }
        completion.whenComplete((result, failure) -> unregisterWallClockTask(taskId, pending, failure));
        try {
            ScheduledFuture<?> timer = wallClockScheduler.schedule(() -> {
                if (completion.isDone()) {
                    return;
                }
                try {
                    Bukkit.getScheduler().runTask(ReSync.getInstance(), () -> {
                        if (completion.isDone()) {
                            return;
                        }
                        try {
                            CompletableFuture<Void> actionCompletion = action.get();
                            if (actionCompletion == null) {
                                completion.completeExceptionally(new IllegalStateException("Wall-clock task returned no completion future"));
                                return;
                            }
                            actionCompletion.whenComplete((result, failure) -> {
                                if (failure != null) {
                                    completion.completeExceptionally(unwrapCompletionFailure(failure));
                                } else {
                                    completion.complete(null);
                                }
                            });
                        } catch (RuntimeException exception) {
                            completion.completeExceptionally(exception);
                        }
                    });
                } catch (RuntimeException exception) {
                    completion.completeExceptionally(exception);
                }
            }, normalizedDelay, TimeUnit.MILLISECONDS);
            pending.attach(timer);
        } catch (RuntimeException exception) {
            completion.completeExceptionally(exception);
            throw exception;
        }
    }

    private void unregisterWallClockTask(String taskId, WallClockTask expected, Throwable failure) {
        if (taskId == null || expected == null || !wallClockTasks.remove(taskId, expected) || expected.cancelled) {
            return;
        }
        ScheduledTaskState state = expected.completion.isCancelled() ? ScheduledTaskState.CANCELLED
                : failure != null ? ScheduledTaskState.FAILED : ScheduledTaskState.FINISHED;
        Throwable cause = failure != null ? unwrapCompletionFailure(failure) : null;
        String message = cause != null && cause.getMessage() != null ? cause.getMessage() : "";
        rememberTerminalWallClockTask(taskId, expected, state, message);
    }

    public void registerPendingTask(String taskId, String graphId, BukkitTask task, CompletableFuture<Void> completion) {
        registerPendingTask(taskId, graphId, "flow_runtime", task, completion, System.currentTimeMillis(), -1L, false);
    }

    public void registerPendingTask(String taskId, String graphId, String runtimeOwner, BukkitTask task, CompletableFuture<Void> completion,
                                    long createdAt, long nextFireAt, boolean recurring) {
        if (taskId == null || taskId.isBlank() || task == null) {
            throw new IllegalArgumentException("Tracked task ID and Bukkit task are required");
        }
        terminalTasks.remove(taskId);
        PendingTask previous = pendingTasks.get(taskId);
        long stableCreatedAt = previous != null ? previous.createdAt() : createdAt;
        PendingTask replacement = new PendingTask(graphId != null ? graphId : "", runtimeOwner != null ? runtimeOwner : "flow_runtime", task,
            completion, stableCreatedAt, nextFireAt, recurring, previous != null ? previous.lastFailure() : "");
        previous = pendingTasks.put(taskId, replacement);
        if (previous != null && !previous.task().isCancelled()) {
            previous.task().cancel();
        }
        if (previous != null && previous.completion() != null && !previous.completion().isDone()) {
            previous.completion().cancel(false);
        }
    }

    public void unregisterPendingTask(String taskId) {
        if (taskId != null) {
            PendingTask removed = pendingTasks.remove(taskId);
            if (removed != null) {
                rememberTerminalTask(taskId, removed, TaskCancellationStatus.FINISHED);
            }
        }
    }

    public void finishPendingTask(String taskId, Throwable failure) {
        if (failure != null) recordScheduledTaskFailure(taskId, unwrapCompletionFailure(failure));
        unregisterPendingTask(taskId);
    }

    public boolean cancelPendingTask(String taskId) {
        TaskCancellationStatus status = cancelPendingTaskWithStatus(taskId);
        return status == TaskCancellationStatus.CANCELLED || status == TaskCancellationStatus.ALREADY_CANCELLED;
    }

    public TaskCancellationStatus cancelPendingTaskWithStatus(String taskId) {
        PendingTask pending = taskId != null ? pendingTasks.remove(taskId) : null;
        if (pending == null) {
            WallClockTask wallClockTask = taskId != null ? wallClockTasks.remove(taskId) : null;
            if (wallClockTask != null) {
                wallClockTask.cancel();
                rememberTerminalWallClockTask(taskId, wallClockTask, ScheduledTaskState.CANCELLED, "");
                return TaskCancellationStatus.CANCELLED;
            }
            TerminalTask terminal = taskId != null ? terminalTasks.get(taskId) : null;
            if (terminal == null) {
                return TaskCancellationStatus.UNKNOWN;
            }
            return terminal.snapshot().state() == ScheduledTaskState.CANCELLED
                ? TaskCancellationStatus.ALREADY_CANCELLED
                : TaskCancellationStatus.FINISHED;
        }
        if (!pending.task().isCancelled()) {
            pending.task().cancel();
        }
        if (pending.completion() != null && !pending.completion().isDone()) {
            pending.completion().cancel(false);
        }
        rememberTerminalTask(taskId, pending, TaskCancellationStatus.CANCELLED);
        return TaskCancellationStatus.CANCELLED;
    }

    public void cancelPendingTasks() {
        for (Map.Entry<String, PendingTask> entry : pendingTasks.entrySet()) {
            PendingTask pending = entry.getValue();
            if (!pending.task().isCancelled()) {
                pending.task().cancel();
            }
            if (pending.completion() != null && !pending.completion().isDone()) {
                pending.completion().cancel(false);
            }
            rememberTerminalTask(entry.getKey(), pending, TaskCancellationStatus.CANCELLED);
        }
        pendingTasks.clear();
        for (Map.Entry<String, WallClockTask> entry : wallClockTasks.entrySet()) {
            WallClockTask pending = entry.getValue();
            if (wallClockTasks.remove(entry.getKey(), pending)) {
                pending.cancel();
                rememberTerminalWallClockTask(entry.getKey(), pending, ScheduledTaskState.CANCELLED, "");
            }
        }
    }

    public int cancelPendingTasks(String graphId) {
        if (graphId == null || graphId.isBlank()) {
            return 0;
        }
        List<String> taskIds = pendingTasks.entrySet().stream().filter(entry -> graphId.equals(entry.getValue().graphId())).map(Map.Entry::getKey).toList();
        List<String> wallClockTaskIds = wallClockTasks.entrySet().stream().filter(entry -> graphId.equals(entry.getValue().graphId)).map(Map.Entry::getKey).toList();
        int cancelled = 0;
        for (String taskId : taskIds) {
            if (cancelPendingTask(taskId)) {
                cancelled++;
            }
        }
        for (String taskId : wallClockTaskIds) {
            if (cancelPendingTask(taskId)) {
                cancelled++;
            }
        }
        return cancelled;
    }

    public ScheduledTaskSnapshot getScheduledTaskSnapshot(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return null;
        }
        PendingTask pending = pendingTasks.get(taskId);
        if (pending != null) {
            return snapshot(taskId, pending, ScheduledTaskState.ACTIVE);
        }
        WallClockTask wallClockTask = wallClockTasks.get(taskId);
        if (wallClockTask != null) {
            return snapshot(taskId, wallClockTask, ScheduledTaskState.ACTIVE, "");
        }
        TerminalTask terminal = terminalTasks.get(taskId);
        return terminal != null ? terminal.snapshot() : null;
    }

    public List<ScheduledTaskSnapshot> getScheduledTaskSnapshots() {
        Map<String, ScheduledTaskSnapshot> snapshots = new LinkedHashMap<>();
        terminalTasks.forEach((taskId, terminal) -> snapshots.put(taskId, terminal.snapshot()));
        pendingTasks.forEach((taskId, pending) -> snapshots.put(taskId, snapshot(taskId, pending, ScheduledTaskState.ACTIVE)));
        wallClockTasks.forEach((taskId, pending) -> snapshots.put(taskId, snapshot(taskId, pending, ScheduledTaskState.ACTIVE, "")));
        return snapshots.values().stream()
            .sorted((first, second) -> first.taskId().compareToIgnoreCase(second.taskId()))
            .toList();
    }

    public void updateScheduledTaskNextFireAt(String taskId, long nextFireAt) {
        if (taskId == null || taskId.isBlank()) {
            return;
        }
        pendingTasks.computeIfPresent(taskId, (ignored, pending) -> new PendingTask(pending.graphId(), pending.runtimeOwner(), pending.task(),
            pending.completion(), pending.createdAt(), nextFireAt, pending.recurring(), pending.lastFailure()));
    }

    public void recordScheduledTaskFailure(String taskId, Throwable failure) {
        if (taskId == null || taskId.isBlank() || failure == null) {
            return;
        }
        String message = failure.getMessage() != null && !failure.getMessage().isBlank() ? failure.getMessage() : failure.getClass().getSimpleName();
        pendingTasks.computeIfPresent(taskId, (ignored, pending) -> new PendingTask(pending.graphId(), pending.runtimeOwner(), pending.task(),
            pending.completion(), pending.createdAt(), pending.nextFireAt(), pending.recurring(), message));
        WallClockTask wallClockTask = wallClockTasks.get(taskId);
        if (wallClockTask != null) {
            wallClockTask.lastFailure = message;
        }
    }

    private void rememberTerminalTask(String taskId, PendingTask pending, TaskCancellationStatus status) {
        if (taskId == null || taskId.isBlank()) {
            return;
        }
        ScheduledTaskState state = status == TaskCancellationStatus.CANCELLED ? ScheduledTaskState.CANCELLED
            : pending.lastFailure() != null && !pending.lastFailure().isBlank() ? ScheduledTaskState.FAILED : ScheduledTaskState.FINISHED;
        terminalTasks.put(taskId, new TerminalTask(snapshot(taskId, pending, state), System.currentTimeMillis()));
        trimTerminalTasks();
    }

    private void rememberTerminalWallClockTask(String taskId, WallClockTask pending, ScheduledTaskState state, String lastFailure) {
        if (taskId == null || taskId.isBlank()) {
            return;
        }
        terminalTasks.put(taskId, new TerminalTask(snapshot(taskId, pending, state, lastFailure), System.currentTimeMillis()));
        trimTerminalTasks();
    }

    private void trimTerminalTasks() {
        if (terminalTasks.size() <= 1024) {
            return;
        }
        terminalTasks.entrySet().stream()
            .min(Map.Entry.comparingByValue((first, second) -> Long.compare(first.completedAt(), second.completedAt())))
            .map(Map.Entry::getKey)
            .ifPresent(terminalTasks::remove);
    }

    private ScheduledTaskSnapshot snapshot(String taskId, PendingTask pending, ScheduledTaskState state) {
        return new ScheduledTaskSnapshot(taskId, pending.runtimeOwner(), pending.graphId(), pending.createdAt(), pending.nextFireAt(), pending.recurring(), state,
            pending.lastFailure() != null ? pending.lastFailure() : "");
    }

    private ScheduledTaskSnapshot snapshot(String taskId, WallClockTask pending, ScheduledTaskState state, String lastFailure) {
        String failure = lastFailure != null && !lastFailure.isBlank() ? lastFailure : pending.lastFailure;
        return new ScheduledTaskSnapshot(taskId, pending.runtimeOwner, pending.graphId, pending.createdAt, pending.nextFireAt, pending.recurring, state,
            failure != null ? failure : "");
    }

    public void shutdown() {
        cancelPendingTasks();
        wallClockScheduler.shutdownNow();
    }

    private String graphId(FlowRuntime runtime) {
        FlowGraph graph = runtime != null ? runtime.getGraph() : null;
        return graph != null && graph.getId() != null ? graph.getId() : "";
    }

    private String findStartNode(FlowGraph graph) {
        if (graph == null || graph.getNodes() == null || graph.getNodes().isEmpty()) {
            return null;
        }
        List<Map.Entry<String, FlowNode>> candidates = graph.getNodes().entrySet().stream()
            .filter(entry -> !hasIncomingFlowConnection(graph, entry.getKey()))
            .sorted(Map.Entry.comparingByKey(String.CASE_INSENSITIVE_ORDER))
            .toList();
        for (Map.Entry<String, FlowNode> candidate : candidates) {
            String type = candidate.getValue().getType();
            if (resolveTriggerDefinition(candidate.getValue()) != null || isFunctionStartType(type)) {
                return candidate.getKey();
            }
        }
        for (Map.Entry<String, FlowNode> candidate : candidates) {
            NodeDefinition definition = resolveDefinition(candidate.getValue());
            if (definition != null && definition.getInputs().stream().anyMatch(pin -> pin.getType() == NodeDefinition.PinType.FLOW)) {
                return candidate.getKey();
            }
            if (hasOutgoingExecutionConnection(graph, candidate.getKey())) {
                return candidate.getKey();
            }
        }
        return candidates.isEmpty() ? graph.getNodes().keySet().stream().sorted(String.CASE_INSENSITIVE_ORDER).findFirst().orElse(null)
            : candidates.getFirst().getKey();
    }

    private NodeDefinition resolveDefinition(FlowNode node) {
        if (nodeDefinitionRegistry == null || node == null || node.getType() == null) {
            return null;
        }
        return nodeDefinitionRegistry.get(idCompatibility.mapToNew(node.getType()));
    }

    private boolean isFunctionStartType(String type) {
        return "function_start".equals(type) || "function.start".equals(type) || "function.function_start".equals(type);
    }

    private boolean hasOutgoingExecutionConnection(FlowGraph graph, String nodeId) {
        for (FlowConnection connection : graph.getConnectionsFromSource(nodeId)) {
            String pin = connection.getSourcePin();
            if (pin != null && ("flow".equals(pin) || "next".equals(pin) || "loop".equals(pin) || "done".equals(pin)
                || "true".equals(pin) || "false".equals(pin) || pin.startsWith("branch_"))) {
                return true;
            }
        }
        return false;
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

    private FlowExecutionException validateHandlerOperation(FlowNode node, String nodeId) {
        String operation = node.getHandlerConfig().getString("operation");
        if (operation == null || operation.isBlank()) {
            return null;
        }
        String handlerId = node.getType();
        if (nodeDefinitionRegistry != null) {
            NodeDefinition definition = nodeDefinitionRegistry.get(node.getType());
            if (definition != null && definition.getHandler() != null && !definition.getHandler().isBlank()) {
                handlerId = definition.getHandler();
            }
        }
        Set<String> supportedOperations = handlerRegistry.getSupportedOperations(handlerId);
        if (supportedOperations.contains(operation)) {
            return null;
        }
        return new FlowExecutionException(
            "OPERATION_UNAVAILABLE",
            "Handler '" + handlerId + "' does not support operation: " + operation,
            null,
            nodeId,
            "Migrate or replace the node with a supported operation",
            Map.of("handler", handlerId, "operation", operation)
        );
    }

    private FlowTraceRecord startTraceRecord(FlowRuntime runtime, FlowGraph graph, FlowNode node, String nodeId, int steps, String status,
                                             long durationNanos, Throwable error) {
        FlowTraceRecord record = new FlowTraceRecord();
        record.setGraphId(graph != null ? graph.getId() : "");
        record.setExecutionId(runtime != null ? runtime.getExecutionId() : "");
        record.setNodeId(nodeId);
        record.setNodeType(node != null ? node.getType() : "");
        record.setStatus(status);
        record.setExecutionDepth(steps);
        record.setDurationNanos(durationNanos);
        record.setErrorText(error != null ? error.getMessage() : "");
        if (error instanceof FlowExecutionException executionException) {
            record.setErrorCode(executionException.getCode());
            record.setRemediation(executionException.getRemediation());
        }
        record.setInputSummary(summarizeInputs(node));
        record.setOutputSummary("");
        return record;
    }

    private String summarizeInputs(FlowNode node) {
        if (node == null || node.getInputValues() == null || node.getInputValues().isEmpty()) {
            return "";
        }
        NodeDefinition definition = resolveDefinition(node);
        if (definition != null && definition.isSensitive()) {
            return "[redacted]";
        }
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, Object> entry : node.getInputValues().entrySet()) {
            if (!builder.isEmpty()) {
                builder.append(", ");
            }
            builder.append(entry.getKey()).append('=').append(isSensitiveInput(entry.getKey()) ? "[redacted]" : summarizeValue(entry.getValue()));
            if (builder.length() > 240) {
                return builder.substring(0, 240);
            }
        }
        return builder.toString();
    }

    private boolean isSensitiveInput(String key) {
        if (key == null) {
            return false;
        }
        String normalized = key.toLowerCase(Locale.ROOT);
        return normalized.contains("password") || normalized.contains("secret") || normalized.contains("token")
            || normalized.contains("api_key") || normalized.contains("apikey") || normalized.contains("credential")
            || normalized.contains("authorization") || normalized.equals("auth") || normalized.equals("headers")
            || normalized.equals("response")
            || normalized.contains("cookie") || normalized.contains("session") || normalized.contains("private_key")
            || normalized.contains("access_key");
    }

    private String summarizeValue(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Map<?, ?> map) {
            StringBuilder builder = new StringBuilder("{");
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (builder.length() > 1) {
                    builder.append(", ");
                }
                String key = String.valueOf(entry.getKey());
                builder.append(key).append('=').append(isSensitiveInput(key) ? "[redacted]" : summarizeValue(entry.getValue()));
                if (builder.length() > 48) {
                    return builder.substring(0, 48) + "...";
                }
            }
            return builder.append('}').toString();
        }
        if (value instanceof Iterable<?> iterable) {
            StringBuilder builder = new StringBuilder("[");
            for (Object element : iterable) {
                if (builder.length() > 1) {
                    builder.append(", ");
                }
                builder.append(summarizeValue(element));
                if (builder.length() > 48) {
                    return builder.substring(0, 48) + "...";
                }
            }
            return builder.append(']').toString();
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
        trace(startTraceRecord(runtime, graph, node, nodeId, steps, "success", System.nanoTime() - traceStarted, null));
        FlowDebugService debugger = debugService;
        if (debugger != null && debugger.isEnabled()) {
            debugger.afterNode(runtime, graph, node, nodeId, steps, "success", "", summarizeInputs(node), "");
        }
    }

    private void traceFailure(FlowRuntime runtime, FlowGraph graph, FlowNode node, String nodeId, int steps, long traceStarted, Throwable error) {
        trace(startTraceRecord(runtime, graph, node, nodeId, steps, "failure", System.nanoTime() - traceStarted, error));
        FlowDebugService debugger = debugService;
        if (debugger != null && debugger.isEnabled()) {
            debugger.afterNode(runtime, graph, node, nodeId, steps, "failure", error != null ? error.getMessage() : "", summarizeInputs(node), "");
        }
    }

    public static class FlowExecutionException extends Exception {
        private final String code;
        private final String nodeId;
        private final String remediation;
        private final Map<String, Object> details;

        public FlowExecutionException(String message, Throwable cause, String nodeId) {
            this("FLOW_EXECUTION_FAILED", message, cause, nodeId, "Inspect the node and its runtime inputs");
        }

        public FlowExecutionException(String code, String message, Throwable cause, String nodeId, String remediation) {
            this(code, message, cause, nodeId, remediation, Map.of());
        }

        public FlowExecutionException(String code, String message, Throwable cause, String nodeId, String remediation,
                                      Map<String, Object> details) {
            super(message, cause);
            this.code = code;
            this.nodeId = nodeId;
            this.remediation = remediation;
            this.details = details != null ? Map.copyOf(details) : Map.of();
        }

        public String getCode() {
            return code;
        }

        public String getNodeId() {
            return nodeId;
        }

        public String getRemediation() {
            return remediation;
        }

        public Map<String, Object> getDetails() {
            return details;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("code", code);
            result.put("message", getMessage());
            result.put("nodeId", nodeId != null ? nodeId : "");
            result.put("remediation", remediation != null ? remediation : "");
            result.put("details", details);
            return Collections.unmodifiableMap(result);
        }
    }
}
