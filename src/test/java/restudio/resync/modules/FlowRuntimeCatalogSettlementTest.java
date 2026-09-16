package restudio.resync.modules;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.resync.flow.catalog.CatalogActivationAuthority;
import restudio.resync.flow.catalog.CatalogBindingProof;
import restudio.resync.flow.catalog.CatalogCapabilityDescriptor;
import restudio.resync.flow.catalog.CatalogCategoryDescriptor;
import restudio.resync.flow.catalog.CatalogCompiler;
import restudio.resync.flow.catalog.CatalogContractRange;
import restudio.resync.flow.catalog.CatalogContribution;
import restudio.resync.flow.catalog.CatalogNodeDescriptor;
import restudio.resync.flow.catalog.CatalogProvenance;
import restudio.resync.flow.catalog.CatalogRuntimeActivation;
import restudio.resync.flow.catalog.CatalogVersion;
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
import restudio.resync.flow.runtime.RuntimeFailureContract;
import restudio.resync.flow.runtime.RuntimeOperationDescriptor;
import restudio.resync.flow.runtime.RuntimeProviderDescriptor;
import restudio.resync.flow.runtime.RuntimeSemantics;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypeReference;
import restudio.resync.migration.MigrationActivationMarker;
import restudio.resync.migration.PersistenceRootReadiness;
import restudio.resync.migration.ReplacementActivationRecord;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowRuntimeCatalogSettlementTest {
    @Test
    void settlementRequiresTheExactFinalActivationBeforeAnyContinuation() {
        CatalogRuntimeActivation.ActivationRecord expected = activation();
        AtomicReference<CatalogRuntimeActivation.ActivationRecord> active = new AtomicReference<>(expected);
        List<String> calls = new ArrayList<>();
        FlowRuntimeModule.settleCatalog(expected, active::get, candidate -> {
            assertSame(expected, candidate);
            calls.add("settle");
        });
        calls.add("activate");
        assertEquals(List.of("settle", "activate"), calls);
        assertThrows(IllegalStateException.class, () -> FlowRuntimeModule.settleCatalog(expected, active::get, null));
        assertThrows(IllegalStateException.class, () -> FlowRuntimeModule.settleCatalog(expected, active::get,
            candidate -> {
                throw new IllegalStateException("Rejected final settlement");
            }));
        assertEquals(List.of("settle", "activate"), calls);
    }

    @Test
    void equalBindingWithDifferentActivationIdentityCannotOpenCallbacks() {
        CatalogRuntimeActivation.ActivationRecord expected = activation();
        CatalogRuntimeActivation.ActivationRecord other = new CatalogRuntimeActivation.ActivationRecord(
            expected.catalog(), expected.runtime());
        assertEquals(expected, other);
        AtomicReference<CatalogRuntimeActivation.ActivationRecord> active = new AtomicReference<>(expected);
        List<String> callbacks = new ArrayList<>();
        assertThrows(IllegalStateException.class, () -> {
            FlowRuntimeModule.settleCatalog(expected, active::get, candidate -> active.set(other));
            callbacks.add("runtime-listeners");
            callbacks.add("restore-callbacks");
        });
        assertTrue(callbacks.isEmpty());
        assertThrows(IllegalStateException.class, () -> FlowRuntimeModule.settleCatalog(expected, active::get,
            candidate -> callbacks.add("settlement")));
        assertTrue(callbacks.isEmpty());
    }

    @Test
    void settlementCapabilityIsRequiredAndCannotBeReplaced() {
        FlowRuntimeModule module = new FlowRuntimeModule();
        assertThrows(NullPointerException.class, () -> module.bindCatalogSettlement(null));
        module.bindCatalogSettlement(expected -> {
            throw new IllegalStateException("Unsettled");
        });
        assertThrows(IllegalStateException.class, () -> module.bindCatalogSettlement(expected -> {
        }));
        assertFalse(module.startupActivationComplete());
    }

    @Test
    void recordFallbackChecksTheActualCurrentRuntimeAndReadinessWithoutWeakeningMarkers(@TempDir Path root)
        throws Exception {
        CatalogRuntimeActivation.ActivationRecord current = activation();
        PersistenceRootReadiness readiness = new PersistenceRootReadiness(List.of(
            PersistenceRootReadiness.Owner.registered("settlement.owner", Files.createDirectories(root.resolve("state")), true)));
        ReplacementActivationRecord.write(root, new ReplacementActivationRecord.Values(54L,
            "6ffe6a7740232c2bc7d1eeb551488ba913c728fcd1fdf44712528bdc14d2bd84", 1,
            "58f858c36ff7a799bbb610d8b40bfe297d56f92738b98c91f35a926024144116",
            readiness.reportVersion(), readiness.reportHash()));
        CatalogActivationAuthority authority = CatalogActivationAuthority.fromCommittedMigration(root);
        assertFalse(authority.approved());
        assertTrue(ReplacementActivationRecord.exists(root));
        authority = CatalogActivationAuthority.freshInstall();
        CatalogActivationAuthority.Decision accepted = CatalogActivationAuthority.evaluate(current.catalog(),
            current.runtimeManifest(), readiness, authority);
        assertEquals("CATALOG.FRESH_INSTALL_AUTHORITY_APPROVED", accepted.code());
        assertTrue(accepted.allowed());
        assertFalse(CatalogActivationAuthority.evaluate(current.catalog(), current.runtimeManifest(),
            PersistenceRootReadiness.empty(), authority).allowed());
        assertFalse(CatalogActivationAuthority.evaluate(current.catalog(), new RuntimeBindingRegistry().snapshot().manifest(),
            readiness, authority).allowed());
        MigrationActivationMarker.write(root, new MigrationActivationMarker.Values("1".repeat(64), "2".repeat(64),
            "3".repeat(64), "6ffe6a7740232c2bc7d1eeb551488ba913c728fcd1fdf44712528bdc14d2bd84",
            current.runtimeManifest().bindingManifestHash().canonicalText(), current.runtimeManifest().version(),
            readiness.reportHash(), readiness.reportVersion(), true));
        CatalogActivationAuthority marker = CatalogActivationAuthority.fromCommittedMigration(root);
        assertTrue(marker.requiresBindingContext());
        CatalogActivationAuthority.Decision rejected = CatalogActivationAuthority.evaluate(current.catalog(),
            current.runtimeManifest(), readiness, marker);
        assertFalse(rejected.allowed());
        assertEquals("CATALOG.ACTIVATION_CATALOG_MISMATCH", rejected.code());
    }

    @Test
    void productionStartupSettlesAfterFinalApprovalBeforeActivationAndFenceRelease() throws Exception {
        String runtime = Files.readString(Path.of("src/main/java/restudio/resync/modules/FlowRuntimeModule.java"));
        int start = runtime.indexOf("public synchronized void completeStartupActivation(ReSyncPersistenceCoordinator.ReadinessProof proof)");
        int end = runtime.indexOf("private static long elapsedMillis", start);
        String startup = runtime.substring(start, end);
        int reload = startup.indexOf("reloadNodeDefinitionsFenced()");
        int approval = startup.indexOf("CatalogActivationAuthority.evaluate(");
        int settlement = startup.indexOf("settleCatalog(activation, delegate::activeCatalogRuntimeActivation, catalogSettlement);");
        assertTrue(reload >= 0 && approval > reload && settlement > approval);
        for (String next : List.of("delegate.completeStartupActivation(verified)", "activateRuntime(startupContext)",
            "persistStartupActivationRecord(expectedRecord)", "restoreDeferredStartupCallbacks()", "startupAdmissionFence.close()")) {
            assertTrue(startup.indexOf(next) > settlement, next);
        }
        String failed = startup.substring(startup.indexOf("catch (RuntimeException | Error failure)"));
        assertFalse(failed.contains("startupAdmissionFence.close()"));
        assertFalse(failed.contains("restoreDeferredStartupCallbacks()"));
        String server = Files.readString(Path.of("src/main/java/restudio/resync/server/ReSyncServer.java"));
        assertTrue(server.contains("SqliteProtocolResourceMutationAuthority.CatalogStartup.DEFERRED"));
        assertTrue(server.indexOf("flowRuntimeModule.bindCatalogSettlement(expected ->")
            < server.indexOf("flowRuntimeModule.completeStartupActivation(readinessProof)"));
        assertTrue(server.contains("ReplacementActivationRecord.exists(dataRoot)"));
        assertTrue(server.contains("CatalogActivationAuthority.freshInstall()"));
    }

    @Test
    void postStartCatalogActivationSettlesResourcesAndPersistsItsBindingBeforeProjection() throws Exception {
        String runtime = Files.readString(Path.of("src/main/java/restudio/resync/modules/FlowRuntimeModule.java"));
        String reload = runtime.substring(runtime.indexOf("private boolean reloadNodeDefinitionsFenced()"),
            runtime.indexOf("private void settleReloadedCatalog()"));
        String settlement = runtime.substring(runtime.indexOf("private void settleReloadedCatalog()"),
            runtime.indexOf("private synchronized void retainFatalActivationFault"));

        assertTrue(reload.contains("startupActivationComplete && !preparedCore.noop()"));
        int irreversible = reload.indexOf("this::settleReloadedCatalog");
        assertTrue(irreversible >= 0 && reload.indexOf("projectionCommit,", irreversible) > irreversible);
        assertTrue(settlement.indexOf("settleCatalog(activation, delegate::activeCatalogRuntimeActivation, catalogSettlement)")
            < settlement.indexOf("persistStartupActivationRecord(new ReplacementActivationRecord.Values("));
        assertTrue(settlement.contains("previous.participantReadinessVersion()"));
        assertTrue(settlement.contains("previous.participantReadinessHash()"));
    }

    private static CatalogRuntimeActivation.ActivationRecord activation() {
        OwnerId owner = OwnerId.of("settlement.test");
        ContractRef<CapabilityId> handler = ContractRef.of(owner, CapabilityId.of("handler"));
        ContractRef<OperationId> operationId = ContractRef.of(owner, OperationId.of("execute"));
        ContractRef<ProviderId> provider = ContractRef.of(owner, ProviderId.of("provider"));
        TypeExpr string = TypeExpr.named(TypeReference.of("builtin", "string"));
        RuntimeSemantics semantics = new RuntimeSemantics(RuntimeSemantics.Effect.PURE, RuntimeSemantics.ThreadMode.CURRENT,
            ContractRef.of(owner, CapabilityId.of("authorize")), RuntimeSemantics.Cancellation.NONE, 0, 0, 0,
            RuntimeSemantics.UnloadPolicy.DRAIN, RuntimeSemantics.Retry.NEVER, RuntimeSemantics.Idempotency.INTRINSIC,
            RuntimeSemantics.Audit.NONE, RuntimeSemantics.Confirmation.NONE, RuntimeSemantics.SensitiveData.NONE,
            RuntimeSemantics.Determinism.DETERMINISTIC, Set.of(), Set.of("failure"), Set.of(),
            new RuntimeFailureContract(string, Set.of("RUNTIME.FAILURE"), Set.of("failure"),
                RuntimeFailureContract.CommitBoundary.NO_MUTATION), Set.of(), Set.of());
        RuntimeOperationDescriptor operation = new RuntimeOperationDescriptor(handler, operationId,
            List.of(new RuntimeOperationDescriptor.Pin("value", RuntimeOperationDescriptor.Direction.INPUT, string)), semantics);
        RuntimeBindingRegistry runtime = new RuntimeBindingRegistry();
        runtime.activate(new RuntimeProviderDescriptor(provider, "1.0.0", 0, 0, RuntimeSemantics.UnloadPolicy.DRAIN),
            List.of(RuntimeBinding.available(operation, provider, "1.0.0", invocation -> {
                throw new AssertionError("Settlement must not execute runtime operations");
            })));
        CatalogVersion version = new CatalogVersion(1, 0);
        CatalogContribution contribution = CatalogContribution.builder(owner, "1.0.0", new CatalogContractRange(version, version),
                CatalogProvenance.fromText(CatalogProvenance.SourceKind.BUNDLED, "settlement-test", "1.0.0", "test", "test"))
            .categories(List.of(new CatalogCategoryDescriptor(CapabilityId.of("flow"), "Flow", "Flow operations.", 1)))
            .capabilities(List.of(new CatalogCapabilityDescriptor(CapabilityId.of("flow"), 1, false, InspectorFallback.GENERIC),
                new CatalogCapabilityDescriptor(handler.id(), 1, false, InspectorFallback.GENERIC),
                new CatalogCapabilityDescriptor(CapabilityId.of("generic-editor"), 1, false, InspectorFallback.GENERIC)))
            .definitions(List.of(CatalogNodeDescriptor.builder(NodeId.of("settlement"))
                .domain("flow").family("operation").displayName("Settlement").description("Settlement authority test.")
                .pins(List.of(new CatalogNodeDescriptor.Pin(PinId.of("value"), CatalogNodeDescriptor.Direction.INPUT, string,
                    "Value", "The input value supplied to this operation.", CatalogNodeDescriptor.Requirement.REQUIRED, null,
                    ContractRef.of(owner, CapabilityId.of("generic-editor")), null, null, null, null)))
                .category(ContractRef.of(owner, CapabilityId.of("flow")))
                .branches(List.of(new CatalogNodeDescriptor.Branch("failure", "Failure", "The operation reports a failure.",
                    List.of(new CatalogNodeDescriptor.Case("failure", "Failure", "The operation enters the failure case.")))))
                .handler(new CatalogNodeDescriptor.Handler(handler, operationId)).semantics(semantics).requiredCapabilities(Set.of(handler))
                .metadata(Map.of("authoredSource", Map.of("id", "settlement", "handlerCapability", handler.id().value(),
                    "sourceProvenance", Map.of("sourceUri", "settlement-test", "rowIndex", 0,
                        "owner", owner.value(), "sourceHash", "a".repeat(64)))))
                .build()))
            .runtimeRequirements(List.of(operation)).build();
        return new CatalogRuntimeActivation.ActivationRecord(new CatalogCompiler(version, CatalogBindingProof.live(runtime))
            .compile(List.of(contribution), 56L).snapshot().orElseThrow(), runtime.snapshot());
    }
}
