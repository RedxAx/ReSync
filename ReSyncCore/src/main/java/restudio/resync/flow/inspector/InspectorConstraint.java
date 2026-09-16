package restudio.resync.flow.inspector;

import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContractRef;

import java.util.Map;
import java.util.Objects;

public record InspectorConstraint(ContractRef<CapabilityId> id, String title, String description, Map<String, Object> parameters) {
    public InspectorConstraint {
        id = Objects.requireNonNull(id, "constraint id");
        title = InspectorDescription.title(title, "constraint");
        description = InspectorDescription.description(title, description, "constraint");
        parameters = InspectorSupport.map(parameters == null ? Map.of() : parameters, "parameters");
    }

    public InspectorConstraint(ContractRef<CapabilityId> id, String title, String description) {
        this(id, title, description, Map.of());
    }
}
