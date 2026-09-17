package restudio.resync.flow.runtime;

import org.junit.jupiter.api.Test;
import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.OperationId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ProviderId;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypeReference;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimePrincipalBoundaryTest {
    private static final RuntimeAuthority AUTHORITY = new RuntimeAuthority("resync:principal-test");
    private static final ContractRef<ProviderId> PROVIDER = ContractRef.of(OwnerId.of("resync"), ProviderId.of("flow"));

    @Test
    void missingPrincipalIsDeniedForMutatingBindings() {
        RuntimePrincipalAuthority principals = new RuntimePrincipalAuthority(AUTHORITY);
        RuntimeBindingRegistry registry = registry(principals);
        RuntimeOperationDescriptor operation = operation("missing");
        RuntimeBinding binding = binding(operation);
        registry.activate(provider(), List.of(binding));

        RuntimeCapabilityUnavailableException failure = assertThrows(RuntimeCapabilityUnavailableException.class,
            () -> registry.acquire(input(binding, null)));

        assertEquals("RUNTIME.AUTHORIZATION_DENIED", failure.diagnostic().code());
    }

    @Test
    void principalIssuedByAnotherAuthorityIsDenied() {
        RuntimePrincipalAuthority principals = new RuntimePrincipalAuthority(AUTHORITY);
        RuntimePrincipalAuthority foreignPrincipals = new RuntimePrincipalAuthority(AUTHORITY);
        RuntimeBindingRegistry registry = registry(principals);
        RuntimeOperationDescriptor operation = operation("forged");
        RuntimeBinding binding = binding(operation);
        registry.activate(provider(), List.of(binding));

        RuntimeCapabilityUnavailableException failure = assertThrows(RuntimeCapabilityUnavailableException.class,
            () -> registry.acquire(input(binding, foreignPrincipals.issuePlayer("player"))));

        assertEquals("RUNTIME.AUTHORIZATION_DENIED", failure.diagnostic().code());
    }

    @Test
    void issuePlayerReusesTheSamePrincipalForTheSameIdentity() {
        RuntimePrincipalAuthority principals = new RuntimePrincipalAuthority(AUTHORITY);
        RuntimePrincipal first = principals.issuePlayer("player-stable");
        RuntimePrincipal second = principals.issuePlayer("player-stable");
        assertSame(first, second);
        assertTrue(principals.trusts(second, AUTHORITY));
    }

    private static RuntimeBindingRegistry registry(RuntimePrincipalAuthority principals) {
        AtomicReference<RuntimeBindingRegistry> reference = new AtomicReference<>();
        FlowRuntimeSecurityBoundary security = new FlowRuntimeSecurityBoundary(AUTHORITY,
            () -> reference.get() == null ? null : reference.get().snapshot(), principals);
        RuntimeBindingRegistry registry = new RuntimeBindingRegistry(
            security,
            RuntimeAuditBoundary.unavailable(),
            new FlowRuntimeExecutionBoundary(Runnable::run, Runnable::run, () -> true),
            RuntimeReceiptStore.inMemory(true),
            principals);
        reference.set(registry);
        return registry;
    }

    private static RuntimeLeaseInput input(RuntimeBinding binding, RuntimePrincipal principal) {
        return RuntimeLeaseInput.plan(
            List.of(new RuntimeLeaseInput.BindingRequirement(binding.key(), binding.executionFingerprint())),
            ContentHash.of(CanonicalJson.sha256("principal-test-plan", Map.of("operation", binding.key().canonical()))),
            AUTHORITY,
            principal);
    }

    private static RuntimeBinding binding(RuntimeOperationDescriptor operation) {
        return RuntimeBinding.available(operation, PROVIDER, "1.0.0",
            ignored -> CompletableFuture.completedFuture(RuntimeResult.success()));
    }

    private static RuntimeProviderDescriptor provider() {
        return new RuntimeProviderDescriptor(PROVIDER, "1.0.0", 0, 0, RuntimeSemantics.UnloadPolicy.DRAIN);
    }

    private static RuntimeOperationDescriptor operation(String id) {
        ContractRef<CapabilityId> capability = ContractRef.of(OwnerId.of("resync"), CapabilityId.of(id));
        RuntimeSemantics semantics = new RuntimeSemantics(
            RuntimeSemantics.Effect.STATE_MUTATING,
            RuntimeSemantics.ThreadMode.CURRENT,
            capability,
            RuntimeSemantics.Cancellation.NONE,
            0,
            0,
            0,
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
            new RuntimeFailureContract(TypeExpr.named(TypeReference.of("builtin", "any")),
                Set.of("RUNTIME.HANDLER_FAILURE"), Set.of("failure"), RuntimeFailureContract.CommitBoundary.NO_MUTATION),
            Set.of(),
            Set.of());
        return new RuntimeOperationDescriptor(capability, ContractRef.of(OwnerId.of("resync"), OperationId.of(id)),
            List.of(), semantics);
    }
}
