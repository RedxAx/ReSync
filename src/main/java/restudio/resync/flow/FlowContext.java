package restudio.resync.flow;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.scheduler.BukkitTask;
import restudio.flow.data.FlowNode;
import restudio.resync.ReSync;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

public class FlowContext {
    private final Player player;
    private final Event event;
    private final FlowRuntime runtime;
    private final Map<String, CompletableFuture<Void>> asyncOperations = new HashMap<>();

    public FlowContext(FlowRuntime runtime, Player player, Event event) {
        this.runtime = runtime;
        this.player = player;
        this.event = event;
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
        Object value = runtime.resolveInput(node, pinName, type);
        if (value == null && type == Player.class && player != null) {
            return type.cast(player);
        }
        return type.cast(value);
    }

    public <T> T getInputValue(FlowNode node, String pinName, Class<T> type, T defaultValue) {
        Object value = runtime.resolveInput(node, pinName, type);
        if (value == null && type == Player.class && player != null) {
            return type.cast(player);
        }
        if (value == null) return defaultValue;
        return type.cast(value);
    }

    public void triggerOutput(String pinName) {
        runtime.triggerOutput(pinName);
    }

    public void setNodeOutput(String nodeId, String pinName, Object value) {
        runtime.setNodeOutput(nodeId, pinName, value);
    }

    public TypeAdapterRegistry getTypeAdapter() {
        return runtime.getTypeAdapter();
    }

    public FlowRuntime getRuntime() {
        return runtime;
    }

    public CompletableFuture<Void> runAsync(Runnable runnable) {
        CompletableFuture<Void> future = new CompletableFuture<>();
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
        BukkitTask task = Bukkit.getScheduler().runTaskLater(ReSync.getInstance(), () -> {
            try {
                runnable.run();
                future.complete(null);
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        }, delayTicks);
        
        String taskId = "task_" + System.nanoTime();
        asyncOperations.put(taskId, future);
        future.whenComplete((v, e) -> asyncOperations.remove(taskId));
        
        return future;
    }

    public Map<String, CompletableFuture<Void>> getAsyncOperations() {
        return asyncOperations;
    }
}
