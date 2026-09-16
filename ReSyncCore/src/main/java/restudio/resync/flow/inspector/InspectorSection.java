package restudio.resync.flow.inspector;

import restudio.resync.flow.identity.InspectorSectionId;

import java.util.List;
import java.util.Objects;

public record InspectorSection(InspectorSectionId id, String title, String description, List<InspectorRow> rows, InspectorCondition visibility) {
    public InspectorSection {
        id = Objects.requireNonNull(id, "section id");
        title = InspectorDescription.title(title, "section");
        description = InspectorDescription.description(title, description, "section");
        rows = InspectorSupport.list(rows, "section row");
        InspectorSupport.unique(rows, row -> row.id().value(), "section row");
        visibility = visibility == null ? InspectorCondition.always() : visibility;
    }
}
