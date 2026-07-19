package restudio.resync.modules.flow;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import restudio.flow.data.CustomContentDefinition;
import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowSerializer;
import restudio.flow.data.GuiDefinition;
import restudio.flow.data.ScoreboardDefinition;
import restudio.flow.data.TabDefinition;
import restudio.resync.core.Session;
import restudio.resync.customization.ReSyncJsonResourceStorage;
import restudio.resync.customcontent.CustomContentService;
import restudio.resync.customcontent.CustomContentStorage;
import restudio.resync.customcontent.CustomContentValidator;
import restudio.resync.customcontent.ItemAttributeSchemaService;
import restudio.resync.customcontent.ItemAttributeValidationException;
import restudio.resync.flow.FlowStorage;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.GuiManager;
import restudio.resync.flow.ScoreboardTemplateManager;
import restudio.resync.flow.TabListService;
import restudio.resync.messages.MessageLogService;
import restudio.resync.contracts.ReSyncProtocolContract;
import restudio.resync.resources.ReSyncManagedResource;
import restudio.resync.resources.ReSyncResourceCatalog;
import restudio.resync.runtime.JsonRuntimeResourceValidator;
import restudio.resync.world.WorldManagementService;
import restudio.resync.world.WorldRegistryEntry;
import restudio.resync.world.WorldSnapshot;
import restudio.resync.worldgen.WorldGenProjectStorage;
import restudio.resync.worldgen.data.WorldGenProject;
import restudio.resync.worldgen.data.WorldGenSerializer;
import restudio.resync.worldgen.pipeline.PipelineCompiler;
import restudio.resync.worldgen.pipeline.WorldGenCompileDiagnostics;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

public class FlowResourcePacketRouter {
    private final List<FlowResourcePacketHandler<?>> handlers = new ArrayList<>();
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final MessageLogService messageLogService;
    private final FlowPacketSender sender;
    private final Runnable customContentCatalogRefresh;
    private final FlowResourceRegistry resourceRegistry;
    private final Consumer<String> resourceCatalogRefresh;
    private final JsonRuntimeResourceValidator jsonResourceValidator;

    public FlowResourcePacketRouter(FlowStorage storage, CustomContentStorage customContentStorage, FlowPacketSender sender) {
        this(storage, customContentStorage, null, null, sender, null, null);
    }

    public FlowResourcePacketRouter(FlowStorage storage, CustomContentStorage customContentStorage, ReSyncJsonResourceStorage jsonResourceStorage, FlowPacketSender sender) {
        this(storage, customContentStorage, null, jsonResourceStorage, sender, null, null);
    }

    public FlowResourcePacketRouter(FlowStorage storage, CustomContentStorage customContentStorage, ReSyncJsonResourceStorage jsonResourceStorage, FlowPacketSender sender, MessageLogService messageLogService) {
        this(storage, customContentStorage, null, jsonResourceStorage, sender, messageLogService, null);
    }

    public FlowResourcePacketRouter(FlowStorage storage, CustomContentStorage customContentStorage, ReSyncJsonResourceStorage jsonResourceStorage, FlowPacketSender sender, MessageLogService messageLogService, Runnable customContentCatalogRefresh) {
        this(storage, customContentStorage, null, jsonResourceStorage, sender, messageLogService, customContentCatalogRefresh);
    }

    public FlowResourcePacketRouter(FlowStorage storage, CustomContentStorage customContentStorage, CustomContentService customContentService, ReSyncJsonResourceStorage jsonResourceStorage, FlowPacketSender sender, MessageLogService messageLogService, Runnable customContentCatalogRefresh) {
        this(storage, customContentStorage, customContentService, jsonResourceStorage, sender, messageLogService, customContentCatalogRefresh, new FlowResourceRegistry());
    }

    public FlowResourcePacketRouter(FlowStorage storage, CustomContentStorage customContentStorage, CustomContentService customContentService, ReSyncJsonResourceStorage jsonResourceStorage, FlowPacketSender sender, MessageLogService messageLogService, Runnable customContentCatalogRefresh,
                                    FlowResourceRegistry resourceRegistry) {
        this(storage, customContentStorage, customContentService, jsonResourceStorage, sender, messageLogService, customContentCatalogRefresh, resourceRegistry, ignored -> {
        });
    }

