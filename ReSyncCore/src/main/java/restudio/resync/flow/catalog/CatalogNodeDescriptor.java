package restudio.resync.flow.catalog;

import restudio.resync.flow.identity.BranchId;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.CaseId;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.InspectorFieldId;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.OperationId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.PinId;
import restudio.resync.flow.identity.RepeatableGroupId;
import restudio.resync.flow.identity.ModeId;
import restudio.resync.flow.inspector.InspectorCondition;
import restudio.resync.flow.inspector.InspectorId;
import restudio.resync.flow.runtime.RuntimeSemantics;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypedValue;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class CatalogNodeDescriptor {
    private final NodeId id;
    private final int schemaVersion;
    private final Lifecycle lifecycle;
    private final String domain;
    private final String family;
    private final String displayName;
    private final String description;
    private final ContractRef<CapabilityId> category;
    private final List<Pin> pins;
    private final List<Mode> modes;
    private final List<Branch> branches;
    private final List<RepeatableGroup> repeatables;
    private final InspectorId inspector;
    private final Handler handler;
    private final RuntimeSemantics semantics;
    private final Set<ContractRef<CapabilityId>> requiredCapabilities;
    private final ContractRef<NodeId> replacementIdentity;
    private final PreviewIntent preview;
    private final Map<String, Object> metadata;

    public CatalogNodeDescriptor(NodeId id, int schemaVersion, Lifecycle lifecycle, String domain, String family, String displayName, String description, ContractRef<CapabilityId> category, List<Pin> pins, List<Mode> modes, List<Branch> branches, List<RepeatableGroup> repeatables, InspectorId inspector, Handler handler, RuntimeSemantics semantics, Set<ContractRef<CapabilityId>> requiredCapabilities, ContractRef<NodeId> replacementIdentity) {
        this(id, schemaVersion, lifecycle, domain, family, displayName, description, category, pins, modes, branches, repeatables, inspector, handler, semantics, requiredCapabilities, replacementIdentity, PreviewIntent.none());
    }

    public CatalogNodeDescriptor(NodeId id, int schemaVersion, Lifecycle lifecycle, String domain, String family, String displayName, String description, ContractRef<CapabilityId> category, List<Pin> pins, List<Mode> modes, List<Branch> branches, List<RepeatableGroup> repeatables, InspectorId inspector, Handler handler, RuntimeSemantics semantics, Set<ContractRef<CapabilityId>> requiredCapabilities, ContractRef<NodeId> replacementIdentity, PreviewIntent preview) {
        this(id, schemaVersion, lifecycle, domain, family, displayName, description, category, pins, modes, branches, repeatables, inspector, handler, semantics, requiredCapabilities, replacementIdentity, preview, Map.of());
    }

    public CatalogNodeDescriptor(NodeId id, int schemaVersion, Lifecycle lifecycle, String domain, String family, String displayName, String description, ContractRef<CapabilityId> category, List<Pin> pins, List<Mode> modes, List<Branch> branches, List<RepeatableGroup> repeatables, InspectorId inspector, Handler handler, RuntimeSemantics semantics, Set<ContractRef<CapabilityId>> requiredCapabilities, ContractRef<NodeId> replacementIdentity, PreviewIntent preview, Map<String, Object> metadata) {
        this.id = Objects.requireNonNull(id, "nodeId");
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("Node schema version must be positive");
        }
        this.schemaVersion = schemaVersion;
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.domain = CatalogIds.local(domain, "domain");
        this.family = CatalogIds.local(family, "family");
        this.displayName = CatalogIds.text(displayName, "displayName", 128);
        this.description = CatalogIds.description(description, "description", 280, 24);
        this.category = Objects.requireNonNull(category, "category");
        this.pins = pins == null ? List.of() : List.copyOf(pins);
        this.modes = modes == null ? List.of() : List.copyOf(modes);
        this.branches = branches == null ? List.of() : List.copyOf(branches);
        this.repeatables = repeatables == null ? List.of() : List.copyOf(repeatables);
        validateRepeatableStructure(this.pins, this.repeatables);
        this.inspector = inspector;
        this.handler = Objects.requireNonNull(handler, "handler");
        this.semantics = Objects.requireNonNull(semantics, "semantics");
        this.requiredCapabilities = requiredCapabilities == null ? Set.of() : Set.copyOf(requiredCapabilities);
        this.replacementIdentity = replacementIdentity;
        this.preview = Objects.requireNonNull(preview, "preview");
        this.metadata = immutableMetadata(metadata);
    }

    public static Builder builder(NodeId id) {
        return new Builder(id);
    }

    public static Builder builder(String id) {
        return builder(NodeId.of(id));
    }

    public NodeId id() {
        return id;
    }

    public int schemaVersion() {
        return schemaVersion;
    }

    public Lifecycle lifecycle() {
        return lifecycle;
    }

    public String domain() {
        return domain;
    }

    public String family() {
        return family;
    }

    public String displayName() {
        return displayName;
    }

    public String description() {
        return description;
    }

    public ContractRef<CapabilityId> category() {
        return category;
    }

    public List<Pin> pins() {
        return pins;
    }

    public List<Mode> modes() {
        return modes;
    }

    public List<Branch> branches() {
        return branches;
    }

    public List<RepeatableGroup> repeatables() {
        return repeatables;
    }

    public InspectorId inspector() {
        return inspector;
    }

    public Handler handler() {
        return handler;
    }

    public RuntimeSemantics semantics() {
        return semantics;
    }

    public Set<ContractRef<CapabilityId>> requiredCapabilities() {
        return requiredCapabilities;
    }

    public ContractRef<NodeId> replacementIdentity() {
        return replacementIdentity;
    }

    public PreviewIntent preview() {
        return preview;
    }

    public Map<String, Object> metadata() {
        return metadata;
    }

    public ContractRef<NodeId> reference(OwnerId owner) {
        return ContractRef.of(owner, id);
    }

    public enum Lifecycle {
        ACTIVE,
        DEPRECATED,
        RETIRING,
        MIGRATION_ONLY
    }

    public enum Direction {
        INPUT,
        OUTPUT
    }

    public enum Requirement {
        REQUIRED,
        OPTIONAL,
        DEFAULTED,
        CONDITIONAL
    }

    public record RepeatableIntent(boolean enabled, int minimum, int maximum, boolean ordered, RepeatableGroupId groupId) {
        public RepeatableIntent(boolean enabled, int minimum, int maximum, boolean ordered) {
            this(enabled, minimum, maximum, ordered, null);
        }

        public RepeatableIntent(RepeatableGroupId groupId, int minimum, int maximum, boolean ordered) {
            this(true, minimum, maximum, ordered, Objects.requireNonNull(groupId, "repeatable group id"));
        }

        public RepeatableIntent {
            if (minimum < 0 || maximum < minimum) {
                throw new IllegalArgumentException("Invalid repeatable pin bounds");
            }
            if (!enabled && (minimum != 0 || maximum != 0)) {
                throw new IllegalArgumentException("Disabled repeatable pins must have zero bounds");
            }
            if (!enabled && groupId != null) {
                throw new IllegalArgumentException("Disabled repeatable pins cannot declare a group");
            }
        }

        public static RepeatableIntent disabled() {
            return new RepeatableIntent(false, 0, 0, false);
        }
    }

    public record PinPresentation(String widget, List<TypedValue> options, Map<String, Object> constraints, Map<String, Object> visibleWhen) {
        public PinPresentation {
            widget = widget == null ? null : CatalogIds.text(widget, "widget", 128);
            options = options == null ? List.of() : List.copyOf(options);
            constraints = immutablePresentationMap(constraints, "constraints");
            visibleWhen = immutablePresentationMap(visibleWhen, "visibleWhen");
        }

        public static PinPresentation empty() {
            return new PinPresentation(null, List.of(), Map.of(), Map.of());
        }

        public List<TypedValue> staticOptions() {
            return options;
        }

        public Map<String, Object> visibilityPolicy() {
            return visibleWhen;
        }

        public Map<String, Object> visibleWhenPolicy() {
            return visibleWhen;
        }

        public boolean isEmpty() {
            return widget == null && options.isEmpty() && constraints.isEmpty() && visibleWhen.isEmpty();
        }
    }

    public record Pin(PinId id, Direction direction, TypeExpr type, String displayName, String description, Requirement requirement, TypedValue defaultValue, ContractRef<CapabilityId> editor, ContractRef<InspectorFieldId> optionSource, InspectorCondition visibility, RepeatableIntent repeatable, String resourceRole, PinPresentation presentation) {
        public Pin(String id, Direction direction, TypeExpr type, String displayName, String description, Requirement requirement, ContractRef<CapabilityId> editor) {
            this(PinId.of(id), direction, type, displayName, description, requirement, null, editor, null, InspectorCondition.always(), RepeatableIntent.disabled(), null, PinPresentation.empty());
        }

        public Pin(PinId id, Direction direction, TypeExpr type, String displayName, String description, Requirement requirement, TypedValue defaultValue, ContractRef<CapabilityId> editor, ContractRef<InspectorFieldId> optionSource, InspectorCondition visibility, RepeatableIntent repeatable) {
            this(id, direction, type, displayName, description, requirement, defaultValue, editor, optionSource, visibility, repeatable, null, PinPresentation.empty());
        }

        public Pin(PinId id, Direction direction, TypeExpr type, String displayName, String description, Requirement requirement, TypedValue defaultValue, ContractRef<CapabilityId> editor, ContractRef<InspectorFieldId> optionSource, InspectorCondition visibility, RepeatableIntent repeatable, String resourceRole) {
            this(id, direction, type, displayName, description, requirement, defaultValue, editor, optionSource, visibility, repeatable, resourceRole, PinPresentation.empty());
        }

        public Pin {
            id = Objects.requireNonNull(id, "pinId");
            direction = Objects.requireNonNull(direction, "direction");
            type = Objects.requireNonNull(type, "pinType");
            displayName = CatalogIds.text(displayName, "displayName", 128);
            description = CatalogIds.description(description, "description", 240, 16);
            requirement = Objects.requireNonNull(requirement, "requirement");
            if (defaultValue != null && !type.equals(defaultValue.type())) {
                throw new IllegalArgumentException("Pin default type must match the pin type");
            }
            if (requirement == Requirement.DEFAULTED && defaultValue == null) {
                throw new IllegalArgumentException("Defaulted pins require a typed default");
            }
            editor = Objects.requireNonNull(editor, "editor");
            visibility = visibility == null ? InspectorCondition.always() : visibility;
            repeatable = repeatable == null ? RepeatableIntent.disabled() : repeatable;
            resourceRole = resourceRole == null ? null : CatalogIds.text(resourceRole, "resourceRole", 240);
            presentation = presentation == null ? PinPresentation.empty() : presentation;
            for (TypedValue option : presentation.options()) {
                if (!type.equals(option.type())) {
                    throw new IllegalArgumentException("Pin presentation option type must match the pin type");
                }
                if (option.state() == TypedValue.State.ABSENT) {
                    throw new IllegalArgumentException("Pin presentation options cannot be absent");
                }
                if (option.state() == TypedValue.State.NULL && !allowsNull(type)) {
                    throw new IllegalArgumentException("Pin presentation option null is not permitted by the pin type");
                }
            }
        }

    }

    public record Mode(ModeId id, String displayName, String description, InspectorCondition visibility, ContractRef<CapabilityId> transformation) {
        public Mode(String id, String displayName, String description, InspectorCondition visibility, ContractRef<CapabilityId> transformation) {
            this(ModeId.of(id), displayName, description, visibility, transformation);
        }

        public Mode {
            id = Objects.requireNonNull(id, "modeId");
            displayName = CatalogIds.text(displayName, "displayName", 128);
            description = CatalogIds.description(description, "description", 240, 16);
            visibility = visibility == null ? InspectorCondition.always() : visibility;
            transformation = Objects.requireNonNull(transformation, "transformation");
        }

    }

    public record Case(CaseId id, String title, String description) {
        public Case(String id, String title, String description) {
            this(CaseId.of(id), title, description);
        }

        public Case {
            id = Objects.requireNonNull(id, "caseId");
            title = CatalogIds.text(title, "title", 128);
            description = CatalogIds.description(description, "description", 240, 16);
        }

    }

    public record Branch(BranchId id, String title, String description, List<Case> cases) {
        public Branch(String id, String title, String description, List<Case> cases) {
            this(BranchId.of(id), title, description, cases);
        }

        public Branch {
            id = Objects.requireNonNull(id, "branchId");
            title = CatalogIds.text(title, "title", 128);
            description = CatalogIds.description(description, "description", 240, 16);
            cases = cases == null ? List.of() : List.copyOf(cases);
            if (cases.isEmpty()) {
                throw new IllegalArgumentException("Branches require at least one case");
            }
        }

    }

    public record RepeatableMember(PinId pinId, Direction direction, TypeExpr type) {
        public RepeatableMember {
            pinId = Objects.requireNonNull(pinId, "repeatable member pin id");
            direction = Objects.requireNonNull(direction, "repeatable member direction");
            type = Objects.requireNonNull(type, "repeatable member type");
        }
    }

    public record RepeatableGroup(RepeatableGroupId id, String title, String description, TypeExpr elementType, int minimum, int maximum, boolean ordered, List<RepeatableMember> members) {
        public RepeatableGroup(String id, String title, String description, TypeExpr elementType, int minimum, int maximum, boolean ordered) {
            this(RepeatableGroupId.of(id), title, description, elementType, minimum, maximum, ordered, List.of());
        }

        public RepeatableGroup(RepeatableGroupId id, String title, String description, TypeExpr elementType, int minimum, int maximum, boolean ordered) {
            this(id, title, description, elementType, minimum, maximum, ordered, List.of());
        }

        public static RepeatableGroup withMembers(RepeatableGroupId id, String title, String description,
                                                  int minimum, int maximum, boolean ordered,
                                                  List<RepeatableMember> members) {
            List<RepeatableMember> exactMembers = List.copyOf(Objects.requireNonNull(members, "repeatable members"));
            if (exactMembers.isEmpty()) {
                throw new IllegalArgumentException("A typed repeatable group requires at least one member");
            }
            TypeExpr elementType = exactMembers.size() == 1
                ? exactMembers.getFirst().type()
                : TypeExpr.tuple(exactMembers.stream().map(RepeatableMember::type).toList());
            return new RepeatableGroup(id, title, description, elementType, minimum, maximum, ordered, exactMembers);
        }

        public RepeatableGroup {
            id = Objects.requireNonNull(id, "repeatableId");
            title = CatalogIds.text(title, "title", 128);
            description = CatalogIds.description(description, "description", 240, 16);
            elementType = Objects.requireNonNull(elementType, "elementType");
            members = members == null ? List.of() : List.copyOf(members);
            if (minimum < 0 || maximum < minimum) {
                throw new IllegalArgumentException("Invalid repeatable group bounds");
            }
            Set<PinId> identities = new HashSet<>();
            for (RepeatableMember member : members) {
                RepeatableMember exactMember = Objects.requireNonNull(member, "repeatable member");
                if (!identities.add(exactMember.pinId())) {
                    throw new IllegalArgumentException("Duplicate repeatable member pin ID: " + exactMember.pinId());
                }
            }
            if (!members.isEmpty()) {
                TypeExpr expectedElementType = members.size() == 1
                    ? members.getFirst().type()
                    : TypeExpr.tuple(members.stream().map(RepeatableMember::type).toList());
                if (!elementType.equals(expectedElementType)) {
                    throw new IllegalArgumentException("Repeatable group element type must preserve every member type");
                }
            }
        }

    }

    public record Handler(ContractRef<CapabilityId> capability, ContractRef<OperationId> operation) {
        public Handler {
            capability = Objects.requireNonNull(capability, "capability");
            operation = Objects.requireNonNull(operation, "operation");
        }
    }

    public record PreviewIntent(String intent, ContractRef<CapabilityId> capability, ContractRef<CapabilityId> fallback, boolean readOnly) {
        public PreviewIntent {
            intent = Objects.requireNonNull(intent, "preview intent");
            if (!Set.of("none", "text", "visual", "custom").contains(intent)) {
                throw new IllegalArgumentException("Unknown preview intent: " + intent);
            }
            if (!readOnly) {
                throw new IllegalArgumentException("Preview intents must be read-only");
            }
            if (intent.equals("none") && (capability != null || fallback != null)) {
                throw new IllegalArgumentException("A disabled preview cannot declare capabilities");
            }
            if (!intent.equals("none") && capability == null) {
                throw new IllegalArgumentException("Enabled previews require a capability");
            }
        }

        public static PreviewIntent none() {
            return new PreviewIntent("none", null, null, true);
        }
    }

    public static final class Builder {
        private final NodeId id;
        private int schemaVersion = 1;
        private Lifecycle lifecycle = Lifecycle.ACTIVE;
        private String domain = "flow";
        private String family = "default";
        private String displayName;
        private String description;
        private ContractRef<CapabilityId> category;
        private List<Pin> pins = List.of();
        private List<Mode> modes = List.of();
        private List<Branch> branches = List.of();
        private List<RepeatableGroup> repeatables = List.of();
        private InspectorId inspector;
        private Handler handler;
        private RuntimeSemantics semantics;
        private Set<ContractRef<CapabilityId>> requiredCapabilities = Set.of();
        private ContractRef<NodeId> replacementIdentity;
        private PreviewIntent preview = PreviewIntent.none();
        private Map<String, Object> metadata = Map.of();

        private Builder(NodeId id) {
            this.id = Objects.requireNonNull(id, "nodeId");
            displayName = id.value();
            description = null;
        }

        public Builder schemaVersion(int value) { schemaVersion = value; return this; }
        public Builder lifecycle(Lifecycle value) { lifecycle = value; return this; }
        public Builder domain(String value) { domain = value; return this; }
        public Builder family(String value) { family = value; return this; }
        public Builder displayName(String value) { displayName = value; return this; }
        public Builder description(String value) { description = value; return this; }
        public Builder category(ContractRef<CapabilityId> value) { category = value; return this; }
        public Builder pins(List<Pin> value) { pins = value; return this; }
        public Builder modes(List<Mode> value) { modes = value; return this; }
        public Builder branches(List<Branch> value) { branches = value; return this; }
        public Builder repeatables(List<RepeatableGroup> value) { repeatables = value; return this; }
        public Builder inspector(InspectorId value) { inspector = value; return this; }
        public Builder handler(Handler value) { handler = value; return this; }
        public Builder semantics(RuntimeSemantics value) { semantics = value; return this; }
        public Builder requiredCapabilities(Set<ContractRef<CapabilityId>> value) { requiredCapabilities = value; return this; }
        public Builder replacementIdentity(ContractRef<NodeId> value) { replacementIdentity = value; return this; }
        public Builder preview(PreviewIntent value) { preview = value; return this; }
        public Builder metadata(Map<String, Object> value) { metadata = value; return this; }

        public CatalogNodeDescriptor build() {
            return new CatalogNodeDescriptor(id, schemaVersion, lifecycle, domain, family, displayName, description, category, pins, modes, branches, repeatables, inspector, handler, semantics, requiredCapabilities, replacementIdentity, preview, metadata);
        }
    }

    private static Map<String, Object> immutableMetadata(Map<String, Object> value) {
        if (value == null || value.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : value.entrySet()) {
            copy.put(Objects.requireNonNull(entry.getKey(), "metadata key"), freezeMetadata(entry.getValue()));
        }
        return Collections.unmodifiableMap(copy);
    }

    private static Map<String, Object> immutablePresentationMap(Map<String, Object> value, String name) {
        if (value == null || value.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : value.entrySet()) {
            copy.put(Objects.requireNonNull(entry.getKey(), name + " key"), freezePresentation(entry.getValue(), name));
        }
        return Collections.unmodifiableMap(copy);
    }

    private static Object freezePresentation(Object value, String name) {
        if (value == null || value instanceof String || value instanceof Boolean || value instanceof BigDecimal
            || value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            return value;
        }
        if (value instanceof Float floatValue) {
            if (!Float.isFinite(floatValue)) {
                throw new IllegalArgumentException(name + " contains a non-finite number");
            }
            return BigDecimal.valueOf(floatValue.doubleValue());
        }
        if (value instanceof Double doubleValue) {
            if (!Double.isFinite(doubleValue)) {
                throw new IllegalArgumentException(name + " contains a non-finite number");
            }
            return BigDecimal.valueOf(doubleValue);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new IllegalArgumentException(name + " keys must be strings");
                }
                copy.put(key, freezePresentation(entry.getValue(), name + "." + key));
            }
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof Collection<?> collection) {
            return Collections.unmodifiableList(collection.stream()
                .map(entry -> freezePresentation(entry, name + "[]"))
                .toList());
        }
        throw new IllegalArgumentException("Unsupported " + name + " value");
    }

    private static boolean allowsNull(TypeExpr type) {
        return !containsResource(type) || type instanceof TypeExpr.OptionalType;
    }

    private static boolean containsResource(TypeExpr type) {
        return switch (type) {
            case TypeExpr.ResourceType ignored -> true;
            case TypeExpr.Named named -> named.arguments().stream().anyMatch(CatalogNodeDescriptor::containsResource);
            case TypeExpr.OptionalType optional -> containsResource(optional.element());
            case TypeExpr.ListType list -> containsResource(list.element());
            case TypeExpr.MapType map -> containsResource(map.key()) || containsResource(map.value());
            case TypeExpr.TupleType tuple -> tuple.elements().stream().anyMatch(CatalogNodeDescriptor::containsResource);
            case TypeExpr.ResultType result -> containsResource(result.success()) || containsResource(result.failure());
            case TypeExpr.UnionType union -> union.variants().stream().map(TypeExpr.UnionVariant::type).anyMatch(CatalogNodeDescriptor::containsResource);
            case TypeExpr.OpaqueType ignored -> false;
        };
    }

    private static void validateRepeatableStructure(List<Pin> pins, List<RepeatableGroup> groups) {
        Map<PinId, Pin> pinsById = new LinkedHashMap<>();
        for (Pin pin : pins) {
            Pin exactPin = Objects.requireNonNull(pin, "pin");
            if (pinsById.putIfAbsent(exactPin.id(), exactPin) != null) {
                throw new IllegalArgumentException("Duplicate pin ID: " + exactPin.id());
            }
        }
        Map<RepeatableGroupId, RepeatableGroup> groupsById = new LinkedHashMap<>();
        for (RepeatableGroup group : groups) {
            RepeatableGroup exactGroup = Objects.requireNonNull(group, "repeatable group");
            if (groupsById.putIfAbsent(exactGroup.id(), exactGroup) != null) {
                throw new IllegalArgumentException("Duplicate repeatable group ID: " + exactGroup.id());
            }
            for (RepeatableMember member : exactGroup.members()) {
                Pin pin = pinsById.get(member.pinId());
                if (pin == null || pin.direction() != member.direction() || !pin.type().equals(member.type())) {
                    throw new IllegalArgumentException("Repeatable member must exactly match a declared pin: " + member.pinId());
                }
                RepeatableIntent intent = pin.repeatable();
                if (!intent.enabled() || !exactGroup.id().equals(intent.groupId())
                    || intent.minimum() != exactGroup.minimum() || intent.maximum() != exactGroup.maximum()
                    || intent.ordered() != exactGroup.ordered()) {
                    throw new IllegalArgumentException("Repeatable member intent must exactly match its group: " + member.pinId());
                }
            }
        }
        for (Pin pin : pins) {
            RepeatableGroupId groupId = pin.repeatable().groupId();
            if (groupId == null) {
                continue;
            }
            RepeatableGroup group = groupsById.get(groupId);
            if (!pin.repeatable().enabled() || group == null
                || group.members().stream().noneMatch(member -> member.pinId().equals(pin.id()))) {
                throw new IllegalArgumentException("Repeatable pin must belong to its declared group: " + pin.id());
            }
        }
    }

    private static Object freezeMetadata(Object value) {
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean || value instanceof Character) {
            return value;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new IllegalArgumentException("Metadata map keys must be strings");
                }
                copy.put(key, freezeMetadata(entry.getValue()));
            }
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof Collection<?> collection) {
            return Collections.unmodifiableList(collection.stream().map(CatalogNodeDescriptor::freezeMetadata).toList());
        }
        return String.valueOf(value);
    }
}
