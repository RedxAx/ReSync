package restudio.resync.flow.catalog;

import org.junit.jupiter.api.Test;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.OperationId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ProviderId;
import restudio.resync.flow.runtime.RuntimeBinding;
import restudio.resync.flow.runtime.RuntimeBindingManifest;
import restudio.resync.flow.runtime.RuntimeBindingRegistry;
import restudio.resync.flow.runtime.RuntimeFailureContract;
import restudio.resync.flow.runtime.RuntimeOperationDescriptor;
import restudio.resync.flow.runtime.RuntimeProviderDescriptor;
import restudio.resync.flow.runtime.RuntimeResult;
import restudio.resync.flow.runtime.RuntimeSemantics;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypeReference;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BindingManifestAuthorityTest {
    private static final TypeExpr STRING = TypeExpr.named(TypeReference.of("builtin", "string"));

    @Test
    void liveRuntimeManifestHashIsPublishedIntoTheCatalogSnapshot() {
        RuntimeBindingRegistry registry = new RuntimeBindingRegistry();
        RuntimeBindingManifest runtime = registry.snapshot().manifest();

        CatalogSnapshot snapshot = new CatalogCompiler(new CatalogVersion(1, 0), CatalogBindingProof.live(registry))
            .compile(List.of(), 1)
            .snapshot()
            .orElseThrow();

        assertEquals(runtime.bindingManifestHash(), snapshot.bindingManifestHash());
        assertTrue(snapshot.canonicalContent().contains(runtime.bindingManifestHash().canonicalText()));
        assertTrue(runtime.matches(snapshot.bindingManifestHash()));
    }

    @Test
    void runtimeManifestChangesRejectThePreviouslyBoundCatalog() {
        RuntimeBindingRegistry registry = new RuntimeBindingRegistry();
        CatalogSnapshot snapshot = new CatalogCompiler(new CatalogVersion(1, 0), CatalogBindingProof.live(registry))
            .compile(List.of(), 1)
            .snapshot()
            .orElseThrow();
        RuntimeBindingManifest previous = registry.snapshot().manifest();

        ContractRef<ProviderId> provider = ContractRef.of(OwnerId.of("resync.test"), ProviderId.of("provider"));
        RuntimeOperationDescriptor operation = new RuntimeOperationDescriptor(
            ContractRef.of(OwnerId.of("resync.test"), CapabilityId.of("capability")),
            ContractRef.of(OwnerId.of("resync.test"), OperationId.of("operation")),
            pins(), semantics());
        registry.activate(new RuntimeProviderDescriptor(provider, "1.0.0", 0, 0, RuntimeSemantics.UnloadPolicy.DRAIN),
            List.of(RuntimeBinding.unavailable(operation, provider, "1.0.0")));

        RuntimeBindingManifest current = registry.snapshot().manifest();
        assertFalse(current.matches(snapshot.bindingManifestHash()));
        assertTrue(previous.matches(snapshot.bindingManifestHash()));
    }

    @Test
    void capturedLiveProofKeepsFingerprintsAndHashFromOneRuntimeSnapshot() {
        RuntimeBindingRegistry registry = new RuntimeBindingRegistry();
        ContractRef<ProviderId> provider = ContractRef.of(OwnerId.of("resync.test"), ProviderId.of("provider"));
        RuntimeOperationDescriptor operation = new RuntimeOperationDescriptor(
            ContractRef.of(OwnerId.of("resync.test"), CapabilityId.of("capability")),
            ContractRef.of(OwnerId.of("resync.test"), OperationId.of("operation")),
            pins(), semantics());
        registry.activate(new RuntimeProviderDescriptor(provider, "1.0.0", 0, 0, RuntimeSemantics.UnloadPolicy.DRAIN),
            List.of(RuntimeBinding.available(operation, provider, "1.0.0",
                ignored -> CompletableFuture.completedFuture(RuntimeResult.success()))));

        CatalogBindingProof captured = CatalogBindingProof.live(registry).capture();
        String hash = captured.activeBindingManifestHash().orElseThrow().canonicalText();
        registry.unload(provider);

        assertEquals(hash, captured.activeBindingManifestHash().orElseThrow().canonicalText());
        assertTrue(captured.activeFingerprint(operation.key()).isPresent());
        assertFalse(CatalogBindingProof.live(registry).activeFingerprint(operation.key()).isPresent());
    }

    private static RuntimeSemantics semantics() {
        return new RuntimeSemantics(
            RuntimeSemantics.Effect.PURE,
            RuntimeSemantics.ThreadMode.CURRENT,
            ContractRef.of(OwnerId.of("resync.test"), CapabilityId.of("authorization")),
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
            Set.of(),
            Set.of("failed"),
            Set.of(),
            new RuntimeFailureContract(STRING, Set.of("RUNTIME.FAILURE"), Set.of("failed"), RuntimeFailureContract.CommitBoundary.NO_MUTATION),
            Set.of(),
            Set.of());
    }

    private static List<RuntimeOperationDescriptor.Pin> pins() {
        return List.of(
            new RuntimeOperationDescriptor.Pin("value", RuntimeOperationDescriptor.Direction.INPUT, STRING),
            new RuntimeOperationDescriptor.Pin("result", RuntimeOperationDescriptor.Direction.OUTPUT, STRING));
    }
}
