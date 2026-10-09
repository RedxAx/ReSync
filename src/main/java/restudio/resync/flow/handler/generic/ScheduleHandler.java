package restudio.resync.flow.handler.generic;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.entity.Entity;
import org.bukkit.scheduler.BukkitTask;
import restudio.flow.data.FlowResourceReference;
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
import restudio.resync.flow.FunctionCallSupport;
import restudio.resync.flow.identity.CorrelationId;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.FunctionParameterId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.runtime.RuntimeExecutionContext;
import restudio.resync.flow.runtime.RuntimePrincipal;
import restudio.resync.flow.runtime.RuntimePrincipalAuthority;
import restudio.resync.flow.automation.AutomationDefinitionRegistry;
import restudio.resync.flow.automation.AutomationDefinition;
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
    private final RuntimePrincipalAuthority principalAuthority;
    private final ServerId serverId;
    private static final OwnerId CORE_GRAPH_OWNER = OwnerId.of("restudio.resync");
    private static final Set<String> GRAPH_TYPES = Set.of("flow", "function", "command");
    private volatile RuntimePrincipal persistentSchedulePrincipal;

    public ScheduleHandler(FlowStorage flowStorage) {
        this(flowStorage, Clock.systemUTC(), null, null, null);
    }

    public ScheduleHandler(FlowStorage flowStorage, Clock clock) {
        this(flowStorage, clock, null, null, null);
    }

    public ScheduleHandler(FlowStorage flowStorage, Clock clock, AutomationDefinitionRegistry definitions,
                           AutomationTaskService automationTasks, FlowValueCodecRegistry valueCodecs) {
        this(flowStorage, clock, definitions, automationTasks, valueCodecs, null, null);
    }

    public ScheduleHandler(FlowStorage flowStorage, Clock clock, AutomationDefinitionRegistry definitions,
                           AutomationTaskService automationTasks, FlowValueCodecRegistry valueCodecs,
                           RuntimePrincipalAuthority principalAuthority, ServerId serverId) {
        this.flowStorage = flowStorage;
        this.clock = clock != null ? clock : Clock.systemUTC();
        this.definitions = definitions;
        this.automationTasks = automationTasks;
        this.valueCodecs = valueCodecs;
        this.principalAuthority = principalAuthority;
        this.serverId = serverId;

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
        ServerResourceLocator flowTarget = requireFlowTarget(context, node);
        String taskId = newTaskId();
        long createdAt = clock.millis();
        UUID playerId = context.getPlayer() != null ? context.getPlayer().getUniqueId() : null;
        ScheduleExecutionGate running = new ScheduleExecutionGate();
        scheduleNextOccurrence(executor, pattern, flowTarget, taskId, playerId, running, createdAt, clock.instant());
        context.setOutput(node, "task_id", taskId);
        context.setOutput(node, "scheduled", true);
        Map<String, Object> task = taskValue(executor.getScheduledTaskSnapshot(taskId));
        context.setOutput(node, "task", task);
        setResult(context, node, FlowOperationResult.success(task));
    }

    private void scheduleNextOccurrence(FlowExecutor executor, SchedulePattern pattern, ServerResourceLocator flowTarget, String taskId,
                                        UUID playerId, ScheduleExecutionGate running, long createdAt, Instant cursor) {
        Instant target = pattern.nextAfter(cursor)
            .orElseThrow(() -> new IllegalArgumentException("Scheduled time must be in the future"));
        long delayMillis = pattern.delayMillisFrom(clock.instant(), target);
        CompletableFuture<Void> timerCompletion = new CompletableFuture<>();
        executor.scheduleWallClockTask(taskId, flowTarget.canonicalText(), "schedule", delayMillis, createdAt, target.toEpochMilli(), pattern.isRecurring(), () -> {
            long firedAt = clock.millis();
            Map<String, Object> firingTask = taskValue(executor.getScheduledTaskSnapshot(taskId));
            if (pattern.isRecurring()) {
                scheduleNextOccurrence(executor, pattern, flowTarget, taskId, playerId, running, createdAt, clock.instant());
            }
            return executeScheduledFlow(executor, flowTarget, taskId, playerId, running, firingTask, firedAt);
        }, timerCompletion);
    }

    private void scheduleRepeating(FlowContext context, FlowNode node, long intervalTicks) {
        FlowExecutor executor = requireExecutor(context);
        ServerResourceLocator flowTarget = requireFlowTarget(context, node);
        String taskId = newTaskId();
        long createdAt = clock.millis();
        UUID playerId = context.getPlayer() != null ? context.getPlayer().getUniqueId() : null;
        ScheduleExecutionGate running = new ScheduleExecutionGate();
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(ReSync.getInstance(),
            () -> {
                long firedAt = clock.millis();
                Map<String, Object> firingTask = taskValue(executor.getScheduledTaskSnapshot(taskId));
                executor.updateScheduledTaskNextFireAt(taskId, Math.addExact(firedAt, Math.multiplyExact(intervalTicks, 50L)));
                executeScheduledFlow(executor, flowTarget, taskId, playerId, running, firingTask, firedAt);
            }, intervalTicks, intervalTicks);
        long nextFireAt = Math.addExact(createdAt, Math.multiplyExact(intervalTicks, 50L));
        executor.registerPendingTask(taskId, flowTarget.canonicalText(), "schedule", task, null, createdAt, nextFireAt, true);
        context.setOutput(node, "task_id", taskId);
        context.setOutput(node, "scheduled", true);
        Map<String, Object> snapshotValue = taskValue(executor.getScheduledTaskSnapshot(taskId));
        context.setOutput(node, "task", snapshotValue);
        setResult(context, node, FlowOperationResult.success(snapshotValue));
    }

    private CompletableFuture<Void> executeScheduledFlow(FlowExecutor executor, ServerResourceLocator flowTarget, String taskId, UUID playerId, ScheduleExecutionGate running,
                                                         Map<String, Object> firingTask, long firedAt) {
        if (!running.tryBegin()) {
            Log.fine("[Flow:Schedule] Skipped overlapping execution for task: " + taskId);
            return CompletableFuture.completedFuture(null);
        }
        FlowGraph graph = targetGraph(flowTarget);
        if (graph == null) {
            running.complete();
            executor.recordScheduledTaskFailure(taskId, new IllegalStateException("Flow not found: " + flowTarget.canonicalText()));
            executor.cancelPendingTask(taskId);
            Log.warn("[Flow:Schedule] Flow not found: " + flowTarget.canonicalText());
            return CompletableFuture.completedFuture(null);
        }
        Player player = playerId != null ? Bukkit.getPlayer(playerId) : null;
        Map<String, Object> variables = Map.of(
            "schedule.task", firingTask != null ? firingTask : Map.of(),
            "schedule.task_id", taskId,
            "schedule.flow_id", flowTarget.id(),
            "schedule.flow_locator", flowTarget,
            "schedule.fired_at", firedAt
        );
        return executor.execute(graph, player, null, variables).whenComplete((result, failure) -> {
            running.complete();
            if (failure != null) {
                executor.recordScheduledTaskFailure(taskId, failure);
                Log.warn("[Flow:Schedule] Scheduled flow failed: " + flowTarget.canonicalText() + " - " + failure.getMessage(), failure);
            }
        });
    }

    private void scheduleDefinition(FlowContext context, FlowNode node) {
        String definitionId = AutomationReferences.id(context.getInputValue(node, "schedule", Object.class, null));
        ScheduleDefinition definition = definitions.schedule(definitionId);
        Object ownerValue = automationOwnerValue(context, node, definition);
        AutomationOwner owner = AutomationOwner.resolve(definition.scope(), context, ownerValue);
        ServerResourceLocator targetLocator = targetLocator(definition);
        FlowGraph target = targetGraph(targetLocator);
        if (target == null) {
            throw new IllegalArgumentException("Schedule target not found: " + targetLocator.canonicalText());
        }
        Map<String, Object> arguments = definition.targetType() == ScheduleDefinition.TargetType.FUNCTION
            ? captureFunctionArguments(context, node, target, definition.persistent()) : captureArgumentsMap(context, node, definition.persistent());
        int signatureVersion = target.getFunctionVersion();
        UUID scheduledPlayerId = context.getPlayer() != null ? context.getPlayer().getUniqueId() : playerId(owner, definition);
        FlowExecutor executor = requireExecutor(context);
        FlowExecutor.FunctionInvocationContext functionContext = schedulingFunctionContext(context, executor);
        if (definition.persistent() && functionContext == null) {
            throw new IllegalStateException("Persistent schedules require a trusted creator principal");
        }
        Supplier<CompletableFuture<Object>> invocation = scheduleInvocation(definition, owner, arguments, signatureVersion,
            scheduledPlayerId, executor, targetLocator, functionContext,
            functionContext != null ? functionContext.principal().canonical() : null,
            functionContext != null ? functionContext.sessionReference() : null);
        Timing timing = timing(definition);
        AutomationTaskService.StartResult result = automationTasks.startSchedule(new AutomationTaskService.ScheduleRequest(
            definition, owner, timing.firstDelay(), timing.interval(), timing.nextDelay(), invocation, arguments, signatureVersion,
            functionContext != null ? functionContext.principal().canonical() : null,
            functionContext != null ? functionContext.sessionReference() : null, targetLocator));
        Map<String, Object> task = result.task().value();
        context.setOutput(node, "output_schedule", definitions.reference(definition));
        context.setOutput(node, "task", task);
        context.setOutput(node, "scheduled", result.started() || result.keptExisting());
        setResult(context, node, FlowOperationResult.success(task));
    }

    public void restorePersistentSchedules(FlowExecutor executor) {
        automationTasks.restorePersistentSchedules(state -> {
            ScheduleDefinition definition = definitions.schedule(state.definitionId());
            AutomationOwner owner = new AutomationOwner(state.ownerId(), state.ownerId());
            ServerResourceLocator targetLocator = restoredTargetLocator(state, definition);
            FlowGraph target = targetGraph(targetLocator);
            if (target == null) {
                throw new IllegalArgumentException("Schedule target not found: " + targetLocator.canonicalText());
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
                definition, owner, state.arguments(), state.signatureVersion(), playerId, executor, targetLocator, null,
                state.creatorPrincipal(), state.creatorSessionReference());
            return new AutomationTaskService.ScheduleRequest(definition, owner, timing.firstDelay(), timing.interval(),
                timing.nextDelay(), invocation, state.arguments(), state.signatureVersion(), state.creatorPrincipal(),
                state.creatorSessionReference(), targetLocator);
        });
    }

    Supplier<CompletableFuture<Object>> scheduleInvocation(ScheduleDefinition definition, AutomationOwner owner,
                                                           Map<String, Object> arguments, int signatureVersion,
                                                           UUID scheduledPlayerId, FlowExecutor executor,
                                                           FlowExecutor.FunctionInvocationContext scheduledFunctionContext,
                                                           String creatorPrincipal, String creatorSessionReference) {
        return scheduleInvocation(definition, owner, arguments, signatureVersion, scheduledPlayerId, executor,
            targetLocator(definition), scheduledFunctionContext, creatorPrincipal, creatorSessionReference);
    }

    Supplier<CompletableFuture<Object>> scheduleInvocation(ScheduleDefinition definition, AutomationOwner owner,
                                                           Map<String, Object> arguments, int signatureVersion,
                                                           UUID scheduledPlayerId, FlowExecutor executor,
                                                           ServerResourceLocator targetLocator,
                                                           FlowExecutor.FunctionInvocationContext scheduledFunctionContext,
                                                           String creatorPrincipal, String creatorSessionReference) {
        AutomationInstanceKey key = new AutomationInstanceKey(definition.kind(), definition.id(), definition.scope(), owner.id());
        return (AutomationTaskService.InvocationSupplier) admitted -> {
            AutomationTaskService.TaskSnapshot task = admitted.task();
            return onServerThread(() -> {
                if (!automationTasks.canInvoke(admitted)) {
                    return CompletableFuture.failedFuture(new IllegalStateException("Schedule Task Is No Longer Active"));
                }
                FlowGraph currentTarget = targetGraph(targetLocator);
                if (currentTarget == null) {
                    return CompletableFuture.failedFuture(new IllegalStateException("Schedule target not found: " + targetLocator.canonicalText()));
                }
                if (definition.targetType() == ScheduleDefinition.TargetType.FUNCTION && currentTarget.getFunctionVersion() != signatureVersion) {
                    return CompletableFuture.failedFuture(new IllegalStateException(
                        "Function signature changed from version " + signatureVersion + " to " + currentTarget.getFunctionVersion()
                            + "; open the Schedule node and reconnect its arguments"));
                }
                Player player = scheduledPlayerId != null ? Bukkit.getPlayer(scheduledPlayerId) : null;
                if (definition.scope() == AutomationScope.PLAYER && scheduledPlayerId != null && player == null) {
                    if (definition.offlinePolicy() == ScheduleDefinition.OfflinePolicy.CANCEL) {
                        automationTasks.cancel(task.taskId());
                    }
                    if (definition.offlinePolicy() == ScheduleDefinition.OfflinePolicy.WAIT) {
                        return CompletableFuture.completedFuture(AutomationTaskService.waitForOwner());
                    }
                    if (definition.offlinePolicy() != ScheduleDefinition.OfflinePolicy.RUN_WITHOUT_PLAYER) {
                        return CompletableFuture.completedFuture(null);
                    }
                }
                Map<String, Object> scheduleContext = new LinkedHashMap<>();
                scheduleContext.put("schedule.task", stableScheduleTask(task));
                scheduleContext.put("schedule.definition", definitions.reference(definition));
                scheduleContext.put("schedule.fired_at", task.nextRun());
                scheduleContext.put("schedule.arguments", arguments);
                if (definition.targetType() == ScheduleDefinition.TargetType.FUNCTION) {
                    FlowExecutor.FunctionInvocationContext invocation = functionInvocationForSchedule(executor, player,
                        scheduleContext, scheduledFunctionContext, key, task.runCount(), definition.persistent(),
                        creatorPrincipal, creatorSessionReference, task.taskId());
                    Map<String, Object> inputs = runtimeArguments(currentTarget, arguments);
                    if (!automationTasks.canInvoke(admitted)) {
                        return CompletableFuture.failedFuture(new IllegalStateException("Schedule Task Is No Longer Active"));
                    }
                    CompletableFuture<Map<String, Object>> function = invocation == null
                        ? executor.executeFunction(currentTarget, player, null, inputs, scheduleContext)
                        : executor.executeFunction(currentTarget, player, null, inputs, scheduleContext,
                            invocation, admitted.cancellation());
                    return function
                        .thenApply(result -> (Object) result);
                }
                if (definition.targetType() == ScheduleDefinition.TargetType.COMMAND) {
                    return invokeAdmitted(admitted,
                        () -> executor.execute(currentTarget, player, null, scheduleContext).thenApply(ignored -> (Object) Map.of()));
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
                    return invokeAdmitted(admitted,
                        () -> executor.execute(currentTarget, player, null, scheduleContext).thenApply(ignored -> (Object) Map.of()));
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
                return invokeAdmitted(admitted,
                    () -> executor.execute(currentTarget, eventEntry, player, event, eventContext).thenApply(ignored -> (Object) Map.of()));
            });
        };
    }

    private CompletableFuture<Object> invokeAdmitted(AutomationTaskService.Invocation admitted,
                                                     Supplier<CompletableFuture<Object>> action) {
        if (!automationTasks.canInvoke(admitted)) {
            return CompletableFuture.failedFuture(new IllegalStateException("Schedule Task Is No Longer Active"));
        }
        return invokeAction(action);
    }

    private ServerResourceLocator targetLocator(ScheduleDefinition definition) {
        if (definition == null) {
            throw new IllegalArgumentException("Schedule definition is required");
        }
        if (serverId == null) {
            throw new IllegalStateException("Schedule target resolution requires an authoritative server ID");
        }
        return validateTargetLocator(definition.targetLocator(serverId));
    }

    private ServerResourceLocator restoredTargetLocator(AutomationTaskService.PersistentTask state,
                                                        ScheduleDefinition definition) {
        ServerResourceLocator expected = targetLocator(definition);
        String persisted = state.targetLocator();
        if (persisted != null && !persisted.isBlank()) {
            ServerResourceLocator locator;
            try {
                locator = ServerResourceLocator.parseCanonicalText(persisted);
            } catch (RuntimeException failure) {
                throw new IllegalArgumentException("Persisted schedule target locator is invalid", failure);
            }
            locator = validateTargetLocator(locator);
            if (!expected.equals(locator)) {
                throw new IllegalArgumentException("Persisted schedule target does not match its definition");
            }
            validateLegacyTargetFields(state, locator);
            return locator;
        }
        if (state.targetType() == null || state.targetType().isBlank()
            || state.targetId() == null || state.targetId().isBlank()) {
            throw new IllegalArgumentException("Persisted schedule has no complete typed target; restore rejected");
        }
        ScheduleDefinition.TargetType legacyType;
        try {
            legacyType = ScheduleDefinition.TargetType.valueOf(state.targetType().trim().replace('-', '_').toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("Persisted schedule target type is invalid; restore rejected", failure);
        }
        ServerResourceLocator migrated = validateTargetLocator(new ServerResourceLocator(serverId,
            ContractRef.of(CORE_GRAPH_OWNER, ResourceTypeId.of(legacyType == ScheduleDefinition.TargetType.FUNCTION
                ? "function" : legacyType == ScheduleDefinition.TargetType.COMMAND ? "command" : "flow")), state.targetId()));
        if (!expected.equals(migrated)) {
            throw new IllegalArgumentException("Persisted legacy schedule target does not match its definition");
        }
        return migrated;
    }

    private void validateLegacyTargetFields(AutomationTaskService.PersistentTask state, ServerResourceLocator locator) {
        if (state.targetType() != null && !state.targetType().isBlank()
            && !state.targetType().equals(locator.resourceType().value())) {
            throw new IllegalArgumentException("Persisted schedule target type conflicts with its locator");
        }
        if (state.targetId() != null && !state.targetId().isBlank() && !state.targetId().equals(locator.id())) {
            throw new IllegalArgumentException("Persisted schedule target ID conflicts with its locator");
        }
    }

    private ServerResourceLocator validateTargetLocator(ServerResourceLocator locator) {
        if (locator == null || serverId == null || !serverId.equals(locator.serverId())
            || !CORE_GRAPH_OWNER.equals(locator.owner()) || !GRAPH_TYPES.contains(locator.resourceType().value())
            || locator.id().isBlank()) {
            throw new IllegalArgumentException("Schedule target locator is not an authoritative Core graph locator");
        }
        return locator;
    }

    private FlowGraph targetGraph(ServerResourceLocator locator) {
        if (flowStorage == null) {
            return null;
        }
        ServerResourceLocator validated = validateTargetLocator(locator);
        FlowGraph graph = flowStorage.getGraph(validated.resourceType().value(), validated.id());
        if (graph == null || !validated.id().equals(graph.getId())
            || !validated.resourceType().value().equals(graph.getResourceType())
            || ("function".equals(validated.resourceType().value()) != graph.isFunction())) {
            return null;
        }
        return graph;
    }

    Map<String, Object> stableScheduleTask(AutomationTaskService.TaskSnapshot task) {
        return Map.of(
            "taskId", task.taskId(),
            "definitionId", task.definitionId(),
            "scope", task.scope().name().toLowerCase(Locale.ROOT),
            "ownerId", task.ownerId(),
            "runCount", task.runCount(),
            "scheduledAt", task.nextRun());
    }

    private FlowExecutor.FunctionInvocationContext schedulingFunctionContext(FlowContext context, FlowExecutor executor) {
        Map<String, Object> variables = new LinkedHashMap<>(context.getRuntime().getEventVariables());
        if (context.getRuntimePrincipal() != null && context.getInvocationId() != null) {
            return executor.functionInvocationContext(context.getPlayer(), context.getEvent(), variables,
                context.getRuntimePrincipal(), context.getInvocationId(), context.getRequestedDeadlineMillis());
        }
        return executor.defaultFunctionInvocationContext(context.getPlayer(), context.getEvent(), variables);
    }

    FlowExecutor.FunctionInvocationContext functionInvocationForSchedule(
        FlowExecutor executor, Player player, Map<String, Object> scheduleContext,
        FlowExecutor.FunctionInvocationContext scheduledContext, AutomationInstanceKey key, long runCount
    ) {
        return functionInvocationForSchedule(executor, player, scheduleContext, scheduledContext, key, runCount, false,
            scheduledContext != null ? scheduledContext.creatorPrincipal() : null,
            scheduledContext != null ? scheduledContext.creatorSessionReference() : null);
    }

    FlowExecutor.FunctionInvocationContext functionInvocationForSchedule(
        FlowExecutor executor, Player player, Map<String, Object> scheduleContext,
        FlowExecutor.FunctionInvocationContext scheduledContext, AutomationInstanceKey key, long runCount,
        boolean persistent, String creatorPrincipal, String creatorSessionReference
    ) {
        return functionInvocationForSchedule(executor, player, scheduleContext, scheduledContext, key, runCount,
            persistent, creatorPrincipal, creatorSessionReference, null);
    }

    private FlowExecutor.FunctionInvocationContext functionInvocationForSchedule(
        FlowExecutor executor, Player player, Map<String, Object> scheduleContext,
        FlowExecutor.FunctionInvocationContext scheduledContext, AutomationInstanceKey key, long runCount,
        boolean persistent, String creatorPrincipal, String creatorSessionReference, String taskId
    ) {
        FlowExecutor.FunctionInvocationContext base = scheduledContext != null
            ? scheduledContext : executor.defaultFunctionInvocationContext(player, null, scheduleContext);
        RuntimePrincipal executionPrincipal = persistent ? persistentSchedulePrincipal() : base != null ? base.principal() : null;
        if (executionPrincipal == null) {
            if (persistent) {
                throw new IllegalStateException("Persistent schedule principal is unavailable");
            }
            return null;
        }
        if (persistent && (creatorPrincipal == null || creatorPrincipal.isBlank())) {
            throw new IllegalStateException("Persistent schedule creator attribution is unavailable");
        }
        if (base != null && base.sessionReference() != null) {
            scheduleContext.put("runtime.sessionId", base.sessionReference());
        } else if (creatorSessionReference != null) {
            scheduleContext.put("runtime.sessionId", creatorSessionReference);
        }
        CorrelationId invocationId = CorrelationId.deterministic("schedule-function|"
            + key.definitionId() + "|" + key.scope().name() + "|" + key.ownerId() + "|"
            + (taskId != null ? taskId : "") + "|" + runCount);
        return executor.functionInvocationContext(player, null, scheduleContext, executionPrincipal, invocationId,
            RuntimeExecutionContext.NO_DEADLINE, creatorPrincipal, creatorSessionReference);
    }

    private RuntimePrincipal persistentSchedulePrincipal() {
        if (principalAuthority == null || serverId == null) {
            return null;
        }
        RuntimePrincipal current = persistentSchedulePrincipal;
        if (current != null && principalAuthority.trusts(current, principalAuthority.authority())) {
            return current;
        }
        synchronized (this) {
            if (persistentSchedulePrincipal == null
                || !principalAuthority.trusts(persistentSchedulePrincipal, principalAuthority.authority())) {
                persistentSchedulePrincipal = principalAuthority.issuePersistentSystem(
                    "resync:schedule:" + serverId.canonicalText());
            }
            return persistentSchedulePrincipal;
        }
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
        if (target == null || target.getFunctionInputs() == null) {
            return values;
        }
        List<FlowGraph.FunctionParameter> declared = new ArrayList<>(target.getFunctionInputs());
        List<FunctionParameterId> authoredIds = declared.stream()
            .map(parameter -> parameter != null ? parameter.getParameterId() : null)
            .toList();
        List<FunctionParameterId> capturedIds = captured == null ? List.of() : captured.keySet().stream()
            .map(FunctionCallSupport::parameterId)
            .filter(id -> id != null)
            .toList();
        retainCapturedParameterIds(target, capturedIds);
        if (authoredIds.stream().noneMatch(id -> id != null) && capturedIds.isEmpty()) {
            target.adaptLegacyFunctionParameterIds();
        }
        List<FlowGraph.FunctionParameter> resolved = target.getFunctionInputs();
        for (int index = 0; index < resolved.size(); index++) {
            FlowGraph.FunctionParameter parameter = resolved.get(index);
            if (parameter == null || parameter.getName() == null || parameter.getName().isBlank()) {
                continue;
            }
            FunctionParameterId authoredId = index < authoredIds.size() ? authoredIds.get(index) : null;
            FunctionParameterId capturedId = index < capturedIds.size() ? capturedIds.get(index) : null;
            Object value = explicitArgument(captured, authoredId);
            if (value == null) {
                value = explicitArgument(captured, capturedId);
            }
            if (value == null) {
                value = FunctionCallSupport.valueForArgument(captured, parameter);
            }
            String key = authoredId != null ? authoredId.canonicalText()
                : capturedId != null ? capturedId.canonicalText() : parameter.getName();
            values.put(key, valueCodecs != null && valueCodecs.hasCodec(parameter.getTypeRef())
                ? valueCodecs.decode(parameter.getTypeRef(), value) : value);
        }
        return values;
    }

    private void retainCapturedParameterIds(FlowGraph target, List<FunctionParameterId> capturedIds) {
        if (capturedIds.isEmpty()) {
            return;
        }
        List<FlowGraph.FunctionParameter> parameters = target.getFunctionInputs();
        List<FlowGraph.FunctionParameter> retained = new ArrayList<>(parameters.size());
        boolean changed = false;
        for (int index = 0; index < parameters.size(); index++) {
            FlowGraph.FunctionParameter parameter = parameters.get(index);
            FunctionParameterId capturedId = index < capturedIds.size() ? capturedIds.get(index) : null;
            if (parameter != null && parameter.getParameterId() == null && capturedId != null
                && parameters.stream().filter(value -> value != null).noneMatch(value -> capturedId.equals(value.getParameterId()))) {
                parameter = new FlowGraph.FunctionParameter(capturedId, parameter.getName(), parameter.getType(),
                    parameter.getWidget(), parameter.getOptionsSource(), parameter.getDefaultValue(), parameter.getTypeRef());
                changed = true;
            }
            retained.add(parameter);
        }
        if (changed) {
            target.setFunctionInputs(retained);
        }
    }

    private Object explicitArgument(Map<String, Object> captured, FunctionParameterId id) {
        if (captured == null || id == null) {
            return null;
        }
        return captured.get(id.canonicalText());
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
        AutomationInstanceKey key = new AutomationInstanceKey(AutomationDefinition.Kind.valueOf(selected.kind().name()),
            selected.definitionId(), selected.scope(), selected.ownerId());
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
        context.setOutput(node, "output_task", value);
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
        return automationTasks.check(new AutomationInstanceKey(definition.kind(), definition.id(), definition.scope(), owner.id()));
    }

    private Object automationOwnerValue(FlowContext context, FlowNode node, ScheduleDefinition definition) {
        Object wired = context.getInputValue(node, "owner", Object.class, null);
        return switch (definition.scope()) {
            case PLAYER -> wired != null ? wired : context.getInputValue(node, "player", Object.class, context.getPlayer());
            case ENTITY -> wired != null ? wired : context.getInputValue(node, "entity", Object.class, null);
            case NETWORK -> wired != null ? wired : context.getInputValue(node, "network", Object.class, null);
            default -> wired != null ? wired : context.getPlayer();
        };
    }

    private Map<String, Object> captureFunctionArguments(FlowContext context, FlowNode node, FlowGraph target, boolean persistent) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        Map<String, Object> fallback = captureArgumentsMap(context, node, persistent);
        if (target.getFunctionInputs() == null) {
            return Map.of();
        }
        for (FlowGraph.FunctionParameter parameter : target.getFunctionInputs()) {
            if (parameter == null || parameter.getName() == null || parameter.getName().isBlank()) {
                continue;
            }
            Object value = context.getInputValue(node, FunctionCallSupport.parameterPinKey(parameter, true), Object.class, null);
            if (value == null && parameter.isLegacyNameOnly()) {
                value = context.getInputValue(node, parameter.getName(), Object.class, null);
            }
            if (value == null) {
                value = FunctionCallSupport.valueForArgument(fallback, parameter);
            }
            if (value == null && parameter.getDefaultValue() != null && !parameter.getDefaultValue().isBlank()) {
                value = parameter.getDefaultValue();
            }
            if (value == null) {
                throw new IllegalArgumentException("Function argument is required: " + parameter.getName());
            }
            arguments.put(FunctionCallSupport.parameterKey(parameter), captureValue(context, parameter.getTypeRef(), value, persistent));
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

    private ServerResourceLocator requireFlowTarget(FlowContext context, FlowNode node) {
        if (serverId == null) {
            throw new IllegalStateException("Flow schedule target resolution requires an authoritative server ID");
        }
        Object raw = context.getInputValue(node, "flow_id", Object.class, null);
        ServerResourceLocator locator;
        if (raw instanceof ServerResourceLocator typed) {
            locator = typed;
        } else if (raw instanceof FlowResourceReference legacy) {
            if (!legacy.available() || !CORE_GRAPH_OWNER.value().equals(legacy.owner()) || !"flow".equals(legacy.kind())) {
                throw new IllegalArgumentException("Flow schedule target must be an available Core flow locator");
            }
            locator = new ServerResourceLocator(serverId,
                ContractRef.of(CORE_GRAPH_OWNER, ResourceTypeId.of("flow")), legacy.id());
        } else if (raw instanceof String flowId && !flowId.isBlank()) {
            locator = new ServerResourceLocator(serverId,
                ContractRef.of(CORE_GRAPH_OWNER, ResourceTypeId.of("flow")), flowId);
        } else {
            throw new IllegalArgumentException("Flow schedule target locator is required");
        }
        locator = validateTargetLocator(locator);
        if (targetGraph(locator) == null) {
            throw new IllegalArgumentException("Flow not found: " + locator.canonicalText());
        }
        return locator;
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
