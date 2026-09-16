package restudio.resync.flow.inspector;

import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContractRef;

import java.util.List;
import java.util.Objects;

public record InspectorCapability(
    ContractRef<CapabilityId> id,
    String title,
    String description,
    InspectorValueSchema inputSchema,
    InspectorValueSchema outputSchema,
    List<InspectorConstraint> constraints,
    ContractRef<CapabilityId> fallbackCapability,
    InspectorFallback fallback,
    InspectorDraftSerialization draftSerialization,
    InspectorValidationBehavior validation
) {
    public InspectorCapability {
        id = Objects.requireNonNull(id, "capability id");
        title = InspectorDescription.title(title, "capability");
        description = InspectorDescription.description(title, description, "capability");
        inputSchema = Objects.requireNonNull(inputSchema, "input schema");
        outputSchema = Objects.requireNonNull(outputSchema, "output schema");
        constraints = InspectorSupport.list(constraints == null ? List.of() : constraints, "capability constraints");
        InspectorSupport.unique(constraints, InspectorConstraint::id, "capability constraint");
        if (fallback == InspectorFallback.REJECT && fallbackCapability != null) {
            throw new IllegalArgumentException("Rejecting capabilities cannot declare a fallback capability");
        }
        fallback = Objects.requireNonNull(fallback, "fallback");
        draftSerialization = Objects.requireNonNull(draftSerialization, "draft serialization");
        validation = Objects.requireNonNull(validation, "validation");
    }

    public InspectorCapability(ContractRef<CapabilityId> id, String title, String description, InspectorValueSchema inputSchema, InspectorValueSchema outputSchema, List<InspectorConstraint> constraints, ContractRef<CapabilityId> fallbackCapability, InspectorFallback fallback) {
        this(id, title, description, inputSchema, outputSchema, constraints, fallbackCapability, fallback, InspectorDraftSerialization.canonicalJson(), InspectorValidationBehavior.standard());
    }
}
