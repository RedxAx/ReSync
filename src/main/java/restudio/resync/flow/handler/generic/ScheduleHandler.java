package restudio.resync.flow.handler.generic;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowNode;
import restudio.flow.data.FlowOperationResult;
import restudio.resync.Log;
import restudio.resync.ReSync;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.FlowExecutor;
import restudio.resync.flow.FlowStorage;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.NodeHandler;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;

public class ScheduleHandler implements NodeHandler {
    private final FlowStorage flowStorage;
    private final Clock clock;
    private final Map<String, BiConsumer<FlowContext, FlowNode>> operations = new HashMap<>();

    public ScheduleHandler(FlowStorage flowStorage) {
        this(flowStorage, Clock.systemUTC());
    }

    public ScheduleHandler(FlowStorage flowStorage, Clock clock) {
        this.flowStorage = flowStorage;
        this.clock = clock != null ? clock : Clock.systemUTC();

        operations.put("delay", (ctx, node) -> {
            Number seconds = ctx.getInputValue(node, "seconds", Number.class, 1L);
            waitWallClockBeforeContinuation(ctx, node, delayMillis(seconds));
            setResult(ctx, node, FlowOperationResult.success(true));
        });

        operations.put("wait_ticks", (ctx, node) -> {
            long ticks = ctx.getInputValue(node, "ticks", Long.class, 20L);
            if (ticks < 0L) throw new IllegalArgumentException("Wait ticks must be non-negative");
            waitBeforeContinuation(ctx, node, ticks);
            setResult(ctx, node, FlowOperationResult.success(true));
        });

        operations.put("schedule", (ctx, node) -> {
            ZoneId zoneId = resolveZone(ctx.getInputValue(node, "time_zone", String.class, ""));
            String time = ctx.getInputValue(node, "time_string", String.class, "12:00");
            schedulePattern(ctx, node, SchedulePattern.daily(time, zoneId));
        });

        operations.put("cron", (ctx, node) -> {
            ZoneId zoneId = resolveZone(ctx.getInputValue(node, "time_zone", String.class, ""));
            String expression = ctx.getInputValue(node, "expression", String.class, "0 12 * * *");
            schedulePattern(ctx, node, SchedulePattern.cron(expression, zoneId));
        });

        operations.put("schedule_at_time", (ctx, node) -> {
            ZoneId zoneId = resolveZone(ctx.getInputValue(node, "time_zone", String.class, ""));
            String time = ctx.getInputValue(node, "time", String.class, "");
            schedulePattern(ctx, node, SchedulePattern.once(time, zoneId));
        });

        operations.put("schedule_repeating", (ctx, node) -> {
            long intervalTicks = ctx.getInputValue(node, "interval_ticks", Long.class, 1200L);
            scheduleRepeating(ctx, node, requirePositiveInterval(intervalTicks));
        });

        operations.put("interval", (ctx, node) -> {
            long seconds = ctx.getInputValue(node, "seconds", Long.class, 1L);
            long intervalTicks = Math.multiplyExact(requirePositiveInterval(seconds), 20L);
            scheduleRepeating(ctx, node, intervalTicks);
        });

        operations.put("cancel_task", (ctx, node) -> {
            FlowExecutor executor = requireExecutor(ctx);
            String taskId = ctx.getInputValue(node, "task_id", String.class, "");
            if (taskId == null || taskId.isBlank()) {
                throw new IllegalArgumentException("Scheduled task ID is required");
            }
            FlowExecutor.TaskCancellationStatus status = executor.cancelPendingTaskWithStatus(taskId);
            boolean cancelled = status == FlowExecutor.TaskCancellationStatus.CANCELLED || status == FlowExecutor.TaskCancellationStatus.ALREADY_CANCELLED;
            Map<String, Object> task = taskValue(executor.getScheduledTaskSnapshot(taskId));
            ctx.setOutput(node, "cancelled", cancelled);
            ctx.setOutput(node, "status", status.name().toLowerCase(Locale.ROOT));
            ctx.setOutput(node, "task", task);
            FlowOperationResult<Map<String, Object>> result = switch (status) {
                case CANCELLED, ALREADY_CANCELLED -> FlowOperationResult.success(task);
                case FINISHED -> FlowOperationResult.failure("SCHEDULE_ALREADY_FINISHED", "Scheduled task already finished: " + taskId, Map.of("taskId", taskId));
                case UNKNOWN -> FlowOperationResult.failure("SCHEDULE_TASK_NOT_FOUND", "Scheduled task not found: " + taskId, Map.of("taskId", taskId));
            };
            setResult(ctx, node, result);
        });

        operations.put("get_task", (ctx, node) -> {
            FlowExecutor executor = requireExecutor(ctx);
            String taskId = ctx.getInputValue(node, "task_id", String.class, "");
            if (taskId == null || taskId.isBlank()) {
                throw new IllegalArgumentException("Scheduled task ID is required");
            }
            FlowExecutor.ScheduledTaskSnapshot snapshot = executor.getScheduledTaskSnapshot(taskId);
            Map<String, Object> task = taskValue(snapshot);
            ctx.setOutput(node, "task", task);
            setResult(ctx, node, snapshot != null ? FlowOperationResult.success(task)
                : FlowOperationResult.failure("SCHEDULE_TASK_NOT_FOUND", "Scheduled task not found: " + taskId, Map.of("taskId", safe(taskId))));
        });

        operations.put("list_tasks", (ctx, node) -> {
            FlowExecutor executor = requireExecutor(ctx);
            List<Map<String, Object>> tasks = executor.getScheduledTaskSnapshots().stream().map(this::taskValue).toList();
            ctx.setOutput(node, "tasks", tasks);
            setResult(ctx, node, FlowOperationResult.success(tasks));
        });
    }

