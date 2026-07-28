package restudio.resync.flow.handler.generic;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.entity.Entity;
import org.bukkit.scheduler.BukkitTask;
import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowDataType;
import restudio.flow.data.FlowNode;
import restudio.flow.data.FlowOperationResult;
import restudio.flow.data.FlowTypeRef;
import restudio.resync.Log;
import restudio.resync.ReSync;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.FlowExecutor;
import restudio.resync.flow.FlowStorage;
import restudio.resync.flow.FlowValueCodecRegistry;
import restudio.resync.flow.automation.AutomationDefinitionRegistry;
import restudio.resync.flow.automation.AutomationInstanceKey;
import restudio.resync.flow.automation.AutomationOwner;
import restudio.resync.flow.automation.AutomationReferences;
import restudio.resync.flow.automation.AutomationScope;
import restudio.resync.flow.automation.AutomationTaskService;
import restudio.resync.flow.automation.ScheduleDefinition;
import restudio.resync.flow.automation.event.ScheduleFiredEvent;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.NodeHandler;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

public class ScheduleHandler implements NodeHandler {
    private final FlowStorage flowStorage;
    private final Clock clock;
    private final Map<String, BiConsumer<FlowContext, FlowNode>> operations = new HashMap<>();
    private final AutomationDefinitionRegistry definitions;
    private final AutomationTaskService automationTasks;
    private final FlowValueCodecRegistry valueCodecs;

    public ScheduleHandler(FlowStorage flowStorage) {
        this(flowStorage, Clock.systemUTC(), null, null, null);
    }

    public ScheduleHandler(FlowStorage flowStorage, Clock clock) {
        this(flowStorage, clock, null, null, null);
    }