    public FlowResourcePacketRouter(FlowStorage storage, CustomContentStorage customContentStorage, CustomContentService customContentService, ReSyncJsonResourceStorage jsonResourceStorage, FlowPacketSender sender, MessageLogService messageLogService, Runnable customContentCatalogRefresh,
                                    FlowResourceRegistry resourceRegistry, Consumer<String> resourceCatalogRefresh) {
        this(storage, customContentStorage, customContentService, jsonResourceStorage, sender, messageLogService, customContentCatalogRefresh, resourceRegistry,
            resourceCatalogRefresh, new ItemAttributeSchemaService());
    }

    public FlowResourcePacketRouter(FlowStorage storage, CustomContentStorage customContentStorage, CustomContentService customContentService, ReSyncJsonResourceStorage jsonResourceStorage, FlowPacketSender sender, MessageLogService messageLogService, Runnable customContentCatalogRefresh,
                                    FlowResourceRegistry resourceRegistry, Consumer<String> resourceCatalogRefresh, ItemAttributeSchemaService itemAttributeSchemaService) {
        this.sender = sender;
        this.messageLogService = messageLogService;
        this.customContentCatalogRefresh = customContentCatalogRefresh;
        this.resourceRegistry = resourceRegistry != null ? resourceRegistry : new FlowResourceRegistry();
        this.resourceCatalogRefresh = resourceCatalogRefresh != null ? resourceCatalogRefresh : ignored -> {
        };
        this.jsonResourceValidator = new JsonRuntimeResourceValidator(customContentService);
        if (storage != null) {
            registerLifecycle(graphAdapter(storage, ReSyncResourceCatalog.FLOW));
            registerLifecycle(graphAdapter(storage, ReSyncResourceCatalog.FUNCTION));
            registerLifecycle(graphAdapter(storage, ReSyncResourceCatalog.COMMAND));
        }
        register(guiAdapter(storage, sender));
        register(scoreboardAdapter(storage, sender));
        register(tabAdapter(storage, sender));
        register(customContentAdapter(customContentStorage, customContentService, sender,
            itemAttributeSchemaService != null ? itemAttributeSchemaService : new ItemAttributeSchemaService()));
        register(projectMetadataAdapter(storage, sender));
        if (jsonResourceStorage != null) {
            for (String type : jsonResourceStorage.resourceTypes()) {
                register(jsonAdapter(jsonResourceStorage, type, sender));
            }
        }
    }

    public FlowResourceRegistry getResourceRegistry() {
        return resourceRegistry;
    }

    public void registerExternalLifecycle(WorldGenProjectStorage worldGenStorage, WorldManagementService worldManagementService) {
        if (worldGenStorage != null) {
            registerLifecycle(worldGenAdapter(worldGenStorage));
        }
        if (worldManagementService != null) {
            registerLifecycle(worldAdapter(worldManagementService));
        }
    }

    private <T> void register(FlowResourceAdapter<T> adapter) {
        if (resourceRegistry.get(adapter.descriptor().typeId()) == null) {
            resourceRegistry.register(adapter);
        }
        if (sender != null) {
            handlers.add(new FlowResourcePacketHandler<>(adapter, sender));
        }
    }

    private void registerLifecycle(FlowResourceAdapter<?> adapter) {
        if (resourceRegistry.get(adapter.descriptor().typeId()) == null) {
            resourceRegistry.register(adapter);
        }
    }

