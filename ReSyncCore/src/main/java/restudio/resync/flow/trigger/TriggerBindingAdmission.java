package restudio.resync.flow.trigger;

import restudio.resync.flow.graph.CompiledExecutionPlan;
import restudio.resync.flow.graph.CompiledExecutionStep;
import restudio.resync.flow.graph.ExecutionTarget;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.TriggerBindingId;

import java.util.Objects;

public final class TriggerBindingAdmission {
    private static final OwnerId FLOW_OWNER = OwnerId.of("restudio.resync");
    private static final ResourceTypeId FLOW_TYPE = ResourceTypeId.of("flow");

    private TriggerBindingAdmission() {
    }

    public static TriggerBinding admit(TriggerBindingDocument document, TriggerBinding binding,
                                       CompiledExecutionPlan plan, TriggerSourceCatalog catalog) {
        Objects.requireNonNull(document, "Trigger Binding Document Is Required");
        Objects.requireNonNull(binding, "Trigger Binding Is Required");
        TriggerBinding authoritative = requireAuthoritative(document, binding);
        return admitCandidate(document.serverId(), authoritative, plan, catalog);
    }

    static TriggerBinding admitRebindingCandidate(TriggerBindingDocument document, TriggerBinding binding,
                                                  ExecutionTarget candidate, CompiledExecutionPlan plan,
                                                  TriggerSourceCatalog catalog) {
        Objects.requireNonNull(document, "Trigger Binding Document Is Required");
        Objects.requireNonNull(binding, "Trigger Binding Is Required");
        Objects.requireNonNull(candidate, "Candidate Execution Target Is Required");
        TriggerBinding authoritative = requireAuthoritative(document, binding);
        if (!authoritative.target().resource().serverId().equals(candidate.resource().serverId())) {
            throw rejected(binding.id(), Reason.SERVER_MISMATCH, "Rebound Trigger Target Must Remain On The Same Server");
        }
        TriggerBinding rebound = new TriggerBinding(authoritative.id(), authoritative.route(), candidate, authoritative.unknown());
        return admitCandidate(document.serverId(), rebound, plan, catalog);
    }

    private static TriggerBinding requireAuthoritative(TriggerBindingDocument document, TriggerBinding binding) {
        TriggerBinding authoritative = document.bindings().stream()
            .filter(candidate -> candidate.id().equals(binding.id()))
            .findFirst()
            .orElseThrow(() -> rejected(binding.id(), Reason.BINDING_MISSING,
                "Trigger Binding Is Missing From The Authoritative Document"));
        if (!TriggerBindingCodec.INSTANCE.encodeBinding(authoritative).equals(TriggerBindingCodec.INSTANCE.encodeBinding(binding))) {
            throw rejected(binding.id(), Reason.BINDING_MISMATCH,
                "Trigger Binding Does Not Exactly Match The Authoritative Document");
        }
        return authoritative;
    }