    public ScheduleHandler(FlowStorage flowStorage, Clock clock, AutomationDefinitionRegistry definitions,
                           AutomationTaskService automationTasks, FlowValueCodecRegistry valueCodecs) {
        this.flowStorage = flowStorage;
        this.clock = clock != null ? clock : Clock.systemUTC();
        this.definitions = definitions;
        this.automationTasks = automationTasks;
        this.valueCodecs = valueCodecs;

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

        if (definitions != null && automationTasks != null) {
            operations.put("schedule_definition", this::scheduleDefinition);
            operations.put("scheduled_task", this::controlScheduledTask);
        }
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
        if (!"scheduled_task".equals(operation)) {
            String successOutput = "schedule_definition".equals(operation) ? "scheduled" : "flow";
            ctx.triggerOutput(Boolean.TRUE.equals(success) ? successOutput : "failed");
        } else if (!Boolean.TRUE.equals(success)) {
            ctx.setOutput(node, "state", "inactive");
            ctx.triggerOutput("inactive");
        }
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

    private void scheduleDefinition(FlowContext context, FlowNode node) {
        String definitionId = AutomationReferences.id(context.getInputValue(node, "schedule", Object.class, null));
        ScheduleDefinition definition = definitions.schedule(definitionId);
        Object ownerValue = automationOwnerValue(context, node, definition);
        AutomationOwner owner = AutomationOwner.resolve(definition.scope(), context, ownerValue);
        FlowGraph target = flowStorage != null ? flowStorage.getGraph(definition.targetId()) : null;
        if (target == null) {
            throw new IllegalArgumentException("Schedule target not found: " + definition.targetId());
        }
        if (definition.targetType() == ScheduleDefinition.TargetType.FUNCTION && !target.isFunction()) {
            throw new IllegalArgumentException("Schedule target is not a Function: " + definition.targetId());
        }
        Map<String, Object> arguments = definition.targetType() == ScheduleDefinition.TargetType.FUNCTION
            ? captureFunctionArguments(context, node, target, definition.persistent()) : captureArgumentsMap(context, node, definition.persistent());
        int signatureVersion = target.getFunctionVersion();
        UUID scheduledPlayerId = context.getPlayer() != null ? context.getPlayer().getUniqueId() : playerId(owner, definition);
        FlowExecutor executor = requireExecutor(context);
        Supplier<CompletableFuture<Object>> invocation = scheduleInvocation(definition, owner, arguments, signatureVersion, scheduledPlayerId, executor);
        Timing timing = timing(definition);
        AutomationTaskService.StartResult result = automationTasks.startSchedule(new AutomationTaskService.ScheduleRequest(
            definition, owner, timing.firstDelay(), timing.interval(), timing.nextDelay(), invocation, arguments, signatureVersion));
        Map<String, Object> task = result.task().value();
        context.setOutput(node, "schedule", definitions.reference(definition));
        context.setOutput(node, "task", task);
        context.setOutput(node, "scheduled", result.started() || result.keptExisting());
        setResult(context, node, FlowOperationResult.success(task));
    }

    public void restorePersistentSchedules(FlowExecutor executor) {
        automationTasks.restorePersistentSchedules(state -> {
            ScheduleDefinition definition = definitions.schedule(state.definitionId());
            AutomationOwner owner = new AutomationOwner(state.ownerId(), state.ownerId());
            FlowGraph target = flowStorage.getGraph(definition.targetId());
            if (target == null) {
                throw new IllegalArgumentException("Schedule target not found: " + definition.targetId());
            }
            Timing timing;
            try {
                timing = timing(definition);
            } catch (IllegalArgumentException failure) {
                if (state.nextRun() > clock.millis()) {
                    throw failure;
                }
                timing = new Timing(0L, 0L, null);
            }
            UUID playerId = playerId(owner, definition);
            Supplier<CompletableFuture<Object>> invocation = scheduleInvocation(
                definition, owner, state.arguments(), state.signatureVersion(), playerId, executor);
            return new AutomationTaskService.ScheduleRequest(definition, owner, timing.firstDelay(), timing.interval(),
                timing.nextDelay(), invocation, state.arguments(), state.signatureVersion());
        });
    }

    private Supplier<CompletableFuture<Object>> scheduleInvocation(ScheduleDefinition definition, AutomationOwner owner,
                                                                   Map<String, Object> arguments, int signatureVersion,
                                                                   UUID scheduledPlayerId, FlowExecutor executor) {
        AutomationInstanceKey key = new AutomationInstanceKey(definition.id(), definition.scope(), owner.id());
        return () -> onServerThread(() -> {
            FlowGraph currentTarget = flowStorage.getGraph(definition.targetId());
            if (currentTarget == null) {
                return CompletableFuture.failedFuture(new IllegalStateException("Schedule target not found: " + definition.targetId()));
            }
            if (definition.targetType() == ScheduleDefinition.TargetType.FUNCTION && currentTarget.getFunctionVersion() != signatureVersion) {
                return CompletableFuture.failedFuture(new IllegalStateException(
                    "Function signature changed from version " + signatureVersion + " to " + currentTarget.getFunctionVersion()
                        + "; open the Schedule node and reconnect its arguments"));
            }
            Player player = scheduledPlayerId != null ? Bukkit.getPlayer(scheduledPlayerId) : null;
            if (definition.scope() == AutomationScope.PLAYER && scheduledPlayerId != null && player == null) {
                if (definition.offlinePolicy() == ScheduleDefinition.OfflinePolicy.CANCEL) {
                    automationTasks.cancel(key);
                }
                if (definition.offlinePolicy() == ScheduleDefinition.OfflinePolicy.WAIT) {
                    return CompletableFuture.completedFuture(AutomationTaskService.waitForOwner());
                }
                if (definition.offlinePolicy() != ScheduleDefinition.OfflinePolicy.RUN_WITHOUT_PLAYER) {
                    return CompletableFuture.completedFuture(null);
                }
            }
            AutomationTaskService.TaskSnapshot task = automationTasks.check(key);
            Map<String, Object> scheduleContext = new LinkedHashMap<>();
            scheduleContext.put("schedule.task", task.value());
            scheduleContext.put("schedule.definition", definitions.reference(definition));
            scheduleContext.put("schedule.fired_at", clock.millis());
            scheduleContext.put("schedule.arguments", arguments);
            if (definition.targetType() == ScheduleDefinition.TargetType.FUNCTION) {
                return executor.executeFunction(currentTarget, player, null, runtimeArguments(currentTarget, arguments), scheduleContext)
                    .thenApply(result -> (Object) result);
            }
            String eventEntry = currentTarget.getNodes().entrySet().stream().filter(entry -> entry.getValue() != null)
                .filter(entry -> "event.schedule".equals(entry.getValue().getType()))
                .filter(entry -> definition.id().equals(AutomationReferences.id(
                    entry.getValue().getInputValues() != null ? entry.getValue().getInputValues().get("schedule") : null)))
                .map(Map.Entry::getKey).findFirst().orElse(null);
            if (eventEntry == null) {
                if (!definition.id().startsWith("migrated.schedule.")) {
                    return CompletableFuture.failedFuture(new IllegalStateException(
                        "Target Flow requires a Schedule Event selecting " + definition.name()));
                }
                return executor.execute(currentTarget, player, null, scheduleContext).thenApply(ignored -> (Object) Map.of());
            }
            Map<String, Object> taskValue = task.value();
            ScheduleFiredEvent event = new ScheduleFiredEvent(definitions.reference(definition), taskValue, owner.value(), arguments);
            Map<String, Object> eventContext = new LinkedHashMap<>(scheduleContext);
            eventContext.put("event.schedule", definitions.reference(definition));
            eventContext.put("event.task", taskValue);
            eventContext.put("event.owner", owner.value());
            eventContext.put("event.arguments", arguments);
            eventContext.put("event.firedAt", task.lastRun());
            eventContext.put("event.runCount", task.runCount());
            return executor.execute(currentTarget, eventEntry, player, event, eventContext).thenApply(ignored -> (Object) Map.of());
        });
    }

    private CompletableFuture<Object> onServerThread(Supplier<CompletableFuture<Object>> action) {
        if (Bukkit.getServer() == null || Bukkit.isPrimaryThread()) {
            return invokeAction(action);
        }
        CompletableFuture<Object> result = new CompletableFuture<>();
        try {
            Bukkit.getScheduler().runTask(ReSync.getInstance(), () ->
                invokeAction(action).whenComplete((value, failure) -> {
                    if (failure == null) {
                        result.complete(value);
                    } else {
                        result.completeExceptionally(failure);
                    }
                }));
        } catch (RuntimeException failure) {
            result.completeExceptionally(failure);
        }
        return result;
    }

    private CompletableFuture<Object> invokeAction(Supplier<CompletableFuture<Object>> action) {
        try {
            CompletableFuture<Object> result = action.get();
            return result != null ? result : CompletableFuture.completedFuture(null);
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    private Map<String, Object> runtimeArguments(FlowGraph target, Map<String, Object> captured) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (FlowGraph.FunctionParameter parameter : target.getFunctionInputs()) {
            Object value = captured.get(parameter.getName());
            values.put(parameter.getName(), valueCodecs != null && valueCodecs.hasCodec(parameter.getTypeRef())
                ? valueCodecs.decode(parameter.getTypeRef(), value) : value);
        }
        return values;
    }

    private UUID playerId(AutomationOwner owner, ScheduleDefinition definition) {
        if (definition.scope() != AutomationScope.PLAYER) {
            return null;
        }
        try {
            return UUID.fromString(owner.id());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private void controlScheduledTask(FlowContext context, FlowNode node) {
        String action = context.getInputValue(node, "action", String.class, "check").trim().toLowerCase(Locale.ROOT);
        AutomationTaskService.TaskSnapshot selected = selectedTask(context, node);
        AutomationInstanceKey key = new AutomationInstanceKey(selected.definitionId(), selected.scope(), selected.ownerId());
        AutomationTaskService.TaskSnapshot result = switch (action) {
            case "check" -> automationTasks.check(key);
            case "cancel" -> automationTasks.cancel(key);
            case "pause" -> automationTasks.pause(key);
            case "resume" -> automationTasks.resume(key);
            case "run now", "run_now" -> {
                automationTasks.runNow(key);
                yield automationTasks.check(key);
            }
            default -> throw new IllegalArgumentException("Unknown Scheduled Task action: " + action);
        };
        Map<String, Object> value = result.value();
        context.setOutput(node, "task", value);
        context.setOutput(node, "state", value.get("state"));
        context.setOutput(node, "remaining", value.get("remaining"));
        context.setOutput(node, "next_run", value.get("nextRun"));
        context.setOutput(node, "last_run", value.get("lastRun"));
        context.setOutput(node, "run_count", value.get("runCount"));
        context.setOutput(node, "last_result", value.get("lastResult"));
        context.setOutput(node, "last_error", value.get("lastError"));
        setResult(context, node, FlowOperationResult.success(value));
        context.triggerOutput(switch (result.state()) {
            case ACTIVE -> "active";
            case PAUSED -> "paused";
            default -> "inactive";
        });
    }

    private AutomationTaskService.TaskSnapshot selectedTask(FlowContext context, FlowNode node) {
        Object taskValue = context.getInputValue(node, "task", Object.class, null);
        if (taskValue == null) {
            taskValue = context.getRuntime().getEventVariables().get("schedule.task");
        }
        if (taskValue instanceof Map<?, ?> map && map.get("taskId") != null) {
            AutomationTaskService.TaskSnapshot task = automationTasks.task(map.get("taskId").toString());
            if (task != null) {
                return task;
            }
        }
        String definitionId = AutomationReferences.id(context.getInputValue(node, "schedule", Object.class, null));
        ScheduleDefinition definition = definitions.schedule(definitionId);
        AutomationOwner owner = AutomationOwner.resolve(definition.scope(), context, automationOwnerValue(context, node, definition));
        return automationTasks.check(new AutomationInstanceKey(definition.id(), definition.scope(), owner.id()));
    }

    private Object automationOwnerValue(FlowContext context, FlowNode node, ScheduleDefinition definition) {
        return switch (definition.scope()) {
            case PLAYER -> context.getInputValue(node, "owner", Object.class,
                context.getInputValue(node, "player", Object.class, context.getPlayer()));
            case ENTITY -> context.getInputValue(node, "owner", Object.class,
                context.getInputValue(node, "entity", Object.class, null));
            case NETWORK -> context.getInputValue(node, "owner", Object.class,
                context.getInputValue(node, "network", Object.class, null));
            default -> null;
        };
    }

    private Map<String, Object> captureFunctionArguments(FlowContext context, FlowNode node, FlowGraph target, boolean persistent) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        Map<String, Object> fallback = captureArgumentsMap(context, node, persistent);
        for (FlowGraph.FunctionParameter parameter : target.getFunctionInputs()) {
            if (parameter == null || parameter.getName() == null || parameter.getName().isBlank()) {
                continue;
            }
            Object value = context.getInputValue(node, parameter.getName(), Object.class, fallback.get(parameter.getName()));
            if (value == null && parameter.getDefaultValue() != null && !parameter.getDefaultValue().isBlank()) {
                value = parameter.getDefaultValue();
            }
            if (value == null) {
                throw new IllegalArgumentException("Function argument is required: " + parameter.getName());
            }
            arguments.put(parameter.getName(), captureValue(context, parameter.getTypeRef(), value, persistent));
        }
        return Collections.unmodifiableMap(arguments);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> captureArgumentsMap(FlowContext context, FlowNode node, boolean persistent) {
        Object value = context.getInputValue(node, "arguments", Object.class, Map.of());
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> arguments = new LinkedHashMap<>();
        map.forEach((key, entry) -> {
            if (key != null) {
                arguments.put(key.toString(), stableValue(entry, persistent));
            }
        });
        return Collections.unmodifiableMap(arguments);
    }

    private Object captureValue(FlowContext context, FlowTypeRef type, Object value, boolean persistent) {
        if (value == null) {
            return null;
        }
        FlowDataType dataType = FlowDataType.fromString(type.getTypeId());
        Class<?> javaType = dataType.getJavaType();
        if (javaType != null && !Object.class.equals(javaType) && !javaType.isInstance(value)) {
            Object adapted = context.getTypeAdapter().adapt(value, javaType);
            if (adapted == null || !javaType.isInstance(adapted)) {
                throw new IllegalArgumentException("Function argument must be " + type);
            }
            value = adapted;
        }
        if (valueCodecs != null && valueCodecs.hasCodec(type)) {
            Object encoded = valueCodecs.encode(type, value);
            return persistent ? encoded : valueCodecs.decode(type, encoded);
        }
        if (persistent) {
            throw new IllegalArgumentException("Persistent Schedule argument type is unsupported: " + type);
        }
        return stableValue(value, false);
    }

    private Object stableValue(Object value, boolean persistent) {
        return switch (value) {
            case Map<?, ?> map -> {
                Map<String, Object> copy = new LinkedHashMap<>();
                map.forEach((key, entry) -> copy.put(String.valueOf(key), stableValue(entry, persistent)));
                yield Collections.unmodifiableMap(copy);
            }
            case List<?> list -> Collections.unmodifiableList(new ArrayList<>(list.stream()
                .map(entry -> stableValue(entry, persistent)).toList()));
            case Entity entity when persistent -> entity.getUniqueId().toString();
            case UUID uuid when persistent -> uuid.toString();
            case Enum<?> enumeration when persistent -> enumeration.name();
            case String text -> text;
            case Number number -> number;
            case Boolean bool -> bool;
            case null -> null;
            case Object object when persistent -> throw new IllegalArgumentException(
                "Persistent Schedule value is unsupported: " + object.getClass().getSimpleName());
            default -> value;
        };
    }

    private Timing timing(ScheduleDefinition definition) {
        return switch (definition.timingMode()) {
            case AFTER_DELAY -> new Timing(definition.unit().toMillis(definition.duration()), 0L, null);
            case REPEATING -> {
                long interval = definition.unit().toMillis(definition.duration());
                if (interval <= 0L) {
                    throw new IllegalArgumentException("Repeating Schedule interval must be positive");
                }
                long firstDelay = definition.initialDelay() > 0D ? definition.unit().toMillis(definition.initialDelay()) : interval;
                yield new Timing(firstDelay, interval, () -> interval);
            }
            case AT_TIME -> {
                SchedulePattern pattern = SchedulePattern.once(definition.dateTime(), resolveZone(definition.timeZone()));
                long delay = pattern.delayMillisFrom(clock.instant(), pattern.nextAfter(clock.instant())
                    .orElseThrow(() -> new IllegalArgumentException("Scheduled time must be in the future")));
                yield new Timing(delay, 0L, null);
            }
            case CRON -> {
                SchedulePattern pattern = SchedulePattern.cron(definition.cron(), resolveZone(definition.timeZone()));
                LongSupplier next = () -> {
                    Instant now = clock.instant();
                    return pattern.delayMillisFrom(now, pattern.nextAfter(now)
                        .orElseThrow(() -> new IllegalArgumentException("Cron Schedule has no next occurrence")));
                };
                yield new Timing(next.getAsLong(), 0L, next);
            }
        };
    }

    private record Timing(long firstDelay, long interval, LongSupplier nextDelay) {
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
