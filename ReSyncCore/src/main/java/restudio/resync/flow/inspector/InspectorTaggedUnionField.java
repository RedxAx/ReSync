package restudio.resync.flow.inspector;

import restudio.resync.flow.identity.CaseId;
import restudio.resync.flow.identity.InspectorFieldId;
import restudio.resync.flow.type.TypeExpr;

import java.util.List;
import java.util.stream.Collectors;

public final class InspectorTaggedUnionField extends InspectorField {
    private final List<InspectorUnionCase> cases;

    public InspectorTaggedUnionField(InspectorFieldId id, String title, String description, TypeExpr valueType, List<InspectorUnionCase> cases, InspectorCondition visibility, InspectorFallback fallback, InspectorCapability editor) {
        super(id, title, description, valueType, visibility, fallback, editor);
        if (!(valueType instanceof TypeExpr.UnionType unionType)) {
            throw new IllegalArgumentException("Tagged union fields require a union type");
        }
        this.cases = InspectorSupport.list(cases, "union cases");
        if (unionType.variants().size() != this.cases.size()) {
            throw new IllegalArgumentException("Every union variant needs exactly one inspector case");
        }
        InspectorSupport.unique(this.cases, caseDescriptor -> caseDescriptor.id().value(), "union case");
        var variants = unionType.variants().stream().map(TypeExpr.UnionVariant::variantId).collect(Collectors.toSet());
        for (var caseDescriptor : this.cases) {
            if (!variants.contains(caseDescriptor.id().value())) {
                throw new IllegalArgumentException("Union case does not reference a union variant: " + caseDescriptor.id());
            }
        }
    }

    @Override
    public Kind kind() {
        return Kind.UNION;
    }

    public List<InspectorUnionCase> cases() {
        return cases;
    }

    @Override
    public List<InspectorField> children() {
        return cases.stream().map(InspectorUnionCase::field).toList();
    }
}
