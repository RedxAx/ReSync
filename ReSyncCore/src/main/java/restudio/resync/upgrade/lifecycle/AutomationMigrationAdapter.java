package restudio.resync.upgrade.lifecycle;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.migration.QuarantineRecord;
import restudio.resync.upgrade.TypedLifecycleMigrationAdapter;

public final class AutomationMigrationAdapter implements TypedLifecycleMigrationAdapter {
    public static final String ID = "resync.lifecycle.automation-v1";
    public static final String DYNAMIC_REFERENCE_CODE = "MIGRATION.AUTOMATION_DYNAMIC_REFERENCE";
    public static final String FAMILY_QUARANTINED_CODE = "MIGRATION.AUTOMATION_FAMILY_QUARANTINED";
    public static final String INVALID_STATE_CODE = "MIGRATION.AUTOMATION_STATE_INVALID";
    public static final String RESOURCE_CONFLICT_CODE = "MIGRATION.AUTOMATION_RESOURCE_CONFLICT";
    public static final String VARIABLE_TYPE_CONFLICT_CODE = "MIGRATION.AUTOMATION_VARIABLE_TYPE_CONFLICT";
    private static final String FLOW_PREFIX = "assets/Blueprints/Flows/";
    private static final String FUNCTION_PREFIX = "assets/Blueprints/Functions/";
    private static final String COMMAND_PREFIX = "assets/Blueprints/Commands/";
    private static final String VARIABLE_PREFIX = "assets/Automation/Variables/";
    private static final String SCHEDULE_PREFIX = "assets/Automation/Schedules/";
    private static final Set<String> COMMAND_BINDING_FIELDS = Set.of("commandLabel", "structured", "commandPaths");
    private static final Set<String> RUNTIME_PATHS = Set.of(
        "automation/runtime-state.json",
        "runtime/automation-tasks.json",
        "flow-variables.json");
    private static final Set<String> SCHEDULE_TYPES = Set.of(
        "schedule.schedule",
        "schedule.schedule_repeating",
        "schedule.cron",
        "schedule.at.time",
        "schedule.interval");

    @Override
    public String adapterId() {
        return ID;
    }

    @Override
    public Adaptation adapt(Input input) throws IOException {
        Adaptation commands = new TriggerCommandMigrationAdapter().adapt(input);
        Set<String> commandGraphs = commands.claims().stream().map(Claim::relativePath)
            .filter(path -> !path.equals("triggers.json")).collect(Collectors.toUnmodifiableSet());
        Adaptation automation = adaptAutomation(input, commandGraphs);
        List<Claim> claims = mergedClaims(automation.claims(), commands.claims());
        List<QuarantineRecord> quarantine = new ArrayList<>(automation.quarantineRecords());
        quarantine.addAll(commands.quarantineRecords());
        if (!quarantine.isEmpty()) {
            addFamilyQuarantine(input, claims, quarantine);
        }
        if (!claimsMatch(input, commands.claims()) || !quarantine.isEmpty()) {
            return new Adaptation(claims, List.of(), quarantine);
        }
        try {
            return new Adaptation(claims, mergedChanges(input, automation.changes(), commands.changes()), List.of());
        } catch (IllegalArgumentException exception) {
            SourceFile source = commands.changes().stream().map(Change::sourcePath).filter(path -> !path.isBlank())
                .map(input::file).flatMap(Optional::stream).findFirst()
                .orElseGet(() -> input.files().stream().filter(file -> graphPath(file.relativePath())).findFirst().orElseThrow());
            quarantine.add(quarantine(source, INVALID_STATE_CODE, source.relativePath(),
                "The Command binding and automation graph changes cannot be composed exactly: " + exception.getMessage(),
                List.of(source.relativePath())));
            return new Adaptation(claims, List.of(), quarantine);
        }
    }

    private static Adaptation adaptAutomation(Input input, Set<String> commandGraphs) throws IOException {
        Objects.requireNonNull(input, "input");
        List<Claim> claims = new ArrayList<>();
        List<Change> changes = new ArrayList<>();
        List<QuarantineRecord> quarantine = new ArrayList<>();
        List<SourceFile> familySources = new ArrayList<>();
        Map<String, List<SourceFile>> existingDefinitions = new LinkedHashMap<>();
        List<GraphSource> graphs = new ArrayList<>();
        for (SourceFile source : input.files()) {
            if (!owned(source.relativePath()) && !commandGraphs.contains(source.relativePath())) {
                continue;
            }
            familySources.add(source);
            claims.add(new Claim(source.relativePath(), source.owner()));
            if (definitionPath(source.relativePath())) {
                existingDefinitions.computeIfAbsent(normalizedPath(source.relativePath()), ignored -> new ArrayList<>()).add(source);
            } else if (graphPath(source.relativePath()) || commandGraphs.contains(source.relativePath())) {
                graphs.add(readGraph(input, source, commandGraphs.contains(source.relativePath()) ? "command"
                    : expectedResourceType(source.relativePath()), quarantine));
            } else if (source.relativePath().equals("automation/runtime-state.json")) {
                adaptRuntimeState(input, source, changes, quarantine);
            }
        }
        Map<VariableIdentity, Set<String>> variableTypes = indexVariableTypes(graphs);
        Map<String, PlannedDefinition> plannedDefinitions = new LinkedHashMap<>();
        for (GraphSource graph : graphs) {
            if (graph.document() == null) {
                continue;
            }
            GraphAdaptation adaptation = adaptGraph(graph, variableTypes);
            quarantine.addAll(adaptation.quarantine());
            if (!adaptation.quarantine().isEmpty() || !adaptation.changed()) {
                continue;
            }
            boolean conflict = false;
            for (PlannedDefinition definition : adaptation.definitions()) {
                PlannedDefinition planned = plannedDefinitions.get(definition.path());
                if (planned != null && !Arrays.equals(planned.bytes(), definition.bytes())) {
                    quarantine.add(quarantine(graph.source(), RESOURCE_CONFLICT_CODE, definition.path(),
                        "Multiple automation definitions resolve to the same typed resource with different content.", List.of(definition.path())));
                    conflict = true;
                    break;
                }
                List<SourceFile> existing = existingDefinitions.getOrDefault(normalizedPath(definition.path()), List.of());
                if (existing.size() > 1 || existing.stream().anyMatch(source -> !source.relativePath().equals(definition.path()))) {
                    quarantine.add(quarantine(graph.source(), RESOURCE_CONFLICT_CODE, definition.path(),
                        "An existing automation definition uses a case-aliased or ambiguous path for this typed resource.",
                        existing.stream().map(SourceFile::relativePath).sorted().toList()));
                    conflict = true;
                    break;
                }
                if (!existing.isEmpty() && !canonicalEquivalent(input.read(existing.getFirst()), definition.bytes())) {
                    quarantine.add(quarantine(graph.source(), RESOURCE_CONFLICT_CODE, definition.path(),
                        "An existing automation definition has different content for this typed resource.",
                        List.of(graph.source().relativePath(), existing.getFirst().relativePath())));
                    conflict = true;
                    break;
                }
            }
            if (conflict) {
                continue;
            }
            changes.add(new Change("automation-graph", graph.source().relativePath(), graph.source().relativePath(), adaptation.graphBytes()));
            for (PlannedDefinition definition : adaptation.definitions()) {
                List<SourceFile> existing = existingDefinitions.getOrDefault(normalizedPath(definition.path()), List.of());
                if (existing.isEmpty()) {
                    plannedDefinitions.putIfAbsent(definition.path(), definition);
                }
            }
        }
        plannedDefinitions.values().forEach(definition -> changes.add(
            new Change("generate", "", definition.path(), definition.bytes())));
        if (!quarantine.isEmpty()) {
            List<String> failedSources = quarantine.stream().map(QuarantineRecord::sourceLocation).distinct().sorted().toList();
            Set<String> quarantinedSources = new LinkedHashSet<>(failedSources);
            for (SourceFile source : familySources) {
                if (quarantinedSources.add(source.relativePath())) {
                    quarantine.add(quarantine(source, FAMILY_QUARANTINED_CODE, source.relativePath(),
                        "This automation source is retained with the invalid automation family so no graph, definition, or runtime state can migrate independently.",
                        failedSources));
                }
            }
            return new Adaptation(claims, List.of(), quarantine);
        }
        return new Adaptation(claims, changes, List.of());
    }

