package restudio.resync.modules.flow;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import restudio.resync.Log;
import restudio.flow.data.CustomContentDefinition;
import restudio.flow.data.CustomContentGraphAdapter;
import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowSerializer;
import restudio.resync.customcontent.CustomContentAccess;
import restudio.resync.customcontent.CustomContentStorage;
import restudio.resync.core.Session;
import restudio.resync.flow.FlowStorage;
import restudio.resync.flow.GlobalTriggers;
import restudio.resync.flow.triggers.TriggerBinding;
import restudio.resync.flow.triggers.TriggerRegistry;
import restudio.resync.flow.triggers.TriggerType;
import restudio.resync.jobs.JobRecord;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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
    private final FlowPacketSender sender;
    private final Gson gson = new Gson();

    public FlowBlueprintPacketHandler(FlowStorage storage, TriggerRegistry triggerRegistry, GlobalTriggers globalTriggers, FlowPacketSender sender) {
        this.storage = storage;
        this.triggerRegistry = triggerRegistry;
        this.globalTriggers = globalTriggers;
        this.sender = sender;
        refreshAllEventBindings();
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
            String flowId = graph.getId().toString();
            rollbackFlowId = flowId;
            previousGraph = storage.getGraph(flowId);
            storage.saveGraph(graph);
            CustomContentStorage customContentStorage = CustomContentAccess.getStorage();
            if (customContentStorage != null) {
                previousContent = customContentStorage.getByFlow(flowId).stream()
                    .collect(Collectors.toMap(CustomContentDefinition::getId, Function.identity(), (left, right) -> left));
                CustomContentDefinition content = CustomContentGraphAdapter.toDefinition(graph);
                if (content != null) {
                    customContentStorage.save(content);
                } else if (!graph.isFunction()) {
                    ensureDefaultContentGraphs(flowId, customContentStorage);
                }
            }
            updateEventBindings(graph);
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
                }
            }
            if (triggerRegistry != null) {
                triggerRegistry.replaceFlowBindings(flowId, TriggerType.EVENT, List.of());
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

    private void refreshAllEventBindings() {
        if (triggerRegistry == null || globalTriggers == null) {
            return;
        }
        for (String flowId : storage.listFlowIds()) {
            FlowGraph graph = storage.getGraph(flowId);
            if (graph != null) {
                updateEventBindings(graph);
            }
        }
        globalTriggers.refreshBindings();
    }

    private void updateEventBindings(FlowGraph graph) {
        if (triggerRegistry == null || globalTriggers == null || graph == null) {
            return;
        }
        Set<String> contexts = new HashSet<>();
        for (var entry : graph.getNodes().entrySet()) {
            String context = mapEventContext(entry.getValue().getType());
            if (context != null) {
                contexts.add(context);
            }
        }
        List<TriggerBinding> bindings = new ArrayList<>();
        String flowId = graph.getId().toString();
        for (String context : contexts) {
            bindings.add(new TriggerBinding(flowId + ':' + context, flowId, TriggerType.EVENT, context));
        }
        triggerRegistry.replaceFlowBindings(flowId, TriggerType.EVENT, bindings);
        globalTriggers.refreshBindings();
    }

    private void ensureDefaultContentGraphs(String flowId, CustomContentStorage customContentStorage) {
        ensureDefaultContentGraph(flowId + "_default_item", "item", "Default Item", customContentStorage);
        ensureDefaultContentGraph(flowId + "_default_block", "block", "Default Block", customContentStorage);
        ensureDefaultContentGraph(flowId + "_default_armor", "armor", "Default Armor", customContentStorage);
    }

    private void ensureDefaultContentGraph(String graphId, String type, String name, CustomContentStorage customContentStorage) {
        if (storage.getGraph(graphId) != null) {
            return;
        }
        FlowGraph contentGraph = CustomContentGraphAdapter.createContentGraph(graphId, type, name);
        storage.saveGraph(contentGraph);
        CustomContentDefinition content = CustomContentGraphAdapter.toDefinition(contentGraph);
        if (content != null) {
            customContentStorage.save(content);
        }
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