    private FlowResourceAdapter<FlowGraph> graphAdapter(FlowStorage storage, String resourceType) {
        ReSyncManagedResource descriptor = ReSyncResourceCatalog.byType(resourceType);
        return new FlowResourceAdapter<>() {
            @Override
            public ReSyncManagedResource descriptor() {
                return descriptor;
            }

            @Override
            public FlowGraph get(String id) {
                FlowGraph graph = storage.getGraph(id);
                return graph != null && resourceType.equals(storage.getGraphResourceType(id)) ? graph : null;
            }

            @Override
            public List<String> listIds() {
                return storage.listGraphIds(resourceType);
            }

            @Override
            public FlowGraph deserialize(String json) {
                return FlowSerializer.deserialize(json);
            }

            @Override
            public String id(FlowGraph value) {
                return value != null ? value.getId() : "";
            }

            @Override
            public void save(FlowGraph value) {
                storage.saveGraph(value);
            }

            @Override
            public void delete(String id) {
                if (!resourceType.equals(storage.getGraphResourceType(id))) {
                    throw new IllegalArgumentException(descriptor.displayName() + " not found: " + id);
                }
                storage.deleteGraph(id);
            }

            @Override
            public void validate(FlowGraph value) {
                storage.requireValidGraph(value);
                String actualType = storage.graphResourceType(value);
                if (!resourceType.equals(actualType)) {
                    throw new IllegalArgumentException("Expected " + resourceType + " graph but received " + actualType);
                }
            }

            @Override
            public FlowGraph duplicate(FlowGraph value, String targetId) {
                FlowGraph copy = FlowSerializer.deserialize(FlowSerializer.serialize(value));
                copy.setId(targetId);
                return copy;
            }

            @Override
            public FlowGraph reload(String id) {
                FlowGraph graph = storage.reloadGraph(id);
                return graph != null && resourceType.equals(storage.getGraphResourceType(id)) ? graph : null;
            }

            @Override
            public Set<String> supportedOperations() {
                return Set.of("discover", "query", "get", "create", "validate", "save", "update", "duplicate", "reload", "delete");
            }

            @Override
            public String identityRules() {
                return "stable_graph_id_and_resource_type";
            }

            @Override
            public String authoritativeService() {
                return "FlowStorage";
            }

            @Override
            public boolean changeEvents() {
                return true;
            }

            @Override
            public boolean activeRefresh() {
                return true;
            }
        };
    }

    private FlowResourceAdapter<WorldGenProject> worldGenAdapter(WorldGenProjectStorage storage) {
        return new FlowResourceAdapter<>() {
            @Override
            public ReSyncManagedResource descriptor() {
                return ReSyncResourceCatalog.byType(ReSyncResourceCatalog.WORLDGEN);
            }

            @Override
            public WorldGenProject get(String id) {
                return storage.getProject(id);
            }

            @Override
            public List<String> listIds() {
                return storage.listProjectIds();
            }

            @Override
            public WorldGenProject deserialize(String json) {
                return WorldGenSerializer.deserializeProject(json);
            }

            @Override
            public String id(WorldGenProject value) {
                return value != null ? value.getId() : "";
            }

            @Override
            public void save(WorldGenProject value) {
                storage.saveProject(value);
            }

            @Override
            public void delete(String id) {
                storage.deleteProject(id);
            }

            @Override
            public void validate(WorldGenProject value) {
                WorldGenCompileDiagnostics diagnostics = PipelineCompiler.diagnoseProject(value);
                if (!diagnostics.isSuccess()) {
                    String message = diagnostics.getDiagnostics().isEmpty() ? "WorldGen Compile Failed" : diagnostics.getDiagnostics().getFirst().message();
                    throw new IllegalArgumentException(message);
                }
            }

            @Override
            public WorldGenProject duplicate(WorldGenProject value, String targetId) {
                WorldGenProject copy = WorldGenSerializer.deserializeProject(WorldGenSerializer.serializeProject(value));
                copy.setId(targetId);
                return copy;
            }

            @Override
            public WorldGenProject reload(String id) {
                return storage.reloadProject(id, this::validate);
            }

            @Override
            public Set<String> supportedOperations() {
                return resourceOperationsWithDuplicateAndReload();
            }

            @Override
            public String authoritativeService() {
                return "WorldGenProjectStorage";
            }

            @Override
            public boolean changeEvents() {
                return true;
            }
        };
    }

    private FlowResourceAdapter<WorldRegistryEntry> worldAdapter(WorldManagementService service) {
        return new FlowResourceAdapter<>() {
            @Override
            public ReSyncManagedResource descriptor() {
                return ReSyncResourceCatalog.byType(ReSyncResourceCatalog.WORLD);
            }

            @Override
            public WorldRegistryEntry get(String id) {
                if (id == null || id.isBlank()) {
                    return null;
                }
                return snapshot().getWorlds().stream()
                    .filter(value -> value != null && id.equalsIgnoreCase(value.getWorldName()))
                    .findFirst()
                    .map(WorldRegistryEntry::copy)
                    .orElse(null);
            }

            @Override
            public List<String> listIds() {
                return snapshot().getWorlds().stream()
                    .filter(value -> value != null && value.getWorldName() != null && !value.getWorldName().isBlank())
                    .map(WorldRegistryEntry::getWorldName)
                    .distinct()
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .toList();
            }

            @Override
            public WorldRegistryEntry deserialize(String json) {
                throw new UnsupportedOperationException("World resources are managed through WorldManagementService operations");
            }

            @Override
            public String id(WorldRegistryEntry value) {
                return value != null ? value.getWorldName() : "";
            }

            @Override
            public void save(WorldRegistryEntry value) {
                throw new UnsupportedOperationException("World resources are managed through WorldManagementService operations");
            }

            @Override
            public void delete(String id) {
                throw new UnsupportedOperationException("World resources are managed through WorldManagementService operations");
            }

            @Override
            public Set<String> supportedOperations() {
                return Set.of("discover", "query", "get");
            }

            @Override
            public String identityRules() {
                return "case_insensitive_world_name";
            }

            @Override
            public String lifecycle() {
                return "runtime_managed";
            }

            @Override
            public String authoritativeService() {
                return "WorldManagementService";
            }

            @Override
            public boolean changeEvents() {
                return true;
            }

            @Override
            public boolean activeRefresh() {
                return true;
            }

            private WorldSnapshot snapshot() {
                WorldSnapshot snapshot = service.createSnapshot();
                return snapshot != null ? snapshot : new WorldSnapshot();
            }
        };
    }