    private static List<Claim> mergedClaims(List<Claim> automation, List<Claim> commands) {
        Map<String, Claim> claims = new LinkedHashMap<>();
        automation.forEach(claim -> claims.put(claim.relativePath(), claim));
        commands.forEach(claim -> claims.put(claim.relativePath(), claim));
        return List.copyOf(claims.values());
    }

    private static void addFamilyQuarantine(Input input, List<Claim> claims, List<QuarantineRecord> quarantine) {
        List<String> failedSources = quarantine.stream().map(QuarantineRecord::sourceLocation).distinct().sorted().toList();
        Set<String> reported = quarantine.stream().map(QuarantineRecord::sourceLocation).collect(Collectors.toCollection(LinkedHashSet::new));
        for (Claim claim : claims) {
            input.file(claim.relativePath()).filter(source -> reported.add(source.relativePath())).ifPresent(source ->
                quarantine.add(quarantine(source, FAMILY_QUARANTINED_CODE, source.relativePath(),
                    "This source is retained with the invalid automation and command family so no related state can migrate independently.",
                    failedSources)));
        }
    }

    private static boolean claimsMatch(Input input, List<Claim> claims) {
        return claims.stream().allMatch(claim -> input.file(claim.relativePath())
            .map(source -> source.owner().equals(claim.owner())).orElse(false));
    }

    private static List<Change> mergedChanges(Input input, List<Change> automation, List<Change> commands) throws IOException {
        Map<String, Change> changes = new LinkedHashMap<>();
        for (Change change : automation) {
            changes.put(change.targetPath(), change);
        }
        for (Change command : commands) {
            Change existing = changes.get(command.targetPath());
            if (existing == null) {
                changes.put(command.targetPath(), command);
                continue;
            }
            if (!existing.sourcePath().equals(command.sourcePath()) || !"migrate-command-binding".equals(command.kind())) {
                throw new IllegalArgumentException("two changes target " + command.targetPath());
            }
            SourceFile source = input.file(command.sourcePath()).orElseThrow();
            Map<String, Object> original = object(CanonicalJson.parse(input.read(source)), "commandGraph");
            if (!"command".equals(optionalText(original.get("resourceType")))) {
                throw new IllegalArgumentException("composed graph is not a Command resource");
            }
            Map<String, Object> migrated = object(CanonicalJson.parse(existing.targetBytes()), "automationCommandGraph");
            Map<String, Object> bound = object(CanonicalJson.parse(command.targetBytes()), "boundCommandGraph");
            Set<String> keys = new LinkedHashSet<>(original.keySet());
            keys.addAll(bound.keySet());
            for (String key : keys) {
                Object before = original.get(key);
                Object after = bound.get(key);
                if (Objects.equals(before, after)) {
                    continue;
                }
                if (!COMMAND_BINDING_FIELDS.contains(key) || !bound.containsKey(key)) {
                    throw new IllegalArgumentException("Command binding changed unsupported field " + key);
                }
                Object current = migrated.get(key);
                if (!Objects.equals(current, before) && !Objects.equals(current, after)) {
                    throw new IllegalArgumentException("Command binding conflicts at field " + key);
                }
                migrated.put(key, copy(after, "commandBinding." + key));
            }
            changes.put(command.targetPath(), new Change("migrate-command-automation", command.sourcePath(), command.targetPath(),
                CanonicalJson.canonicalBytes(migrated)));
        }
        return List.copyOf(changes.values());
    }

    private static GraphSource readGraph(Input input, SourceFile source, String resourceType,
                                         List<QuarantineRecord> quarantine) throws IOException {
        try {
            return new GraphSource(source, resourceType, object(CanonicalJson.parse(input.read(source)), "graph"));
        } catch (IllegalArgumentException exception) {
            quarantine.add(quarantine(source, INVALID_STATE_CODE, source.relativePath(),
                "The automation graph is not valid canonical JSON: " + exception.getMessage(), List.of(source.relativePath())));
            return new GraphSource(source, resourceType, null);
        }
    }

