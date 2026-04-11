package restudio.resync.modules.flow;

import restudio.resync.Log;
import restudio.flow.data.FlowSerializer;
import restudio.flow.data.TabDefinition;
import restudio.resync.core.Session;
import restudio.resync.flow.FlowStorage;
import restudio.resync.flow.TabListService;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public class FlowTabPacketHandler {
    private final FlowStorage storage;
    private final FlowPacketSender sender;

    public FlowTabPacketHandler(FlowStorage storage, FlowPacketSender sender) {
        this.storage = storage;
        this.sender = sender;
    }

    public void handleRequest(Session session, ByteBuffer buffer) {
        if (!buffer.hasRemaining()) {
            sender.sendError(session, "INVALID_REQUEST", "Tab ID not provided");
            return;
        }
        byte[] idBytes = new byte[buffer.remaining()];
        buffer.get(idBytes);
        String tabId = new String(idBytes, StandardCharsets.UTF_8);
        if (tabId.length() > FlowPacketSender.MAX_STRING_LENGTH) {
            sender.sendError(session, "INVALID_TAB_ID", "Tab ID too long");
            return;
        }
        TabDefinition tab = storage.getTab(tabId);
        if (tab != null) {
            sender.sendTabData(session, tab);
        } else {
            sender.sendError(session, "TAB_NOT_FOUND", "Tab not found: " + tabId);
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
            TabDefinition tab = FlowSerializer.deserializeTab(json);
            if (tab == null || tab.getId() == null || tab.getId().isBlank()) {
                sender.sendError(session, "INVALID_TAB", "Tab ID is missing");
                return;
            }
            storage.saveTab(tab);
            TabListService.refreshActiveTabs(storage, tab.getId());
            Log.fine("Tab saved: " + tab.getId());
            sender.sendTabSaveAck(session, tab.getId());
        } catch (Exception e) {
            sender.sendError(session, "SAVE_FAILED", "Failed to save tab: " + e.getMessage());
        }
    }

    public void handleDelete(Session session, ByteBuffer buffer) {
        if (!buffer.hasRemaining()) {
            return;
        }
        byte[] idBytes = new byte[buffer.remaining()];
        buffer.get(idBytes);
        String tabId = new String(idBytes, StandardCharsets.UTF_8);
        storage.deleteTab(tabId);
        TabListService.clearActiveTabReferences(tabId, true);
        String defaultId = storage.getDefaultTabId();
        if (defaultId != null && defaultId.equalsIgnoreCase(tabId)) {
            storage.clearDefaultTab();
        }
        Log.fine("Tab deleted: " + tabId);
    }

    public void handleListRequest(Session session) {
        sender.sendTabList(session, storage.listTabIds());
    }
}
