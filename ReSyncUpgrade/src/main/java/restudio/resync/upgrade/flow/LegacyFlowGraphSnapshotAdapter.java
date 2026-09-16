package restudio.resync.upgrade.flow;

import restudio.resync.contract.canonical.CanonicalCodec;
import restudio.resync.contract.canonical.JsonValue;
import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.migration.FlowGraphMigrationSchemaCatalog;
import restudio.resync.flow.migration.FlowNodeMigrationMap;
import restudio.resync.migration.MigrationPaths;
import restudio.resync.migration.ProductionPersistenceOwners;
import restudio.resync.migration.ProductionSnapshotMetadataManifest;
import restudio.resync.migration.QuarantineRecord;
import restudio.resync.migration.SnapshotMetadata;
import restudio.resync.upgrade.ImmutableSnapshotAdapter;
import restudio.resync.upgrade.LegacySnapshotWindow;
import restudio.resync.upgrade.UpgradeSourceWindow;
import restudio.resync.upgrade.UpgradeVersion;
import restudio.resync.upgrade.adapter.OfflineUpgradeAdapter;
import restudio.resync.upgrade.adapter.OfflineUpgradeSnapshotAdapter;
import restudio.resync.upgrade.adapter.OfflineUpgradeSnapshotInput;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.regex.Pattern;

public final class LegacyFlowGraphSnapshotAdapter implements OfflineUpgradeSnapshotAdapter {
    public static final String ADAPTER_ID = "resync.flow-graph";
    public static final int ADAPTER_VERSION = 1;
    public static final int SOURCE_FORMAT_VERSION = LegacySnapshotWindow.SOURCE_FORMAT_VERSION;
    public static final int TARGET_FORMAT_VERSION = LegacySnapshotWindow.TARGET_FORMAT_VERSION;
    public static final String SOURCE_BUILD = LegacySnapshotWindow.SOURCE_BUILD;
    public static final String GRAPH_OWNER = ProductionPersistenceOwners.FLOW_ASSETS;
    public static final String FLOW_PREFIX = "assets/Blueprints/Flows";
    public static final String FUNCTION_PREFIX = "assets/Blueprints/Functions";
    public static final String COMMAND_PREFIX = "assets/Blueprints/Commands";
    private static final String SCHEMA_VERSION_FIELD = "version";
    private static final String SOURCE_SCHEMA_MODE = "compatibility";
    private static final String SOURCE_SCHEMA_ROOT = "ReSyncUpgrade/src/main/resources/nodes/migrated";
    private static final Set<String> GRAPH_TYPES = Set.of("flow", "function", "command");
    private static final String TYPED_AUTOMATION_BACKUP_ROOT = "assets/migration-backups/";
    private static final String TYPED_AUTOMATION_BACKUP_PREFIX = "typed-automation-";
    private static final String PASSTHROUGH_OUTPUT_PREFIX = "__passthrough:";
    private static final Pattern PIN_ID = Pattern.compile("[A-Za-z][A-Za-z0-9]{0,31}(?:[._-][A-Za-z0-9][A-Za-z0-9]{0,31})*");
    private static final Set<String> LEGACY_EVENT_FLOW_PINS = Set.of("next", "left", "right", "middle", "shift_left", "shift_right");
    private static final Map<Character, String> LEGACY_NAMED_COLORS = Map.ofEntries(
        Map.entry('0', "black"), Map.entry('1', "dark_blue"), Map.entry('2', "dark_green"), Map.entry('3', "dark_aqua"),
        Map.entry('4', "dark_red"), Map.entry('5', "dark_purple"), Map.entry('6', "gold"), Map.entry('7', "gray"),
        Map.entry('8', "dark_gray"), Map.entry('9', "blue"), Map.entry('a', "green"), Map.entry('b', "aqua"),
        Map.entry('c', "red"), Map.entry('d', "light_purple"), Map.entry('e', "yellow"), Map.entry('f', "white")
    );
    private static final Map<String, String> NAMED_COLOR_RGB = Map.ofEntries(
        Map.entry("black", "#000000"), Map.entry("dark_blue", "#0000AA"), Map.entry("dark_green", "#00AA00"), Map.entry("dark_aqua", "#00AAAA"),
        Map.entry("dark_red", "#AA0000"), Map.entry("dark_purple", "#AA00AA"), Map.entry("gold", "#FFAA00"), Map.entry("gray", "#AAAAAA"),
        Map.entry("dark_gray", "#555555"), Map.entry("blue", "#5555FF"), Map.entry("green", "#55FF55"), Map.entry("aqua", "#55FFFF"),
        Map.entry("red", "#FF5555"), Map.entry("light_purple", "#FF55FF"), Map.entry("yellow", "#FFFF55"), Map.entry("white", "#FFFFFF")
    );
    private static final Map<String, String> LEGACY_PARTICLE_MODES = Map.ofEntries(
        Map.entry("particle_spawn", "point"), Map.entry("particle.spawn", "point"),
        Map.entry("particle_area", "area"), Map.entry("particle.area", "area"),
        Map.entry("particle_player_spawn", "player"), Map.entry("particle.player.spawn", "player"),
        Map.entry("particle_line", "line"), Map.entry("particle.line", "line"),
        Map.entry("particle_circle", "circle"), Map.entry("particle.circle", "circle"),
        Map.entry("particle_sphere", "sphere"), Map.entry("particle.sphere", "sphere"),
        Map.entry("particle_ellipse", "ellipse"), Map.entry("particle.ellipse", "ellipse"),
        Map.entry("particle_spiral", "spiral"), Map.entry("particle.spiral", "spiral"),
        Map.entry("particle_cone", "cone"), Map.entry("particle.cone", "cone"),
        Map.entry("particle_ring", "ring"), Map.entry("particle.ring", "ring"),
        Map.entry("particle_cube", "cube"), Map.entry("particle.cube", "cube"),
        Map.entry("particle_wave", "wave"), Map.entry("particle.wave", "wave"),
        Map.entry("particle_text", "text"), Map.entry("particle.text", "text"),
        Map.entry("particle_block_dust", "block_dust"), Map.entry("particle.block.dust", "block_dust"),
        Map.entry("particle_item_break", "item_break"), Map.entry("particle.item.break", "item_break"),
        Map.entry("particle_explosion", "explosion"), Map.entry("particle.explosion", "explosion")
    );
    private static final Map<String, String> ID_MIGRATION = FlowNodeMigrationMap.load();
    private static final List<GraphPath> DEFAULT_PATHS = List.of(new GraphPath("assets", ""));
    private final String graphOwner;
    private final List<GraphPath> paths;
    private final Map<String, NodeSchema> schemas;

    public LegacyFlowGraphSnapshotAdapter() {
        this(GRAPH_OWNER, DEFAULT_PATHS, loadProductSchemas());
    }

    public LegacyFlowGraphSnapshotAdapter(String graphOwner, List<GraphPath> paths) {
        this(graphOwner, paths, loadProductSchemas());
    }