    private static void adaptRuntimeState(Input input, SourceFile source, List<Change> changes, List<QuarantineRecord> quarantine) throws IOException {
        try {
            byte[] sourceBytes = input.read(source);
            Map<String, Object> state = object(CanonicalJson.parse(sourceBytes), "automationRuntimeState");
            nonNegativeInteger(state.get("revision"), "automationRuntimeState.revision");
            text(state.get("mutationId"), "automationRuntimeState.mutationId");
            byte[] targetBytes = CanonicalJson.canonicalBytes(state);
            if (!Arrays.equals(sourceBytes, targetBytes)) {
                changes.add(new Change("automation-runtime-state", source.relativePath(), source.relativePath(), targetBytes));
            }
        } catch (IllegalArgumentException exception) {
            quarantine.add(quarantine(source, INVALID_STATE_CODE, source.relativePath(),
                "Automation runtime state does not preserve an exact revision and mutation identity: " + exception.getMessage(), List.of(source.relativePath())));
        }
    }

    private static GraphAdaptation adaptGraph(GraphSource graph, Map<VariableIdentity, Set<String>> variableTypes) {
        Map<String, Object> document = copyObject(graph.document(), "graph");
        Map<String, Object> nodes;
        List<Object> connections;
        try {
            String resourceType = text(document.get("resourceType"), "graph.resourceType");
            if (!resourceType.equals(graph.resourceType())) {
                throw new IllegalArgumentException("graph.resourceType does not match its typed path");
            }
            String graphId = text(document.get("id"), "graph.id");
            if (!graphId.equals(fileId(graph.source().relativePath()))) {
                throw new IllegalArgumentException("graph.id does not match its typed path");
            }
            nonNegativeInteger(document.get("resourceRevision"), "graph.resourceRevision");
            text(document.get("mutationId"), "graph.mutationId");
            nodes = object(document.get("nodes"), "graph.nodes");
            connections = array(document.get("connections"), "graph.connections");
        } catch (IllegalArgumentException exception) {
            boolean hasLegacy = containsLegacyAutomation(document.get("nodes"));
            if (!hasLegacy) {
                return GraphAdaptation.unchanged();
            }
            return GraphAdaptation.quarantined(quarantine(graph.source(), INVALID_STATE_CODE, graph.source().relativePath(),
                "The graph cannot preserve its exact typed identity, revision, and mutation: " + exception.getMessage(), List.of(graph.source().relativePath())));
        }
        List<PlannedDefinition> definitions = new ArrayList<>();
        List<QuarantineRecord> quarantine = new ArrayList<>();
        QuarantineRecord variableTypeConflict = variableTypeConflict(graph, nodes, connections, variableTypes);
        if (variableTypeConflict != null) {
            return GraphAdaptation.quarantined(variableTypeConflict);
        }
        QuarantineRecord scheduleOutputConflict;
        try {
            scheduleOutputConflict = scheduleOutputConflict(graph, nodes, connections);
        } catch (IllegalArgumentException exception) {
            return GraphAdaptation.quarantined(quarantine(graph.source(), INVALID_STATE_CODE,
                graph.source().relativePath(), "The legacy Schedule output connections cannot be inspected without losing data: "
                    + exception.getMessage(), List.of(graph.source().relativePath())));
        }
        if (scheduleOutputConflict != null) {
            return GraphAdaptation.quarantined(scheduleOutputConflict);
        }
        boolean changed = false;
        for (String nodeId : nodes.keySet().stream().sorted().toList()) {
            NodeAdaptation adaptation;
            String sourceType;
            try {
                Map<String, Object> node = object(nodes.get(nodeId), "graph.nodes." + nodeId);
                sourceType = optionalText(node.get("type"));
                if (sourceType.equals("variable.access")) {
                    adaptation = adaptVariable(graph, document, nodeId, node, connections, variableTypes);
                } else if (SCHEDULE_TYPES.contains(sourceType)) {
                    adaptation = adaptSchedule(graph, document, nodeId, node, connections);
                } else {
                    continue;
                }
            } catch (IllegalArgumentException exception) {
                quarantine.add(quarantine(graph.source(), INVALID_STATE_CODE, graph.source().relativePath() + "#node/" + nodeId,
                    "The automation node cannot be transformed without losing data: " + exception.getMessage(), List.of("node:" + nodeId)));
                continue;
            }
            if (adaptation.quarantine() != null) {
                quarantine.add(adaptation.quarantine());
                continue;
            }
            nodes.put(nodeId, adaptation.node());
            if (adaptation.definition() != null) {
                definitions.add(adaptation.definition());
            }
            changed = true;
        }
        for (String nodeId : nodes.keySet().stream().sorted().toList()) {
            NodeAdaptation adaptation;
            try {
                Map<String, Object> node = object(nodes.get(nodeId), "graph.nodes." + nodeId);
                if (!"schedule.cancel_task".equals(optionalText(node.get("type")))) {
                    continue;
                }
                adaptation = adaptPairedCancel(graph, nodeId, node, nodes, connections);
            } catch (IllegalArgumentException exception) {
                quarantine.add(quarantine(graph.source(), INVALID_STATE_CODE, graph.source().relativePath() + "#node/" + nodeId,
                    "The paired cancel node cannot be transformed without losing data: " + exception.getMessage(), List.of("node:" + nodeId)));
                continue;
            }
            if (adaptation == null) {
                continue;
            }
            if (adaptation.quarantine() != null) {
                quarantine.add(adaptation.quarantine());
                continue;
            }
            nodes.put(nodeId, adaptation.node());
            changed = true;
        }
        if (!quarantine.isEmpty()) {
            return new GraphAdaptation(false, null, List.of(), quarantine);
        }
        Map<String, PlannedDefinition> definitionsByPath = new LinkedHashMap<>();
        for (PlannedDefinition definition : definitions) {
            PlannedDefinition existing = definitionsByPath.putIfAbsent(definition.path(), definition);
            if (existing != null && !Arrays.equals(existing.bytes(), definition.bytes())) {
                return GraphAdaptation.quarantined(quarantine(graph.source(), RESOURCE_CONFLICT_CODE, definition.path(),
                    "Multiple nodes in this graph normalize to the same generated automation definition with different content.",
                    List.of(existing.path(), definition.path())));
            }
        }
        if (!changed) {
            return GraphAdaptation.unchanged();
        }
        document.put("connections", connections);
        document.put("nodes", nodes);
        return new GraphAdaptation(true, CanonicalJson.canonicalBytes(document), List.copyOf(definitionsByPath.values()), List.of());
    }

