package restudio.resync.modules;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import restudio.flow.data.CustomContentDefinition;
import restudio.resync.Log;
import restudio.resync.ReSync;
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
import restudio.resync.customcontent.ItemAttributeSchemaService;
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
import restudio.resync.flow.util.TextFormatter;
import restudio.resync.messages.MessageLogService;
import restudio.resync.flow.triggers.TriggerRegistry;
import restudio.resync.modules.flow.FlowBlueprintPacketHandler;
import restudio.resync.modules.flow.FlowNodeRegistryPacketHandler;
import restudio.resync.modules.flow.FlowOptionCatalogPacketHandler;
import restudio.resync.modules.flow.FlowPacketSender;
import restudio.resync.modules.flow.FlowPlaceholderPreviewHandler;
import restudio.resync.modules.flow.FlowResourcePacketRouter;
import restudio.resync.player.PlayerSessionLinkService;
import restudio.resync.protocol.Codec;
import restudio.resync.protocol.messages.DataMessage;
import restudio.resync.protocol.messages.SubscribeRequest;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class FlowModule implements Module {
    private static final ModuleMetadata METADATA = ModuleMetadata.of("flowLegacyHandler", "FlowLegacyHandler", "flow");
    private static final long CUSTOM_CONTENT_CATALOG_POLL_INTERVAL_MS = 2000L;
    private static final long QUICK_EDIT_SESSION_TTL_MS = 30L * 60L * 1000L;
    private final FlowStorage storage;
    private final Set<Session> subscribedSessions = ConcurrentHashMap.newKeySet();
    private final FlowPacketSender sender;
    private final FlowBlueprintPacketHandler blueprintHandler;
    private final FlowResourcePacketRouter resourceRouter;
    private final FlowPlaceholderPreviewHandler placeholderPreviewHandler;
    private final FlowOptionCatalogPacketHandler optionCatalogHandler;
    private final FlowNodeRegistryPacketHandler nodeRegistryHandler;
    private final NodeDefinitionRegistry definitionRegistry;
    private final CustomContentStorage customContentStorage;
    private final PlayerSessionLinkService sessionLinkService;
    private FlowTraceService traceService;
    private FlowDebugService debugService;
    private FlowTraceSink traceSink;
    private FlowExecutor executor;
    private final Gson gson = new Gson();
    private final ItemAttributeSchemaService quickEditAttributeService = new ItemAttributeSchemaService();
    private final Map<String, QuickEditSession> quickEditSessions = new ConcurrentHashMap<>();
    private long lastCustomContentCatalogPollAt;

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
        this(storage, codec, channelId, triggerRegistry, globalTriggers, flowRegistry, definitionRegistry, propertyRegistry, customContentStorage, customContentService, extensionData, optionCatalogRegistry, jsonResourceStorage, null);
    }

    public FlowModule(FlowStorage storage, Codec codec, int channelId, TriggerRegistry triggerRegistry, GlobalTriggers globalTriggers,
                      FlowRegistry flowRegistry, NodeDefinitionRegistry definitionRegistry,
                      PropertyRegistry propertyRegistry, CustomContentStorage customContentStorage, CustomContentService customContentService,
                      ReSyncExtensionData extensionData, OptionCatalogRegistry optionCatalogRegistry, ReSyncJsonResourceStorage jsonResourceStorage, MessageLogService messageLogService) {
        this(storage, codec, channelId, triggerRegistry, globalTriggers, flowRegistry, definitionRegistry, propertyRegistry, customContentStorage, customContentService, extensionData, optionCatalogRegistry, jsonResourceStorage, messageLogService, null);
    }

    public FlowModule(FlowStorage storage, Codec codec, int channelId, TriggerRegistry triggerRegistry, GlobalTriggers globalTriggers,
                      FlowRegistry flowRegistry, NodeDefinitionRegistry definitionRegistry,
                      PropertyRegistry propertyRegistry, CustomContentStorage customContentStorage, CustomContentService customContentService,
                      ReSyncExtensionData extensionData, OptionCatalogRegistry optionCatalogRegistry, ReSyncJsonResourceStorage jsonResourceStorage, MessageLogService messageLogService,
                      PlayerSessionLinkService sessionLinkService) {
        this.storage = storage;
        this.definitionRegistry = definitionRegistry;
        this.customContentStorage = customContentStorage;
        this.sessionLinkService = sessionLinkService;
        this.sender = new FlowPacketSender(codec, channelId, subscribedSessions);
        this.blueprintHandler = new FlowBlueprintPacketHandler(storage, triggerRegistry, globalTriggers, sender);
        this.placeholderPreviewHandler = new FlowPlaceholderPreviewHandler(sender);
        this.optionCatalogHandler = new FlowOptionCatalogPacketHandler(sender, customContentService, optionCatalogRegistry);
        this.resourceRouter = new FlowResourcePacketRouter(storage, customContentStorage, customContentService, jsonResourceStorage, sender, messageLogService, optionCatalogHandler::broadcastCustomContentCatalogs);
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
    public void onTick() {
        if (subscribedSessions.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastCustomContentCatalogPollAt < CUSTOM_CONTENT_CATALOG_POLL_INTERVAL_MS) {
            return;
        }
        lastCustomContentCatalogPollAt = now;
        optionCatalogHandler.broadcastChangedCustomContentCatalogs();
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
                    case 0x61 -> handleQuickEditApply(session, buffer);
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

    public QuickEditResult startQuickEdit(Player player) {
        pruneQuickEditSessions();
        if (player == null) {
            return QuickEditResult.failure("Player Required");
        }
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held == null || held.getType().isAir()) {
            return QuickEditResult.failure("Hold An Item");
        }
        List<Session> targets = quickEditTargets(player);
        if (targets.isEmpty()) {
            return QuickEditResult.failure("No Remotely Client Connected");
        }
        if (targets.size() > 1) {
            return QuickEditResult.failure("Multiple Remotely Clients Connected");
        }
        CustomContentDefinition existingContent = existingContentDefinition(held);
        if (existingContent != null) {
            sendOpenCustomContent(targets.getFirst(), existingContent);
            return QuickEditResult.success("Content Opened");
        }
        String sessionId = UUID.randomUUID().toString();
        CustomContentDefinition definition = quickEditDefinition(sessionId, held);
        quickEditSessions.put(sessionId, new QuickEditSession(sessionId, player.getUniqueId(), normalizedSnapshot(held), System.currentTimeMillis()));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sessionId", sessionId);
        payload.put("player", player.getName());
        payload.put("definition", definition);
        String json = gson.toJson(payload);
        for (Session target : targets) {
            sender.sendJsonPayload(target, (byte) 0x60, json, "QUICK_EDIT_TOO_LARGE", "Quick edit data exceeds maximum size");
        }
        return QuickEditResult.success("Quick Edit Opened");
    }

    private List<Session> quickEditTargets(Player player) {
        Session linked = sessionLinkService != null ? sessionLinkService.getLinkedSession(player.getUniqueId()) : null;
        if (isOpenFlowSession(linked)) {
            return List.of(linked);
        }
        List<Session> open = subscribedSessions.stream()
            .filter(this::isOpenFlowSession)
            .toList();
        return open.size() == 1 ? open : open.isEmpty() ? List.of() : List.of(open.getFirst(), open.get(1));
    }

    private CustomContentDefinition existingContentDefinition(ItemStack item) {
        if (item == null || !item.hasItemMeta() || customContentStorage == null) {
            return null;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return null;
        }
        String contentId = meta.getPersistentDataContainer().get(new NamespacedKey(ReSync.getInstance(), "content_id"), PersistentDataType.STRING);
        if (contentId == null || contentId.isBlank()) {
            return null;
        }
        return customContentStorage.get(contentId);
    }

    private void sendOpenCustomContent(Session session, CustomContentDefinition definition) {
        if (definition.getGraph() == null && definition.getFlowId() != null && !definition.getFlowId().isBlank()) {
            FlowGraph graph = storage.getGraph(definition.getFlowId());
            if (graph != null) {
                definition.setGraph(graph);
            }
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", definition.getId());
        payload.put("content", definition);
        sender.sendJsonPayload(session, (byte) 0x63, gson.toJson(payload), "CONTENT_OPEN_TOO_LARGE", "Content open data exceeds maximum size");
    }

    private boolean isOpenFlowSession(Session session) {
        return session != null
            && subscribedSessions.contains(session)
            && session.getConnection() != null
            && session.getConnection().isOpen();
    }

    private CustomContentDefinition quickEditDefinition(String sessionId, ItemStack item) {
        CustomContentDefinition definition = new CustomContentDefinition();
        definition.setId("quickedit_" + sessionId.substring(0, 8));
        definition.setFlowId("quickedit." + sessionId);
        definition.setType("item");
        definition.setProvider("vanilla");
        definition.setMaterial(item.getType().name());
        definition.setDisplayName(customDisplayName(item));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            if (meta.hasLore() && meta.getLore() != null) {
                definition.setLore(meta.getLore());
            } else {
                definition.setLore(List.of());
            }
            if (meta.hasCustomModelData()) {
                definition.setCustomModelData(meta.getCustomModelData());
            }
        }
        definition.setComponents(quickEditAttributeService.componentsFromStack(item));
        definition.setTags(List.of());
        definition.setAbilities(List.of());
        return definition;
    }

    private String customDisplayName(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta != null && meta.hasDisplayName()) {
            return meta.getDisplayName();
        }
        return "";
    }

    private void handleQuickEditApply(Session session, ByteBuffer buffer) {
        pruneQuickEditSessions();
        byte[] jsonBytes = new byte[buffer.remaining()];
        buffer.get(jsonBytes);
        JsonObject root = JsonParser.parseString(new String(jsonBytes, StandardCharsets.UTF_8)).getAsJsonObject();
        String sessionId = text(root, "sessionId");
        if (sessionId.isBlank()) {
            sendQuickEditFailure(session, "", "Missing quick edit session");
            return;
        }
        QuickEditSession editSession = quickEditSessions.get(sessionId);
        if (editSession == null) {
            sendQuickEditFailure(session, sessionId, "Quick edit session expired");
            return;
        }
        JsonElement definitionElement = root.get("definition");
        if (definitionElement == null || !definitionElement.isJsonObject()) {
            sendQuickEditFailure(session, sessionId, "Missing item definition");
            return;
        }
        CustomContentDefinition definition = gson.fromJson(definitionElement, CustomContentDefinition.class);
        applyQuickEdit(session, editSession, definition);
    }

    private void applyQuickEdit(Session session, QuickEditSession editSession, CustomContentDefinition definition) {
        if (definition == null) {
            sendQuickEditFailure(session, editSession.sessionId(), "Missing item definition");
            return;
        }
        Player player = Bukkit.getPlayer(editSession.playerId());
        if (player == null || !player.isOnline()) {
            sendQuickEditFailure(session, editSession.sessionId(), "Player is offline");
            return;
        }
        ItemStack current = player.getInventory().getItemInMainHand();
        if (current == null || current.getType().isAir()) {
            sendQuickEditFailure(session, editSession.sessionId(), "Hold An Item");
            return;
        }
        if (!sameEditedItem(current, editSession.originalItem())) {
            sendQuickEditFailure(session, editSession.sessionId(), "Held Item Changed");
            return;
        }
        String materialName = definition.getMaterial();
        Material material = Material.matchMaterial(materialName == null || materialName.isBlank() ? "STICK" : materialName);
        if (material == null || !material.isItem()) {
            sendQuickEditFailure(session, editSession.sessionId(), "Invalid material: " + materialName);
            return;
        }
        List<Map<String, Object>> errors = quickEditAttributeService.validate(material.name(), definition.getComponents());
        if (!errors.isEmpty()) {
            sendQuickEditFailure(session, editSession.sessionId(), quickEditError(errors));
            return;
        }
        int amount = current.getAmount();
        ItemStack edited = current.clone();
        edited.setAmount(Math.max(1, amount));
        if (edited.getType() != material) {
            edited.setType(material);
        }
        try {
            edited = quickEditAttributeService.applyComponents(edited, definition.getComponents());
        } catch (RuntimeException failure) {
            sendQuickEditFailure(session, editSession.sessionId(), failure.getMessage());
            return;
        }
        ItemMeta meta = edited.getItemMeta();
        if (meta != null) {
            boolean hasNameComponent = hasAnyComponent(definition, "minecraft:custom_name", "minecraft:item_name");
            boolean hasLoreComponent = hasAnyComponent(definition, "minecraft:lore");
            if (!hasNameComponent) {
                if (definition.getDisplayName() != null && !definition.getDisplayName().isBlank()) {
                    meta.displayName(TextFormatter.parseItemName(definition.getDisplayName()));
                } else {
                    meta.displayName(null);
                }
            }
            if (!hasLoreComponent) {
                if (definition.getLore() != null && !definition.getLore().isEmpty()) {
                    meta.lore(definition.getLore().stream().map(TextFormatter::parseItemLore).toList());
                } else {
                    meta.lore(null);
                }
            }
            meta.setCustomModelData(definition.getCustomModelData());
            edited.setItemMeta(meta);
        }
        player.getInventory().setItemInMainHand(edited);
        quickEditSessions.put(editSession.sessionId(), new QuickEditSession(editSession.sessionId(), editSession.playerId(), normalizedSnapshot(edited), System.currentTimeMillis()));
        sender.sendJsonPayload(session, (byte) 0x62, gson.toJson(Map.of("sessionId", editSession.sessionId(), "status", "applied")), "QUICK_EDIT_RESULT_TOO_LARGE", "Quick edit result exceeds maximum size");
        player.sendMessage("§8[ReSync] §aQuick Edit Applied");
    }

    private boolean hasAnyComponent(CustomContentDefinition definition, String... keys) {
        if (definition == null || definition.getComponents() == null || definition.getComponents().isEmpty()) {
            return false;
        }
        for (String key : keys) {
            if (definition.getComponents().containsKey(key)) {
                return true;
            }
        }
        return false;
    }

    private ItemStack normalizedSnapshot(ItemStack item) {
        ItemStack snapshot = item.clone();
        snapshot.setAmount(1);
        return snapshot;
    }

    private boolean sameEditedItem(ItemStack current, ItemStack original) {
        if (current == null || original == null) {
            return false;
        }
        ItemStack currentSnapshot = normalizedSnapshot(current);
        return currentSnapshot.isSimilar(original);
    }

    private void sendQuickEditFailure(Session session, String sessionId, String message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sessionId", sessionId != null ? sessionId : "");
        payload.put("status", "failed");
        payload.put("message", message != null && !message.isBlank() ? message : "Apply Failed");
        sender.sendJsonPayload(session, (byte) 0x62, gson.toJson(payload), "QUICK_EDIT_RESULT_TOO_LARGE", "Quick edit result exceeds maximum size");
    }

    private String quickEditError(List<Map<String, Object>> errors) {
        Map<String, Object> first = errors.getFirst();
        Object component = first.getOrDefault("component", first.getOrDefault("id", ""));
        Object message = first.getOrDefault("message", "Invalid component");
        String prefix = component != null && !component.toString().isBlank() ? component + ": " : "";
        return prefix + message;
    }

    private String text(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return "";
        }
        return object.get(key).getAsString();
    }

    private void pruneQuickEditSessions() {
        long cutoff = System.currentTimeMillis() - QUICK_EDIT_SESSION_TTL_MS;
        quickEditSessions.entrySet().removeIf(entry -> entry.getValue().lastTouchedAt() < cutoff);
    }

    public record QuickEditResult(boolean success, String message) {
        public static QuickEditResult success(String message) {
            return new QuickEditResult(true, message);
        }

        public static QuickEditResult failure(String message) {
            return new QuickEditResult(false, message);
        }
    }

    private record QuickEditSession(String sessionId, UUID playerId, ItemStack originalItem, long lastTouchedAt) {
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
