package restudio.resync.flow.registry;

import restudio.flow.data.FlowDataType;
import restudio.flow.data.FlowTypeRef;
import restudio.resync.flow.contract.FlowNodeCategoryContract;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.PinId;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class NodeDefinition {
    public enum PinType {
        FLOW,
        DATA,
        EXEC
    }

    public enum PinDirection {
        INPUT,
        OUTPUT
    }

    public static final class NodeCategory {
        private static final Map<String, NodeCategory> REGISTRY = new LinkedHashMap<>();

        public static final NodeCategory EVENT = builtin("event");
        public static final NodeCategory ACTION = builtin("action");
        public static final NodeCategory PLAYER = builtin("player");
        public static final NodeCategory LOGIC = builtin("logic");
        public static final NodeCategory NETWORK = builtin("network");
        public static final NodeCategory CHAT = builtin("chat");
        public static final NodeCategory DATA = builtin("data");
        public static final NodeCategory VARIABLE = builtin("variable");
        public static final NodeCategory FLOW = builtin("flow");
        public static final NodeCategory FUNCTION = builtin("function");
        public static final NodeCategory COMMAND = builtin("command");
        public static final NodeCategory ENTITY = builtin("entity");
        public static final NodeCategory BLOCK = builtin("block");
        public static final NodeCategory WORLD = builtin("world");
        public static final NodeCategory INVENTORY = builtin("inventory");
        public static final NodeCategory ITEM = builtin("item");
        public static final NodeCategory SCOREBOARD = builtin("scoreboard");
        public static final NodeCategory TRADE = builtin("trade");
        public static final NodeCategory NPC = builtin("npc");
        public static final NodeCategory LOOT = builtin("loot");
        public static final NodeCategory MENU = builtin("menu");
        public static final NodeCategory TAB_LIST = builtin("tab_list");
        public static final NodeCategory DIALOG = builtin("dialog");
        public static final NodeCategory CUSTOM_CONTENT = builtin("custom_content");
        public static final NodeCategory RECIPE = builtin("recipe");
        public static final NodeCategory ECONOMY = builtin("economy");
        public static final NodeCategory ADVANCEMENT = builtin("advancement");
        public static final NodeCategory TEXT = builtin("text");
        public static final NodeCategory PERMISSION = builtin("permission");
        public static final NodeCategory ABILITY = builtin("ability");
        public static final NodeCategory VISUAL = builtin("visual");
        public static final NodeCategory DATABASE = builtin("database");
        public static final NodeCategory HTTP = builtin("http");
        public static final NodeCategory DISCORD = builtin("discord");
        public static final NodeCategory UTILITY = builtin("utility");
        public static final NodeCategory WORLD_GEN = builtin("world_gen");

        private final String id;
        private final String displayName;
        private final int color;
        private final int priority;

        private NodeCategory(String id, String displayName, int color, int priority) {
            this.id = id;
            this.displayName = displayName;
            this.color = color;
            this.priority = priority;
            REGISTRY.put(id, this);
        }

        private static NodeCategory builtin(String id) {
            FlowNodeCategoryContract.Category category = FlowNodeCategoryContract.category(id);
            return new NodeCategory(category.id(), category.displayName(), category.color(), category.priority());
        }

        public String getId() {
            return id;
        }

        public String getDisplayName() {
            return displayName;
        }

        public int getColor() {
            return color;
        }

        public int getPriority() {
            return priority;
        }

        public static NodeCategory fromString(String id) {
            if (id == null || id.isBlank()) {
                return UTILITY;
            }
            NodeCategory cat = find(id);
            return cat != null ? cat : UTILITY;
        }

        public static NodeCategory find(String id) {
            if (id == null || id.isBlank()) {
                return null;
            }
            return REGISTRY.get(id.toLowerCase());
        }

        public static List<NodeCategory> values() {
            return List.copyOf(REGISTRY.values());
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof NodeCategory)) return false;
            NodeCategory that = (NodeCategory) o;
            return id.equals(that.id);
        }

        @Override
        public int hashCode() {
            return id.hashCode();
        }

        @Override
        public String toString() {
            return id;
        }
    }

    public enum WidgetType {
        AUTO,
        TEXT,
        TOGGLE,
        DROPDOWN,
        SEARCHABLE_LIST,
        SLIDER,
        NUMBER,
        MULTILINE,
        COLOR;

        public static WidgetType fromSerializedName(String value) {
            String normalized = Objects.requireNonNull(value, "Widget type is required").trim().toUpperCase(Locale.ROOT);
            return "BOOLEAN".equals(normalized) ? TOGGLE : valueOf(normalized);
        }
    }

    public enum NodeKind {
        EVENT,
        ACTION,
        QUERY,
        PURE,
        FAMILY,
        ALIAS
    }

    private final String id;
    private final String displayName;
    private final NodeCategory category;
    private final List<PinDefinition> inputs;
    private final List<PinDefinition> outputs;
    private final int color;
    private final int priority;
    private final boolean hidden;
    private final String hiddenReason;
    private String owner;
    private final String description;
    private final String handler;
    private final Map<String, Object> handlerConfig;
    private final boolean trigger;
    private final String eventType;
    private final List<String> aliases;
    private final List<PinMapping> outputMappings;
    private final int schemaVersion;
    private final NodeKind kind;
    private final Availability availability;
    private final String canonicalId;
    private final List<String> legacyIds;
    private final boolean deprecated;
    private final List<String> tags;
    private final List<String> examples;
    private final String family;
    private final boolean recommended;
    private final String replacementFor;
    private final String authorizationPolicy;
    private final boolean sensitive;
    private final boolean destructive;
    private final String auditPolicy;
    private final String confirmationPolicy;
    private final String clockDomain;
    private final AuthoredNodeMetadata authoredMetadata;
    private final MigrationMapping migrationMapping;

    private NodeDefinition(Builder builder) {
        this.id = builder.id;
        this.displayName = builder.displayName;
        this.category = builder.category;
        this.inputs = builder.inputs;
        this.outputs = builder.outputs;
        this.color = builder.color;
        this.priority = builder.priority;
        this.hidden = builder.hidden;
        this.hiddenReason = builder.hiddenReason;
        this.owner = builder.owner;
        this.description = builder.description;
        this.handler = builder.handler;
        this.handlerConfig = builder.handlerConfig;
        this.trigger = builder.trigger;
        this.eventType = builder.eventType;
        this.aliases = builder.aliases;
        this.outputMappings = builder.outputMappings;
        this.schemaVersion = builder.schemaVersion;
        this.kind = builder.kind;
        this.availability = builder.availability;
        this.canonicalId = builder.canonicalId;
        this.legacyIds = builder.legacyIds;
        this.deprecated = builder.deprecated;
        this.tags = builder.tags;
        this.examples = builder.examples;
        this.family = builder.family;
        this.recommended = builder.recommended;
        this.replacementFor = builder.replacementFor;
        this.authorizationPolicy = builder.authorizationPolicy;
        this.sensitive = builder.sensitive;
        this.destructive = builder.destructive;
        this.auditPolicy = builder.auditPolicy;
        this.confirmationPolicy = builder.confirmationPolicy;
        this.clockDomain = builder.clockDomain;
        this.authoredMetadata = builder.authoredMetadata;
        this.migrationMapping = builder.migrationMapping;
    }

    public String getId() {
        return id;
    }

    public static ContractRef<NodeId> sourceReference(OwnerId owner, String sourceId) {
        Objects.requireNonNull(owner, "Node owner is required");
        String localId = Objects.requireNonNull(sourceId, "Node source identity is required");
        String namespace = owner.value() + ":";
        if (localId.startsWith(namespace)) {
            localId = localId.substring(namespace.length());
        } else if (localId.indexOf(':') >= 0) {
            throw new IllegalArgumentException("Node identity is not owned by its declared owner: " + sourceId);
        }
        return new ContractRef<>(owner, NodeId.of(localId));
    }

    public String getDisplayName() {
        return displayName;
    }

    public NodeCategory getCategory() {
        return category;
    }

    public List<PinDefinition> getInputs() {
        return inputs;
    }

    public List<PinDefinition> getOutputs() {
        return outputs;
    }

    public int getColor() {
        return color;
    }

    public int getPriority() {
        return priority;
    }

    public boolean isHidden() {
        return hidden;
    }

    public String getHiddenReason() {
        return hiddenReason != null ? hiddenReason : "";
    }

    public String getOwner() {
        return owner != null && !owner.isBlank() ? owner : "builtin";
    }

    public void assignOwner(String owner) {
        this.owner = owner != null && !owner.isBlank() ? owner : "builtin";
    }

    public String getDescription() {
        return description;
    }

    public String getHandler() {
        return handler;
    }

    public Map<String, Object> getHandlerConfig() {
        return handlerConfig;
    }

    public boolean isTrigger() {
        return trigger;
    }

    public String getEventType() {
        return eventType;
    }

    public List<String> getAliases() {
        return aliases;
    }

    public List<PinMapping> getOutputMappings() {
        return outputMappings;
    }

    public int getSchemaVersion() {
        return schemaVersion;
    }

    public NodeKind getKind() {
        return kind;
    }

    public Availability getAvailability() {
        return availability;
    }

    public String getCanonicalId() {
        return canonicalId;
    }

    public List<String> getLegacyIds() {
        return legacyIds;
    }

    public boolean isDeprecated() {
        return deprecated;
    }

    public List<String> getTags() {
        return tags;
    }

    public String getAuthorizationPolicy() {
        return authorizationPolicy != null && !authorizationPolicy.isBlank() ? authorizationPolicy : "trusted_server_flow";
    }

    public boolean isSensitive() {
        return sensitive;
    }

    public boolean isDestructive() {
        return destructive;
    }

    public String getAuditPolicy() {
        return auditPolicy != null && !auditPolicy.isBlank() ? auditPolicy : "none";
    }

    public String getConfirmationPolicy() {
        return confirmationPolicy != null && !confirmationPolicy.isBlank() ? confirmationPolicy : "none";
    }

    public String getClockDomain() {
        return clockDomain != null ? clockDomain : "";
    }

    public AuthoredNodeMetadata getAuthoredMetadata() {
        return authoredMetadata;
    }

    public MigrationMapping getMigrationMapping() {
        return migrationMapping;
    }

    public MigrationMapping getAuthoredPinMigration() {
        return migrationMapping;
    }

    public List<PinMigrationMapping> getPinMigrationMappings() {
        return migrationMapping == null ? List.of() : migrationMapping.pins();
    }

    public List<String> getExamples() {
        return examples;
    }

    public String getFamily() {
        return family;
    }

    public NodeDefinition copy() {
        Builder builder = new Builder(this);
        builder.inputs.clear();
        builder.inputs.addAll(inputs.stream().map(NodeDefinition::copyPin).toList());
        builder.outputs.clear();
        builder.outputs.addAll(outputs.stream().map(NodeDefinition::copyPin).toList());
        builder.handlerConfig = copyMap(handlerConfig);
        builder.aliases = List.copyOf(aliases);
        builder.outputMappings = List.copyOf(outputMappings);
        builder.legacyIds = List.copyOf(legacyIds);
        builder.tags = List.copyOf(tags);
        builder.examples = List.copyOf(examples);
        builder.availability = availability == null ? null : new Availability(availability.plugin, availability.platform, availability.minVersion);
        return builder.build();
    }

    public boolean isRecommended() {
        return recommended;
    }

    public String getReplacementFor() {
        return replacementFor;
    }

    public NodeDefinition withPins(List<PinDefinition> inputs, List<PinDefinition> outputs) {
        Builder builder = new Builder(this);
        builder.inputs.clear();
        builder.outputs.clear();
        if (inputs != null) {
            builder.inputs.addAll(inputs);
        }
        if (outputs != null) {
            builder.outputs.addAll(outputs);
        }
        return builder.build();
    }

    private static PinDefinition copyPin(PinDefinition pin) {
        RepeatablePin repeatable = pin.repeatable == null ? null
            : new RepeatablePin(pin.repeatable.groupId, pin.repeatable.minItems, pin.repeatable.maxItems, pin.repeatable.itemLabel);
        PinConstraints constraints = pin.constraints == null ? null
            : new PinConstraints(pin.constraints.min, pin.constraints.max, pin.constraints.step);
        return new PinDefinition(pin.id, pin.displayName, pin.type, pin.direction, pin.dataType, pin.widgetType,
            List.copyOf(pin.options), pin.optionsSource, pin.defaultValue, constraints, Map.copyOf(pin.visibleWhen),
            pin.description, pin.optional, pin.typeRef, repeatable, pin.runtimeName);
    }

    private static Map<String, Object> copyMap(Map<String, Object> source) {
        if (source == null) {
            return null;
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(key, copyValue(value)));
        return copy;
    }

    private static Object copyValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> copy = new LinkedHashMap<>();
            map.forEach((key, nested) -> copy.put(key, copyValue(nested)));
            return copy;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(NodeDefinition::copyValue).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        }
        if (value instanceof java.util.Set<?> set) {
            return set.stream().map(NodeDefinition::copyValue).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        }
        if (value != null && value.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(value);
            Object copy = java.lang.reflect.Array.newInstance(value.getClass().getComponentType(), length);
            for (int index = 0; index < length; index++) {
                java.lang.reflect.Array.set(copy, index, copyValue(java.lang.reflect.Array.get(value, index)));
            }
            return copy;
        }
        return value;
    }

    public NodeDefinition withAuthoredMetadata(AuthoredNodeMetadata authoredMetadata) {
        return new Builder(this).authoredMetadata(authoredMetadata).build();
    }

    public NodeDefinition withMigrationMapping(MigrationMapping migrationMapping) {
        return new Builder(this).migrationMapping(migrationMapping).build();
    }

    public NodeDefinition withCanonicalId(String canonicalId) {
        return new Builder(this).canonicalId(canonicalId).build();
    }

    public NodeDefinition withLegacyIds(List<String> legacyIds) {
        return new Builder(this).legacyIds(legacyIds).build();
    }

    public NodeDefinition withId(String id) {
        return new Builder(this).id(id).build();
    }

    public record PinMapping(String source, String target) {
    }

    public record LegacyPinId(String value) {
        public LegacyPinId {
            Objects.requireNonNull(value, "Legacy pin ID is required");
            if (value.isBlank() || value.length() > 128 || ".".equals(value) || "..".equals(value)) {
                throw new IllegalArgumentException("Legacy pin ID must be a nonblank safe token");
            }
            for (int index = 0; index < value.length(); index++) {
                char character = value.charAt(index);
                boolean letter = character >= 'A' && character <= 'Z' || character >= 'a' && character <= 'z';
                boolean digit = character >= '0' && character <= '9';
                if (index == 0 && !letter || index > 0 && !letter && !digit && character != '_' && character != '-' && character != '.') {
                    throw new IllegalArgumentException("Legacy pin ID must be a nonblank safe token");
                }
                if (Character.isWhitespace(character) || Character.isISOControl(character)) {
                    throw new IllegalArgumentException("Legacy pin ID must not contain whitespace or control characters");
                }
            }
        }

        public static LegacyPinId of(String value) {
            return new LegacyPinId(value);
        }
    }

    public record PinMigrationMapping(LegacyPinId sourcePinId, PinId targetPinId, PinDirection direction,
                                      int sourceSchemaVersion, int targetSchemaVersion) {
        public PinMigrationMapping {
            sourcePinId = Objects.requireNonNull(sourcePinId, "Source pin ID is required");
            targetPinId = Objects.requireNonNull(targetPinId, "Target pin ID is required");
            direction = Objects.requireNonNull(direction, "Pin direction is required");
            if (sourceSchemaVersion < 1 || targetSchemaVersion < 1) {
                throw new IllegalArgumentException("Pin migration schema versions must be positive");
            }
            if (targetSchemaVersion <= sourceSchemaVersion) {
                throw new IllegalArgumentException("Pin migration target schema version must advance the source version");
            }
        }

        public PinMigrationMapping(PinId sourcePinId, PinId targetPinId, PinDirection direction,
                                   int sourceSchemaVersion, int targetSchemaVersion) {
            this(LegacyPinId.of(sourcePinId.value()), targetPinId, direction, sourceSchemaVersion, targetSchemaVersion);
        }

        public PinMigrationMapping(String sourcePinId, PinId targetPinId, PinDirection direction,
                                   int sourceSchemaVersion, int targetSchemaVersion) {
            this(LegacyPinId.of(sourcePinId), targetPinId, direction, sourceSchemaVersion, targetSchemaVersion);
        }

        public LegacyPinId source() {
            return sourcePinId;
        }

        public PinId target() {
            return targetPinId;
        }

        public int fromVersion() {
            return sourceSchemaVersion;
        }

        public int toVersion() {
            return targetSchemaVersion;
        }
    }

    public record MigrationMapping(int sourceSchemaVersion, int targetSchemaVersion, boolean complete,
                                   List<PinMigrationMapping> pins) {
        public MigrationMapping {
            if (sourceSchemaVersion < 1 || targetSchemaVersion < 1) {
                throw new IllegalArgumentException("Migration schema versions must be positive");
            }
            if (targetSchemaVersion <= sourceSchemaVersion) {
                throw new IllegalArgumentException("Migration target schema version must advance the source version");
            }
            pins = pins == null ? List.of() : List.copyOf(pins);
            Set<String> sources = new LinkedHashSet<>();
            Set<String> targets = new LinkedHashSet<>();
            for (PinMigrationMapping pin : pins) {
                Objects.requireNonNull(pin, "Pin migration mapping is required");
                if (pin.sourceSchemaVersion() != sourceSchemaVersion || pin.targetSchemaVersion() != targetSchemaVersion) {
                    throw new IllegalArgumentException("Pin migration mapping versions must match the migration versions");
                }
                String sourceKey = pin.direction().name() + ":" + pin.sourcePinId().value();
                String targetKey = pin.direction().name() + ":" + pin.targetPinId().value();
                if (!sources.add(sourceKey)) {
                    throw new IllegalArgumentException("Duplicate pin migration source: " + sourceKey);
                }
                if (!targets.add(targetKey)) {
                    throw new IllegalArgumentException("Duplicate pin migration target: " + targetKey);
                }
            }
        }

        public MigrationMapping(boolean complete, int sourceSchemaVersion, int targetSchemaVersion,
                                List<PinMigrationMapping> pins) {
            this(sourceSchemaVersion, targetSchemaVersion, complete, pins);
        }

        public List<PinMigrationMapping> mappings() {
            return pins;
        }

        public int fromVersion() {
            return sourceSchemaVersion;
        }

        public int toVersion() {
            return targetSchemaVersion;
        }
    }

    public static class Availability {
        private final String plugin;
        private final String platform;
        private final String minVersion;

        public Availability(String plugin, String platform, String minVersion) {
            this.plugin = plugin;
            this.platform = platform;
            this.minVersion = minVersion;
        }

        public String getPlugin() {
            return plugin;
        }

        public String getPlatform() {
            return platform;
        }

        public String getMinVersion() {
            return minVersion;
        }
    }

    public static class PinConstraints {
        private final Double min;
        private final Double max;
        private final Double step;

        public PinConstraints(Double min, Double max, Double step) {
            this.min = min;
            this.max = max;
            this.step = step;
        }

        public Double getMin() {
            return min;
        }

        public Double getMax() {
            return max;
        }

        public Double getStep() {
            return step;
        }
    }

    public static class RepeatablePin {
        private final String groupId;
        private final int minItems;
        private final int maxItems;
        private final String itemLabel;

        public RepeatablePin(String groupId, int minItems, int maxItems, String itemLabel) {
            this.groupId = groupId;
            this.minItems = Math.max(0, minItems);
            this.maxItems = Math.max(this.minItems, maxItems);
            this.itemLabel = itemLabel;
        }

        public String getGroupId() {
            return groupId;
        }

        public int getMinItems() {
            return minItems;
        }

        public int getMaxItems() {
            return maxItems;
        }

        public String getItemLabel() {
            return itemLabel;
        }
    }

    public static class PinDefinition {
        private final PinId id;
        private final String displayName;
        private final String runtimeName;
        private final PinType type;
        private final PinDirection direction;
        private final FlowDataType dataType;
        private final FlowTypeRef typeRef;
        private final RepeatablePin repeatable;
        private final WidgetType widgetType;
        private final List<String> options;
        private final String optionsSource;
        private final String defaultValue;
        private final PinConstraints constraints;
        private final Map<String, String> visibleWhen;
        private final String description;
        private final boolean optional;

        public PinDefinition(String name, PinType type, PinDirection direction, FlowDataType dataType) {
            this(PinId.of(name), name, type, direction, dataType, null, null, null, null, null, null, null, false);
        }

        public PinDefinition(String name, PinType type, PinDirection direction, FlowDataType dataType, boolean optional) {
            this(PinId.of(name), name, type, direction, dataType, null, null, null, null, null, null, null, optional);
        }

        public PinDefinition(String name, PinType type, PinDirection direction, FlowDataType dataType, FlowTypeRef typeRef) {
            this(PinId.of(name), name, type, direction, dataType, null, null, null, null, null, null, null, false, typeRef);
        }

        public PinDefinition(String name, PinType type, PinDirection direction, FlowDataType dataType,
                             WidgetType widgetType, List<String> options, String optionsSource, String defaultValue,
                             PinConstraints constraints, Map<String, String> visibleWhen, String description) {
            this(PinId.of(name), name, type, direction, dataType, widgetType, options, optionsSource, defaultValue, constraints, visibleWhen, description, false);
        }

        public PinDefinition(String name, PinType type, PinDirection direction, FlowDataType dataType,
                             WidgetType widgetType, List<String> options, String optionsSource, String defaultValue,
                             PinConstraints constraints, Map<String, String> visibleWhen, String description, boolean optional) {
            this(PinId.of(name), name, type, direction, dataType, widgetType, options, optionsSource, defaultValue, constraints, visibleWhen,
                description, optional, dataType != null ? FlowTypeRef.simple(dataType.getId()) : FlowTypeRef.simple("any"));
        }

        public PinDefinition(String name, PinType type, PinDirection direction, FlowDataType dataType,
                             WidgetType widgetType, List<String> options, String optionsSource, String defaultValue,
                             PinConstraints constraints, Map<String, String> visibleWhen, String description, boolean optional,
                             FlowTypeRef typeRef) {
            this(PinId.of(name), name, type, direction, dataType, widgetType, options, optionsSource, defaultValue, constraints, visibleWhen,
                description, optional, typeRef, null);
        }

        public PinDefinition(PinId id, String displayName, PinType type, PinDirection direction, FlowDataType dataType) {
            this(id, displayName, type, direction, dataType, null, null, null, null, null, null, null, false);
        }

        public PinDefinition(PinId id, String displayName, PinType type, PinDirection direction, FlowDataType dataType,
                             boolean optional) {
            this(id, displayName, type, direction, dataType, null, null, null, null, null, null, null, optional);
        }

        public PinDefinition(PinId id, String displayName, PinType type, PinDirection direction, FlowDataType dataType,
                             FlowTypeRef typeRef) {
            this(id, displayName, type, direction, dataType, null, null, null, null, null, null, null, false, typeRef);
        }

        public PinDefinition(PinId id, String displayName, PinType type, PinDirection direction, FlowDataType dataType,
                             WidgetType widgetType, List<String> options, String optionsSource, String defaultValue,
                             PinConstraints constraints, Map<String, String> visibleWhen, String description) {
            this(id, displayName, type, direction, dataType, widgetType, options, optionsSource, defaultValue, constraints,
                visibleWhen, description, false);
        }

        public PinDefinition(PinId id, String displayName, PinType type, PinDirection direction, FlowDataType dataType,
                             WidgetType widgetType, List<String> options, String optionsSource, String defaultValue,
                             PinConstraints constraints, Map<String, String> visibleWhen, String description, boolean optional) {
            this(id, displayName, type, direction, dataType, widgetType, options, optionsSource, defaultValue, constraints,
                visibleWhen, description, optional, dataType != null ? FlowTypeRef.simple(dataType.getId()) : FlowTypeRef.simple("any"));
        }

        public PinDefinition(PinId id, String displayName, PinType type, PinDirection direction, FlowDataType dataType,
                             WidgetType widgetType, List<String> options, String optionsSource, String defaultValue,
                             PinConstraints constraints, Map<String, String> visibleWhen, String description, boolean optional,
                             FlowTypeRef typeRef) {
            this(id, displayName, type, direction, dataType, widgetType, options, optionsSource, defaultValue, constraints,
                visibleWhen, description, optional, typeRef, null);
        }

        public PinDefinition(String name, PinType type, PinDirection direction, FlowDataType dataType,
                             WidgetType widgetType, List<String> options, String optionsSource, String defaultValue,
                             PinConstraints constraints, Map<String, String> visibleWhen, String description, boolean optional,
                             FlowTypeRef typeRef, RepeatablePin repeatable) {
            this(PinId.of(name), name, type, direction, dataType, widgetType, options, optionsSource, defaultValue, constraints,
                visibleWhen, description, optional, typeRef, repeatable);
        }

        public PinDefinition(PinId id, String displayName, PinType type, PinDirection direction, FlowDataType dataType,
                             WidgetType widgetType, List<String> options, String optionsSource, String defaultValue,
                             PinConstraints constraints, Map<String, String> visibleWhen, String description, boolean optional,
                             FlowTypeRef typeRef, RepeatablePin repeatable) {
            this(id, displayName, type, direction, dataType, widgetType, options, optionsSource, defaultValue, constraints,
                visibleWhen, description, optional, typeRef, repeatable, null);
        }

        public PinDefinition(PinId id, String displayName, String runtimeName, PinType type, PinDirection direction,
                             FlowDataType dataType, WidgetType widgetType, List<String> options, String optionsSource,
                             String defaultValue, PinConstraints constraints, Map<String, String> visibleWhen,
                             String description, boolean optional, FlowTypeRef typeRef, RepeatablePin repeatable) {
            this(id, displayName, type, direction, dataType, widgetType, options, optionsSource, defaultValue, constraints,
                visibleWhen, description, optional, typeRef, repeatable, runtimeName);
        }

        public PinDefinition(PinId id, String displayName, PinType type, PinDirection direction, FlowDataType dataType,
                             WidgetType widgetType, List<String> options, String optionsSource, String defaultValue,
                             PinConstraints constraints, Map<String, String> visibleWhen, String description, boolean optional,
                             FlowTypeRef typeRef, RepeatablePin repeatable, String runtimeName) {
            this.id = Objects.requireNonNull(id, "Pin ID is required");
            this.displayName = displayName != null && !displayName.isBlank() ? displayName : id.value();
            this.runtimeName = requireRuntimeName(id, runtimeName);
            this.type = type;
            this.direction = direction;
            this.dataType = dataType;
            this.typeRef = typeRef;
            this.repeatable = repeatable;
            this.widgetType = widgetType;
            this.options = options != null ? options : Collections.emptyList();
            this.optionsSource = optionsSource;
            this.defaultValue = defaultValue;
            this.constraints = constraints;
            this.visibleWhen = visibleWhen != null ? visibleWhen : Collections.emptyMap();
            this.description = description;
            this.optional = optional;
        }

        public String getName() {
            return id.value();
        }

        public String getRuntimeName() {
            return runtimeName;
        }

        public PinId getId() {
            return id;
        }

        public String getDisplayName() {
            return displayName;
        }

        public PinType getType() {
            return type;
        }

        public PinDirection getDirection() {
            return direction;
        }

        public FlowDataType getDataType() {
            return dataType;
        }

        public FlowTypeRef getTypeRef() {
            return typeRef != null ? typeRef : FlowTypeRef.simple(dataType != null ? dataType.getId() : "any");
        }

        public RepeatablePin getRepeatable() {
            return repeatable;
        }

        public WidgetType getWidgetType() {
            return widgetType;
        }

        public List<String> getOptions() {
            return options;
        }

        public String getOptionsSource() {
            return optionsSource;
        }

        public String getDefaultValue() {
            return defaultValue;
        }

        public PinConstraints getConstraints() {
            return constraints;
        }

        public Map<String, String> getVisibleWhen() {
            return visibleWhen;
        }

        public String getDescription() {
            return description;
        }

        public boolean isOptional() {
            return optional;
        }

        private static String requireRuntimeName(PinId id, String runtimeName) {
            String value = runtimeName == null ? id.value() : runtimeName;
            try {
                LegacyPinId.of(value);
            } catch (RuntimeException exception) {
                throw new IllegalArgumentException("Pin runtime name must be a safe local routing token: " + value, exception);
            }
            return value;
        }
    }

    public static class Builder {
        private String id;
        private String displayName;
        private NodeCategory category;
        private final List<PinDefinition> inputs = new ArrayList<>();
        private final List<PinDefinition> outputs = new ArrayList<>();
        private int color = 0xFFAAAAAA;
        private int priority = 0;
        private boolean hidden = false;
        private String hiddenReason = "";
        private String owner = "builtin";
        private String description;
        private String handler;
        private Map<String, Object> handlerConfig;
        private boolean trigger = false;
        private String eventType;
        private List<String> aliases = Collections.emptyList();
        private List<PinMapping> outputMappings = Collections.emptyList();
        private int schemaVersion = 1;
        private NodeKind kind;
        private Availability availability;
        private String canonicalId;
        private List<String> legacyIds = Collections.emptyList();
        private boolean deprecated;
        private List<String> tags = Collections.emptyList();
        private List<String> examples = Collections.emptyList();
        private String family;
        private boolean recommended;
        private String replacementFor;
        private String authorizationPolicy = "trusted_server_flow";
        private boolean sensitive;
        private boolean destructive;
        private String auditPolicy = "none";
        private String confirmationPolicy = "none";
        private String clockDomain = "";
        private AuthoredNodeMetadata authoredMetadata;
        private MigrationMapping migrationMapping;

        public Builder(String id, String displayName, NodeCategory category) {
            this.id = id;
            this.displayName = displayName;
            this.category = category;
        }

        private Builder(NodeDefinition definition) {
            this.id = definition.id;
            this.displayName = definition.displayName;
            this.category = definition.category;
            this.inputs.addAll(definition.inputs);
            this.outputs.addAll(definition.outputs);
            this.color = definition.color;
            this.priority = definition.priority;
            this.hidden = definition.hidden;
            this.hiddenReason = definition.hiddenReason;
            this.owner = definition.owner;
            this.description = definition.description;
            this.handler = definition.handler;
            this.handlerConfig = definition.handlerConfig;
            this.trigger = definition.trigger;
            this.eventType = definition.eventType;
            this.aliases = definition.aliases;
            this.outputMappings = definition.outputMappings;
            this.schemaVersion = definition.schemaVersion;
            this.kind = definition.kind;
            this.availability = definition.availability;
            this.canonicalId = definition.canonicalId;
            this.legacyIds = definition.legacyIds;
            this.deprecated = definition.deprecated;
            this.tags = definition.tags;
            this.examples = definition.examples;
            this.family = definition.family;
            this.recommended = definition.recommended;
            this.replacementFor = definition.replacementFor;
            this.authorizationPolicy = definition.authorizationPolicy;
            this.sensitive = definition.sensitive;
            this.destructive = definition.destructive;
            this.auditPolicy = definition.auditPolicy;
            this.confirmationPolicy = definition.confirmationPolicy;
            this.clockDomain = definition.clockDomain;
            this.authoredMetadata = definition.authoredMetadata;
            this.migrationMapping = definition.migrationMapping;
        }

        public Builder input(String name, PinType type, FlowDataType dataType) {
            inputs.add(new PinDefinition(name, type, PinDirection.INPUT, dataType));
            return this;
        }

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder input(PinDefinition pin) {
            inputs.add(pin);
            return this;
        }

        public Builder output(String name, PinType type, FlowDataType dataType) {
            outputs.add(new PinDefinition(name, type, PinDirection.OUTPUT, dataType));
            return this;
        }

        public Builder output(PinDefinition pin) {
            outputs.add(pin);
            return this;
        }

        public Builder color(int color) {
            this.color = color;
            return this;
        }

        public Builder color(NodeCategory category) {
            if (category != null) {
                this.color = category.getColor();
            }
            return this;
        }

        public Builder priority(int priority) {
            this.priority = priority;
            return this;
        }

        public Builder hidden(boolean hidden) {
            this.hidden = hidden;
            return this;
        }

        public Builder hiddenReason(String hiddenReason) {
            this.hiddenReason = hiddenReason != null ? hiddenReason : "";
            return this;
        }

        public Builder owner(String owner) {
            this.owner = owner != null && !owner.isBlank() ? owner : "builtin";
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder handler(String handler) {
            this.handler = handler;
            return this;
        }

        public Builder handlerConfig(Map<String, Object> handlerConfig) {
            this.handlerConfig = handlerConfig;
            return this;
        }

        public Builder trigger(boolean trigger) {
            this.trigger = trigger;
            return this;
        }

        public Builder eventType(String eventType) {
            this.eventType = eventType;
            return this;
        }

        public Builder aliases(List<String> aliases) {
            this.aliases = aliases != null ? aliases : Collections.emptyList();
            return this;
        }

        public Builder outputMappings(List<PinMapping> outputMappings) {
            this.outputMappings = outputMappings != null ? outputMappings : Collections.emptyList();
            return this;
        }

        public Builder schemaVersion(int schemaVersion) {
            this.schemaVersion = schemaVersion;
            return this;
        }

        public Builder kind(NodeKind kind) {
            this.kind = kind;
            return this;
        }

        public Builder availability(Availability availability) {
            this.availability = availability;
            return this;
        }

        public Builder canonicalId(String canonicalId) {
            this.canonicalId = canonicalId;
            return this;
        }

        public Builder legacyIds(List<String> legacyIds) {
            this.legacyIds = legacyIds != null ? legacyIds : Collections.emptyList();
            return this;
        }

        public Builder deprecated(boolean deprecated) {
            this.deprecated = deprecated;
            return this;
        }

        public Builder tags(List<String> tags) {
            this.tags = tags != null ? tags : Collections.emptyList();
            return this;
        }

        public Builder examples(List<String> examples) {
            this.examples = examples != null ? examples : Collections.emptyList();
            return this;
        }

        public Builder family(String family) {
            this.family = family;
            return this;
        }

        public Builder recommended(boolean recommended) {
            this.recommended = recommended;
            return this;
        }

        public Builder replacementFor(String replacementFor) {
            this.replacementFor = replacementFor;
            return this;
        }

        public Builder authorizationPolicy(String authorizationPolicy) {
            this.authorizationPolicy = authorizationPolicy != null && !authorizationPolicy.isBlank() ? authorizationPolicy : "trusted_server_flow";
            return this;
        }

        public Builder sensitive(boolean sensitive) {
            this.sensitive = sensitive;
            return this;
        }

        public Builder destructive(boolean destructive) {
            this.destructive = destructive;
            return this;
        }

        public Builder auditPolicy(String auditPolicy) {
            this.auditPolicy = auditPolicy != null && !auditPolicy.isBlank() ? auditPolicy : "none";
            return this;
        }

        public Builder confirmationPolicy(String confirmationPolicy) {
            this.confirmationPolicy = confirmationPolicy != null && !confirmationPolicy.isBlank() ? confirmationPolicy : "none";
            return this;
        }

        public Builder clockDomain(String clockDomain) {
            this.clockDomain = clockDomain != null ? clockDomain : "";
            return this;
        }

        public Builder authoredMetadata(AuthoredNodeMetadata authoredMetadata) {
            this.authoredMetadata = authoredMetadata;
            return this;
        }

        public Builder migrationMapping(MigrationMapping migrationMapping) {
            this.migrationMapping = migrationMapping;
            return this;
        }

        public Builder hidden() {
            return hidden(true);
        }

        public NodeDefinition build() {
            if (authoredMetadata != null && !authoredIdentityMatches(id, owner, authoredMetadata.id())) {
                throw new IllegalArgumentException("Authored node identity does not match the node definition identity");
            }
            if (color == 0xFFAAAAAA && category != null) {
                color(category);
            }
            if (kind == null) {
                kind = inferKind();
            }
            if (description == null || description.isBlank()) {
                description = displayName + " Flow capability.";
            }
            if (tags == null || tags.isEmpty()) {
                LinkedHashSet<String> resolvedTags = new LinkedHashSet<>();
                if (id != null) {
                    for (String token : id.toLowerCase(Locale.ROOT).split("[.:_\\-]+")) {
                        if (!token.isBlank()) {
                            resolvedTags.add(token);
                        }
                    }
                }
                if (category != null) {
                    resolvedTags.add(category.getId().toLowerCase(Locale.ROOT));
                }
                resolvedTags.add(kind.name().toLowerCase(Locale.ROOT));
                tags = List.copyOf(resolvedTags);
            }
            if (examples == null || examples.isEmpty()) {
                examples = List.of(defaultUsageHint());
            }
            if (destructive && "none".equals(confirmationPolicy)) {
                confirmationPolicy = "explicit_flow_intent";
            }
            return new NodeDefinition(this);
        }

        private static boolean authoredIdentityMatches(String id, String owner, String authoredId) {
            if (Objects.equals(id, authoredId)) {
                return true;
            }
            return owner != null && !owner.isBlank() && Objects.equals(id, owner + ":" + authoredId);
        }

        private String defaultUsageHint() {
            return switch (kind) {
                case EVENT -> "Connect the event Flow output to the actions that should run.";
                case ACTION -> "Connect the required inputs, then continue from the Flow output.";
                case QUERY, PURE -> "Connect the inputs and use the typed outputs in another node.";
                case FAMILY -> "Choose the operation and connect the inputs required by that operation.";
                case ALIAS -> "Replace this node with its canonical equivalent.";
            };
        }

        private NodeKind inferKind() {
            if (trigger) {
                return NodeKind.EVENT;
            }
            if (canonicalId != null && !canonicalId.isBlank() && hidden) {
                return NodeKind.ALIAS;
            }
            boolean hasFlowInput = inputs.stream().anyMatch(pin -> pin.getType() == PinType.FLOW && pin.getDirection() == PinDirection.INPUT);
            boolean hasFlowOutput = outputs.stream().anyMatch(pin -> pin.getType() == PinType.FLOW && pin.getDirection() == PinDirection.OUTPUT);
            if (handler != null && List.of("player", "entity", "world", "block", "inventory", "itemstack").contains(handler)) {
                return hasFlowInput ? NodeKind.FAMILY : NodeKind.QUERY;
            }
            if (hasFlowInput || hasFlowOutput) {
                return NodeKind.ACTION;
            }
            return NodeKind.QUERY;
        }
    }

    public static class PinBuilder {
        private PinId id;
        private String displayName;
        private PinType type;
        private PinDirection direction;
        private FlowDataType dataType;
        private FlowTypeRef typeRef;
        private RepeatablePin repeatable;
        private String runtimeName;
        private WidgetType widgetType;
        private List<String> options;
        private String optionsSource;
        private String defaultValue;
        private PinConstraints constraints;
        private Map<String, String> visibleWhen;
        private String description;
        private boolean optional;

        public PinBuilder(String name, PinType type, PinDirection direction, FlowDataType dataType) {
            this.id = PinId.of(name);
            this.displayName = name;
            this.type = type;
            this.direction = direction;
            this.dataType = dataType;
            this.typeRef = dataType != null ? FlowTypeRef.simple(dataType.getId()) : FlowTypeRef.simple("any");
        }

        public PinBuilder(PinId id, String displayName, PinType type, PinDirection direction, FlowDataType dataType) {
            this.id = Objects.requireNonNull(id, "Pin ID is required");
            this.displayName = displayName != null && !displayName.isBlank() ? displayName : id.value();
            this.type = type;
            this.direction = direction;
            this.dataType = dataType;
            this.typeRef = dataType != null ? FlowTypeRef.simple(dataType.getId()) : FlowTypeRef.simple("any");
        }

        public PinBuilder id(PinId id) {
            this.id = Objects.requireNonNull(id, "Pin ID is required");
            return this;
        }

        public PinBuilder displayName(String displayName) {
            this.displayName = displayName;
            return this;
        }

        public PinBuilder runtimeName(String runtimeName) {
            this.runtimeName = runtimeName;
            return this;
        }

        public PinBuilder typeRef(FlowTypeRef typeRef) {
            this.typeRef = typeRef;
            return this;
        }

        public PinBuilder repeatable(String groupId, int minItems, int maxItems, String itemLabel) {
            this.repeatable = new RepeatablePin(groupId, minItems, maxItems, itemLabel);
            return this;
        }

        public PinBuilder widget(WidgetType widgetType) {
            this.widgetType = widgetType;
            return this;
        }

        public PinBuilder options(List<String> options) {
            this.options = options;
            return this;
        }

        public PinBuilder optionsSource(String optionsSource) {
            this.optionsSource = optionsSource;
            return this;
        }

        public PinBuilder defaultValue(String defaultValue) {
            this.defaultValue = defaultValue;
            return this;
        }

        public PinBuilder constraints(Double min, Double max, Double step) {
            this.constraints = new PinConstraints(min, max, step);
            return this;
        }

        public PinBuilder visibleWhen(String pinName, String expectedValue) {
            if (this.visibleWhen == null) {
                this.visibleWhen = new HashMap<>();
            }
            this.visibleWhen.put(pinName, expectedValue);
            return this;
        }

        public PinBuilder visibleWhen(Map<String, String> conditions) {
            if (this.visibleWhen == null) {
                this.visibleWhen = new HashMap<>();
            }
            this.visibleWhen.putAll(conditions);
            return this;
        }

        public PinBuilder description(String description) {
            this.description = description;
            return this;
        }

        public PinBuilder optional(boolean optional) {
            this.optional = optional;
            return this;
        }

        public PinDefinition build() {
            return new PinDefinition(id, displayName, type, direction, dataType, widgetType, options, optionsSource, defaultValue, constraints,
                visibleWhen, description, optional, typeRef, repeatable, runtimeName);
        }
    }
}
