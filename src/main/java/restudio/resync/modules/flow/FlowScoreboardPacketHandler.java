package restudio.resync.modules.flow;

import restudio.resync.Log;
import restudio.flow.data.FlowSerializer;
import restudio.flow.data.ScoreboardDefinition;
import restudio.resync.core.Session;
import restudio.resync.flow.FlowStorage;
import restudio.resync.flow.ScoreboardTemplateManager;
import restudio.resync.jobs.JobRecord;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public class FlowScoreboardPacketHandler {
    private final FlowStorage storage;
    private final FlowPacketSender sender;

    public FlowScoreboardPacketHandler(FlowStorage storage, FlowPacketSender sender) {
        this.storage = storage;
        this.sender = sender;
    }

    public void handleRequest(Session session, ByteBuffer buffer) {
        if (!buffer.hasRemaining()) {
            sender.sendError(session, "INVALID_REQUEST", "Scoreboard ID not provided");
            return;
        }
        byte[] idBytes = new byte[buffer.remaining()];
        buffer.get(idBytes);
        String scoreboardId = new String(idBytes, StandardCharsets.UTF_8);
        if (scoreboardId.length() > FlowPacketSender.MAX_STRING_LENGTH) {
            sender.sendError(session, "INVALID_SCOREBOARD_ID", "Scoreboard ID too long");
            return;
        }
        ScoreboardDefinition scoreboard = storage.getScoreboard(scoreboardId);
        if (scoreboard != null) {
            sender.sendScoreboardData(session, scoreboard);
        } else {
            sender.sendError(session, "SCOREBOARD_NOT_FOUND", "Scoreboard not found: " + scoreboardId);
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
        JobRecord<String> job = sender.beginJob(session, "saveScoreboard", "");
        try {
            ScoreboardDefinition scoreboard = FlowSerializer.deserializeScoreboard(json);
            if (scoreboard == null || scoreboard.getId() == null || scoreboard.getId().isBlank()) {
                sender.failJob(job, "Scoreboard ID is missing", null);
                sender.sendError(session, "INVALID_SCOREBOARD", "Scoreboard ID is missing");
                return;
            }
            storage.saveScoreboard(scoreboard);
            ScoreboardTemplateManager.refreshActiveTemplates(storage, scoreboard.getId());
            Log.fine("Scoreboard saved: " + scoreboard.getId());
            sender.sendScoreboardSaveAck(session, scoreboard.getId());
            sender.succeedJob(job, scoreboard.getId(), "Saved");
        } catch (Exception e) {
            sender.failJob(job, e.getMessage(), e);
            sender.sendError(session, "SAVE_FAILED", "Failed to save scoreboard: " + e.getMessage());
        }
    }

    public void handleDelete(Session session, ByteBuffer buffer) {
        if (!buffer.hasRemaining()) {
            return;
        }
        byte[] idBytes = new byte[buffer.remaining()];
        buffer.get(idBytes);
        String scoreboardId = new String(idBytes, StandardCharsets.UTF_8);
        JobRecord<String> job = sender.beginJob(session, "deleteScoreboard", scoreboardId);
        try {
            storage.deleteScoreboard(scoreboardId);
            ScoreboardTemplateManager.clearActiveTemplateReferences(scoreboardId, true);
            String defaultId = storage.getDefaultScoreboardId();
            if (defaultId != null && defaultId.equalsIgnoreCase(scoreboardId)) {
                storage.clearDefaultScoreboard();
            }
            Log.fine("Scoreboard deleted: " + scoreboardId);
            sender.succeedJob(job, scoreboardId, "Deleted");
        } catch (Exception e) {
            sender.failJob(job, e.getMessage(), e);
            sender.sendError(session, "DELETE_FAILED", "Failed to delete scoreboard: " + e.getMessage());
        }
    }

    public void handleListRequest(Session session) {
        sender.sendScoreboardList(session, storage.listScoreboardIds());
    }
}
