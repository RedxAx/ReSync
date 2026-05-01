package restudio.resync.modules.flow;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import restudio.flow.data.FlowDataType;
import restudio.flow.data.FlowDataTypeAdapter;
import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowSerializer;
import restudio.flow.data.GuiDefinition;
import restudio.flow.data.ScoreboardDefinition;
import restudio.flow.data.TabDefinition;
import restudio.flow.data.CustomContentDefinition;
import restudio.resync.Log;
import restudio.resync.core.Session;
import restudio.resync.flow.registry.NodeDefinition;
import restudio.resync.flow.diagnostics.FlowTraceRecord;
import restudio.resync.flow.sync.NodeRegistrySnapshot;
import restudio.resync.jobs.JobManager;
import restudio.resync.jobs.JobRecord;
import restudio.resync.protocol.Codec;
import restudio.resync.protocol.messages.DataMessage;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class FlowPacketSender {
    public static final int MAX_PACKET_SIZE = 1024 * 1024;
    public static final int MAX_STRING_LENGTH = 65536;
    private final Codec codec;
    private final int channelId;
    private final Set<Session> subscribedSessions;
    private final JobManager jobManager;
    private final Gson gson = new GsonBuilder()
            .registerTypeAdapter(FlowDataType.class, new FlowDataTypeAdapter())
            .registerTypeAdapter(NodeDefinition.NodeCategory.class, new com.google.gson.TypeAdapter<NodeDefinition.NodeCategory>() {
                @Override
                public void write(com.google.gson.stream.JsonWriter out, NodeDefinition.NodeCategory value) throws java.io.IOException {
                    out.value(value != null ? value.getId() : null);
                }

                @Override
                public NodeDefinition.NodeCategory read(com.google.gson.stream.JsonReader in) throws java.io.IOException {
                    String id = in.nextString();
                    return NodeDefinition.NodeCategory.fromString(id);
                }
            })
            .create();

    public FlowPacketSender(Codec codec, int channelId, Set<Session> subscribedSessions) {
        this.codec = codec;
        this.channelId = channelId;
        this.subscribedSessions = subscribedSessions;
        this.jobManager = new JobManager(job -> broadcastJob("jobStatus", job.snapshot()));
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

    public void sendCustomContentData(Session session, CustomContentDefinition content) {
        sendJsonPacket(session, (byte) 0x32, gson.toJson(content), "CONTENT_TOO_LARGE", "Custom content data exceeds maximum size");
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

    public void sendCustomContentSaveAck(Session session, String contentId) {
        sendIdAck(session, (byte) 0x35, contentId);
    }

    public JobRecord<String> beginJob(Session session, String action, String target) {
        JobRecord<String> job = jobManager.create(action, session != null ? session.getClientId() : "unknown", target == null ? "" : target);
        sendJob(session, "jobAccepted", job.snapshot());
        job.markRunning();
        jobManager.publish(job);
        return job;
    }

    public void succeedJob(JobRecord<String> job, String result, String message) {
        if (job != null && job.markSucceeded(result, message == null || message.isBlank() ? "Succeeded" : message)) {
            jobManager.publish(job);
        }
    }

    public void failJob(JobRecord<String> job, String message, Throwable throwable) {
        if (job != null && job.markFailed(message == null || message.isBlank() ? "Failed" : message, throwable)) {
            jobManager.publish(job);
        }
    }

    public void sendJobSnapshot(Session session, String actorClientId) {
        sendJob(session, "jobSnapshot", jobManager.activeOrRecentSnapshot(actorClientId, 300000));
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

    public void sendCustomContentList(Session session, List<String> contentIds) {
        sendStringList(session, (byte) 0x31, contentIds);
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

    public void sendOptionCatalog(Session session, String sourceId, List<String> values, String revision) {
        String json = gson.toJson(Map.of(
            "sourceId", sourceId != null ? sourceId : "",
            "revision", revision != null ? revision : "",
            "values", values != null ? values : List.of()
        ));
        byte[] jsonBytes = json.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(1 + jsonBytes.length);
        buffer.put((byte) 0x38);
        buffer.put(jsonBytes);
        sendRaw(session, buffer.array(), true);
    }

    public void sendTraceSnapshot(Session session, List<FlowTraceRecord> records) {
        sendTracePacket(session, (byte) 0x41, records == null ? List.of() : records);
    }

    public void sendTraceEvent(Session session, FlowTraceRecord record) {
        sendTracePacket(session, (byte) 0x42, record);
    }

    private void sendTracePacket(Session session, byte packetId, Object payload) {
        String json = gson.toJson(payload);
        byte[] jsonBytes = json.getBytes(StandardCharsets.UTF_8);
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

    private void broadcastJob(String action, Object data) {
        for (Session session : subscribedSessions) {
            sendJob(session, action, data);
        }
    }

    private void sendJob(Session session, String action, Object data) {
        String json = gson.toJson(Map.of(
            "type", "job",
            "action", action,
            "data", data == null ? Map.of() : data,
            "timestamp", System.currentTimeMillis()
        ));
        byte[] jsonBytes = json.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(1 + jsonBytes.length);
        buffer.put((byte) 0x44);
        buffer.put(jsonBytes);
        sendRaw(session, buffer.array(), false);
    }

    public void sendError(Session session, String errorCode, String message) {
        byte[] errorBytes = message.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(1 + 4 + errorBytes.length);
        buffer.put((byte) 0x05);
        buffer.putInt(errorBytes.length);
        buffer.put(errorBytes);
        sendRaw(session, buffer.array(), false);
        Log.warn("Error sent to " + session.getClientId() + ": " + errorCode + " - " + message);
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