    private static TriggerBinding admitCandidate(ServerId serverId, TriggerBinding binding,
                                                  CompiledExecutionPlan plan, TriggerSourceCatalog catalog) {
        Objects.requireNonNull(serverId, "Trigger Binding Server ID Is Required");
        Objects.requireNonNull(binding, "Trigger Binding Is Required");
        Objects.requireNonNull(plan, "Compiled Execution Plan Is Required");
        Objects.requireNonNull(catalog, "Trigger Source Catalog Is Required");
        ExecutionTarget target = binding.target();
        if (!serverId.equals(target.resource().serverId())) {
            throw rejected(binding.id(), Reason.SERVER_MISMATCH, "Trigger Target Must Belong To The Binding Server");
        }
        if (!FLOW_OWNER.equals(target.resource().type().owner()) || !FLOW_TYPE.equals(target.resource().type().id())) {
            throw rejected(binding.id(), Reason.RESOURCE_TYPE_MISMATCH, "Trigger Target Must Be A Flow Resource");
        }
        if (!target.resource().equals(plan.graph())) {
            throw rejected(binding.id(), Reason.TARGET_RESOURCE_MISMATCH, "Compiled Plan Resource Does Not Match The Trigger Target");
        }
        if (target.expectedRevision() != plan.graphRevision()) {
            throw rejected(binding.id(), Reason.STALE_REVISION, "Compiled Plan Revision Does Not Match The Trigger Target");
        }
        if (!target.expectedBinding().equals(plan.catalogBinding())) {
            throw rejected(binding.id(), Reason.STALE_CATALOG_BINDING, "Compiled Plan Catalog Binding Does Not Match The Trigger Target");
        }
        CatalogBinding sourceBinding = catalog.binding();
        if (sourceBinding == null || !sourceBinding.equals(target.expectedBinding()) || !sourceBinding.equals(plan.catalogBinding())) {
            throw rejected(binding.id(), Reason.SOURCE_CATALOG_BINDING_MISMATCH,
                "Trigger Source Catalog Does Not Match The Compiled Plan Catalog Binding");
        }
        CompiledExecutionStep start = plan.steps().stream()
            .filter(step -> step.nodeId().equals(target.startNodeId()))
            .findFirst()
            .orElseThrow(() -> rejected(binding.id(), Reason.START_STEP_MISSING, "Trigger Start Step Is Missing From The Compiled Plan"));
        if (!start.definition().equals(binding.route().source())) {
            throw rejected(binding.id(), Reason.SOURCE_MISMATCH, "Trigger Route Source Does Not Match The Plan Start Step");
        }
        TriggerSourceDescriptor descriptor = resolve(binding, catalog);
        if (!descriptor.active()) {
            throw rejected(binding.id(), Reason.SOURCE_INACTIVE, "Trigger Source Descriptor Is Not Active");
        }
        if (descriptor.kind() != binding.route().kind()) {
            throw rejected(binding.id(), Reason.KIND_MISMATCH, "Trigger Source Kind Does Not Match The Binding Route");
        }
        target.require(plan);
        return binding;
    }

    private static TriggerSourceDescriptor resolve(TriggerBinding binding, TriggerSourceCatalog catalog) {
        try {
            return Objects.requireNonNull(catalog.resolve(binding.route().source()), "Trigger Source Resolution Is Required")
                .orElseThrow(() -> rejected(binding.id(), Reason.SOURCE_MISSING, "Trigger Source Descriptor Is Missing"));
        } catch (AdmissionException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new AdmissionException(binding.id(), Reason.SOURCE_MISSING, "Trigger Source Descriptor Resolution Failed", exception);
        }
    }

    private static AdmissionException rejected(TriggerBindingId id, Reason reason, String message) {
        return new AdmissionException(id, reason, message);
    }

    public enum Reason {
        BINDING_MISSING,
        BINDING_MISMATCH,
        SERVER_MISMATCH,
        RESOURCE_TYPE_MISMATCH,
        TARGET_RESOURCE_MISMATCH,
        STALE_REVISION,
        STALE_CATALOG_BINDING,
        SOURCE_CATALOG_BINDING_MISMATCH,
        START_STEP_MISSING,
        SOURCE_MISMATCH,
        SOURCE_MISSING,
        SOURCE_INACTIVE,
        KIND_MISMATCH
    }

    public static final class AdmissionException extends IllegalArgumentException {
        private final TriggerBindingId bindingId;
        private final Reason reason;

        public AdmissionException(TriggerBindingId bindingId, Reason reason, String message) {
            super(message);
            this.bindingId = Objects.requireNonNull(bindingId, "Trigger Binding ID Is Required");
            this.reason = Objects.requireNonNull(reason, "Trigger Admission Reason Is Required");
        }

        public AdmissionException(TriggerBindingId bindingId, Reason reason, String message, Throwable cause) {
            super(message, cause);
            this.bindingId = Objects.requireNonNull(bindingId, "Trigger Binding ID Is Required");
            this.reason = Objects.requireNonNull(reason, "Trigger Admission Reason Is Required");
        }

        public TriggerBindingId bindingId() {
            return bindingId;
        }

        public Reason reason() {
            return reason;
        }
    }
}