    public void registerTo(HandlerRegistry registry) {
        registry.register("ScheduleHandler", this);
    }

    @Override
    public void execute(FlowContext ctx, FlowNode node) {
        String operation = node.getHandlerConfig().getString("operation");
        BiConsumer<FlowContext, FlowNode> handler = operation != null ? operations.get(operation) : null;
        if (handler == null) {
            throw new IllegalArgumentException("Unknown schedule operation: " + operation);
        }
        try {
            handler.accept(ctx, node);
        } catch (RuntimeException exception) {
            FlowOperationResult<Object> failure = FlowOperationResult.failure(scheduleErrorCode(exception), failureMessage(exception), Map.of("operation", operation));
            setResult(ctx, node, failure);
        }
        Object success = ctx.getOutput(node, "success");
        ctx.triggerOutput(Boolean.TRUE.equals(success) ? "flow" : "failed");
    }

    @Override
    public Set<String> getSupportedOperations() {
        return Set.copyOf(operations.keySet());
    }

    private void setResult(FlowContext context, FlowNode node, FlowOperationResult<?> result) {
        context.setOutput(node, "result", result);
        context.setOutput(node, "success", result.success());
        context.setOutput(node, "error_code", result.errorCode());
        context.setOutput(node, "message", result.message());
        if (!result.success()) {
            context.setOutput(node, "scheduled", false);
            context.setOutput(node, "completed", false);
        }
    }

    private void waitBeforeContinuation(FlowContext context, FlowNode node, long ticks) {
        context.runLaterBeforeContinuation(() -> context.setOutput(node, "completed", true), ticks);
    }

    private void waitWallClockBeforeContinuation(FlowContext context, FlowNode node, long delayMillis) {
        context.runAfterMillisBeforeContinuation(() -> context.setOutput(node, "completed", true), delayMillis);
    }

