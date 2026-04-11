package restudio.resync.modules;

import restudio.resync.Log;
import restudio.flow.data.FlowGraph;
import restudio.flow.data.GuiDefinition;
import restudio.flow.data.ScoreboardDefinition;
import restudio.flow.data.TabDefinition;
import restudio.resync.core.Session;
import restudio.resync.flow.FlowStorage;
import restudio.resync.flow.GlobalTriggers;
import restudio.resync.flow.FlowRegistry;
import restudio.resync.flow.plugins.FlowNodePluginRegistry;
import restudio.resync.flow.registry.NodeDefinitionRegistry;
import restudio.resync.flow.triggers.TriggerRegistry;
import restudio.resync.modules.flow.FlowBlueprintPacketHandler;
import restudio.resync.modules.flow.FlowGuiPacketHandler;
import restudio.resync.modules.flow.FlowNodeRegistryPacketHandler;
import restudio.resync.modules.flow.FlowPacketSender;
import restudio.resync.modules.flow.FlowPlaceholderPreviewHandler;
import restudio.resync.modules.flow.FlowScoreboardPacketHandler;
import restudio.resync.modules.flow.FlowTabPacketHandler;
import restudio.resync.protocol.Codec;
import restudio.resync.protocol.messages.DataMessage;
import restudio.resync.protocol.messages.SubscribeRequest;

import java.nio.ByteBuffer;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class FlowModule implements Module {
    private static final ModuleMetadata METADATA = ModuleMetadata.of("flowLegacyHandler", "FlowLegacyHandler", "flow");
    private final FlowStorage storage;
    private final Set<Session> subscribedSessions = ConcurrentHashMap.newKeySet();
    private final FlowPacketSender sender;
    private final FlowBlueprintPacketHandler blueprintHandler;
    private final FlowGuiPacketHandler guiHandler;
    private final FlowScoreboardPacketHandler scoreboardHandler;
    private final FlowTabPacketHandler tabHandler;
    private final FlowPlaceholderPreviewHandler placeholderPreviewHandler;
    private final FlowNodeRegistryPacketHandler nodeRegistryHandler;

    public FlowModule(FlowStorage storage, Codec codec, int channelId, TriggerRegistry triggerRegistry, GlobalTriggers globalTriggers,
                      FlowRegistry flowRegistry, NodeDefinitionRegistry definitionRegistry, FlowNodePluginRegistry pluginRegistry) {
        this.storage = storage;
        this.sender = new FlowPacketSender(codec, channelId, subscribedSessions);
        this.blueprintHandler = new FlowBlueprintPacketHandler(storage, triggerRegistry, globalTriggers, sender);
        this.guiHandler = new FlowGuiPacketHandler(storage, sender);
        this.scoreboardHandler = new FlowScoreboardPacketHandler(storage, sender);
        this.tabHandler = new FlowTabPacketHandler(storage, sender);
        this.placeholderPreviewHandler = new FlowPlaceholderPreviewHandler(sender);
        this.nodeRegistryHandler = new FlowNodeRegistryPacketHandler(definitionRegistry, pluginRegistry, sender);
    }

    @Override
    public ModuleMetadata getMetadata() {
        return METADATA;
    }

    @Override
    public String getChannelId() {
        return "flow";
    }

    public FlowStorage getStorage() {
        return storage;
    }

    @Override
    public void onSubscribe(Session session, SubscribeRequest req) {
        Log.fine(session.getClientId() + " subscribed to flow channel");
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
            sender.sendError(session, "EMPTY_PACKET", "Packet payload is empty");
            return;
        }

        if (payload.length > FlowPacketSender.MAX_PACKET_SIZE) {
            sender.sendError(session, "PACKET_TOO_LARGE", "Packet exceeds maximum size");
            return;
        }

        ByteBuffer buffer = ByteBuffer.wrap(payload);
        byte packetId = buffer.get();

        try {
            switch (packetId) {
                case 0x01 -> blueprintHandler.handleRequest(session, buffer);
                case 0x02 -> {
                }
                case 0x03 -> blueprintHandler.handleSave(session, buffer);
                case 0x04 -> {
                }
                case 0x06 -> blueprintHandler.handleTriggerUpdate(buffer);
                case 0x08 -> blueprintHandler.handleDelete(session, buffer);
                case 0x09 -> blueprintHandler.handleListRequest(session);
                case 0x0C -> nodeRegistryHandler.handleRequest(session, buffer);
                case 0x11 -> guiHandler.handleRequest(session, buffer);
                case 0x13 -> guiHandler.handleSave(session, buffer);
                case 0x14 -> guiHandler.handleListRequest(session);
                case 0x16 -> guiHandler.handleDelete(session, buffer);
                case 0x18 -> scoreboardHandler.handleRequest(session, buffer);
                case 0x19 -> scoreboardHandler.handleSave(session, buffer);
                case 0x1A -> scoreboardHandler.handleListRequest(session);
                case 0x1B -> scoreboardHandler.handleDelete(session, buffer);
                case 0x20 -> tabHandler.handleRequest(session, buffer);
                case 0x21 -> tabHandler.handleSave(session, buffer);
                case 0x22 -> tabHandler.handleListRequest(session);
                case 0x23 -> tabHandler.handleDelete(session, buffer);
                case 0x27 -> placeholderPreviewHandler.handle(session, buffer);
                default -> Log.warn("Unknown flow packet: 0x" + String.format("%02X", packetId));
            }
        } catch (Exception e) {
            Log.error("Error handling flow packet 0x" + String.format("%02X", packetId) + ": " + e.getMessage());
            sender.sendError(session, "PROCESSING_ERROR", e.getMessage());
        }
    }

    public void sendFlowData(Session session, FlowGraph graph) {
        sender.sendFlowData(session, graph);
    }

    public void sendGuiData(Session session, GuiDefinition gui) {
        sender.sendGuiData(session, gui);
    }

    public void sendScoreboardData(Session session, ScoreboardDefinition scoreboard) {
        sender.sendScoreboardData(session, scoreboard);
    }

    public void sendTabData(Session session, TabDefinition tab) {
        sender.sendTabData(session, tab);
    }

    public void sendGuiState(Session session, boolean editable, String guiId, String flowId) {
        sender.sendGuiState(session, editable, guiId, flowId);
    }
}
