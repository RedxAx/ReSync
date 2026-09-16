package restudio.resync.flow.inspector;

import restudio.resync.flow.identity.CaseId;
import restudio.resync.flow.type.TypeExpr;

import java.util.Objects;

public record InspectorUnionCase(CaseId id, String title, String description, TypeExpr type, InspectorField field, InspectorCondition visibility) {
    public InspectorUnionCase {
        id = Objects.requireNonNull(id, "union case id");
        title = InspectorDescription.title(title, "union case");
        description = InspectorDescription.description(title, description, "union case");
        type = Objects.requireNonNull(type, "union case type");
        field = Objects.requireNonNull(field, "union case field");
        if (!type.equals(field.valueType())) {
            throw new IllegalArgumentException("Union case field type must match the case type");
        }
        visibility = visibility == null ? InspectorCondition.always() : visibility;
    }

    public InspectorUnionCase(CaseId id, String title, String description, InspectorField field, InspectorCondition visibility) {
        this(id, title, description, field.valueType(), field, visibility);
    }
}
