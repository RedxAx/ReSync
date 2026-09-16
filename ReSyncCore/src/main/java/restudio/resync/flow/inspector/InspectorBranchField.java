package restudio.resync.flow.inspector;

import restudio.resync.flow.identity.BranchId;
import restudio.resync.flow.identity.InspectorFieldId;
import restudio.resync.flow.type.TypeExpr;

import java.util.List;
import java.util.Objects;

public final class InspectorBranchField extends InspectorField {
    private final BranchId branchId;
    private final InspectorFieldId selectorField;
    private final List<InspectorBranchCase> cases;

    public InspectorBranchField(InspectorFieldId id, String title, String description, TypeExpr valueType, BranchId branchId, InspectorFieldId selectorField, List<InspectorBranchCase> cases, InspectorCondition visibility, InspectorFallback fallback, InspectorCapability editor) {
        super(id, title, description, valueType, visibility, fallback, editor);
        if (id.equals(selectorField)) {
            throw new IllegalArgumentException("A branch cannot select itself");
        }
        this.branchId = Objects.requireNonNull(branchId, "branch id");
        this.selectorField = Objects.requireNonNull(selectorField, "selector field");
        this.cases = InspectorSupport.list(cases, "branch cases");
        if (this.cases.isEmpty()) {
            throw new IllegalArgumentException("Branches require at least one case");
        }
        InspectorSupport.unique(this.cases, caseDescriptor -> caseDescriptor.id().value(), "branch case");
    }

    @Override
    public Kind kind() {
        return Kind.BRANCH;
    }

    public BranchId branchId() {
        return branchId;
    }

    public InspectorFieldId selectorField() {
        return selectorField;
    }

    public List<InspectorBranchCase> cases() {
        return cases;
    }

    @Override
    public List<InspectorField> children() {
        return cases.stream().flatMap(caseDescriptor -> caseDescriptor.fields().stream()).toList();
    }
}
