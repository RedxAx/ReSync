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
import restudio.resync.contracts.ReSyncProtocolContract;
import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowNode;
import restudio.flow.data.FlowSerializer;
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
import restudio.resync.flow.FlowValueCodecRegistry;
import restudio.resync.flow.FlowStorage;
import restudio.resync.flow.GlobalTriggers;
import restudio.resync.flow.FlowRegistry;
import restudio.resync.flow.handler.property.PropertyRegistry;
import restudio.resync.flow.jobs.FlowJobRegistry;
import restudio.resync.flow.registry.NodeDefinitionRegistry;
import restudio.resync.flow.sync.NodeRegistrySnapshot;
import restudio.resync.flow.diagnostics.FlowDebugService;
import restudio.resync.flow.diagnostics.FlowTraceService;
import restudio.resync.flow.diagnostics.FlowTraceSink;
import restudio.resync.flow.util.TextFormatter;
import restudio.resync.flow.testing.FlowFunctionTestHarness;
import restudio.resync.messages.MessageLogService;
import restudio.resync.flow.triggers.TriggerRegistry;
import restudio.resync.modules.flow.FlowBlueprintPacketHandler;
import restudio.resync.modules.flow.BuiltinOptionCatalogService;
import restudio.resync.modules.flow.FlowNodeRegistryPacketHandler;
import restudio.resync.modules.flow.FlowOptionCatalogPacketHandler;
import restudio.resync.modules.flow.FlowPacketSender;
import restudio.resync.modules.flow.FlowPlaceholderPreviewHandler;
import restudio.resync.modules.flow.FlowResourcePacketRouter;
import restudio.resync.modules.flow.FlowResourceRegistry;
import restudio.resync.player.PlayerSessionLinkService;
import restudio.resync.protocol.Codec;
import restudio.resync.protocol.messages.DataMessage;
import restudio.resync.protocol.messages.SubscribeRequest;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

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
    private final ItemAttributeSchemaService quickEditAttributeService;
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
        this(storage, codec, channelId, triggerRegistry, globalTriggers, flowRegistry, definitionRegistry, propertyRegistry, customContentStorage, customContentService, extensionData,
            optionCatalogRegistry, jsonResourceStorage, messageLogService, sessionLinkService,
            new BuiltinOptionCatalogService(() -> customContentService, new ItemAttributeSchemaService()));
    }

    public FlowModule(FlowStorage storage, Codec codec, int channelId, TriggerRegistry triggerRegistry, GlobalTriggers globalTriggers,
                      FlowRegistry flowRegistry, NodeDefinitionRegistry definitionRegistry,
                      PropertyRegistry propertyRegistry, CustomContentStorage customContentStorage, CustomContentService customContentService,
                      ReSyncExtensionData extensionData, OptionCatalogRegistry optionCatalogRegistry, ReSyncJsonResourceStorage jsonResourceStorage, MessageLogService messageLogService,
                      PlayerSessionLinkService sessionLinkService, BuiltinOptionCatalogService builtinOptionCatalogs) {
        this(storage, codec, channelId, triggerRegistry, globalTriggers, flowRegistry, definitionRegistry, propertyRegistry, customContentStorage, customContentService,
            extensionData, optionCatalogRegistry, jsonResourceStorage, messageLogService, sessionLinkService, builtinOptionCatalogs, new FlowResourceRegistry());
    }

    public FlowModule(FlowStorage storage, Codec codec, int channelId, TriggerRegistry triggerRegistry, GlobalTriggers globalTriggers,
                      FlowRegistry flowRegistry, NodeDefinitionRegistry definitionRegistry,
                      PropertyRegistry propertyRegistry, CustomContentStorage customContentStorage, CustomContentService customContentService,
                      ReSyncExtensionData extensionData, OptionCatalogRegistry optionCatalogRegistry, ReSyncJsonResourceStorage jsonResourceStorage, MessageLogService messageLogService,
                      PlayerSessionLinkService sessionLinkService, BuiltinOptionCatalogService builtinOptionCatalogs, FlowResourceRegistry resourceRegistry) {
        this(storage, codec, channelId, triggerRegistry, globalTriggers, flowRegistry, definitionRegistry, propertyRegistry, customContentStorage, customContentService,
            extensionData, optionCatalogRegistry, jsonResourceStorage, messageLogService, sessionLinkService, builtinOptionCatalogs, resourceRegistry, new FlowValueCodecRegistry());
    }

    public FlowModule(FlowStorage storage, Codec codec, int channelId, TriggerRegistry triggerRegistry, GlobalTriggers globalTriggers,
                      FlowRegistry flowRegistry, NodeDefinitionRegistry definitionRegistry,
                      PropertyRegistry propertyRegistry, CustomContentStorage customContentStorage, CustomContentService customContentService,
                      ReSyncExtensionData extensionData, OptionCatalogRegistry optionCatalogRegistry, ReSyncJsonResourceStorage jsonResourceStorage, MessageLogService messageLogService,
                      PlayerSessionLinkService sessionLinkService, BuiltinOptionCatalogService builtinOptionCatalogs, FlowResourceRegistry resourceRegistry,
                      FlowValueCodecRegistry valueCodecs) {
        this(storage, codec, channelId, triggerRegistry, globalTriggers, flowRegistry, definitionRegistry, propertyRegistry, customContentStorage, customContentService,
            extensionData, optionCatalogRegistry, jsonResourceStorage, messageLogService, sessionLinkService, builtinOptionCatalogs, resourceRegistry, valueCodecs, null);
    }

    public FlowModule(FlowStorage storage, Codec codec, int channelId, TriggerRegistry triggerRegistry, GlobalTriggers globalTriggers,
                      FlowRegistry flowRegistry, NodeDefinitionRegistry definitionRegistry,
                      PropertyRegistry propertyRegistry, CustomContentStorage customContentStorage, CustomContentService customContentService,
                      ReSyncExtensionData extensionData, OptionCatalogRegistry optionCatalogRegistry, ReSyncJsonResourceStorage jsonResourceStorage, MessageLogService messageLogService,
                      PlayerSessionLinkService sessionLinkService, BuiltinOptionCatalogService builtinOptionCatalogs, FlowResourceRegistry resourceRegistry,
                      FlowValueCodecRegistry valueCodecs, FlowJobRegistry flowJobs) {
        BuiltinOptionCatalogService catalogs = builtinOptionCatalogs != null ? builtinOptionCatalogs : new BuiltinOptionCatalogService(() -> customContentService, new ItemAttributeSchemaService());
        catalogs.registerProviders(optionCatalogRegistry);
        this.quickEditAttributeService = catalogs.itemAttributeSchemaService();
        this.storage = storage;
        this.definitionRegistry = definitionRegistry;
        this.customContentStorage = customContentStorage;
        this.sessionLinkService = sessionLinkService;
        this.sender = new FlowPacketSender(codec, channelId, subscribedSessions, flowJobs);
        this.blueprintHandler = new FlowBlueprintPacketHandler(storage, triggerRegistry, globalTriggers, sender);
        this.placeholderPreviewHandler = new FlowPlaceholderPreviewHandler(sender);
        this.optionCatalogHandler = new FlowOptionCatalogPacketHandler(sender, optionCatalogRegistry, catalogs);
        FlowResourceRegistry resources = resourceRegistry != null ? resourceRegistry : new FlowResourceRegistry();
        resources.setChangeListener(optionCatalogHandler::broadcastCatalog);
        this.resourceRouter = new FlowResourcePacketRouter(storage, customContentStorage, customContentService, jsonResourceStorage, sender, messageLogService,
            optionCatalogHandler::broadcastCustomContentCatalogs, resources, optionCatalogHandler::broadcastCatalog, quickEditAttributeService);
        this.nodeRegistryHandler = new FlowNodeRegistryPacketHandler(definitionRegistry, sender, propertyRegistry, customContentService, extensionData, optionCatalogRegistry, resources, valueCodecs);
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
                    case ReSyncProtocolContract.FLOW_PACKET_REQUEST -> blueprintHandler.handleRequest(session, buffer);
                    case ReSyncProtocolContract.FLOW_PACKET_DATA -> {
                    }
                    case ReSyncProtocolContract.FLOW_PACKET_SAVE -> blueprintHandler.handleSave(session, buffer);
                    case ReSyncProtocolContract.FLOW_PACKET_GUI_STATE -> {
                    }
                    case ReSyncProtocolContract.FLOW_PACKET_TRIGGER_UPDATE -> blueprintHandler.handleTriggerUpdate(session, buffer);
                    case ReSyncProtocolContract.FLOW_PACKET_DELETE -> blueprintHandler.handleDelete(session, buffer);
                    case ReSyncProtocolContract.FLOW_PACKET_LIST_REQUEST -> blueprintHandler.handleListRequest(session);
                    case ReSyncProtocolContract.FLOW_PACKET_NODE_REGISTRY_REQUEST -> nodeRegistryHandler.handleRequest(session, buffer);
                    case ReSyncProtocolContract.FLOW_PACKET_PLACEHOLDER_PREVIEW_REQUEST -> placeholderPreviewHandler.handle(session, buffer);
                    case ReSyncProtocolContract.FLOW_PACKET_OPTION_CATALOG_REQUEST -> optionCatalogHandler.handle(session, buffer);
                    case ReSyncProtocolContract.FLOW_PACKET_TRACE_TOGGLE -> handleTraceToggle(session, buffer);
                    case ReSyncProtocolContract.FLOW_PACKET_TRACE_CLEAR -> handleTraceClear(session);
                    case ReSyncProtocolContract.FLOW_PACKET_JOB_SNAPSHOT_REQUEST -> {
                        sender.sendJobSnapshot(session, session.getClientId());
                        sender.sendScheduledTaskSnapshot(session, executor != null ? executor.getScheduledTaskSnapshots() : List.of());
                    }
                    case ReSyncProtocolContract.FLOW_PACKET_DEBUG_COMMAND -> handleDebugCommand(session, buffer);
                    case ReSyncProtocolContract.FLOW_PACKET_FUNCTION_TEST_REQUEST -> handleFunctionTest(session, buffer);
                    case ReSyncProtocolContract.FLOW_PACKET_QUICK_EDIT_APPLY -> handleQuickEditApply(session, buffer);
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

        if (packetId == ReSyncProtocolContract.FLOW_PACKET_SAVE || packetId == ReSyncProtocolContract.FLOW_PACKET_DELETE) {
            refreshCustomFunctionDefinitions();
        }
    }

    public void refreshCustomFunctionDefinitions() {
        CustomFunctionNodeDefinitions.rebuild(definitionRegistry, storage);
        sender.broadcastNodeRegistry(buildFullNodeRegistrySnapshot());
    }

    private NodeRegistrySnapshot buildFullNodeRegistrySnapshot() {
        return nodeRegistryHandler.buildFullSnapshot();
    }

    public String getNodeRegistryChecksum() {
        return nodeRegistryHandler.computeRegistryChecksum();
    }

    public void setNodeRegistryDiagnosticsSupplier(Supplier<Map<String, Object>> diagnosticsSupplier) {
        nodeRegistryHandler.setDiagnosticsSupplier(diagnosticsSupplier);
    }

    public void broadcastOptionCatalog(String sourceId) {
        if (sourceId != null && !sourceId.isBlank()) {
            optionCatalogHandler.broadcastCatalog(sourceId);
        }
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
            sender.sendJsonPayload(target, ReSyncProtocolContract.FLOW_PACKET_QUICK_EDIT_OPEN, json, "QUICK_EDIT_TOO_LARGE", "Quick edit data exceeds maximum size");
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
        sender.sendJsonPayload(session, ReSyncProtocolContract.FLOW_PACKET_OPEN_CUSTOM_CONTENT, gson.toJson(payload), "CONTENT_OPEN_TOO_LARGE", "Content open data exceeds maximum size");
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
        definition.setComponents(quickEditAttributeService.customComponentsFromStack(item));
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
        definition.setComponents(quickEditAttributeService.customComponentsForMaterial(material.name(), definition.getComponents()));
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
        sender.sendJsonPayload(session, ReSyncProtocolContract.FLOW_PACKET_QUICK_EDIT_RESULT, gson.toJson(Map.of("sessionId", editSession.sessionId(), "status", "applied")), "QUICK_EDIT_RESULT_TOO_LARGE", "Quick edit result exceeds maximum size");
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
        sender.sendJsonPayload(session, ReSyncProtocolContract.FLOW_PACKET_QUICK_EDIT_RESULT, gson.toJson(payload), "QUICK_EDIT_RESULT_TOO_LARGE", "Quick edit result exceeds maximum size");
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

    private void handleFunctionTest(Session session, ByteBuffer buffer) {
        JsonObject request;
        try {
            String json = readRemaining(buffer);
            request = json == null || json.isBlank() ? new JsonObject() : gson.fromJson(json, JsonObject.class);
        } catch (RuntimeException exception) {
            sendFunctionTestFailure(session, "", "", "INVALID_FIXTURE", "Function fixture is invalid");
            return;
        }
        String requestId = string(request, "requestId");
        String graphId = string(request, "graphId");
        FlowGraph graph;
        try {
            graph = request.has("graph") && request.get("graph").isJsonObject()
                ? FlowSerializer.deserialize(request.get("graph").toString())
                : graphId != null && !graphId.isBlank() ? storage.getGraph(graphId) : null;
        } catch (RuntimeException exception) {
            sendFunctionTestFailure(session, requestId, graphId, "INVALID_FUNCTION_GRAPH", "Function graph is invalid");
            return;
        }
        if (executor == null) {
            sendFunctionTestFailure(session, requestId, graphId, "EXECUTOR_UNAVAILABLE", "Flow executor is unavailable");
            return;
        }
        if (graph == null || !graph.isFunction()) {
            sendFunctionTestFailure(session, requestId, graphId, "FUNCTION_NOT_FOUND", "Function not found: " + graphId);
            return;
        }
        Map<String, Object> inputs = objectMap(request.get("inputs"));
        Map<String, Object> expectedOutputs = objectMap(request.get("expectedOutputs"));
        Map<String, Object> serverContext = objectMap(request.get("serverContext"));
        long timeoutMillis = Math.clamp(longValue(request, "timeoutMillis", 5000L), 1L, 30000L);
        Clock fixtureClock;
        try {
            ZoneId zone = ZoneId.of(defaultText(string(request, "zoneId"), "UTC"));
            String instant = string(request, "clockInstant");
            fixtureClock = instant == null || instant.isBlank() ? Clock.system(zone) : Clock.fixed(Instant.parse(instant), zone);
        } catch (RuntimeException exception) {
            sendFunctionTestFailure(session, requestId, graphId, "INVALID_FIXTURE_CLOCK", "Fixture clock or time zone is invalid");
            return;
        }
        FlowFunctionTestHarness harness = new FlowFunctionTestHarness(executor, fixtureClock);
        FlowFunctionTestHarness.Fixture fixture = new FlowFunctionTestHarness.Fixture(defaultText(string(request, "name"), "Fixture"), inputs,
            expectedOutputs, serverContext, null, null, Duration.ofMillis(timeoutMillis));
        harness.run(graph, fixture).whenComplete((result, failure) -> {
            if (failure != null) {
                sendFunctionTestFailure(session, requestId, graphId, "FUNCTION_TEST_FAILED", failure.getMessage());
                return;
            }
            sender.sendJsonPayload(session, ReSyncProtocolContract.FLOW_PACKET_FUNCTION_TEST_RESULT,
                gson.toJson(Map.of("requestId", requestId, "graphId", graphId, "result", result)), "FUNCTION_TEST_RESULT_TOO_LARGE",
                "Function test result exceeds maximum size");
        });
    }

    private void sendFunctionTestFailure(Session session, String requestId, String graphId, String code, String message) {
        sender.sendJsonPayload(session, ReSyncProtocolContract.FLOW_PACKET_FUNCTION_TEST_RESULT,
            gson.toJson(Map.of("requestId", defaultText(requestId, ""), "graphId", defaultText(graphId, ""), "error",
                Map.of("code", code, "message", defaultText(message, code)))), "FUNCTION_TEST_RESULT_TOO_LARGE", "Function test result exceeds maximum size");
    }

    private Map<String, Object> objectMap(JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            return Map.of();
        }
        Map<?, ?> values = gson.fromJson(element, Map.class);
        Map<String, Object> result = new LinkedHashMap<>();
        values.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    private long longValue(JsonObject root, String key, long fallback) {
        try {
            return root != null && root.has(key) && !root.get(key).isJsonNull() ? root.get(key).getAsLong() : fallback;
        } catch (RuntimeException exception) {
            return fallback;
        }
    }

    private String defaultText(String value, String fallback) {
        return value != null && !value.isBlank() ? value : fallback;
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
        for (Map.Entry<String, FlowNode> entry : graph.getNodes().entrySet()) {
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
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