    private static NodeAdaptation adaptVariable(GraphSource graph, Map<String, Object> document, String nodeId, Map<String, Object> node,
                                                  List<Object> connections, Map<VariableIdentity, Set<String>> variableTypes) {
        validateSourceVersion(node);
        Map<String, Object> values = nodeValues(node);
        if (wired(connections, nodeId, Set.of("mode", "name", "scope", "persist"))) {
            return NodeAdaptation.quarantined(dynamic(graph, nodeId, "Variable identity or operation mode is supplied by a graph connection."));
        }
        String action = optionalText(values.getOrDefault("mode", "get")).toLowerCase(Locale.ROOT);
        String name = optionalText(values.get("name")).trim();
        if (action.equals("list") || name.isBlank()) {
            return NodeAdaptation.quarantined(dynamic(graph, nodeId, "Variable identity cannot be resolved to one static definition."));
        }
        String scope = scope(values.get("scope"));
        boolean persistent = bool(values.get("persist"));
        String valueType = inferVariableType(action, values.get("value"));
        VariableIdentity identity = new VariableIdentity(name.toLowerCase(Locale.ROOT), scope, persistent);
        Set<String> inferred = variableTypes.getOrDefault(identity, Set.of());
        if (inferred.size() > 1) {
            return NodeAdaptation.quarantined(quarantine(graph.source(), VARIABLE_TYPE_CONFLICT_CODE,
                graph.source().relativePath() + "#variable/" + name,
                "The same legacy variable identity has conflicting explicit value types: " + inferred.stream().sorted().toList(),
                List.of("variable:" + name, "scope:" + scope, "persistent:" + persistent)));
        }
        if (valueType.equals("any")) {
            if (inferred.size() != 1) {
                return NodeAdaptation.quarantined(dynamic(graph, nodeId, "Variable value type is missing or ambiguous."));
            }
            valueType = inferred.iterator().next();
        }
        String definitionId = definitionId("variable", name, scope, persistent ? "persistent" : "runtime", valueType);
        Map<String, Object> definition = definition("variable_definition", definitionId);
        definition.put("description", "Migrated Variable");
        definition.put("name", name);
        definition.put("persistent", persistent);
        definition.put("scope", scope);
        definition.put("valueType", valueType);
        Map<String, Object> replacement = new LinkedHashMap<>(values);
        Object owner = replacement.remove("player");
        replacement.keySet().removeAll(Set.of("mode", "scope", "persist", "name"));
        replacement.put("action", title(action));
        replacement.put("variable", resourceReference("variable_definition", definitionId));
        if (owner != null) {
            replacement.put("owner", owner);
        }
        Map<String, Object> migrated = copyObject(node, "node");
        migrated.put("type", "automation.variable");
        setTargetVersion(migrated, 2);
        migrated.put(valueField(node), replacement);
        migrated.put("handlerConfig", migratedHandlerConfig(node, "variable_access", "variable_access"));
        renameTargetPin(connections, nodeId, "player", "owner");
        renameSourcePin(connections, nodeId, Map.of("flow", "output_flow", "value", "output_value", "variable", "output_variable"));
        String path = VARIABLE_PREFIX + definitionId + ".json";
        return NodeAdaptation.migrated(migrated, new PlannedDefinition(path, CanonicalJson.canonicalBytes(definition)));
    }

    private static NodeAdaptation adaptSchedule(GraphSource graph, Map<String, Object> document, String nodeId, Map<String, Object> node,
                                                  List<Object> connections) {
        String type = optionalText(node.get("type"));
        validateSourceVersion(node);
        if (wired(connections, nodeId, scheduleIdentityPins(type))) {
            return NodeAdaptation.quarantined(dynamic(graph, nodeId, "Schedule identity or timing is supplied by a graph connection."));
        }
        Map<String, Object> values = nodeValues(node);
        ScheduleTarget target;
        try {
            target = scheduleTarget(values.get("flow_id"));
        } catch (IllegalArgumentException exception) {
            return NodeAdaptation.quarantined(dynamic(graph, nodeId, "Schedule target cannot be preserved exactly: " + exception.getMessage()));
        }
        if (target.id().isBlank()) {
            return NodeAdaptation.quarantined(dynamic(graph, nodeId, "Schedule target cannot be resolved to one static typed resource."));
        }
        String graphId = text(document.get("id"), "graph.id");
        String definitionId = definitionId("schedule", graphId, nodeId);
        Map<String, Object> definition;
        try {
            definition = scheduleDefinition(definitionId, type, target, values);
        } catch (IllegalArgumentException exception) {
            return NodeAdaptation.quarantined(dynamic(graph, nodeId, "Schedule timing cannot be preserved exactly: " + exception.getMessage()));
        }
        Map<String, Object> replacement = new LinkedHashMap<>(values);
        replacement.keySet().removeAll(scheduleIdentityPins(type));
        replacement.put("schedule", resourceReference("schedule_definition", definitionId));
        Map<String, Object> migrated = copyObject(node, "node");
        migrated.put("type", "automation.schedule");
        setTargetVersion(migrated, 2);
        migrated.put(valueField(node), replacement);
        migrated.put("handlerConfig", migratedHandlerConfig(node, legacyScheduleOperation(type), "schedule_definition"));
        renameSourcePin(connections, nodeId, Map.of(
            "flow", "scheduled",
            "output_flow", "scheduled",
            "scheduled", "success",
            "task_id", "task"));
        String path = SCHEDULE_PREFIX + definitionId + ".json";
        return NodeAdaptation.migrated(migrated, new PlannedDefinition(path, CanonicalJson.canonicalBytes(definition)));
    }