    public boolean handle(Session session, byte packetId, ByteBuffer buffer) {
        if (packetId == ReSyncProtocolContract.MESSAGE_LOG_PACKET_REQUEST) {
            handleMessageLogRequest(session, buffer);
            return true;
        }
        for (FlowResourcePacketHandler<?> handler : handlers) {
            if (handler.handle(session, packetId, buffer)) {
                return true;
            }
        }
        return false;
    }

    private void handleMessageLogRequest(Session session, ByteBuffer buffer) {
        JsonObject payload;
        try {
            JsonObject request = readJsonObject(buffer);
            int page = intValue(request, "page", 0);
            int pageSize = intValue(request, "pageSize", 20);
            String query = stringValue(request, "query");
            String source = stringValue(request, "source");
            payload = messageLogService != null ? messageLogService.page(page, pageSize, query, source) : emptyMessageLogPage(page, pageSize, query, source);
        } catch (IllegalArgumentException exception) {
            payload = emptyMessageLogPage(0, 20, "", "");
            payload.addProperty("success", false);
            payload.addProperty("errorCode", "MESSAGE_LOG_REQUEST_INVALID");
            payload.addProperty("message", exception.getMessage());
        }
        sender.sendJsonPayload(session, ReSyncProtocolContract.MESSAGE_LOG_PACKET_RESPONSE, gson.toJson(payload), "MESSAGE_LOG_TOO_LARGE", "Message log page exceeds maximum size");
    }

    private JsonObject readJsonObject(ByteBuffer buffer) {
        if (buffer == null || !buffer.hasRemaining()) {
            return new JsonObject();
        }
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        String json = new String(bytes, StandardCharsets.UTF_8);
        if (json.isBlank()) {
            return new JsonObject();
        }
        try {
            return JsonParser.parseString(json).getAsJsonObject();
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Message log request must be a JSON object", exception);
        }
    }

    private int intValue(JsonObject json, String key, int fallback) {
        if (json == null || key == null || !json.has(key) || json.get(key).isJsonNull()) {
            return fallback;
        }
        try {
            return json.get(key).getAsInt();
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Message log field must be an integer: " + key, exception);
        }
    }

    private String stringValue(JsonObject json, String key) {
        if (json == null || key == null || !json.has(key) || json.get(key).isJsonNull()) {
            return "";
        }
        try {
            return json.get(key).getAsString();
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Message log field must be text: " + key, exception);
        }
    }

    private JsonObject emptyMessageLogPage(int page, int pageSize, String query, String source) {
        JsonObject payload = new JsonObject();
        payload.addProperty("page", Math.max(0, page));
        payload.addProperty("pageSize", Math.max(1, pageSize));
        payload.addProperty("total", 0);
        payload.addProperty("query", query != null ? query : "");
        payload.addProperty("source", source != null ? source : "");
        payload.add("entries", new JsonArray());
        return payload;
    }

