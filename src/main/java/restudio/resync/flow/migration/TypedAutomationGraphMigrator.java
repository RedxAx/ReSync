package restudio.resync.flow.migration;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import restudio.flow.data.FlowConnection;
import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowNode;
import restudio.flow.data.FlowResourceReference;
import restudio.resync.Log;
import restudio.resync.customization.ReSyncJsonResourceStorage;
import restudio.resync.flow.FlowStorage;
import restudio.resync.flow.automation.AutomationReferences;
import restudio.resync.flow.registry.NodeDefinition;
import restudio.resync.flow.registry.NodeDefinitionRegistry;
import restudio.resync.resources.ReSyncResourceCatalog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class TypedAutomationGraphMigrator {
    private static final Gson GSON = new Gson();
    private static final Set<String> SCHEDULE_TYPES = Set.of(
        "schedule.schedule", "schedule.schedule_repeating", "schedule.cron", "schedule.at.time", "schedule.interval");
    private final FlowStorage flows;
    private final ReSyncJsonResourceStorage resources;
    private final NodeDefinitionRegistry definitions;
    private final Map<VariableIdentity, Set<String>> variableTypes = new LinkedHashMap<>();

    public TypedAutomationGraphMigrator(FlowStorage flows, ReSyncJsonResourceStorage resources) {
        this(flows, resources, null);
    }

    public TypedAutomationGraphMigrator(FlowStorage flows, ReSyncJsonResourceStorage resources, NodeDefinitionRegistry definitions) {
        this.flows = flows;
        this.resources = resources;
        this.definitions = definitions;
    }

    public void migrateStoredFlows() {
        if (flows == null || resources == null) {
            return;
        }
        String migrationId = "typed-automation-" + System.currentTimeMillis();
        indexVariableTypes();
        int migratedGraphs = 0;
        int migratedNodes = 0;
        int skippedNodes = 0;
        for (String flowId : flows.listFlowIds()) {
            FlowGraph graph = flows.getGraph(flowId);
            if (graph == null || graph.getNodes() == null) {
                continue;
            }
            int changed = 0;
            for (Map.Entry<String, FlowNode> entry : graph.getNodes().entrySet()) {
                FlowNode node = entry.getValue();
                if (node == null) {
                    continue;
                }
                MigrationResult result;
                try {
                    result = migrateNode(graph, flowId, entry.getKey(), node);
                } catch (RuntimeException failure) {
                    Log.warn("[TypedAutomationMigrator] Kept " + flowId + "/" + entry.getKey()
                        + " on its compatibility handler: " + failure.getMessage());
                    result = MigrationResult.SKIPPED;
                }
                if (result == MigrationResult.MIGRATED) {
                    changed++;
                } else if (result == MigrationResult.SKIPPED) {
                    skippedNodes++;
                }
            }
            if (changed > 0) {
                flows.backupGraphForMigration(flowId, migrationId);
                flows.saveGraph(graph);
                migratedGraphs++;
                migratedNodes += changed;
            }
        }
        if (migratedNodes > 0 || skippedNodes > 0) {
            Log.info("[TypedAutomationMigrator] Migrated " + migratedNodes + " nodes across " + migratedGraphs
                + " Flows; left " + skippedNodes + " dynamic or externally referenced nodes on compatibility handlers");
        }
    }

    private MigrationResult migrateNode(FlowGraph graph, String flowId, String nodeId, FlowNode node) {
        if ("variable.access".equals(node.getType())) {
            return migrateVariable(graph, flowId, nodeId, node);
        }
        if (SCHEDULE_TYPES.contains(node.getType())) {
            return migrateSchedule(graph, flowId, nodeId, node);
        }
        if ("schedule.cancel_task".equals(node.getType()) && recoverableCancel(graph, nodeId)) {
            return migrateCancelTask(graph, nodeId, node);
        }
        return MigrationResult.UNCHANGED;
    }

    private MigrationResult migrateVariable(FlowGraph graph, String flowId, String nodeId, FlowNode node) {
        Map<String, Object> input = values(node);
        String action = text(input.getOrDefault("mode", "get")).toLowerCase(Locale.ROOT);
        if ("list".equals(action) || wired(graph, nodeId, Set.of("name", "scope", "persist"))) {
            return MigrationResult.SKIPPED;
        }
        String name = text(input.get("name")).trim();
        if (name.isBlank()) {
            return MigrationResult.SKIPPED;
        }
        String scope = switch (text(input.getOrDefault("scope", "local")).toLowerCase(Locale.ROOT)) {
            case "global" -> "server";
            case "player" -> "player";
            default -> "flow";
        };
        boolean persistent = booleanValue(input.get("persist"));
        String valueType = inferVariableType(graph, nodeId, input.get("value"), action);
        VariableIdentity identity = new VariableIdentity(name.toLowerCase(Locale.ROOT), scope, persistent);
        Set<String> inferredTypes = variableTypes.getOrDefault(identity, Set.of());
        if ("any".equals(valueType)) {
            if (inferredTypes.size() != 1) {
                String reason = inferredTypes.isEmpty() ? "no value type could be inferred" : "conflicting value types " + inferredTypes;
                Log.warn("[TypedAutomationMigrator] Kept " + flowId + "/" + nodeId + " on Variable Access because " + reason
                    + " for " + name + " (" + scope + ", " + (persistent ? "persistent" : "runtime") + ")");
                return MigrationResult.SKIPPED;
            }
            valueType = inferredTypes.iterator().next();
        }
        if (persistent && valueType.contains("any")) {
            return MigrationResult.SKIPPED;
        }
        String definitionId = definitionId("variable", name, scope, persistent ? "persistent" : "runtime", valueType);
        saveVariableDefinition(definitionId, name, scope, persistent, valueType);
        Object owner = input.get("player");
        input.keySet().removeAll(Set.of("mode", "scope", "persist", "name", "player"));
        input.put("variable", new FlowResourceReference(ReSyncResourceCatalog.VARIABLE_DEFINITION, definitionId, "server"));
        input.put("action", action);
        if (owner != null) {
            input.put("owner", owner);
        }
        node.setType("automation.variable");
        node.setInputValues(input);
        node.setHandlerConfig(Map.of("operation", "variable_access"));
        renameTargetPin(graph, nodeId, "player", "owner");
        return MigrationResult.MIGRATED;
    }

    private void indexVariableTypes() {
        variableTypes.clear();
        for (String flowId : flows.listFlowIds()) {
            FlowGraph graph = flows.getGraph(flowId);
            if (graph == null || graph.getNodes() == null) {
                continue;
            }
            for (Map.Entry<String, FlowNode> entry : graph.getNodes().entrySet()) {
                FlowNode node = entry.getValue();
                if (node == null || !"variable.access".equals(node.getType())
                    || wired(graph, entry.getKey(), Set.of("name", "scope", "persist"))) {
                    continue;
                }
                Map<String, Object> input = values(node);
                String action = text(input.getOrDefault("mode", "get")).toLowerCase(Locale.ROOT);
                String name = text(input.get("name")).trim();
                if (name.isBlank() || "list".equals(action)) {
                    continue;
                }
                String scope = switch (text(input.getOrDefault("scope", "local")).toLowerCase(Locale.ROOT)) {
                    case "global" -> "server";
                    case "player" -> "player";
                    default -> "flow";
                };
                boolean persistent = booleanValue(input.get("persist"));
                String type = inferVariableType(graph, entry.getKey(), input.get("value"), action);
                if (!"any".equals(type)) {
                    variableTypes.computeIfAbsent(new VariableIdentity(name.toLowerCase(Locale.ROOT), scope, persistent),
                        ignored -> new LinkedHashSet<>()).add(type);
                }
            }
        }
    }

    private MigrationResult migrateSchedule(FlowGraph graph, String flowId, String nodeId, FlowNode node) {
        if (wired(graph, nodeId, identityPins(node.getType())) || outgoing(graph, nodeId, Set.of("result"))
            || (outgoing(graph, nodeId, Set.of("task_id")) && !recoverableTaskOutputs(graph, nodeId))) {
            return MigrationResult.SKIPPED;
        }
        Map<String, Object> input = values(node);
        String targetId = AutomationReferences.id(input.get("flow_id"));
        if (targetId.isBlank()) {
            return MigrationResult.SKIPPED;
        }
        String definitionId = definitionId("schedule", flowId, nodeId);
        JsonObject definition = scheduleDefinition(definitionId, node.getType(), targetId, input);
        if (definition == null) {
            return MigrationResult.SKIPPED;
        }
        saveIfMissing(ReSyncResourceCatalog.SCHEDULE_DEFINITION, definitionId, definition);
        Object flowInput = input.get("flow");
        Map<String, Object> replacement = new LinkedHashMap<>();
        if (flowInput != null) {
            replacement.put("flow", flowInput);
        }
        replacement.put("schedule", new FlowResourceReference(ReSyncResourceCatalog.SCHEDULE_DEFINITION, definitionId, "server"));
        node.setType("automation.schedule");
        node.setInputValues(replacement);
        node.setHandlerConfig(Map.of("operation", "schedule_definition"));
        migrateScheduleSourcePins(graph, nodeId);
        return MigrationResult.MIGRATED;
    }

    private MigrationResult migrateCancelTask(FlowGraph graph, String nodeId, FlowNode node) {
        Map<String, Object> input = values(node);
        Object flow = input.get("flow");
        Map<String, Object> replacement = new LinkedHashMap<>();
        if (flow != null) {
            replacement.put("flow", flow);
        }
        replacement.put("action", "Cancel");
        node.setType("automation.scheduled_task");
        node.setInputValues(replacement);
        node.setHandlerConfig(Map.of("operation", "scheduled_task"));
        for (FlowConnection connection : graph.getConnections()) {
            if (nodeId.equals(connection.getTargetNodeId()) && "task_id".equals(connection.getTargetPin())) {
                connection.setTargetPin("task");
            }
            if (nodeId.equals(connection.getSourceNodeId())) {
                connection.setSourcePin(switch (connection.getSourcePin()) {
                    case "flow" -> "inactive";
                    case "cancelled" -> "success";
                    case "status" -> "state";
                    default -> connection.getSourcePin();
                });
            }
        }
        return MigrationResult.MIGRATED;
    }

    private void saveVariableDefinition(String id, String name, String scope, boolean persistent, String valueType) {
        JsonObject definition = new JsonObject();
        definition.addProperty("id", id);
        definition.addProperty("name", name);
        definition.addProperty("description", "Migrated Variable");
        definition.addProperty("valueType", valueType);
        definition.addProperty("scope", scope);
        definition.addProperty("persistent", persistent);
        saveIfMissing(ReSyncResourceCatalog.VARIABLE_DEFINITION, id, definition);
    }

    private JsonObject scheduleDefinition(String id, String type, String targetId, Map<String, Object> input) {
        JsonObject definition = new JsonObject();
        definition.addProperty("id", id);
        definition.addProperty("name", "Migrated " + targetId);
        definition.addProperty("description", "Migrated Schedule");
        definition.addProperty("targetType", "flow");
        definition.addProperty("targetId", targetId);
        definition.addProperty("scope", "server");
        definition.addProperty("persistent", false);
        definition.addProperty("overlapPolicy", "skip");
        definition.addProperty("existingTaskPolicy", "replace");
        definition.addProperty("failurePolicy", "continue");
        definition.addProperty("offlinePolicy", "wait");
        definition.addProperty("missedRunPolicy", "run_once");
        switch (type) {
            case "schedule.schedule" -> {
                String[] time = text(input.getOrDefault("time_string", "12:00")).split(":");
                if (time.length < 2) return null;
                if (time.length > 2 && Integer.parseInt(time[2]) != 0) return null;
                definition.addProperty("timingMode", "cron");
                definition.addProperty("cron", Integer.parseInt(time[1]) + " " + Integer.parseInt(time[0]) + " * * *");
                definition.addProperty("timeZone", text(input.getOrDefault("time_zone", "UTC")));
            }
            case "schedule.schedule_repeating" -> {
                definition.addProperty("timingMode", "repeating");
                definition.add("duration", json(input.getOrDefault("interval_ticks", 1200)));
                definition.addProperty("unit", "ticks");
                definition.addProperty("initialDelay", 0);
            }
            case "schedule.interval" -> {
                definition.addProperty("timingMode", "repeating");
                definition.add("duration", json(input.getOrDefault("seconds", 1)));
                definition.addProperty("unit", "seconds");
                definition.addProperty("initialDelay", 0);
            }
            case "schedule.cron" -> {
                definition.addProperty("timingMode", "cron");
                definition.addProperty("cron", text(input.getOrDefault("expression", "0 12 * * *")));
                definition.addProperty("timeZone", text(input.getOrDefault("time_zone", "UTC")));
            }
            case "schedule.at.time" -> {
                definition.addProperty("timingMode", "at_time");
                definition.addProperty("dateTime", text(input.get("time")));
                definition.addProperty("timeZone", text(input.getOrDefault("time_zone", "UTC")));
            }
            default -> {
                return null;
            }
        }
        return definition;
    }

    private void saveIfMissing(String type, String id, JsonObject definition) {
        if (resources.get(type, id) == null) {
            resources.save(type, definition);
        }
    }

    private Map<String, Object> values(FlowNode node) {
        return node.getInputValues() != null ? new LinkedHashMap<>(node.getInputValues()) : new LinkedHashMap<>();
    }

    private boolean wired(FlowGraph graph, String nodeId, Set<String> pins) {
        return graph.getConnections().stream().anyMatch(connection -> nodeId.equals(connection.getTargetNodeId()) && pins.contains(connection.getTargetPin()));
    }

    private boolean outgoing(FlowGraph graph, String nodeId, Set<String> pins) {
        return graph.getConnections().stream().anyMatch(connection -> nodeId.equals(connection.getSourceNodeId()) && pins.contains(connection.getSourcePin()));
    }

    private Set<String> identityPins(String type) {
        return switch (type) {
            case "schedule.schedule" -> Set.of("flow_id", "time_string", "time_zone");
            case "schedule.schedule_repeating" -> Set.of("flow_id", "interval_ticks");
            case "schedule.interval" -> Set.of("flow_id", "seconds");
            case "schedule.cron" -> Set.of("flow_id", "expression", "time_zone");
            case "schedule.at.time" -> Set.of("flow_id", "time", "time_zone");
            default -> Set.of("flow_id");
        };
    }

    private void renameTargetPin(FlowGraph graph, String nodeId, String oldPin, String newPin) {
        for (FlowConnection connection : graph.getConnections()) {
            if (nodeId.equals(connection.getTargetNodeId()) && oldPin.equals(connection.getTargetPin())) {
                connection.setTargetPin(newPin);
            }
        }
    }

    private void migrateScheduleSourcePins(FlowGraph graph, String nodeId) {
        for (FlowConnection connection : graph.getConnections()) {
            if (!nodeId.equals(connection.getSourceNodeId())) {
                continue;
            }
            connection.setSourcePin(switch (connection.getSourcePin()) {
                case "flow" -> "scheduled";
                case "scheduled" -> "success";
                case "task_id" -> "task";
                default -> connection.getSourcePin();
            });
        }
    }

    private boolean recoverableTaskOutputs(FlowGraph graph, String nodeId) {
        List<FlowConnection> connections = graph.getConnections().stream()
            .filter(connection -> nodeId.equals(connection.getSourceNodeId()) && "task_id".equals(connection.getSourcePin())).toList();
        return !connections.isEmpty() && connections.stream().allMatch(connection -> {
            FlowNode target = graph.getNodes().get(connection.getTargetNodeId());
            return target != null && ("schedule.cancel_task".equals(target.getType()) || "automation.scheduled_task".equals(target.getType()))
                && ("task_id".equals(connection.getTargetPin()) || "task".equals(connection.getTargetPin()));
        });
    }

    private boolean recoverableCancel(FlowGraph graph, String nodeId) {
        List<FlowConnection> connections = graph.getConnections().stream()
            .filter(connection -> nodeId.equals(connection.getTargetNodeId()) && ("task_id".equals(connection.getTargetPin())
                || "task".equals(connection.getTargetPin()))).toList();
        return connections.size() == 1 && connections.stream().allMatch(connection -> {
            FlowNode source = graph.getNodes().get(connection.getSourceNodeId());
            return source != null && (SCHEDULE_TYPES.contains(source.getType()) || "automation.schedule".equals(source.getType()));
        });
    }

    private String inferType(Object value, boolean numeric) {
        if (numeric || value instanceof Number) return "number";
        if (value instanceof Boolean) return "boolean";
        if (value instanceof Map<?, ?>) return "map<string,any>";
        if (value instanceof List<?>) return "list<any>";
        return value instanceof String ? "string" : "any";
    }

    private String inferVariableType(FlowGraph graph, String nodeId, Object value, String action) {
        String inferred = inferType(value, Set.of("increment", "decrement", "multiply", "divide").contains(action));
        if (!"any".equals(inferred) || definitions == null) {
            return inferred;
        }
        FlowConnection connection = graph.getConnections().stream()
            .filter(candidate -> nodeId.equals(candidate.getTargetNodeId()) && "value".equals(candidate.getTargetPin()))
            .findFirst().orElse(null);
        if (connection == null) {
            return inferred;
        }
        FlowNode source = graph.getNodes().get(connection.getSourceNodeId());
        NodeDefinition definition = source != null ? definitions.get(source.getType()) : null;
        if (definition == null) {
            return inferred;
        }
        return definition.getOutputs().stream().filter(output -> connection.getSourcePin().equals(output.getName()))
            .map(output -> output.getTypeRef().toString()).filter(type -> !"any".equals(type) && !"execution".equals(type))
            .findFirst().orElse(inferred);
    }

    private boolean booleanValue(Object value) {
        return value instanceof Boolean bool ? bool : Boolean.parseBoolean(text(value));
    }

    private String definitionId(String prefix, String... parts) {
        List<String> values = new ArrayList<>();
        values.add(prefix);
        for (String part : parts) {
            String normalized = text(part).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]+", "_").replaceAll("^_+|_+$", "");
            if (!normalized.isBlank()) values.add(normalized);
        }
        return "migrated." + String.join(".", values);
    }

    private JsonElement json(Object value) {
        return GSON.toJsonTree(value);
    }

    private String text(Object value) {
        return value != null ? value.toString() : "";
    }

    private enum MigrationResult {
        MIGRATED,
        SKIPPED,
        UNCHANGED
    }

    private record VariableIdentity(String name, String scope, boolean persistent) {
    }
}