    private static NodeAdaptation adaptPairedCancel(GraphSource graph, String nodeId, Map<String, Object> node,
                                                      Map<String, Object> nodes, List<Object> connections) {
        List<Map<String, Object>> taskInputs = incoming(connections, nodeId, "task_id");
        List<Map<String, Object>> migratedInputs = taskInputs.stream()
            .filter(connection -> "task".equals(sourcePin(connection)))
            .filter(connection -> automationScheduleSource(nodes, connection))
            .toList();
        if (migratedInputs.isEmpty()) {
            return null;
        }
        validateSourceVersion(node);
        if (taskInputs.size() != 1 || migratedInputs.size() != 1) {
            return NodeAdaptation.quarantined(dynamic(graph, nodeId,
                "The paired cancel task input does not resolve to one migrated Schedule output."));
        }
        String scheduleNodeId = sourceNode(migratedInputs.getFirst());
        Map<String, Object> scheduleNode = object(nodes.get(scheduleNodeId), "graph.nodes." + scheduleNodeId);
        if (!"automation.schedule".equals(optionalText(scheduleNode.get("type")))) {
            return NodeAdaptation.quarantined(dynamic(graph, nodeId,
                "The paired cancel task input does not originate from a migrated Schedule."));
        }
        Set<String> unsupportedOutputs = Set.of("failed", "result", "error_code", "message");
        if (wiredSource(connections, nodeId, unsupportedOutputs)) {
            return NodeAdaptation.quarantined(dynamic(graph, nodeId,
                "Connected cancel outputs cannot be represented by the consolidated Scheduled Task node: "
                    + connectedSourcePins(connections, nodeId, unsupportedOutputs)));
        }
        Map<String, Object> replacement = nodeValues(node);
        replacement.remove("task_id");
        replacement.put("action", "Cancel");
        Map<String, Object> migrated = copyObject(node, "node");
        migrated.put("type", "automation.scheduled_task");
        setTargetVersion(migrated, 2);
        migrated.put(valueField(node), replacement);
        migrated.put("handlerConfig", migratedHandlerConfig(node, "cancel_task", "scheduled_task"));
        renameTargetPin(connections, nodeId, "task_id", "task");
        renameSourcePin(connections, nodeId, Map.of(
            "cancelled", "success",
            "success", "success",
            "status", "state",
            "task", "output_task",
            "flow", "inactive",
            "output_flow", "inactive"));
        return NodeAdaptation.migrated(migrated, null);
    }

    private static boolean automationScheduleSource(Map<String, Object> nodes, Map<String, Object> connection) {
        Object value = nodes.get(sourceNode(connection));
        return value instanceof Map<?, ?>
            && "automation.schedule".equals(optionalText(object(value, "scheduleSource").get("type")));
    }

    private static void setTargetVersion(Map<String, Object> node, int version) {
        if (node.containsKey("version") || !node.containsKey("definitionVersion")) {
            node.put("version", version);
        }
        if (node.containsKey("definitionVersion")) {
            node.put("definitionVersion", version);
        }
    }

    private static void validateSourceVersion(Map<String, Object> node) {
        boolean versionPresent = node.containsKey("version");
        boolean definitionVersionPresent = node.containsKey("definitionVersion");
        if (!versionPresent && !definitionVersionPresent) {
            throw new IllegalArgumentException("node version is required");
        }
        Long version = versionPresent ? sourceVersion(node.get("version"), "node.version") : null;
        Long definitionVersion = definitionVersionPresent
            ? sourceVersion(node.get("definitionVersion"), "node.definitionVersion") : null;
        if (version != null && definitionVersion != null && !version.equals(definitionVersion)) {
            throw new IllegalArgumentException("node version fields conflict");
        }
        long resolved = version != null ? version : definitionVersion;
        if (resolved != 1L && resolved != 2L) {
            throw new IllegalArgumentException("node version is unsupported: " + resolved);
        }
    }

    private static long sourceVersion(Object value, String field) {
        if (!(value instanceof BigDecimal number)) {
            throw new IllegalArgumentException(field + " must be an integer");
        }
        try {
            return number.longValueExact();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(field + " must be an integer", exception);
        }
    }

    private static Map<String, Object> migratedHandlerConfig(Map<String, Object> node, String sourceOperation, String targetOperation) {
        if (!node.containsKey("handlerConfig")) {
            return Map.of("operation", targetOperation);
        }
        Map<String, Object> config = copyObject(node.get("handlerConfig"), "node.handlerConfig");
        if (config.containsKey("operation") && !sourceOperation.equals(config.get("operation"))) {
            throw new IllegalArgumentException("node.handlerConfig.operation conflicts with the legacy node type");
        }
        config.put("operation", targetOperation);
        return config;
    }

    private static String legacyScheduleOperation(String type) {
        return switch (type) {
            case "schedule.schedule" -> "schedule";
            case "schedule.schedule_repeating" -> "schedule_repeating";
            case "schedule.cron" -> "cron";
            case "schedule.at.time" -> "schedule_at_time";
            case "schedule.interval" -> "interval";
            default -> throw new IllegalArgumentException("schedule type is unsupported");
        };
    }

    private static Map<String, Object> scheduleDefinition(String id, String type, ScheduleTarget target, Map<String, Object> values) {
        Map<String, Object> definition = definition("schedule_definition", id);
        definition.put("description", "Migrated Schedule");
        definition.put("existingTaskPolicy", "replace");
        definition.put("failurePolicy", "continue");
        definition.put("missedRunPolicy", "run_once");
        definition.put("name", "Migrated " + target.id());
        definition.put("offlinePolicy", "wait");
        definition.put("overlapPolicy", "skip");
        definition.put("persistent", false);
        definition.put("scope", "server");
        definition.put("target", target.value());
        switch (type) {
            case "schedule.schedule" -> {
                String[] time = optionalText(values.getOrDefault("time_string", "12:00")).split(":", -1);
                if (time.length < 2 || time.length > 3 || time.length == 3 && integer(time[2], "seconds") != 0) {
                    throw new IllegalArgumentException("daily time is unsupported");
                }
                definition.put("cron", integer(time[1], "minutes") + " " + integer(time[0], "hours") + " * * *");
                definition.put("timeZone", optionalText(values.getOrDefault("time_zone", "UTC")));
                definition.put("timingMode", "cron");
            }
            case "schedule.schedule_repeating" -> repeating(definition, values.getOrDefault("interval_ticks", BigDecimal.valueOf(1200)), "ticks");
            case "schedule.interval" -> repeating(definition, values.getOrDefault("seconds", BigDecimal.ONE), "seconds");
            case "schedule.cron" -> {
                definition.put("cron", requiredStatic(values.getOrDefault("expression", "0 12 * * *"), "cron expression"));
                definition.put("timeZone", requiredStatic(values.getOrDefault("time_zone", "UTC"), "time zone"));
                definition.put("timingMode", "cron");
            }
            case "schedule.at.time" -> {
                definition.put("dateTime", requiredStatic(values.get("time"), "date time"));
                definition.put("timeZone", requiredStatic(values.getOrDefault("time_zone", "UTC"), "time zone"));
                definition.put("timingMode", "at_time");
            }
            default -> throw new IllegalArgumentException("schedule type is unsupported");
        }
        return definition;
    }

    private static void repeating(Map<String, Object> definition, Object duration, String unit) {
        if (!(duration instanceof BigDecimal)) {
            throw new IllegalArgumentException("duration is not a static number");
        }
        definition.put("duration", duration);
        definition.put("initialDelay", BigDecimal.ZERO);
        definition.put("timingMode", "repeating");
        definition.put("unit", unit);
    }

