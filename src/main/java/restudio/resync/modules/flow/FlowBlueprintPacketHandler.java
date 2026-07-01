package restudio.resync.modules.flow;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import restudio.resync.Log;
import restudio.flow.data.CustomContentDefinition;
import restudio.flow.data.CustomContentGraphAdapter;
import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowNode;
import restudio.flow.data.FlowSerializer;
import restudio.resync.customcontent.CustomContentAccess;
import restudio.resync.customcontent.CustomContentService;
import restudio.resync.customcontent.CustomContentStorage;
import restudio.resync.core.Session;
import restudio.resync.flow.FlowStorage;
import restudio.resync.flow.GlobalTriggers;
import restudio.resync.flow.migration.FlowGraphMigrator;
import restudio.resync.flow.registry.NodeDefinitionRegistry;
import restudio.resync.flow.triggers.TriggerBinding;
import restudio.resync.flow.triggers.TriggerRegistry;
import restudio.resync.flow.triggers.TriggerType;
import restudio.resync.jobs.JobRecord;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

public class FlowBlueprintPacketHandler {
    private static final Set<String> DIRECT_DISPATCH_EVENT_NODES = Set.of("resync_command", "click");
    private final FlowStorage storage;
    private final TriggerRegistry triggerRegistry;
    private final GlobalTriggers globalTriggers;
    private final NodeDefinitionRegistry definitionRegistry;
    private final FlowPacketSender sender;
    private final Gson gson = new Gson();

    public FlowBlueprintPacketHandler(FlowStorage storage, TriggerRegistry triggerRegistry, GlobalTriggers globalTriggers, FlowPacketSender sender) {
        this(storage, triggerRegistry, globalTriggers, NodeDefinitionRegistry.getInstance(), sender);
    }

    public FlowBlueprintPacketHandler(FlowStorage storage, TriggerRegistry triggerRegistry, GlobalTriggers globalTriggers, NodeDefinitionRegistry definitionRegistry, FlowPacketSender sender) {
        this.storage = storage;
        this.triggerRegistry = triggerRegistry;
        this.globalTriggers = globalTriggers;
        this.definitionRegistry = definitionRegistry;
        this.sender = sender;
        refreshAllGraphBindings();
    }

    public void handleRequest(Session session, ByteBuffer buffer) {
        if (!buffer.hasRemaining()) {
            sender.sendError(session, "INVALID_REQUEST", "Flow ID not provided");
            return;
        }
        byte[] idBytes = new byte[buffer.remaining()];
        buffer.get(idBytes);
        String flowId = new String(idBytes, StandardCharsets.UTF_8);
        if (flowId.length() > FlowPacketSender.MAX_STRING_LENGTH) {
            sender.sendError(session, "INVALID_FLOW_ID", "Flow ID too long");
            return;
        }
        FlowGraph graph = storage.getGraph(flowId);
        if (graph != null) {
            sender.sendFlowData(session, graph);
        } else {
            sender.sendError(session, "FLOW_NOT_FOUND", "Flow not found: " + flowId);
        }
    }