    private FlowResourceAdapter<GuiDefinition> guiAdapter(FlowStorage storage, FlowPacketSender sender) {
        return new FlowResourceAdapter<>() {
            @Override
            public ReSyncManagedResource descriptor() {
                return ReSyncResourceCatalog.byType(ReSyncResourceCatalog.GUI);
            }

            @Override
            public GuiDefinition get(String id) {
                return storage.getGui(id);
            }

            @Override
            public List<String> listIds() {
                return storage.listGuiIds();
            }

            @Override
            public GuiDefinition deserialize(String json) {
                return FlowSerializer.deserializeGui(json);
            }

            @Override
            public String id(GuiDefinition value) {
                return value.getId();
            }

            @Override
            public void save(GuiDefinition value) {
                storage.saveGui(value);
            }

            @Override
            public void delete(String id) {
                storage.deleteGui(id);
            }

            @Override
            public GuiDefinition duplicate(GuiDefinition value, String targetId) {
                GuiDefinition copy = value.copy();
                copy.setId(targetId);
                return copy;
            }

            @Override
            public Set<String> supportedOperations() {
                return resourceOperationsWithDuplicateAndApply();
            }

            @Override
            public Object apply(GuiDefinition value, Object context) {
                if (!(context instanceof FlowContext flowContext) || flowContext.getPlayer() == null) {
                    throw new IllegalArgumentException("GUI application requires a player context");
                }
                GuiManager manager = GuiManager.activeManager();
                if (manager == null) {
                    throw new IllegalStateException("GUI runtime is unavailable");
                }
                manager.openGui(flowContext.getPlayer(), value);
                return value.getId().equals(manager.getOpenGuiId(flowContext.getPlayer()));
            }

            @Override
            public void sendData(Session session, GuiDefinition value) {
                sender.sendGuiData(session, value);
            }

            @Override
            public void sendList(Session session, List<String> ids) {
                sender.sendGuiList(session, ids);
            }

            @Override
            public void sendSaveAck(Session session, String id) {
                sender.sendGuiSaveAck(session, id);
            }

            @Override
            public void sendSaveAck(Session session, String id, String requestId) {
                sender.sendGuiSaveAck(session, id, requestId);
            }

            @Override
            public void afterSave(GuiDefinition value) {
                GuiManager.refreshOpenGuis(value);
            }

            @Override
            public void afterSave(Session session, GuiDefinition value) {
                afterSave(value);
                if (session != null) {
                    GuiManager.refreshSessionGui(session, value);
                }
            }

            @Override
            public boolean activeRefresh() {
                return true;
            }

            @Override
            public String saveAction() {
                return "saveGui";
            }

            @Override
            public String deleteAction() {
                return "deleteGui";
            }
        };
    }

    private FlowResourceAdapter<ScoreboardDefinition> scoreboardAdapter(FlowStorage storage, FlowPacketSender sender) {
        return new FlowResourceAdapter<>() {
            @Override
            public ReSyncManagedResource descriptor() {
                return ReSyncResourceCatalog.byType(ReSyncResourceCatalog.SCOREBOARD);
            }

            @Override
            public ScoreboardDefinition get(String id) {
                return storage.getScoreboard(id);
            }

            @Override
            public List<String> listIds() {
                return storage.listScoreboardIds();
            }

            @Override
            public ScoreboardDefinition deserialize(String json) {
                return FlowSerializer.deserializeScoreboard(json);
            }

            @Override
            public String id(ScoreboardDefinition value) {
                return value.getId();
            }

            @Override
            public void save(ScoreboardDefinition value) {
                storage.saveScoreboard(value);
            }

            @Override
            public void delete(String id) {
                storage.deleteScoreboard(id);
            }

            @Override
            public ScoreboardDefinition duplicate(ScoreboardDefinition value, String targetId) {
                ScoreboardDefinition copy = gson.fromJson(gson.toJson(value), ScoreboardDefinition.class);
                copy.setId(targetId);
                return copy;
            }

            @Override
            public Set<String> supportedOperations() {
                return resourceOperationsWithDuplicateAndApply();
            }

            @Override
            public Object apply(ScoreboardDefinition value, Object context) {
                if (!(context instanceof FlowContext flowContext) || flowContext.getPlayer() == null) {
                    throw new IllegalArgumentException("Scoreboard application requires a player context");
                }
                if (!ScoreboardTemplateManager.showTemplate(flowContext.getPlayer(), value, true)) {
                    throw new IllegalStateException("Scoreboard could not be applied");
                }
                return true;
            }

            @Override
            public void sendData(Session session, ScoreboardDefinition value) {
                sender.sendScoreboardData(session, value);
            }

            @Override
            public void sendList(Session session, List<String> ids) {
                sender.sendScoreboardList(session, ids);
            }

            @Override
            public void sendSaveAck(Session session, String id) {
                sender.sendScoreboardSaveAck(session, id);
            }

            @Override
            public void sendSaveAck(Session session, String id, String requestId) {
                sender.sendScoreboardSaveAck(session, id, requestId);
            }

            @Override
            public void afterSave(ScoreboardDefinition value) {
                ScoreboardTemplateManager.refreshActiveTemplates(storage, value.getId());
            }

            @Override
            public void afterDelete(String id) {
                ScoreboardTemplateManager.clearActiveTemplateReferences(id, true);
                String defaultId = storage.getDefaultScoreboardId();
                if (defaultId != null && defaultId.equalsIgnoreCase(id)) {
                    storage.clearDefaultScoreboard();
                }
            }

            @Override
            public boolean activeRefresh() {
                return true;
            }
        };
    }

