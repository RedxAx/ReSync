package restudio.resync.flow.inspector;

import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.InspectorFieldId;
import restudio.resync.flow.identity.LocalId;

import java.util.List;
import java.util.Objects;

public record InspectorValidationRule(
    InspectorFieldId id,
    String title,
    String description,
    InspectorValidationPhase phase,
    LocalId stage,
    ContractRef<CapabilityId> capability,
    List<InspectorFieldId> appliesTo,
    List<String> diagnosticCodes
) {
    public InspectorValidationRule {
        id = Objects.requireNonNull(id, "validation rule id");
        title = InspectorDescription.title(title, "validation rule");
        description = InspectorDescription.description(title, description, "validation rule");
        phase = Objects.requireNonNull(phase, "validation phase");
        stage = Objects.requireNonNull(stage, "validation stage");
        capability = Objects.requireNonNull(capability, "validation capability");
        appliesTo = InspectorSupport.list(appliesTo == null ? List.of() : appliesTo, "validation target");
        InspectorSupport.unique(appliesTo, InspectorFieldId::value, "validation target");
        diagnosticCodes = diagnosticCodes == null ? List.of() : diagnosticCodes.stream().map(value -> Objects.requireNonNull(value, "diagnostic code")).toList();
    }

    public InspectorValidationRule(InspectorFieldId id, String title, String description, InspectorValidationPhase phase,
                                   ContractRef<CapabilityId> capability, List<InspectorFieldId> appliesTo) {
        this(id, title, description, phase, id, capability, appliesTo, List.of());
    }
}