    long delayMillis(Number seconds) {
        double value = seconds != null ? seconds.doubleValue() : 1D;
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("Delay seconds must be finite");
        }
        if (value < 0D) throw new IllegalArgumentException("Delay seconds must be non-negative");
        double milliseconds = value * 1000D;
        if (milliseconds > Long.MAX_VALUE) {
            throw new ArithmeticException("Delay duration overflow");
        }
        return (long) Math.ceil(milliseconds);
    }

    private void schedulePattern(FlowContext context, FlowNode node, SchedulePattern pattern) {
        FlowExecutor executor = requireExecutor(context);
        String flowId = requireFlowId(context, node);
        String taskId = newTaskId();
        long createdAt = clock.millis();
        UUID playerId = context.getPlayer() != null ? context.getPlayer().getUniqueId() : null;
        ScheduleExecutionGate running = new ScheduleExecutionGate();
        scheduleNextOccurrence(executor, pattern, flowId, taskId, playerId, running, createdAt, clock.instant());
        context.setOutput(node, "task_id", taskId);
        context.setOutput(node, "scheduled", true);
        Map<String, Object> task = taskValue(executor.getScheduledTaskSnapshot(taskId));
        context.setOutput(node, "task", task);
        setResult(context, node, FlowOperationResult.success(task));
    }

    private void scheduleNextOccurrence(FlowExecutor executor, SchedulePattern pattern, String flowId, String taskId,
                                        UUID playerId, ScheduleExecutionGate running, long createdAt, Instant cursor) {
        Instant target = pattern.nextAfter(cursor)
            .orElseThrow(() -> new IllegalArgumentException("Scheduled time must be in the future"));
        long delayMillis = pattern.delayMillisFrom(clock.instant(), target);
        CompletableFuture<Void> timerCompletion = new CompletableFuture<>();
        executor.scheduleWallClockTask(taskId, flowId, "schedule", delayMillis, createdAt, target.toEpochMilli(), pattern.isRecurring(), () -> {
            long firedAt = clock.millis();
            Map<String, Object> firingTask = taskValue(executor.getScheduledTaskSnapshot(taskId));
            if (pattern.isRecurring()) {
                scheduleNextOccurrence(executor, pattern, flowId, taskId, playerId, running, createdAt, clock.instant());
            }
            return executeScheduledFlow(executor, flowId, taskId, playerId, running, firingTask, firedAt);
        }, timerCompletion);
    }

    private void scheduleRepeating(FlowContext context, FlowNode node, long intervalTicks) {
        FlowExecutor executor = requireExecutor(context);
        String flowId = requireFlowId(context, node);
        String taskId = newTaskId();
        long createdAt = clock.millis();
        UUID playerId = context.getPlayer() != null ? context.getPlayer().getUniqueId() : null;
        ScheduleExecutionGate running = new ScheduleExecutionGate();
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(ReSync.getInstance(),
            () -> {
                long firedAt = clock.millis();
                Map<String, Object> firingTask = taskValue(executor.getScheduledTaskSnapshot(taskId));
                executor.updateScheduledTaskNextFireAt(taskId, Math.addExact(firedAt, Math.multiplyExact(intervalTicks, 50L)));
                executeScheduledFlow(executor, flowId, taskId, playerId, running, firingTask, firedAt);
            }, intervalTicks, intervalTicks);
        long nextFireAt = Math.addExact(createdAt, Math.multiplyExact(intervalTicks, 50L));
        executor.registerPendingTask(taskId, flowId, "schedule", task, null, createdAt, nextFireAt, true);
        context.setOutput(node, "task_id", taskId);
        context.setOutput(node, "scheduled", true);
        Map<String, Object> snapshotValue = taskValue(executor.getScheduledTaskSnapshot(taskId));
        context.setOutput(node, "task", snapshotValue);
        setResult(context, node, FlowOperationResult.success(snapshotValue));
    }

    private CompletableFuture<Void> executeScheduledFlow(FlowExecutor executor, String flowId, String taskId, UUID playerId, ScheduleExecutionGate running,
                                                         Map<String, Object> firingTask, long firedAt) {
        if (!running.tryBegin()) {
            Log.fine("[Flow:Schedule] Skipped overlapping execution for task: " + taskId);
            return CompletableFuture.completedFuture(null);
        }
        FlowGraph graph = flowStorage != null ? flowStorage.getGraph(flowId) : null;
        if (graph == null) {
            running.complete();
            executor.recordScheduledTaskFailure(taskId, new IllegalStateException("Flow not found: " + flowId));
            executor.cancelPendingTask(taskId);
            Log.warn("[Flow:Schedule] Flow not found: " + flowId);
            return CompletableFuture.completedFuture(null);
        }
        Player player = playerId != null ? Bukkit.getPlayer(playerId) : null;
        Map<String, Object> variables = Map.of(
            "schedule.task", firingTask != null ? firingTask : Map.of(),
            "schedule.task_id", taskId,
            "schedule.flow_id", flowId,
            "schedule.fired_at", firedAt
        );
        return executor.execute(graph, player, null, variables).whenComplete((result, failure) -> {
            running.complete();
            if (failure != null) {
                executor.recordScheduledTaskFailure(taskId, failure);
                Log.warn("[Flow:Schedule] Scheduled flow failed: " + flowId + " - " + failure.getMessage(), failure);
            }
        });
    }

    private FlowExecutor requireExecutor(FlowContext context) {
        FlowExecutor executor = context.getExecutor();
        if (executor == null) {
            throw new IllegalStateException("Flow executor is unavailable");
        }
        return executor;
    }

    private String requireFlowId(FlowContext context, FlowNode node) {
        String flowId = context.getInputValue(node, "flow_id", String.class, "");
        if (flowId == null || flowId.isBlank()) {
            throw new IllegalArgumentException("Flow ID is required");
        }
        if (flowStorage == null || flowStorage.getGraph(flowId) == null) {
            throw new IllegalArgumentException("Flow not found: " + flowId);
        }
        return flowId;
    }

    private ZoneId resolveZone(String value) {
        if (value == null || value.isBlank()) {
            return ZoneId.of("UTC");
        }
        try {
            return ZoneId.of(value.trim());
        } catch (Exception exception) {
            throw new IllegalArgumentException("Unknown time zone: " + value, exception);
        }
    }

    private long requirePositiveInterval(long value) {
        if (value <= 0L) {
            throw new IllegalArgumentException("Schedule interval must be positive");
        }
        return value;
    }

    private String newTaskId() {
        return "schedule_" + UUID.randomUUID();
    }

    private Map<String, Object> taskValue(FlowExecutor.ScheduledTaskSnapshot snapshot) {
        if (snapshot == null) {
            return Map.of();
        }
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("taskId", snapshot.taskId());
        value.put("runtimeOwner", snapshot.runtimeOwner());
        value.put("flowId", snapshot.graphId());
        value.put("createdAt", snapshot.createdAt());
        value.put("nextFireAt", snapshot.nextFireAt());
        value.put("recurring", snapshot.recurring());
        value.put("state", snapshot.state().name().toLowerCase(Locale.ROOT));
        value.put("lastFailure", snapshot.lastFailure() != null ? snapshot.lastFailure() : "");
        return Map.copyOf(value);
    }

    private String scheduleErrorCode(RuntimeException exception) {
        return switch (exception) {
            case ArithmeticException ignored -> "SCHEDULE_OVERFLOW";
            case IllegalArgumentException ignored -> "SCHEDULE_INPUT_INVALID";
            case IllegalStateException ignored -> "SCHEDULE_RUNTIME_UNAVAILABLE";
            default -> "SCHEDULE_OPERATION_FAILED";
        };
    }

    private String failureMessage(RuntimeException exception) {
        return exception.getMessage() != null && !exception.getMessage().isBlank() ? exception.getMessage() : "Schedule operation failed";
    }

    private String safe(String value) {
        return value != null ? value : "";
    }
}
