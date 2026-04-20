package restudio.resync.flow;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.scheduler.BukkitTask;
import restudio.flow.data.FlowNode;
import restudio.resync.ReSync;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public class FlowContext {
    private final Player player;
    private final Event event;
    private final FlowRuntime runtime;
    private final Map<String, CompletableFuture<Void>> asyncOperations = new ConcurrentHashMap<>();
    private final List<String> triggeredOutputs = new CopyOnWriteArrayList<>();
    private final Consumer<String> deferredOutputConsumer;
    private final AtomicLong operationCounter = new AtomicLong(0);
    private volatile boolean synchronousCapture = true;

    public FlowContext(FlowRuntime runtime, Player player, Event event) {
        this(runtime, player, event, null);
    }

    public FlowContext(FlowRuntime runtime, Player player, Event event, Consumer<String> deferredOutputConsumer) {
        this.runtime = runtime;
        this.player = player;
        this.event = event;
        this.deferredOutputConsumer = deferredOutputConsumer;
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

    public void triggerOutput(String pinName) {
        if (pinName == null || pinName.isBlank()) {
            return;
        }

        // Keep legacy runtime pin behavior for synchronous capture and legacy contexts.
        // For deferred outputs (after synchronous capture with a callback), route via callback
        // only to avoid stale runtime pins leaking into subsequent node executions.
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

    public Map<String, CompletableFuture<Void>> getAsyncOperations() {
        return asyncOperations;
    }
}
