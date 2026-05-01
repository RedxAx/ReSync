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
import java.util.Set;
import java.util.UUID;

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
        byte[] jsonBytes = new byte[buffer.remaining()];
        buffer.get(jsonBytes);
        String json = new String(jsonBytes, StandardCharsets.UTF_8);
        JobRecord<String> job = sender.beginJob(session, "saveFlow", "");
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
            storage.saveGraph(graph);
            CustomContentStorage customContentStorage = CustomContentAccess.getStorage();
            if (customContentStorage != null) {
                CustomContentDefinition content = CustomContentGraphAdapter.toDefinition(graph);
                if (content != null) {
                    customContentStorage.save(content);
                } else if (!graph.isFunction()) {
                    ensureDefaultContentGraphs(graph.getId(), customContentStorage);
                }
            }
            updateEventBindings(graph);
            Log.fine("Flow saved: " + graph.getId());
            sender.sendFlowSaveAck(session, graph.getId());
            sender.succeedJob(job, graph.getId().toString(), "Saved");
        } catch (Exception e) {
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

        try {
            byte[] idBytes = new byte[buffer.remaining()];
            buffer.get(idBytes);
            String flowId = new String(idBytes, StandardCharsets.UTF_8);
            job = sender.beginJob(session, "deleteFlow", flowId);
            storage.deleteGraph(flowId);
            CustomContentStorage customContentStorage = CustomContentAccess.getStorage();
            if (customContentStorage != null) {
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
        JobRecord<String> job = sender.beginJob(session, "updateTriggers", "");
        byte[] jsonBytes = new byte[buffer.remaining()];
        buffer.get(jsonBytes);
        String json = new String(jsonBytes, StandardCharsets.UTF_8);
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
