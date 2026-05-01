package restudio.resync.modules.flow;

import restudio.resync.Log;
import restudio.flow.data.FlowSerializer;
import restudio.flow.data.GuiDefinition;
import restudio.resync.core.Session;
import restudio.resync.flow.FlowStorage;
import restudio.resync.jobs.JobRecord;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public class FlowGuiPacketHandler {
    private final FlowStorage storage;
    private final FlowPacketSender sender;

    public FlowGuiPacketHandler(FlowStorage storage, FlowPacketSender sender) {
        this.storage = storage;
        this.sender = sender;
    }

    public void handleRequest(Session session, ByteBuffer buffer) {
        if (!buffer.hasRemaining()) {
            sender.sendError(session, "INVALID_REQUEST", "GUI ID not provided");
            return;
        }
        byte[] idBytes = new byte[buffer.remaining()];
        buffer.get(idBytes);
        String guiId = new String(idBytes, StandardCharsets.UTF_8);
        if (guiId.length() > FlowPacketSender.MAX_STRING_LENGTH) {
            sender.sendError(session, "INVALID_GUI_ID", "GUI ID too long");
            return;
        }
        GuiDefinition gui = storage.getGui(guiId);
        if (gui != null) {
            sender.sendGuiData(session, gui);
        } else {
            sender.sendError(session, "GUI_NOT_FOUND", "GUI not found: " + guiId);
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
        FlowMutationPayload payload = FlowMutationPayloadReader.read(buffer);
        String json = payload.payload();
        JobRecord<String> job = sender.beginJob(session, "saveGui", "", payload.requestId());
        if (job == null) {
            return;
        }
        try {
            GuiDefinition gui = FlowSerializer.deserializeGui(json);
            if (gui == null || gui.getId() == null || gui.getId().isBlank()) {
                sender.failJob(job, "GUI ID is missing", null);
                sender.sendError(session, "INVALID_GUI", "GUI ID is missing");
                return;
            }
            storage.saveGui(gui);
            Log.fine("GUI saved: " + gui.getId());
            sender.sendGuiSaveAck(session, gui.getId());
            sender.succeedJob(job, gui.getId(), "Saved");
        } catch (Exception e) {
            sender.failJob(job, e.getMessage(), e);
            sender.sendError(session, "SAVE_FAILED", "Failed to save GUI: " + e.getMessage());
        }
    }

    public void handleDelete(Session session, ByteBuffer buffer) {
        if (!buffer.hasRemaining()) {
            return;
        }
        FlowMutationPayload payload = FlowMutationPayloadReader.read(buffer);
        String guiId = payload.payload();
        JobRecord<String> job = sender.beginJob(session, "deleteGui", guiId, payload.requestId());
        if (job == null) {
            return;
        }
        try {
            storage.deleteGui(guiId);
            Log.fine("GUI deleted: " + guiId);
            sender.succeedJob(job, guiId, "Deleted");
        } catch (Exception e) {
            sender.failJob(job, e.getMessage(), e);
            sender.sendError(session, "DELETE_FAILED", "Failed to delete GUI: " + e.getMessage());
        }
    }

    public void handleListRequest(Session session) {
        sender.sendGuiList(session, storage.listGuiIds());
    }
}
