package restudio.resync.flow.inspector;

import restudio.resync.flow.identity.CaseId;

import java.util.List;
import java.util.Objects;

public record InspectorBranchCase(CaseId id, String title, String description, InspectorCondition when, List<InspectorField> fields) {
    public InspectorBranchCase {
        id = Objects.requireNonNull(id, "branch case id");
        title = InspectorDescription.title(title, "branch case");
        description = InspectorDescription.description(title, description, "branch case");
        when = when == null ? InspectorCondition.always() : when;
        fields = InspectorSupport.list(fields, "branch case fields");
        InspectorSupport.unique(fields, field -> field.id().value(), "branch case field");
    }
}
