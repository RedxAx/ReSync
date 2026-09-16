package restudio.resync.flow.trigger;

import org.junit.jupiter.api.Test;
import restudio.resync.flow.catalog.CatalogNodeDescriptor;
import restudio.resync.flow.graph.CompiledExecutionPlan;
import restudio.resync.flow.graph.CompiledExecutionStep;
import restudio.resync.flow.graph.ExecutionTarget;
import restudio.resync.flow.graph.OpaqueData;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.NodeInstanceId;
import restudio.resync.flow.identity.OperationId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.identity.TriggerBindingId;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TriggerBindingAdmissionTest {
    private static final OwnerId OWNER = OwnerId.of("restudio.resync");
    private static final ServerId SERVER = ServerId.of(UUID.fromString("11111111-1111-4111-8111-111111111111"));
    private static final ServerResourceLocator FLOW = locator(SERVER, "flow", "join-flow");
    private static final NodeInstanceId START = NodeInstanceId.of(UUID.fromString("22222222-2222-4222-8222-222222222222"));
    private static final ContractRef<NodeId> SOURCE = ContractRef.of(OWNER, NodeId.of("player-join"));
    private static final CatalogBinding CATALOG = new CatalogBinding(7, hash('a'), hash('b'));
    private static final TriggerSourceCatalog EVENT_CATALOG = catalog(CATALOG, true, TriggerKind.EVENT);

    @Test
    void admissionRequiresTheExactSameServerFlowPlanStartAndActiveDescriptor() {
        ExecutionTarget target = new ExecutionTarget(FLOW, START, 4, CATALOG);
        TriggerBinding binding = binding(target);
        TriggerBindingDocument document = new TriggerBindingDocument(SERVER, 3,
            UUID.fromString("33333333-3333-4333-8333-333333333333"), List.of(binding));

        assertSame(binding, TriggerBindingAdmission.admit(document, binding, plan(FLOW, 4, CATALOG, SOURCE), EVENT_CATALOG));

        TriggerBindingAdmission.AdmissionException staleRevision = assertThrows(
            TriggerBindingAdmission.AdmissionException.class,
            () -> TriggerBindingAdmission.admit(document, binding, plan(FLOW, 5, CATALOG, SOURCE), EVENT_CATALOG));
        assertEquals(TriggerBindingAdmission.Reason.STALE_REVISION, staleRevision.reason());

        TriggerBindingAdmission.AdmissionException wrongSource = assertThrows(
            TriggerBindingAdmission.AdmissionException.class,
            () -> TriggerBindingAdmission.admit(document, binding,
                plan(FLOW, 4, CATALOG, ContractRef.of(OWNER, NodeId.of("server-start"))), EVENT_CATALOG));
        assertEquals(TriggerBindingAdmission.Reason.SOURCE_MISMATCH, wrongSource.reason());

        TriggerBindingAdmission.AdmissionException inactive = assertThrows(
            TriggerBindingAdmission.AdmissionException.class,
            () -> TriggerBindingAdmission.admit(document, binding, plan(FLOW, 4, CATALOG, SOURCE),
                catalog(CATALOG, false, TriggerKind.EVENT)));
        assertEquals(TriggerBindingAdmission.Reason.SOURCE_INACTIVE, inactive.reason());

        TriggerBindingAdmission.AdmissionException wrongKind = assertThrows(
            TriggerBindingAdmission.AdmissionException.class,
            () -> TriggerBindingAdmission.admit(document, binding, plan(FLOW, 4, CATALOG, SOURCE),
                catalog(CATALOG, true, TriggerKind.SYSTEM)));
        assertEquals(TriggerBindingAdmission.Reason.KIND_MISMATCH, wrongKind.reason());

        CatalogBinding unrelatedCatalog = new CatalogBinding(7, hash('c'), hash('d'));
        TriggerBindingAdmission.AdmissionException wrongCatalog = assertThrows(
            TriggerBindingAdmission.AdmissionException.class,
            () -> TriggerBindingAdmission.admit(document, binding, plan(FLOW, 4, CATALOG, SOURCE),
                catalog(unrelatedCatalog, true, TriggerKind.EVENT)));
        assertEquals(TriggerBindingAdmission.Reason.SOURCE_CATALOG_BINDING_MISMATCH, wrongCatalog.reason());
    }

    @Test
    void admissionRequiresTheExactBindingStoredInTheAuthoritativeDocument() {
        TriggerBinding binding = binding(new ExecutionTarget(FLOW, START, 4, CATALOG));
        TriggerBindingDocument document = document(SERVER, binding);
        TriggerBinding changed = new TriggerBinding(binding.id(), binding.route(), binding.target(),
            OpaqueData.of(Map.of("future", "changed")));
        TriggerBinding missing = new TriggerBinding(
            TriggerBindingId.of(UUID.fromString("88888888-8888-4888-8888-888888888888")),
            binding.route(), binding.target(), binding.unknown());

        TriggerBindingAdmission.AdmissionException mismatch = assertThrows(
            TriggerBindingAdmission.AdmissionException.class,
            () -> TriggerBindingAdmission.admit(document, changed, plan(FLOW, 4, CATALOG, SOURCE), EVENT_CATALOG));
        assertEquals(TriggerBindingAdmission.Reason.BINDING_MISMATCH, mismatch.reason());

        TriggerBindingAdmission.AdmissionException absent = assertThrows(
            TriggerBindingAdmission.AdmissionException.class,
            () -> TriggerBindingAdmission.admit(document, missing, plan(FLOW, 4, CATALOG, SOURCE), EVENT_CATALOG));
        assertEquals(TriggerBindingAdmission.Reason.BINDING_MISSING, absent.reason());
    }

    @Test
    void admissionRejectsCrossServerAndNonFlowTargetsBeforeActivation() {
        ServerId otherServer = ServerId.of(UUID.fromString("44444444-4444-4444-8444-444444444444"));
        ExecutionTarget crossServer = new ExecutionTarget(locator(otherServer, "flow", "join-flow"), START, 4, CATALOG);
        TriggerBinding crossServerBinding = binding(crossServer);
        TriggerBindingAdmission.AdmissionException serverFailure = assertThrows(
            TriggerBindingAdmission.AdmissionException.class,
            () -> TriggerBindingAdmission.admit(document(SERVER, crossServerBinding), crossServerBinding,
                plan(crossServer.resource(), 4, CATALOG, SOURCE), EVENT_CATALOG));
        assertEquals(TriggerBindingAdmission.Reason.SERVER_MISMATCH, serverFailure.reason());

        ExecutionTarget function = new ExecutionTarget(locator(SERVER, "function", "join-flow"), START, 4, CATALOG);
        TriggerBinding functionBinding = binding(function);
        TriggerBindingAdmission.AdmissionException typeFailure = assertThrows(
            TriggerBindingAdmission.AdmissionException.class,
            () -> TriggerBindingAdmission.admit(document(SERVER, functionBinding), functionBinding,
                plan(function.resource(), 4, CATALOG, SOURCE), EVENT_CATALOG));
        assertEquals(TriggerBindingAdmission.Reason.RESOURCE_TYPE_MISMATCH, typeFailure.reason());

        ServerResourceLocator foreignFlow = locator(SERVER, OwnerId.of("other.owner"), "flow", "join-flow");
        TriggerBinding foreignBinding = binding(new ExecutionTarget(foreignFlow, START, 4, CATALOG));
        TriggerBindingAdmission.AdmissionException ownerFailure = assertThrows(
            TriggerBindingAdmission.AdmissionException.class,
            () -> TriggerBindingAdmission.admit(document(SERVER, foreignBinding), foreignBinding,
                plan(foreignFlow, 4, CATALOG, SOURCE), EVENT_CATALOG));
        assertEquals(TriggerBindingAdmission.Reason.RESOURCE_TYPE_MISMATCH, ownerFailure.reason());
    }

    @Test
    void rebindingChangesOnlyTheExactTargetAndReportsStaleCatalogBindings() {
        ExecutionTarget original = new ExecutionTarget(FLOW, START, 4, CATALOG);
        TriggerBinding binding = binding(original);
        CatalogBinding nextCatalog = new CatalogBinding(8, hash('c'), hash('d'));
        ExecutionTarget candidate = new ExecutionTarget(FLOW, START, 5, nextCatalog);
        TriggerBindingDocument document = document(SERVER, binding);

        TriggerBinding rebound = TriggerBindingRebinder.rebind(document, binding, candidate,
            plan(FLOW, 5, nextCatalog, SOURCE), catalog(nextCatalog, true, TriggerKind.EVENT));

        assertEquals(binding.id(), rebound.id());
        assertEquals(binding.route(), rebound.route());
        assertSame(binding.unknown(), rebound.unknown());
        assertSame(candidate, rebound.target());

        CatalogBinding wrongCatalog = new CatalogBinding(8, hash('e'), hash('f'));
        TriggerBindingAdmission.AdmissionException staleBinding = assertThrows(
            TriggerBindingAdmission.AdmissionException.class,
            () -> TriggerBindingRebinder.rebind(document, binding, candidate,
                plan(FLOW, 5, wrongCatalog, SOURCE), catalog(wrongCatalog, true, TriggerKind.EVENT)));
        assertEquals(TriggerBindingAdmission.Reason.STALE_CATALOG_BINDING, staleBinding.reason());

        TriggerBindingAdmission.AdmissionException staleSourceCatalog = assertThrows(
            TriggerBindingAdmission.AdmissionException.class,
            () -> TriggerBindingRebinder.rebind(document, binding, candidate,
                plan(FLOW, 5, nextCatalog, SOURCE), EVENT_CATALOG));
        assertEquals(TriggerBindingAdmission.Reason.SOURCE_CATALOG_BINDING_MISMATCH, staleSourceCatalog.reason());

        TriggerBinding modified = new TriggerBinding(binding.id(), binding.route(), binding.target(),
            OpaqueData.of(Map.of("future", "modified")));
        TriggerBindingAdmission.AdmissionException modifiedAuthority = assertThrows(
            TriggerBindingAdmission.AdmissionException.class,
            () -> TriggerBindingRebinder.rebind(document, modified, candidate,
                plan(FLOW, 5, nextCatalog, SOURCE), catalog(nextCatalog, true, TriggerKind.EVENT)));
        assertEquals(TriggerBindingAdmission.Reason.BINDING_MISMATCH, modifiedAuthority.reason());
    }

    private static TriggerBinding binding(ExecutionTarget target) {
        return new TriggerBinding(TriggerBindingId.of(UUID.fromString("55555555-5555-4555-8555-555555555555")),
            new TriggerRoute(TriggerKind.EVENT, SOURCE), target, OpaqueData.of(Map.of("future", "kept")));
    }

    private static CompiledExecutionPlan plan(ServerResourceLocator resource, long revision, CatalogBinding binding,
                                              ContractRef<NodeId> source) {
        CatalogNodeDescriptor.Handler handler = new CatalogNodeDescriptor.Handler(
            ContractRef.of(OWNER, CapabilityId.of("execute")), ContractRef.of(OWNER, OperationId.of("run")));
        CompiledExecutionStep step = new CompiledExecutionStep(
            UUID.fromString("66666666-6666-4666-8666-666666666666"), START, source, handler,
            Map.of(), Map.of(), OpaqueData.empty());
        return new CompiledExecutionPlan(UUID.fromString("77777777-7777-4777-8777-777777777777"), resource,
            revision, binding, hash('9'), List.of(step), List.of(), List.of(), OpaqueData.empty());
    }

    private static ServerResourceLocator locator(ServerId server, String type, String id) {
        return locator(server, OWNER, type, id);
    }

    private static ServerResourceLocator locator(ServerId server, OwnerId owner, String type, String id) {
        return new ServerResourceLocator(server, ContractRef.of(owner, ResourceTypeId.of(type)), id);
    }

    private static TriggerBindingDocument document(ServerId server, TriggerBinding binding) {
        return new TriggerBindingDocument(server, 3,
            UUID.fromString("33333333-3333-4333-8333-333333333333"), List.of(binding));
    }

    private static TriggerSourceCatalog catalog(CatalogBinding binding, boolean active, TriggerKind kind) {
        return new TriggerSourceCatalog() {
            @Override
            public CatalogBinding binding() {
                return binding;
            }

            @Override
            public Optional<TriggerSourceDescriptor> resolve(ContractRef<NodeId> source) {
                return SOURCE.equals(source) ? Optional.of(new TriggerSourceDescriptor(active, kind)) : Optional.empty();
            }
        };
    }

    private static ContentHash hash(char value) {
        return ContentHash.of(String.valueOf(value).repeat(64));
    }
}
