package restudio.resync.flow.catalog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.OperationId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.PinId;
import restudio.resync.flow.identity.ProviderId;
import restudio.resync.flow.inspector.InspectorFallback;
import restudio.resync.flow.runtime.RuntimeBinding;
import restudio.resync.flow.runtime.RuntimeBindingRegistry;
import restudio.resync.flow.runtime.RuntimeBindingManifest;
import restudio.resync.flow.runtime.RuntimeFailureContract;
import restudio.resync.flow.runtime.RuntimeOperationDescriptor;
import restudio.resync.flow.runtime.RuntimeProviderDescriptor;
import restudio.resync.flow.runtime.RuntimeResult;
import restudio.resync.flow.runtime.RuntimeSemantics;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypeReference;
import restudio.resync.migration.MigrationActivationMarker;
import restudio.resync.migration.PersistenceRootReadiness;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CatalogActivationAuthorityTest {
    @TempDir
    Path temporary;

    @Test
    void missingLegacyAndPartialMarkersRemainForbidden() throws Exception {
        assertFalse(CatalogActivationAuthority.fromCommittedMigration(temporary).approved());

        Path marker = MigrationActivationMarker.markerPath(temporary);
        Files.createDirectories(marker.getParent());
        Files.writeString(marker, "format=1\n");
        assertFalse(CatalogActivationAuthority.fromCommittedMigration(temporary).approved());

        Files.writeString(marker, Files.readString(marker).replace("format=1", "format=2"));
        assertFalse(CatalogActivationAuthority.fromCommittedMigration(temporary).approved());
    }

    @Test
    void committedMarkerCarriesAllBindingIdentityAndCannotApproveWithoutContext() throws Exception {
        Path participantRoot = Files.createDirectories(temporary.resolve("participant"));
        PersistenceRootReadiness readiness = new PersistenceRootReadiness(List.of(
            PersistenceRootReadiness.Owner.registered("resync.participant", participantRoot, true)));
        String replacementRootHash = "c".repeat(64);
        MigrationActivationMarker.Values values = new MigrationActivationMarker.Values(
            "a".repeat(64), "b".repeat(64), replacementRootHash, "d".repeat(64),
            "e".repeat(64), RuntimeBindingManifest.VERSION, readiness.reportHash(), readiness.reportVersion(), true);
        MigrationActivationMarker.write(temporary, values);

        CatalogActivationAuthority authority = CatalogActivationAuthority.fromCommittedMigration(temporary);
        assertTrue(authority.approved());
        assertTrue(authority.requiresBindingContext());
        assertEquals("offline-upgrade:" + replacementRootHash, authority.approvalReference());
        assertEquals("CATALOG.ACTIVATION_BINDING_CONTEXT_REQUIRED",
            CatalogActivationAuthority.evaluateReplacement(true, authority).code());
    }

    @Test
    void committedMarkerApprovesExactCatalogRuntimeAndReadiness() throws Exception {
        RuntimeBindingRegistry registry = liveRuntimeRegistry();
        RuntimeBindingManifest runtime = registry.snapshot().manifest();
        CatalogSnapshot snapshot = replacementSnapshot(registry);
        PersistenceRootReadiness readiness = readiness(temporary);
        MigrationActivationMarker.write(temporary, markerValues(snapshot, runtime, readiness));
        assertEquals(runtime.bindingManifestHash(), snapshot.bindingManifestHash());

        CatalogActivationAuthority authority = CatalogActivationAuthority.fromCommittedMigration(temporary);
        CatalogActivationAuthority.Decision decision = CatalogActivationAuthority.evaluate(snapshot, runtime, readiness, authority);

        assertTrue(decision.allowed(), decision.detail());
        assertEquals("CATALOG.GATE2_AUTHORITY_APPROVED", decision.code());
    }

    @Test
    void freshInstallRejectsCatalogBoundToDifferentRuntimeManifest() throws Exception {
        RuntimeBindingRegistry registry = liveRuntimeRegistry();
        CatalogSnapshot snapshot = replacementSnapshot(registry);
        registry.activate(new RuntimeProviderDescriptor(
                ContractRef.of(OWNER, ProviderId.of("second-provider")), "1.0.0", 0, 0, RuntimeSemantics.UnloadPolicy.DRAIN),
            List.of(RuntimeBinding.available(secondOperation(), ContractRef.of(OWNER, ProviderId.of("second-provider")), "1.0.0",
                ignored -> CompletableFuture.completedFuture(RuntimeResult.success()))));
        PersistenceRootReadiness readiness = readiness(temporary);

        CatalogActivationAuthority.Decision decision = CatalogActivationAuthority.evaluate(
            snapshot, registry.snapshot().manifest(), readiness, CatalogActivationAuthority.freshInstall());

        assertFalse(decision.allowed());
        assertEquals("CATALOG.ACTIVATION_RUNTIME_BINDING_MISMATCH", decision.code());
    }

    @Test
    void freshInstallApprovesExactRuntimeManifestAndCompleteReadiness() throws Exception {
        RuntimeBindingRegistry registry = liveRuntimeRegistry();
        RuntimeBindingManifest runtime = registry.snapshot().manifest();
        CatalogSnapshot snapshot = replacementSnapshot(registry);
        PersistenceRootReadiness readiness = readiness(temporary);
        assertEquals(runtime.bindingManifestHash(), snapshot.bindingManifestHash());

        CatalogActivationAuthority.Decision decision = CatalogActivationAuthority.evaluate(
            snapshot, runtime, readiness, CatalogActivationAuthority.freshInstall());

        assertTrue(decision.allowed(), decision.detail());
        assertEquals("CATALOG.FRESH_INSTALL_AUTHORITY_APPROVED", decision.code());
    }

    @Test
    void incompleteReadinessCannotBeEncodedAsAnApprovedMarker() {
        assertFalse(new PersistenceRootReadiness(List.of()).complete());
        try {
            new MigrationActivationMarker.Values("a".repeat(64), "b".repeat(64), "c".repeat(64),
                "d".repeat(64), "e".repeat(64), 1, "f".repeat(64), 1, false);
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("An incomplete readiness report was accepted");
    }

    private static final CatalogVersion CONTRACT = new CatalogVersion(1, 0);
    private static final OwnerId OWNER = OwnerId.of("resync.activation.authority");
    private static final ContractRef<CapabilityId> HANDLER = ContractRef.of(OWNER, CapabilityId.of("replacement-handler"));
    private static final ContractRef<OperationId> OPERATION = ContractRef.of(OWNER, OperationId.of("execute"));
    private static final NodeId NODE = NodeId.of("replacement-authority-node");
    private static final TypeExpr STRING = TypeExpr.named(TypeReference.of("builtin", "string"));

    private static RuntimeBindingRegistry liveRuntimeRegistry() {
        RuntimeBindingRegistry registry = new RuntimeBindingRegistry();
        ContractRef<ProviderId> provider = ContractRef.of(OWNER, ProviderId.of("provider"));
        RuntimeOperationDescriptor operation = replacementOperation();
        registry.activate(new RuntimeProviderDescriptor(provider, "1.0.0", 0, 0, RuntimeSemantics.UnloadPolicy.DRAIN),
            List.of(RuntimeBinding.available(operation, provider, "1.0.0",
                ignored -> CompletableFuture.completedFuture(RuntimeResult.success()))));
        return registry;
    }

    private static CatalogSnapshot replacementSnapshot(RuntimeBindingRegistry registry) {
        CatalogContribution contribution = CatalogContribution.builder(OWNER, "1.0.0",
                new CatalogContractRange(CONTRACT, CONTRACT),
                CatalogProvenance.fromText(CatalogProvenance.SourceKind.BUNDLED, "nodes/activation-authority.json", "1.0.0", "test", "activation-authority"))
            .categories(List.of(new CatalogCategoryDescriptor(CapabilityId.of("flow"), "Flow", "Replacement activation authority test category.", 1)))
            .capabilities(List.of(new CatalogCapabilityDescriptor(CapabilityId.of("flow"), 1, false, InspectorFallback.GENERIC),
                new CatalogCapabilityDescriptor(HANDLER.id(), 1, false, InspectorFallback.GENERIC),
                new CatalogCapabilityDescriptor(CapabilityId.of("generic-editor"), 1, false, InspectorFallback.GENERIC)))
            .definitions(List.of(replacementDefinition()))
            .runtimeRequirements(List.of(replacementOperation()))
            .build();
        return new CatalogCompiler(CONTRACT, CatalogBindingProof.live(registry))
            .compile(List.of(contribution), 1)
            .snapshot()
            .orElseThrow();
    }

    private static CatalogNodeDescriptor replacementDefinition() {
        return CatalogNodeDescriptor.builder(NODE)
            .domain("flow")
            .family("operation")
            .displayName("Replacement Authority")
            .description("Verifies exact catalog, runtime, and readiness activation identity.")
            .pins(List.of(new CatalogNodeDescriptor.Pin(PinId.of("value"), CatalogNodeDescriptor.Direction.INPUT, STRING,
                "Value", "The value supplied to the activation authority.", CatalogNodeDescriptor.Requirement.REQUIRED,
                null, ContractRef.of(OWNER, CapabilityId.of("generic-editor")), null, null, null, null)))
            .category(ContractRef.of(OWNER, CapabilityId.of("flow")))
            .branches(List.of(new CatalogNodeDescriptor.Branch("failure", "Failure", "Reports that activation verification failed.",
                List.of(new CatalogNodeDescriptor.Case("failure", "Failure", "The activation verification did not succeed.")))))
            .handler(new CatalogNodeDescriptor.Handler(HANDLER, OPERATION))
            .semantics(semantics())
            .requiredCapabilities(Set.of(HANDLER))
            .metadata(Map.of("authoredSource", Map.of(
                "id", NODE.value(),
                "handlerCapability", HANDLER.id().value(),
                "sourceProvenance", Map.of(
                    "sourceUri", "nodes/activation-authority.json",
                    "rowIndex", 0,
                    "owner", OWNER.value(),
                    "sourceHash", "a".repeat(64)))))
            .build();
    }

    private static RuntimeOperationDescriptor replacementOperation() {
        return new RuntimeOperationDescriptor(HANDLER, OPERATION,
            List.of(new RuntimeOperationDescriptor.Pin("value", RuntimeOperationDescriptor.Direction.INPUT, STRING)), semantics());
    }

    private static RuntimeOperationDescriptor secondOperation() {
        ContractRef<CapabilityId> capability = ContractRef.of(OWNER, CapabilityId.of("second-handler"));
        return new RuntimeOperationDescriptor(capability, ContractRef.of(OWNER, OperationId.of("second-operation")),
            List.of(new RuntimeOperationDescriptor.Pin("value", RuntimeOperationDescriptor.Direction.INPUT, STRING)), semantics());
    }

    private static RuntimeSemantics semantics() {
        return new RuntimeSemantics(
            RuntimeSemantics.Effect.PURE,
            RuntimeSemantics.ThreadMode.CURRENT,
            ContractRef.of(OWNER, CapabilityId.of("authorize")),
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
            Set.of("failure"),
            Set.of(),
            new RuntimeFailureContract(STRING, Set.of("RUNTIME.FAILURE"), Set.of("failure"), RuntimeFailureContract.CommitBoundary.NO_MUTATION),
            Set.of(),
            Set.of());
    }

    private static PersistenceRootReadiness readiness(Path root) throws Exception {
        Path participantRoot = Files.createDirectories(root.resolve("participant"));
        return new PersistenceRootReadiness(List.of(
            PersistenceRootReadiness.Owner.registered("resync.participant", participantRoot, true)));
    }

    private static MigrationActivationMarker.Values markerValues(CatalogSnapshot snapshot,
                                                                  RuntimeBindingManifest runtime,
                                                                  PersistenceRootReadiness readiness) {
        return new MigrationActivationMarker.Values(
            "a".repeat(64),
            "b".repeat(64),
            "c".repeat(64),
            snapshot.contentChecksum().canonicalText(),
            runtime.bindingManifestHash().canonicalText(),
            runtime.version(),
            readiness.reportHash(),
            readiness.reportVersion(),
            true);
    }
}
