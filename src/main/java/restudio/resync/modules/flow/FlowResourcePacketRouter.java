package restudio.resync.modules.flow;

import restudio.flow.data.CustomContentDefinition;
import restudio.flow.data.FlowSerializer;
import restudio.flow.data.GuiDefinition;
import restudio.flow.data.ScoreboardDefinition;
import restudio.flow.data.TabDefinition;
import restudio.resync.core.Session;
import restudio.resync.customcontent.CustomContentStorage;
import restudio.resync.customcontent.CustomContentValidator;
import restudio.resync.flow.FlowStorage;
import restudio.resync.flow.GuiManager;
import restudio.resync.flow.ScoreboardTemplateManager;
import restudio.resync.flow.TabListService;
import restudio.resync.resources.ReSyncManagedResource;
import restudio.resync.resources.ReSyncResourceCatalog;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public class FlowResourcePacketRouter {
    private final List<FlowResourcePacketHandler<?>> handlers = new ArrayList<>();

    public FlowResourcePacketRouter(FlowStorage storage, CustomContentStorage customContentStorage, FlowPacketSender sender) {
        handlers.add(new FlowResourcePacketHandler<>(guiAdapter(storage, sender), sender));
        handlers.add(new FlowResourcePacketHandler<>(scoreboardAdapter(storage, sender), sender));
        handlers.add(new FlowResourcePacketHandler<>(tabAdapter(storage, sender), sender));
        handlers.add(new FlowResourcePacketHandler<>(customContentAdapter(customContentStorage, sender), sender));
        handlers.add(new FlowResourcePacketHandler<>(projectMetadataAdapter(storage, sender), sender));
    }

    public boolean handle(Session session, byte packetId, ByteBuffer buffer) {
        for (FlowResourcePacketHandler<?> handler : handlers) {
            if (handler.handle(session, packetId, buffer)) {
                return true;
            }
        }
        return false;
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

    private FlowResourceAdapter<CustomContentDefinition> customContentAdapter(CustomContentStorage storage, FlowPacketSender sender) {
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
        };
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
}
