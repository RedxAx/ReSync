package restudio.resync.flow.runtime;

import org.junit.jupiter.api.Test;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContractRef;
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
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RuntimeDurabilityTest {
    @Test
    void structuredResultsAreImmutableAndTyped() {
        PinId output = PinId.of("output");
        RuntimeResult result = RuntimeResult.success(Map.of(output, value("string", "done")), "success");

        assertEquals("success", result.branch());
        assertEquals(value("string", "done"), result.outputs().get(output));
        assertThrows(UnsupportedOperationException.class, () -> result.outputs().put(output, value("string", "changed")));
    }

    @Test
    void blockedProviderUnloadRestoresExistingPlanLease() {
        ContractRef<CapabilityId> capability = capability("capability");
        ContractRef<OperationId> operation = operation("operation");
        ContractRef<ProviderId> provider = provider("provider");
        RuntimeOperationDescriptor descriptor = new RuntimeOperationDescriptor(
            capability,
            operation,
            List.of(new RuntimeOperationDescriptor.Pin("output", RuntimeOperationDescriptor.Direction.OUTPUT, type("string"))),
            semantics());
        RuntimeBinding binding = RuntimeBinding.available(descriptor, provider, "1.0.0",
            ignored -> CompletableFuture.completedFuture(RuntimeResult.success(value("string", "ok"))));
        RuntimeBindingRegistry registry = RuntimeTestSupport.registry();
        registry.activate(new RuntimeProviderDescriptor(provider, "1.0.0", 0, 0, RuntimeSemantics.UnloadPolicy.BLOCK), List.of(binding));
        RuntimePlanLease lease = registry.acquire(RuntimeTestSupport.input(new RuntimeLeaseInput.BindingRequirement(
            descriptor.key(), binding.descriptor().fingerprint(), List.of(), List.of(PinId.of("output")))));

        RuntimeUnloadResult unload = registry.unload(provider);

        assertEquals(RuntimeUnloadResult.Status.BLOCKED, unload.status());
        assertEquals(RuntimeProviderState.ACTIVE, registry.snapshot().provider(provider).orElseThrow().state());
        assertEquals(RuntimeResult.Status.SUCCESS, lease.execute(descriptor.key(), Map.of(), "mutation-1")
            .toCompletableFuture().join().status());
        lease.close();
    }

    private static RuntimeSemantics semantics() {
        return new RuntimeSemantics(
            RuntimeSemantics.Effect.PURE,
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
            new RuntimeFailureContract(type("failure"), Set.of("RUNTIME.FAILURE"), Set.of("failure"), RuntimeFailureContract.CommitBoundary.NO_MUTATION),
            Set.of(),
            Set.of());
    }

    private static TypedValue value(String localId, String value) {
        return TypedValue.value(type(localId), value);
    }

    private static TypeExpr type(String localId) {
        return TypeExpr.named(TypeReference.of("test", localId));
    }

    private static ContractRef<CapabilityId> capability(String localId) {
        return ContractRef.of(new OwnerId("test"), CapabilityId.of(localId));
    }

    private static ContractRef<OperationId> operation(String localId) {
        return ContractRef.of(new OwnerId("test"), OperationId.of(localId));
    }

    private static ContractRef<ProviderId> provider(String localId) {
        return ContractRef.of(new OwnerId("test"), ProviderId.of(localId));
    }
}