    private FlowResourceAdapter<TabDefinition> tabAdapter(FlowStorage storage, FlowPacketSender sender) {
        return new FlowResourceAdapter<>() {
            @Override
            public ReSyncManagedResource descriptor() {
                return ReSyncResourceCatalog.byType(ReSyncResourceCatalog.TAB);
            }

            @Override
            public TabDefinition get(String id) {
                return storage.getTab(id);
            }

            @Override
            public List<String> listIds() {
                return storage.listTabIds();
            }

            @Override
            public TabDefinition deserialize(String json) {
                return FlowSerializer.deserializeTab(json);
            }

            @Override
            public String id(TabDefinition value) {
                return value.getId();
            }

            @Override
            public void save(TabDefinition value) {
                storage.saveTab(value);
            }

            @Override
            public void delete(String id) {
                storage.deleteTab(id);
            }

            @Override
            public TabDefinition duplicate(TabDefinition value, String targetId) {
                TabDefinition copy = gson.fromJson(gson.toJson(value), TabDefinition.class);
                copy.setId(targetId);
                return copy;
            }

            @Override
            public Set<String> supportedOperations() {
                return resourceOperationsWithDuplicateAndApply();
            }

            @Override
            public Object apply(TabDefinition value, Object context) {
                if (!(context instanceof FlowContext flowContext) || flowContext.getPlayer() == null) {
                    throw new IllegalArgumentException("Tab application requires a player context");
                }
                if (!TabListService.applyTemplate(flowContext.getPlayer(), value, true)) {
                    throw new IllegalStateException("Tab profile could not be applied");
                }
                return true;
            }

            @Override
            public void sendData(Session session, TabDefinition value) {
                sender.sendTabData(session, value);
            }

            @Override
            public void sendList(Session session, List<String> ids) {
                sender.sendTabList(session, ids);
            }

            @Override
            public void sendSaveAck(Session session, String id) {
                sender.sendTabSaveAck(session, id);
            }

            @Override
            public void sendSaveAck(Session session, String id, String requestId) {
                sender.sendTabSaveAck(session, id, requestId);
            }

            @Override
            public void afterSave(TabDefinition value) {
                TabListService.refreshActiveTabs(storage, value.getId());
            }

            @Override
            public void afterDelete(String id) {
                TabListService.clearActiveTabReferences(id, true);
                String defaultId = storage.getDefaultTabId();
                if (defaultId != null && defaultId.equalsIgnoreCase(id)) {
                    storage.clearDefaultTab();
                }
            }

            @Override
            public boolean activeRefresh() {
                return true;
            }
        };
    }

