package restudio.resync.flow.inspector;

import restudio.resync.flow.identity.OwnerId;

import java.util.List;
import java.util.Objects;

public final class InspectorDescriptor {
    private final OwnerId owner;
    private final InspectorId id;
    private final String title;
    private final String description;
    private final List<InspectorSection> sections;
    private final List<InspectorCapability> capabilities;
    private final List<InspectorOptionSource> optionSources;
    private final List<InspectorFunctionSignature> functionSignatures;
    private final List<InspectorValidationRule> validationRules;

    public InspectorDescriptor(OwnerId owner, InspectorId id, String title, String description, List<InspectorSection> sections, List<InspectorCapability> capabilities, List<InspectorOptionSource> optionSources, List<InspectorFunctionSignature> functionSignatures, List<InspectorValidationRule> validationRules) {
        this.owner = Objects.requireNonNull(owner, "inspector owner");
        this.id = Objects.requireNonNull(id, "inspector id");
        this.title = InspectorDescription.title(title, "inspector");
        this.description = InspectorDescription.description(this.title, description, "inspector");
        this.sections = InspectorSupport.list(sections, "inspector section");
        this.capabilities = InspectorSupport.list(capabilities, "inspector capability");
        this.optionSources = InspectorSupport.list(optionSources == null ? List.of() : optionSources, "inspector option source");
        this.functionSignatures = InspectorSupport.list(functionSignatures == null ? List.of() : functionSignatures, "function signature");
        this.validationRules = InspectorSupport.list(validationRules == null ? List.of() : validationRules, "validation rule");
        InspectorContractValidator.requireValid(this);
    }

    public InspectorDescriptor(OwnerId owner, InspectorId id, String title, String description, List<InspectorSection> sections, List<InspectorCapability> capabilities) {
        this(owner, id, title, description, sections, capabilities, List.of(), List.of(), List.of());
    }

    public OwnerId owner() {
        return owner;
    }

    public InspectorId id() {
        return id;
    }

    public String title() {
        return title;
    }

    public String description() {
        return description;
    }

    public List<InspectorSection> sections() {
        return sections;
    }

    public List<InspectorCapability> capabilities() {
        return capabilities;
    }

    public List<InspectorOptionSource> optionSources() {
        return optionSources;
    }

    public List<InspectorFunctionSignature> functionSignatures() {
        return functionSignatures;
    }

    public List<InspectorValidationRule> validationRules() {
        return validationRules;
    }
}
