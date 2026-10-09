package restudio.resync.flow.handler.generic;

import org.junit.jupiter.api.Test;
import restudio.flow.data.FlowNode;
import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowOperationResult;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.FlowExecutor;
import restudio.resync.flow.TypeAdapterRegistry;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.automation.AutomationDefinition;
import restudio.resync.flow.automation.AutomationInstanceKey;
import restudio.resync.flow.automation.AutomationScope;
import restudio.resync.flow.automation.AutomationTaskService;
import restudio.resync.flow.automation.ScheduleDefinition;
import restudio.resync.flow.identity.CorrelationId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.runtime.CompiledRuntimeContext;
import restudio.resync.flow.runtime.RuntimeAuditBoundary;
import restudio.resync.flow.runtime.RuntimeAuthority;
import restudio.resync.flow.runtime.RuntimeExecutionContext;
import restudio.resync.flow.runtime.RuntimePrincipal;
import restudio.resync.flow.runtime.RuntimePrincipalAuthority;
import restudio.resync.flow.runtime.RuntimeReceiptStore;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ScheduleHandlerTest {
    @Test
    void scheduleTargetLocatorKeepsSameIdGraphsDistinctByType() {
        ServerId server = ServerId.deterministic("schedule-target-identity");
        ScheduleDefinition flow = definition("flow", ScheduleDefinition.TargetType.FLOW);
        ScheduleDefinition function = definition("flow", ScheduleDefinition.TargetType.FUNCTION);

        assertNotEquals(flow.targetLocator(server), function.targetLocator(server));
        assertEquals("flow", flow.targetLocator(server).resourceType().value());
        assertEquals("function", function.targetLocator(server).resourceType().value());
    }

    @Test
    void invalidCronPatternProducesStructuredFailureBranch() {
        TestFlowContext context = execute("cron", Map.of(
            "expression", "61 12 * * *",
            "time_zone", "UTC",
            "flow_id", "daily"
        ));

        FlowOperationResult<?> result = assertInstanceOf(FlowOperationResult.class, context.outputs.get("result"));
        assertFalse(result.success());
        assertEquals("SCHEDULE_INPUT_INVALID", context.outputs.get("error_code"));
        assertEquals("failed", context.triggeredOutput);
    }

    @Test
    void overflowingDelayProducesStructuredFailureBranch() {
        TestFlowContext context = execute("delay", Map.of("seconds", Long.MAX_VALUE));

        assertEquals("SCHEDULE_OVERFLOW", context.outputs.get("error_code"));
        assertEquals("failed", context.triggeredOutput);
    }

    @Test
    void convertsFractionalSecondsWithoutUsingServerTicks() {
        ScheduleHandler handler = new ScheduleHandler(null);

        assertEquals(1500L, handler.delayMillis(1.5D));
        assertThrows(IllegalArgumentException.class, () -> handler.delayMillis(-2D));
    }

    @Test
    void rejectsNonFiniteDelayDurations() {
        ScheduleHandler handler = new ScheduleHandler(null);

        assertThrows(IllegalArgumentException.class, () -> handler.delayMillis(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> handler.delayMillis(Double.POSITIVE_INFINITY));
    }

    @Test
    void delayedCompiledFunctionStartsAfterParentDeadlineWithFreshDeadline() {
        RuntimeAuthority authority = new RuntimeAuthority("schedule-deadline-test");
        RuntimePrincipalAuthority principals = new RuntimePrincipalAuthority(authority);
        RuntimePrincipal principal = principals.issueAuthenticatedClient("schedule-client");
        CapturingExecutor executor = executor(authority, principals, principal);
        ScheduleHandler handler = new ScheduleHandler(null);
        long parentDeadline = System.currentTimeMillis() - 1L;
        FlowExecutor.FunctionInvocationContext parent = parentContext(principal, parentDeadline);
        try {
            FlowExecutor.FunctionInvocationContext invocation = handler.functionInvocationForSchedule(executor, null,
                new LinkedHashMap<>(), parent, scheduleKey(), 1L);
            Map<String, Object> result = CompletableFuture.supplyAsync(
                () -> executor.executeFunction(new FlowGraph(), null, null, Map.of(), Map.of(), invocation).join(),
                CompletableFuture.delayedExecutor(50L, java.util.concurrent.TimeUnit.MILLISECONDS)).join();

            assertTrue(System.currentTimeMillis() > parentDeadline);
            assertEquals(RuntimeExecutionContext.NO_DEADLINE, invocation.requestedDeadlineMillis());
            assertEquals("ok", result.get("result"));
            assertEquals(1, executor.invocations.size());
            assertEquals(RuntimeExecutionContext.NO_DEADLINE, executor.invocations.getFirst().requestedDeadlineMillis());
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void repeatingCompiledFunctionsUseDistinctPerFireIdsAndStableReplayIds() {
        RuntimeAuthority authority = new RuntimeAuthority("schedule-repeat-test");
        RuntimePrincipalAuthority principals = new RuntimePrincipalAuthority(authority);
        RuntimePrincipal principal = principals.issueAuthenticatedClient("schedule-client");
        CapturingExecutor executor = executor(authority, principals, principal);
        ScheduleHandler handler = new ScheduleHandler(null);
        try {
            FlowExecutor.FunctionInvocationContext first = handler.functionInvocationForSchedule(executor, null,
                new LinkedHashMap<>(), parentContext(principal, System.currentTimeMillis() - 1L), scheduleKey(), 1L);
            FlowExecutor.FunctionInvocationContext firstRetry = handler.functionInvocationForSchedule(executor, null,
                new LinkedHashMap<>(), parentContext(principal, System.currentTimeMillis() - 1L), scheduleKey(), 1L);
            FlowExecutor.FunctionInvocationContext second = handler.functionInvocationForSchedule(executor, null,
                new LinkedHashMap<>(), parentContext(principal, System.currentTimeMillis() - 1L), scheduleKey(), 2L);

            executor.executeFunction(new FlowGraph(), null, null, Map.of(), Map.of(), first).join();
            executor.executeFunction(new FlowGraph(), null, null, Map.of(), Map.of(), firstRetry).join();
            executor.executeFunction(new FlowGraph(), null, null, Map.of(), Map.of(), second).join();

            assertEquals(first.invocationId(), firstRetry.invocationId());
            assertNotEquals(first.invocationId(), second.invocationId());
            assertEquals(RuntimeExecutionContext.NO_DEADLINE, first.requestedDeadlineMillis());
            assertEquals(RuntimeExecutionContext.NO_DEADLINE, second.requestedDeadlineMillis());
            assertEquals(List.of(first.invocationId(), firstRetry.invocationId(), second.invocationId()),
                executor.invocations.stream().map(FlowExecutor.FunctionInvocationContext::invocationId).toList());
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void scheduledFunctionContextExcludesMutableTaskState() {
        ScheduleHandler handler = new ScheduleHandler(null);
        AutomationTaskService.TaskSnapshot first = new AutomationTaskService.TaskSnapshot(
            "task", AutomationTaskService.Kind.SCHEDULE, "schedule-test", AutomationScope.SERVER, "server", "server",
            true, AutomationTaskService.State.ACTIVE, 3L, 1_000L, 2_000L, 1_999L, 4L, 0L, 0L, 0L, 0D, "old", "");
        AutomationTaskService.TaskSnapshot retry = new AutomationTaskService.TaskSnapshot(
            "task", AutomationTaskService.Kind.SCHEDULE, "schedule-test", AutomationScope.SERVER, "server", "server",
            true, AutomationTaskService.State.FAILED, 4L, 1_000L, 2_000L, 9_999L, 4L, 0L, 0L, 0L, 0D, "new", "failure");

        assertEquals(handler.stableScheduleTask(first), handler.stableScheduleTask(retry));
        assertEquals(Map.of("taskId", "task", "definitionId", "schedule-test", "scope", "server", "ownerId", "server",
            "runCount", 4L, "scheduledAt", 2_000L), handler.stableScheduleTask(first));
    }

    @Test
    void persistentSchedulesUseStableServerPrincipalAndKeepCreatorAttribution() {
        RuntimeAuthority authority = new RuntimeAuthority("schedule-principal-test");
        RuntimePrincipalAuthority firstPrincipals = new RuntimePrincipalAuthority(authority);
        RuntimePrincipal creator = firstPrincipals.issueAuthenticatedClient("schedule-client");
        ServerId serverId = ServerId.deterministic("schedule-principal-server");
        CapturingExecutor firstExecutor = executor(authority, firstPrincipals, creator);
        ScheduleHandler firstHandler = new ScheduleHandler(null, Clock.systemUTC(), null, null, null, firstPrincipals, serverId);
        FlowExecutor.FunctionInvocationContext first = firstHandler.functionInvocationForSchedule(firstExecutor, null,
            new LinkedHashMap<>(), parentContext(creator, System.currentTimeMillis() - 1L), scheduleKey(), 7L, true,
            creator.canonical(), "client-session");

        RuntimePrincipalAuthority restoredPrincipals = new RuntimePrincipalAuthority(authority);
        RuntimePrincipal restoredDefault = restoredPrincipals.issueSystem("resync-runtime");
        CapturingExecutor restoredExecutor = executor(authority, restoredPrincipals, restoredDefault);
        ScheduleHandler restoredHandler = new ScheduleHandler(null, Clock.systemUTC(), null, null, null,
            restoredPrincipals, serverId);
        FlowExecutor.FunctionInvocationContext restored = restoredHandler.functionInvocationForSchedule(restoredExecutor, null,
            new LinkedHashMap<>(), null, scheduleKey(), 7L, true, creator.canonical(), "client-session");

        try {
            assertEquals(first.principal().canonical(), restored.principal().canonical());
            assertEquals(RuntimePrincipal.Kind.SYSTEM, first.principal().kind());
            assertEquals(creator.canonical(), first.creatorPrincipal());
            assertEquals("client-session", first.creatorSessionReference());
            assertEquals(first.creatorPrincipal(), restored.creatorPrincipal());
            assertEquals(first.creatorSessionReference(), restored.creatorSessionReference());
            assertNotEquals(creator.canonical(), first.principal().canonical());
        } finally {
            firstExecutor.shutdown();
            restoredExecutor.shutdown();
        }
    }

    private FlowExecutor.FunctionInvocationContext parentContext(RuntimePrincipal principal, long deadline) {
        return new FlowExecutor.FunctionInvocationContext(principal,
            CorrelationId.of(UUID.fromString("66666666-6666-4666-8666-666666666666")),
            new CompiledRuntimeContext(null, null, Map.of(), principal), deadline, "schedule-session");
    }

    private AutomationInstanceKey scheduleKey() {
        return new AutomationInstanceKey(AutomationDefinition.Kind.SCHEDULE, "schedule-test", AutomationScope.SERVER, "server");
    }

    private CapturingExecutor executor(RuntimeAuthority authority, RuntimePrincipalAuthority principals,
                                       RuntimePrincipal principal) {
        CapturingExecutor executor = new CapturingExecutor();
        executor.configureCompiledFunctionRuntime(authority, principals, RuntimeReceiptStore.inMemory(true),
            ServerId.deterministic("schedule-test"), principal, RuntimeAuditBoundary.unavailable());
        return executor;
    }

    private static final class CapturingExecutor extends FlowExecutor {
        private final List<FunctionInvocationContext> invocations = new CopyOnWriteArrayList<>();

        private CapturingExecutor() {
            super(new HandlerRegistry(), new TypeAdapterRegistry(), Map.of());
        }

        @Override
        public CompletableFuture<Map<String, Object>> executeFunction(FlowGraph functionGraph, org.bukkit.entity.Player player,
                                                                        org.bukkit.event.Event event, Map<String, Object> inputs,
                                                                        Map<String, Object> eventVars,
                                                                        FunctionInvocationContext invocationContext) {
            invocations.add(invocationContext);
            return CompletableFuture.completedFuture(Map.of("result", "ok"));
        }
    }

    private TestFlowContext execute(String operation, Map<String, Object> inputs) {
        ScheduleHandler handler = new ScheduleHandler(null, Clock.fixed(Instant.parse("2026-07-16T00:00:00Z"), ZoneOffset.UTC));
        FlowNode node = new FlowNode("schedule.test", 0, 0, Map.of());
        node.setHandlerConfig(Map.of("operation", operation));
        TestFlowContext context = new TestFlowContext(inputs);
        handler.execute(context, node);
        return context;
    }

    private ScheduleDefinition definition(String id, ScheduleDefinition.TargetType targetType) {
        return new ScheduleDefinition("schedule-" + targetType.name().toLowerCase(), "Schedule", "", targetType, id,
            ScheduleDefinition.TimingMode.AFTER_DELAY, 0D, restudio.resync.flow.automation.TimerDefinition.TimeUnit.SECONDS,
            0D, "", "UTC", "", AutomationScope.SERVER, false, ScheduleDefinition.OverlapPolicy.SKIP,
            ScheduleDefinition.ExistingTaskPolicy.REPLACE, ScheduleDefinition.FailurePolicy.CONTINUE,
            ScheduleDefinition.OfflinePolicy.WAIT, ScheduleDefinition.MissedRunPolicy.RUN_ONCE);
    }

    private static class TestFlowContext extends FlowContext {
        private final Map<String, Object> inputs;
        private final Map<String, Object> outputs = new HashMap<>();
        private String triggeredOutput;

        private TestFlowContext(Map<String, Object> inputs) {
            super(null, null, null);
            this.inputs = inputs;
        }

        @Override
        public <T> T getInputValue(FlowNode node, String pinName, Class<T> type, T defaultValue) {
            Object value = inputs.get(pinName);
            return value != null ? type.cast(value) : defaultValue;
        }

        @Override
        public void setOutput(FlowNode node, String pinName, Object value) {
            outputs.put(pinName, value);
        }

        @Override
        public Object getOutput(FlowNode node, String pinName) {
            return outputs.get(pinName);
        }

        @Override
        public void triggerOutput(String pinName) {
            triggeredOutput = pinName;
        }
    }
}
