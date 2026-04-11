package restudio.resync.modules.flow;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import restudio.resync.Log;
import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowSerializer;
import restudio.resync.core.Session;
import restudio.resync.flow.FlowStorage;
import restudio.resync.flow.GlobalTriggers;
import restudio.resync.flow.triggers.TriggerBinding;
import restudio.resync.flow.triggers.TriggerRegistry;
import restudio.resync.flow.triggers.TriggerType;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class FlowBlueprintPacketHandler {
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
        try {
            FlowGraph graph = FlowSerializer.deserialize(json);
            if (graph == null) {
                sender.sendError(session, "INVALID_GRAPH", "Failed to parse flow graph");
                return;
            }
            if (graph.getId() == null) {
                graph.setId(UUID.randomUUID().toString());
            }
            storage.saveGraph(graph);
            updateEventBindings(graph);
            Log.fine("Flow saved: " + graph.getId());
            sender.sendFlowSaveAck(session, graph.getId());
        } catch (Exception e) {
            sender.sendError(session, "SAVE_FAILED", "Failed to save flow: " + e.getMessage());
            Log.error("Flow save error: " + e.getMessage());
        }
    }

    public void handleDelete(Session session, ByteBuffer buffer) {
        if (!buffer.hasRemaining()) {
            return;
        }
        byte[] idBytes = new byte[buffer.remaining()];
        buffer.get(idBytes);
        String flowId = new String(idBytes, StandardCharsets.UTF_8);
        storage.deleteGraph(flowId);
        if (triggerRegistry != null) {
            triggerRegistry.replaceFlowBindings(flowId, TriggerType.EVENT, List.of());
        }
        if (globalTriggers != null) {
            globalTriggers.refreshBindings();
        }
        Log.fine("Flow deleted: " + flowId);
    }

    public void handleListRequest(Session session) {
        sender.sendFlowList(session, storage.listFlowIds());
    }

    public void handleTriggerUpdate(ByteBuffer buffer) {
        if (!buffer.hasRemaining() || triggerRegistry == null) {
            return;
        }
        byte[] jsonBytes = new byte[buffer.remaining()];
        buffer.get(jsonBytes);
        String json = new String(jsonBytes, StandardCharsets.UTF_8);
        List<TriggerBinding> bindings = gson.fromJson(json, new TypeToken<List<TriggerBinding>>() {
        }.getType());
        triggerRegistry.setBindings(bindings);
        if (globalTriggers != null) {
            globalTriggers.refreshBindings();
        }
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

    private String mapEventContext(String nodeType) {
        if (nodeType == null) {
            return null;
        }
        return nodeType.startsWith("event:") ? nodeType.substring(6) : null;
    }
}