    public LegacyFlowGraphSnapshotAdapter(String graphOwner, List<GraphPath> paths, Map<String, NodeSchema> schemas) {
        this.graphOwner = requireText(graphOwner, "graphOwner");
        List<GraphPath> normalized = new ArrayList<>(paths == null ? List.of() : paths);
        normalized.sort(Comparator.comparing(GraphPath::prefix));
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("At Least One Graph Path Is Required");
        }
        Set<String> prefixes = new HashSet<>();
        Set<String> types = new HashSet<>();
        for (GraphPath path : normalized) {
            if (!prefixes.add(path.prefix()) || !types.add(path.resourceType())) {
                throw new IllegalArgumentException("Duplicate Graph Path Identity");
            }
        }
        this.paths = List.copyOf(normalized);
        Map<String, NodeSchema> normalizedSchemas = new HashMap<>();
        if (schemas != null) {
            for (Map.Entry<String, NodeSchema> entry : schemas.entrySet()) {
                normalizedSchemas.put(requireText(entry.getKey(), "nodeType"), Objects.requireNonNull(entry.getValue(), "node schema"));
            }
        }
        this.schemas = Map.copyOf(normalizedSchemas);
    }

    public static UpgradeSourceWindow sourceWindow() {
        return LegacySnapshotWindow.sourceWindow();
    }

    @Override
    public OfflineUpgradeAdapter.AdapterKey key() {
        return new OfflineUpgradeAdapter.AdapterKey(ADAPTER_ID, ADAPTER_VERSION);
    }

    @Override
    public String owner() {
        return graphOwner;
    }

    @Override
    public boolean claims(OfflineUpgradeSnapshotInput input) {
        Objects.requireNonNull(input, "input");
        if (!supports(input.snapshot().metadata()) || !productionMetadataMatches(input)) {
            return false;
        }
        GraphDiscovery discovery = graphEntries(input.root(), input.snapshot());
        return !discovery.entries().isEmpty()
            || !discovery.metadataEntries().isEmpty()
            || !discovery.invalidMetadataEntries().isEmpty()
            || input.snapshot().directories().stream().anyMatch(LegacyFlowGraphSnapshotAdapter::isSupportedGraphDirectory);
    }

    @Override
    public boolean owns(String relativePath, String sourceOwner) {
        return graphOwner.equals(sourceOwner) && !isTypedAutomationBackupPath(relativePath)
            && (path(relativePath) != null || isProjectMetadata(relativePath));
    }

    static boolean isTypedAutomationBackupPath(String relativePath) {
        String normalized = relativePath == null ? "" : relativePath.replace('\\', '/');
        if (!normalized.startsWith(TYPED_AUTOMATION_BACKUP_ROOT)) {
            return false;
        }
        String remainder = normalized.substring(TYPED_AUTOMATION_BACKUP_ROOT.length());
        int separator = remainder.indexOf('/');
        if (separator <= TYPED_AUTOMATION_BACKUP_PREFIX.length()) {
            return false;
        }
        String migrationId = remainder.substring(0, separator);
        return migrationId.startsWith(TYPED_AUTOMATION_BACKUP_PREFIX)
            && migrationId.length() > TYPED_AUTOMATION_BACKUP_PREFIX.length();
    }

    @Override
    public SnapshotTransform transform(OfflineUpgradeSnapshotInput input) throws IOException {
        Objects.requireNonNull(input, "input");
        requireSupportedProductionSnapshot(input);
        GraphDiscovery discovery = graphEntries(input.root(), input.snapshot());
        List<GraphEntry> entries = discovery.entries();
        List<String> claimed = new ArrayList<>(entries.stream().map(entry -> entry.entry().relativePath()).toList());
        claimed.addAll(discovery.metadataEntries().stream().map(ImmutableSnapshotAdapter.Entry::relativePath).toList());
        List<QuarantineRecord> quarantines = new ArrayList<>(discovery.invalidMetadataEntries().stream()
            .map(entry -> quarantine(entry, "MIGRATION.FLOW_PROJECT_METADATA_INVALID",
                "Project metadata is malformed or contains conflicting graph identities.",
                "Repair project metadata before migrating its graph documents.", List.of(entry.relativePath())))
            .toList());
        List<ValidatedGraph> valid = new ArrayList<>();
        for (GraphEntry candidate : entries) {
            ImmutableSnapshotAdapter.Entry entry = candidate.entry();
            GraphPath graphPath = candidate.path();
            if (graphPath == null || !graphOwner.equals(entry.owner())) {
                quarantines.add(quarantine(entry, "MIGRATION.FLOW_GRAPH_MISSING_OWNER",
                    "The graph is not owned by the flow storage participant.",
                    "Repair the snapshot ownership before migrating flow graphs.", List.of(entry.owner())));
                continue;
            }
            try {
                RawGraph graph = readGraph(input.root(), candidate);
                valid.add(new ValidatedGraph(entry, graphPath, graph));
            } catch (RuntimeException | IOException exception) {
                quarantines.add(quarantine(entry, code(exception),
                    "The graph is not a supported typed legacy FlowGraph document.",
                    "Repair the graph identity or retain it in quarantine before retrying.",
                    List.of(graphPath.resourceType().isBlank() ? "graph" : graphPath.resourceType())));
            }
        }
        Map<String, List<ValidatedGraph>> byIdentity = new HashMap<>();
        for (ValidatedGraph candidate : valid) {
            byIdentity.computeIfAbsent(candidate.graph().resourceType() + "\u0000" + candidate.graph().id(), ignored -> new ArrayList<>())
                .add(candidate);
        }
        for (List<ValidatedGraph> collision : byIdentity.values()) {
            if (collision.size() < 2) {
                continue;
            }
            List<String> references = collision.stream().map(candidate -> candidate.entry().relativePath())
                .sorted(CanonicalJson::compareCodePoints).toList();
            for (ValidatedGraph candidate : collision) {
                quarantines.add(quarantine(candidate.entry(), "MIGRATION.FLOW_GRAPH_IDENTITY_COLLISION",
                    "More than one graph document declares the same typed identity.",
                    "Retain one canonical graph document for the typed identity before retrying.", references));
            }
            valid.removeAll(collision);
        }
        List<FileTransform> files = new ArrayList<>();
        for (ValidatedGraph candidate : valid) {
            try {
                Map<String, Object> transformed = migrate(candidate.graph().root(), candidate.graph().resourceType());
                RawGraph output = new RawGraph(transformed, candidate.graph().id(), candidate.graph().resourceType());
                if (!candidate.graph().root().equals(output.root())) {
                    files.add(new FileTransform(candidate.entry().relativePath(), candidate.entry().relativePath(),
                        CanonicalCodec.encode(JsonValue.fromJava(output.root()))));
                }
            } catch (RuntimeException exception) {
                quarantines.add(quarantine(candidate.entry(), code(exception),
                    "The graph contains malformed legacy node or connection content.",
                    "Repair the graph document or retain it in quarantine before retrying.",
                    List.of(candidate.graph().resourceType(), exception.getMessage() == null ? "graph" : exception.getMessage())));
            }
        }
        return new SnapshotTransform(files, claimed, quarantines);
    }

    public byte[] transformGraph(byte[] sourceBytes, String resourceType) {
        Objects.requireNonNull(sourceBytes, "sourceBytes");
        String type = requireText(resourceType, "resourceType");
        Map<String, Object> root = object(CanonicalCodec.decodePermissive(sourceBytes), "graph");
        RawGraph source = new RawGraph(root, requiredText(root.get("id"), "graph id"), type);
        return CanonicalCodec.encode(JsonValue.fromJava(migrate(source.root(), source.resourceType())));
    }

    public static boolean supports(SnapshotMetadata metadata) {
        return metadata != null && metadata.formatVersion() == SOURCE_FORMAT_VERSION && SOURCE_BUILD.equals(metadata.build());
    }

    private Map<String, Object> migrate(Map<String, Object> source, String resourceType) {
        Map<String, Object> graph = deepObject(source);
        if (!GRAPH_TYPES.contains(resourceType)) {
            throw new IllegalArgumentException("FLOW_GRAPH_RESOURCE_TYPE_UNSUPPORTED");
        }
        int version = integer(graph.get("version"), 0);
        boolean outdated = version < TARGET_FORMAT_VERSION;
        boolean changed = version >= TARGET_FORMAT_VERSION && !hasValidAssetIdentity(graph, resourceType);
        boolean malformedPassthrough = hasRawPassthrough(graph);
        if (outdated) {
            changed |= put(graph, "version", TARGET_FORMAT_VERSION);
            changed |= normalizeGraphMetadata(graph);
        }
        Map<String, Object> nodes = objectOrEmpty(graph.get("nodes"));
        if (!graph.containsKey("nodes")) {
            graph.put("nodes", nodes);
            changed = true;
        }
        Map<String, String> originalTypes = new LinkedHashMap<>();
        Map<String, Object> nodeValues = new LinkedHashMap<>();
        for (String nodeId : nodes.keySet().stream().sorted(CanonicalJson::compareCodePoints).toList()) {
            Object rawNode = nodes.get(nodeId);
            if (!(rawNode instanceof Map<?, ?>)) {
                throw new IllegalArgumentException("FLOW_GRAPH_NODE_INVALID");
            }
            Map<String, Object> node = deepObject(rawNode);
            nodes.put(nodeId, node);
            String originalType = optionalText(node.get("type"));
            originalTypes.put(nodeId, originalType);
            String currentType = ID_MIGRATION.getOrDefault(originalType, originalType);
            boolean nodeChanged = false;
            if (!currentType.equals(originalType) && (!originalType.isBlank())) {
                nodeChanged |= put(node, "type", currentType);
            }
            int nodeVersion = integer(node.get("version"), 1);
            Map<String, Object> inputs = inputValues(node);
            if (!node.containsKey("inputValues")) {
                node.put("inputValues", inputs);
                nodeChanged = true;
            }
            if (!node.containsKey("x")) {
                node.put("x", 0.0d);
                nodeChanged = true;
            }
            if (!node.containsKey("y")) {
                node.put("y", 0.0d);
                nodeChanged = true;
            }
            Map<String, Object> handlerConfig = objectOrEmpty(node.get("handlerConfig"));
            if (!node.containsKey("handlerConfig")) {
                nodeChanged = true;
            }
            node.put("handlerConfig", handlerConfig);
            NodeSchema schema = pinSchema(originalType, currentType);
            NodeSchema targetSchema = canonicalSchema(currentType, schema);
            NodeSchema valueSchema = schema;
            if (valueSchema == null && "color_blend".equals(currentType)) {
                valueSchema = targetSchema;
            }
            if (schema == null && requiresSchema(originalType, currentType)) {
                throw new IllegalArgumentException("FLOW_GRAPH_SCHEMA_UNAVAILABLE");
            }
            nodeChanged |= migrateAuthoredInputPins(inputs, originalType, currentType);
            boolean nodeOutdated = targetSchema != null && nodeVersion < schemaTargetVersion(targetSchema);
            nodeChanged |= migrateNodeValues(graph, node, inputs, originalType, currentType, outdated, nodeOutdated,
                malformedPassthrough, valueSchema);
            nodeChanged |= migrateLegacyMiscTimeAddCanonicalStage(node, originalType, currentType);
            nodeChanged |= migrateColorBlendHandler(node, currentType);
            node.put("inputValues", inputs);
            nodeChanged |= migrateHandlerDefaults(node, valueSchema);
            nodeChanged |= normalizeNodeVersion(node, integer(node.get("version"), nodeVersion), targetSchema);
            if (nodeChanged) {
                changed = true;
            }
            nodeValues.put(nodeId, node);
        }
        graph.put("nodes", nodes);
        changed |= migrateConnections(graph, originalTypes, nodeValues, outdated);
        if (changed) {
            String existingType = optionalText(graph.get("resourceType"));
            if (existingType.isBlank()) {
                changed |= put(graph, "resourceType", resourceType);
            } else if (!resourceType.equals(existingType)) {
                throw new IllegalArgumentException("FLOW_GRAPH_RESOURCE_TYPE_MISMATCH");
            }
            applyAssetIdentity(graph, resourceType);
        }
        return graph;
    }

    static boolean hasValidAssetIdentity(Map<String, Object> graph, String resourceType) {
        try {
            if (!resourceType.equals(optionalText(graph.get("resourceType")))) {
                return false;
            }
            if (integer(graph.get("assetFormatVersion"), 0) != 3 || !graph.containsKey("assetRevision")
                || !graph.containsKey("assetMutationId") || !graph.containsKey("assetHash")) {
                return false;
            }
            long assetRevision = integerLongExact(graph.get("assetRevision"));
            long resourceRevision = integerLongExact(graph.get("resourceRevision"));
            if (assetRevision < 0 || assetRevision != resourceRevision) {
                return false;
            }
            String assetMutation = optionalText(graph.get("assetMutationId"));
            String resourceMutation = optionalText(graph.get("resourceMutationId"));
            if (assetMutation.isBlank() || !assetMutation.equals(resourceMutation)) {
                return false;
            }
            String hash = optionalText(graph.get("assetHash"));
            return hash.matches("[0-9a-fA-F]{64}") && hash.equalsIgnoreCase(sha256(assetHashBytes(graph)));
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private boolean normalizeGraphMetadata(Map<String, Object> graph) {
        boolean changed = false;
        changed |= defaultBoolean(graph, "enabled", true);
        changed |= defaultBoolean(graph, "function", false);
        changed |= defaultText(graph, "functionOwner", "server");
        changed |= defaultText(graph, "functionNamespace", "local");
        changed |= defaultNumber(graph, "functionVersion", 1);
        changed |= defaultText(graph, "functionDescription", "");
        changed |= normalizeParameters(graph, "functionInputs");
        changed |= normalizeParameters(graph, "functionOutputs");
        if (graph.get("editorPassthroughs") == null) {
            graph.put("editorPassthroughs", new ArrayList<>());
            changed = true;
        } else if (!(graph.get("editorPassthroughs") instanceof List<?>)) {
            throw new IllegalArgumentException("FLOW_GRAPH_EDITOR_PASSTHROUGHS_INVALID");
        }
        Object variablesValue = graph.get("localVariables");
        if (variablesValue == null) {
            graph.put("localVariables", new ArrayList<>());
            return true;
        }
        if (!(variablesValue instanceof List<?> variables)) {
            throw new IllegalArgumentException("FLOW_GRAPH_LOCAL_VARIABLES_INVALID");
        }
        List<Object> normalized = new ArrayList<>();
        for (Object value : variables) {
            if (!(value instanceof Map<?, ?>)) {
                throw new IllegalArgumentException("FLOW_GRAPH_LOCAL_VARIABLE_INVALID");
            }
            Map<String, Object> variable = deepObject(value);
            changed |= defaultText(variable, "scope", "local");
            changed |= defaultText(variable, "lifetime", "execution");
            changed |= defaultText(variable, "owner", "graph");
            changed |= defaultText(variable, "absencePolicy", "use_default");
            changed |= defaultText(variable, "concurrencyPolicy", "isolated");
            normalized.add(variable);
        }
        graph.put("localVariables", normalized);
        return changed;
    }

    private boolean normalizeParameters(Map<String, Object> graph, String field) {
        Object value = graph.get(field);
        if (value == null) {
            graph.put(field, new ArrayList<>());
            return true;
        }
        if (!(value instanceof List<?> parameters)) {
            throw new IllegalArgumentException("FLOW_GRAPH_PARAMETERS_INVALID");
        }
        boolean changed = false;
        List<Object> normalized = new ArrayList<>();
        for (Object valueEntry : parameters) {
            if (!(valueEntry instanceof Map<?, ?>)) {
                throw new IllegalArgumentException("FLOW_GRAPH_PARAMETER_INVALID");
            }
            Map<String, Object> parameter = deepObject(valueEntry);
            changed |= defaultText(parameter, "widget", "");
            changed |= defaultText(parameter, "optionsSource", "");
            changed |= defaultText(parameter, "defaultValue", "");
            Object typeRef = parameter.get("typeRef");
            if (typeRef instanceof String text) {
                Map<String, Object> normalizedType = typeRefForType(text);
                changed |= put(parameter, "typeRef", normalizedType);
            } else if (typeRef instanceof Map<?, ?>) {
                changed |= normalizeTypeRef(parameter, "typeRef");
            } else if (parameter.get("type") instanceof String text && !text.isBlank()) {
                changed |= put(parameter, "typeRef", typeRefForType(text));
            }
            normalized.add(parameter);
        }
        graph.put(field, normalized);
        return changed;
    }

    private boolean normalizeTypeRef(Map<String, Object> owner, String key) {
        Object value = owner.get(key);
        if (!(value instanceof Map<?, ?>)) {
            return false;
        }
        Map<String, Object> ref = deepObject(value);
        boolean changed = false;
        String typeId = optionalText(ref.get("typeId"));
        if (!typeId.isBlank()) {
            changed |= put(ref, "typeId", typeId.toLowerCase(Locale.ROOT));
            List<Object> arguments = listOrEmpty(ref.get("arguments"));
            if (arguments.isEmpty()) {
                List<Object> defaults = switch (typeId.toLowerCase(Locale.ROOT)) {
                    case "list", "set", "queue", "stack", "optional", "result", "job_reference" -> List.of(typeRef("any"));
                    case "map" -> List.of(typeRef("any"), typeRef("any"));
                    default -> List.of();
                };
                if (!defaults.isEmpty()) {
                    ref.put("arguments", defaults);
                    changed = true;
                }
            } else {
                List<Object> normalized = new ArrayList<>();
                for (Object argument : arguments) {
                    if (argument instanceof Map<?, ?>) {
                        Map<String, Object> child = deepObject(argument);
                        Map<String, Object> wrapper = new LinkedHashMap<>();
                        wrapper.put("typeRef", child);
                        normalizeTypeRef(wrapper, "typeRef");
                        normalized.add(wrapper.get("typeRef"));
                    } else {
                        normalized.add(argument);
                    }
                }
                if (!normalized.equals(arguments)) {
                    ref.put("arguments", normalized);
                    changed = true;
                }
            }
        }
        owner.put(key, ref);
        return changed;
    }

    private boolean migrateNodeValues(Map<String, Object> graph, Map<String, Object> node, Map<String, Object> inputs,
                                      String originalType, String currentType, boolean outdated, boolean nodeOutdated,
                                      boolean malformedPassthrough, NodeSchema schema) {
        boolean changed = false;
        if ("player_push".equals(originalType)) {
            changed |= putIfDifferent(inputs, "mode", "push");
        }
        if (outdated && "entity.kill".equals(currentType)) {
            changed |= inputs.remove("action") != null;
        }
        if ((outdated || nodeOutdated || malformedPassthrough) && "if".equals(currentType) && !inputs.containsKey("condition")) {
            String nodeId = findNodeId(graph, node);
            if (!hasIncomingPin(graph, nodeId, "condition")) {
                inputs.put("condition", false);
                changed = true;
            }
        }
        if (Set.of("custom_content.item", "custom_content.block", "custom_content.armor").contains(currentType)) {
            Object legacySlot = inputs.remove("armor_slot");
            changed |= legacySlot != null;
            if ("custom_content.armor".equals(currentType)) {
                Map<String, Object> properties = objectOrEmpty(graph.get("contentProperties"));
                if (!graph.containsKey("contentProperties")) {
                    graph.put("contentProperties", properties);
                    changed = true;
                }
                if (!properties.containsKey("armor_slot")) {
                    properties.put("armor_slot", optionalText(legacySlot).isBlank() ? "chest" : optionalText(legacySlot));
                    changed = true;
                }
                graph.put("contentProperties", properties);
            }
        }
        String scheduleType = switch (originalType == null ? "" : originalType) {
            case "misc.delay", "delay.ticks" -> "schedule.wait_ticks";
            case "delay", "delay.seconds" -> "schedule.delay";
            case "cancel.schedule" -> "schedule.cancel_task";
            default -> null;
        };
        if (scheduleType != null) {
            changed |= put(node, "type", scheduleType);
            String operation = switch (scheduleType) {
                case "schedule.wait_ticks" -> "wait_ticks";
                case "schedule.cancel_task" -> "cancel_task";
                default -> "delay";
            };
            changed |= putHandlerOperation(node, operation);
            currentType = scheduleType;
        }
        String particleMode = LEGACY_PARTICLE_MODES.get(originalType);
        if (particleMode == null) {
            particleMode = LEGACY_PARTICLE_MODES.get(currentType);
        }
        if (particleMode != null) {
            changed |= put(node, "type", "particle.apply");
            changed |= putIfDifferent(inputs, "mode", particleMode);
            changed |= move(inputs, "particle_type", "particle");
            changed |= move(inputs, "center_location", "location");
            changed |= move(inputs, "is_filled", "filled");
            changed |= move(inputs, "points", "count");
            changed |= putHandlerOperation(node, "particle_apply");
            currentType = "particle.apply";
        }
        if ("variable.access".equals(currentType)) {
            changed |= migrateVariableAccess(inputs, originalType);
            changed |= lowerCase(inputs, "mode");
            changed |= lowerCase(inputs, "scope");
        }
        if ("permission.perm_has".equals(currentType) || "perm.check".equals(currentType)) {
            changed |= migrateRepeatableCount(inputs, "__permission_count", "__repeatable_count:permissions");
        }
        changed |= migrateTimeValues(inputs, originalType, currentType);
        changed |= migrateColors(node, inputs, schema);
        changed |= migrateResourceReferences(currentType, inputs);
        return changed;
    }

    private boolean migrateLegacyMiscTimeAddCanonicalStage(Map<String, Object> node, String originalType,
                                                            String currentType) {
        if (!"misc.time_add".equals(originalType) || !"time_add".equals(currentType)) {
            return false;
        }
        NodeSchema schema = pinSchema("time.add", currentType);
        if (schema == null || schema.pinMappings().stream().noneMatch(mapping ->
            "output".equals(mapping.direction()) && "time".equals(mapping.sourcePinId())
                && "output_time".equals(mapping.targetPinId()) && mapping.sourceSchemaVersion() == 3
                && mapping.targetSchemaVersion() == 4)) {
            return false;
        }
        int version = integer(node.get("version"), 1);
        if (version >= 4) {
            return false;
        }
        node.put("version", 4);
        return true;
    }

    private boolean migrateColorBlendHandler(Map<String, Object> node, String currentType) {
        if (!"color_blend".equals(currentType)) {
            return false;
        }
        return putHandlerOperation(node, "color_blend");
    }

    private boolean migrateHandlerDefaults(Map<String, Object> node, NodeSchema schema) {
        if (schema == null || schema.handlerDefaults().isEmpty()) {
            return false;
        }
        Map<String, Object> config = objectOrEmpty(node.get("handlerConfig"));
        boolean changed = false;
        for (Map.Entry<String, Object> entry : schema.handlerDefaults().entrySet()) {
            if (!config.containsKey(entry.getKey())) {
                config.put(entry.getKey(), deepCopy(entry.getValue()));
                changed = true;
            }
        }
        node.put("handlerConfig", config);
        return changed;
    }

    private boolean normalizeNodeVersion(Map<String, Object> node, int version, NodeSchema schema) {
        int targetVersion = schema == null ? 1 : schemaTargetVersion(schema);
        if (version >= targetVersion) {
            return false;
        }
        node.put("version", targetVersion);
        return true;
    }

    private NodeSchema canonicalSchema(String currentType, NodeSchema pinSchema) {
        NodeSchema currentSchema = schemas.get(currentType);
        if (currentSchema != null) {
            return currentSchema;
        }
        return schemas.entrySet().stream()
            .filter(entry -> currentType.equals(ID_MIGRATION.get(entry.getKey())))
            .map(Map.Entry::getValue)
            .max(Comparator.comparingInt(this::schemaTargetVersion))
            .orElse(pinSchema);
    }

    private int schemaTargetVersion(NodeSchema schema) {
        return Math.max(schema.version(), schema.pinMappings().stream()
            .mapToInt(PinMapping::targetSchemaVersion)
            .max()
            .orElse(schema.version()));
    }

    private boolean putHandlerOperation(Map<String, Object> node, String operation) {
        Map<String, Object> config = objectOrEmpty(node.get("handlerConfig"));
        boolean changed = !operation.equals(config.get("operation"));
        config.put("operation", operation);
        node.put("handlerConfig", config);
        return changed;
    }

    private boolean migrateTimeValues(Map<String, Object> inputs, String originalType, String currentType) {
        if (originalType == null) {
            return false;
        }
        boolean changed = false;
        switch (originalType) {
            case "misc.time_format" -> {
                changed |= move(inputs, "timestamp_ms", "time");
                changed |= move(inputs, "format_pattern", "format");
            }
            case "misc.time_parse" -> {
                changed |= move(inputs, "date_string", "string");
                changed |= move(inputs, "format_pattern", "format");
            }
            case "misc.time_add" -> changed |= move(inputs, "timestamp_ms", "time");
            case "misc.time_diff" -> {
                changed |= move(inputs, "timestamp1_ms", "time1");
                changed |= move(inputs, "timestamp2_ms", "time2");
            }
            default -> {
            }
        }
        if (Set.of("time.format", "time.parse", "time.add", "time_format", "time_parse", "time_add",
            "schedule.schedule", "schedule.cron", "schedule.at.time").contains(currentType)
            && optionalText(inputs.get("time_zone")).isBlank()) {
            inputs.put("time_zone", "UTC");
            changed = true;
        }
        return changed;
    }

    private boolean migrateColors(Map<String, Object> node, Map<String, Object> inputs, NodeSchema schema) {
        String type = optionalText(node.get("type")).toLowerCase(Locale.ROOT);
        boolean changed = false;
        for (Map.Entry<String, Object> entry : new ArrayList<>(inputs.entrySet())) {
            String declaredType = schema == null ? "" : optionalText(schema.inputTypes().get(entry.getKey())).toLowerCase(Locale.ROOT);
            boolean named = declaredType.equals("named_text_color");
            boolean rgb = declaredType.equals("rgb_color");
            if (!named && !rgb) {
                continue;
            }
            Object value = entry.getValue();
            Object normalized = rgb ? normalizeRgbColor(value) : named ? normalizeNamedColor(value) : value;
            if (!Objects.equals(value, normalized)) {
                inputs.put(entry.getKey(), normalized);
                changed = true;
            }
        }
        return changed;
    }

    private boolean migrateResourceReferences(String nodeType, Map<String, Object> inputs) {
        if (nodeType == null) {
            return false;
        }
        return switch (nodeType) {
            case "loot.generate", "loot.give", "loot.fill_container" -> reference(inputs, "loot_table", "loot_table");
            case "trade.apply_trade_profile", "trade.open_trades" -> reference(inputs, "profile_id", "trade_profile");
            case "npc.spawn", "npc.despawn", "npc.open" -> reference(inputs, "npc_id", "npc_definition");
            case "npc.set_profile" -> reference(inputs, "npc_id", "npc_definition") | reference(inputs, "profile_id", "trade_profile");
            case "network.get.server.health", "network.server.mode" -> reference(inputs, "node_id", "network_node");
            case "network.player.send" -> reference(inputs, "server", "network_route");
            case "network.player.handoff" -> reference(inputs, "target_node", "network_node") | reference(inputs, "server", "network_route");
            case "scoreboard.show.template", "scoreboard_show_template" -> reference(inputs, "scoreboard_id", "scoreboard");
            default -> false;
        };
    }

    private boolean reference(Map<String, Object> inputs, String pin, String kind) {
        Object value = inputs.get(pin);
        if (!(value instanceof String id) || id.isBlank()) {
            return false;
        }
        inputs.put(pin, new LinkedHashMap<>(Map.of("kind", kind, "id", id, "owner", "builtin", "available", true, "metadata", Map.of())));
        return true;
    }

    private boolean migrateVariableAccess(Map<String, Object> inputs, String originalType) {
        boolean changed = move(inputs, "key", "name") | move(inputs, "variable", "name") | move(inputs, "variable_name", "name");
        if (originalType == null) {
            return changed;
        }
        String mode = null;
        String scope = null;
        switch (originalType) {
            case "variable_set_global" -> { mode = "set"; scope = "global"; }
            case "variable_set_local" -> { mode = "set"; scope = "local"; }
            case "variable_set_player" -> { mode = "set"; scope = "player"; }
            case "variable_get_global" -> { mode = "get"; scope = "global"; }
            case "variable_get_local" -> { mode = "get"; scope = "local"; }
            case "variable_get_player" -> { mode = "get"; scope = "player"; }
            case "variable_delete" -> mode = "delete";
            case "variable_exists" -> mode = "exists";
            case "variable_list_all" -> mode = "list";
            case "variable_increment" -> mode = "increment";
            case "variable_decrement" -> mode = "decrement";
            case "variable_multiply" -> mode = "multiply";
            case "variable_divide" -> mode = "divide";
            default -> {
            }
        }
        if (mode != null) {
            changed |= putIfDifferent(inputs, "mode", mode);
        }
        if (scope != null) {
            changed |= putIfDifferent(inputs, "scope", scope);
        }
        return changed;
    }

    private boolean migrateRepeatableCount(Map<String, Object> inputs, String source, String target) {
        if (!inputs.containsKey(source)) {
            return false;
        }
        Object value = inputs.remove(source);
        inputs.putIfAbsent(target, value);
        return true;
    }

    private boolean migrateAuthoredInputPins(Map<String, Object> inputs, String originalType, String currentType) {
        NodeSchema schema = pinSchema(originalType, currentType);
        if (schema == null || schema.pinMappings().isEmpty()) {
            return false;
        }
        List<PinMapping> mappings = schema.pinMappings().stream()
            .filter(mapping -> "input".equals(mapping.direction()))
            .toList();
        if (mappings.isEmpty()) {
            return false;
        }
        Set<String> sourcePins = new HashSet<>();
        Set<String> targetPins = new HashSet<>();
        for (PinMapping mapping : mappings) {
            String sourcePin = mapping.sourcePinId();
            String targetPin = mapping.targetPinId();
            if (!sourcePins.add(sourcePin) || !targetPins.add(targetPin)
                || !sourcePin.equals(targetPin) && (sourcePins.contains(targetPin) || targetPins.contains(sourcePin))) {
                throw new IllegalArgumentException("FLOW_GRAPH_AUTHORED_PIN_MAPPING_AMBIGUOUS");
            }
        }
        Map<String, Object> migrated = new LinkedHashMap<>(inputs);
        boolean changed = false;
        for (PinMapping mapping : mappings) {
            String sourcePin = mapping.sourcePinId();
            String targetPin = mapping.targetPinId();
            if (sourcePin.equals(targetPin) || !inputs.containsKey(sourcePin)) {
                continue;
            }
            if (inputs.containsKey(targetPin)) {
                throw new IllegalArgumentException("FLOW_GRAPH_AUTHORED_PIN_INPUT_COLLISION");
            }
            migrated.remove(sourcePin);
            migrated.put(targetPin, inputs.get(sourcePin));
            changed = true;
        }
        if (changed) {
            inputs.clear();
            inputs.putAll(migrated);
        }
        return changed;
    }

    private boolean migrateConnections(Map<String, Object> graph, Map<String, String> originalTypes,
                                       Map<String, Object> nodes, boolean outdated) {
        Object rawConnections = graph.get("connections");
        if (rawConnections == null) {
            graph.put("connections", new ArrayList<>());
            return true;
        }
        if (!(rawConnections instanceof List<?>)) {
            throw new IllegalArgumentException("FLOW_GRAPH_CONNECTIONS_INVALID");
        }
        List<Object> connections = deepList(rawConnections);
        boolean changed = false;
        Map<PinIdentity, String> authoredPinIdentities = new HashMap<>();
        for (int index = 0; index < connections.size(); index++) {
            Object raw = connections.get(index);
            if (!(raw instanceof Map<?, ?>)) {
                throw new IllegalArgumentException("FLOW_GRAPH_CONNECTION_INVALID");
            }
            Map<String, Object> connection = deepObject(raw);
            connections.set(index, connection);
            String sourceId = optionalText(connection.get("sourceNodeId"));
            String sourcePin = optionalText(connection.get("sourcePin"));
            String targetId = optionalText(connection.get("targetNodeId"));
            String targetPin = optionalText(connection.get("targetPin"));
            if (normalizePassthroughSource(graph, connection)) {
                changed = true;
                sourceId = optionalText(connection.get("sourceNodeId"));
                sourcePin = optionalText(connection.get("sourcePin"));
            }
            Map<String, Object> sourceNode = objectOrEmpty(nodes.get(sourceId));
            Map<String, Object> targetNode = objectOrEmpty(nodes.get(targetId));
            String originalSourceType = originalTypes.get(sourceId);
            String originalTargetType = originalTypes.get(targetId);
            if (isLegacyBreakContinuation(originalSourceType, sourceNode, sourcePin)) {
                String loopId = enclosingLoopNodeId(graph, nodes, sourceId);
                if (loopId != null) {
                    connection.put("sourceNodeId", loopId);
                    connection.put("sourcePin", "done");
                    sourceId = loopId;
                    sourcePin = "done";
                    sourceNode = objectOrEmpty(nodes.get(loopId));
                    originalSourceType = originalTypes.get(loopId);
                    changed = true;
                }
            }
            String mappedSource = mapSourcePin(originalSourceType, sourceNode, sourcePin, outdated);
            String mappedTarget = mapTargetPin(originalTargetType, targetNode, targetPin);
            rejectAuthoredPinCollision(authoredPinIdentities, sourceId, originalSourceType, sourceNode,
                sourcePin, mappedSource, "output");
            rejectAuthoredPinCollision(authoredPinIdentities, targetId, originalTargetType, targetNode,
                targetPin, mappedTarget, "input");
            if (!mappedSource.equals(sourcePin)) {
                connection.put("sourcePin", mappedSource);
                changed = true;
            }
            if (!mappedTarget.equals(targetPin)) {
                connection.put("targetPin", mappedTarget);
                changed = true;
            }
        }
        graph.put("connections", connections);
        return changed;
    }

    private void rejectAuthoredPinCollision(Map<PinIdentity, String> identities, String nodeId,
                                            String originalType, Map<String, Object> node, String persistedPin,
                                            String mappedPin, String direction) {
        if (nodeId.isBlank() || persistedPin.isBlank() || mappedPin.isBlank()) {
            return;
        }
        boolean legacyTimeAddOutput = "misc.time_add".equals(originalType)
            && "output".equals(direction) && "output_time".equals(mappedPin);
        NodeSchema schema = pinSchema(originalType, optionalText(node.get("type")));
        if (!legacyTimeAddOutput && (schema == null || schema.pinMappings().stream().noneMatch(mapping ->
            direction.equals(mapping.direction())
                && (persistedPin.equals(mapping.sourcePinId()) || mappedPin.equals(mapping.targetPinId()))))) {
            return;
        }
        PinIdentity identity = new PinIdentity(nodeId, direction, mappedPin);
        String previous = identities.putIfAbsent(identity, persistedPin);
        if (previous != null && !previous.equals(persistedPin)) {
            throw new IllegalArgumentException("FLOW_GRAPH_AUTHORED_PIN_"
                + direction.toUpperCase(Locale.ROOT) + "_COLLISION");
        }
    }

    private boolean normalizePassthroughSource(Map<String, Object> graph, Map<String, Object> connection) {
        String editorNodeId = optionalText(connection.get("editorSourceNodeId"));
        String editorPin = optionalText(connection.get("editorSourcePin"));
        String sourcePin = optionalText(connection.get("sourcePin"));
        if (!passthrough(sourcePin) && !passthrough(editorPin)) {
            return false;
        }
        String visibleNodeId = passthrough(sourcePin) ? optionalText(connection.get("sourceNodeId")) : editorNodeId;
        String visiblePin = passthrough(sourcePin) ? sourcePin : editorPin;
        ResolvedSource resolved = resolvePassthroughSource(graph, visibleNodeId, visiblePin, new HashSet<>());
        if (resolved == null || passthrough(resolved.pin())) {
            throw new IllegalArgumentException("FLOW_GRAPH_PASSTHROUGH_UNRESOLVED");
        }
        boolean changed = !resolved.nodeId().equals(optionalText(connection.get("sourceNodeId")))
            || !resolved.pin().equals(optionalText(connection.get("sourcePin")));
        connection.put("sourceNodeId", resolved.nodeId());
        connection.put("sourcePin", resolved.pin());
        if (passthrough(sourcePin)) {
            connection.put("editorSourceNodeId", visibleNodeId);
            connection.put("editorSourcePin", visiblePin);
            changed = true;
        }
        return changed;
    }

    private ResolvedSource resolvePassthroughSource(Map<String, Object> graph, String nodeId, String pin, Set<String> visited) {
        if (!passthrough(pin)) {
            return new ResolvedSource(nodeId, pin);
        }
        if (nodeId.isBlank() || !visited.add(nodeId + ':' + pin)) {
            return null;
        }
        String inputPin = pin.substring(PASSTHROUGH_OUTPUT_PREFIX.length());
        List<Object> connections = listOrEmpty(graph.get("connections"));
        for (Object raw : connections) {
            Map<String, Object> connection = objectOrEmpty(raw);
            if (nodeId.equals(optionalText(connection.get("targetNodeId"))) && inputPin.equals(optionalText(connection.get("targetPin")))) {
                String editorPin = optionalText(connection.get("editorSourcePin"));
                String nextNode = passthrough(editorPin) ? optionalText(connection.get("editorSourceNodeId")) : optionalText(connection.get("sourceNodeId"));
                String nextPin = passthrough(editorPin) ? editorPin : optionalText(connection.get("sourcePin"));
                return resolvePassthroughSource(graph, nextNode, nextPin, visited);
            }
        }
        return null;
    }

    private boolean hasRawPassthrough(Map<String, Object> graph) {
        return listOrEmpty(graph.get("connections")).stream().anyMatch(raw -> passthrough(optionalText(objectOrEmpty(raw).get("sourcePin"))));
    }

    private String enclosingLoopNodeId(Map<String, Object> graph, Map<String, Object> nodes, String nodeId) {
        ArrayDeque<String> pending = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        pending.add(nodeId);
        visited.add(nodeId);
        while (!pending.isEmpty()) {
            String current = pending.removeFirst();
            for (Object raw : listOrEmpty(graph.get("connections"))) {
                Map<String, Object> connection = objectOrEmpty(raw);
                if (!current.equals(optionalText(connection.get("targetNodeId"))) || !isExecutionInputPin(optionalText(connection.get("targetPin")))) {
                    continue;
                }
                String sourceId = optionalText(connection.get("sourceNodeId"));
                Map<String, Object> source = objectOrEmpty(nodes.get(sourceId));
                if (isLoopBodyOutput(optionalText(source.get("type")), optionalText(connection.get("sourcePin")))) {
                    return sourceId;
                }
                if (visited.add(sourceId)) {
                    pending.add(sourceId);
                }
            }
        }
        return null;
    }

    private String mapSourcePin(String originalType, Map<String, Object> sourceNode, String pin, boolean outdated) {
        if (pin.isBlank()) {
            return pin;
        }
        String authored = authoredPin(originalType, sourceNode, pin, "output");
        if (authored != null) {
            return authored;
        }
        if (originalType != null && (originalType.startsWith("event:") || originalType.startsWith("event."))) {
            if (LEGACY_EVENT_FLOW_PINS.contains(pin)) {
                return "flow";
            }
            if (pin.startsWith("event.") && LEGACY_EVENT_FLOW_PINS.contains(pin.substring(6))) {
                return "flow";
            }
            if (!"flow".equals(pin) && !pin.startsWith("event.")) {
                return "event." + pin;
            }
        }
        if (originalType == null) {
            return pin;
        }
        String mapped = switch (originalType) {
            case "loop_while" -> outdated && "completed".equals(pin) ? "done" : "flow".equals(pin) ? "loop" : pin;
            case "loop.for.each", "loop.for.each.player", "loop.for.each.entity", "loop.count",
                 "logic_loop_for_each", "logic_loop_for_each_player", "logic_loop_for_each_entity", "flow.loop_count" -> "flow".equals(pin) ? "loop" : pin;
            case "misc.time_format" -> "formatted_string".equals(pin) ? "string" : pin;
            case "misc.time_parse" -> "timestamp_ms".equals(pin) ? "time" : pin;
            case "misc.time_add" -> "new_timestamp".equals(pin) ? "time" : pin;
            case "misc.time_diff" -> "diff_value".equals(pin) ? "unit_diff" : pin;
            case "misc.delay", "delay.ticks", "delay", "delay.seconds" -> "done".equals(pin) ? "completed" : pin;
            case "particle_spawn", "particle.spawn", "particle_area", "particle.area", "particle_player_spawn", "particle.player.spawn",
                 "particle_line", "particle.line", "particle_circle", "particle.circle", "particle_sphere", "particle.sphere",
                 "particle_ellipse", "particle.ellipse", "particle_spiral", "particle.spiral", "particle_cone", "particle.cone",
                 "particle_ring", "particle.ring", "particle_cube", "particle.cube", "particle_wave", "particle.wave",
                 "particle_text", "particle.text", "particle_block_dust", "particle.block.dust", "particle_item_break", "particle.item.break",
                  "particle_explosion", "particle.explosion" -> "next".equals(pin) ? "output_flow" : pin;
            default -> pin;
        };
        if (!"misc.time_add".equals(originalType) || !"time".equals(mapped)) {
            return mapped;
        }
        String canonical = authoredPin("time.add", sourceNode, mapped, "output");
        return canonical == null ? mapped : canonical;
    }

    private String mapTargetPin(String originalType, Map<String, Object> targetNode, String pin) {
        if (pin.isBlank()) {
            return pin;
        }
        String authored = authoredPin(originalType, targetNode, pin, "input");
        if (authored != null) {
            return authored;
        }
        String targetType = optionalText(targetNode.get("type"));
        if ("entity_kill".equals(originalType) && "entity.kill".equals(targetType) && "entity".equals(pin)) {
            return "target";
        }
        if ("variable.access".equals(targetType)) {
            return switch (pin) {
                case "key", "variable", "variable_name" -> "name";
                default -> pin;
            };
        }
        if (originalType == null) {
            return pin;
        }
        return switch (originalType) {
            case "misc.time_format" -> switch (pin) {
                case "timestamp_ms" -> "time";
                case "format_pattern" -> "format";
                default -> pin;
            };
            case "misc.time_parse" -> switch (pin) {
                case "date_string" -> "string";
                case "format_pattern" -> "format";
                default -> pin;
            };
            case "misc.time_add" -> "timestamp_ms".equals(pin) ? "time" : pin;
            case "misc.time_diff" -> switch (pin) {
                case "timestamp1_ms" -> "time1";
                case "timestamp2_ms" -> "time2";
                default -> pin;
            };
            case "particle_spawn", "particle.spawn", "particle_area", "particle.area", "particle_player_spawn", "particle.player.spawn",
                 "particle_line", "particle.line", "particle_circle", "particle.circle", "particle_sphere", "particle.sphere",
                 "particle_ellipse", "particle.ellipse", "particle_spiral", "particle.spiral", "particle_cone", "particle.cone",
                 "particle_ring", "particle.ring", "particle_cube", "particle.cube", "particle_wave", "particle.wave",
                 "particle_text", "particle.text", "particle_block_dust", "particle.block.dust", "particle_item_break", "particle.item.break",
                 "particle_explosion", "particle.explosion" -> switch (pin) {
                case "particle_type" -> "particle";
                case "center_location" -> "location";
                case "is_filled" -> "filled";
                case "points" -> "count";
                case "next" -> "flow";
                default -> pin;
            };
            default -> pin;
        };
    }

    private String authoredPin(String originalType, Map<String, Object> node, String pin, String direction) {
        NodeSchema schema = pinSchema(originalType, optionalText(node.get("type")));
        if (schema == null) {
            return null;
        }
        for (PinMapping mapping : schema.pinMappings()) {
            if (direction.equals(mapping.direction()) && pin.equals(mapping.sourcePinId())) {
                return mapping.targetPinId();
            }
        }
        return null;
    }

    private NodeSchema pinSchema(String originalType, String currentType) {
        NodeSchema schema = schemas.get(originalType);
        return schema == null ? schemas.get(currentType) : schema;
    }

    private RawGraph readGraph(Path root, GraphEntry candidate) throws IOException {
        ImmutableSnapshotAdapter.Entry entry = candidate.entry();
        Map<String, Object> document;
        try {
            document = object(CanonicalCodec.decodePermissive(Files.readAllBytes(MigrationPaths.resolveInside(root, entry.relativePath()))), "graph");
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("FLOW_GRAPH_DOCUMENT_INVALID", exception);
        }
        String id = requiredText(document.get("id"), "graph id");
        String fileId = candidate.path().resourceId(entry.relativePath());
        if (!id.equals(fileId)) {
            throw new IllegalArgumentException("FLOW_GRAPH_IDENTITY_MISMATCH");
        }
        String type = graphType(document, candidate.declaration(), candidate.path(), entry.relativePath());
        if (!candidate.path().resourceType().isBlank() && !candidate.path().resourceType().equals(type)) {
            throw new IllegalArgumentException("FLOW_GRAPH_RESOURCE_TYPE_MISMATCH");
        }
        String declaredType = optionalText(document.get("resourceType"));
        if (!declaredType.isBlank() && !declaredType.equals(type)) {
            throw new IllegalArgumentException("FLOW_GRAPH_RESOURCE_TYPE_MISMATCH");
        }
        if (candidate.declaration() != null && !candidate.declaration().resourceType().equals(type)) {
            throw new IllegalArgumentException("FLOW_GRAPH_METADATA_TYPE_MISMATCH");
        }
        objectOrEmpty(document.get("nodes"));
        listOrEmpty(document.get("connections"));
        return new RawGraph(document, id, type);
    }

    private GraphDiscovery graphEntries(Path root, ImmutableSnapshotAdapter.View snapshot) {
        Map<String, GraphDeclaration> declarations = new HashMap<>();
        List<ImmutableSnapshotAdapter.Entry> metadataEntries = new ArrayList<>();
        List<ImmutableSnapshotAdapter.Entry> invalidMetadataEntries = new ArrayList<>();
        boolean metadataInvalid = false;
        for (ImmutableSnapshotAdapter.Entry entry : snapshot.entries()) {
            if (!isProjectMetadata(entry.relativePath())) {
                continue;
            }
            metadataEntries.add(entry);
            try {
                Map<String, Object> document = object(CanonicalCodec.decodePermissive(
                    Files.readAllBytes(MigrationPaths.resolveInside(root, entry.relativePath()))), "project metadata");
                Object resourcesValue = document.get("resources");
                if (!(resourcesValue instanceof List<?> resources)) {
                    throw new IllegalArgumentException("Project resources must be an array");
                }
                Map<String, GraphDeclaration> parsed = new HashMap<>();
                for (Object value : resources) {
                    if (!(value instanceof Map<?, ?>)) {
                        throw new IllegalArgumentException("Project resource must be an object");
                    }
                    Map<String, Object> resource = deepObject(value);
                    String type = optionalText(resource.get("type"));
                    String id = optionalText(resource.get("id"));
                    String folder = optionalText(resource.get("path"));
                    if (!GRAPH_TYPES.contains(type)) {
                        continue;
                    }
                    if (id.isBlank() || folder.isBlank()) {
                        throw new IllegalArgumentException("Project graph resource identity is incomplete");
                    }
                    String normalizedFolder = normalizeProjectFolder(folder);
                    String fileName = id + ".json";
                    String relative = normalizedFolder.endsWith(".json") ? normalizedFolder : normalizedFolder + "/" + fileName;
                    if (isTypedAutomationBackupPath(relative)) {
                        continue;
                    }
                    GraphDeclaration declaration = new GraphDeclaration(type, id);
                    registerDeclaration(parsed, relative, declaration);
                    String typedRelative = normalizedFolder + "/" + type + "__" + id + ".json";
                    if (!isTypedAutomationBackupPath(typedRelative)) {
                        registerDeclaration(parsed, typedRelative, declaration);
                    }
                }
                for (Map.Entry<String, GraphDeclaration> declaration : parsed.entrySet()) {
                    registerDeclaration(declarations, declaration.getKey(), declaration.getValue());
                }
            } catch (IOException | RuntimeException exception) {
                metadataInvalid = true;
                invalidMetadataEntries.add(entry);
            }
        }
        boolean metadataInvalidFinal = metadataInvalid;
        List<GraphEntry> entries = snapshot.entries().stream()
            .map(entry -> {
                GraphPath graphPath = path(entry.relativePath());
                if (graphPath == null || isProjectMetadata(entry.relativePath())
                    || isTypedAutomationBackupPath(entry.relativePath())) {
                    return null;
                }
                GraphDeclaration declaration = declarations.get(entry.relativePath());
                String typedType = graphPath.typedResourceType(entry.relativePath());
                boolean graphDocument = declaration != null || !typedType.isBlank();
                if (!graphDocument && !metadataInvalidFinal) {
                    try {
                        Map<String, Object> document = object(CanonicalCodec.decodePermissive(
                            Files.readAllBytes(MigrationPaths.resolveInside(root, entry.relativePath()))), "graph");
                        graphDocument = isExplicitGraphIdentity(document);
                    } catch (IOException | RuntimeException ignored) {
                        return null;
                    }
                }
                return graphDocument ? new GraphEntry(entry, graphPath, declaration) : null;
            })
            .filter(Objects::nonNull)
            .sorted(Comparator.comparing(entry -> entry.entry().relativePath(), CanonicalJson::compareCodePoints))
            .toList();
        return new GraphDiscovery(entries, metadataEntries, invalidMetadataEntries);
    }

    private void registerDeclaration(Map<String, GraphDeclaration> declarations, String path, GraphDeclaration declaration) {
        GraphDeclaration previous = declarations.putIfAbsent(path, declaration);
        if (previous != null && !previous.equals(declaration)) {
            throw new IllegalArgumentException("Conflicting project graph declarations: " + path);
        }
    }

    private String normalizeProjectFolder(String folder) {
        String normalized = MigrationPaths.requireRelative(folder).replace('\\', '/');
        return normalized.startsWith("assets/") ? normalized : "assets/" + normalized;
    }

    private boolean isExplicitGraphIdentity(Map<String, Object> document) {
        String id = optionalText(document.get("id"));
        if (id.isBlank()) {
            return false;
        }
        String resourceType = optionalText(document.get("resourceType"));
        return GRAPH_TYPES.contains(resourceType) || Boolean.TRUE.equals(document.get("function"));
    }

    private String graphType(Map<String, Object> document, GraphDeclaration declaration, GraphPath path, String relativePath) {
        String declared = optionalText(document.get("resourceType"));
        if (!declared.isBlank()) {
            if (!GRAPH_TYPES.contains(declared)) {
                throw new IllegalArgumentException("FLOW_GRAPH_RESOURCE_TYPE_UNSUPPORTED");
            }
            return declared;
        }
        if (declaration != null) {
            return declaration.resourceType();
        }
        String typedFilename = path.typedResourceType(relativePath);
        if (!typedFilename.isBlank()) {
            return typedFilename;
        }
        if (Boolean.TRUE.equals(document.get("function"))) {
            return "function";
        }
        throw new IllegalArgumentException("FLOW_GRAPH_RESOURCE_TYPE_UNSUPPORTED");
    }

    private GraphPath path(String relativePath) {
        if (relativePath == null) {
            return null;
        }
        return paths.stream().filter(path -> path.matches(relativePath)).findFirst().orElse(null);
    }

    private boolean isProjectMetadata(String relativePath) {
        return "assets/project.json".equals(relativePath) || "project.json".equals(relativePath);
    }

    private boolean productionMetadataMatches(OfflineUpgradeSnapshotInput input) {
        try {
            ProductionSnapshotMetadataManifest.Values manifest = ProductionSnapshotMetadataManifest.read(input.provenanceRoot());
            return manifest.metadata().equals(input.snapshot().metadata()) && manifest.manifestHash().equals(input.snapshot().manifestHash());
        } catch (IOException | RuntimeException exception) {
            return false;
        }
    }

    private void requireSupportedProductionSnapshot(OfflineUpgradeSnapshotInput input) throws IOException {
        if (!supports(input.snapshot().metadata())) {
            throw new IOException("The snapshot is outside the fixed legacy FlowGraph source window");
        }
        try {
            ProductionSnapshotMetadataManifest.Values manifest = ProductionSnapshotMetadataManifest.read(input.provenanceRoot());
            if (!manifest.metadata().equals(input.snapshot().metadata()) || !manifest.manifestHash().equals(input.snapshot().manifestHash())) {
                throw new IOException("Production Snapshot Metadata Does Not Match The Bound Snapshot");
            }
        } catch (RuntimeException exception) {
            throw new IOException("Production Snapshot Metadata Is Invalid", exception);
        }
    }

    private static QuarantineRecord quarantine(ImmutableSnapshotAdapter.Entry entry, String code, String reason, String action, List<String> refs) {
        String record = "flow-graph-" + CanonicalJson.sha256("resync.flow-graph.quarantine", List.of(code, entry.relativePath(), entry.sha256(), reason, refs == null ? List.of() : refs)).substring(0, 24);
        return new QuarantineRecord(record, code, entry.relativePath(), reason, refs, action, entry.sha256());
    }

    private static String code(Exception exception) {
        String message = exception.getMessage();
        if (message == null) {
            return "MIGRATION.FLOW_GRAPH_UNSUPPORTED";
        }
        if (message.startsWith("FLOW_GRAPH_IDENTITY_COLLISION")) {
            return "MIGRATION.FLOW_GRAPH_IDENTITY_COLLISION";
        }
        if (message.startsWith("FLOW_GRAPH_RESOURCE_TYPE_MISMATCH")) {
            return "MIGRATION.FLOW_GRAPH_TYPE_MISMATCH";
        }
        if (message.startsWith("FLOW_GRAPH_PASSTHROUGH")) {
            return "MIGRATION.FLOW_GRAPH_PASSTHROUGH_UNRESOLVED";
        }
        return "MIGRATION.FLOW_GRAPH_UNSUPPORTED";
    }

    private static String normalizeType(String value) {
        String normalized = value == null || value.isBlank() ? "any" : value.strip().toLowerCase(Locale.ROOT);
        if (normalized.indexOf('<') >= 0) {
            return normalized;
        }
        return switch (normalized) {
            case "list", "set", "queue", "stack", "optional", "result", "job_reference" -> normalized + "<any>";
            case "map" -> "map<any,any>";
            default -> normalized;
        };
    }

    private static boolean requiresSchema(String originalType, String currentType) {
        String original = originalType == null ? "" : originalType;
        String current = currentType == null ? "" : currentType;
        return Set.of(
            "if", "entity.kill", "entity_kill", "custom_content.item", "custom_content.block", "custom_content.armor",
            "schedule.wait_ticks", "schedule.delay", "schedule.cancel_task", "misc.delay", "delay.ticks", "delay", "delay.seconds", "cancel.schedule",
            "particle.apply", "particle_spawn", "particle.spawn", "particle_area", "particle.area", "particle_player_spawn", "particle.player.spawn",
            "particle_line", "particle.line", "particle_circle", "particle.circle", "particle_sphere", "particle.sphere", "particle_ellipse", "particle.ellipse",
            "particle_spiral", "particle.spiral", "particle_cone", "particle.cone", "particle_ring", "particle.ring", "particle_cube", "particle.cube",
            "particle_wave", "particle.wave", "particle_text", "particle.text", "particle_block_dust", "particle.block.dust", "particle_item_break", "particle.item.break",
            "particle_explosion", "particle.explosion", "variable.access", "permission.perm_has", "perm.check", "time.format", "time.parse", "time.add", "time.diff",
            "misc.time_format", "misc.time_parse", "misc.time_add", "misc.time_diff", "schedule.schedule", "schedule.cron", "schedule.at.time",
            "loot.generate", "loot.give", "loot.fill_container", "trade.apply_trade_profile", "trade.open_trades", "npc.spawn", "npc.despawn", "npc.open",
            "npc.set_profile", "network.get.server.health", "network.server.mode", "network.player.send", "network.player.handoff", "scoreboard.show.template",
            "loop_while", "loop.count", "loop.for.each", "loop.for.each.player", "loop.for.each.entity"
        ).contains(original) || Set.of(
            "if", "entity.kill", "custom_content.item", "custom_content.block", "custom_content.armor", "schedule.wait_ticks", "schedule.delay",
            "schedule.cancel_task", "particle.apply", "variable.access", "permission.perm_has", "perm.check", "time.format", "time.parse", "time.add",
            "time.diff", "loot.generate", "loot.give", "loot.fill_container", "trade.apply_trade_profile", "trade.open_trades", "npc.spawn", "npc.despawn",
            "npc.open", "npc.set_profile", "network.get.server.health", "network.server.mode", "network.player.send", "network.player.handoff",
            "scoreboard.show.template", "loop_while", "loop.count", "loop.for.each", "loop.for.each.player", "loop.for.each.entity"
        ).contains(current);
    }

    private static Map<String, NodeSchema> loadProductSchemas() {
        String resource = FlowGraphMigrationSchemaCatalog.RESOURCE.substring(1);
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        ClassLoader fallback = LegacyFlowGraphSnapshotAdapter.class.getClassLoader();
        InputStream stream = loader != null ? loader.getResourceAsStream(resource) : null;
        if (stream == null && fallback != null) {
            stream = fallback.getResourceAsStream(resource);
        }
        if (stream == null) {
            throw new IllegalStateException("Product FlowGraph migration schema is missing: " + FlowGraphMigrationSchemaCatalog.RESOURCE);
        }
        try (InputStream input = stream) {
            return loadProductSchemas(input);
        } catch (IOException | RuntimeException exception) {
            if (exception instanceof IllegalStateException state) {
                throw state;
            }
            throw new IllegalStateException("Failed to load product FlowGraph migration schema", exception);
        }
    }

    public static Map<String, NodeSchema> loadProductSchemas(InputStream input) {
        if (input == null) {
            throw new IllegalStateException("Product FlowGraph migration schema is missing");
        }
        try {
            Map<String, Object> root = object(CanonicalCodec.decodePermissive(input.readAllBytes()), "flow graph migration schema");
            int version = integer(root.get(SCHEMA_VERSION_FIELD), 0);
            if (version != FlowGraphMigrationSchemaCatalog.VERSION) {
                throw new IllegalStateException("Unsupported product FlowGraph migration schema version: " + version);
            }
            String schemaHash = optionalText(root.get("schemaHash"));
            if (!schemaHash.matches("[0-9a-fA-F]{64}")) {
                throw new IllegalStateException("Product FlowGraph migration schema hash is missing");
            }
            Map<String, Object> unsigned = deepObject(root);
            unsigned.remove("schemaHash");
            if (!schemaHash.equalsIgnoreCase(CanonicalJson.sha256("resync.flow-graph-schema.artifact", unsigned))) {
                throw new IllegalStateException("Product FlowGraph migration schema hash is invalid");
            }
            Map<String, Object> source = object(root.get("source"), "flow graph migration schema source");
            if (!SOURCE_SCHEMA_MODE.equals(optionalText(source.get("mode")))
                || !SOURCE_SCHEMA_ROOT.equals(optionalText(source.get("root")))) {
                throw new IllegalStateException("Product FlowGraph migration schema source identity is invalid");
            }
            String sourceHash = optionalText(source.get("hash"));
            if (!sourceHash.matches("[0-9a-fA-F]{64}")) {
                throw new IllegalStateException("Product FlowGraph migration schema source hash is missing");
            }
            List<Object> sourceFiles = list(source.get("files"), "flow graph migration schema source files");
            if (sourceFiles.isEmpty()) {
                throw new IllegalStateException("Product FlowGraph migration schema source files are empty");
            }
            int sourceCount = integer(source.get("count"), -1);
            if (sourceCount != sourceFiles.size()) {
                throw new IllegalStateException("Product FlowGraph migration schema source count is invalid");
            }
            if (!sourceHash.equalsIgnoreCase(CanonicalJson.sha256("resync.flow-graph-schema.source", sourceFiles))) {
                throw new IllegalStateException("Product FlowGraph migration schema source hash is invalid");
            }
            Map<String, Object> definitions = object(root.get("nodes"), "flow graph migration schema nodes");
            if (definitions.isEmpty()) {
                throw new IllegalStateException("Product FlowGraph migration schema is empty");
            }
            Map<String, NodeSchema> result = new HashMap<>();
            for (Map.Entry<String, Object> entry : definitions.entrySet()) {
                Map<String, Object> definition = object(entry.getValue(), "node migration schema");
                int nodeVersion = integer(definition.get(SCHEMA_VERSION_FIELD), 0);
                if (nodeVersion < 1) {
                    throw new IllegalStateException("Product node migration schema version is invalid: " + entry.getKey());
                }
                Map<String, Object> handlerDefaults = objectOrEmpty(definition.get("handlerDefaults"));
                Map<String, String> inputTypes = new HashMap<>();
                Map<String, Object> rawInputTypes = objectOrEmpty(definition.get("inputTypes"));
                for (Map.Entry<String, Object> schemaInput : rawInputTypes.entrySet()) {
                    String type = optionalText(schemaInput.getValue());
                    if (type.isBlank()) {
                        throw new IllegalStateException("Product node migration input type is blank: " + entry.getKey());
                    }
                    inputTypes.put(schemaInput.getKey(), type);
                }
                List<PinMapping> pinMappings = parsePinMappings(definition, entry.getKey(), nodeVersion);
                result.put(entry.getKey(), new NodeSchema(nodeVersion, handlerDefaults, inputTypes,
                    objectOrEmpty(definition.get("normalization")), objectOrEmpty(definition.get("identity")), pinMappings));
            }
            return Map.copyOf(result);
        } catch (IOException | RuntimeException exception) {
            if (exception instanceof IllegalStateException state) {
                throw state;
            }
            throw new IllegalStateException("Failed to load product FlowGraph migration schema", exception);
        }
    }

    private static List<PinMapping> parsePinMappings(Map<String, Object> definition, String nodeType, int nodeVersion) {
        if (!definition.containsKey("pinMappings")) {
            return List.of();
        }
        Object rawMappings = definition.get("pinMappings");
        if (!(rawMappings instanceof List<?> mappings) || mappings.isEmpty()) {
            throw new IllegalStateException("Product node migration pin mappings are incomplete: " + nodeType);
        }
        List<PinMapping> result = new ArrayList<>();
        Set<String> sourcePins = new HashSet<>();
        Set<String> targetPins = new HashSet<>();
        Integer sourceVersion = null;
        Integer targetVersion = null;
        for (int index = 0; index < mappings.size(); index++) {
            Object rawMapping = mappings.get(index);
            if (!(rawMapping instanceof Map<?, ?>)) {
                throw new IllegalStateException("Product node migration pin mapping is invalid: " + nodeType + "#" + index);
            }
            Map<String, Object> mapping = object(rawMapping, "node migration pin mapping");
            String sourcePin = exactPinId(mapping.get("sourcePinId"), "sourcePinId", nodeType, index);
            String targetPin = exactPinId(mapping.get("targetPinId"), "targetPinId", nodeType, index);
            String direction = mapping.get("direction") instanceof String text ? text : "";
            if (!Set.of("input", "output").contains(direction)) {
                throw new IllegalStateException("Product node migration pin mapping direction is invalid: " + nodeType + "#" + index);
            }
            int mappingSourceVersion = mappingVersion(mapping.get("sourceSchemaVersion"), "sourceSchemaVersion", nodeType, index);
            int mappingTargetVersion = mappingVersion(mapping.get("targetSchemaVersion"), "targetSchemaVersion", nodeType, index);
            if (mappingSourceVersion < 1 || mappingTargetVersion <= mappingSourceVersion || mappingTargetVersion != nodeVersion) {
                throw new IllegalStateException("Product node migration pin mapping schema versions are invalid: " + nodeType + "#" + index);
            }
            if (sourceVersion != null && sourceVersion != mappingSourceVersion || targetVersion != null && targetVersion != mappingTargetVersion) {
                throw new IllegalStateException("Product node migration pin mapping schema versions are inconsistent: " + nodeType);
            }
            sourceVersion = mappingSourceVersion;
            targetVersion = mappingTargetVersion;
            if (!sourcePins.add(direction + ':' + sourcePin) || !targetPins.add(direction + ':' + targetPin)) {
                throw new IllegalStateException("Product node migration pin mapping collides: " + nodeType);
            }
            result.add(new PinMapping(sourcePin, targetPin, direction, mappingSourceVersion, mappingTargetVersion));
        }
        return List.copyOf(result);
    }

    private static String exactPinId(Object value, String field, String nodeType, int index) {
        if (!(value instanceof String pin) || pin.length() > 128 || !PIN_ID.matcher(pin).matches()) {
            throw new IllegalStateException("Product node migration " + field + " is not an exact PinId: " + nodeType + "#" + index);
        }
        return pin;
    }

    private static int mappingVersion(Object value, String field, String nodeType, int index) {
        if (!(value instanceof Number number)) {
            throw new IllegalStateException("Product node migration " + field + " is invalid: " + nodeType + "#" + index);
        }
        try {
            return new BigDecimal(number.toString()).intValueExact();
        } catch (ArithmeticException exception) {
            throw new IllegalStateException("Product node migration " + field + " is invalid: " + nodeType + "#" + index, exception);
        }
    }

    public static boolean isCanonicalManagedFlowFileNode(Map<String, Object> node) {
        if (node == null) {
            return false;
        }
        NodeSchema schema = loadProductSchemas().get(optionalText(node.get("type")));
        if (schema == null) {
            return false;
        }
        Map<String, Object> identity = schema.identity();
        return "FileHandler".equals(optionalText(identity.get("handler")))
            && "DATABASE".equals(optionalText(identity.get("category")))
            && "trusted_server_flow".equals(optionalText(identity.get("authorizationPolicy")))
            && optionalText(schema.handlerDefaults().get("operation")).matches("file_[a-z0-9_]+");
    }

    public static boolean isSupportedGraphType(String type) {
        return GRAPH_TYPES.contains(type);
    }

    public static boolean isSupportedGraphDirectory(String directory) {
        return FLOW_PREFIX.equals(directory) || FUNCTION_PREFIX.equals(directory) || COMMAND_PREFIX.equals(directory);
    }

    private static Map<String, Object> typeRef(String typeId) {
        return new LinkedHashMap<>(Map.of("typeId", typeId, "arguments", List.of()));
    }

    private static Map<String, Object> typeRefForType(String value) {
        String normalized = normalizeType(value);
        int opening = normalized.indexOf('<');
        if (opening < 0 || !normalized.endsWith(">")) {
            return typeRef(normalized);
        }
        String typeId = normalized.substring(0, opening).strip();
        String argumentsText = normalized.substring(opening + 1, normalized.length() - 1);
        List<Object> arguments = new ArrayList<>();
        for (String argument : splitTypeArguments(argumentsText)) {
            arguments.add(typeRefForType(argument));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("typeId", typeId);
        result.put("arguments", arguments);
        return result;
    }

    private static List<String> splitTypeArguments(String value) {
        List<String> result = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current == '<') {
                depth++;
            } else if (current == '>') {
                depth--;
            } else if (current == ',' && depth == 0) {
                result.add(value.substring(start, index).strip());
                start = index + 1;
            }
        }
        String last = value.substring(start).strip();
        if (!last.isBlank()) {
            result.add(last);
        }
        return result;
    }

    private static Object normalizeNamedColor(Object value) {
        if (!(value instanceof String text)) {
            return value;
        }
        String normalized = text.strip().toLowerCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        if (normalized.length() == 2 && (normalized.charAt(0) == '&' || normalized.charAt(0) == '§')) {
            return LEGACY_NAMED_COLORS.getOrDefault(normalized.charAt(1), text);
        }
        return NAMED_COLOR_RGB.containsKey(normalized) ? normalized : value;
    }

    private static Object normalizeRgbColor(Object value) {
        if (value instanceof Number number) {
            return String.format("#%06X", number.intValue() & 0xFFFFFF);
        }
        if (!(value instanceof String text)) {
            return value;
        }
        Object named = normalizeNamedColor(text);
        if (named instanceof String name && NAMED_COLOR_RGB.containsKey(name)) {
            return NAMED_COLOR_RGB.get(name);
        }
        String normalized = text.strip();
        if (normalized.startsWith("0x") || normalized.startsWith("0X")) {
            normalized = normalized.substring(2);
        } else if (normalized.startsWith("#")) {
            normalized = normalized.substring(1);
        }
        if (normalized.matches("(?i)[0-9a-f]{3}")) {
            normalized = "" + normalized.charAt(0) + normalized.charAt(0) + normalized.charAt(1) + normalized.charAt(1) + normalized.charAt(2) + normalized.charAt(2);
        }
        return normalized.matches("(?i)[0-9a-f]{6}") ? "#" + normalized.toUpperCase(Locale.ROOT) : value;
    }

    private static boolean defaultText(Map<String, Object> map, String key, String value) {
        if (optionalText(map.get(key)).isBlank()) {
            map.put(key, value);
            return true;
        }
        return false;
    }

    private static boolean defaultBoolean(Map<String, Object> map, String key, boolean value) {
        if (map.get(key) == null) {
            map.put(key, value);
            return true;
        }
        return false;
    }

    private static boolean defaultNumber(Map<String, Object> map, String key, long value) {
        if (map.get(key) == null) {
            map.put(key, value);
            return true;
        }
        return false;
    }

    private static boolean put(Map<String, Object> map, String key, Object value) {
        if (Objects.equals(map.get(key), value)) {
            return false;
        }
        map.put(key, value);
        return true;
    }

    private static boolean putIfDifferent(Map<String, Object> map, String key, Object value) {
        return put(map, key, value);
    }

    private static boolean move(Map<String, Object> map, String source, String target) {
        if (source.equals(target) || !map.containsKey(source)) {
            return false;
        }
        if (map.containsKey(target)) {
            throw new IllegalArgumentException("FLOW_GRAPH_AUTHORED_PIN_INPUT_COLLISION");
        }
        map.put(target, map.remove(source));
        return true;
    }

    private static boolean lowerCase(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            return false;
        }
        String normalized = text.toLowerCase(Locale.ROOT);
        return put(map, key, normalized);
    }

    private static Map<String, Object> inputValues(Map<String, Object> node) {
        Object value = node.get("inputValues");
        if (value == null) {
            return new LinkedHashMap<>();
        }
        return object(value, "node inputValues");
    }

    private static String findNodeId(Map<String, Object> graph, Map<String, Object> node) {
        Map<String, Object> nodes = objectOrEmpty(graph.get("nodes"));
        return nodes.entrySet().stream().filter(entry -> entry.getValue() == node).map(Map.Entry::getKey).findFirst().orElse("");
    }

    private static boolean hasIncomingPin(Map<String, Object> graph, String nodeId, String pin) {
        return listOrEmpty(graph.get("connections")).stream().anyMatch(raw -> {
            Map<String, Object> connection = objectOrEmpty(raw);
            return nodeId.equals(optionalText(connection.get("targetNodeId"))) && pin.equals(optionalText(connection.get("targetPin")));
        });
    }

    private static boolean isLegacyBreakContinuation(String originalType, Map<String, Object> sourceNode, String pin) {
        String currentType = optionalText(sourceNode.get("type"));
        return ("break.loop".equals(originalType) || "break_loop".equals(originalType) || "break.loop".equals(currentType)) && ("flow".equals(pin) || "next".equals(pin));
    }

    private static boolean isExecutionInputPin(String pin) {
        return "flow".equals(pin) || "next".equals(pin);
    }

    private static boolean isLoopBodyOutput(String type, String pin) {
        if (!"flow".equals(pin) && !"loop".equals(pin)) {
            return false;
        }
        return Set.of("loop_while", "loop.count", "loop.for.each", "loop.for.each.player", "loop.for.each.entity").contains(type);
    }

    private static boolean passthrough(String pin) {
        return pin != null && pin.startsWith(PASSTHROUGH_OUTPUT_PREFIX);
    }

    private static Map<String, Object> object(JsonValue value, String field) {
        if (!(value instanceof JsonValue.JsonObject object)) {
            throw new IllegalArgumentException("Expected " + field + " object");
        }
        return deepObject(object.toJava());
    }

    private static Map<String, Object> object(Object value, String field) {
        if (!(value instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("Expected " + field + " object");
        }
        return deepObject(value);
    }

    private static Map<String, Object> objectOrEmpty(Object value) {
        return value == null ? new LinkedHashMap<>() : object(value, "object");
    }

    private static List<Object> listOrEmpty(Object value) {
        return value == null ? new ArrayList<>() : deepList(value);
    }

    private static List<Object> list(Object value, String field) {
        if (!(value instanceof List<?>)) {
            throw new IllegalArgumentException("Expected " + field + " array");
        }
        return deepList(value);
    }

    private static Map<String, Object> deepObject(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("Expected object");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException("Object key must be a string");
            }
            result.put(key, deepCopy(entry.getValue()));
        }
        return result;
    }

    private static List<Object> deepList(Object value) {
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException("Expected array");
        }
        return list.stream().map(LegacyFlowGraphSnapshotAdapter::deepCopy).collect(Collectors.toCollection(ArrayList::new));
    }

    private static Object deepCopy(Object value) {
        if (value instanceof Map<?, ?>) {
            return deepObject(value);
        }
        if (value instanceof List<?>) {
            return deepList(value);
        }
        return value;
    }

    private static String requiredText(Object value, String field) {
        String text = optionalText(value);
        if (text.isBlank()) {
            throw new IllegalArgumentException("Missing " + field);
        }
        return text;
    }

    private static String optionalText(Object value) {
        return value instanceof String text ? text : "";
    }

    private static int integer(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return fallback;
    }

    private static void applyAssetIdentity(Map<String, Object> graph, String resourceType) {
        String previousMutation = optionalText(graph.get("assetMutationId"));
        if (previousMutation.isBlank()) {
            previousMutation = optionalText(graph.get("resourceMutationId"));
        }
        long currentRevision = Math.max(integerLong(graph.get("assetRevision"), 0), integerLong(graph.get("resourceRevision"), 0));
        long revision;
        try {
            revision = Math.addExact(currentRevision, 1L);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("FLOW_GRAPH_REVISION_OVERFLOW", exception);
        }
        graph.put("resourceType", resourceType);
        graph.put("resourceRevision", revision);
        graph.put("resourceHash", "");
        String seed = "resync.flow-graph.asset-mutation\u0000" + optionalText(graph.get("id")) + '\u0000' + previousMutation + '\u0000' + revision + '\u0000' + sha256(JsonValue.fromJava(graph).canonicalBytes());
        String mutation = UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)).toString();
        graph.put("resourceMutationId", mutation);
        graph.put("assetFormatVersion", 3);
        graph.put("assetRevision", revision);
        graph.put("assetMutationId", mutation);
        graph.remove("assetHash");
        graph.put("assetHash", sha256(assetHashBytes(graph)));
    }

    private static long integerLong(Object value, long fallback) {
        if (!(value instanceof Number number)) {
            return fallback;
        }
        try {
            return number.longValue();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("FLOW_GRAPH_REVISION_INVALID", exception);
        }
    }

    private static long integerLongExact(Object value) {
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException("FLOW_GRAPH_REVISION_INVALID");
        }
        if (number instanceof BigDecimal decimal) {
            try {
                return decimal.longValueExact();
            } catch (ArithmeticException exception) {
                throw new IllegalArgumentException("FLOW_GRAPH_REVISION_INVALID", exception);
            }
        }
        return number.longValue();
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 Is Unavailable", exception);
        }
    }

    private static byte[] assetHashBytes(Map<String, Object> graph) {
        Map<String, Object> unsigned = deepObject(graph);
        unsigned.remove("assetHash");
        Object canonicalTree = JsonValue.fromJava(unsigned).toJava();
        return gsonLikeBytes(canonicalTree);
    }

    private static byte[] gsonLikeBytes(Object value) {
        StringBuilder output = new StringBuilder();
        writeGsonLike(value, output);
        return output.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void writeGsonLike(Object value, StringBuilder output) {
        if (value == null) {
            output.append("null");
        } else if (value instanceof String text) {
            writeGsonLikeString(text, output);
        } else if (value instanceof Boolean booleanValue) {
            output.append(booleanValue ? "true" : "false");
        } else if (value instanceof Number number) {
            output.append(number instanceof BigDecimal decimal ? decimal.toPlainString() : number);
        } else if (value instanceof Map<?, ?> map) {
            output.append('{');
            List<Map.Entry<?, ?>> entries = new ArrayList<>(map.entrySet());
            entries.sort(Comparator.comparing(entry -> String.valueOf(entry.getKey()), CanonicalJson::compareCodePoints));
            for (int index = 0; index < entries.size(); index++) {
                if (index > 0) {
                    output.append(',');
                }
                writeGsonLikeString(String.valueOf(entries.get(index).getKey()), output);
                output.append(':');
                writeGsonLike(entries.get(index).getValue(), output);
            }
            output.append('}');
        } else if (value instanceof List<?> list) {
            output.append('[');
            for (int index = 0; index < list.size(); index++) {
                if (index > 0) {
                    output.append(',');
                }
                writeGsonLike(list.get(index), output);
            }
            output.append(']');
        } else {
            throw new IllegalArgumentException("Unsupported asset JSON value: " + value.getClass().getName());
        }
    }

    private static void writeGsonLikeString(String value, StringBuilder output) {
        output.append('"');
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            switch (current) {
                case '"' -> output.append("\\\"");
                case '\\' -> output.append("\\\\");
                case '\b' -> output.append("\\b");
                case '\f' -> output.append("\\f");
                case '\n' -> output.append("\\n");
                case '\r' -> output.append("\\r");
                case '\t' -> output.append("\\t");
                case '<' -> output.append("\\u003c");
                case '>' -> output.append("\\u003e");
                case '&' -> output.append("\\u0026");
                case '=' -> output.append("\\u003d");
                case '\'' -> output.append("\\u0027");
                case '\u2028' -> output.append("\\u2028");
                case '\u2029' -> output.append("\\u2029");
                default -> {
                    if (current < 0x20) {
                        output.append(String.format(Locale.ROOT, "\\u%04x", (int) current));
                    } else {
                        output.append(current);
                    }
                }
            }
        }
        output.append('"');
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).strip();
        if (normalized.isBlank() || normalized.indexOf('\u0000') >= 0 || normalized.indexOf('\n') >= 0 || normalized.indexOf('\r') >= 0) {
            throw new IllegalArgumentException(field + " Is Invalid");
        }
        return normalized;
    }

    public record NodeSchema(int version, Map<String, Object> handlerDefaults, Map<String, String> inputTypes,
                             Map<String, Object> normalization, Map<String, Object> identity,
                             List<PinMapping> pinMappings) {
        public NodeSchema {
            if (version < 1) {
                throw new IllegalArgumentException("Node Schema Version Must Be Positive");
            }
            handlerDefaults = Map.copyOf(handlerDefaults == null ? Map.of() : handlerDefaults);
            inputTypes = Map.copyOf(inputTypes == null ? Map.of() : inputTypes);
            normalization = Map.copyOf(normalization == null ? Map.of() : normalization);
            identity = Map.copyOf(identity == null ? Map.of() : identity);
            pinMappings = List.copyOf(pinMappings == null ? List.of() : pinMappings);
            Set<String> sourcePins = new HashSet<>();
            Set<String> targetPins = new HashSet<>();
            for (PinMapping mapping : pinMappings) {
                if (mapping.targetSchemaVersion() != version
                    || !sourcePins.add(mapping.direction() + ':' + mapping.sourcePinId())
                    || !targetPins.add(mapping.direction() + ':' + mapping.targetPinId())) {
                    throw new IllegalArgumentException("Node Pin Mappings Are Incomplete Or Colliding");
                }
            }
        }

        public NodeSchema(int version, Map<String, Object> handlerDefaults, Map<String, String> inputTypes) {
            this(version, handlerDefaults, inputTypes, Map.of(), Map.of(), List.of());
        }

        public NodeSchema(int version, Map<String, Object> handlerDefaults, Map<String, String> inputTypes,
                          Map<String, Object> normalization) {
            this(version, handlerDefaults, inputTypes, normalization, Map.of(), List.of());
        }

        public NodeSchema(int version, Map<String, Object> handlerDefaults, Map<String, String> inputTypes,
                          Map<String, Object> normalization, Map<String, Object> identity) {
            this(version, handlerDefaults, inputTypes, normalization, identity, List.of());
        }
    }

    public record PinMapping(String sourcePinId, String targetPinId, String direction,
                             int sourceSchemaVersion, int targetSchemaVersion) {
        public PinMapping {
            if (sourcePinId == null || sourcePinId.length() > 128 || !PIN_ID.matcher(sourcePinId).matches()
                || targetPinId == null || targetPinId.length() > 128 || !PIN_ID.matcher(targetPinId).matches()) {
                throw new IllegalArgumentException("Pin Mapping ID Is Invalid");
            }
            if (!"input".equals(direction) && !"output".equals(direction)
                || sourceSchemaVersion < 1 || targetSchemaVersion <= sourceSchemaVersion) {
                throw new IllegalArgumentException("Pin Mapping Schema Is Invalid");
            }
        }
    }

    public record GraphPath(String prefix, String resourceType) {
        public GraphPath {
            prefix = MigrationPaths.requireRelative(prefix);
            resourceType = resourceType == null ? "" : resourceType.strip();
            if (!resourceType.isBlank() && !GRAPH_TYPES.contains(resourceType)) {
                throw new IllegalArgumentException("Unsupported graph resource type: " + resourceType);
            }
        }

        public boolean matches(String path) {
            if (path == null || !path.startsWith(prefix + "/") || !path.endsWith(".json")) {
                return false;
            }
            String suffix = path.substring(prefix.length() + 1, path.length() - 5);
            return !suffix.isBlank() && (resourceType.isBlank() || !suffix.equals("project"));
        }

        public String resourceId(String path) {
            if (!matches(path)) {
                throw new IllegalArgumentException("Path Is Not A Graph Path");
            }
            String fileName = path.substring(path.lastIndexOf('/') + 1, path.length() - 5);
            int typedSeparator = fileName.indexOf("__");
            return typedSeparator > 0 ? fileName.substring(typedSeparator + 2) : fileName;
        }

        public String typedResourceType(String path) {
            if (!matches(path)) {
                throw new IllegalArgumentException("Path Is Not A Graph Path");
            }
            String fileName = path.substring(path.lastIndexOf('/') + 1, path.length() - 5);
            int typedSeparator = fileName.indexOf("__");
            if (typedSeparator <= 0) {
                return "";
            }
            String type = fileName.substring(0, typedSeparator);
            return GRAPH_TYPES.contains(type) ? type : "";
        }
    }

    private record RawGraph(Map<String, Object> root, String id, String resourceType) {
    }

    private record GraphDiscovery(List<GraphEntry> entries,
                                  List<ImmutableSnapshotAdapter.Entry> metadataEntries,
                                  List<ImmutableSnapshotAdapter.Entry> invalidMetadataEntries) {
        private GraphDiscovery {
            entries = List.copyOf(entries);
            metadataEntries = List.copyOf(metadataEntries);
            invalidMetadataEntries = List.copyOf(invalidMetadataEntries);
        }
    }

    private record ValidatedGraph(ImmutableSnapshotAdapter.Entry entry, GraphPath graphPath, RawGraph graph) {
    }

    private record GraphEntry(ImmutableSnapshotAdapter.Entry entry, GraphPath path, GraphDeclaration declaration) {
    }

    private record GraphDeclaration(String resourceType, String id) {
    }

    private record ResolvedSource(String nodeId, String pin) {
    }

    private record PinIdentity(String nodeId, String direction, String pin) {
    }
}
