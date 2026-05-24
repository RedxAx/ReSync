package restudio.resync.flow.migration;

import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowConnection;
import restudio.flow.data.FlowNode;
import restudio.resync.Log;
import restudio.resync.flow.FlowStorage;
import restudio.resync.flow.registry.NodeDefinition;
import restudio.resync.flow.registry.NodeDefinitionRegistry;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class FlowGraphMigrator {
    private static final Set<String> LEGACY_EVENT_FLOW_PINS = Set.of("next", "left", "right", "middle", "shift_left", "shift_right");
    private final FlowStorage storage;
    private final NodeDefinitionRegistry nodeDefinitionRegistry;
    private final IdCompatibilityLayer idCompatibility;

    public FlowGraphMigrator(FlowStorage storage) {
        this(storage, null);
    }

    public FlowGraphMigrator(FlowStorage storage, NodeDefinitionRegistry nodeDefinitionRegistry) {
        this.storage = storage;
        this.nodeDefinitionRegistry = nodeDefinitionRegistry;
        this.idCompatibility = new IdCompatibilityLayer();
    }

    public void migrateStoredFlows() {
        if (storage == null) {
            return;
        }
        int migrated = 0;
        for (String flowId : storage.listFlowIds()) {
            boolean legacyGraphFile = !storage.hasStoredGraphVersion(flowId);
            FlowGraph graph = storage.getGraph(flowId);
            if (graph == null) {
                continue;
            }
            if (migrateGraph(graph, legacyGraphFile)) {
                storage.saveGraph(graph);
                migrated++;
            }
        }
        if (migrated > 0) {
            Log.info("[FlowGraphMigrator] Migrated " + migrated + " flow(s) to version " + FlowGraph.CURRENT_VERSION);
        }
    }

    public boolean migrateGraph(FlowGraph graph) {
        return migrateGraph(graph, false);
    }

    public boolean migrateGraph(FlowGraph graph, boolean forceLegacyGraph) {
        if (graph == null) {
            return false;
        }
        boolean changed = false;
        boolean legacyGraph = forceLegacyGraph || graph.getVersion() <= 0;
        boolean outdatedGraph = forceLegacyGraph || graph.getVersion() < FlowGraph.CURRENT_VERSION;
        if (outdatedGraph) {
            graph.setVersion(FlowGraph.CURRENT_VERSION);
            changed = true;
        }
        Map<String, FlowNode> nodes = graph.getNodes();
        if (nodes == null || nodes.isEmpty()) {
            return changed;
        }
        Map<String, String> originalTypes = new HashMap<>();
        for (Map.Entry<String, FlowNode> entry : nodes.entrySet()) {
            FlowNode node = entry.getValue();
            if (node == null) {
                continue;
            }
            String originalType = node.getType();
            originalTypes.put(entry.getKey(), originalType);
            boolean legacyNode = node.getVersion() <= 0;
            boolean outdatedNode = node.getVersion() < FlowNode.CURRENT_VERSION;
            String mappedType = idCompatibility.mapToNew(originalType);
            if (mappedType != null && !mappedType.equals(originalType) && (legacyGraph || legacyNode || idCompatibility.hasMapping(originalType))) {
                node.setType(mappedType);
                changed = true;
            }
            if (migrateNodeValues(node, originalType)) {
                changed = true;
            }
            if (migrateHandlerConfig(node)) {
                changed = true;
            }
            if (outdatedNode) {
                node.setVersion(FlowNode.CURRENT_VERSION);
                changed = true;
            }
        }
        if (migrateConnections(graph, originalTypes)) {
            changed = true;
        }
        return changed;
    }

    private boolean migrateHandlerConfig(FlowNode node) {
        if (nodeDefinitionRegistry == null || node == null || node.getType() == null) {
            return false;
        }
        NodeDefinition definition = nodeDefinitionRegistry.get(node.getType());
        if (definition == null || definition.getHandlerConfig() == null || definition.getHandlerConfig().isEmpty()) {
            return false;
        }
        Map<String, Object> handlerConfig = node.getHandlerConfigValues();
        boolean changed = false;
        for (Map.Entry<String, Object> entry : definition.getHandlerConfig().entrySet()) {
            if (!handlerConfig.containsKey(entry.getKey())) {
                handlerConfig.put(entry.getKey(), entry.getValue());
                changed = true;
            }
        }
        if (changed) {
            node.setHandlerConfig(handlerConfig);
        }
        return changed;
    }

    private boolean migrateNodeValues(FlowNode node, String originalType) {
        if (node == null) {
            return false;
        }
        boolean changed = false;
        Map<String, Object> inputValues = node.getInputValues();
        if (inputValues == null) {
            inputValues = new HashMap<>();
            node.setInputValues(inputValues);
            changed = true;
        }
        if ("player_push".equals(originalType)) {
            if (!"push".equals(inputValues.get("mode"))) {
                inputValues.put("mode", "push");
                changed = true;
            }
        } else if ("entity_kill".equals(originalType)) {
            if (!"do".equals(inputValues.get("action"))) {
                inputValues.put("action", "do");
                changed = true;
            }
        }
        if ("variable.access".equals(node.getType())) {
            changed |= migrateVariableAccessValues(inputValues, originalType);
            changed |= lowerCaseInputValue(inputValues, "mode");
            changed |= lowerCaseInputValue(inputValues, "scope");
        }
        return changed;
    }

    private boolean migrateVariableAccessValues(Map<String, Object> inputValues, String originalType) {
        boolean changed = false;
        changed |= moveInputValue(inputValues, "key", "name");
        changed |= moveInputValue(inputValues, "variable", "name");
        changed |= moveInputValue(inputValues, "variable_name", "name");
        if (originalType == null) {
            return changed;
        }
        switch (originalType) {
            case "variable_set_global" -> {
                changed |= putIfDifferent(inputValues, "mode", "set");
                changed |= putIfDifferent(inputValues, "scope", "global");
            }
            case "variable_set_local" -> {
                changed |= putIfDifferent(inputValues, "mode", "set");
                changed |= putIfDifferent(inputValues, "scope", "local");
            }
            case "variable_set_player" -> {
                changed |= putIfDifferent(inputValues, "mode", "set");
                changed |= putIfDifferent(inputValues, "scope", "player");
            }
            case "variable_get_global" -> {
                changed |= putIfDifferent(inputValues, "mode", "get");
                changed |= putIfDifferent(inputValues, "scope", "global");
            }
            case "variable_get_local" -> {
                changed |= putIfDifferent(inputValues, "mode", "get");
                changed |= putIfDifferent(inputValues, "scope", "local");
            }
            case "variable_get_player" -> {
                changed |= putIfDifferent(inputValues, "mode", "get");
                changed |= putIfDifferent(inputValues, "scope", "player");
            }
            case "variable_delete" -> changed |= putIfDifferent(inputValues, "mode", "delete");
            case "variable_exists" -> changed |= putIfDifferent(inputValues, "mode", "exists");
            case "variable_list_all" -> changed |= putIfDifferent(inputValues, "mode", "list");
            case "variable_increment" -> changed |= putIfDifferent(inputValues, "mode", "increment");
            case "variable_decrement" -> changed |= putIfDifferent(inputValues, "mode", "decrement");
            case "variable_multiply" -> changed |= putIfDifferent(inputValues, "mode", "multiply");
            case "variable_divide" -> changed |= putIfDifferent(inputValues, "mode", "divide");
            default -> {
            }
        }
        return changed;
    }

    private boolean moveInputValue(Map<String, Object> inputValues, String sourceKey, String targetKey) {
        if (!inputValues.containsKey(sourceKey) || inputValues.containsKey(targetKey)) {
            return false;
        }
        inputValues.put(targetKey, inputValues.remove(sourceKey));
        return true;
    }

    private boolean putIfDifferent(Map<String, Object> inputValues, String key, Object value) {
        Object current = inputValues.get(key);
        if (value == null ? current == null : value.equals(current)) {
            return false;
        }
        inputValues.put(key, value);
        return true;
    }

    private boolean lowerCaseInputValue(Map<String, Object> inputValues, String key) {
        Object value = inputValues.get(key);
        if (!(value instanceof String stringValue) || stringValue.isBlank()) {
            return false;
        }
        String normalized = stringValue.toLowerCase(Locale.ROOT);
        if (normalized.equals(stringValue)) {
            return false;
        }
        inputValues.put(key, normalized);
        return true;
    }

    private boolean migrateConnections(FlowGraph graph, Map<String, String> originalTypes) {
        if (graph.getConnections() == null || graph.getConnections().isEmpty()) {
            return false;
        }
        boolean changed = false;
        for (FlowConnection connection : graph.getConnections()) {
            if (connection == null) {
                continue;
            }
            FlowNode sourceNode = graph.getNodes().get(connection.getSourceNodeId());
            FlowNode targetNode = graph.getNodes().get(connection.getTargetNodeId());
            String originalSourceType = originalTypes.get(connection.getSourceNodeId());
            String originalTargetType = originalTypes.get(connection.getTargetNodeId());
            String sourcePin = mapSourcePin(originalSourceType, sourceNode, connection.getSourcePin());
            if (!equals(sourcePin, connection.getSourcePin())) {
                connection.setSourcePin(sourcePin);
                changed = true;
            }
            String targetPin = mapTargetPin(originalTargetType, targetNode, connection.getTargetPin());
            if (!equals(targetPin, connection.getTargetPin())) {
                connection.setTargetPin(targetPin);
                changed = true;
            }
        }
        return changed;
    }

    private String mapSourcePin(String originalType, FlowNode sourceNode, String pin) {
        if (pin == null || pin.isBlank()) {
            return pin;
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
        return pin;
    }

    private String mapTargetPin(String originalType, FlowNode targetNode, String pin) {
        if (pin == null || pin.isBlank()) {
            return pin;
        }
        String targetType = targetNode != null ? targetNode.getType() : null;
        if ("entity_kill".equals(originalType) && "entity.kill".equals(targetType) && "entity".equals(pin)) {
            return "target";
        }
        if ("variable.access".equals(targetType)) {
            return switch (pin) {
                case "key", "variable", "variable_name" -> "name";
                default -> pin;
            };
        }
        return pin;
    }

    private boolean equals(String first, String second) {
        return first == null ? second == null : first.equals(second);
    }
}
