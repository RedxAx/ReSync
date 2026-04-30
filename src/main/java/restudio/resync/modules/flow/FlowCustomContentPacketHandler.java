package restudio.resync.modules.flow;

import com.google.gson.Gson;
import restudio.flow.data.CustomContentDefinition;
import restudio.resync.Log;
import restudio.resync.core.Session;
import restudio.resync.customcontent.CustomContentStorage;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public class FlowCustomContentPacketHandler {
    private final CustomContentStorage storage;
    private final FlowPacketSender sender;
    private final Gson gson = new Gson();

    public FlowCustomContentPacketHandler(CustomContentStorage storage, FlowPacketSender sender) {
        this.storage = storage;
        this.sender = sender;
    }

    public void handleRequest(Session session, ByteBuffer buffer) {
        if (!buffer.hasRemaining()) {
            sender.sendError(session, "INVALID_CONTENT_REQUEST", "Content ID not provided");
            return;
        }
        String id = readRemaining(buffer);
        CustomContentDefinition definition = storage.get(id);
        if (definition != null) {
            sender.sendCustomContentData(session, definition);
        } else {
            sender.sendError(session, "CONTENT_NOT_FOUND", "Custom content not found: " + id);
        }
    }

    public void handleListRequest(Session session) {
        sender.sendCustomContentList(session, storage.listIds());
    }

    public void handleSave(Session session, ByteBuffer buffer) {
        if (!buffer.hasRemaining()) {
            sender.sendError(session, "INVALID_CONTENT_SAVE", "No custom content data provided");
            return;
        }
        String json = readRemaining(buffer);
        try {
            CustomContentDefinition definition = gson.fromJson(json, CustomContentDefinition.class);
            if (definition == null || definition.getId() == null || definition.getId().isBlank()) {
                sender.sendError(session, "INVALID_CONTENT", "Invalid custom content definition");
                return;
            }
            storage.save(definition);
            sender.sendCustomContentSaveAck(session, definition.getId());
        } catch (Exception e) {
            Log.warn("Custom content save failed: " + e.getMessage());
            sender.sendError(session, "CONTENT_SAVE_FAILED", e.getMessage());
        }
    }

    public void handleDelete(Session session, ByteBuffer buffer) {
        if (!buffer.hasRemaining()) {
            return;
        }
        storage.delete(readRemaining(buffer));
    }

    private String readRemaining(ByteBuffer buffer) {
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
