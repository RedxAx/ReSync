package restudio.resync.flow;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.Cancellable;
import org.bukkit.scheduler.BukkitTask;
import restudio.flow.data.FlowDataType;
import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowNode;
import restudio.resync.ReSync;
import restudio.resync.flow.handler.FlowHandlerException;
import restudio.resync.flow.registry.NodeDefinition;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;

public class FlowContext {
    private final Player player;
    private final Event event;
    private final FlowRuntime runtime;
    private final FlowExecutor executor;
    private final Map<String, CompletableFuture<Void>> asyncOperations = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<Void>> beforeContinuationOperations = new ConcurrentHashMap<>();
    private final List<String> triggeredOutputs = new CopyOnWriteArrayList<>();
    private final Consumer<String> deferredOutputConsumer;
    private Function<String, CompletableFuture<Void>> deferredOutputDispatcher;
    private final AtomicLong operationCounter = new AtomicLong(0);
    private final AtomicBoolean deferredOutputTriggered = new AtomicBoolean();
    private volatile boolean synchronousCapture = true;
    private volatile boolean continuationHalted;

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

    public Player getPlayerInput(FlowNode node, String pinName) {
        Player selected = getInputValue(node, pinName, Player.class, null);
        return selected != null ? selected : player;
    }

    public Event getEvent() {
        return event;
    }

    public boolean isEventMutationOpen() {
        return event != null && runtime != null && runtime.isEventMutationOpen();
    }

    public boolean setEventCancelled(boolean cancelled) {
        if (!isEventMutationOpen() || !(event instanceof Cancellable cancellable)) {
            return false;
        }
        cancellable.setCancelled(cancelled);
        return true;
    }

