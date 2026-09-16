package restudio.resync.upgrade.command;

import restudio.resync.contract.canonical.CanonicalCodec;
import restudio.resync.contract.canonical.JsonValue;
import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.migration.MigrationPaths;
import restudio.resync.migration.ProductionSnapshotMetadataManifest;
import restudio.resync.migration.ProductionPersistenceOwners;
import restudio.resync.migration.QuarantineRecord;
import restudio.resync.migration.SnapshotMetadata;
import restudio.resync.upgrade.ImmutableSnapshotAdapter;
import restudio.resync.upgrade.LegacySnapshotWindow;
import restudio.resync.upgrade.adapter.OfflineUpgradeAdapter;
import restudio.resync.upgrade.adapter.OfflineUpgradeSnapshotAdapter;
import restudio.resync.upgrade.adapter.OfflineUpgradeSnapshotInput;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public final class LegacyCommandBindingSnapshotAdapter implements OfflineUpgradeSnapshotAdapter {
    public static final String ADAPTER_ID = "resync.command-binding-cross-document";
    public static final int ADAPTER_VERSION = 1;
    public static final int SOURCE_FORMAT_VERSION = LegacySnapshotWindow.SOURCE_FORMAT_VERSION;
    public static final String SOURCE_BUILD = LegacySnapshotWindow.SOURCE_BUILD;
    public static final String TRIGGER_SCHEMA = "trigger-registry-array-v1";
    public static final String TRIGGER_PATH = "triggers.json";
    public static final String TRIGGER_OWNER = ProductionPersistenceOwners.TRIGGERS;
    public static final String GRAPH_OWNER = ProductionPersistenceOwners.FLOW_ASSETS;
    public static final String GRAPH_PREFIX = "assets";

    private final String triggerPath;
    private final String triggerOwner;
    private final String graphOwner;
    private final String graphPrefix;

    public LegacyCommandBindingSnapshotAdapter() {
        this(TRIGGER_PATH, TRIGGER_OWNER, GRAPH_OWNER, GRAPH_PREFIX);
    }

    public LegacyCommandBindingSnapshotAdapter(String triggerPath, String triggerOwner,
                                               String graphOwner, String graphPrefix) {
        this.triggerPath = MigrationPaths.requireRelative(triggerPath);
        this.triggerOwner = requireText(triggerOwner, "triggerOwner");
        this.graphOwner = requireText(graphOwner, "graphOwner");
        this.graphPrefix = MigrationPaths.requireRelative(graphPrefix);
    }

    @Override
    public OfflineUpgradeAdapter.AdapterKey key() {
        return new OfflineUpgradeAdapter.AdapterKey(ADAPTER_ID, ADAPTER_VERSION);
    }

    @Override
    public String owner() {
        return triggerOwner;
    }

    @Override
    public boolean claims(OfflineUpgradeSnapshotInput input) {
        Objects.requireNonNull(input, "input");
        if (!supports(input.snapshot().metadata())) {
            return false;
        }
        if (!productionMetadataMatches(input)) {
            return false;
        }
        ImmutableSnapshotAdapter.Entry entry = entry(input, triggerPath);
        if (entry == null || !triggerOwner.equals(entry.owner())) {
            return false;
        }
        try {
            return CanonicalCodec.decodePermissive(read(input.root(), triggerPath)) instanceof JsonValue.JsonArray;
        } catch (RuntimeException | IOException exception) {
            return false;
        }
    }

    @Override
    public boolean owns(String relativePath, String sourceOwner) {
        if (triggerPath.equals(relativePath)) {
            return triggerOwner.equals(sourceOwner);
        }
        return isGraphPath(relativePath) && graphOwner.equals(sourceOwner);
    }

    @Override
    public SnapshotTransform transform(OfflineUpgradeSnapshotInput input) throws IOException {
        Objects.requireNonNull(input, "input");
        if (!supports(input.snapshot().metadata())) {
            throw new IOException("The snapshot is outside the fixed legacy command binding window");
        }
        requireProductionMetadata(input);
        ImmutableSnapshotAdapter.Entry triggerEntry = requireEntry(input, triggerPath);
        List<String> claimed = new ArrayList<>(List.of(triggerPath));
        Map<String, ImmutableSnapshotAdapter.Entry> graphEntries = commandGraphEntries(input.root(), input.snapshot());
        claimed.addAll(graphEntries.keySet());
        List<QuarantineRecord> quarantines = new ArrayList<>();
        if (!triggerOwner.equals(triggerEntry.owner())) {
            quarantines.add(quarantine(triggerEntry, "MIGRATION.COMMAND_BINDING_MISSING_OWNER",
                "The trigger document is not owned by the declared trigger participant.",
                "Repair the snapshot participant ownership before migrating command bindings.", List.of(triggerOwner, triggerEntry.owner())));
            return new SnapshotTransform(List.of(), claimed, quarantines);
        }
        ParsedTriggerDocument document;
        try {
            document = parseBindings(read(input.root(), triggerPath));
        } catch (RuntimeException exception) {
            quarantines.add(quarantine(triggerEntry, "MIGRATION.COMMAND_BINDING_UNSUPPORTED",
                "The legacy trigger document is not a supported trigger-binding array.",
                "Repair the trigger document or retain it in quarantine.", List.of(triggerPath)));
            validateGraphEntries(input, graphEntries, Set.of(), quarantines);
            return new SnapshotTransform(List.of(), claimed, quarantines);
        }

        List<LegacyBinding> bindings = document.bindings();
        Set<String> referencedFlows = bindings.stream()
            .map(binding -> binding.binding().flowId())
            .collect(Collectors.toUnmodifiableSet());
        Map<String, RawGraphDocument> validatedGraphs = validateGraphEntries(input, graphEntries, referencedFlows, quarantines);
        Map<String, List<LegacyBinding>> byFlow = new HashMap<>();
        Map<String, String> bindingFlows = new HashMap<>();
        Set<String> duplicateIds = new HashSet<>();
        Set<String> invalidFlows = new HashSet<>();
        for (LegacyBinding binding : bindings) {
            String bindingId = binding.binding().bindingId();
            String previousFlow = bindingFlows.putIfAbsent(bindingId, binding.binding().flowId());
            if (previousFlow != null) {
                invalidFlows.add(previousFlow);
                invalidFlows.add(binding.binding().flowId());
                if (duplicateIds.add(bindingId)) {
                    quarantines.add(quarantine(triggerEntry, "MIGRATION.COMMAND_BINDING_DUPLICATE",
                        "Multiple legacy command bindings have the same binding identity.",
                        "Keep one binding for each exact trigger identity.", List.of(bindingId)));
                }
                continue;
            }
            byFlow.computeIfAbsent(binding.binding().flowId(), ignored -> new ArrayList<>()).add(binding);
        }

        List<OfflineUpgradeSnapshotAdapter.FileTransform> files = new ArrayList<>();
        Set<String> migratedIds = new LinkedHashSet<>();
        for (Map.Entry<String, List<LegacyBinding>> flow : byFlow.entrySet().stream()
            .sorted(Map.Entry.comparingByKey(CanonicalJson::compareCodePoints)).toList()) {
            String flowId = flow.getKey();
            if (invalidFlows.contains(flowId)) {
                continue;
            }
            List<ImmutableSnapshotAdapter.Entry> candidates = validatedGraphs.keySet().stream()
                .map(path -> graphEntries.get(path))
                .filter(entry -> graphId(entry.relativePath()).equals(flowId))
                .sorted(Comparator.comparing(ImmutableSnapshotAdapter.Entry::relativePath, CanonicalJson::compareCodePoints))
                .toList();
            if (candidates.isEmpty()) {
                if (graphEntries.values().stream().anyMatch(entry -> graphId(entry.relativePath()).equals(flowId))) {
                    continue;
                }
                quarantines.add(quarantine(triggerEntry, "MIGRATION.COMMAND_BINDING_MISSING_OWNER",
                    "A legacy command binding references a command graph that is not present in the snapshot.",
                    "Restore the exact typed command graph before migrating its binding.", List.of(flowId)));
                continue;
            }
            if (candidates.size() > 1) {
                quarantines.add(quarantine(triggerEntry, "MIGRATION.COMMAND_BINDING_AMBIGUOUS",
                    "A legacy command binding resolves to more than one command graph document.",
                    "Retain one exact graph document for the command resource.", candidates.stream()
                        .map(ImmutableSnapshotAdapter.Entry::relativePath).toList()));
                continue;
            }
            ImmutableSnapshotAdapter.Entry graphEntry = candidates.getFirst();
            try {
                RawGraphDocument graph = validatedGraphs.get(graphEntry.relativePath());
                CommandBindingOutput output = new CommandBindingTransformer().transform(
                    new CommandBindingInput(graph, flow.getValue().stream().map(LegacyBinding::binding).toList()));
                if (output.changed()) {
                    long revision = AssetFileIdentity.nextRevision(graph.root());
                    String mutationId = AssetFileIdentity.nextMutationId(graph.root(), output.graph(), flowId, revision);
                    JsonValue.JsonObject identity = AssetFileIdentity.withResourceIdentity(
                        output.graph().root(), "command", revision, mutationId);
                    files.add(new FileTransform(graphEntry.relativePath(), graphEntry.relativePath(),
                        AssetFileIdentity.bytes(identity)));
                }
                migratedIds.addAll(output.appliedBindings().stream()
                    .map(CommandBindingOutput.AppliedBinding::bindingId)
                    .toList());
            } catch (RuntimeException exception) {
                quarantines.add(quarantine(graphEntry, code(exception),
                    "The command graph and its legacy bindings cannot be assigned deterministically.",
                    "Repair the graph identity, command nodes, or trigger binding collision and rerun the migration.",
                flow.getValue().stream().map(binding -> binding.binding().bindingId()).toList()));
            }
        }
        if (!migratedIds.isEmpty()) {
            Set<String> migrated = Set.copyOf(migratedIds);
            List<JsonValue> retained = new ArrayList<>();
            for (JsonValue row : document.rows()) {
                if (!(row instanceof JsonValue.JsonObject object)) {
                    retained.add(row);
                    continue;
                }
                String id = text(object.value("id"));
                String type = text(object.value("type"));
                if ("COMMAND".equalsIgnoreCase(type) && migrated.contains(id)) {
                    continue;
                }
                retained.add(object);
            }
            files.add(new FileTransform(triggerPath, triggerPath,
                CanonicalCodec.encode(JsonValue.array(retained))));
        }
        return new SnapshotTransform(files, claimed, quarantines);
    }

    private Map<String, RawGraphDocument> validateGraphEntries(OfflineUpgradeSnapshotInput input,
                                                                 Map<String, ImmutableSnapshotAdapter.Entry> graphEntries,
                                                                 Set<String> referencedFlows,
                                                                 List<QuarantineRecord> quarantines) {
        Map<String, RawGraphDocument> valid = new LinkedHashMap<>();
        for (ImmutableSnapshotAdapter.Entry entry : graphEntries.values().stream()
            .sorted(Comparator.comparing(ImmutableSnapshotAdapter.Entry::relativePath, CanonicalJson::compareCodePoints))
            .toList()) {
            String flowId = graphId(entry.relativePath());
            if (!graphOwner.equals(entry.owner())) {
                quarantines.add(quarantine(entry, "MIGRATION.COMMAND_BINDING_MISSING_OWNER",
                    "The command graph is not owned by the flow assets participant declared for command resources.",
                    "Repair the snapshot participant ownership before migrating the binding.", List.of(flowId, entry.owner())));
                continue;
            }
            try {
                RawGraphDocument graph = RawGraphDocument.parse(read(input.root(), entry.relativePath()));
                validateGraphIdentity(graph, entry.relativePath(), flowId);
                AssetFileIdentity.validate(graph.root(), "command");
                if (!referencedFlows.contains(flowId)) {
                    if (!AssetFileIdentity.isCurrent(graph.root(), "command")) {
                        throw new IllegalArgumentException("Asset identity is not current for an unreferenced command graph");
                    }
                    TypedCommandGraphSemanticValidator.validate(graph, flowId);
                }
                valid.put(entry.relativePath(), graph);
            } catch (IOException | RuntimeException exception) {
                quarantines.add(quarantine(entry, code(exception),
                    "The command graph is not a valid current asset or a referenced legacy migration target.",
                    "Repair the command graph identity and rerun the migration.", List.of(flowId)));
            }
        }
        return valid;
    }

    public static boolean supports(SnapshotMetadata metadata) {
        return metadata != null
            && metadata.formatVersion() == SOURCE_FORMAT_VERSION
            && SOURCE_BUILD.equals(metadata.build());
    }

    private boolean productionMetadataMatches(OfflineUpgradeSnapshotInput input) {
        try {
            ProductionSnapshotMetadataManifest.Values manifest = ProductionSnapshotMetadataManifest.read(input.provenanceRoot());
            return manifest.metadata().equals(input.snapshot().metadata())
                && manifest.manifestHash().equals(input.snapshot().manifestHash());
        } catch (IOException | RuntimeException exception) {
            return false;
        }
    }

    private void requireProductionMetadata(OfflineUpgradeSnapshotInput input) throws IOException {
        ProductionSnapshotMetadataManifest.Values manifest = ProductionSnapshotMetadataManifest.read(input.provenanceRoot());
        if (!manifest.metadata().equals(input.snapshot().metadata())
            || !manifest.manifestHash().equals(input.snapshot().manifestHash())) {
            throw new IOException("Production Snapshot Metadata Does Not Match The Bound Snapshot");
        }
    }

    private Map<String, ImmutableSnapshotAdapter.Entry> commandGraphEntries(Path root, ImmutableSnapshotAdapter.View snapshot) {
        Map<String, ImmutableSnapshotAdapter.Entry> entries = new LinkedHashMap<>();
        for (ImmutableSnapshotAdapter.Entry entry : snapshot.entries()) {
            if (!isGraphPath(entry.relativePath())) {
                continue;
            }
            try {
                JsonValue value = CanonicalCodec.decodePermissive(Files.readAllBytes(
                    MigrationPaths.resolveInside(root, entry.relativePath())));
                if (value instanceof JsonValue.JsonObject object && isCommandGraph(object)) {
                    entries.put(entry.relativePath(), entry);
                }
            } catch (IOException | RuntimeException ignored) {
            }
        }
        return entries;
    }

    private ParsedTriggerDocument parseBindings(byte[] bytes) {
        JsonValue value = CanonicalCodec.decodePermissive(bytes);
        if (!(value instanceof JsonValue.JsonArray array)) {
            throw new IllegalArgumentException("Legacy trigger bindings must be an array");
        }
        List<LegacyBinding> result = new ArrayList<>();
        for (JsonValue item : array.values()) {
            if (!(item instanceof JsonValue.JsonObject object)) {
                throw new IllegalArgumentException("Legacy trigger binding must be an object");
            }
            JsonValue typeValue = object.value("type");
            if (!(typeValue instanceof JsonValue.JsonString type)) {
                continue;
            }
            if (!"COMMAND".equals(type.value())) {
                continue;
            }
            String id = requiredString(object.value("id"), "binding id");
            String flowId = requiredString(object.value("flowId"), "binding flow id");
            JsonValue contextValue = object.value("context");
            if (!(contextValue instanceof JsonValue.JsonString context)) {
                throw new IllegalArgumentException("Command binding context must be a string");
            }
            result.add(new LegacyBinding(new CommandBinding(id, flowId, context.value()), object));
        }
        result.sort(Comparator.comparing(binding -> binding.binding().bindingId(), CanonicalJson::compareCodePoints));
        return new ParsedTriggerDocument(array.values(), result);
    }

    private void validateGraphIdentity(RawGraphDocument graph, String path, String flowId) {
        String id = requiredString(graph.root().value("id"), "graph id");
        String resourceType = requiredString(graph.root().value("resourceType"), "graph resource type");
        if (!flowId.equals(id) || !"command".equals(resourceType)) {
            throw new IllegalArgumentException("Command graph identity does not match its typed source path");
        }
        if (!flowId.equals(graphId(path))) {
            throw new IllegalArgumentException("Command graph identity does not match its file name");
        }
    }

    private String graphId(String path) {
        String fileName = path.substring(path.lastIndexOf('/') + 1);
        String id = fileName.substring(0, fileName.length() - ".json".length());
        int typedSeparator = id.indexOf("__");
        return typedSeparator > 0 ? id.substring(typedSeparator + 2) : id;
    }

    private boolean isGraphPath(String path) {
        if (path == null || !path.startsWith(graphPrefix + "/") || !path.endsWith(".json")) {
            return false;
        }
        String suffix = path.substring(graphPrefix.length() + 1, path.length() - ".json".length());
        return !suffix.isBlank() && !suffix.equals("project");
    }

    private boolean isCommandGraph(JsonValue.JsonObject object) {
        if ("command".equals(text(object.value("resourceType")))) {
            return true;
        }
        JsonValue nodes = object.value("nodes");
        if (!(nodes instanceof JsonValue.JsonObject nodeObject)) {
            return false;
        }
        return nodeObject.fields().values().stream()
            .filter(value -> value instanceof JsonValue.JsonObject)
            .map(value -> (JsonValue.JsonObject) value)
            .map(value -> text(value.value("type")))
            .anyMatch(type -> type.equals("event.resync.command") || type.equals("event:resync_command"));
    }

    private static ImmutableSnapshotAdapter.Entry entry(OfflineUpgradeSnapshotInput input, String path) {
        return input.snapshot().entries().stream().filter(value -> path.equals(value.relativePath())).findFirst().orElse(null);
    }

    private static ImmutableSnapshotAdapter.Entry requireEntry(OfflineUpgradeSnapshotInput input, String path) throws IOException {
        ImmutableSnapshotAdapter.Entry entry = entry(input, path);
        if (entry == null) {
            throw new IOException("Required Snapshot File Is Missing: " + path);
        }
        return entry;
    }

    private static byte[] read(Path root, String path) throws IOException {
        return Files.readAllBytes(MigrationPaths.resolveInside(root, path));
    }

    private static String requiredString(JsonValue value, String field) {
        if (!(value instanceof JsonValue.JsonString string) || string.value().trim().isEmpty()) {
            throw new IllegalArgumentException("Required " + field + " is missing");
        }
        return string.value();
    }

    private static String text(JsonValue value) {
        return value instanceof JsonValue.JsonString string ? string.value().trim() : "";
    }

    private static String code(Exception exception) {
        String message = exception.getMessage();
        if (message == null) {
            return "MIGRATION.COMMAND_BINDING_UNSUPPORTED";
        }
        if (message.startsWith("COMMAND_BINDING_AMBIGUOUS")) {
            return "MIGRATION.COMMAND_BINDING_AMBIGUOUS";
        }
        if (message.startsWith("COMMAND_BINDING_CONFLICT")) {
            return "MIGRATION.COMMAND_BINDING_CONFLICT";
        }
        if (message.startsWith("COMMAND_GRAPH_AMBIGUOUS")) {
            return "MIGRATION.COMMAND_GRAPH_AMBIGUOUS";
        }
        if (message.startsWith("COMMAND_GRAPH_INVALID")) {
            return "MIGRATION.COMMAND_GRAPH_INVALID";
        }
        if (message.startsWith("Asset ") || message.startsWith("Resource and asset")) {
            return "MIGRATION.ASSET_IDENTITY_INVALID";
        }
        return "MIGRATION.COMMAND_BINDING_UNSUPPORTED";
    }

    private static QuarantineRecord quarantine(ImmutableSnapshotAdapter.Entry entry, String code,
                                               String reason, String action, List<String> references) {
        String recordId = "command-binding-" + CanonicalJson.sha256("resync.command-binding.quarantine",
            List.of(code, entry.relativePath(), entry.sha256(), reason, references == null ? List.of() : references)).substring(0, 24);
        return new QuarantineRecord(recordId, code, entry.relativePath(), reason, references, action, entry.sha256());
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isBlank() || normalized.indexOf('\u0000') >= 0
            || normalized.indexOf('\n') >= 0 || normalized.indexOf('\r') >= 0) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return normalized;
    }

    private record LegacyBinding(CommandBinding binding, JsonValue.JsonObject source) {
    }

    private record ParsedTriggerDocument(List<JsonValue> rows, List<LegacyBinding> bindings) {
        private ParsedTriggerDocument {
            rows = List.copyOf(rows);
            bindings = List.copyOf(bindings);
        }
    }
}
