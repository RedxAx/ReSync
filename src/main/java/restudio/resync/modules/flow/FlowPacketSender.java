package restudio.resync.modules.flow;

import com.google.gson.Gson;
import org.bukkit.Bukkit;
import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowSerializer;
import restudio.flow.data.GuiDefinition;
import restudio.flow.data.ScoreboardDefinition;
import restudio.flow.data.TabDefinition;
import restudio.resync.core.Session;
import restudio.resync.flow.sync.NodeRegistrySnapshot;
import restudio.resync.protocol.Codec;
import restudio.resync.protocol.messages.DataMessage;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

public class FlowPacketSender {
    public static final int MAX_PACKET_SIZE = 1024 * 1024;
    public static final int MAX_STRING_LENGTH = 65536;
    private final Codec codec;
    private final int channelId;
    private final Set<Session> subscribedSessions;
    private final Gson gson = new Gson();

    public FlowPacketSender(Codec codec, int channelId, Set<Session> subscribedSessions) {
        this.codec = codec;
        this.channelId = channelId;
        this.subscribedSessions = subscribedSessions;
    }

    public void sendFlowData(Session session, FlowGraph graph) {
        sendJsonPacket(session, (byte) 0x02, FlowSerializer.serialize(graph), "FLOW_TOO_LARGE", "Flow data exceeds maximum size");
    }

    public void sendGuiData(Session session, GuiDefinition gui) {
        sendJsonPacket(session, (byte) 0x12, FlowSerializer.serializeGui(gui), "GUI_TOO_LARGE", "GUI data exceeds maximum size");
    }

    public void sendScoreboardData(Session session, ScoreboardDefinition scoreboard) {
        sendJsonPacket(session, (byte) 0x1C, FlowSerializer.serializeScoreboard(scoreboard), "SCOREBOARD_TOO_LARGE", "Scoreboard data exceeds maximum size");
    }

    public void sendTabData(Session session, TabDefinition tab) {
        sendJsonPacket(session, (byte) 0x24, FlowSerializer.serializeTab(tab), "TAB_TOO_LARGE", "Tab data exceeds maximum size");
    }

    public void sendFlowSaveAck(Session session, String flowId) {
        sendIdAck(session, (byte) 0x07, flowId);
    }

    public void sendGuiSaveAck(Session session, String guiId) {
        sendIdAck(session, (byte) 0x17, guiId);
    }

    public void sendScoreboardSaveAck(Session session, String scoreboardId) {
        sendIdAck(session, (byte) 0x1E, scoreboardId);
    }

    public void sendTabSaveAck(Session session, String tabId) {
        sendIdAck(session, (byte) 0x26, tabId);
    }

    public void sendFlowList(Session session, List<String> flowIds) {
        sendStringList(session, (byte) 0x0A, flowIds);
    }

    public void sendGuiList(Session session, List<String> guiIds) {
        sendStringList(session, (byte) 0x15, guiIds);
    }

    public void sendScoreboardList(Session session, List<String> scoreboardIds) {
        sendStringList(session, (byte) 0x1D, scoreboardIds);
    }

    public void sendTabList(Session session, List<String> tabIds) {
        sendStringList(session, (byte) 0x25, tabIds);
    }

    public void sendGuiState(Session session, boolean editable, String guiId, String flowId) {
        if (guiId != null && guiId.length() > MAX_STRING_LENGTH) {
            sendError(session, "INVALID_GUI_ID", "GUI ID too long");
            return;
        }
        if (flowId != null && flowId.length() > MAX_STRING_LENGTH) {
            sendError(session, "INVALID_FLOW_ID", "Flow ID too long");
            return;
        }

        byte[] guiBytes = guiId != null ? guiId.getBytes(StandardCharsets.UTF_8) : new byte[0];
        byte[] flowBytes = flowId != null ? flowId.getBytes(StandardCharsets.UTF_8) : new byte[0];
        ByteBuffer buffer = ByteBuffer.allocate(1 + 1 + 4 + guiBytes.length + 4 + flowBytes.length);
        buffer.put((byte) 0x04);
        buffer.put((byte) (editable ? 1 : 0));
        buffer.putInt(guiBytes.length);
        buffer.put(guiBytes);
        buffer.putInt(flowBytes.length);
        buffer.put(flowBytes);
        sendRaw(session, buffer.array(), false);
    }