    private FlowResourceAdapter<CustomContentDefinition> customContentAdapter(CustomContentStorage storage, CustomContentService service, FlowPacketSender sender,
                                                                                ItemAttributeSchemaService attributeSchemaService) {
        CustomContentValidator validator = new CustomContentValidator();
        return new FlowResourceAdapter<>() {
            @Override
            public ReSyncManagedResource descriptor() {
                return ReSyncResourceCatalog.byType(ReSyncResourceCatalog.CUSTOM_CONTENT);
            }

            @Override
            public CustomContentDefinition get(String id) {
                return storage.get(id);
            }

            @Override
            public List<String> listIds() {
                return storage.listIds();
            }

            @Override
            public CustomContentDefinition deserialize(String json) {
                return storage.repairMalformedFlowIdentity(FlowSerializer.deserializeCustomContent(json));
            }

            @Override
            public String id(CustomContentDefinition value) {
                return value.getId();
            }

            @Override
            public void validate(CustomContentDefinition value) {
                value.setComponents(attributeSchemaService.customComponentsForMaterial(value.getMaterial(), value.getComponents()));
                List<String> errors = validator.validate(value);
                if (!errors.isEmpty()) {
                    throw new IllegalArgumentException(String.join("; ", errors));
                }
                List<Map<String, Object>> componentErrors = attributeSchemaService.validate(value.getMaterial(), value.getComponents());
                if (!componentErrors.isEmpty()) {
                    throw new ItemAttributeValidationException(componentErrors);
                }
            }

            @Override
            public void save(CustomContentDefinition value) {
                storage.save(value);
            }

            @Override
            public void delete(String id) {
                storage.delete(id);
            }

            @Override
            public CustomContentDefinition duplicate(CustomContentDefinition value, String targetId) {
                CustomContentDefinition copy = gson.fromJson(gson.toJson(value), CustomContentDefinition.class);
                copy.setId(targetId);
                return copy;
            }

            @Override
            public void sendData(Session session, CustomContentDefinition value) {
                sender.sendCustomContentData(session, value);
            }

            @Override
            public void sendList(Session session, List<String> ids) {
                sender.sendCustomContentList(session, ids);
            }

            @Override
            public void sendSaveAck(Session session, String id) {
                sender.sendCustomContentSaveAck(session, id);
            }

            @Override
            public void sendSaveAck(Session session, String id, String requestId) {
                sender.sendCustomContentSaveAck(session, id, requestId);
            }

            @Override
            public String saveErrorCode() {
                return "CONTENT_SAVE_FAILED";
            }

            @Override
            public String deleteErrorCode() {
                return "CONTENT_DELETE_FAILED";
            }

            @Override
            public void afterSave(CustomContentDefinition value) {
                if (service != null) {
                    service.reconcileContentItems(value.getId());
                }
                refreshCustomContentCatalogs();
            }

            @Override
            public void afterDelete(String id) {
                if (service != null) {
                    service.clearContentItems(id);
                }
                refreshCustomContentCatalogs();
            }

            @Override
            public Set<String> supportedOperations() {
                return resourceOperationsWithDuplicate();
            }

            @Override
            public String authoritativeService() {
                return "CustomContentService";
            }

            @Override
            public boolean changeEvents() {
                return true;
            }

            @Override
            public boolean activeRefresh() {
                return true;
            }
        };
    }

    private void refreshCustomContentCatalogs() {
        if (customContentCatalogRefresh != null) {
            customContentCatalogRefresh.run();
        }
    }

    private void refreshResourceCatalog(String type) {
        resourceCatalogRefresh.accept("server:resync:" + type);
    }

    private FlowResourceAdapter<String> projectMetadataAdapter(FlowStorage storage, FlowPacketSender sender) {
        return new FlowResourceAdapter<>() {
            @Override
            public ReSyncManagedResource descriptor() {
                return ReSyncResourceCatalog.byType(ReSyncResourceCatalog.PROJECT_METADATA);
            }

            @Override
            public String get(String id) {
                String json = storage.getProjectMetadata(id);
                if (json == null && !"project".equals(id)) {
                    json = storage.getProjectMetadata("project");
                }
                return json;
            }

            @Override
            public List<String> listIds() {
                return storage.listProjectMetadataIds();
            }

            @Override
            public String deserialize(String json) {
                return json;
            }

            @Override
            public String id(String value) {
                return "project";
            }

            @Override
            public void save(String value) {
                storage.saveProjectMetadata(value);
            }

            @Override
            public void delete(String id) {
                storage.deleteProjectMetadata(id);
            }

            @Override
            public void sendData(Session session, String value) {
                sender.sendProjectMetadataData(session, value);
            }

            @Override
            public void sendList(Session session, List<String> ids) {
                sender.sendProjectMetadataList(session, ids);
            }

            @Override
            public void sendSaveAck(Session session, String id) {
                sender.sendProjectMetadataSaveAck(session, "project");
            }

            @Override
            public void sendSaveAck(Session session, String id, String requestId) {
                sender.sendProjectMetadataSaveAck(session, "project", requestId);
            }

            @Override
            public String requestMissingMessage() {
                return "Project Metadata ID not provided";
            }

            @Override
            public String identityRules() {
                return "singleton:project";
            }

            @Override
            public String defaultRequestId() {
                return "project";
            }

            @Override
            public String saveErrorCode() {
                return "PROJECT_METADATA_SAVE_FAILED";
            }

            @Override
            public String deleteErrorCode() {
                return "PROJECT_METADATA_DELETE_FAILED";
            }
        };
    }

