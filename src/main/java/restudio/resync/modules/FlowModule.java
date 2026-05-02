package restudio.resync.modules;

import restudio.resync.Log;
import restudio.flow.data.FlowGraph;
import restudio.flow.data.GuiDefinition;
import restudio.flow.data.ScoreboardDefinition;
import restudio.flow.data.TabDefinition;
import restudio.resync.customcontent.CustomContentService;
import restudio.resync.core.Session;
import restudio.resync.flow.CustomFunctionNodeDefinitions;
import restudio.resync.customcontent.CustomContentStorage;
import restudio.resync.flow.FlowStorage;
import restudio.resync.flow.GlobalTriggers;
import restudio.resync.flow.FlowRegistry;
import restudio.resync.flow.plugins.FlowNodePluginRegistry;
import restudio.resync.flow.handler.property.PropertyRegistry;
import restudio.resync.flow.registry.NodeDefinitionRegistry;
import restudio.resync.flow.sync.NodePluginPayload;
import restudio.resync.flow.sync.NodeRegistrySnapshot;
import restudio.resync.flow.diagnostics.FlowTraceRecord;
import restudio.resync.flow.diagnostics.FlowTraceService;
import restudio.resync.flow.diagnostics.FlowTraceSink;
import restudio.resync.flow.triggers.TriggerRegistry;
import restudio.resync.modules.flow.FlowBlueprintPacketHandler;
import restudio.resync.modules.flow.FlowCustomContentPacketHandler;
import restudio.resync.modules.flow.FlowGuiPacketHandler;
import restudio.resync.modules.flow.FlowNodeRegistryPacketHandler;
import restudio.resync.modules.flow.FlowOptionCatalogPacketHandler;
import restudio.resync.modules.flow.FlowPacketSender;
import restudio.resync.modules.flow.FlowPlaceholderPreviewHandler;
import restudio.resync.modules.flow.FlowScoreboardPacketHandler;
import restudio.resync.modules.flow.FlowTabPacketHandler;
import restudio.resync.protocol.Codec;
import restudio.resync.protocol.messages.DataMessage;
import restudio.resync.protocol.messages.SubscribeRequest;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
    private final FlowOptionCatalogPacketHandler optionCatalogHandler;
    private final FlowNodeRegistryPacketHandler nodeRegistryHandler;
    private final FlowCustomContentPacketHandler customContentHandler;
    private final NodeDefinitionRegistry definitionRegistry;
    private final FlowNodePluginRegistry pluginRegistry;
    private FlowTraceService traceService;
    private FlowTraceSink traceSink;

    public FlowModule(FlowStorage storage, Codec codec, int channelId, TriggerRegistry triggerRegistry, GlobalTriggers globalTriggers,
                      FlowRegistry flowRegistry, NodeDefinitionRegistry definitionRegistry, FlowNodePluginRegistry pluginRegistry,
                      PropertyRegistry propertyRegistry, CustomContentStorage customContentStorage, CustomContentService customContentService) {
        this.storage = storage;
        this.definitionRegistry = definitionRegistry;
        this.pluginRegistry = pluginRegistry;
        this.sender = new FlowPacketSender(codec, channelId, subscribedSessions);
        this.blueprintHandler = new FlowBlueprintPacketHandler(storage, triggerRegistry, globalTriggers, sender);
        this.guiHandler = new FlowGuiPacketHandler(storage, sender);
        this.scoreboardHandler = new FlowScoreboardPacketHandler(storage, sender);
        this.tabHandler = new FlowTabPacketHandler(storage, sender);
        this.placeholderPreviewHandler = new FlowPlaceholderPreviewHandler(sender);
        this.optionCatalogHandler = new FlowOptionCatalogPacketHandler(sender, customContentService);
        this.nodeRegistryHandler = new FlowNodeRegistryPacketHandler(definitionRegistry, pluginRegistry, sender, propertyRegistry, customContentService);
        this.customContentHandler = new FlowCustomContentPacketHandler(customContentStorage, sender);
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
        if (payload == null || payload.length < 1) {
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
                case 0x06 -> blueprintHandler.handleTriggerUpdate(session, buffer);
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
                case 0x30 -> customContentHandler.handleRequest(session, buffer);
                case 0x33 -> customContentHandler.handleSave(session, buffer);
                case 0x34 -> customContentHandler.handleDelete(session, buffer);
                case 0x36 -> customContentHandler.handleListRequest(session);
                case 0x37 -> optionCatalogHandler.handle(session, buffer);
                case 0x40 -> handleTraceToggle(session, buffer);
                case 0x43 -> handleTraceClear(session);
                case 0x45 -> sender.sendJobSnapshot(session, session.getClientId());
                default -> {
                    Log.warn("Unknown flow packet: 0x" + String.format("%02X", packetId));
                    sender.sendError(session, "UNKNOWN_PACKET", "Unknown flow packet: 0x" + String.format("%02X", packetId));
                }
            }
        } catch (Exception e) {
            Log.error("Error handling flow packet 0x" + String.format("%02X", packetId) + ": " + e.getMessage());
            sender.sendError(session, "PROCESSING_ERROR", e.getMessage());
        }

        if (packetId == 0x03 || packetId == 0x08) {
            refreshCustomFunctionDefinitions();
        }
    }

    public void refreshCustomFunctionDefinitions() {
        CustomFunctionNodeDefinitions.rebuild(definitionRegistry, storage);
        sender.broadcastNodeRegistry(buildFullNodeRegistrySnapshot());
    }

    private NodeRegistrySnapshot buildFullNodeRegistrySnapshot() {
        NodeRegistrySnapshot snapshot = new NodeRegistrySnapshot();
        snapshot.setFullSync(true);

        List<String> nodeIds = new ArrayList<>(definitionRegistry.getAllDefinitions().keySet());
        nodeIds.sort(String.CASE_INSENSITIVE_ORDER);
        snapshot.setNodeIds(nodeIds);
        snapshot.setGeneratedAt(System.currentTimeMillis());
        snapshot.setRegistryChecksum(nodeRegistryHandler.computeRegistryChecksum());

        List<NodePluginPayload> payloads = new ArrayList<>();
        if (pluginRegistry != null) {
            for (String pluginId : pluginRegistry.getPluginIds()) {
                NodePluginPayload payload = pluginRegistry.buildPayload(pluginId);
                if (payload != null) {
                    payloads.add(payload);
                }
            }
        }
        snapshot.setPlugins(payloads);
        snapshot.setRemovedPlugins(List.of());
        nodeRegistryHandler.populateServerMetadata(snapshot);
        return snapshot;
    }

    public String getNodeRegistryChecksum() {
        return nodeRegistryHandler.computeRegistryChecksum();
    }

    public int getSubscribedSessionCount() {
        return subscribedSessions.size();
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

    public void setTraceService(FlowTraceService traceService) {
        this.traceService = traceService;
    }

    private void handleTraceToggle(Session session, ByteBuffer buffer) {
        if (traceService == null) {
            sender.sendTraceSnapshot(session, List.of());
            return;
        }
        boolean enabled = buffer.remaining() <= 0 || buffer.get() != 0;
        traceService.setEnabled(enabled);
        if (enabled && traceSink == null) {
            traceSink = record -> {
                for (Session subscribedSession : subscribedSessions) {
                    sender.sendTraceEvent(subscribedSession, record);
                }
            };
            traceService.addSink(traceSink);
        }
        sender.sendTraceSnapshot(session, traceService.snapshot());
    }

    private void handleTraceClear(Session session) {
        if (traceService != null) {
            traceService.clear();
        }
        sender.sendTraceSnapshot(session, List.of());
    }
}
