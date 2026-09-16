package restudio.resync.flow.inspector;

import restudio.resync.flow.identity.InspectorFieldId;

import java.util.List;
import java.util.Objects;

public record InspectorRow(InspectorFieldId id, String title, String description, List<InspectorField> fields, InspectorCondition visibility) {
    public InspectorRow {
        id = Objects.requireNonNull(id, "row id");
        title = InspectorDescription.title(title, "row");
        description = InspectorDescription.description(title, description, "row");
        fields = InspectorSupport.list(fields, "row field");
        InspectorSupport.unique(fields, field -> field.id().value(), "row field");
        visibility = visibility == null ? InspectorCondition.always() : visibility;
    }
}