    public void handleSave(Session session, ByteBuffer buffer) {
        if (!buffer.hasRemaining()) {
            sender.sendError(session, "INVALID_SAVE", "No data provided");
            return;
        }
        if (buffer.remaining() > FlowPacketSender.MAX_PACKET_SIZE) {
            sender.sendError(session, "SAVE_TOO_LARGE", "Save data exceeds maximum size");
            return;
        }
        FlowMutationPayload payload = FlowMutationPayloadReader.read(buffer);
        String json = payload.payload();
        JobRecord<String> job = sender.beginJob(session, "saveFlow", "", payload.requestId());
        if (job == null) {
            return;
        }
        FlowGraph previousGraph = null;
        Map<String, CustomContentDefinition> previousContent = Map.of();
        List<TriggerBinding> previousTriggers = triggerRegistry != null ? triggerRegistry.getBindings() : List.of();
        String rollbackFlowId = null;
        try {
            FlowGraph graph = FlowSerializer.deserialize(json);
            if (graph == null) {
                sender.failJob(job, "Failed to parse flow graph", null);
                sender.sendError(session, "INVALID_GRAPH", "Failed to parse flow graph");
                return;
            }
            if (graph.getId() == null) {
                graph.setId(UUID.randomUUID().toString());
            }
            new FlowGraphMigrator(storage, definitionRegistry).migrateGraph(graph);
            String flowId = graph.getId().toString();
            rollbackFlowId = flowId;
            previousGraph = storage.getGraph(flowId);
            CustomContentStorage customContentStorage = CustomContentAccess.getStorage();
            CustomContentDefinition content = CustomContentGraphAdapter.toDefinition(graph);
            if (content != null) {
                if (customContentStorage != null) {
                    previousContent = customContentStorage.getByFlow(flowId).stream()
                        .collect(Collectors.toMap(CustomContentDefinition::getId, Function.identity(), (left, right) -> left));
                    CustomContentService customContentService = CustomContentAccess.getService();
                    customContentStorage.save(content);
                    if (customContentService != null) {
                        customContentService.reconcileContentItems(content.getId());
                    }
                    for (String previousContentId : previousContent.keySet()) {
                        if (!previousContentId.equalsIgnoreCase(content.getId())) {
                            customContentStorage.delete(previousContentId);
                            if (customContentService != null) {
                                customContentService.clearContentItems(previousContentId);
                            }
                        }
                    }
                }
                Log.fine("Custom content saved from flow: " + content.getId());
                sender.sendFlowSaveAck(session, flowId);
                sender.succeedJob(job, flowId, "Saved");
                return;
            }
            storage.saveGraph(graph);
            if (customContentStorage != null) {
                previousContent = customContentStorage.getByFlow(flowId).stream()
                    .collect(Collectors.toMap(CustomContentDefinition::getId, Function.identity(), (left, right) -> left));
            }
            updateGraphBindings(graph);
            Log.fine("Flow saved: " + flowId);
            sender.sendFlowSaveAck(session, flowId);
            sender.succeedJob(job, flowId, "Saved");
        } catch (Exception e) {
            restoreFlowSave(rollbackFlowId, previousGraph, previousContent, previousTriggers);
            sender.failJob(job, e.getMessage(), e);
            sender.sendError(session, "SAVE_FAILED", "Failed to save flow: " + e.getMessage());
            Log.error("Flow save error: " + e.getMessage());
        }
    }

    public void handleDelete(Session session, ByteBuffer buffer) {
        if (!buffer.hasRemaining()) {
            sender.sendError(session, "INVALID_DELETE", "Flow ID not provided");
            return;
        }
        JobRecord<String> job = null;
        FlowGraph previousGraph = null;
        Map<String, CustomContentDefinition> previousContent = Map.of();
        List<TriggerBinding> previousTriggers = triggerRegistry != null ? triggerRegistry.getBindings() : List.of();
        String rollbackFlowId = null;

        try {
            FlowMutationPayload payload = FlowMutationPayloadReader.read(buffer);
            String flowId = payload.payload();
            rollbackFlowId = flowId;
            job = sender.beginJob(session, "deleteFlow", flowId, payload.requestId());
            if (job == null) {
                return;
            }
            previousGraph = storage.getGraph(flowId);
            storage.deleteGraph(flowId);
            CustomContentStorage customContentStorage = CustomContentAccess.getStorage();
            if (customContentStorage != null) {
                previousContent = customContentStorage.getByFlow(flowId).stream()
                    .collect(Collectors.toMap(CustomContentDefinition::getId, Function.identity(), (left, right) -> left));
                for (CustomContentDefinition definition : customContentStorage.getByFlow(flowId)) {
                    customContentStorage.delete(definition.getId());
                    CustomContentService customContentService = CustomContentAccess.getService();
                    if (customContentService != null) {
                        customContentService.clearContentItems(definition.getId());
                    }
                }
            }
            if (triggerRegistry != null) {
                triggerRegistry.removeFlowBindings(flowId);
            }
            if (globalTriggers != null) {
                globalTriggers.refreshBindings();
            }
            Log.fine("Flow deleted: " + flowId);
            sender.succeedJob(job, flowId, "Deleted");
        } catch (Exception e) {
            restoreFlowSave(rollbackFlowId, previousGraph, previousContent, previousTriggers);
            sender.failJob(job, e.getMessage(), e);
            sender.sendError(session, "DELETE_FAILED", "Failed to delete flow: " + e.getMessage());
            Log.error("Flow delete error: " + e.getMessage());
        }
    }