    private static Map<VariableIdentity, Set<String>> indexVariableTypes(Collection<GraphSource> graphs) {
        Map<VariableIdentity, Set<String>> result = new LinkedHashMap<>();
        for (GraphSource graph : graphs) {
            if (graph.document() == null || !(graph.document().get("nodes") instanceof Map<?, ?> rawNodes)) {
                continue;
            }
            for (Object value : rawNodes.values()) {
                if (!(value instanceof Map<?, ?> rawNode)) {
                    continue;
                }
                try {
                    Map<String, Object> node = object(rawNode, "node");
                    if (!optionalText(node.get("type")).equals("variable.access")) {
                        continue;
                    }
                    Map<String, Object> values = nodeValues(node);
                    String name = optionalText(values.get("name")).trim();
                    String type = inferVariableType(optionalText(values.getOrDefault("mode", "get")).toLowerCase(Locale.ROOT), values.get("value"));
                    if (!name.isBlank() && !type.equals("any")) {
                        result.computeIfAbsent(new VariableIdentity(name.toLowerCase(Locale.ROOT), scope(values.get("scope")), bool(values.get("persist"))),
                            ignored -> new LinkedHashSet<>()).add(type);
                    }
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        Map<VariableIdentity, Set<String>> immutable = new LinkedHashMap<>();
        result.forEach((key, value) -> immutable.put(key, Set.copyOf(value)));
        return Map.copyOf(immutable);
    }

    private static QuarantineRecord variableTypeConflict(GraphSource graph, Map<String, Object> nodes, List<Object> connections,
                                                          Map<VariableIdentity, Set<String>> variableTypes) {
        for (String nodeId : nodes.keySet().stream().sorted().toList()) {
            try {
                Map<String, Object> node = object(nodes.get(nodeId), "graph.nodes." + nodeId);
                if (!optionalText(node.get("type")).equals("variable.access")
                    || wired(connections, nodeId, Set.of("mode", "name", "scope", "persist"))) {
                    continue;
                }
                Map<String, Object> values = nodeValues(node);
                String name = optionalText(values.get("name")).trim();
                if (name.isBlank()) {
                    continue;
                }
                String scope = scope(values.get("scope"));
                boolean persistent = bool(values.get("persist"));
                Set<String> types = variableTypes.getOrDefault(new VariableIdentity(name.toLowerCase(Locale.ROOT), scope, persistent), Set.of());
                if (types.size() > 1) {
                    return quarantine(graph.source(), VARIABLE_TYPE_CONFLICT_CODE, graph.source().relativePath() + "#variable/" + name,
                        "The same legacy variable identity has conflicting explicit value types: " + types.stream().sorted().toList(),
                        List.of("variable:" + name, "scope:" + scope, "persistent:" + persistent));
                }
            } catch (IllegalArgumentException exception) {
                return quarantine(graph.source(), INVALID_STATE_CODE, graph.source().relativePath() + "#node/" + nodeId,
                    "The legacy variable identity cannot be inspected without losing data: " + exception.getMessage(), List.of("node:" + nodeId));
            }
        }
        return null;
    }

    private static Map<String, Object> definition(String type, String id) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", id);
        value.put("mutationId", "migration-" + CanonicalJson.sha256("migration.automation.definition", List.of(type, id)).substring(0, 32));
        value.put("resourceRevision", BigDecimal.ONE);
        value.put("resourceType", type);
        return value;
    }

    private static Map<String, Object> resourceReference(String type, String id) {
        return Map.of("id", id, "resourceType", type, "scope", "server");
    }

    private static QuarantineRecord dynamic(GraphSource graph, String nodeId, String reason) {
        return quarantine(graph.source(), DYNAMIC_REFERENCE_CODE, graph.source().relativePath() + "#node/" + nodeId,
            reason, List.of("node:" + nodeId));
    }

    private static QuarantineRecord quarantine(SourceFile source, String code, String location, String reason, List<String> references) {
        String recordId = "automation-" + CanonicalJson.sha256("migration.automation.quarantine",
            List.of(source.relativePath(), location, code, reason, source.sha256())).substring(0, 24);
        List<String> affected = new ArrayList<>(references);
        if (!location.equals(source.relativePath())) {
            affected.add(location);
        }
        return new QuarantineRecord(recordId, code, source.relativePath(), reason, affected,
            "Keep the original automation data and replace dynamic references with one explicit typed resource before retrying.", source.sha256());
    }

    private static boolean owned(String path) {
        return graphPath(path) || definitionPath(path) || RUNTIME_PATHS.contains(path);
    }

    private static boolean graphPath(String path) {
        return path.endsWith(".json")
            && (path.startsWith(FLOW_PREFIX) || path.startsWith(FUNCTION_PREFIX) || path.startsWith(COMMAND_PREFIX));
    }

    private static boolean commandGraphPath(String path) {
        return path.endsWith(".json") && path.startsWith(COMMAND_PREFIX);
    }

    private static boolean definitionPath(String path) {
        String normalized = normalizedPath(path);
        return normalized.endsWith(".json")
            && (normalized.startsWith(normalizedPath(VARIABLE_PREFIX)) || normalized.startsWith(normalizedPath(SCHEDULE_PREFIX)));
    }

    private static String expectedResourceType(String path) {
        if (path.startsWith(FUNCTION_PREFIX)) {
            return "function";
        }
        return path.startsWith(COMMAND_PREFIX) ? "command" : "flow";
    }

    private static String fileId(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        return name.substring(0, name.length() - ".json".length());
    }

    private static boolean containsLegacyAutomation(Object value) {
        if (!(value instanceof Map<?, ?> nodes)) {
            return false;
        }
        return nodes.values().stream().filter(Map.class::isInstance).map(Map.class::cast)
            .map(node -> node.get("type")).anyMatch(type -> "variable.access".equals(type) || SCHEDULE_TYPES.contains(type));
    }

    private static Map<String, Object> nodeValues(Map<String, Object> node) {
        String field = valueField(node);
        Object value = node.get(field);
        return value == null ? new LinkedHashMap<>() : copyObject(value, "node." + field);
    }

    private static String valueField(Map<String, Object> node) {
        return node.containsKey("inputValues") ? "inputValues" : "inputs";
    }

    private static boolean wired(List<Object> connections, String nodeId, Set<String> pins) {
        for (Object value : connections) {
            Map<String, Object> connection = object(value, "connection");
            String targetNode = optionalText(connection.get("targetNodeId"));
            String targetPin = optionalText(connection.get("targetPin"));
            if (connection.get("target") instanceof Map<?, ?> target) {
                targetNode = optionalText(target.get("nodeId"));
                targetPin = optionalText(target.get("pinId"));
            }
            if (targetNode.equals(nodeId) && pins.contains(targetPin)) {
                return true;
            }
        }
        return false;
    }

    private static List<Map<String, Object>> incoming(List<Object> connections, String nodeId, String pinId) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object value : connections) {
            Map<String, Object> connection = object(value, "connection");
            if (nodeId.equals(targetNode(connection)) && pinId.equals(targetPin(connection))) {
                result.add(connection);
            }
        }
        return List.copyOf(result);
    }

