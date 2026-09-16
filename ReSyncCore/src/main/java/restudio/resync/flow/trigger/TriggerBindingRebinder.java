package restudio.resync.flow.trigger;

import restudio.resync.flow.graph.CompiledExecutionPlan;
import restudio.resync.flow.graph.ExecutionTarget;

import java.util.Objects;

public final class TriggerBindingRebinder {
    private TriggerBindingRebinder() {
    }

    public static TriggerBinding rebind(TriggerBindingDocument document, TriggerBinding binding, ExecutionTarget candidate,
                                        CompiledExecutionPlan plan, TriggerSourceCatalog catalog) {
        Objects.requireNonNull(document, "Trigger Binding Document Is Required");
        return TriggerBindingAdmission.admitRebindingCandidate(document, binding, candidate, plan, catalog);
    }
}