    public void sendPlaceholderPreview(Session session, int requestId, String rendered) {
        byte[] renderedBytes = rendered.getBytes(StandardCharsets.UTF_8);
        ByteBuffer out = ByteBuffer.allocate(1 + 4 + 4 + renderedBytes.length);
        out.put((byte) 0x28);
        out.putInt(requestId);
        out.putInt(renderedBytes.length);
        out.put(renderedBytes);
        sendRaw(session, out.array(), false);
    }

    public void sendNodeRegistrySnapshot(Session session, NodeRegistrySnapshot snapshot) {
        if (session == null || snapshot == null) {
            return;
        }
        String json = gson.toJson(snapshot);
        byte[] jsonBytes = json.getBytes(StandardCharsets.UTF_8);
        byte packetId = snapshot.isFullSync() ? (byte) 0x0B : (byte) 0x0D;
        ByteBuffer buffer = ByteBuffer.allocate(1 + jsonBytes.length);
        buffer.put(packetId);
        buffer.put(jsonBytes);
        sendRaw(session, buffer.array(), true);
    }

    public void broadcastNodeRegistry(NodeRegistrySnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        for (Session session : subscribedSessions) {
            sendNodeRegistrySnapshot(session, snapshot);
        }
    }

    public void sendError(Session session, String errorCode, String message) {
        byte[] errorBytes = message.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(1 + 4 + errorBytes.length);
        buffer.put((byte) 0x05);
        buffer.putInt(errorBytes.length);
        buffer.put(errorBytes);
        sendRaw(session, buffer.array(), false);
        Bukkit.getLogger().warning("[ReSync] Sending error to client " + session.getClientId() + ": " + errorCode + " - " + message);
    }

    private void sendJsonPacket(Session session, byte packetId, String json, String errorCode, String errorMessage) {
        byte[] jsonBytes = json.getBytes(StandardCharsets.UTF_8);
        if (jsonBytes.length > MAX_PACKET_SIZE) {
            sendError(session, errorCode, errorMessage);
            return;
        }
        ByteBuffer buffer = ByteBuffer.allocate(1 + jsonBytes.length);
        buffer.put(packetId);
        buffer.put(jsonBytes);
        sendRaw(session, buffer.array(), false);
    }

    private void sendIdAck(Session session, byte packetId, String id) {
        if (id == null) {
            return;
        }
        byte[] idBytes = id.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(1 + 4 + idBytes.length);
        buffer.put(packetId);
        buffer.putInt(idBytes.length);
        buffer.put(idBytes);
        sendRaw(session, buffer.array(), false);
    }

    private void sendStringList(Session session, byte packetId, List<String> ids) {
        List<String> values = ids != null ? ids : List.of();
        int totalBytes = 1 + 4;
        for (String id : values) {
            totalBytes += 4 + id.getBytes(StandardCharsets.UTF_8).length;
        }
        ByteBuffer buffer = ByteBuffer.allocate(totalBytes);
        buffer.put(packetId);
        buffer.putInt(values.size());
        for (String id : values) {
            byte[] bytes = id.getBytes(StandardCharsets.UTF_8);
            buffer.putInt(bytes.length);
            buffer.put(bytes);
        }
        sendRaw(session, buffer.array(), false);
    }

    public void sendRaw(Session session, byte[] payload, boolean compress) {
        if (session == null || payload == null) {
            return;
        }
        DataMessage msg = new DataMessage();
        msg.setChannel(channelId);
        msg.setPayload(payload);
        codec.sendMessage(session.getConnection().getWebSocket(), msg, channelId, compress);
    }
}
