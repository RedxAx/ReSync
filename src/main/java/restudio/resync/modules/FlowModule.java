package restudio.resync.modules;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import restudio.resync.Log;
import restudio.resync.api.OptionCatalogRegistry;
import restudio.resync.api.ReSyncExtensionData;
import restudio.flow.data.FlowGraph;
import restudio.flow.data.GuiDefinition;
import restudio.flow.data.ScoreboardDefinition;
import restudio.flow.data.TabDefinition;
import restudio.resync.customcontent.CustomContentService;
import restudio.resync.customization.ReSyncJsonResourceStorage;
import restudio.resync.core.Session;
import restudio.resync.flow.CustomFunctionNodeDefinitions;
import restudio.resync.customcontent.CustomContentStorage;
import restudio.resync.flow.FlowExecutor;
import restudio.resync.flow.FlowStorage;
import restudio.resync.flow.GlobalTriggers;
import restudio.resync.flow.FlowRegistry;
import restudio.resync.flow.handler.property.PropertyRegistry;
import restudio.resync.flow.registry.NodeDefinitionRegistry;
import restudio.resync.flow.sync.NodeRegistrySnapshot;
import restudio.resync.flow.diagnostics.FlowDebugService;
import restudio.resync.flow.diagnostics.FlowTraceService;
import restudio.resync.flow.diagnostics.FlowTraceSink;
import restudio.resync.flow.triggers.TriggerRegistry;
import restudio.resync.modules.flow.FlowBlueprintPacketHandler;
import restudio.resync.modules.flow.FlowNodeRegistryPacketHandler;
import restudio.resync.modules.flow.FlowOptionCatalogPacketHandler;
import restudio.resync.modules.flow.FlowPacketSender;
import restudio.resync.modules.flow.FlowPlaceholderPreviewHandler;
import restudio.resync.modules.flow.FlowResourcePacketRouter;
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
    private final FlowResourcePacketRouter resourceRouter;
    private final FlowPlaceholderPreviewHandler placeholderPreviewHandler;
    private final FlowOptionCatalogPacketHandler optionCatalogHandler;
    private final FlowNodeRegistryPacketHandler nodeRegistryHandler;
    private final NodeDefinitionRegistry definitionRegistry;
    private FlowTraceService traceService;
    private FlowDebugService debugService;
    private FlowTraceSink traceSink;
    private FlowExecutor executor;
    private final Gson gson = new Gson();

    public FlowModule(FlowStorage storage, Codec codec, int channelId, TriggerRegistry triggerRegistry, GlobalTriggers globalTriggers,
                      FlowRegistry flowRegistry, NodeDefinitionRegistry definitionRegistry,
                      PropertyRegistry propertyRegistry, CustomContentStorage customContentStorage, CustomContentService customContentService) {
        this(storage, codec, channelId, triggerRegistry, globalTriggers, flowRegistry, definitionRegistry, propertyRegistry, customContentStorage, customContentService, null);
    }

    public FlowModule(FlowStorage storage, Codec codec, int channelId, TriggerRegistry triggerRegistry, GlobalTriggers globalTriggers,
                      FlowRegistry flowRegistry, NodeDefinitionRegistry definitionRegistry,
                      PropertyRegistry propertyRegistry, CustomContentStorage customContentStorage, CustomContentService customContentService,
                      ReSyncExtensionData extensionData) {
        this(storage, codec, channelId, triggerRegistry, globalTriggers, flowRegistry, definitionRegistry, propertyRegistry, customContentStorage, customContentService, extensionData, null);
    }

    public FlowModule(FlowStorage storage, Codec codec, int channelId, TriggerRegistry triggerRegistry, GlobalTriggers globalTriggers,
                      FlowRegistry flowRegistry, NodeDefinitionRegistry definitionRegistry,
                      PropertyRegistry propertyRegistry, CustomContentStorage customContentStorage, CustomContentService customContentService,
                      ReSyncExtensionData extensionData, OptionCatalogRegistry optionCatalogRegistry) {
        this(storage, codec, channelId, triggerRegistry, globalTriggers, flowRegistry, definitionRegistry, propertyRegistry, customContentStorage, customContentService, extensionData, optionCatalogRegistry, null);
    }

    public FlowModule(FlowStorage storage, Codec codec, int channelId, TriggerRegistry triggerRegistry, GlobalTriggers globalTriggers,
                      FlowRegistry flowRegistry, NodeDefinitionRegistry definitionRegistry,
                      PropertyRegistry propertyRegistry, CustomContentStorage customContentStorage, CustomContentService customContentService,
                      ReSyncExtensionData extensionData, OptionCatalogRegistry optionCatalogRegistry, ReSyncJsonResourceStorage jsonResourceStorage) {
        this.storage = storage;
        this.definitionRegistry = definitionRegistry;
        this.sender = new FlowPacketSender(codec, channelId, subscribedSessions);
        this.blueprintHandler = new FlowBlueprintPacketHandler(storage, triggerRegistry, globalTriggers, sender);
        this.resourceRouter = new FlowResourcePacketRouter(storage, customContentStorage, jsonResourceStorage, sender);
        this.placeholderPreviewHandler = new FlowPlaceholderPreviewHandler(sender);
        this.optionCatalogHandler = new FlowOptionCatalogPacketHandler(sender, customContentService, optionCatalogRegistry);
        this.nodeRegistryHandler = new FlowNodeRegistryPacketHandler(definitionRegistry, sender, propertyRegistry, customContentService, extensionData);
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
            if (!resourceRouter.handle(session, packetId, buffer)) {
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
                    case 0x27 -> placeholderPreviewHandler.handle(session, buffer);
                    case 0x37 -> optionCatalogHandler.handle(session, buffer);
                    case 0x40 -> handleTraceToggle(session, buffer);
                    case 0x43 -> handleTraceClear(session);
                    case 0x45 -> sender.sendJobSnapshot(session, session.getClientId());
                    case 0x46 -> handleDebugCommand(session, buffer);
                    default -> {
                        Log.warn("Unknown flow packet: 0x" + String.format("%02X", packetId));
                        sender.sendError(session, "UNKNOWN_PACKET", "Unknown flow packet: 0x" + String.format("%02X", packetId));
                    }
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

        snapshot.setPlugins(nodeRegistryHandler.buildPluginPayloads());
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

    public void setDebugService(FlowDebugService debugService) {
        this.debugService = debugService;
    }

    public void setExecutor(FlowExecutor executor) {
        this.executor = executor;
    }

    private void handleTraceToggle(Session session, ByteBuffer buffer) {
        if (traceService == null) {
            sender.sendTraceSnapshot(session, List.of());
            return;
        }
        boolean enabled = buffer.remaining() <= 0 || buffer.get() != 0;
        traceService.setEnabled(enabled);
        if (enabled) {
            ensureTraceSink();
        }
        sender.sendTraceSnapshot(session, traceService.snapshot());
    }

    private void handleTraceClear(Session session) {
        if (traceService != null) {
            traceService.clear();
        }
        sender.sendTraceSnapshot(session, List.of());
    }

    private void handleDebugCommand(Session session, ByteBuffer buffer) {
        if (debugService == null) {
            sender.sendDebugSnapshot(session, Map.of("type", "snapshot", "enabled", false));
            return;
        }
        String json = readRemaining(buffer);
        JsonObject root = json == null || json.isBlank() ? new JsonObject() : gson.fromJson(json, JsonObject.class);
        String action = string(root, "action");
        switch (action) {
            case "enable" -> {
                ensureTraceSink();
                debugService.setEnabled(true);
            }
            case "disable" -> debugService.setEnabled(false);
            case "pause" -> debugService.pauseAll();
            case "resume" -> debugService.resume(string(root, "sessionId"), "Resumed", "");
            case "resumeAll" -> debugService.resumeAll("Resumed");
            case "stepInto" -> debugService.resume(string(root, "sessionId"), "Step Into", "into");
            case "stepOver" -> debugService.resume(string(root, "sessionId"), "Step Over", "over");
            case "stepOut" -> debugService.resume(string(root, "sessionId"), "Step Out", "out");
            case "stop" -> debugService.stop(string(root, "sessionId"));
            case "clear" -> debugService.clear();
            case "breakpoints" -> debugService.setBreakpoints(string(root, "graphId"), nodeSet(root.get("nodeIds")));
            case "testRun" -> startDebugTestRun(root);
            default -> {
            }
        }
        sender.sendDebugSnapshot(session, debugService.snapshot());
    }

    private void ensureTraceSink() {
        if (traceService != null && traceSink == null) {
            traceSink = record -> {
                for (Session subscribedSession : subscribedSessions) {
                    sender.sendTraceEvent(subscribedSession, record);
                }
            };
            traceService.addSink(traceSink);
        }
    }

    private void startDebugTestRun(JsonObject root) {
        if (executor == null) {
            return;
        }
        String graphId = string(root, "graphId");
        FlowGraph graph = graphId != null && !graphId.isBlank() ? storage.getGraph(graphId) : null;
        if (graph == null) {
            return;
        }
        String nodeId = string(root, "nodeId");
        if (nodeId == null || nodeId.isBlank()) {
            nodeId = firstExecutableNode(graph);
        }
        if (nodeId != null && !nodeId.isBlank()) {
            executor.execute(graph, nodeId, null, null);
        }
    }

    private String firstExecutableNode(FlowGraph graph) {
        if (graph == null || graph.getNodes() == null || graph.getNodes().isEmpty()) {
            return null;
        }
        for (Map.Entry<String, restudio.flow.data.FlowNode> entry : graph.getNodes().entrySet()) {
            if (entry.getValue() != null && entry.getValue().getType() != null
                && (entry.getValue().getType().startsWith("event.") || entry.getValue().getType().startsWith("event:"))) {
                return entry.getKey();
            }
        }
        return graph.getNodes().keySet().stream().sorted(String.CASE_INSENSITIVE_ORDER).findFirst().orElse(null);
    }

    private Set<String> nodeSet(JsonElement element) {
        Set<String> values = ConcurrentHashMap.newKeySet();
        if (element != null && element.isJsonArray()) {
            for (JsonElement item : element.getAsJsonArray()) {
                if (item != null && !item.isJsonNull()) {
                    values.add(item.getAsString());
                }
            }
        }
        return values;
    }

    private String string(JsonObject root, String key) {
        if (root == null || key == null || !root.has(key) || root.get(key).isJsonNull()) {
            return "";
        }
        return root.get(key).getAsString();
    }

    private String readRemaining(ByteBuffer buffer) {
        if (buffer == null || !buffer.hasRemaining()) {
            return "";
        }
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }
}
