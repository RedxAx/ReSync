package restudio.resync.flow.migration;

import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowConnection;
import restudio.flow.data.FlowNode;
import restudio.flow.data.FlowResourceReference;
import restudio.flow.data.FlowSerializer;
import restudio.flow.data.FlowVariable;
import restudio.resync.Log;
import restudio.resync.flow.FlowStorage;
import restudio.resync.flow.registry.NodeDefinition;
import restudio.resync.flow.registry.NodeDefinitionRegistry;
import restudio.resync.storage.MigrationLedger;
import restudio.resync.storage.StorageSafety;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class FlowGraphMigrator {
    private static final String PASSTHROUGH_OUTPUT_PREFIX = "__passthrough:";
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
    private final FlowStorage storage;
    private final NodeDefinitionRegistry nodeDefinitionRegistry;
    private final IdCompatibilityLayer idCompatibility;
    private volatile FlowMigrationReport lastReport = FlowMigrationReport.empty();

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
        String migrationId = "flow-v" + FlowGraph.CURRENT_VERSION;
        int scanned = 0;
        int migrated = 0;
        int failed = 0;
        int changedNodes = 0;
        ArrayList<String> changedGraphIds = new ArrayList<>();
        ArrayList<String> failedGraphIds = new ArrayList<>();
        try (MigrationLedger.Fence fence = MigrationLedger.acquireFence(storage.getAssetsPath())) {
            MigrationLedger ledger = new MigrationLedger(storage.getAssetsPath());
            for (String flowId : storage.listFlowIds()) {
                scanned++;
                boolean legacyGraphFile = !storage.hasStoredGraphVersion(flowId);
                String storedType = storage.getGraphResourceType(flowId);
                FlowGraph graph = storedType.isBlank() ? storage.getGraph(flowId) : storage.getGraph(storedType, flowId);
                if (graph == null) {
                    continue;
                }
                String sourceHash = StorageSafety.sha256(FlowSerializer.serialize(graph));
                if (ledger.isCommitted(migrationId, flowId, sourceHash)) {
                    continue;
                }
                try {
                    FlowGraph candidate = FlowSerializer.deserialize(FlowSerializer.serialize(graph));
                    GraphMigrationResult result = migrateGraphDetailed(candidate, legacyGraphFile);
                    if (result.changed()) {
                        ledger.prepare(migrationId, flowId, graph.getResourceType(), graph.getResourceRevision(), sourceHash, FlowGraph.CURRENT_VERSION, fence.epoch());
                        storage.backupGraphForMigration(flowId, migrationId + "-" + sourceHash.substring(0, 12));
                        storage.saveGraph(candidate);
                        ledger.commit(migrationId, flowId, sourceHash);
                        migrated++;
                        changedNodes += result.changedNodes();
                        changedGraphIds.add(flowId);
                    } else {
                        ledger.prepare(migrationId, flowId, graph.getResourceType(), graph.getResourceRevision(), sourceHash, FlowGraph.CURRENT_VERSION, fence.epoch());
                        ledger.commit(migrationId, flowId, sourceHash);
                    }
                } catch (RuntimeException | IOException exception) {
                    try {
                        ledger.fail(migrationId, flowId, sourceHash, exception.getMessage());
                    } catch (IOException ledgerFailure) {
                        exception.addSuppressed(ledgerFailure);
                    }
                    failed++;
                    failedGraphIds.add(flowId);
                    Log.warn("[FlowGraphMigrator] Failed to migrate " + flowId + ": " + exception.getMessage());
                }
            }
        } catch (IOException exception) {
            failed++;
            Log.warn("[FlowGraphMigrator] Migration fence unavailable: " + exception.getMessage());
        }
        lastReport = new FlowMigrationReport(migrationId, scanned, migrated, failed, changedNodes, changedGraphIds, failedGraphIds);
        if (migrated > 0 || failed > 0) {
            Log.info("[FlowGraphMigrator] Scanned " + scanned + ", migrated " + migrated + ", changed nodes " + changedNodes + ", failed " + failed);
        }
    }

    public FlowMigrationReport getLastReport() {
        return lastReport;
    }

    public boolean migrateGraph(FlowGraph graph) {
        return migrateGraph(graph, false);
    }

    public boolean migrateGraph(FlowGraph graph, boolean forceLegacyGraph) {
        return migrateGraphDetailed(graph, forceLegacyGraph).changed();
    }

    private GraphMigrationResult migrateGraphDetailed(FlowGraph graph, boolean forceLegacyGraph) {
        if (graph == null) {
            return new GraphMigrationResult(false, 0);
        }
        boolean changed = false;
        int changedNodes = 0;
        boolean legacyGraph = forceLegacyGraph || graph.getVersion() <= 0;
        boolean outdatedGraph = forceLegacyGraph || graph.getVersion() < FlowGraph.CURRENT_VERSION;
        boolean malformedPassthroughGraph = hasRawPassthroughConnections(graph);
        if (outdatedGraph) {
            graph.setVersion(FlowGraph.CURRENT_VERSION);
            migrateGraphMetadata(graph);
            changed = true;
        }
        Map<String, FlowNode> nodes = graph.getNodes();
        if (nodes == null || nodes.isEmpty()) {
            return new GraphMigrationResult(changed, 0);
        }
        Map<String, String> originalTypes = new HashMap<>();
        for (Map.Entry<String, FlowNode> entry : nodes.entrySet()) {
            FlowNode node = entry.getValue();
            if (node == null) {
                continue;
            }
            String originalType = node.getType();
            boolean nodeChanged = false;
            originalTypes.put(entry.getKey(), originalType);
            boolean legacyNode = node.getVersion() <= 0;
            String mappedType = idCompatibility.mapToNew(originalType);
            if (mappedType != null && !mappedType.equals(originalType) && (legacyGraph || legacyNode || idCompatibility.hasMapping(originalType))) {
                node.setType(mappedType);
                changed = true;
                nodeChanged = true;
            }
            if (migrateNodeValues(graph, node, originalType, outdatedGraph, malformedPassthroughGraph)) {
                changed = true;
                nodeChanged = true;
            }
            if (migrateHandlerConfig(node)) {
                changed = true;
                nodeChanged = true;
            }
            int targetVersion = targetNodeVersion(node);
            if (node.getVersion() < targetVersion) {
                node.setVersion(targetVersion);
                changed = true;
                nodeChanged = true;
            }
            if (nodeChanged) {
                changedNodes++;
            }
        }
        if (migrateConnections(graph, originalTypes, outdatedGraph)) {
            changed = true;
        }
        return new GraphMigrationResult(changed, changedNodes);
    }

    private void migrateGraphMetadata(FlowGraph graph) {
        graph.setFunctionOwner(graph.getFunctionOwner());
        graph.setFunctionNamespace(graph.getFunctionNamespace());
        graph.setFunctionVersion(graph.getFunctionVersion());
        graph.setFunctionDescription(graph.getFunctionDescription());
        if (graph.getFunctionInputs() != null) {
            for (FlowGraph.FunctionParameter parameter : graph.getFunctionInputs()) {
                if (parameter != null) {
                    parameter.setTypeRef(parameter.getTypeRef().normalizedGenerics());
                }
            }
        }
        if (graph.getFunctionOutputs() != null) {
            for (FlowGraph.FunctionParameter parameter : graph.getFunctionOutputs()) {
                if (parameter != null) {
                    parameter.setTypeRef(parameter.getTypeRef().normalizedGenerics());
                }
            }
        }
        if (graph.getLocalVariables() != null) {
            for (FlowVariable variable : graph.getLocalVariables()) {
                if (variable == null) {
                    continue;
                }
                variable.setScope(variable.getScope());
                variable.setLifetime(variable.getLifetime());
                variable.setOwner(variable.getOwner());
                variable.setAbsencePolicy(variable.getAbsencePolicy());
                variable.setConcurrencyPolicy(variable.getConcurrencyPolicy());
            }
        }
    }

    private int targetNodeVersion(FlowNode node) {
        if (nodeDefinitionRegistry == null || node == null || node.getType() == null) {
            return FlowNode.CURRENT_VERSION;
        }
        NodeDefinition definition = nodeDefinitionRegistry.get(node.getType());
        return definition != null ? Math.max(FlowNode.CURRENT_VERSION, definition.getSchemaVersion()) : FlowNode.CURRENT_VERSION;
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

    private boolean migrateNodeValues(FlowGraph graph, FlowNode node, String originalType, boolean outdatedGraph, boolean malformedPassthroughGraph) {
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
        }
        if (outdatedGraph && "entity.kill".equals(node.getType()) && inputValues.remove("action") != null) {
            changed = true;
        }
        if ((outdatedGraph || node.getVersion() < targetNodeVersion(node) || malformedPassthroughGraph) && "if".equals(node.getType()) && !inputValues.containsKey("condition")) {
            String nodeId = graph != null ? graph.findNodeId(node) : null;
            boolean connected = graph != null && nodeId != null && graph.getConnections().stream().anyMatch(connection -> nodeId.equals(connection.getTargetNodeId()) && "condition".equals(connection.getTargetPin()));
            if (!connected) {
                inputValues.put("condition", false);
                changed = true;
            }
        }
        if ("custom_content.item".equals(node.getType()) || "custom_content.block".equals(node.getType()) || "custom_content.armor".equals(node.getType())) {
            Object legacyArmorSlot = inputValues.remove("armor_slot");
            if (legacyArmorSlot != null) {
                changed = true;
            }
            if ("custom_content.armor".equals(node.getType()) && graph != null && !graph.getContentProperties().containsKey("armor_slot")) {
                String armorSlot = legacyArmorSlot != null && !String.valueOf(legacyArmorSlot).isBlank() ? String.valueOf(legacyArmorSlot) : "chest";
                graph.getContentProperties().put("armor_slot", armorSlot);
                changed = true;
            }
        }
        String scheduleType = switch (originalType != null ? originalType : "") {
            case "misc.delay", "delay.ticks" -> "schedule.wait_ticks";
            case "delay", "delay.seconds" -> "schedule.delay";
            case "cancel.schedule" -> "schedule.cancel_task";
            default -> null;
        };
        if (scheduleType != null) {
            if (!scheduleType.equals(node.getType())) {
                node.setType(scheduleType);
                changed = true;
            }
            String operation = switch (scheduleType) {
                case "schedule.wait_ticks" -> "wait_ticks";
                case "schedule.cancel_task" -> "cancel_task";
                default -> "delay";
            };
            Map<String, Object> handlerConfig = node.getHandlerConfigValues();
            if (!operation.equals(handlerConfig.get("operation"))) {
                handlerConfig.put("operation", operation);
                node.setHandlerConfig(handlerConfig);
                changed = true;
            }
        }
        String particleMode = originalType != null ? LEGACY_PARTICLE_MODES.get(originalType) : null;
        if (particleMode == null && node.getType() != null) particleMode = LEGACY_PARTICLE_MODES.get(node.getType());
        if (particleMode != null) {
            if (!"particle.apply".equals(node.getType())) {
                node.setType("particle.apply");
                changed = true;
            }
            changed |= putIfDifferent(inputValues, "mode", particleMode);
            changed |= moveInputValue(inputValues, "particle_type", "particle");
            changed |= moveInputValue(inputValues, "center_location", "location");
            changed |= moveInputValue(inputValues, "is_filled", "filled");
            changed |= moveInputValue(inputValues, "points", "count");
            Map<String, Object> handlerConfig = node.getHandlerConfigValues();
            if (!"particle_apply".equals(handlerConfig.get("operation"))) {
                handlerConfig.put("operation", "particle_apply");
                node.setHandlerConfig(handlerConfig);
                changed = true;
            }
        }
        if ("variable.access".equals(node.getType())) {
            changed |= migrateVariableAccessValues(inputValues, originalType);
            changed |= lowerCaseInputValue(inputValues, "mode");
            changed |= lowerCaseInputValue(inputValues, "scope");
        }
        if ("permission.perm_has".equals(node.getType()) || "perm.check".equals(node.getType())) {
            changed |= migrateRepeatableCount(inputValues, "__permission_count", "__repeatable_count:permissions");
        }
        changed |= migrateTimeValues(inputValues, originalType, node.getType());
        changed |= migrateTypedColorValues(node, inputValues);
        changed |= migrateResourceReferences(node.getType(), inputValues);
        return changed;
    }

    private boolean migrateTimeValues(Map<String, Object> inputValues, String originalType, String currentType) {
        if (originalType == null) {
            return false;
        }
        boolean changed = false;
        switch (originalType) {
            case "misc.time_format" -> {
                changed |= moveInputValue(inputValues, "timestamp_ms", "time");
                changed |= moveInputValue(inputValues, "format_pattern", "format");
            }
            case "misc.time_parse" -> {
                changed |= moveInputValue(inputValues, "date_string", "string");
                changed |= moveInputValue(inputValues, "format_pattern", "format");
            }
            case "misc.time_add" -> changed |= moveInputValue(inputValues, "timestamp_ms", "time");
            case "misc.time_diff" -> {
                changed |= moveInputValue(inputValues, "timestamp1_ms", "time1");
                changed |= moveInputValue(inputValues, "timestamp2_ms", "time2");
            }
            default -> {}
        }
        if (Set.of("time.format", "time.parse", "time.add", "schedule.schedule", "schedule.cron", "schedule.at.time").contains(currentType)) {
            Object zone = inputValues.get("time_zone");
            if (zone == null || zone.toString().isBlank()) {
                inputValues.put("time_zone", "UTC");
                changed = true;
            }
        }
        return changed;
    }

    private boolean migrateTypedColorValues(FlowNode node, Map<String, Object> inputValues) {
        if (nodeDefinitionRegistry == null || node == null || node.getType() == null) {
            return false;
        }
        NodeDefinition definition = nodeDefinitionRegistry.get(node.getType());
        if (definition == null) {
            return false;
        }
        boolean changed = false;
        for (NodeDefinition.PinDefinition input : definition.getInputs()) {
            Object value = inputValues.get(input.getName());
            if (value == null) {
                continue;
            }
            String typeId = input.getTypeRef().getTypeId();
            Object normalized = switch (typeId) {
                case "named_text_color" -> normalizeNamedColor(value);
                case "rgb_color" -> normalizeRgbColor(value);
                default -> value;
            };
            if (!normalized.equals(value)) {
                inputValues.put(input.getName(), normalized);
                changed = true;
            }
        }
        return changed;
    }

    private Object normalizeNamedColor(Object value) {
        if (!(value instanceof String text)) {
            return value;
        }
        String normalized = text.strip().toLowerCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        if (normalized.length() == 2 && (normalized.charAt(0) == '&' || normalized.charAt(0) == '§')) {
            return LEGACY_NAMED_COLORS.getOrDefault(normalized.charAt(1), value.toString());
        }
        return NAMED_COLOR_RGB.containsKey(normalized) ? normalized : value;
    }

    private Object normalizeRgbColor(Object value) {
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
            normalized = "" + normalized.charAt(0) + normalized.charAt(0) + normalized.charAt(1) + normalized.charAt(1)
                + normalized.charAt(2) + normalized.charAt(2);
        }
        return normalized.matches("(?i)[0-9a-f]{6}") ? "#" + normalized.toUpperCase(Locale.ROOT) : value;
    }

    private boolean migrateResourceReferences(String nodeType, Map<String, Object> inputValues) {
        if (nodeType == null) {
            return false;
        }
        return switch (nodeType) {
            case "loot.generate", "loot.give", "loot.fill_container" -> migrateResourceReference(inputValues, "loot_table", "loot_table");
            case "trade.apply_trade_profile", "trade.open_trades" -> migrateResourceReference(inputValues, "profile_id", "trade_profile");
            case "npc.spawn", "npc.despawn", "npc.open" -> migrateResourceReference(inputValues, "npc_id", "npc_definition");
            case "npc.set_profile" -> migrateResourceReference(inputValues, "npc_id", "npc_definition")
                | migrateResourceReference(inputValues, "profile_id", "trade_profile");
            case "network.get.server.health", "network.server.mode" -> migrateResourceReference(inputValues, "node_id", "network_node");
            case "network.player.send" -> migrateResourceReference(inputValues, "server", "network_route");
            case "network.player.handoff" -> migrateResourceReference(inputValues, "target_node", "network_node")
                | migrateResourceReference(inputValues, "server", "network_route");
            case "scoreboard.show.template" -> migrateResourceReference(inputValues, "scoreboard_id", "scoreboard");
            default -> false;
        };
    }

    private boolean migrateResourceReference(Map<String, Object> inputValues, String pin, String kind) {
        Object value = inputValues.get(pin);
        if (!(value instanceof String id) || id.isBlank()) {
            return false;
        }
        inputValues.put(pin, new FlowResourceReference(kind, id, "builtin"));
        return true;
    }

    private boolean migrateRepeatableCount(Map<String, Object> inputValues, String legacyKey, String targetKey) {
        if (!inputValues.containsKey(legacyKey)) {
            return false;
        }
        Object value = inputValues.remove(legacyKey);
        inputValues.putIfAbsent(targetKey, value);
        return true;
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
            default -> { return changed; }
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

    private boolean migrateConnections(FlowGraph graph, Map<String, String> originalTypes, boolean outdatedGraph) {
        if (graph.getConnections() == null || graph.getConnections().isEmpty()) {
            return false;
        }
        boolean changed = false;
        for (FlowConnection connection : graph.getConnections()) {
            if (connection == null) {
                continue;
            }
            if (normalizePassthroughSource(graph, connection)) {
                changed = true;
            }
            FlowNode sourceNode = graph.getNodes().get(connection.getSourceNodeId());
            FlowNode targetNode = graph.getNodes().get(connection.getTargetNodeId());
            String originalSourceType = originalTypes.get(connection.getSourceNodeId());
            String originalTargetType = originalTypes.get(connection.getTargetNodeId());
            if (isLegacyBreakContinuation(originalSourceType, sourceNode, connection.getSourcePin())) {
                String loopNodeId = enclosingLoopNodeId(graph, connection.getSourceNodeId());
                if (loopNodeId != null) {
                    connection.setSourceNodeId(loopNodeId);
                    connection.setSourcePin("done");
                    sourceNode = graph.getNodes().get(loopNodeId);
                    originalSourceType = originalTypes.get(loopNodeId);
                    changed = true;
                }
            }
            String sourcePin = mapSourcePin(originalSourceType, sourceNode, connection.getSourcePin(), outdatedGraph);
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
        if (changed) {
            graph.setConnections(new ArrayList<>(graph.getConnections()));
        }
        return changed;
    }

    private boolean normalizePassthroughSource(FlowGraph graph, FlowConnection connection) {
        String editorNodeId = connection.getEditorSourceNodeId();
        String editorPin = connection.getEditorSourcePin();
        boolean rawPassthrough = passthrough(connection.getSourcePin());
        if (!rawPassthrough && !passthrough(editorPin)) {
            return false;
        }
        String visibleNodeId = rawPassthrough ? connection.getSourceNodeId() : editorNodeId;
        String visiblePin = rawPassthrough ? connection.getSourcePin() : editorPin;
        ResolvedSource resolved = resolvePassthroughSource(graph, visibleNodeId, visiblePin, new HashSet<>());
        if (resolved == null || passthrough(resolved.pin())) {
            return false;
        }
        boolean changed = !equals(resolved.nodeId(), connection.getSourceNodeId()) || !equals(resolved.pin(), connection.getSourcePin());
        connection.setSourceNodeId(resolved.nodeId());
        connection.setSourcePin(resolved.pin());
        if (rawPassthrough) {
            connection.setEditorSourceNodeId(visibleNodeId);
            connection.setEditorSourcePin(visiblePin);
            changed = true;
        }
        return changed;
    }

    private ResolvedSource resolvePassthroughSource(FlowGraph graph, String nodeId, String pin, Set<String> visited) {
        if (!passthrough(pin)) {
            return new ResolvedSource(nodeId, pin);
        }
        if (nodeId == null || !visited.add(nodeId + ':' + pin)) {
            return null;
        }
        String inputPin = pin.substring(PASSTHROUGH_OUTPUT_PREFIX.length());
        FlowConnection incoming = graph.getConnections().stream().filter(connection -> connection != null && nodeId.equals(connection.getTargetNodeId()) && inputPin.equals(connection.getTargetPin())).findFirst().orElse(null);
        if (incoming == null) {
            return null;
        }
        String nextNodeId = passthrough(incoming.getEditorSourcePin()) ? incoming.getEditorSourceNodeId() : incoming.getSourceNodeId();
        String nextPin = passthrough(incoming.getEditorSourcePin()) ? incoming.getEditorSourcePin() : incoming.getSourcePin();
        return resolvePassthroughSource(graph, nextNodeId, nextPin, visited);
    }

    private boolean passthrough(String pin) {
        return pin != null && pin.startsWith(PASSTHROUGH_OUTPUT_PREFIX);
    }

    private boolean hasRawPassthroughConnections(FlowGraph graph) {
        return graph != null && graph.getConnections().stream().filter(connection -> connection != null).anyMatch(connection -> passthrough(connection.getSourcePin()));
    }

    private boolean isLegacyBreakContinuation(String originalType, FlowNode sourceNode, String pin) {
        String currentType = sourceNode != null ? sourceNode.getType() : null;
        return ("break.loop".equals(originalType) || "break_loop".equals(originalType) || "break.loop".equals(currentType))
            && ("flow".equals(pin) || "next".equals(pin));
    }

    private String enclosingLoopNodeId(FlowGraph graph, String nodeId) {
        ArrayList<String> pending = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        pending.add(nodeId);
        visited.add(nodeId);
        for (int index = 0; index < pending.size(); index++) {
            String current = pending.get(index);
            for (FlowConnection connection : graph.getConnections()) {
                if (connection == null || !current.equals(connection.getTargetNodeId()) || !isExecutionInputPin(connection.getTargetPin())) {
                    continue;
                }
                FlowNode source = graph.getNodes().get(connection.getSourceNodeId());
                if (source != null && isLoopBodyOutput(source.getType(), connection.getSourcePin())) {
                    return connection.getSourceNodeId();
                }
                if (visited.add(connection.getSourceNodeId())) {
                    pending.add(connection.getSourceNodeId());
                }
            }
        }
        return null;
    }

    private boolean isExecutionInputPin(String pin) {
        return "flow".equals(pin) || "next".equals(pin);
    }

    private boolean isLoopBodyOutput(String type, String pin) {
        if (!"flow".equals(pin) && !"loop".equals(pin)) {
            return false;
        }
        return "loop_while".equals(type) || "loop.count".equals(type) || "loop.for.each".equals(type)
            || "loop.for.each.player".equals(type) || "loop.for.each.entity".equals(type);
    }

    private String mapSourcePin(String originalType, FlowNode sourceNode, String pin, boolean outdatedGraph) {
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
        if (originalType != null) {
            return switch (originalType) {
                case "loop_while" -> outdatedGraph && "completed".equals(pin) ? "done" : "flow".equals(pin) ? "loop" : pin;
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
                     "particle_explosion", "particle.explosion" -> "next".equals(pin) ? "flow" : pin;
                default -> pin;
            };
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
        if (originalType != null) {
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
        return pin;
    }

    private boolean equals(String first, String second) {
        return first == null ? second == null : first.equals(second);
    }

    private record ResolvedSource(String nodeId, String pin) {
    }

    private record GraphMigrationResult(boolean changed, int changedNodes) {
    }
}