    private static QuarantineRecord scheduleOutputConflict(GraphSource graph, Map<String, Object> nodes,
                                                            List<Object> connections) {
        for (Map.Entry<String, Object> entry : nodes.entrySet()) {
            Map<String, Object> node = object(entry.getValue(), "graph.nodes." + entry.getKey());
            if (!SCHEDULE_TYPES.contains(optionalText(node.get("type")))) {
                continue;
            }
            String nodeId = entry.getKey();
            if (wiredSource(connections, nodeId, Set.of("result"))) {
                return dynamic(graph, nodeId,
                    "The connected Schedule result output has no lossless consolidated Schedule equivalent.");
            }
            for (Map<String, Object> connection : outgoing(connections, nodeId, "task_id")) {
                String targetNodeId = targetNode(connection);
                Object targetValue = nodes.get(targetNodeId);
                if (!(targetValue instanceof Map<?, ?>)
                    || !"schedule.cancel_task".equals(optionalText(object(targetValue,
                        "graph.nodes." + targetNodeId).get("type")))
                    || !"task_id".equals(targetPin(connection))) {
                    return dynamic(graph, nodeId,
                        "The connected Schedule Task ID is not paired with one legacy Cancel Scheduled Flow input.");
                }
            }
        }
        return null;
    }

    private static List<Map<String, Object>> outgoing(List<Object> connections, String nodeId, String pinId) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object value : connections) {
            Map<String, Object> connection = object(value, "connection");
            if (nodeId.equals(sourceNode(connection)) && pinId.equals(sourcePin(connection))) {
                result.add(connection);
            }
        }
        return List.copyOf(result);
    }

    private static boolean wiredSource(List<Object> connections, String nodeId, Set<String> pins) {
        return connections.stream().map(value -> object(value, "connection"))
            .anyMatch(connection -> nodeId.equals(sourceNode(connection)) && pins.contains(sourcePin(connection)));
    }

    private static List<String> connectedSourcePins(List<Object> connections, String nodeId, Set<String> pins) {
        return connections.stream().map(value -> object(value, "connection"))
            .filter(connection -> nodeId.equals(sourceNode(connection)) && pins.contains(sourcePin(connection)))
            .map(AutomationMigrationAdapter::sourcePin).distinct().sorted().toList();
    }

    private static String sourceNode(Map<String, Object> connection) {
        if (connection.get("source") instanceof Map<?, ?> source) {
            return optionalText(source.get("nodeId"));
        }
        return optionalText(connection.get("sourceNodeId"));
    }

    private static String sourcePin(Map<String, Object> connection) {
        if (connection.get("source") instanceof Map<?, ?> source) {
            return optionalText(source.get("pinId"));
        }
        return optionalText(connection.get("sourcePin"));
    }

    private static String targetNode(Map<String, Object> connection) {
        if (connection.get("target") instanceof Map<?, ?> target) {
            return optionalText(target.get("nodeId"));
        }
        return optionalText(connection.get("targetNodeId"));
    }

    private static String targetPin(Map<String, Object> connection) {
        if (connection.get("target") instanceof Map<?, ?> target) {
            return optionalText(target.get("pinId"));
        }
        return optionalText(connection.get("targetPin"));
    }

    private static void renameTargetPin(List<Object> connections, String nodeId, String sourcePin, String targetPin) {
        for (int index = 0; index < connections.size(); index++) {
            Map<String, Object> connection = copyObject(connections.get(index), "connection");
            if (nodeId.equals(optionalText(connection.get("targetNodeId"))) && sourcePin.equals(optionalText(connection.get("targetPin")))) {
                connection.put("targetPin", targetPin);
            }
            if (connection.get("target") instanceof Map<?, ?> rawTarget) {
                Map<String, Object> target = copyObject(rawTarget, "connection.target");
                if (nodeId.equals(optionalText(target.get("nodeId"))) && sourcePin.equals(optionalText(target.get("pinId")))) {
                    target.put("pinId", targetPin);
                    connection.put("target", target);
                }
            }
            connections.set(index, connection);
        }
    }

    private static void renameSourcePin(List<Object> connections, String nodeId, Map<String, String> pins) {
        for (int index = 0; index < connections.size(); index++) {
            Map<String, Object> connection = copyObject(connections.get(index), "connection");
            if (nodeId.equals(optionalText(connection.get("sourceNodeId")))) {
                String pin = pins.get(optionalText(connection.get("sourcePin")));
                if (pin != null) {
                    connection.put("sourcePin", pin);
                }
            }
            if (connection.get("source") instanceof Map<?, ?> rawSource) {
                Map<String, Object> source = copyObject(rawSource, "connection.source");
                if (nodeId.equals(optionalText(source.get("nodeId")))) {
                    String pin = pins.get(optionalText(source.get("pinId")));
                    if (pin != null) {
                        source.put("pinId", pin);
                        connection.put("source", source);
                    }
                }
            }
            connections.set(index, connection);
        }
    }

    private static Set<String> scheduleIdentityPins(String type) {
        return switch (type) {
            case "schedule.schedule" -> Set.of("flow_id", "time_string", "time_zone");
            case "schedule.schedule_repeating" -> Set.of("flow_id", "interval_ticks");
            case "schedule.interval" -> Set.of("flow_id", "seconds");
            case "schedule.cron" -> Set.of("flow_id", "expression", "time_zone");
            case "schedule.at.time" -> Set.of("flow_id", "time", "time_zone");
            default -> Set.of("flow_id");
        };
    }

    private static String inferVariableType(String action, Object value) {
        if (Set.of("increment", "decrement", "multiply", "divide").contains(action) || value instanceof BigDecimal) {
            return "number";
        }
        if (value instanceof Boolean) {
            return "boolean";
        }
        if (value instanceof String) {
            return "string";
        }
        if (value instanceof List<?>) {
            return "list<any>";
        }
        if (value instanceof Map<?, ?>) {
            return "map<string,any>";
        }
        return "any";
    }

    private static String scope(Object value) {
        return switch (optionalText(value).toLowerCase(Locale.ROOT)) {
            case "global", "server" -> "server";
            case "player" -> "player";
            default -> "flow";
        };
    }

    private static boolean bool(Object value) {
        return value instanceof Boolean bool ? bool : Boolean.parseBoolean(optionalText(value));
    }

    private static ScheduleTarget scheduleTarget(Object value) {
        if (value instanceof String text) {
            String id = text.trim();
            return new ScheduleTarget(id, "flow", Map.of("id", id, "resourceType", "flow"));
        }
        if (value instanceof Map<?, ?> map) {
            String id = optionalText(map.get("id")).trim();
            String resourceId = optionalText(map.get("resourceId")).trim();
            if (!id.isBlank() && !resourceId.isBlank() && !id.equals(resourceId)) {
                throw new IllegalArgumentException("structured target declares conflicting IDs");
            }
            id = id.isBlank() ? resourceId : id;
            String resourceType = optionalText(map.get("resourceType")).trim();
            String type = optionalText(map.get("type")).trim();
            if (!resourceType.isBlank() && !type.isBlank() && !resourceType.equals(type)) {
                throw new IllegalArgumentException("structured target declares conflicting resource types");
            }
            resourceType = resourceType.isBlank() ? type : resourceType;
            if (resourceType.isBlank()) {
                throw new IllegalArgumentException("structured target does not declare resourceType");
            }
            if (!resourceType.equals("flow")) {
                throw new IllegalArgumentException("structured target resourceType is unsupported: " + resourceType);
            }
            Map<String, Object> target = object(map, "scheduleTarget");
            target.remove("resourceId");
            target.remove("type");
            target.put("id", id);
            target.put("resourceType", resourceType);
            return new ScheduleTarget(id, resourceType, target);
        }
        return new ScheduleTarget("", "flow", Map.of("id", "", "resourceType", "flow"));
    }

    private static String definitionId(String prefix, String... parts) {
        List<String> values = new ArrayList<>();
        values.add(prefix);
        for (String part : parts) {
            String normalized = optionalText(part).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]+", "_").replaceAll("^_+|_+$", "");
            if (!normalized.isBlank()) {
                values.add(normalized);
            }
        }
        return "migrated." + String.join(".", values);
    }

    private static String title(String value) {
        return value.isEmpty() ? value : value.substring(0, 1).toUpperCase(Locale.ROOT) + value.substring(1);
    }

    private static boolean canonicalEquivalent(byte[] left, byte[] right) {
        try {
            return CanonicalJson.canonicalize(CanonicalJson.parse(left)).equals(CanonicalJson.canonicalize(CanonicalJson.parse(right)));
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static String normalizedPath(String path) {
        return path.toLowerCase(Locale.ROOT);
    }

    private static Map<String, Object> object(Object value, String field) {
        if (!(value instanceof Map<?, ?> raw)) {
            throw new IllegalArgumentException(field + " must be an object");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException(field + " contains a non-text key");
            }
            result.put(key, entry.getValue());
        }
        return result;
    }

    private static Map<String, Object> copyObject(Object value, String field) {
        Map<String, Object> source = object(value, field);
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, item) -> result.put(key, copy(item, field + "." + key)));
        return result;
    }

    private static Object copy(Object value, String field) {
        if (value instanceof Map<?, ?>) {
            return copyObject(value, field);
        }
        if (value instanceof List<?> list) {
            List<Object> result = new ArrayList<>(list.size());
            for (int index = 0; index < list.size(); index++) {
                result.add(copy(list.get(index), field + "[" + index + "]"));
            }
            return result;
        }
        return value;
    }

    private static List<Object> array(Object value, String field) {
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException(field + " must be an array");
        }
        return new ArrayList<>(list);
    }

    private static String text(Object value, String field) {
        if (!(value instanceof String text) || text.isBlank() || text.indexOf('\0') >= 0 || text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0) {
            throw new IllegalArgumentException(field + " must be non-blank single-line text");
        }
        return text;
    }

    private static String optionalText(Object value) {
        return value == null ? "" : value.toString();
    }

    private static String requiredStatic(Object value, String field) {
        return text(value, field);
    }

    private static long nonNegativeInteger(Object value, String field) {
        if (!(value instanceof BigDecimal number)) {
            throw new IllegalArgumentException(field + " must be an integer");
        }
        try {
            long result = number.longValueExact();
            if (result < 0) {
                throw new IllegalArgumentException(field + " must be non-negative");
            }
            return result;
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(field + " must be an integer", exception);
        }
    }

    private static int integer(String value, String field) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(field + " must be an integer", exception);
        }
    }

    private record GraphSource(SourceFile source, String resourceType, Map<String, Object> document) {
    }

    private record VariableIdentity(String name, String scope, boolean persistent) {
    }

    private record ScheduleTarget(String id, String resourceType, Map<String, Object> value) {
        private ScheduleTarget {
            value = Collections.unmodifiableMap(new LinkedHashMap<>(value));
        }
    }

    private record PlannedDefinition(String path, byte[] bytes) {
        private PlannedDefinition {
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    private record NodeAdaptation(Map<String, Object> node, PlannedDefinition definition, QuarantineRecord quarantine) {
        private static NodeAdaptation migrated(Map<String, Object> node, PlannedDefinition definition) {
            return new NodeAdaptation(node, definition, null);
        }

        private static NodeAdaptation quarantined(QuarantineRecord quarantine) {
            return new NodeAdaptation(null, null, quarantine);
        }
    }

    private record GraphAdaptation(boolean changed, byte[] graphBytes, List<PlannedDefinition> definitions, List<QuarantineRecord> quarantine) {
        private static GraphAdaptation unchanged() {
            return new GraphAdaptation(false, null, List.of(), List.of());
        }

        private static GraphAdaptation quarantined(QuarantineRecord quarantine) {
            return new GraphAdaptation(false, null, List.of(), List.of(quarantine));
        }
    }
}
