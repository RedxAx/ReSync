package restudio.resync.flow.runtime;

import org.junit.jupiter.api.Test;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.CorrelationId;
import restudio.resync.flow.identity.OperationId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.PinId;
import restudio.resync.flow.identity.ProviderId;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypeReference;
import restudio.resync.flow.type.TypedValue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimePlanLeaseContextPropagationTest {
    @Test
    void compiledRuntimeContextReachesTheHandler() {
        RuntimeOperationDescriptor operation = operation();
        ContractRef<ProviderId> provider = provider();
        AtomicReference<CompiledRuntimeContext> observed = new AtomicReference<>();
        AtomicReference<CorrelationId> observedInvocation = new AtomicReference<>();
        RuntimeBinding binding = RuntimeBinding.available(operation, provider, "1.0.0", invocation -> {
            observed.set(invocation.runtimeContext());
            observedInvocation.set(invocation.invocationId());
            return CompletableFuture.completedFuture(RuntimeResult.success(TypedValue.value(type("string"), "ok")));
        });
        RuntimeBindingRegistry registry = RuntimeTestSupport.registry();
        registry.activate(new RuntimeProviderDescriptor(provider, "1.0.0", 0, 100, RuntimeSemantics.UnloadPolicy.DRAIN), List.of(binding));
        RuntimePlanLease lease = registry.acquire(RuntimeTestSupport.input(new RuntimeLeaseInput.BindingRequirement(
            operation.key(), binding.executionFingerprint(), List.of(), List.of(PinId.of("output")))));
        CompiledRuntimeContext context = new CompiledRuntimeContext(
            new CompiledRuntimeContext.PlayerIdentity(UUID.fromString("11111111-1111-4111-8111-111111111111"), "Player"),
            null,
            Map.of());
        CorrelationId invocationId = CorrelationId.of(UUID.fromString("22222222-2222-4222-8222-222222222222"));

        try {
            RuntimeResult result = lease.execute(new RuntimeInvocation(
                operation.key(), Map.of(), "context-propagation", new RuntimeCancellationToken())
                .withRuntimeContext(context)
                .withInvocationId(invocationId)).toCompletableFuture().join();

            assertTrue(result.successful());
            assertSame(context, observed.get());
            assertEquals(invocationId, observedInvocation.get());
        } finally {
            lease.close();
        }
    }

    private static RuntimeOperationDescriptor operation() {
        return new RuntimeOperationDescriptor(
            capability("context"),
            operationId("context"),
            List.of(new RuntimeOperationDescriptor.Pin("output", RuntimeOperationDescriptor.Direction.OUTPUT, type("string"))),
            new RuntimeSemantics(
                RuntimeSemantics.Effect.STATE_MUTATING,
                RuntimeSemantics.ThreadMode.CURRENT,
                capability("authorization"),
                RuntimeSemantics.Cancellation.NONE,
                0,
                0,
                0,
                RuntimeSemantics.UnloadPolicy.DRAIN,
                RuntimeSemantics.Retry.NEVER,
                RuntimeSemantics.Idempotency.INTRINSIC,
                RuntimeSemantics.Audit.NONE,
                RuntimeSemantics.Confirmation.NONE,
                RuntimeSemantics.SensitiveData.NONE,
                RuntimeSemantics.Determinism.DETERMINISTIC,
                Set.of("success"),
                Set.of("failure"),
                Set.of(),
                new RuntimeFailureContract(
                    type("failure"),
                    Set.of("RUNTIME.HANDLER_FAILURE"),
                    Set.of("failure"),
                    RuntimeFailureContract.CommitBoundary.ATOMIC),
                Set.of(),
                Set.of()));
    }

    private static ContractRef<CapabilityId> capability(String id) {
        return ContractRef.of(new OwnerId("restudio.resync"), new CapabilityId(id));
    }

    private static ContractRef<OperationId> operationId(String id) {
        return ContractRef.of(new OwnerId("restudio.resync"), new OperationId(id));
    }

    private static ContractRef<ProviderId> provider() {
        return ContractRef.of(new OwnerId("restudio.resync"), new ProviderId("context-provider"));
    }

    private static TypeExpr type(String id) {
        return TypeExpr.named(TypeReference.of("restudio.resync", id));
    }
}
