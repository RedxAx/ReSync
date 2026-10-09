package restudio.resync.flow.runtime;

import org.junit.jupiter.api.Test;
import restudio.resync.flow.graph.GraphEndpoint;
import restudio.resync.flow.identity.BranchId;
import restudio.resync.flow.identity.NodeInstanceId;
import restudio.resync.flow.diagnostic.Diagnostic;
import restudio.resync.flow.diagnostic.DiagnosticPhase;
import restudio.resync.flow.diagnostic.DiagnosticSeverity;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.OperationId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.PinId;
import restudio.resync.flow.identity.ProviderId;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypeReference;
import restudio.resync.flow.type.TypedValue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RuntimePlanLeaseInputSnapshotTest {
    private static final PinId INPUT = PinId.of("input");
    private static final PinId OUTPUT = PinId.of("output");

    @Test
    void callerMutationCannotChangeRetryExecutionOrDurableOutcome() throws Exception {
        RuntimeBindingRegistry registry = RuntimeTestSupport.registry();
        ContractRef<ProviderId> provider = provider("input-snapshot");
        RuntimeOperationDescriptor operation = operation();
        CompletableFuture<RuntimeResult> firstAttempt = new CompletableFuture<>();
        AtomicInteger calls = new AtomicInteger();
        List<TypedValue> observedInputs = new ArrayList<>();
        List<Map<GraphEndpoint, TypedValue>> observedEndpoints = new ArrayList<>();
        RuntimeBinding binding = RuntimeBinding.available(operation, provider, "1.0.0", invocation -> {
            TypedValue value = invocation.inputs().get(INPUT);
            observedInputs.add(value);
            observedEndpoints.add(invocation.routedInputs());
            if (calls.incrementAndGet() == 1) {
                return firstAttempt;
            }
            return CompletableFuture.completedFuture(RuntimeResult.success(Map.of(OUTPUT, value), "success"));
        });
        registry.activate(providerDescriptor(provider), List.of(binding));
        RuntimePlanLease lease = registry.acquire(RuntimeTestSupport.input(requirement(operation, binding)));
        TypedValue original = value("original");
        TypedValue changed = value("changed");
        Map<PinId, TypedValue> callerInputs = new HashMap<>();
        callerInputs.put(INPUT, original);

        GraphEndpoint endpoint = new GraphEndpoint(NodeInstanceId.deterministic("snapshot-target"), INPUT, null, BranchId.of("selected"));
        Map<GraphEndpoint, TypedValue> callerEndpoints = new HashMap<>(Map.of(endpoint, original));
        RuntimeInvocation invocation = new RuntimeInvocation(operation.key(), callerInputs, "mutation-1", new RuntimeCancellationToken())
            .withRoutedInputs(callerEndpoints);
        CompletionStage<RuntimeResult> pending = lease.execute(invocation);
        callerInputs.put(INPUT, changed);
        callerEndpoints.put(endpoint, changed);
        firstAttempt.complete(RuntimeResult.failure(new RuntimeFailure(
            diagnostic("RUNTIME.HANDLER_FAILURE"), true, TypedValue.value(type("failure"), "retry"))));

        RuntimeResult result = pending.toCompletableFuture().get(2, TimeUnit.SECONDS);
        RuntimeResult replay = lease.execute(invocation).toCompletableFuture().get(2, TimeUnit.SECONDS);
        RuntimeResult conflict = lease.execute(invocation.withRoutedInputs(Map.of(
            new GraphEndpoint(endpoint.nodeId(), INPUT, null, BranchId.of("other")), original)))
            .toCompletableFuture().get(2, TimeUnit.SECONDS);

        assertEquals(RuntimeResult.Status.SUCCESS, result.status());
        assertEquals(original, result.outputs().get(OUTPUT));
        assertEquals(List.of(original, original), observedInputs);
        assertEquals(List.of(Map.of(endpoint, original), Map.of(endpoint, original)), observedEndpoints);
        assertEquals(result, replay);
        assertEquals(RuntimeResult.Status.FAILURE, conflict.status());
        assertEquals("RUNTIME.INVALID_INVOCATION", conflict.failure().diagnostic().code());
        assertEquals(2, calls.get());
        lease.close();
    }

    @Test
    void ephemeralExecuteCopiesInputsAndCompletesWithoutDurableReceipts() throws Exception {
        RuntimeBindingRegistry registry = RuntimeTestSupport.registry();
        ContractRef<ProviderId> provider = provider("ephemeral-snapshot");
        RuntimeOperationDescriptor operation = ephemeralOperation();
        CompletableFuture<RuntimeResult> pending = new CompletableFuture<>();
        AtomicInteger calls = new AtomicInteger();
        List<TypedValue> observedInputs = new ArrayList<>();
        RuntimeBinding binding = RuntimeBinding.available(operation, provider, "1.0.0", invocation -> {
            TypedValue value = invocation.inputs().get(INPUT);
            observedInputs.add(value);
            calls.incrementAndGet();
            return pending;
        });
        registry.activate(providerDescriptor(provider), List.of(binding));
        RuntimePlanLease lease = registry.acquire(RuntimeTestSupport.input(requirement(operation, binding)));
        TypedValue original = value("original");
        TypedValue changed = value("changed");
        Map<PinId, TypedValue> callerInputs = new HashMap<>();
        callerInputs.put(INPUT, original);

        CompletionStage<RuntimeResult> started = lease.execute(operation.key(), callerInputs, "ephemeral-1");
        callerInputs.put(INPUT, changed);
        pending.complete(RuntimeResult.success(Map.of(OUTPUT, original), null));

        RuntimeResult result = started.toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertEquals(RuntimeResult.Status.SUCCESS, result.status());
        assertEquals(original, observedInputs.getFirst());
        assertEquals(1, calls.get());
        lease.close();
    }

    @Test
    void nullInputIsRejectedBeforeInvocationAdmission() {
        RuntimeBindingRegistry registry = RuntimeTestSupport.registry();
        ContractRef<ProviderId> provider = provider("null-input");
        RuntimeOperationDescriptor operation = operation();
        RuntimeBinding binding = RuntimeBinding.available(operation, provider, "1.0.0", invocation ->
            CompletableFuture.completedFuture(RuntimeResult.success(Map.of(OUTPUT, invocation.inputs().get(INPUT)), "success")));
        registry.activate(providerDescriptor(provider), List.of(binding));
        RuntimePlanLease lease = registry.acquire(RuntimeTestSupport.input(requirement(operation, binding)));
        Map<PinId, TypedValue> callerInputs = new HashMap<>();
        callerInputs.put(INPUT, null);

        NullPointerException failure = assertThrows(NullPointerException.class,
            () -> lease.execute(operation.key(), callerInputs, "mutation-1"));

        assertEquals("Input Value Cannot Be Null", failure.getMessage());
        assertEquals(0, lease.activeInvocations());
        lease.close();
    }

    private static RuntimeLeaseInput.BindingRequirement requirement(
        RuntimeOperationDescriptor operation,
        RuntimeBinding binding
    ) {
        return new RuntimeLeaseInput.BindingRequirement(
            operation.key(), binding.executionFingerprint(), List.of(INPUT), List.of(OUTPUT));
    }

    private static RuntimeOperationDescriptor operation() {
        return new RuntimeOperationDescriptor(
            capability("input-snapshot"),
            operationId("input-snapshot"),
            List.of(
                new RuntimeOperationDescriptor.Pin(INPUT, RuntimeOperationDescriptor.Direction.INPUT, type("string")),
                new RuntimeOperationDescriptor.Pin(OUTPUT, RuntimeOperationDescriptor.Direction.OUTPUT, type("string"))),
            new RuntimeSemantics(
                RuntimeSemantics.Effect.STATE_MUTATING,
                RuntimeSemantics.ThreadMode.CURRENT,
                capability("authorization"),
                RuntimeSemantics.Cancellation.COOPERATIVE,
                0,
                0,
                0,
                RuntimeSemantics.UnloadPolicy.DRAIN,
                RuntimeSemantics.Retry.SAFE,
                RuntimeSemantics.Idempotency.MUTATION_ID,
                RuntimeSemantics.Audit.METADATA,
                RuntimeSemantics.Confirmation.NONE,
                RuntimeSemantics.SensitiveData.NONE,
                RuntimeSemantics.Determinism.DETERMINISTIC,
                Set.of("success"),
                Set.of("failure"),
                Set.of("cancelled"),
                new RuntimeFailureContract(
                    type("failure"),
                    Set.of("RUNTIME.HANDLER_FAILURE"),
                    Set.of("failure"),
                    RuntimeFailureContract.CommitBoundary.ATOMIC),
                Set.of(),
                Set.of()));
    }

    private static RuntimeOperationDescriptor ephemeralOperation() {
        return new RuntimeOperationDescriptor(
            capability("ephemeral-snapshot"),
            operationId("ephemeral-snapshot"),
            List.of(
                new RuntimeOperationDescriptor.Pin(INPUT, RuntimeOperationDescriptor.Direction.INPUT, type("string")),
                new RuntimeOperationDescriptor.Pin(OUTPUT, RuntimeOperationDescriptor.Direction.OUTPUT, type("string"))),
            new RuntimeSemantics(
                RuntimeSemantics.Effect.PURE,
                RuntimeSemantics.ThreadMode.CURRENT,
                capability("authorization"),
                RuntimeSemantics.Cancellation.NONE,
                0,
                0,
                0,
                RuntimeSemantics.UnloadPolicy.DRAIN,
                RuntimeSemantics.Retry.NEVER,
                RuntimeSemantics.Idempotency.NONE,
                RuntimeSemantics.Audit.NONE,
                RuntimeSemantics.Confirmation.NONE,
                RuntimeSemantics.SensitiveData.NONE,
                RuntimeSemantics.Determinism.DETERMINISTIC,
                Set.of(),
                Set.of("failure"),
                Set.of(),
                new RuntimeFailureContract(
                    type("failure"),
                    Set.of("RUNTIME.HANDLER_FAILURE"),
                    Set.of("failure"),
                    RuntimeFailureContract.CommitBoundary.NO_MUTATION),
                Set.of(),
                Set.of()));
    }

    private static RuntimeProviderDescriptor providerDescriptor(ContractRef<ProviderId> provider) {
        return new RuntimeProviderDescriptor(provider, "1.0.0", 0, 50, RuntimeSemantics.UnloadPolicy.DRAIN);
    }

    private static ContractRef<CapabilityId> capability(String id) {
        return new ContractRef<>(new OwnerId("restudio.resync"), new CapabilityId(id));
    }

    private static ContractRef<OperationId> operationId(String id) {
        return new ContractRef<>(new OwnerId("restudio.resync"), new OperationId(id));
    }

    private static ContractRef<ProviderId> provider(String id) {
        return new ContractRef<>(new OwnerId("restudio.resync"), new ProviderId(id));
    }

    private static TypeExpr type(String id) {
        return TypeExpr.named(TypeReference.of("restudio.resync", id));
    }

    private static TypedValue value(String value) {
        return TypedValue.value(type("string"), value);
    }

    private static Diagnostic diagnostic(String code) {
        return Diagnostic.builder(code, DiagnosticSeverity.ERROR, DiagnosticPhase.ENVIRONMENT, "execution")
            .messageKey(new ContractRef<>(new OwnerId("restudio.resync"), new CapabilityId("runtime")))
            .message("Runtime Failure")
            .remediation("Retry the operation.")
            .correlationId(UUID.randomUUID())
            .build();
    }
}
