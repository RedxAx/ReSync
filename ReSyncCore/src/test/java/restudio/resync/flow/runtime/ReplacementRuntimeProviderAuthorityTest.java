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

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplacementRuntimeProviderAuthorityTest {
    private static final OwnerId OWNER = new OwnerId("resync.test");
    private static final ContractRef<ProviderId> PROVIDER = ContractRef.of(OWNER, ProviderId.of("authority-provider"));
    private static final ContractRef<CapabilityId> CAPABILITY = ContractRef.of(OWNER, CapabilityId.of("authority-capability"));
    private static final ContractRef<OperationId> OPERATION = ContractRef.of(OWNER, OperationId.of("authority-operation"));
    private static final PinId OUTPUT = PinId.of("output");

    @Test
    void unavailableAuthorityFailsClosedWithStableDiagnostic() {
        ReplacementRuntimeProviderAuthority authority = ReplacementRuntimeProviderAuthority.unavailable();
        RuntimeOperationDescriptor descriptor = operation();

        RuntimeCapabilityUnavailableException failure = assertThrows(
            RuntimeCapabilityUnavailableException.class,
            () -> authority.resolve(descriptor));

        assertFalse(authority.configured());
        assertEquals("RUNTIME.INVALID_INVOCATION", failure.diagnostic().code());
        assertTrue(failure.getMessage().contains("replacement runtime provider authority is unavailable"));
    }

    @Test
    void explicitAuthorityDelegatesExactResolutionAndRetainsLeaseUntilClose() {
        ReplacementRuntimeProviderRegistry registry = new ReplacementRuntimeProviderRegistry();
        RuntimeOperationDescriptor descriptor = operation();
        AtomicBoolean shutdown = new AtomicBoolean();
        registry.activate(provider(), List.of(new ReplacementRuntimeProviderRegistry.BindingRegistration(
            descriptor,
            PROVIDER,
            "1.0.0",
            invocation -> CompletableFuture.completedFuture(RuntimeResult.success()))),
            new RuntimeProviderLifecycle() {
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
            });
        ReplacementRuntimeProviderAuthority authority = ReplacementRuntimeProviderAuthority.of(registry);

        ReplacementRuntimeProviderRegistry.ResolvedOperation lease = authority.resolve(descriptor);

        assertTrue(authority.configured());
        assertEquals(PROVIDER, lease.provider());
        assertEquals(RuntimeUnloadResult.Status.BLOCKED, registry.unload(PROVIDER).status());

        lease.close();

        assertTrue(shutdown.get());
        assertTrue(registry.snapshot().binding(descriptor.key()).isEmpty());
    }

    private static RuntimeProviderDescriptor provider() {
        return new RuntimeProviderDescriptor(PROVIDER, "1.0.0", 0, 0, RuntimeSemantics.UnloadPolicy.DRAIN);
    }

    private static RuntimeOperationDescriptor operation() {
        return new RuntimeOperationDescriptor(
            CAPABILITY,
            OPERATION,
            List.of(new RuntimeOperationDescriptor.Pin(
                OUTPUT,
                RuntimeOperationDescriptor.Direction.OUTPUT,
                TypeExpr.named(TypeReference.of(OWNER.canonicalText(), "string")))),
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
                    TypeExpr.named(TypeReference.of(OWNER.canonicalText(), "failure")),
                    Set.of("RUNTIME.FAILURE"),
                    Set.of("failure"),
                    RuntimeFailureContract.CommitBoundary.NO_MUTATION),
                Set.of(),
                Set.of()));
    }
}
