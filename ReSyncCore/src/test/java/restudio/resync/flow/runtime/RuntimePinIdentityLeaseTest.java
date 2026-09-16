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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RuntimePinIdentityLeaseTest {
    private static final OwnerId OWNER = OwnerId.of("restudio.resync");
    private static final PinId INPUT = PinId.of("value");
    private static final PinId OUTPUT = PinId.of("result");

    @Test
    void replacementRejectsSameTypeDifferentPinId() {
        RuntimeBindingRegistry registry = RuntimeTestSupport.registry();
        ContractRef<ProviderId> oldProvider = provider("old-id");
        ContractRef<ProviderId> newProvider = provider("new-id");
        RuntimeOperationDescriptor operation = operation(List.of(
            pin(INPUT, RuntimeOperationDescriptor.Direction.INPUT),
            pin(OUTPUT, RuntimeOperationDescriptor.Direction.OUTPUT)));
        RuntimeOperationDescriptor replacementOperation = operation(List.of(
            pin("alias", RuntimeOperationDescriptor.Direction.INPUT),
            pin(OUTPUT, RuntimeOperationDescriptor.Direction.OUTPUT)));
        registry.activate(provider(oldProvider), List.of(binding(operation, oldProvider)));

        assertThrows(IllegalArgumentException.class, () -> registry.prepareReplacement(
            List.of(new RuntimeBindingRegistry.RuntimeProviderContribution(
                provider(newProvider), List.of(binding(replacementOperation, newProvider)))),
            Set.of(oldProvider)));
    }

    @Test
    void replacementRejectsSwappedPinIdsAndDirections() {
        RuntimeBindingRegistry registry = RuntimeTestSupport.registry();
        ContractRef<ProviderId> oldProvider = provider("old-order");
        ContractRef<ProviderId> newProvider = provider("new-order");
        RuntimeOperationDescriptor operation = operation(List.of(
            pin(INPUT, RuntimeOperationDescriptor.Direction.INPUT),
            pin("other", RuntimeOperationDescriptor.Direction.INPUT),
            pin(OUTPUT, RuntimeOperationDescriptor.Direction.OUTPUT)));
        RuntimeOperationDescriptor swapped = operation(List.of(
            pin("other", RuntimeOperationDescriptor.Direction.INPUT),
            pin(INPUT, RuntimeOperationDescriptor.Direction.INPUT),
            pin(OUTPUT, RuntimeOperationDescriptor.Direction.OUTPUT)));
        registry.activate(provider(oldProvider), List.of(binding(operation, oldProvider)));

        assertThrows(IllegalArgumentException.class, () -> registry.prepareReplacement(
            List.of(new RuntimeBindingRegistry.RuntimeProviderContribution(
                provider(newProvider), List.of(binding(swapped, newProvider)))),
            Set.of(oldProvider)));

        RuntimeOperationDescriptor directionChanged = operation(List.of(
            pin(INPUT, RuntimeOperationDescriptor.Direction.OUTPUT),
            pin("other", RuntimeOperationDescriptor.Direction.INPUT),
            pin(OUTPUT, RuntimeOperationDescriptor.Direction.INPUT)));
        assertThrows(IllegalArgumentException.class, () -> registry.prepareReplacement(
            List.of(new RuntimeBindingRegistry.RuntimeProviderContribution(
                provider(newProvider), List.of(binding(directionChanged, newProvider)))),
            Set.of(oldProvider)));
    }

    @Test
    void leaseRejectsAliasesSwappedIdsAndWrongDirections() {
        RuntimeBindingRegistry registry = RuntimeTestSupport.registry();
        ContractRef<ProviderId> providerId = provider("lease-pins");
        RuntimeOperationDescriptor operation = operation(List.of(
            pin(INPUT, RuntimeOperationDescriptor.Direction.INPUT),
            pin("other", RuntimeOperationDescriptor.Direction.INPUT),
            pin(OUTPUT, RuntimeOperationDescriptor.Direction.OUTPUT)));
        RuntimeBinding binding = binding(operation, providerId);
        registry.activate(provider(providerId), List.of(binding));

        assertThrows(RuntimeCapabilityUnavailableException.class, () -> registry.acquire(input(
            binding, List.of(PinId.of("alias"), PinId.of("other")), List.of(OUTPUT))));
        assertThrows(RuntimeCapabilityUnavailableException.class, () -> registry.acquire(input(
            binding, List.of(PinId.of("other"), INPUT), List.of(OUTPUT))));
        assertThrows(RuntimeCapabilityUnavailableException.class, () -> registry.acquire(input(
            binding, List.of(INPUT, PinId.of("other"), OUTPUT), List.of())));
    }

    @Test
    void exactPinReplayIsDurableAndResultAliasesAreRejected() throws Exception {
        RuntimeBindingRegistry registry = RuntimeTestSupport.registry();
        ContractRef<ProviderId> providerId = provider("replay-pins");
        AtomicInteger calls = new AtomicInteger();
        RuntimeOperationDescriptor operation = operation(List.of(
            pin(INPUT, RuntimeOperationDescriptor.Direction.INPUT),
            pin(OUTPUT, RuntimeOperationDescriptor.Direction.OUTPUT)));
        RuntimeBinding binding = new RuntimeBinding(
            new RuntimeBindingDescriptor(operation, providerId, "1.0.0", true),
            ignored -> {
                calls.incrementAndGet();
                return CompletableFuture.completedFuture(RuntimeResult.success(
                    Map.of(OUTPUT, value("done")), null));
            });
        registry.activate(provider(providerId), List.of(binding));
        RuntimePlanLease lease = registry.acquire(input(binding, List.of(INPUT), List.of(OUTPUT)));

        RuntimeResult first = lease.execute(operation.key(), Map.of(INPUT, value("input")), "exact-replay")
            .toCompletableFuture().get(5, TimeUnit.SECONDS);
        RuntimeResult replay = lease.execute(operation.key(), Map.of(INPUT, value("input")), "exact-replay")
            .toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertEquals(RuntimeResult.Status.SUCCESS, first.status());
        assertEquals(first.canonicalJson(), replay.canonicalJson());
        assertEquals(1, calls.get());

        RuntimeResult alias = lease.execute(operation.key(), Map.of(PinId.of("alias"), value("input")), "alias")
            .toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertEquals(RuntimeResult.Status.FAILURE, alias.status());
        assertEquals("RUNTIME.INVALID_INVOCATION", alias.failure().diagnostic().code());

        RuntimeBinding badResultBinding = new RuntimeBinding(
            new RuntimeBindingDescriptor(operation, provider("bad-result"), "1.0.0", true),
            ignored -> CompletableFuture.completedFuture(RuntimeResult.success(
                Map.of(PinId.of("result-alias"), value("done")), null)));
        RuntimeBindingRegistry badResultRegistry = RuntimeTestSupport.registry();
        ContractRef<ProviderId> badResultProvider = provider("bad-result");
        badResultRegistry.activate(provider(badResultProvider), List.of(badResultBinding));
        RuntimePlanLease badResultLease = badResultRegistry.acquire(
            input(badResultBinding, List.of(INPUT), List.of(OUTPUT)));
        RuntimeResult badResult = badResultLease.execute(operation.key(), Map.of(INPUT, value("input")), "bad-result")
            .toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertEquals(RuntimeResult.Status.FAILURE, badResult.status());
        assertEquals("RUNTIME.RESULT_OUTPUT_COUNT", badResult.failure().diagnostic().code());
    }

    private static RuntimeLeaseInput input(RuntimeBinding binding, List<PinId> inputs, List<PinId> outputs) {
        return RuntimeTestSupport.input(new RuntimeLeaseInput.BindingRequirement(
            binding.key(), binding.executionFingerprint(), inputs, outputs));
    }

    private static RuntimeBinding binding(RuntimeOperationDescriptor operation, ContractRef<ProviderId> provider) {
        return RuntimeBinding.available(operation, provider, "1.0.0",
            ignored -> CompletableFuture.completedFuture(RuntimeResult.success(value("done"))));
    }

    private static RuntimeOperationDescriptor operation(List<RuntimeOperationDescriptor.Pin> pins) {
        return new RuntimeOperationDescriptor(
            ContractRef.of(OWNER, CapabilityId.of("pin-capability")),
            ContractRef.of(OWNER, OperationId.of("pin-operation")),
            pins,
            semantics());
    }

    private static RuntimeOperationDescriptor.Pin pin(PinId id, RuntimeOperationDescriptor.Direction direction) {
        return new RuntimeOperationDescriptor.Pin(id, direction, type("string"));
    }

    private static RuntimeOperationDescriptor.Pin pin(String id, RuntimeOperationDescriptor.Direction direction) {
        return pin(PinId.of(id), direction);
    }

    private static RuntimeProviderDescriptor provider(ContractRef<ProviderId> provider) {
        return new RuntimeProviderDescriptor(provider, "1.0.0", 100, 500, RuntimeSemantics.UnloadPolicy.DRAIN);
    }

    private static ContractRef<ProviderId> provider(String id) {
        return ContractRef.of(OWNER, ProviderId.of(id));
    }

    private static RuntimeSemantics semantics() {
        return new RuntimeSemantics(
            RuntimeSemantics.Effect.PURE,
            RuntimeSemantics.ThreadMode.CURRENT,
            ContractRef.of(OWNER, CapabilityId.of("authorization")),
            RuntimeSemantics.Cancellation.NONE,
            1_000,
            100,
            5_000,
            RuntimeSemantics.UnloadPolicy.DRAIN,
            RuntimeSemantics.Retry.NEVER,
            RuntimeSemantics.Idempotency.MUTATION_ID,
            RuntimeSemantics.Audit.NONE,
            RuntimeSemantics.Confirmation.NONE,
            RuntimeSemantics.SensitiveData.NONE,
            RuntimeSemantics.Determinism.DETERMINISTIC,
            Set.of("success"),
            Set.of("failure"),
            Set.of(),
            new RuntimeFailureContract(type("failure"), Set.of("RUNTIME.FAILURE"), Set.of("failure"),
                RuntimeFailureContract.CommitBoundary.NO_MUTATION),
            Set.of(),
            Set.of());
    }

    private static TypeExpr type(String id) {
        return TypeExpr.named(TypeReference.of(OWNER.value(), id));
    }

    private static TypedValue value(String value) {
        return TypedValue.value(type("string"), value);
    }
}
