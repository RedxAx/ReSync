package restudio.resync.flow.runtime;

import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.OperationId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.PinId;
import restudio.resync.flow.identity.ProviderId;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypeReference;
import restudio.resync.flow.type.TypedValue;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplacementRuntimeProviderRegistryTest {
    private static final OwnerId OWNER = new OwnerId("resync.test");
    private static final ContractRef<ProviderId> PROVIDER = ContractRef.of(OWNER, ProviderId.of("replacement-provider"));
    private static final ContractRef<CapabilityId> CAPABILITY = ContractRef.of(OWNER, CapabilityId.of("replacement-capability"));
    private static final ContractRef<OperationId> OPERATION = ContractRef.of(OWNER, OperationId.of("replacement-operation"));
    private static final PinId OUTPUT = PinId.of("output");

    @Test
    void resolvesOnlyTheExactAuthoredCapabilityOperationDescriptorAndFingerprint() {
        ReplacementRuntimeProviderRegistry registry = new ReplacementRuntimeProviderRegistry();
        RuntimeOperationDescriptor descriptor = operation(CAPABILITY, OPERATION);
        AtomicBoolean invoked = new AtomicBoolean();
        ReplacementRuntimeProviderRegistry.BindingRegistration binding = new ReplacementRuntimeProviderRegistry.BindingRegistration(
            descriptor,
            PROVIDER,
            "1.0.0",
            invocation -> {
                invoked.set(true);
                return CompletableFuture.completedFuture(RuntimeResult.success(value("string", "ok")));
            });
        registry.activate(provider(), List.of(binding));

        ReplacementRuntimeProviderRegistry.ResolvedOperation resolved = registry.resolve(
            CAPABILITY, OPERATION, descriptor, descriptor.executionFingerprint());
        RuntimeResult result = resolved.execute(new RuntimeInvocation(
            descriptor.key(), Map.of(), "replacement-1", new RuntimeCancellationToken()))
            .toCompletableFuture().join();

        assertEquals(RuntimeResult.Status.SUCCESS, result.status());
        assertTrue(invoked.get());
        assertThrows(RuntimeCapabilityUnavailableException.class, () -> registry.resolve(
            ContractRef.of(OWNER, CapabilityId.of("other-capability")), OPERATION, descriptor, descriptor.executionFingerprint()));
        assertThrows(RuntimeCapabilityUnavailableException.class, () -> registry.resolve(
            CAPABILITY, ContractRef.of(OWNER, OperationId.of("other-operation")), descriptor, descriptor.executionFingerprint()));
        RuntimeOperationDescriptor changed = new RuntimeOperationDescriptor(
            CAPABILITY, OPERATION,
            List.of(new RuntimeOperationDescriptor.Pin(OUTPUT, RuntimeOperationDescriptor.Direction.OUTPUT, type("number"))),
            descriptor.semantics());
        assertThrows(RuntimeCapabilityUnavailableException.class, () -> registry.resolve(
            CAPABILITY, OPERATION, changed, changed.executionFingerprint()));
    }

    @Test
    void missingBindingFailsClosedWithoutAHandlerFallback() {
        ReplacementRuntimeProviderRegistry registry = new ReplacementRuntimeProviderRegistry();
        RuntimeOperationDescriptor descriptor = operation(CAPABILITY, OPERATION);

        assertThrows(RuntimeCapabilityUnavailableException.class, () -> registry.resolve(
            CAPABILITY, OPERATION, descriptor, descriptor.executionFingerprint()));
        assertEquals(0, registry.snapshot().bindings().size());
    }

    @Test
    void providerDrainBlocksNewResolutionsAndUnloadsAfterLeaseRelease() {
        ReplacementRuntimeProviderRegistry registry = new ReplacementRuntimeProviderRegistry();
        RuntimeOperationDescriptor descriptor = operation(CAPABILITY, OPERATION);
        ReplacementRuntimeProviderRegistry.BindingRegistration binding = new ReplacementRuntimeProviderRegistry.BindingRegistration(
            descriptor, PROVIDER, "1.0.0", invocation -> CompletableFuture.completedFuture(RuntimeResult.success()));
        AtomicBoolean shutdown = new AtomicBoolean();
        RuntimeProviderLifecycle lifecycle = new RuntimeProviderLifecycle() {
            @Override
            public boolean ready(RuntimeProviderDescriptor ignored, List<RuntimeBindingDescriptor> bindings) {
                return true;
            }

            @Override
            public void compensate() {
            }

            @Override
            public void shutdown() {
                shutdown.set(true);
            }
        };
        registry.activate(provider(), List.of(binding), lifecycle);
        ReplacementRuntimeProviderRegistry.ResolvedOperation lease = registry.resolve(descriptor);

        RuntimeUnloadResult blocked = registry.unload(PROVIDER);
        assertEquals(RuntimeUnloadResult.Status.BLOCKED, blocked.status());
        assertThrows(RuntimeCapabilityUnavailableException.class, () -> registry.resolve(descriptor));

        lease.close();
        assertTrue(shutdown.get());
        assertEquals(RuntimeUnloadResult.Status.NOT_FOUND, registry.unload(PROVIDER).status());
        assertTrue(registry.snapshot().binding(descriptor.key()).isEmpty());
    }

    @Test
    void stagedProviderRejectsReadinessFailureWithoutActivation() {
        ReplacementRuntimeProviderRegistry registry = new ReplacementRuntimeProviderRegistry();
        RuntimeOperationDescriptor descriptor = operation(CAPABILITY, OPERATION);
        ReplacementRuntimeProviderRegistry.BindingRegistration binding = new ReplacementRuntimeProviderRegistry.BindingRegistration(
            descriptor, PROVIDER, "1.0.0", invocation -> CompletableFuture.completedFuture(RuntimeResult.success()));
        AtomicBoolean compensated = new AtomicBoolean();
        RuntimeProviderLifecycle lifecycle = new RuntimeProviderLifecycle() {
            @Override
            public boolean ready(RuntimeProviderDescriptor ignored, List<RuntimeBindingDescriptor> bindings) {
                return false;
            }

            @Override
            public void compensate() {
                compensated.set(true);
            }

            @Override
            public void shutdown() {
            }
        };

        assertThrows(RuntimeCapabilityUnavailableException.class, () -> registry.stage(provider(), List.of(binding), lifecycle));
        assertTrue(compensated.get());
        assertTrue(registry.snapshot().providers().isEmpty());
    }

    private static RuntimeProviderDescriptor provider() {
        return new RuntimeProviderDescriptor(PROVIDER, "1.0.0", 0, 0, RuntimeSemantics.UnloadPolicy.DRAIN);
    }

    private static RuntimeOperationDescriptor operation(
        ContractRef<CapabilityId> capability,
        ContractRef<OperationId> operation
    ) {
        return new RuntimeOperationDescriptor(
            capability,
            operation,
            List.of(new RuntimeOperationDescriptor.Pin(OUTPUT, RuntimeOperationDescriptor.Direction.OUTPUT, type("string"))),
            new RuntimeSemantics(
                RuntimeSemantics.Effect.PURE,
                RuntimeSemantics.ThreadMode.CURRENT,
                CAPABILITY,
                RuntimeSemantics.Cancellation.COOPERATIVE,
                1_000,
                0,
                0,
                RuntimeSemantics.UnloadPolicy.DRAIN,
                RuntimeSemantics.Retry.NEVER,
                RuntimeSemantics.Idempotency.NONE,
                RuntimeSemantics.Audit.NONE,
                RuntimeSemantics.Confirmation.NONE,
                RuntimeSemantics.SensitiveData.NONE,
                RuntimeSemantics.Determinism.DETERMINISTIC,
                Set.of("success"),
                Set.of("failure"),
                Set.of("cancelled"),
                new RuntimeFailureContract(
                    type("failure"), Set.of("RUNTIME.FAILURE"), Set.of("failure"), RuntimeFailureContract.CommitBoundary.NO_MUTATION),
                Set.of(),
                Set.of()));
    }

    private static TypeExpr type(String value) {
        return TypeExpr.named(TypeReference.of(OWNER.canonicalText(), value));
    }

    private static TypedValue value(String type, String value) {
        return TypedValue.value(type(type), value);
    }
}