    public void handleListRequest(Session session) {
        sender.sendFlowList(session, storage.listFlowIds());
    }

    public void handleTriggerUpdate(Session session, ByteBuffer buffer) {
        if (!buffer.hasRemaining() || triggerRegistry == null) {
            return;
        }
        FlowMutationPayload payload = FlowMutationPayloadReader.read(buffer);
        JobRecord<String> job = sender.beginJob(session, "updateTriggers", "", payload.requestId());
        if (job == null) {
            return;
        }
        String json = payload.payload();
        try {
            List<TriggerBinding> bindings = gson.fromJson(json, new TypeToken<List<TriggerBinding>>() {
            }.getType());
            triggerRegistry.setBindingsPreservingType(bindings, TriggerType.EVENT);
            if (globalTriggers != null) {
                globalTriggers.refreshBindings();
            }
            sender.succeedJob(job, "triggers", "Saved");
        } catch (Exception e) {
            sender.failJob(job, e.getMessage(), e);
            sender.sendError(session, "TRIGGER_UPDATE_FAILED", "Failed to update triggers: " + e.getMessage());
        }
    }

    private void refreshAllGraphBindings() {
        if (triggerRegistry == null) {
            return;
        }
        for (String flowId : storage.listFlowIds()) {
            FlowGraph graph = storage.getGraph(flowId);
            if (graph != null) {
                updateGraphBindings(graph);
            }
        }
        if (globalTriggers != null) {
            globalTriggers.refreshBindings();
        }
    }

    private void updateGraphBindings(FlowGraph graph) {
        if (triggerRegistry == null || graph == null || graph.getId() == null || graph.getId().isBlank() || graph.getNodes() == null) {
            return;
        }
        updateEventBindings(graph);
        updateCommandBindings(graph);
        if (globalTriggers != null) {
            globalTriggers.refreshBindings();
        }
    }

    private void updateEventBindings(FlowGraph graph) {
        Set<String> contexts = new HashSet<>();
        for (var entry : graph.getNodes().entrySet()) {
            FlowNode node = entry.getValue();
            if (node == null) {
                continue;
            }
            String context = mapEventContext(node.getType());
            if (context != null) {
                contexts.add(context);
            }
        }
        List<TriggerBinding> bindings = new ArrayList<>();
        String flowId = graph.getId();
        for (String context : contexts) {
            bindings.add(new TriggerBinding(flowId + ':' + context, flowId, TriggerType.EVENT, context));
        }
        triggerRegistry.replaceFlowBindings(flowId, TriggerType.EVENT, bindings);
    }

    private void updateCommandBindings(FlowGraph graph) {
        String flowId = graph.getId();
        List<TriggerBinding> existing = triggerRegistry.getBindings(TriggerType.COMMAND).stream()
            .filter(binding -> flowId.equals(binding.getFlowId()))
            .toList();
        List<TriggerBinding> bindings = new ArrayList<>();
        int index = 0;
        for (var entry : graph.getNodes().entrySet()) {
            FlowNode node = entry.getValue();
            if (node == null || !isCommandNode(node.getType())) {
                continue;
            }
            String bindingId = flowId + ":command:" + entry.getKey();
            String context = commandContext(node, existing, bindingId, index);
            if (context == null || context.isBlank()) {
                continue;
            }
            bindings.add(new TriggerBinding(bindingId, flowId, TriggerType.COMMAND, context));
            index++;
        }
        triggerRegistry.replaceFlowBindings(flowId, TriggerType.COMMAND, bindings);
    }