    private FlowResourceAdapter<JsonObject> jsonAdapter(ReSyncJsonResourceStorage storage, String type, FlowPacketSender sender) {
        return new FlowResourceAdapter<>() {
            @Override
            public ReSyncManagedResource descriptor() {
                return ReSyncResourceCatalog.byType(type);
            }

            @Override
            public JsonObject get(String id) {
                return storage.get(type, id);
            }

            @Override
            public List<String> listIds() {
                return storage.listIds(type);
            }

            @Override
            public JsonObject deserialize(String json) {
                return gson.fromJson(json, JsonObject.class);
            }

            @Override
            public String id(JsonObject value) {
                if (value == null || !value.has("id") || value.get("id").isJsonNull()) {
                    return "";
                }
                return value.get("id").getAsString();
            }

            @Override
            public void save(JsonObject value) {
                storage.save(type, value);
            }

            @Override
            public void validate(JsonObject value) {
                jsonResourceValidator.validate(type, value);
            }

            @Override
            public void delete(String id) {
                storage.delete(type, id);
            }

            @Override
            public JsonObject duplicate(JsonObject value, String targetId) {
                JsonObject copy = value.deepCopy();
                copy.addProperty("id", targetId);
                return copy;
            }

            @Override
            public Set<String> supportedOperations() {
                return resourceOperationsWithDuplicateAndReload();
            }

            @Override
            public JsonObject reload(String id) {
                JsonObject value = storage.reload(type, id);
                if (value != null) {
                    refreshResourceCatalog(type);
                }
                return value;
            }

            @Override
            public void sendData(Session session, JsonObject value) {
                sender.sendJsonResourceData(session, descriptor().flowPackets().data(), gson.toJson(value), descriptor().displayName());
            }

            @Override
            public void sendList(Session session, List<String> ids) {
                sender.sendJsonResourceList(session, descriptor().flowPackets().list(), ids);
            }

            @Override
            public void sendSaveAck(Session session, String id) {
                sender.sendJsonResourceSaveAck(session, descriptor().flowPackets().saveAck(), id);
            }

            @Override
            public void sendSaveAck(Session session, String id, String requestId) {
                sender.sendJsonResourceSaveAck(session, descriptor().flowPackets().saveAck(), id, requestId);
            }

            @Override
            public void afterSave(JsonObject value) {
                refreshResourceCatalog(type);
            }

            @Override
            public void afterDelete(String id) {
                refreshResourceCatalog(type);
            }

            @Override
            public String authoritativeService() {
                return switch (type) {
                    case ReSyncResourceCatalog.RECIPE_DEFINITION -> "RecipeModule";
                    case ReSyncResourceCatalog.ADVANCEMENT_TREE -> "AdvancementModule";
                    case ReSyncResourceCatalog.DIALOG -> "DialogService";
                    case ReSyncResourceCatalog.TRADE_PROFILE -> "TradeProfileService";
                    case ReSyncResourceCatalog.NPC_DEFINITION -> "NpcService";
                    case ReSyncResourceCatalog.LOOT_TABLE -> "LootTableService";
                    case ReSyncResourceCatalog.TEXT_TEMPLATE -> "ReTextService";
                    default -> "ReSyncJsonResourceStorage:" + type;
                };
            }

            @Override
            public boolean activeRefresh() {
                return Set.of(ReSyncResourceCatalog.TRADE_PROFILE, ReSyncResourceCatalog.NPC_DEFINITION, ReSyncResourceCatalog.RECIPE_DEFINITION,
                    ReSyncResourceCatalog.ADVANCEMENT_TREE, ReSyncResourceCatalog.TEXT_TEMPLATE).contains(type);
            }
        };
    }

    private Set<String> resourceOperationsWithDuplicate() {
        return Set.of("discover", "query", "get", "create", "validate", "save", "update", "duplicate", "delete");
    }

    private Set<String> resourceOperationsWithDuplicateAndApply() {
        return Set.of("discover", "query", "get", "create", "validate", "save", "update", "duplicate", "delete", "apply");
    }

    private Set<String> resourceOperationsWithDuplicateAndReload() {
        return Set.of("discover", "query", "get", "create", "validate", "save", "update", "duplicate", "delete", "reload");
    }
}
