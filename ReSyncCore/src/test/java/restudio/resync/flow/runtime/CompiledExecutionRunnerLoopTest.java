package restudio.resync.flow.runtime;

import org.junit.jupiter.api.Test;
import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.catalog.CatalogBindingProof;
import restudio.resync.flow.catalog.CatalogCapabilityDescriptor;
import restudio.resync.flow.catalog.CatalogCategoryDescriptor;
import restudio.resync.flow.catalog.CatalogCompiler;
import restudio.resync.flow.catalog.CatalogContractRange;
import restudio.resync.flow.catalog.CatalogContribution;
import restudio.resync.flow.catalog.CatalogNodeDescriptor;
import restudio.resync.flow.catalog.CatalogProvenance;
import restudio.resync.flow.catalog.CatalogRuntimeActivation;
import restudio.resync.flow.catalog.CatalogSnapshot;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.graph.CompiledExecutionPlan;
import restudio.resync.flow.graph.CompiledExecutionRunner;
import restudio.resync.flow.graph.CompiledExecutionStep;
import restudio.resync.flow.graph.GraphCompiler;
import restudio.resync.flow.graph.GraphConnection;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.graph.GraphEndpoint;
import restudio.resync.flow.graph.GraphNode;
import restudio.resync.flow.graph.OpaqueData;
import restudio.resync.flow.diagnostic.Diagnostic;
import restudio.resync.flow.diagnostic.DiagnosticPhase;
import restudio.resync.flow.diagnostic.DiagnosticSeverity;
import restudio.resync.flow.identity.BranchId;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.ConnectionId;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.CorrelationId;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.NodeInstanceId;
import restudio.resync.flow.identity.OperationId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.PinId;
import restudio.resync.flow.identity.ProviderId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.inspector.InspectorFallback;
import restudio.resync.flow.type.ConversionGraph;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypeReference;
import restudio.resync.flow.type.TypedValue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompiledExecutionRunnerLoopTest {
    private static final OwnerId OWNER = OwnerId.of("restudio.resync");
    private static final ContractRef<CapabilityId> LOOP_CAPABILITY = ContractRef.of(OWNER, CapabilityId.of("flow.control"));
    private static final ContractRef<CapabilityId> TEST_CAPABILITY = ContractRef.of(OWNER, CapabilityId.of("loop.runtime.test"));
    private static final ContractRef<CapabilityId> EDITOR = ContractRef.of(OWNER, CapabilityId.of("generic-editor"));
    private static final ContractRef<ProviderId> PROVIDER = ContractRef.of(OWNER, ProviderId.of("loop-runtime-test"));
    private static final ContractRef<OperationId> LOOP_COUNT = ContractRef.of(OWNER, OperationId.of("loop_count"));
    private static final ContractRef<OperationId> LOOP_FOR_EACH = ContractRef.of(OWNER, OperationId.of("loop_for_each"));
    private static final TypeExpr EXECUTION = TypeExpr.named(TypeReference.of("builtin", "execution"));
    private static final TypeExpr NUMBER = TypeExpr.named(TypeReference.of("builtin", "number"));
    private static final TypeExpr BOOLEAN = TypeExpr.named(TypeReference.of("builtin", "boolean"));
    private static final TypeExpr TEXT = TypeExpr.named(TypeReference.of("builtin", "string"));
    private static final TypeExpr CONVERTED_TEXT = TypeExpr.named(TypeReference.of("restudio.resync", "converted-text"));
    private static final TypeExpr LIST_TEXT = new TypeExpr.ListType(TEXT, Map.of());
    private static final PinId FLOW = PinId.of("flow");
    private static final PinId COUNT = PinId.of("count");
    private static final PinId LIST = PinId.of("list");
    private static final PinId LOOP = PinId.of("loop");
    private static final PinId DONE = PinId.of("done");
    private static final PinId INDEX = PinId.of("index");
    private static final PinId ELEMENT = PinId.of("element");
    private static final PinId COMPLETED = PinId.of("completed");
    private static final PinId VALUE = PinId.of("value");
    private static final PinId BODY_VALUE = PinId.of("body-value");
    private static final PinId CONVERT_INPUT = PinId.of("convert-input");
    private static final PinId CONVERT_OUTPUT = PinId.of("convert-output");
    private static final NodeInstanceId CONTROLLER = node(1);
    private static final NodeInstanceId BODY = node(2);
    private static final NodeInstanceId AFTER = node(3);
    private static final NodeInstanceId FINAL = node(4);
    private static final CatalogBinding CATALOG_BINDING = new CatalogBinding(1, hash("catalog"), hash("manifest"));
    private static final ServerResourceLocator RESOURCE = new ServerResourceLocator(
        UUID.fromString("11111111-1111-4111-8111-111111111111"), ContractRef.of(OWNER, ResourceTypeId.of("flow")), "loop-runtime");

    @Test
    void countRunsExactOrderedIterationsSequentiallyThenDoneOnce() throws Exception {
        LoopFixture fixture = new LoopFixture(CompiledExecutionStep.LoopControl.Kind.COUNT, TypedValue.value(NUMBER, 3));
        List<String> order = new CopyOnWriteArrayList<>();
        List<RuntimeInvocation> bodies = new CopyOnWriteArrayList<>();
        List<CompletableFuture<RuntimeResult>> pending = new CopyOnWriteArrayList<>();
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximumActive = new AtomicInteger();
        AtomicInteger doneCalls = new AtomicInteger();
        fixture.controller.set(invocation -> {
            order.add("controller");
            return CompletableFuture.completedFuture(admission(fixture.controllerOperation));
        });
        fixture.body.set(invocation -> {
            bodies.add(invocation);
            order.add("body:" + index(invocation));
            maximumActive.accumulateAndGet(active.incrementAndGet(), Math::max);
            CompletableFuture<RuntimeResult> result = new CompletableFuture<>();
            pending.add(result);
            return result.thenApply(value -> {
                active.decrementAndGet();
                return value;
            });
        });
        fixture.done.set(invocation -> {
            doneCalls.incrementAndGet();
            order.add("done");
            assertEquals(TypedValue.value(BOOLEAN, true), invocation.inputs().get(COMPLETED));
            assertTrue(invocation.inputs().get(FLOW).hasValue());
            return CompletableFuture.completedFuture(RuntimeResult.success());
        });

        CompletableFuture<CompiledExecutionRunner.ExecutionResult> execution = fixture.execute();

        assertEquals(List.of("controller", "body:0"), order);
        assertFalse(execution.isDone());
        pending.get(0).complete(RuntimeResult.success());
        assertEquals(List.of("controller", "body:0", "body:1"), order);
        pending.get(1).complete(RuntimeResult.success());
        assertEquals(List.of("controller", "body:0", "body:1", "body:2"), order);
        pending.get(2).complete(RuntimeResult.success());

        assertEquals(CompiledExecutionRunner.Status.SUCCESS, execution.get(5, TimeUnit.SECONDS).status());
        assertEquals(List.of("controller", "body:0", "body:1", "body:2", "done"), order);
        assertEquals(1, maximumActive.get());
        assertEquals(0, active.get());
        assertEquals(1, doneCalls.get());
        assertEquals(List.of(0, 1, 2), bodies.stream().map(CompiledExecutionRunnerLoopTest::index).toList());
        assertTrue(bodies.stream().allMatch(invocation -> invocation.inputs().get(FLOW).hasValue()));
        assertNotSame(bodies.get(0).inputs(), bodies.get(1).inputs());
        assertNotSame(bodies.get(1).inputs(), bodies.get(2).inputs());
    }

    @Test
    void forEachSnapshotsSourceOnceAndRoutesExactOrderedPairs() throws Exception {
        List<String> source = new ArrayList<>(List.of("a", "b", "c"));
        LoopFixture fixture = new LoopFixture(CompiledExecutionStep.LoopControl.Kind.FOR_EACH,
            TypedValue.value(LIST_TEXT, source));
        List<String> observed = new CopyOnWriteArrayList<>();
        AtomicInteger doneCalls = new AtomicInteger();
        fixture.body.set(invocation -> {
            observed.add(index(invocation) + ":" + invocation.inputs().get(ELEMENT).value());
            if (observed.size() == 1) {
                source.clear();
                source.add("changed");
            }
            return CompletableFuture.completedFuture(RuntimeResult.success());
        });
        fixture.done.set(invocation -> {
            doneCalls.incrementAndGet();
            return CompletableFuture.completedFuture(RuntimeResult.success());
        });

        CompiledExecutionRunner.ExecutionResult result = fixture.execute().get(5, TimeUnit.SECONDS);

        assertEquals(CompiledExecutionRunner.Status.SUCCESS, result.status());
        assertEquals(List.of("0:a", "1:b", "2:c"), observed);
        assertEquals(List.of("changed"), source);
        assertEquals(1, doneCalls.get());
    }

    @Test
    void zeroCountAndEmptyListSkipBodyAndRunDoneOnceWithCompletedTrue() throws Exception {
        for (LoopFixture fixture : List.of(
            new LoopFixture(CompiledExecutionStep.LoopControl.Kind.COUNT, TypedValue.value(NUMBER, 0)),
            new LoopFixture(CompiledExecutionStep.LoopControl.Kind.FOR_EACH, TypedValue.value(LIST_TEXT, List.of())))) {
            AtomicInteger bodyCalls = new AtomicInteger();
            AtomicInteger doneCalls = new AtomicInteger();
            fixture.body.set(invocation -> {
                bodyCalls.incrementAndGet();
                return CompletableFuture.completedFuture(RuntimeResult.success());
            });
            fixture.done.set(invocation -> {
                doneCalls.incrementAndGet();
                assertEquals(TypedValue.value(BOOLEAN, true), invocation.inputs().get(COMPLETED));
                assertTrue(invocation.inputs().get(FLOW).hasValue());
                return CompletableFuture.completedFuture(RuntimeResult.success());
            });

            CompiledExecutionRunner.ExecutionResult result = fixture.execute().get(5, TimeUnit.SECONDS);

            assertEquals(CompiledExecutionRunner.Status.SUCCESS, result.status());
            assertEquals(0, bodyCalls.get());
            assertEquals(1, doneCalls.get());
        }
    }

    @Test
    void nestedLoopsUseFreshIsolatedFramesAndResetInnerOrder() throws Exception {
        RuntimeOperationDescriptor controllerOperation = loopOperation(CompiledExecutionStep.LoopControl.Kind.COUNT,
            RuntimeSemantics.Cancellation.COOPERATIVE, RuntimeSemantics.UnloadPolicy.DRAIN);
        RuntimeOperationDescriptor innerBodyOperation = actionOperation("nested-body", List.of(pin(FLOW, true, EXECUTION), pin(INDEX, true, NUMBER)));
        RuntimeOperationDescriptor afterOperation = actionOperation("nested-after", List.of(pin(FLOW, true, EXECUTION)));
        RuntimeOperationDescriptor doneOperation = actionOperation("nested-done", List.of(pin(FLOW, true, EXECUTION), pin(COMPLETED, true, BOOLEAN)));
        NodeInstanceId innerController = node(5);
        NodeInstanceId innerBody = node(6);
        List<String> order = new CopyOnWriteArrayList<>();
        List<RuntimeInvocation> innerBodies = new CopyOnWriteArrayList<>();
        AtomicInteger controllerCalls = new AtomicInteger();
        AtomicInteger afterCalls = new AtomicInteger();
        AtomicInteger doneCalls = new AtomicInteger();
        RuntimeBinding controllerBinding = binding(controllerOperation, invocation -> {
            order.add("controller:" + controllerCalls.getAndIncrement());
            return CompletableFuture.completedFuture(admission(controllerOperation));
        });
        RuntimeBinding innerBodyBinding = binding(innerBodyOperation, invocation -> {
            innerBodies.add(invocation);
            order.add("inner:" + index(invocation));
            return CompletableFuture.completedFuture(RuntimeResult.success());
        });
        RuntimeBinding afterBinding = binding(afterOperation, invocation -> {
            afterCalls.incrementAndGet();
            order.add("after");
            return CompletableFuture.completedFuture(RuntimeResult.success());
        });
        RuntimeBinding doneBinding = binding(doneOperation, invocation -> {
            doneCalls.incrementAndGet();
            order.add("done");
            return CompletableFuture.completedFuture(RuntimeResult.success());
        });
        RuntimeBindingRegistry registry = registry(controllerBinding, innerBodyBinding, afterBinding, doneBinding);
        CompiledExecutionStep outer = loopStep(CONTROLLER, controllerOperation, TypedValue.value(NUMBER, 2),
            List.of(innerController, AFTER), Map.of(
                LOOP, List.of(new GraphEndpoint(innerController, FLOW)),
                DONE, List.of(new GraphEndpoint(FINAL, FLOW)),
                INDEX, List.of(),
                COMPLETED, List.of(new GraphEndpoint(FINAL, COMPLETED))));
        CompiledExecutionStep inner = loopStep(innerController, controllerOperation, TypedValue.value(NUMBER, 2),
            List.of(innerBody), Map.of(
                LOOP, List.of(new GraphEndpoint(innerBody, FLOW)),
                DONE, List.of(new GraphEndpoint(AFTER, FLOW)),
                INDEX, List.of(new GraphEndpoint(innerBody, INDEX)),
                COMPLETED, List.of()));
        List<CompiledExecutionStep> steps = List.of(
            outer,
            inner,
            step(innerBody, innerBodyOperation, Map.of(), Map.of()),
            step(AFTER, afterOperation, Map.of(), Map.of()),
            step(FINAL, doneOperation, Map.of(), Map.of()));
        List<GraphConnection> connections = List.of(
            connection("outer-body", CONTROLLER, LOOP, innerController, FLOW),
            connection("outer-done", CONTROLLER, DONE, FINAL, FLOW),
            connection("outer-completed", CONTROLLER, COMPLETED, FINAL, COMPLETED),
            connection("inner-body", innerController, LOOP, innerBody, FLOW),
            connection("inner-index", innerController, INDEX, innerBody, INDEX),
            connection("inner-done", innerController, DONE, AFTER, FLOW));
        CompiledExecutionPlan plan = plan(steps, connections);

        CompiledExecutionRunner.ExecutionResult result = new CompiledExecutionRunner(registry, authority())
            .execute(plan).toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertEquals(CompiledExecutionRunner.Status.SUCCESS, result.status());
        assertEquals(List.of("controller:0", "controller:1", "inner:0", "inner:1", "after",
            "controller:2", "inner:0", "inner:1", "after", "done"), order);
        assertEquals(List.of(0, 1, 0, 1), innerBodies.stream().map(CompiledExecutionRunnerLoopTest::index).toList());
        assertNotSame(innerBodies.get(0).inputs(), innerBodies.get(2).inputs());
        assertNotEquals(innerBodies.get(0).idempotencyKey(), innerBodies.get(2).idempotencyKey());
        assertEquals(2, afterCalls.get());
        assertEquals(1, doneCalls.get());
    }

    @Test
    void successfulLoopRetainsOnlyTheLastIterationBodyResultAndOutput() throws Exception {
        RuntimeOperationDescriptor controllerOperation = loopOperation(CompiledExecutionStep.LoopControl.Kind.COUNT,
            RuntimeSemantics.Cancellation.COOPERATIVE, RuntimeSemantics.UnloadPolicy.DRAIN);
        RuntimeOperationDescriptor bodyOperation = actionOperation("retained-body", List.of(
            pin(FLOW, true, EXECUTION), pin(INDEX, true, NUMBER), pin(BODY_VALUE, false, TEXT)));
        RuntimeOperationDescriptor doneOperation = actionOperation("retained-done", List.of(
            pin(FLOW, true, EXECUTION), pin(COMPLETED, true, BOOLEAN)));
        List<RuntimeResult> bodyResults = new CopyOnWriteArrayList<>();
        RuntimeBindingRegistry registry = registry(
            binding(controllerOperation, invocation -> CompletableFuture.completedFuture(admission(controllerOperation))),
            binding(bodyOperation, invocation -> {
                RuntimeResult result = RuntimeResult.success(Map.of(
                    BODY_VALUE, TypedValue.value(TEXT, "iteration-" + index(invocation))), null);
                bodyResults.add(result);
                return CompletableFuture.completedFuture(result);
            }),
            binding(doneOperation, invocation -> CompletableFuture.completedFuture(RuntimeResult.success())));
        Map<PinId, List<GraphEndpoint>> controllerOutputs = new LinkedHashMap<>();
        controllerOutputs.put(LOOP, List.of(new GraphEndpoint(BODY, FLOW)));
        controllerOutputs.put(DONE, List.of(new GraphEndpoint(FINAL, FLOW)));
        controllerOutputs.put(INDEX, List.of(new GraphEndpoint(BODY, INDEX)));
        controllerOutputs.put(COMPLETED, List.of(new GraphEndpoint(FINAL, COMPLETED)));
        CompiledExecutionPlan plan = plan(List.of(
            loopStep(CONTROLLER, controllerOperation, TypedValue.value(NUMBER, 3), List.of(BODY), controllerOutputs),
            step(BODY, bodyOperation, Map.of(), Map.of()),
            step(FINAL, doneOperation, Map.of(), Map.of())), List.of(
            connection("retained-body", CONTROLLER, LOOP, BODY, FLOW),
            connection("retained-index", CONTROLLER, INDEX, BODY, INDEX),
            connection("retained-done", CONTROLLER, DONE, FINAL, FLOW),
            connection("retained-completed", CONTROLLER, COMPLETED, FINAL, COMPLETED)));

        CompiledExecutionRunner.ExecutionResult result = new CompiledExecutionRunner(registry, authority())
            .execute(plan).toCompletableFuture().get(5, TimeUnit.SECONDS);

        RuntimeResult lastBodyResult = RuntimeResult.success(Map.of(BODY_VALUE, TypedValue.value(TEXT, "iteration-2")), null);
        assertEquals(CompiledExecutionRunner.Status.SUCCESS, result.status());
        assertEquals(3, bodyResults.size());
        assertEquals(lastBodyResult, bodyResults.get(2));
        assertEquals(lastBodyResult, result.nodeResults().get(BODY));
        assertEquals(TypedValue.value(TEXT, "iteration-2"), result.outputs().get(new GraphEndpoint(BODY, BODY_VALUE)));
    }

    @Test
    void bodyFailureStopsLaterIterationsAndSuppressesDone() throws Exception {
        LoopFixture fixture = new LoopFixture(CompiledExecutionStep.LoopControl.Kind.COUNT, TypedValue.value(NUMBER, 3));
        List<Integer> bodies = new CopyOnWriteArrayList<>();
        AtomicInteger doneCalls = new AtomicInteger();
        fixture.body.set(invocation -> {
            int index = index(invocation);
            bodies.add(index);
            return CompletableFuture.completedFuture(index == 1
                ? RuntimeResult.failure(failure(), "failed")
                : RuntimeResult.success());
        });
        fixture.done.set(invocation -> {
            doneCalls.incrementAndGet();
            return CompletableFuture.completedFuture(RuntimeResult.success());
        });

        CompiledExecutionRunner.ExecutionResult result = fixture.execute().get(5, TimeUnit.SECONDS);

        assertEquals(CompiledExecutionRunner.Status.FAILURE, result.status());
        assertEquals(List.of(0, 1), bodies);
        assertEquals(0, doneCalls.get());
        assertEquals("RUNTIME.HANDLER_FAILURE", result.failure().diagnostic().code());
        assertEquals(RuntimeUnloadResult.Status.REMOVED, fixture.registry.tryUnload(PROVIDER).status());
    }

    @Test
    void cancellationWhileFirstBodyIsPendingSuppressesLateSuccessLaterIterationsAndDone() throws Exception {
        LoopFixture fixture = new LoopFixture(CompiledExecutionStep.LoopControl.Kind.COUNT, TypedValue.value(NUMBER, 3));
        RuntimeCancellationToken cancellation = new RuntimeCancellationToken();
        CompletableFuture<RuntimeResult> pending = new CompletableFuture<>();
        AtomicReference<RuntimeInvocation> bodyInvocation = new AtomicReference<>();
        AtomicInteger bodyCalls = new AtomicInteger();
        AtomicInteger doneCalls = new AtomicInteger();
        fixture.body.set(invocation -> {
            bodyCalls.incrementAndGet();
            bodyInvocation.set(invocation);
            return pending;
        });
        fixture.done.set(invocation -> {
            doneCalls.incrementAndGet();
            return CompletableFuture.completedFuture(RuntimeResult.success());
        });

        CompletableFuture<CompiledExecutionRunner.ExecutionResult> execution = fixture.execute(cancellation, CorrelationId.random(),
            RuntimeExecutionContext.NO_DEADLINE, null);
        assertEquals(1, bodyCalls.get());
        cancellation.cancel();
        bodyInvocation.get().cancellationToken().cancelled().toCompletableFuture().get(5, TimeUnit.SECONDS);
        pending.complete(RuntimeResult.success());

        CompiledExecutionRunner.ExecutionResult result = execution.get(5, TimeUnit.SECONDS);
        assertEquals(CompiledExecutionRunner.Status.CANCELLED, result.status());
        assertEquals(1, bodyCalls.get());
        assertEquals(0, doneCalls.get());
        assertEquals(RuntimeUnloadResult.Status.REMOVED, fixture.registry.tryUnload(PROVIDER).status());
    }

    @Test
    void oneAbsoluteDeadlineIsStableAndExpirySuppressesLaterWork() throws Exception {
        ConversionFixture stable = new ConversionFixture();
        CorrelationId root = CorrelationId.random();
        long deadline = System.currentTimeMillis() + 60_000;
        CompiledRuntimeContext context = new CompiledRuntimeContext(null, null, Map.of("origin", TypedValue.value(TEXT, "test")));

        CompiledExecutionRunner.ExecutionResult stableResult = stable.execute(root, deadline, context).get(5, TimeUnit.SECONDS);

        assertEquals(CompiledExecutionRunner.Status.SUCCESS, stableResult.status());
        assertEquals(6, stable.invocations.size());
        stable.invocations.forEach(invocation -> {
            assertEquals(root, invocation.invocationId());
            assertEquals(deadline, invocation.deadlineMillis());
            assertTrue(invocation.runtimeContext() == context);
        });
        assertEquals(2, stable.conversions.size());
        assertEquals(1, stable.activationReads.get());

        LoopFixture expiring = new LoopFixture(CompiledExecutionStep.LoopControl.Kind.COUNT, TypedValue.value(NUMBER, 3));
        CompletableFuture<RuntimeResult> pending = new CompletableFuture<>();
        AtomicReference<RuntimeInvocation> firstBody = new AtomicReference<>();
        AtomicInteger bodyCalls = new AtomicInteger();
        AtomicInteger doneCalls = new AtomicInteger();
        expiring.body.set(invocation -> {
            bodyCalls.incrementAndGet();
            firstBody.set(invocation);
            return pending;
        });
        expiring.done.set(invocation -> {
            doneCalls.incrementAndGet();
            return CompletableFuture.completedFuture(RuntimeResult.success());
        });
        long expiringDeadline = System.currentTimeMillis() + 500;
        CompletableFuture<CompiledExecutionRunner.ExecutionResult> execution = expiring.execute(new RuntimeCancellationToken(),
            CorrelationId.random(), expiringDeadline, null);

        assertNotNull(firstBody.get());
        firstBody.get().cancellationToken().cancelled().toCompletableFuture().get(5, TimeUnit.SECONDS);
        CompiledExecutionRunner.ExecutionResult expired = execution.get(5, TimeUnit.SECONDS);
        pending.complete(RuntimeResult.success());

        assertEquals(CompiledExecutionRunner.Status.FAILURE, expired.status());
        assertEquals("RUNTIME.EXECUTION_TIMEOUT", expired.failure().diagnostic().code());
        assertEquals(expiringDeadline, firstBody.get().deadlineMillis());
        assertEquals(1, bodyCalls.get());
        assertEquals(0, doneCalls.get());
    }

    @Test
    void retainsOneRootLeaseAndStableUniqueFrameAndConversionKeysAcrossReplay() throws Exception {
        ConversionFixture fixture = new ConversionFixture();
        CorrelationId root = CorrelationId.random();
        long deadline = System.currentTimeMillis() + 60_000;
        CompiledRuntimeContext context = CompiledRuntimeContext.empty();

        fixture.execute(root, deadline, context).get(5, TimeUnit.SECONDS);
        List<String> firstKeys = fixture.invocations.stream().map(RuntimeInvocation::idempotencyKey).toList();
        List<String> firstBodyKeys = fixture.bodies.stream().map(RuntimeInvocation::idempotencyKey).toList();
        List<String> firstConversionKeys = fixture.conversions.stream().map(RuntimeInvocation::idempotencyKey).toList();
        fixture.clear();
        fixture.execute(root, deadline, context).get(5, TimeUnit.SECONDS);

        assertEquals(firstKeys, fixture.invocations.stream().map(RuntimeInvocation::idempotencyKey).toList());
        assertEquals(firstBodyKeys, fixture.bodies.stream().map(RuntimeInvocation::idempotencyKey).toList());
        assertEquals(firstConversionKeys, fixture.conversions.stream().map(RuntimeInvocation::idempotencyKey).toList());
        assertEquals(2, firstBodyKeys.stream().distinct().count());
        assertEquals(2, firstConversionKeys.stream().distinct().count());
        assertTrue(firstConversionKeys.stream().allMatch(key -> key.matches("conversion:[0-9a-f]{64}")));
        fixture.invocations.forEach(invocation -> assertEquals(root, invocation.invocationId()));
        assertEquals(2, fixture.activationReads.get());

        LoopFixture leaseFixture = new LoopFixture(CompiledExecutionStep.LoopControl.Kind.COUNT, TypedValue.value(NUMBER, 1),
            RuntimeSemantics.Cancellation.NONE, RuntimeSemantics.UnloadPolicy.BLOCK);
        CompletableFuture<RuntimeResult> pendingDone = new CompletableFuture<>();
        leaseFixture.done.set(invocation -> pendingDone);
        CompletableFuture<CompiledExecutionRunner.ExecutionResult> execution = leaseFixture.execute();
        RuntimeUnloadResult unload = leaseFixture.registry.unload(PROVIDER);

        assertEquals(RuntimeUnloadResult.Status.BLOCKED, unload.status());
        assertEquals(1, unload.remainingLeases());
        pendingDone.complete(RuntimeResult.success());
        assertEquals(CompiledExecutionRunner.Status.SUCCESS, execution.get(5, TimeUnit.SECONDS).status());
        assertEquals(RuntimeUnloadResult.Status.REMOVED, leaseFixture.registry.tryUnload(PROVIDER).status());
    }

    @Test
    void simpleLoopOperationBudgetAcceptsExactlyTenThousandAndRejectsOperationTenThousandOne() throws Exception {
        LoopFixture accepted = new LoopFixture(CompiledExecutionStep.LoopControl.Kind.COUNT, TypedValue.value(NUMBER, 4_999));
        AtomicInteger acceptedBodies = new AtomicInteger();
        AtomicInteger acceptedDone = new AtomicInteger();
        accepted.body.set(invocation -> {
            acceptedBodies.incrementAndGet();
            return CompletableFuture.completedFuture(RuntimeResult.success());
        });
        accepted.done.set(invocation -> {
            acceptedDone.incrementAndGet();
            return CompletableFuture.completedFuture(RuntimeResult.success());
        });

        CompiledExecutionRunner.ExecutionResult acceptedResult = accepted.execute().get(10, TimeUnit.SECONDS);

        assertEquals(10_000, 1 + 2 * 4_999 + 1);
        assertEquals(CompiledExecutionRunner.Status.SUCCESS, acceptedResult.status());
        assertEquals(4_999, acceptedBodies.get());
        assertEquals(1, acceptedDone.get());
        assertTrue(acceptedResult.nodeResults().get(CONTROLLER).successful());
        assertTrue(acceptedResult.nodeResults().get(BODY).successful());
        assertTrue(acceptedResult.nodeResults().get(FINAL).successful());

        LoopFixture rejected = new LoopFixture(CompiledExecutionStep.LoopControl.Kind.COUNT, TypedValue.value(NUMBER, 5_000));
        AtomicInteger rejectedBodies = new AtomicInteger();
        AtomicInteger rejectedDone = new AtomicInteger();
        rejected.body.set(invocation -> {
            rejectedBodies.incrementAndGet();
            return CompletableFuture.completedFuture(RuntimeResult.success());
        });
        rejected.done.set(invocation -> {
            rejectedDone.incrementAndGet();
            return CompletableFuture.completedFuture(RuntimeResult.success());
        });

        CompiledExecutionRunner.ExecutionResult rejectedResult = rejected.execute().get(10, TimeUnit.SECONDS);

        assertEquals(10_001, 1 + 2 * 4_999 + 1 + 1);
        assertEquals(CompiledExecutionRunner.Status.FAILURE, rejectedResult.status());
        assertEquals("RUNTIME.INVALID_INVOCATION", rejectedResult.failure().diagnostic().code());
        assertEquals(4_999, rejectedBodies.get());
        assertEquals(0, rejectedDone.get());
        assertEquals(Set.of(CONTROLLER, BODY), rejectedResult.nodeResults().keySet());
        assertTrue(rejectedResult.nodeResults().values().stream().allMatch(RuntimeResult::successful));
    }

    @Test
    void eachRealConversionHopConsumesOneUnitOfTheSameRootBudget() throws Exception {
        ConversionFixture accepted = new ConversionFixture(true);

        CompiledExecutionRunner.ExecutionResult acceptedResult = accepted.execute(CorrelationId.random(),
            System.currentTimeMillis() + 60_000, CompiledRuntimeContext.empty(), 3_332).get(15, TimeUnit.SECONDS);

        assertEquals(10_000, 1 + 3 * 3_332 + 1 + 2);
        assertEquals(CompiledExecutionRunner.Status.SUCCESS, acceptedResult.status());
        assertEquals(3_332, accepted.conversions.size());
        assertEquals(3_332, accepted.bodies.size());
        assertEquals(1, accepted.doneCalls.get());
        assertEquals(2, accepted.tailCalls.get());

        ConversionFixture rejected = new ConversionFixture(true);

        CompiledExecutionRunner.ExecutionResult rejectedResult = rejected.execute(CorrelationId.random(),
            System.currentTimeMillis() + 60_000, CompiledRuntimeContext.empty(), 3_333).get(15, TimeUnit.SECONDS);

        assertEquals(10_001, 1 + 3 * 3_333 + 1);
        assertEquals(CompiledExecutionRunner.Status.FAILURE, rejectedResult.status());
        assertEquals("RUNTIME.INVALID_INVOCATION", rejectedResult.failure().diagnostic().code());
        assertEquals(3_333, rejected.conversions.size());
        assertEquals(3_333, rejected.bodies.size());
        assertEquals(0, rejected.doneCalls.get());
        assertEquals(0, rejected.tailCalls.get());
        assertEquals(Set.of(CONTROLLER, BODY), rejectedResult.nodeResults().keySet());
        assertTrue(rejectedResult.nodeResults().values().stream().allMatch(RuntimeResult::successful));
    }

    @Test
    void orchestrationFailuresDoNotFabricateNodeRuntimeResults() throws Exception {
        LoopFixture cancelled = new LoopFixture(CompiledExecutionStep.LoopControl.Kind.COUNT, TypedValue.value(NUMBER, 1));
        RuntimeCancellationToken cancelledToken = new RuntimeCancellationToken();
        cancelledToken.cancel();

        CompiledExecutionRunner.ExecutionResult cancelledResult = cancelled.execute(cancelledToken, CorrelationId.random(),
            RuntimeExecutionContext.NO_DEADLINE, null).get(5, TimeUnit.SECONDS);

        assertEquals(CompiledExecutionRunner.Status.CANCELLED, cancelledResult.status());
        assertEquals("RUNTIME.CANCELLED", cancelledResult.failure().diagnostic().code());
        assertTrue(cancelledResult.nodeResults().isEmpty());
        assertEquals(RuntimeUnloadResult.Status.REMOVED, cancelled.registry.tryUnload(PROVIDER).status());

        LoopFixture timedOut = new LoopFixture(CompiledExecutionStep.LoopControl.Kind.COUNT, TypedValue.value(NUMBER, 1));

        CompiledExecutionRunner.ExecutionResult timeoutResult = timedOut.execute(new RuntimeCancellationToken(), CorrelationId.random(),
            System.currentTimeMillis(), null).get(5, TimeUnit.SECONDS);

        assertEquals(CompiledExecutionRunner.Status.FAILURE, timeoutResult.status());
        assertEquals("RUNTIME.EXECUTION_TIMEOUT", timeoutResult.failure().diagnostic().code());
        assertTrue(timeoutResult.nodeResults().isEmpty());
        assertEquals(RuntimeUnloadResult.Status.REMOVED, timedOut.registry.tryUnload(PROVIDER).status());

        LoopFixture invalid = new LoopFixture(CompiledExecutionStep.LoopControl.Kind.COUNT, TypedValue.value(NUMBER, -1));

        CompiledExecutionRunner.ExecutionResult invalidResult = invalid.execute().get(5, TimeUnit.SECONDS);

        assertEquals(CompiledExecutionRunner.Status.FAILURE, invalidResult.status());
        assertEquals("RUNTIME.INVALID_INVOCATION", invalidResult.failure().diagnostic().code());
        assertEquals(Set.of(CONTROLLER), invalidResult.nodeResults().keySet());
        assertTrue(invalidResult.nodeResults().get(CONTROLLER).successful());
        assertEquals(admission(invalid.controllerOperation), invalidResult.nodeResults().get(CONTROLLER));
        assertEquals(RuntimeUnloadResult.Status.REMOVED, invalid.registry.tryUnload(PROVIDER).status());
    }

    @Test
    void ordinaryDataOnlyAndSelectedBranchRoutingRemainUnchanged() throws Exception {
        NodeInstanceId dataSource = node(20);
        NodeInstanceId dataTarget = node(21);
        NodeInstanceId branchSource = node(22);
        NodeInstanceId selected = node(23);
        NodeInstanceId other = node(24);
        PinId output = PinId.of("output");
        PinId input = PinId.of("input");
        RuntimeOperationDescriptor dataSourceOperation = actionOperation("loop_count", List.of(pin(output, false, TEXT)));
        RuntimeOperationDescriptor dataTargetOperation = actionOperation("data-target", List.of(pin(input, true, TEXT)));
        RuntimeOperationDescriptor branchSourceOperation = actionOperation("branch-source", List.of(pin(output, false, EXECUTION)), Set.of("selected", "other"));
        RuntimeOperationDescriptor selectedOperation = actionOperation("selected", List.of(pin(FLOW, true, EXECUTION)));
        RuntimeOperationDescriptor otherOperation = actionOperation("other", List.of(pin(FLOW, true, EXECUTION)));
        List<String> invoked = new CopyOnWriteArrayList<>();
        AtomicReference<TypedValue> received = new AtomicReference<>();
        RuntimeBindingRegistry registry = registry(
            binding(dataSourceOperation, invocation -> {
                invoked.add("data-source");
                return CompletableFuture.completedFuture(RuntimeResult.success(Map.of(output, TypedValue.value(TEXT, "value")), null));
            }),
            binding(dataTargetOperation, invocation -> {
                invoked.add("data-target");
                received.set(invocation.inputs().get(input));
                return CompletableFuture.completedFuture(RuntimeResult.success());
            }),
            binding(branchSourceOperation, invocation -> {
                invoked.add("branch-source");
                return CompletableFuture.completedFuture(RuntimeResult.success(Map.of(output, executionToken()), "selected"));
            }),
            binding(selectedOperation, invocation -> {
                invoked.add("selected");
                return CompletableFuture.completedFuture(RuntimeResult.success());
            }),
            binding(otherOperation, invocation -> {
                invoked.add("other");
                return CompletableFuture.completedFuture(RuntimeResult.success());
            }));
        GraphEndpoint dataEndpoint = new GraphEndpoint(dataTarget, input);
        GraphEndpoint selectedEndpoint = new GraphEndpoint(selected, FLOW);
        GraphEndpoint otherEndpoint = new GraphEndpoint(other, FLOW);
        List<CompiledExecutionStep> steps = List.of(
            step(dataSource, dataSourceOperation, Map.of(), Map.of(output, List.of(dataEndpoint))),
            step(dataTarget, dataTargetOperation, Map.of(), Map.of()),
            step(branchSource, branchSourceOperation, Map.of(), Map.of(output, List.of(selectedEndpoint, otherEndpoint))),
            step(selected, selectedOperation, Map.of(), Map.of()),
            step(other, otherOperation, Map.of(), Map.of()));
        List<GraphConnection> connections = List.of(
            connection("data", dataSource, output, dataTarget, input),
            branchConnection("selected", branchSource, output, "selected", selected, FLOW),
            branchConnection("other", branchSource, output, "other", other, FLOW));

        CompiledExecutionRunner.ExecutionResult result = new CompiledExecutionRunner(registry, authority())
            .execute(plan(steps, connections)).toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertEquals(CompiledExecutionRunner.Status.SUCCESS, result.status());
        assertEquals(TypedValue.value(TEXT, "value"), received.get());
        assertTrue(invoked.containsAll(List.of("data-source", "data-target", "branch-source", "selected")));
        assertFalse(invoked.contains("other"));
    }

    private static final class LoopFixture {
        final RuntimeOperationDescriptor controllerOperation;
        final RuntimeOperationDescriptor bodyOperation;
        final RuntimeOperationDescriptor doneOperation;
        final AtomicReference<RuntimeOperationHandler> controller;
        final AtomicReference<RuntimeOperationHandler> body;
        final AtomicReference<RuntimeOperationHandler> done;
        final RuntimeBindingRegistry registry;
        final CompiledExecutionPlan plan;

        LoopFixture(CompiledExecutionStep.LoopControl.Kind kind, TypedValue source) {
            this(kind, source, RuntimeSemantics.Cancellation.COOPERATIVE, RuntimeSemantics.UnloadPolicy.DRAIN);
        }

        LoopFixture(CompiledExecutionStep.LoopControl.Kind kind, TypedValue source, RuntimeSemantics.Cancellation cancellation,
                    RuntimeSemantics.UnloadPolicy unloadPolicy) {
            controllerOperation = loopOperation(kind, cancellation, unloadPolicy);
            List<RuntimeOperationDescriptor.Pin> bodyPins = new ArrayList<>(List.of(pin(FLOW, true, EXECUTION), pin(INDEX, true, NUMBER)));
            if (kind == CompiledExecutionStep.LoopControl.Kind.FOR_EACH) {
                bodyPins.add(pin(ELEMENT, true, TEXT));
            }
            bodyOperation = actionOperation("body-" + kind.name().toLowerCase(), bodyPins, cancellation, unloadPolicy);
            doneOperation = actionOperation("done-" + kind.name().toLowerCase(),
                List.of(pin(FLOW, true, EXECUTION), pin(COMPLETED, true, BOOLEAN)), cancellation, unloadPolicy);
            controller = new AtomicReference<>(invocation -> CompletableFuture.completedFuture(admission(controllerOperation)));
            body = new AtomicReference<>(invocation -> CompletableFuture.completedFuture(RuntimeResult.success()));
            done = new AtomicReference<>(invocation -> CompletableFuture.completedFuture(RuntimeResult.success()));
            registry = registry(
                binding(controllerOperation, invocation -> controller.get().execute(invocation)),
                binding(bodyOperation, invocation -> body.get().execute(invocation)),
                binding(doneOperation, invocation -> done.get().execute(invocation)));
            Map<PinId, List<GraphEndpoint>> controllerOutputs = new LinkedHashMap<>();
            controllerOutputs.put(LOOP, List.of(new GraphEndpoint(BODY, FLOW)));
            controllerOutputs.put(DONE, List.of(new GraphEndpoint(FINAL, FLOW)));
            controllerOutputs.put(INDEX, List.of(new GraphEndpoint(BODY, INDEX)));
            if (kind == CompiledExecutionStep.LoopControl.Kind.FOR_EACH) {
                controllerOutputs.put(ELEMENT, List.of(new GraphEndpoint(BODY, ELEMENT)));
            }
            controllerOutputs.put(COMPLETED, List.of(new GraphEndpoint(FINAL, COMPLETED)));
            List<GraphConnection> connections = new ArrayList<>();
            connections.add(connection("body", CONTROLLER, LOOP, BODY, FLOW));
            connections.add(connection("index", CONTROLLER, INDEX, BODY, INDEX));
            if (kind == CompiledExecutionStep.LoopControl.Kind.FOR_EACH) {
                connections.add(connection("element", CONTROLLER, ELEMENT, BODY, ELEMENT));
            }
            connections.add(connection("done", CONTROLLER, DONE, FINAL, FLOW));
            connections.add(connection("completed", CONTROLLER, COMPLETED, FINAL, COMPLETED));
            plan = plan(List.of(
                loopStep(CONTROLLER, controllerOperation, source, List.of(BODY), controllerOutputs),
                step(BODY, bodyOperation, Map.of(), Map.of()),
                step(FINAL, doneOperation, Map.of(), Map.of())), connections);
        }

        CompletableFuture<CompiledExecutionRunner.ExecutionResult> execute() {
            return execute(new RuntimeCancellationToken(), CorrelationId.random(), RuntimeExecutionContext.NO_DEADLINE, null);
        }

        CompletableFuture<CompiledExecutionRunner.ExecutionResult> execute(RuntimeCancellationToken cancellation, CorrelationId root,
                                                                           long deadline, CompiledRuntimeContext context) {
            return new CompiledExecutionRunner(registry, authority()).execute(plan, null, Map.of(), cancellation, context, root, deadline)
                .toCompletableFuture();
        }
    }

    private static final class ConversionFixture {
        final List<RuntimeInvocation> invocations = new CopyOnWriteArrayList<>();
        final List<RuntimeInvocation> bodies = new CopyOnWriteArrayList<>();
        final List<RuntimeInvocation> conversions = new CopyOnWriteArrayList<>();
        final AtomicInteger doneCalls = new AtomicInteger();
        final AtomicInteger tailCalls = new AtomicInteger();
        final AtomicInteger activationReads = new AtomicInteger();
        final RuntimeBindingRegistry registry;
        final CatalogSnapshot catalog;
        final CompiledExecutionPlan plan;
        final CompiledExecutionRunner runner;

        ConversionFixture() {
            this(false);
        }

        ConversionFixture(boolean budgetTails) {
            RuntimeOperationDescriptor controllerOperation = loopOperation(CompiledExecutionStep.LoopControl.Kind.COUNT,
                RuntimeSemantics.Cancellation.COOPERATIVE, RuntimeSemantics.UnloadPolicy.DRAIN);
            RuntimeOperationDescriptor bodyOperation = actionOperation("converted-body",
                List.of(pin(FLOW, true, EXECUTION), pin(VALUE, true, CONVERTED_TEXT)));
            RuntimeOperationDescriptor doneOperation = actionOperation("converted-done",
                List.of(pin(FLOW, true, EXECUTION), pin(COMPLETED, true, BOOLEAN)));
            RuntimeOperationDescriptor conversionOperation = actionOperation("number-to-text",
                List.of(pin(CONVERT_INPUT, true, NUMBER), pin(CONVERT_OUTPUT, false, CONVERTED_TEXT)));
            RuntimeOperationDescriptor firstTailOperation = actionOperation("budget-tail-first", List.of());
            RuntimeOperationDescriptor secondTailOperation = actionOperation("budget-tail-second", List.of());
            registry = registry(
                binding(controllerOperation, invocation -> {
                    invocations.add(invocation);
                    return CompletableFuture.completedFuture(admission(controllerOperation));
                }),
                binding(bodyOperation, invocation -> {
                    invocations.add(invocation);
                    bodies.add(invocation);
                    return CompletableFuture.completedFuture(RuntimeResult.success());
                }),
                binding(doneOperation, invocation -> {
                    invocations.add(invocation);
                    doneCalls.incrementAndGet();
                    return CompletableFuture.completedFuture(RuntimeResult.success());
                }),
                binding(conversionOperation, invocation -> {
                    invocations.add(invocation);
                    conversions.add(invocation);
                    Number value = (Number) invocation.inputs().get(CONVERT_INPUT).value();
                    return CompletableFuture.completedFuture(RuntimeResult.success(
                        Map.of(CONVERT_OUTPUT, TypedValue.value(CONVERTED_TEXT, Integer.toString(value.intValue()))), null));
                }),
                binding(firstTailOperation, invocation -> {
                    invocations.add(invocation);
                    tailCalls.incrementAndGet();
                    return CompletableFuture.completedFuture(RuntimeResult.success());
                }),
                binding(secondTailOperation, invocation -> {
                    invocations.add(invocation);
                    tailCalls.incrementAndGet();
                    return CompletableFuture.completedFuture(RuntimeResult.success());
                }));
            ConversionGraph.ConversionEdge edge = new ConversionGraph.ConversionEdge(
                TypeReference.of(OWNER.value(), "number-to-text"), NUMBER, CONVERTED_TEXT, 1,
                ConversionGraph.Losslessness.LOSSLESS, ConversionGraph.FailureBehavior.DIAGNOSTIC,
                TEST_CAPABILITY, conversionOperation.operation());
            CatalogVersion version = new CatalogVersion(1, 0);
            List<RuntimeOperationDescriptor> operations = List.of(controllerOperation, bodyOperation, doneOperation,
                conversionOperation, firstTailOperation, secondTailOperation);
            CatalogContribution contribution = CatalogContribution.builder(OWNER, "1.0.0", new CatalogContractRange(version, version),
                    CatalogProvenance.fromText(CatalogProvenance.SourceKind.BUNDLED, "classpath:/loop-runtime-test", "1.0.0", "test", "loop-runtime"))
                .categories(List.of(new CatalogCategoryDescriptor("flow", "Flow", "Operations for deterministic compiled loop runtime tests.", 1)))
                .capabilities(List.of(
                    new CatalogCapabilityDescriptor(CapabilityId.of("flow.control"), 1, false, InspectorFallback.GENERIC),
                    new CatalogCapabilityDescriptor(CapabilityId.of("loop.runtime.test"), 1, false, InspectorFallback.GENERIC),
                    new CatalogCapabilityDescriptor(CapabilityId.of("generic-editor"), 1, false, InspectorFallback.READ_ONLY_FIELD)))
                .definitions(List.of(
                    definition("count-loop", controllerOperation),
                    definition("converted-body", bodyOperation),
                    definition("converted-done", doneOperation),
                    definition("budget-tail-first", firstTailOperation),
                    definition("budget-tail-second", secondTailOperation)))
                .runtimeRequirements(operations)
                .conversions(List.of(edge))
                .build();
            var compilation = new CatalogCompiler(version, CatalogBindingProof.live(registry)).compile(List.of(contribution), 1);
            assertTrue(compilation.accepted(), compilation.diagnostics()::toString);
            catalog = compilation.snapshot().orElseThrow();
            List<GraphNode> nodes = new ArrayList<>(List.of(
                new GraphNode(CONTROLLER, ContractRef.of(OWNER, NodeId.of("count-loop")), 1, Map.of()),
                new GraphNode(BODY, ContractRef.of(OWNER, NodeId.of("converted-body")), 1, Map.of()),
                new GraphNode(FINAL, ContractRef.of(OWNER, NodeId.of("converted-done")), 1, Map.of())));
            if (budgetTails) {
                nodes.add(new GraphNode(node(7), ContractRef.of(OWNER, NodeId.of("budget-tail-first")), 1, Map.of()));
                nodes.add(new GraphNode(node(8), ContractRef.of(OWNER, NodeId.of("budget-tail-second")), 1, Map.of()));
            }
            List<GraphConnection> connections = List.of(
                connection("compiled-body", CONTROLLER, LOOP, BODY, FLOW),
                connection("compiled-index", CONTROLLER, INDEX, BODY, VALUE),
                connection("compiled-done", CONTROLLER, DONE, FINAL, FLOW),
                connection("compiled-completed", CONTROLLER, COMPLETED, FINAL, COMPLETED));
            GraphDocument graph = new GraphDocument(RESOURCE, 1,
                CatalogBinding.of(catalog.generation(), catalog.contentChecksum(), catalog.bindingManifestHash()), nodes, connections);
            var compiled = new GraphCompiler(registry.snapshot().manifest()).compileResult(graph, catalog);
            assertTrue(compiled.compiled(), compiled.validation().diagnostics()::toString);
            plan = compiled.plan();
            CatalogRuntimeActivation.ActivationRecord activation = new CatalogRuntimeActivation.ActivationRecord(catalog, registry.snapshot());
            runner = new CompiledExecutionRunner(() -> {
                activationReads.incrementAndGet();
                return activation;
            }, registry, authority());
        }

        CompletableFuture<CompiledExecutionRunner.ExecutionResult> execute(CorrelationId root, long deadline, CompiledRuntimeContext context) {
            return execute(root, deadline, context, 2);
        }

        CompletableFuture<CompiledExecutionRunner.ExecutionResult> execute(CorrelationId root, long deadline,
                                                                           CompiledRuntimeContext context, int count) {
            return runner.execute(plan, null, Map.of(new GraphEndpoint(CONTROLLER, COUNT), TypedValue.value(NUMBER, count)),
                new RuntimeCancellationToken(), context, root, deadline).toCompletableFuture();
        }

        void clear() {
            invocations.clear();
            bodies.clear();
            conversions.clear();
            doneCalls.set(0);
            tailCalls.set(0);
        }
    }

    private static CompiledExecutionStep loopStep(NodeInstanceId nodeId, RuntimeOperationDescriptor operation, TypedValue source,
                                                  List<NodeInstanceId> bodySteps, Map<PinId, List<GraphEndpoint>> outputs) {
        CompiledExecutionStep step = step(nodeId, operation, Map.of(sourcePin(operation), source), outputs);
        CompiledExecutionStep.LoopControl.Kind kind = operation.operation().equals(LOOP_COUNT)
            ? CompiledExecutionStep.LoopControl.Kind.COUNT : CompiledExecutionStep.LoopControl.Kind.FOR_EACH;
        return step.withLoopControl(new CompiledExecutionStep.LoopControl(kind, FLOW, sourcePin(operation), LOOP, DONE, INDEX,
            kind == CompiledExecutionStep.LoopControl.Kind.FOR_EACH ? ELEMENT : null, COMPLETED,
            source.type(), kind == CompiledExecutionStep.LoopControl.Kind.FOR_EACH ? TEXT : null, bodySteps));
    }

    private static CompiledExecutionStep step(NodeInstanceId nodeId, RuntimeOperationDescriptor operation,
                                              Map<PinId, TypedValue> inputOverrides,
                                              Map<PinId, List<GraphEndpoint>> outputOverrides) {
        LinkedHashMap<PinId, TypedValue> inputs = new LinkedHashMap<>();
        operation.pins().stream().filter(pin -> pin.direction() == RuntimeOperationDescriptor.Direction.INPUT)
            .forEach(pin -> inputs.put(pin.id(), inputOverrides.getOrDefault(pin.id(), TypedValue.absent(pin.type()))));
        LinkedHashMap<PinId, List<GraphEndpoint>> outputs = new LinkedHashMap<>();
        operation.pins().stream().filter(pin -> pin.direction() == RuntimeOperationDescriptor.Direction.OUTPUT)
            .forEach(pin -> outputs.put(pin.id(), outputOverrides.getOrDefault(pin.id(), List.of())));
        UUID stepId = UUID.nameUUIDFromBytes(("loop-step:" + nodeId.canonicalText()).getBytes(StandardCharsets.UTF_8));
        return new CompiledExecutionStep(stepId, nodeId, ContractRef.of(OWNER, NodeId.of("node-" + nodeId.canonicalText())),
            new CatalogNodeDescriptor.Handler(operation.capability(), operation.operation()), inputs, outputs, operation.semantics(), OpaqueData.empty());
    }

    private static CompiledExecutionPlan plan(List<CompiledExecutionStep> steps, List<GraphConnection> connections) {
        return new CompiledExecutionPlan(UUID.fromString("22222222-2222-4222-8222-222222222222"), RESOURCE, 1,
            CATALOG_BINDING, hash("graph"), steps, connections, List.of(), List.of(), List.of(), List.of(), OpaqueData.empty());
    }

    private static RuntimeBindingRegistry registry(RuntimeBinding... bindings) {
        RuntimeBindingRegistry registry = RuntimeTestSupport.registry();
        RuntimeSemantics.UnloadPolicy unloadPolicy = bindings.length == 0
            ? RuntimeSemantics.UnloadPolicy.DRAIN : bindings[0].descriptor().semantics().unloadPolicy();
        registry.activate(new RuntimeProviderDescriptor(PROVIDER, "1.0.0", 0, 0, unloadPolicy), List.of(bindings));
        return registry;
    }

    private static RuntimeBinding binding(RuntimeOperationDescriptor operation, RuntimeOperationHandler handler) {
        return RuntimeBinding.available(operation, PROVIDER, "1.0.0", handler);
    }

    private static RuntimeOperationDescriptor loopOperation(CompiledExecutionStep.LoopControl.Kind kind,
                                                            RuntimeSemantics.Cancellation cancellation,
                                                            RuntimeSemantics.UnloadPolicy unloadPolicy) {
        List<RuntimeOperationDescriptor.Pin> pins = new ArrayList<>();
        pins.add(pin(FLOW, true, EXECUTION));
        pins.add(pin(kind == CompiledExecutionStep.LoopControl.Kind.COUNT ? COUNT : LIST, true,
            kind == CompiledExecutionStep.LoopControl.Kind.COUNT ? NUMBER : LIST_TEXT));
        pins.add(pin(LOOP, false, EXECUTION));
        pins.add(pin(DONE, false, EXECUTION));
        pins.add(pin(INDEX, false, NUMBER));
        if (kind == CompiledExecutionStep.LoopControl.Kind.FOR_EACH) {
            pins.add(pin(ELEMENT, false, TEXT));
        }
        pins.add(pin(COMPLETED, false, BOOLEAN));
        return new RuntimeOperationDescriptor(LOOP_CAPABILITY,
            kind == CompiledExecutionStep.LoopControl.Kind.COUNT ? LOOP_COUNT : LOOP_FOR_EACH,
            pins, semantics(cancellation, unloadPolicy, Set.of()));
    }

    private static RuntimeOperationDescriptor actionOperation(String id, List<RuntimeOperationDescriptor.Pin> pins) {
        return actionOperation(id, pins, RuntimeSemantics.Cancellation.COOPERATIVE, RuntimeSemantics.UnloadPolicy.DRAIN);
    }

    private static RuntimeOperationDescriptor actionOperation(String id, List<RuntimeOperationDescriptor.Pin> pins,
                                                              Set<String> successBranches) {
        return new RuntimeOperationDescriptor(TEST_CAPABILITY, ContractRef.of(OWNER, OperationId.of(id)), pins,
            semantics(RuntimeSemantics.Cancellation.COOPERATIVE, RuntimeSemantics.UnloadPolicy.DRAIN, successBranches));
    }

    private static RuntimeOperationDescriptor actionOperation(String id, List<RuntimeOperationDescriptor.Pin> pins,
                                                              RuntimeSemantics.Cancellation cancellation,
                                                              RuntimeSemantics.UnloadPolicy unloadPolicy) {
        return new RuntimeOperationDescriptor(TEST_CAPABILITY, ContractRef.of(OWNER, OperationId.of(id)), pins,
            semantics(cancellation, unloadPolicy, Set.of()));
    }

    private static RuntimeSemantics semantics(RuntimeSemantics.Cancellation cancellation, RuntimeSemantics.UnloadPolicy unloadPolicy,
                                              Set<String> successBranches) {
        return new RuntimeSemantics(RuntimeSemantics.Effect.PURE, RuntimeSemantics.ThreadMode.CURRENT, TEST_CAPABILITY,
            cancellation, 0, 0, 0, unloadPolicy, RuntimeSemantics.Retry.NEVER, RuntimeSemantics.Idempotency.INTRINSIC,
            RuntimeSemantics.Audit.NONE, RuntimeSemantics.Confirmation.NONE, RuntimeSemantics.SensitiveData.NONE,
            RuntimeSemantics.Determinism.DETERMINISTIC, successBranches, Set.of("failed"),
            cancellation == RuntimeSemantics.Cancellation.NONE ? Set.of() : Set.of("cancelled"),
            new RuntimeFailureContract(TEXT, Set.of("RUNTIME.HANDLER_FAILURE"), Set.of("failed"),
                RuntimeFailureContract.CommitBoundary.NO_MUTATION), Set.of(), Set.of());
    }

    private static RuntimeResult admission(RuntimeOperationDescriptor operation) {
        LinkedHashMap<PinId, TypedValue> outputs = new LinkedHashMap<>();
        operation.pins().stream().filter(pin -> pin.direction() == RuntimeOperationDescriptor.Direction.OUTPUT)
            .forEach(pin -> outputs.put(pin.id(), TypedValue.absent(pin.type())));
        return RuntimeResult.success(outputs, null);
    }

    private static CatalogNodeDescriptor definition(String id, RuntimeOperationDescriptor operation) {
        return CatalogNodeDescriptor.builder(NodeId.of(id))
            .domain("flow")
            .family("test")
            .displayName("Loop Runtime Test")
            .description("Executes one deterministic compiled loop runtime test operation.")
            .category(ContractRef.of(OWNER, CapabilityId.of("flow")))
            .pins(operation.pins().stream().map(pin -> new CatalogNodeDescriptor.Pin(pin.id(),
                pin.direction() == RuntimeOperationDescriptor.Direction.INPUT
                    ? CatalogNodeDescriptor.Direction.INPUT : CatalogNodeDescriptor.Direction.OUTPUT,
                pin.type(), "Test Pin", "Carries one exact typed loop runtime test value.",
                CatalogNodeDescriptor.Requirement.REQUIRED, null, EDITOR, null, null, null)).toList())
            .branches(List.of(
                new CatalogNodeDescriptor.Branch("failed", "Failed", "Reports a structured test operation failure.",
                    List.of(new CatalogNodeDescriptor.Case("failure", "Failure", "The operation returned a structured failure."))),
                new CatalogNodeDescriptor.Branch("cancelled", "Cancelled", "Reports cooperative test operation cancellation.",
                    List.of(new CatalogNodeDescriptor.Case("cancelled", "Cancelled", "The operation stopped after cancellation.")))))
            .handler(new CatalogNodeDescriptor.Handler(operation.capability(), operation.operation()))
            .semantics(operation.semantics())
            .requiredCapabilities(Set.of(operation.capability()))
            .build();
    }

    private static RuntimeOperationDescriptor.Pin pin(PinId id, boolean input, TypeExpr type) {
        return new RuntimeOperationDescriptor.Pin(id,
            input ? RuntimeOperationDescriptor.Direction.INPUT : RuntimeOperationDescriptor.Direction.OUTPUT, type);
    }

    private static PinId sourcePin(RuntimeOperationDescriptor operation) {
        return operation.operation().equals(LOOP_COUNT) ? COUNT : LIST;
    }

    private static int index(RuntimeInvocation invocation) {
        return ((Number) invocation.inputs().get(INDEX).value()).intValue();
    }

    private static GraphConnection connection(String id, NodeInstanceId source, PinId sourcePin,
                                              NodeInstanceId target, PinId targetPin) {
        return new GraphConnection(ConnectionId.deterministic("loop-runtime:" + id),
            new GraphEndpoint(source, sourcePin), new GraphEndpoint(target, targetPin));
    }

    private static GraphConnection branchConnection(String id, NodeInstanceId source, PinId sourcePin, String branch,
                                                    NodeInstanceId target, PinId targetPin) {
        return new GraphConnection(ConnectionId.deterministic("loop-runtime:" + id),
            new GraphEndpoint(source, sourcePin, null, BranchId.of(branch)), new GraphEndpoint(target, targetPin));
    }

    private static RuntimeAuthority authority() {
        return new RuntimeAuthority("test-authority");
    }

    private static RuntimeFailure failure() {
        return new RuntimeFailure(Diagnostic.builder(
                "RUNTIME.HANDLER_FAILURE", DiagnosticSeverity.ERROR, DiagnosticPhase.ENVIRONMENT, "execution")
            .messageKey(ContractRef.of(OWNER, CapabilityId.of("runtime-handler-failure")))
            .message("The loop body failed during the focused runtime test.")
            .remediation("Inspect the correlated loop body failure.")
            .correlationId(UUID.fromString("33333333-3333-4333-8333-333333333333"))
            .build(), false, TypedValue.nullValue(TEXT));
    }

    private static TypedValue executionToken() {
        return TypedValue.value(EXECUTION, true);
    }

    private static NodeInstanceId node(int value) {
        return NodeInstanceId.of(new UUID(0x4000L, 0x8000000000000000L | value));
    }

    private static ContentHash hash(String value) {
        return ContentHash.of(CanonicalJson.sha256("compiled-loop-runtime-test", Map.of("value", value)));
    }
}
