package restudio.resync.flow.inspector;

import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.InspectorFieldId;
import restudio.resync.flow.type.TypeExpr;

import java.util.Objects;

public record InspectorOptionSource(
    InspectorFieldId id,
    String title,
    String description,
    TypeExpr optionType,
    OptionQuerySchemaV1 querySchema,
    ContractRef<CapabilityId> capability,
    int pageLimit,
    String invalidationKey
) {
    public InspectorOptionSource {
        id = Objects.requireNonNull(id, "option source id");
        title = InspectorDescription.title(title, "option source");
        description = InspectorDescription.description(title, description, "option source");
        optionType = Objects.requireNonNull(optionType, "option type");
        querySchema = Objects.requireNonNull(querySchema, "query schema");
        capability = Objects.requireNonNull(capability, "capability");
        if (pageLimit < 1 || pageLimit > 500) {
            throw new IllegalArgumentException("Option source page limit must be between 1 and 500");
        }
        invalidationKey = InspectorDescription.required(invalidationKey, "option source invalidation key");
    }

    public InspectorOptionSource(InspectorFieldId id, String title, String description, TypeExpr optionType, Object legacyQuerySchema,
                                 ContractRef<CapabilityId> capability, int pageLimit, String invalidationKey) {
        this(id, title, description, optionType, OptionQuerySchemaV1.fromLegacy(legacyQuerySchema), capability, pageLimit, invalidationKey);
    }

    public InspectorOptionSource(InspectorFieldId id, String title, String description, TypeExpr optionType, Object legacyQuerySchema,
                                 ContractRef<CapabilityId> capability, int pageLimit) {
        this(id, title, description, optionType, OptionQuerySchemaV1.fromLegacy(legacyQuerySchema), capability, pageLimit, id.value());
    }

    public InspectorOptionSource(InspectorFieldId id, String title, String description, TypeExpr optionType,
                                 ContractRef<CapabilityId> capability, int pageLimit) {
        this(id, title, description, optionType, OptionQuerySchemaV1.empty(), capability, pageLimit, id.value());
    }
}
