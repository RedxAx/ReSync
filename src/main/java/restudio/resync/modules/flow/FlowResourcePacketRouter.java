package restudio.resync.modules.flow;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import restudio.flow.data.CustomContentDefinition;
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
import restudio.resync.flow.GuiManager;
import restudio.resync.flow.ScoreboardTemplateManager;
import restudio.resync.flow.TabListService;
import restudio.resync.messages.MessageLogService;
import restudio.resync.contracts.ReSyncProtocolContract;
import restudio.resync.resources.ReSyncManagedResource;
import restudio.resync.resources.ReSyncResourceCatalog;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class FlowResourcePacketRouter {
    private final List<FlowResourcePacketHandler<?>> handlers = new ArrayList<>();
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final MessageLogService messageLogService;
    private final FlowPacketSender sender;
    private final Runnable customContentCatalogRefresh;

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
        this.sender = sender;
        this.messageLogService = messageLogService;
        this.customContentCatalogRefresh = customContentCatalogRefresh;
        handlers.add(new FlowResourcePacketHandler<>(guiAdapter(storage, sender), sender));
        handlers.add(new FlowResourcePacketHandler<>(scoreboardAdapter(storage, sender), sender));
        handlers.add(new FlowResourcePacketHandler<>(tabAdapter(storage, sender), sender));
        handlers.add(new FlowResourcePacketHandler<>(customContentAdapter(customContentStorage, customContentService, sender), sender));
        handlers.add(new FlowResourcePacketHandler<>(projectMetadataAdapter(storage, sender), sender));
        if (jsonResourceStorage != null) {
            for (String type : jsonResourceStorage.resourceTypes()) {
                handlers.add(new FlowResourcePacketHandler<>(jsonAdapter(jsonResourceStorage, type, sender), sender));
            }
        }
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
        JsonObject request = readJsonObject(buffer);
        int page = intValue(request, "page", 0);
        int pageSize = intValue(request, "pageSize", 20);
        String query = stringValue(request, "query");
        String source = stringValue(request, "source");
        JsonObject payload = messageLogService != null ? messageLogService.page(page, pageSize, query, source) : emptyMessageLogPage(page, pageSize, query, source);
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
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            return root != null ? root : new JsonObject();
        } catch (Exception ignored) {
            return new JsonObject();
        }
    }

    private int intValue(JsonObject json, String key, int fallback) {
        if (json == null || key == null || !json.has(key) || json.get(key).isJsonNull()) {
            return fallback;
        }
        try {
            return json.get(key).getAsInt();
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private String stringValue(JsonObject json, String key) {
        if (json == null || key == null || !json.has(key) || json.get(key).isJsonNull()) {
            return "";
        }
        try {
            return json.get(key).getAsString();
        } catch (Exception ignored) {
            return "";
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
            public void afterSave(Session session, GuiDefinition value) {
                GuiManager.refreshOpenGuis(value);
                GuiManager.refreshSessionGui(session, value);
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
            public void afterSave(Session session, ScoreboardDefinition value) {
                ScoreboardTemplateManager.refreshActiveTemplates(storage, value.getId());
            }

            @Override
            public void afterDelete(Session session, String id) {
                ScoreboardTemplateManager.clearActiveTemplateReferences(id, true);
                String defaultId = storage.getDefaultScoreboardId();
                if (defaultId != null && defaultId.equalsIgnoreCase(id)) {
                    storage.clearDefaultScoreboard();
                }
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
            public void afterSave(Session session, TabDefinition value) {
                TabListService.refreshActiveTabs(storage, value.getId());
            }

            @Override
            public void afterDelete(Session session, String id) {
                TabListService.clearActiveTabReferences(id, true);
                String defaultId = storage.getDefaultTabId();
                if (defaultId != null && defaultId.equalsIgnoreCase(id)) {
                    storage.clearDefaultTab();
                }
            }
        };
    }

    private FlowResourceAdapter<CustomContentDefinition> customContentAdapter(CustomContentStorage storage, CustomContentService service, FlowPacketSender sender) {
        CustomContentValidator validator = new CustomContentValidator();
        ItemAttributeSchemaService attributeSchemaService = new ItemAttributeSchemaService();
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
                return FlowSerializer.deserializeCustomContent(json);
            }

            @Override
            public String id(CustomContentDefinition value) {
                return value.getId();
            }

            @Override
            public void save(CustomContentDefinition value) {
                List<String> errors = validator.validate(value);
                if (!errors.isEmpty()) {
                    throw new IllegalArgumentException(String.join("; ", errors));
                }
                List<Map<String, Object>> componentErrors = attributeSchemaService.validate(value.getMaterial(), value.getComponents());
                if (!componentErrors.isEmpty()) {
                    throw new ItemAttributeValidationException(componentErrors);
                }
                storage.save(value);
            }

            @Override
            public void delete(String id) {
                storage.delete(id);
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
            public String saveErrorCode() {
                return "CONTENT_SAVE_FAILED";
            }

            @Override
            public String deleteErrorCode() {
                return "CONTENT_DELETE_FAILED";
            }

            @Override
            public void afterSave(Session session, CustomContentDefinition value) {
                if (service != null) {
                    service.reconcileContentItems(value.getId());
                }
                refreshCustomContentCatalogs();
            }

            @Override
            public void afterDelete(Session session, String id) {
                if (service != null) {
                    service.clearContentItems(id);
                }
                refreshCustomContentCatalogs();
            }
        };
    }

    private void refreshCustomContentCatalogs() {
        if (customContentCatalogRefresh != null) {
            customContentCatalogRefresh.run();
        }
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
            public String requestMissingMessage() {
                return "Project Metadata ID not provided";
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
            public void delete(String id) {
                storage.delete(type, id);
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
        };
    }
}
