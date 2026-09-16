package restudio.resync.flow.graph;

import restudio.resync.flow.identity.CaseId;
import restudio.resync.flow.identity.PinId;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record BranchCase(CaseId caseId, Map<PinId, PinValue> values, InspectorState inspectorState, OpaqueData unknown) {
    public BranchCase {
        caseId = Objects.requireNonNull(caseId, "caseId");
        values = GraphCollections.pinValues(values);
        inspectorState = inspectorState != null ? inspectorState : InspectorState.empty();
        unknown = unknown != null ? unknown : OpaqueData.empty();
        unknown.rejectKnownFields("caseId", "values", "inspectorState");
    }

    public BranchCase(CaseId caseId, Map<PinId, PinValue> values) {
        this(caseId, values, InspectorState.empty(), OpaqueData.empty());
    }

    Map<String, Object> canonicalValue() {
        LinkedHashMap<String, Object> values = new LinkedHashMap<>();
        values.put("caseId", caseId.canonicalText());
        values.put("values", PinValue.canonicalValues(this.values));
        values.put("inspectorState", inspectorState.canonicalValue());
        return OpaqueData.mergeKnownFields(unknown, values);
    }
}