    public String getFlowId() {
        FlowGraph graph = runtime.getGraph();
        return graph != null && graph.getId() != null ? graph.getId() : "";
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
        return getInputValue(node, pinName, type, null);
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

    public <T> List<T> getRepeatableInputValues(FlowNode node, String basePinName, Class<T> type) {
        NodeDefinition definition = runtime.getDefinition(node);
        NodeDefinition.PinDefinition base = definition != null ? definition.getInputs().stream()
            .filter(pin -> basePinName.equals(pin.getName()))
            .findFirst()
            .orElse(null) : null;
        NodeDefinition.RepeatablePin repeatable = base != null ? base.getRepeatable() : null;
        if (repeatable == null) {
            T value = getInputValue(node, basePinName, type, null);
            return value != null ? List.of(value) : List.of();
        }
        int count = repeatable.getMinItems();
        if (node.getInputValues() != null) {
            Object stored = node.getInputValues().get("__repeatable_count:" + repeatable.getGroupId());
            if (stored == null && "permissions".equals(repeatable.getGroupId())) {
                stored = node.getInputValues().get("__permission_count");
            }
            if (stored instanceof Number number) {
                count = number.intValue();
            } else if (stored != null) {
                try {
                    count = Integer.parseInt(stored.toString());
                } catch (NumberFormatException exception) {
                    throw new IllegalArgumentException("Repeatable pin count is not a valid integer: " + stored, exception);
                }
            }
        }
        count = Math.clamp(count, repeatable.getMinItems(), repeatable.getMaxItems());
        Set<String> removed = new HashSet<>();
        if (node.getInputValues() != null && node.getInputValues().get("__removed_optional_inputs") instanceof Iterable<?> names) {
            for (Object name : names) {
                if (name != null) {
                    removed.add(name.toString());
                }
            }
        }
        List<T> values = new ArrayList<>();
        for (int index = 1; index <= count; index++) {
            String pinName = index == 1 ? basePinName : basePinName + "_" + index;
            if (removed.contains(pinName)) {
                continue;
            }
            T value = getInputValue(node, pinName, type, null);
            if (value != null) {
                values.add(value);
            }
        }
        return List.copyOf(values);
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

        boolean hasDeferredOutputTarget = deferredOutputConsumer != null || deferredOutputDispatcher != null;
        if (synchronousCapture || !hasDeferredOutputTarget) {
            runtime.triggerOutput(pinName);
        }

        if (synchronousCapture) {
            triggeredOutputs.add(pinName);
            return;
        }

        if (deferredOutputConsumer != null) {
            deferredOutputTriggered.set(true);
            deferredOutputConsumer.accept(pinName);
        }
        if (deferredOutputDispatcher != null) {
            deferredOutputTriggered.set(true);
            CompletableFuture<Void> continuation = deferredOutputDispatcher.apply(pinName);
            trackAsyncOperation(nextOperationId("continuation"), continuation);
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

    public boolean hasDeferredOutputTriggered() {
        return deferredOutputTriggered.get();
    }

    private String nextOperationId(String prefix) {
        return prefix + '_' + operationCounter.incrementAndGet() + '_' + System.nanoTime();
    }

    private void trackAsyncOperation(String taskId, CompletableFuture<Void> future) {
        if (taskId == null || future == null) {
            return;
        }
        asyncOperations.put(taskId, future);
    }

    public void haltContinuation() {
        continuationHalted = true;
    }

    public boolean isContinuationHalted() {
        return continuationHalted;
    }

    private void trackBeforeContinuationOperation(String taskId, CompletableFuture<Void> future) {
        if (taskId == null || future == null) {
            return;
        }
        beforeContinuationOperations.put(taskId, future);
    }

    public CompletableFuture<Void> awaitBeforeContinuation(CompletableFuture<?> operation) {
        if (operation == null) {
            throw new IllegalArgumentException("Flow continuation operation is required");
        }
        CompletableFuture<Void> completion = operation.thenApply(ignored -> null);
        String taskId = nextOperationId("await_continue");
        trackAsyncOperation(taskId, completion);
        trackBeforeContinuationOperation(taskId, completion);
        return completion;
    }

    public void setNodeOutput(String nodeId, String pinName, Object value) {
        runtime.setNodeOutput(nodeId, pinName, value);
    }

    public void setOutput(FlowNode node, String pinName, Object value) {
        runtime.setNodeOutput(runtime.findNodeId(node), pinName, value);
    }

    public Object getOutput(FlowNode node, String pinName) {
        return runtime.getNodeOutput(runtime.findNodeId(node), pinName);
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

    public FlowExecutor getExecutor() {
        return executor;
    }

    public CompletableFuture<Void> runAsync(Runnable runnable) {
        if (runnable == null) throw new IllegalArgumentException("Async Flow action is required");
        CompletableFuture<Void> future = new CompletableFuture<>();
        String taskId = nextOperationId("async");
        trackAsyncOperation(taskId, future);
        try {
            Bukkit.getScheduler().runTaskAsynchronously(ReSync.getInstance(), () -> complete(future, runnable));
        } catch (RuntimeException exception) {
            future.completeExceptionally(exception);
            throw exception;
        }
        return future;
    }

    public CompletableFuture<Void> runSync(Runnable runnable) {
        if (runnable == null) throw new IllegalArgumentException("Synchronous Flow action is required");
        CompletableFuture<Void> future = new CompletableFuture<>();
        String taskId = nextOperationId("sync");
        trackAsyncOperation(taskId, future);
        try {
            Bukkit.getScheduler().runTask(ReSync.getInstance(), () -> complete(future, runnable));
        } catch (RuntimeException exception) {
            future.completeExceptionally(exception);
            throw exception;
        }
        return future;
    }

    public CompletableFuture<Void> runSyncBeforeContinuation(Runnable runnable) {
        if (runnable == null) throw new IllegalArgumentException("Synchronous Flow action is required");
        CompletableFuture<Void> future = new CompletableFuture<>();
        String taskId = nextOperationId("sync_continue");
        trackAsyncOperation(taskId, future);
        trackBeforeContinuationOperation(taskId, future);
        try {
            Bukkit.getScheduler().runTask(ReSync.getInstance(), () -> complete(future, runnable));
        } catch (RuntimeException exception) {
            future.completeExceptionally(exception);
            throw exception;
        }
        return future;
    }

    public CompletableFuture<Void> runAsyncBeforeContinuation(Runnable runnable) {
        if (runnable == null) throw new IllegalArgumentException("Async Flow action is required");
        CompletableFuture<Void> future = new CompletableFuture<>();
        String taskId = nextOperationId("async_continue");
        trackAsyncOperation(taskId, future);
        trackBeforeContinuationOperation(taskId, future);
        try {
            Bukkit.getScheduler().runTaskAsynchronously(ReSync.getInstance(), () -> complete(future, runnable));
        } catch (RuntimeException exception) {
            future.completeExceptionally(exception);
            throw exception;
        }
        return future;
    }

    private void complete(CompletableFuture<Void> future, Runnable runnable) {
        try {
            runnable.run();
            future.complete(null);
        } catch (Exception exception) {
            future.completeExceptionally(exception);
        }
    }

    public CompletableFuture<Void> runLater(Runnable runnable, long delayTicks) {
        return runLater(runnable, delayTicks, false);
    }

    public CompletableFuture<Void> runLaterBeforeContinuation(Runnable runnable, long delayTicks) {
        return runLater(runnable, delayTicks, true);
    }

    public CompletableFuture<Void> runAfterMillisBeforeContinuation(Runnable runnable, long delayMillis) {
        if (runnable == null) throw new IllegalArgumentException("Delayed Flow action is required");
        if (delayMillis < 0L) throw new IllegalArgumentException("Flow delay milliseconds must be non-negative");
        CompletableFuture<Void> future = new CompletableFuture<>();
        String taskId = nextOperationId("wall_continue");
        trackAsyncOperation(taskId, future);
        trackBeforeContinuationOperation(taskId, future);
        if (executor == null) {
            IllegalStateException failure = new IllegalStateException("Flow executor is unavailable");
            future.completeExceptionally(failure);
            throw failure;
        }
        FlowGraph graph = runtime != null ? runtime.getGraph() : null;
        try {
            executor.scheduleWallClockTask(taskId, graph != null ? graph.getId() : "", delayMillis, runnable, future);
        } catch (RuntimeException exception) {
            future.completeExceptionally(exception);
            throw exception;
        }
        return future;
    }

    private CompletableFuture<Void> runLater(Runnable runnable, long delayTicks, boolean beforeContinuation) {
        if (runnable == null) throw new IllegalArgumentException("Delayed Flow action is required");
        if (delayTicks < 0L) throw new IllegalArgumentException("Flow delay ticks must be non-negative");
        CompletableFuture<Void> future = new CompletableFuture<>();
        String taskId = nextOperationId(beforeContinuation ? "later_continue" : "later");
        trackAsyncOperation(taskId, future);
        if (beforeContinuation) {
            trackBeforeContinuationOperation(taskId, future);
        }
        BukkitTask task;
        try {
            task = Bukkit.getScheduler().runTaskLater(ReSync.getInstance(), () -> {
                try {
                    runnable.run();
                    future.complete(null);
                } catch (Exception e) {
                    future.completeExceptionally(e);
                }
            }, delayTicks);
        } catch (RuntimeException exception) {
            future.completeExceptionally(exception);
            throw exception;
        }
        if (executor != null) {
            FlowGraph graph = runtime.getGraph();
            executor.registerPendingTask(taskId, graph != null ? graph.getId() : "", task, future);
            future.whenComplete((result, failure) -> executor.finishPendingTask(taskId, failure));
        }
        return future;
    }

    public boolean cancelScheduledTask(String taskId) {
        return executor != null && executor.cancelPendingTask(taskId);
    }

    public FlowExecutor.TaskCancellationStatus cancelScheduledTaskWithStatus(String taskId) {
        return executor != null ? executor.cancelPendingTaskWithStatus(taskId) : FlowExecutor.TaskCancellationStatus.UNKNOWN;
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

    void setDeferredOutputDispatcher(Function<String, CompletableFuture<Void>> deferredOutputDispatcher) {
        this.deferredOutputDispatcher = deferredOutputDispatcher;
    }

    public FlowContext createSubContext(Map<String, Object> variables) {
        FlowContext child = new FlowContext(runtime, player, event, deferredOutputConsumer, executor);
        child.deferredOutputDispatcher = deferredOutputDispatcher;
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
        try {
            return executeSubFlowBooleanAsync(subGraph, node, extraInputs).get(5, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new FlowHandlerException("SUBFLOW_INTERRUPTED", "Subflow evaluation was interrupted",
                "Retry when the Flow runtime is available", Map.of(), exception);
        } catch (TimeoutException exception) {
            throw new FlowHandlerException("SUBFLOW_TIMEOUT", "Subflow evaluation timed out",
                "Reduce the subflow work or use asynchronous composition", Map.of("timeoutSeconds", 5), exception);
        } catch (ExecutionException exception) {
            throw subFlowFailure(exception);
        }
    }

    public CompletableFuture<Boolean> executeSubFlowBooleanAsync(FlowGraph subGraph, FlowNode node, Map<String, Object> extraInputs) {
        return executeSubFlowAsync(subGraph, "condition", extraInputs).thenApply(result -> {
            if (result instanceof Boolean bool) {
                return bool;
            }
            throw new FlowHandlerException("SUBFLOW_RESULT_TYPE_INVALID", "Subflow condition output is not boolean",
                "Return a boolean from the condition output", Map.of("actualType", result != null ? result.getClass().getName() : "absent"));
        });
    }

    public Object executeSubFlowObject(FlowGraph subGraph, FlowNode node) {
        return executeSubFlowObject(subGraph, node, null);
    }

    public Object executeSubFlowObject(FlowGraph subGraph, FlowNode node, Map<String, Object> extraInputs) {
        try {
            return executeSubFlowObjectAsync(subGraph, node, extraInputs).get(5, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new FlowHandlerException("SUBFLOW_INTERRUPTED", "Subflow evaluation was interrupted",
                "Retry when the Flow runtime is available", Map.of(), exception);
        } catch (TimeoutException exception) {
            throw new FlowHandlerException("SUBFLOW_TIMEOUT", "Subflow evaluation timed out",
                "Reduce the subflow work or use asynchronous composition", Map.of("timeoutSeconds", 5), exception);
        } catch (ExecutionException exception) {
            throw subFlowFailure(exception);
        }
    }

    public CompletableFuture<Object> executeSubFlowObjectAsync(FlowGraph subGraph, FlowNode node, Map<String, Object> extraInputs) {
        return executeSubFlowAsync(subGraph, "result", extraInputs);
    }

    private CompletableFuture<Object> executeSubFlowAsync(FlowGraph subGraph, String outputPin, Map<String, Object> extraInputs) {
        if (executor == null) {
            return CompletableFuture.failedFuture(new FlowHandlerException("SUBFLOW_EXECUTOR_UNAVAILABLE", "Subflow executor is unavailable",
                "Restore the Flow runtime before executing this subflow"));
        }
        if (runtime == null) {
            return CompletableFuture.failedFuture(new FlowHandlerException("SUBFLOW_RUNTIME_UNAVAILABLE", "Parent Flow runtime is unavailable",
                "Execute the subflow from an active Flow context"));
        }
        String outputNodeId;
        try {
            outputNodeId = FlowSubFlowSupport.requireOutputNodeId(subGraph, outputPin);
        } catch (FlowHandlerException exception) {
            return CompletableFuture.failedFuture(exception);
        }
        Map<String, Object> inputs = new HashMap<>(runtime.getLocalVariables());
        if (extraInputs != null) {
            inputs.putAll(extraInputs);
        }
        return executor.executeSubFlow(runtime, subGraph, outputNodeId, outputPin, player, event, inputs);
    }

    private FlowHandlerException subFlowFailure(ExecutionException failure) {
        Throwable cause = failure;
        while ((cause instanceof ExecutionException || cause instanceof CompletionException) && cause.getCause() != null) {
            cause = cause.getCause();
        }
        if (cause instanceof FlowHandlerException handlerFailure) {
            return handlerFailure;
        }
        if (cause instanceof FlowExecutor.FlowExecutionException executionFailure) {
            return new FlowHandlerException(executionFailure.getCode(), executionFailure.getMessage(), executionFailure.getRemediation(),
                executionFailure.getDetails(), executionFailure);
        }
        return new FlowHandlerException("SUBFLOW_EXECUTION_FAILED", "Subflow execution failed",
            "Inspect the subflow and its inputs", Map.of(), cause);
    }

}
