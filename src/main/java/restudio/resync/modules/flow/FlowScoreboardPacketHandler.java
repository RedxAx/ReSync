package restudio.resync.modules.flow;

import org.bukkit.Bukkit;
import restudio.flow.data.FlowSerializer;
import restudio.flow.data.ScoreboardDefinition;
import restudio.resync.core.Session;
import restudio.resync.flow.FlowStorage;
import restudio.resync.flow.nodes.ScoreboardNodes;

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
        try {
            ScoreboardDefinition scoreboard = FlowSerializer.deserializeScoreboard(json);
            if (scoreboard == null || scoreboard.getId() == null || scoreboard.getId().isBlank()) {
                sender.sendError(session, "INVALID_SCOREBOARD", "Scoreboard ID is missing");
                return;
            }
            storage.saveScoreboard(scoreboard);
            ScoreboardNodes.refreshActiveTemplates(storage, scoreboard.getId());
            Bukkit.getLogger().info("[ReSync] Saved scoreboard " + scoreboard.getId() + " from client " + session.getClientId());
            sender.sendScoreboardSaveAck(session, scoreboard.getId());
        } catch (Exception e) {
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
        storage.deleteScoreboard(scoreboardId);
        ScoreboardNodes.clearActiveTemplateReferences(scoreboardId, true);
        String defaultId = storage.getDefaultScoreboardId();
        if (defaultId != null && defaultId.equalsIgnoreCase(scoreboardId)) {
            storage.clearDefaultScoreboard();
        }
        Bukkit.getLogger().info("[ReSync] Deleted scoreboard " + scoreboardId + " from client " + session.getClientId());
    }

    public void handleListRequest(Session session) {
        sender.sendScoreboardList(session, storage.listScoreboardIds());
    }
}
