package restudio.resync.modules;

import org.bukkit.Bukkit;
import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowSerializer;
import restudio.flow.data.GuiDefinition;
import restudio.resync.core.Session;
import restudio.resync.flow.FlowStorage;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import restudio.resync.flow.GlobalTriggers;
import restudio.resync.flow.FlowRegistry;
import restudio.resync.flow.plugins.FlowNodePluginRegistry;
import restudio.resync.flow.registry.NodeDefinitionRegistry;
import restudio.resync.flow.sync.NodePluginPayload;
import restudio.resync.flow.sync.NodeRegistryRequest;
import restudio.resync.flow.sync.NodeRegistrySnapshot;
import restudio.resync.flow.triggers.TriggerBinding;
import restudio.resync.flow.triggers.TriggerRegistry;
import restudio.resync.flow.triggers.TriggerType;
import restudio.resync.protocol.Codec;
import restudio.resync.protocol.messages.DataMessage;
import restudio.resync.protocol.messages.SubscribeRequest;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class FlowModule implements Module {
    private final FlowStorage storage;
    private final Codec codec;
    private final int channelId;
    private final TriggerRegistry triggerRegistry;
    private final GlobalTriggers globalTriggers;
    private final FlowRegistry flowRegistry;
    private final NodeDefinitionRegistry definitionRegistry;
    private final FlowNodePluginRegistry pluginRegistry;
    private final Set<Session> subscribedSessions = ConcurrentHashMap.newKeySet();
    private final Gson gson = new Gson();
    private static final int MAX_PACKET_SIZE = 1024 * 1024; // 1MB
    private static final int MAX_STRING_LENGTH = 65536; // 64KB

    public FlowModule(FlowStorage storage, Codec codec, int channelId, TriggerRegistry triggerRegistry, GlobalTriggers globalTriggers,
                      FlowRegistry flowRegistry, NodeDefinitionRegistry definitionRegistry, FlowNodePluginRegistry pluginRegistry) {
        this.storage = storage;
        this.codec = codec;
        this.channelId = channelId;
        this.triggerRegistry = triggerRegistry;
        this.globalTriggers = globalTriggers;
        this.flowRegistry = flowRegistry;
        this.definitionRegistry = definitionRegistry;
        this.pluginRegistry = pluginRegistry;

        if (this.pluginRegistry != null) {
            this.pluginRegistry.addListener(new FlowNodePluginRegistry.PluginChangeListener() {
                @Override
                public void onPluginLoaded(NodePluginPayload payload) {
                    NodeRegistrySnapshot snapshot = buildDeltaSnapshot(List.of(payload), List.of());
                    broadcastNodeRegistry(snapshot);
                }

                @Override
                public void onPluginUnloaded(String pluginId) {
                    NodeRegistrySnapshot snapshot = buildDeltaSnapshot(List.of(), List.of(pluginId));
                    broadcastNodeRegistry(snapshot);
                }
            });
        }
    }

    @Override
    public String getChannelId() {
        return "flow";
    }

    @Override
    public void onSubscribe(Session session, SubscribeRequest req) {
        Bukkit.getLogger().info("[ReSync] " + session.getClientId() + " subscribed to flow channel.");
        subscribedSessions.add(session);
    }

    @Override
    public void cleanup(Session session) {
        subscribedSessions.remove(session);
    }

    @Override
    public void onData(Session session, DataMessage req) {
        byte[] payload = req.getPayload();
        if (payload.length < 1) {
            sendError(session, "EMPTY_PACKET", "Packet payload is empty");
            return;
        }

        if (payload.length > MAX_PACKET_SIZE) {
            sendError(session, "PACKET_TOO_LARGE", "Packet exceeds maximum size");
            return;
        }

        ByteBuffer buffer = ByteBuffer.wrap(payload);
        byte packetId = buffer.get();

        try {
            switch (packetId) {
                case 0x01:
                    handleRequest(session, buffer);
                    break;
                case 0x02:
                    break;
                case 0x03:
                    handleSave(session, buffer);
                    break;
                case 0x04:
                    break;
                case 0x08:
                    handleDelete(session, buffer);
                    break;
                case 0x09:
                    handleListRequest(session);
                    break;
                case 0x11:
                    handleGuiRequest(session, buffer);
                    break;
                case 0x13:
                    handleGuiSave(session, buffer);
                    break;
                case 0x14:
                    handleGuiListRequest(session);
                    break;
                case 0x16:
                    handleGuiDelete(session, buffer);
                    break;
                case 0x0C:
                    handleNodeRegistryRequest(session, buffer);
                    break;
                case 0x06:
                    handleTriggerUpdate(buffer);
                    break;
                default:
                    Bukkit.getLogger().warning("[ReSync] Unknown packet type: 0x" + String.format("%02X", packetId));
            }
        } catch (Exception e) {
            Bukkit.getLogger().severe("[ReSync] Error handling packet 0x" + String.format("%02X", packetId) + ": " + e.getMessage());
            sendError(session, "PROCESSING_ERROR", e.getMessage());
        }
    }

    private void handleRequest(Session session, ByteBuffer buffer) {
        if (!buffer.hasRemaining()) {
            sendError(session, "INVALID_REQUEST", "Flow ID not provided");
            return;
        }

        byte[] idBytes = new byte[buffer.remaining()];
        buffer.get(idBytes);
        String flowId = new String(idBytes, StandardCharsets.UTF_8);

        if (flowId.length() > MAX_STRING_LENGTH) {
            sendError(session, "INVALID_FLOW_ID", "Flow ID too long");
            return;
        }

        FlowGraph graph = storage.getGraph(flowId);
        if (graph != null) {
            sendFlowData(session, graph);
        } else {
            sendError(session, "FLOW_NOT_FOUND", "Flow not found: " + flowId);
        }
    }

    private void handleSave(Session session, ByteBuffer buffer) {
        if (!buffer.hasRemaining()) {
            sendError(session, "INVALID_SAVE", "No data provided");
            return;
        }

        if (buffer.remaining() > MAX_PACKET_SIZE) {
            sendError(session, "SAVE_TOO_LARGE", "Save data exceeds maximum size");
            return;
        }

        byte[] jsonBytes = new byte[buffer.remaining()];
        buffer.get(jsonBytes);
        String json = new String(jsonBytes, StandardCharsets.UTF_8);

        try {
            FlowGraph graph = FlowSerializer.deserialize(json);
            if (graph == null) {
                sendError(session, "INVALID_GRAPH", "Failed to parse flow graph");
                return;
            }

            if (graph.getId() == null) {
                graph.setId(java.util.UUID.randomUUID().toString());
            }

            storage.saveGraph(graph);
            Bukkit.getLogger().info("[ReSync] Saved flow " + graph.getId() + " from client " + session.getClientId());
            updateEventBindings(graph);
            sendFlowSaveAck(session, graph.getId());
        } catch (Exception e) {
            sendError(session, "SAVE_FAILED", "Failed to save flow: " + e.getMessage());
            Bukkit.getLogger().severe("[ReSync] Save error: " + e.getMessage());
        }
    }

    private void updateEventBindings(FlowGraph graph) {
        if (triggerRegistry == null || globalTriggers == null || graph == null) {
            return;
        }
        java.util.Set<String> contexts = new java.util.HashSet<>();
        for (var entry : graph.getNodes().entrySet()) {
            String context = mapEventContext(entry.getValue().getType());
            if (context != null) {
                contexts.add(context);
            }
        }

        java.util.List<TriggerBinding> bindings = new java.util.ArrayList<>();
        String flowId = graph.getId().toString();
        for (String context : contexts) {
            String bindingId = flowId + ":" + context;
            bindings.add(new TriggerBinding(bindingId, flowId, TriggerType.EVENT, context));
        }

        triggerRegistry.replaceFlowBindings(flowId, TriggerType.EVENT, bindings);
        globalTriggers.refreshBindings();
    }

    private String mapEventContext(String nodeType) {
        if (nodeType == null) {
            return null;
        }
        if (nodeType.startsWith("event:")) {
            String eventType = nodeType.substring(6);
            return eventType;
        }
        return null;
    }

    private void sendFlowSaveAck(Session session, String flowId) {
        if (flowId == null) {
            return;
        }
        byte[] idBytes = flowId.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(1 + 4 + idBytes.length);
        buffer.put((byte) 0x07);
        buffer.putInt(idBytes.length);
        buffer.put(idBytes);

        DataMessage msg = new DataMessage();
        msg.setChannel(channelId);
        msg.setPayload(buffer.array());

        codec.sendMessage(session.getConnection().getWebSocket(), msg, channelId, false);
    }

    private void handleTriggerUpdate(ByteBuffer buffer) {
        if (!buffer.hasRemaining()) {
            return;
        }

        byte[] jsonBytes = new byte[buffer.remaining()];
        buffer.get(jsonBytes);
        String json = new String(jsonBytes, StandardCharsets.UTF_8);

        if (triggerRegistry == null) {
            return;
        }

        java.util.List<TriggerBinding> bindings = gson.fromJson(json, new TypeToken<java.util.List<TriggerBinding>>() {}.getType());
        triggerRegistry.setBindings(bindings);
        if (globalTriggers != null) {
            globalTriggers.refreshBindings();
        }
    }

    private void handleNodeRegistryRequest(Session session, ByteBuffer buffer) {
        byte[] jsonBytes = new byte[buffer.remaining()];
        buffer.get(jsonBytes);
        String json = new String(jsonBytes, StandardCharsets.UTF_8);
        NodeRegistryRequest request = null;
        try {
            if (!json.isBlank()) {
                request = gson.fromJson(json, NodeRegistryRequest.class);
            }
        } catch (Exception e) {
            Bukkit.getLogger().warning("[ReSync] Failed to parse node registry request: " + e.getMessage());
        }

        NodeRegistrySnapshot snapshot = buildSnapshot(request);
        sendNodeRegistrySnapshot(session, snapshot);
    }

    private void handleDelete(Session session, ByteBuffer buffer) {
        if (!buffer.hasRemaining()) {
            return;
        }
        byte[] idBytes = new byte[buffer.remaining()];
        buffer.get(idBytes);
        String flowId = new String(idBytes, StandardCharsets.UTF_8);
        storage.deleteGraph(flowId);

        if (triggerRegistry != null) {
            triggerRegistry.replaceFlowBindings(flowId, TriggerType.EVENT, java.util.List.of());
        }
        if (globalTriggers != null) {
            globalTriggers.refreshBindings();
        }

        Bukkit.getLogger().info("[ReSync] Deleted flow " + flowId + " from client " + session.getClientId());
    }

    private void handleGuiRequest(Session session, ByteBuffer buffer) {
        if (!buffer.hasRemaining()) {
            sendError(session, "INVALID_REQUEST", "GUI ID not provided");
            return;
        }
        byte[] idBytes = new byte[buffer.remaining()];
        buffer.get(idBytes);
        String guiId = new String(idBytes, StandardCharsets.UTF_8);
        if (guiId.length() > MAX_STRING_LENGTH) {
            sendError(session, "INVALID_GUI_ID", "GUI ID too long");
            return;
        }
        GuiDefinition gui = storage.getGui(guiId);
        if (gui != null) {
            sendGuiData(session, gui);
        } else {
            sendError(session, "GUI_NOT_FOUND", "GUI not found: " + guiId);
        }
    }

    private void handleGuiSave(Session session, ByteBuffer buffer) {
        if (!buffer.hasRemaining()) {
            sendError(session, "INVALID_SAVE", "No data provided");
            return;
        }
        if (buffer.remaining() > MAX_PACKET_SIZE) {
            sendError(session, "SAVE_TOO_LARGE", "Save data exceeds maximum size");
            return;
        }
        byte[] jsonBytes = new byte[buffer.remaining()];
        buffer.get(jsonBytes);
        String json = new String(jsonBytes, StandardCharsets.UTF_8);
        try {
            GuiDefinition gui = FlowSerializer.deserializeGui(json);
            if (gui == null || gui.getId() == null || gui.getId().isBlank()) {
                sendError(session, "INVALID_GUI", "GUI ID is missing");
                return;
            }
            storage.saveGui(gui);
            Bukkit.getLogger().info("[ReSync] Saved GUI " + gui.getId() + " from client " + session.getClientId());
            sendGuiSaveAck(session, gui.getId());
        } catch (Exception e) {
            sendError(session, "SAVE_FAILED", "Failed to save GUI: " + e.getMessage());
        }
    }

    private void handleGuiDelete(Session session, ByteBuffer buffer) {
        if (!buffer.hasRemaining()) {
            return;
        }
        byte[] idBytes = new byte[buffer.remaining()];
        buffer.get(idBytes);
        String guiId = new String(idBytes, StandardCharsets.UTF_8);
        storage.deleteGui(guiId);
        Bukkit.getLogger().info("[ReSync] Deleted GUI " + guiId + " from client " + session.getClientId());
    }

    private void handleGuiListRequest(Session session) {
        if (storage == null) {
            return;
        }
        java.util.List<String> guiIds = storage.listGuiIds();
        int totalBytes = 1 + 4;
        for (String id : guiIds) {
            totalBytes += 4 + id.getBytes(StandardCharsets.UTF_8).length;
        }
        ByteBuffer buffer = ByteBuffer.allocate(totalBytes);
        buffer.put((byte) 0x15);
        buffer.putInt(guiIds.size());
        for (String id : guiIds) {
            byte[] bytes = id.getBytes(StandardCharsets.UTF_8);
            buffer.putInt(bytes.length);
            buffer.put(bytes);
        }

        DataMessage msg = new DataMessage();
        msg.setChannel(channelId);
        msg.setPayload(buffer.array());
        codec.sendMessage(session.getConnection().getWebSocket(), msg, channelId, false);
    }

    private void handleListRequest(Session session) {
        if (storage == null) {
            return;
        }
        java.util.List<String> flowIds = storage.listFlowIds();
        int totalBytes = 1 + 4;
        for (String id : flowIds) {
            totalBytes += 4 + id.getBytes(StandardCharsets.UTF_8).length;
        }
        ByteBuffer buffer = ByteBuffer.allocate(totalBytes);
        buffer.put((byte) 0x0A);
        buffer.putInt(flowIds.size());
        for (String id : flowIds) {
            byte[] bytes = id.getBytes(StandardCharsets.UTF_8);
            buffer.putInt(bytes.length);
            buffer.put(bytes);
        }

        DataMessage msg = new DataMessage();
        msg.setChannel(channelId);
        msg.setPayload(buffer.array());
        codec.sendMessage(session.getConnection().getWebSocket(), msg, channelId, false);
    }

    private NodeRegistrySnapshot buildSnapshot(NodeRegistryRequest request) {
        NodeRegistrySnapshot snapshot = new NodeRegistrySnapshot();
        Map<String, String> clientChecksums = request != null ? request.getPluginChecksums() : Map.of();
        boolean fullSync = clientChecksums == null || clientChecksums.isEmpty();
        snapshot.setFullSync(fullSync);

        List<String> nodeIds = new ArrayList<>(definitionRegistry.getAllDefinitions().keySet());
        nodeIds.sort(String.CASE_INSENSITIVE_ORDER);
        snapshot.setNodeIds(nodeIds);

        List<NodePluginPayload> pluginPayloads = new ArrayList<>();
        if (pluginRegistry != null) {
            for (String pluginId : pluginRegistry.getPluginIds()) {
                String checksum = pluginRegistry.getChecksum(pluginId);
                String clientChecksum = clientChecksums != null ? clientChecksums.get(pluginId) : null;
                if (fullSync || checksum == null || !checksum.equals(clientChecksum)) {
                    NodePluginPayload payload = pluginRegistry.buildPayload(pluginId);
                    if (payload != null) {
                        pluginPayloads.add(payload);
                    }
                }
            }
        }
        snapshot.setPlugins(pluginPayloads);

        List<String> removed = new ArrayList<>();
        if (clientChecksums != null && pluginRegistry != null) {
            for (String pluginId : clientChecksums.keySet()) {
                if (!pluginRegistry.getPluginIds().contains(pluginId)) {
                    removed.add(pluginId);
                }
            }
        }
        snapshot.setRemovedPlugins(removed);
        return snapshot;
    }

    private NodeRegistrySnapshot buildDeltaSnapshot(List<NodePluginPayload> plugins, List<String> removedPlugins) {
        NodeRegistrySnapshot snapshot = new NodeRegistrySnapshot();
        snapshot.setFullSync(false);
        List<String> nodeIds = new ArrayList<>(definitionRegistry.getAllDefinitions().keySet());
        nodeIds.sort(String.CASE_INSENSITIVE_ORDER);
        snapshot.setNodeIds(nodeIds);
        snapshot.setPlugins(plugins);
        snapshot.setRemovedPlugins(removedPlugins);
        return snapshot;
    }

    private void sendNodeRegistrySnapshot(Session session, NodeRegistrySnapshot snapshot) {
        if (session == null || snapshot == null) {
            return;
        }
        String json = gson.toJson(snapshot);
        byte[] jsonBytes = json.getBytes(StandardCharsets.UTF_8);
        byte packetId = snapshot.isFullSync() ? (byte) 0x0B : (byte) 0x0D;
        ByteBuffer buffer = ByteBuffer.allocate(1 + jsonBytes.length);
        buffer.put(packetId);
        buffer.put(jsonBytes);

        DataMessage msg = new DataMessage();
        msg.setChannel(channelId);
        msg.setPayload(buffer.array());
        codec.sendMessage(session.getConnection().getWebSocket(), msg, channelId, true);
    }

    private void broadcastNodeRegistry(NodeRegistrySnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        for (Session session : subscribedSessions) {
            sendNodeRegistrySnapshot(session, snapshot);
        }
    }

    public void sendFlowData(Session session, FlowGraph graph) {
        String json = FlowSerializer.serialize(graph);
        byte[] jsonBytes = json.getBytes(StandardCharsets.UTF_8);

        if (jsonBytes.length > MAX_PACKET_SIZE) {
            sendError(session, "FLOW_TOO_LARGE", "Flow data exceeds maximum size");
            return;
        }

        ByteBuffer buffer = ByteBuffer.allocate(1 + jsonBytes.length);
        buffer.put((byte) 0x02);
        buffer.put(jsonBytes);

        DataMessage msg = new DataMessage();
        msg.setChannel(channelId);
        msg.setPayload(buffer.array());

        codec.sendMessage(session.getConnection().getWebSocket(), msg, channelId, false);
    }

    public void sendGuiData(Session session, GuiDefinition gui) {
        String json = FlowSerializer.serializeGui(gui);
        byte[] jsonBytes = json.getBytes(StandardCharsets.UTF_8);

        if (jsonBytes.length > MAX_PACKET_SIZE) {
            sendError(session, "GUI_TOO_LARGE", "GUI data exceeds maximum size");
            return;
        }

        ByteBuffer buffer = ByteBuffer.allocate(1 + jsonBytes.length);
        buffer.put((byte) 0x12);
        buffer.put(jsonBytes);

        DataMessage msg = new DataMessage();
        msg.setChannel(channelId);
        msg.setPayload(buffer.array());

        codec.sendMessage(session.getConnection().getWebSocket(), msg, channelId, false);
    }

    private void sendGuiSaveAck(Session session, String guiId) {
        if (guiId == null) {
            return;
        }
        byte[] idBytes = guiId.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(1 + 4 + idBytes.length);
        buffer.put((byte) 0x17);
        buffer.putInt(idBytes.length);
        buffer.put(idBytes);

        DataMessage msg = new DataMessage();
        msg.setChannel(channelId);
        msg.setPayload(buffer.array());
        codec.sendMessage(session.getConnection().getWebSocket(), msg, channelId, false);
    }

    public void sendGuiState(Session session, boolean editable, String guiId, String flowId) {
        if (guiId != null && guiId.length() > MAX_STRING_LENGTH) {
            sendError(session, "INVALID_GUI_ID", "GUI ID too long");
            return;
        }
        if (flowId != null && flowId.length() > MAX_STRING_LENGTH) {
            sendError(session, "INVALID_FLOW_ID", "Flow ID too long");
            return;
        }

        byte[] guiBytes = guiId != null ? guiId.getBytes(StandardCharsets.UTF_8) : new byte[0];
        byte[] flowBytes = flowId != null ? flowId.getBytes(StandardCharsets.UTF_8) : new byte[0];
        ByteBuffer buffer = ByteBuffer.allocate(1 + 1 + 4 + guiBytes.length + 4 + flowBytes.length);
        buffer.put((byte) 0x04);
        buffer.put((byte) (editable ? 1 : 0));
        buffer.putInt(guiBytes.length);
        buffer.put(guiBytes);
        buffer.putInt(flowBytes.length);
        buffer.put(flowBytes);

        DataMessage msg = new DataMessage();
        msg.setChannel(channelId);
        msg.setPayload(buffer.array());

        codec.sendMessage(session.getConnection().getWebSocket(), msg, channelId, false);
    }

    private void sendError(Session session, String errorCode, String message) {
        byte[] errorBytes = message.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(1 + 4 + 4 + errorBytes.length);
        buffer.put((byte) 0x05); // FLOW_ERROR
        buffer.putInt(message.length());
        buffer.put(message.getBytes(StandardCharsets.UTF_8));
        
        DataMessage msg = new DataMessage();
        msg.setChannel(channelId);
        msg.setPayload(buffer.array());
        
        codec.sendMessage(session.getConnection().getWebSocket(), msg, channelId, false);
        
        Bukkit.getLogger().warning("[ReSync] Sending error to client " + session.getClientId() + ": " + errorCode + " - " + message);
    }
}