    private boolean isCommandNode(String nodeType) {
        if (nodeType == null) {
            return false;
        }
        String normalized = nodeType.trim().toLowerCase(Locale.ROOT);
        return "event.resync.command".equals(normalized) || "event:resync_command".equals(normalized);
    }

    private String commandContext(FlowNode node, List<TriggerBinding> existing, String bindingId, int index) {
        Map<String, Object> values = new HashMap<>(node.getHandlerConfigValues());
        if (node.getInputValues() != null) {
            values.putAll(node.getInputValues());
        }
        String command = text(values, "command");
        if (command.isBlank()) {
            command = text(values, "label");
        }
        if (command.isBlank()) {
            command = text(values, "name");
        }
        List<String> subcommands = stringList(values.get("subcommands"));
        if (subcommands.isEmpty()) {
            subcommands = stringList(values.get("allowedSubcommands"));
        }
        boolean structured = bool(values.get("structured"));
        if (!command.isBlank()) {
            return gson.toJson(Map.of("command", command, "subcommands", subcommands, "structured", structured));
        }
        for (TriggerBinding binding : existing) {
            if (bindingId.equals(binding.getId()) && binding.getContext() != null && !binding.getContext().isBlank()) {
                return binding.getContext();
            }
        }
        if (index < existing.size() && existing.get(index).getContext() != null && !existing.get(index).getContext().isBlank()) {
            return existing.get(index).getContext();
        }
        return "";
    }

    private String text(Map<String, Object> values, String key) {
        Object value = values.get(key);
        return value != null ? value.toString().trim() : "";
    }

    private boolean bool(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value != null && Boolean.parseBoolean(value.toString());
    }

    private List<String> stringList(Object value) {
        if (value instanceof List<?> list) {
            List<String> result = new ArrayList<>();
            for (Object entry : list) {
                if (entry != null && !entry.toString().isBlank()) {
                    result.add(entry.toString().trim());
                }
            }
            return result;
        }
        if (value instanceof String text && !text.isBlank()) {
            return List.of(text);
        }
        return List.of();
    }

    private void restoreFlowSave(String flowId, FlowGraph previousGraph, Map<String, CustomContentDefinition> previousContent, List<TriggerBinding> previousTriggers) {
        try {
            if (previousGraph != null && previousGraph.getId() != null) {
                storage.saveGraph(previousGraph);
            } else if (flowId != null && !flowId.isBlank()) {
                storage.deleteGraph(flowId);
            }
            CustomContentStorage customContentStorage = CustomContentAccess.getStorage();
            if (customContentStorage != null && previousContent != null) {
                for (CustomContentDefinition current : customContentStorage.getByFlow(flowId)) {
                    if (current != null && current.getId() != null && !previousContent.containsKey(current.getId())) {
                        customContentStorage.delete(current.getId());
                    }
                }
                for (CustomContentDefinition definition : previousContent.values()) {
                    customContentStorage.save(definition);
                }
            }
            if (triggerRegistry != null && previousTriggers != null) {
                triggerRegistry.setBindings(previousTriggers);
            }
            if (globalTriggers != null) {
                globalTriggers.refreshBindings();
            }
        } catch (Exception restoreError) {
            Log.error("Flow rollback failed: " + restoreError.getMessage());
        }
    }

    private String mapEventContext(String nodeType) {
        if (nodeType == null) {
            return null;
        }
        String normalized = nodeType.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("event:")) {
            normalized = normalized.substring(6);
        } else if (normalized.startsWith("event.")) {
            normalized = normalized.substring(6);
        } else {
            return null;
        }
        normalized = normalized.replace('.', '_');
        if (DIRECT_DISPATCH_EVENT_NODES.contains(normalized)) {
            return null;
        }
        return normalized.isBlank() ? null : normalized;
    }
}
