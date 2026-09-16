package restudio.resync.flow.inspector;

import restudio.resync.flow.identity.InspectorFieldId;
import restudio.resync.flow.type.TypeExpr;

import java.util.List;

public final class InspectorObjectField extends InspectorField {
    private final List<InspectorField> fields;

    public InspectorObjectField(InspectorFieldId id, String title, String description, TypeExpr valueType, List<InspectorField> fields, InspectorCondition visibility, InspectorFallback fallback, InspectorCapability editor) {
        super(id, title, description, valueType, visibility, fallback, editor);
        this.fields = InspectorSupport.list(fields == null ? List.of() : fields, "object fields");
        InspectorSupport.unique(this.fields, field -> field.id().value(), "object field");
    }

    @Override
    public Kind kind() {
        return Kind.OBJECT;
    }

    @Override
    public List<InspectorField> children() {
        return fields;
    }

    public List<InspectorField> fields() {
        return fields;
    }
}
