package restudio.resync.modules.flow;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import restudio.flow.data.FlowDataType;
import restudio.flow.data.FlowDataTypeAdapter;
import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowSerializer;
import restudio.flow.data.GuiDefinition;
import restudio.flow.data.ScoreboardDefinition;
import restudio.flow.data.TabDefinition;
import restudio.flow.data.CustomContentDefinition;
import restudio.resync.Log;
import restudio.resync.api.OptionCatalogItem;
import restudio.resync.core.Session;
import restudio.resync.contracts.ReSyncProtocolContract;
import restudio.resync.flow.registry.NodeDefinition;
import restudio.resync.flow.FlowExecutor;
import restudio.resync.flow.contract.EditorError;
import restudio.resync.flow.diagnostics.FlowTraceRecord;
import restudio.resync.flow.jobs.FlowJobRegistry;
import restudio.resync.flow.sync.NodeRegistrySnapshot;
import restudio.resync.flow.sync.OptionCatalogSnapshot;
import restudio.resync.jobs.JobManager;
import restudio.resync.jobs.JobRecord;
import restudio.resync.protocol.Codec;
import restudio.resync.protocol.messages.DataMessage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
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
            .registerTypeAdapter(NodeDefinition.NodeCategory.class, new TypeAdapter<NodeDefinition.NodeCategory>() {
                @Override
                public void write(JsonWriter out, NodeDefinition.NodeCategory value) throws IOException {
                    out.value(value != null ? value.getId() : null);
                }

                @Override
                public NodeDefinition.NodeCategory read(JsonReader in) throws IOException {
                    String id = in.nextString();
                    return NodeDefinition.NodeCategory.fromString(id);
                }
            })
            .create();

    public FlowPacketSender(Codec codec, int channelId, Set<Session> subscribedSessions) {
        this(codec, channelId, subscribedSessions, null);
    }

    public FlowPacketSender(Codec codec, int channelId, Set<Session> subscribedSessions, FlowJobRegistry flowJobs) {
        this.codec = codec;
        this.channelId = channelId;
        this.subscribedSessions = subscribedSessions;
        this.jobManager = new JobManager(flowJobs, job -> broadcastJob("jobStatus", job.snapshot()));
    }

    public void sendFlowData(Session session, FlowGraph graph) {
        sendJsonPacket(session, resourcePackets("flow").data(), FlowSerializer.serialize(graph), "FLOW_TOO_LARGE", "Flow data exceeds maximum size");
    }

    public void sendGuiData(Session session, GuiDefinition gui) {
        sendJsonPacket(session, resourcePackets("gui").data(), FlowSerializer.serializeGui(gui), "GUI_TOO_LARGE", "GUI data exceeds maximum size");
    }

    public void sendScoreboardData(Session session, ScoreboardDefinition scoreboard) {
        sendJsonPacket(session, resourcePackets("scoreboard").data(), FlowSerializer.serializeScoreboard(scoreboard), "SCOREBOARD_TOO_LARGE", "Scoreboard data exceeds maximum size");
    }

    public void sendTabData(Session session, TabDefinition tab) {
        sendJsonPacket(session, resourcePackets("tab").data(), FlowSerializer.serializeTab(tab), "TAB_TOO_LARGE", "Tab data exceeds maximum size");
    }

    public void sendCustomContentData(Session session, CustomContentDefinition content) {
        sendJsonPacket(session, resourcePackets("custom_content").data(), gson.toJson(content), "CONTENT_TOO_LARGE", "Custom content data exceeds maximum size");
    }

    public void sendProjectMetadataData(Session session, String json) {
        sendJsonPacket(session, resourcePackets("project_metadata").data(), json, "PROJECT_METADATA_TOO_LARGE", "Project metadata exceeds maximum size");
    }

    public void sendJsonResourceData(Session session, byte packetId, String json, String typeName) {
        String displayName = typeName != null && !typeName.isBlank() ? typeName : "Resource";
        sendJsonPacket(session, packetId, json, displayName.toUpperCase().replace(' ', '_') + "_TOO_LARGE", displayName + " data exceeds maximum size");
    }

    public void sendJsonPayload(Session session, byte packetId, String json, String errorCode, String errorMessage) {
        sendJsonPacket(session, packetId, json, errorCode, errorMessage);
    }

    public void sendFlowSaveAck(Session session, String flowId) {
        sendIdAck(session, resourcePackets("flow").saveAck(), flowId);
    }

    public void sendFlowSaveAck(Session session, String flowId, String requestId) {
        sendIdAck(session, resourcePackets("flow").saveAck(), flowId, requestId);
    }

    public void sendFlowSaveAck(Session session, String flowId, String requestId, long revision, String hash) {
        sendIdAck(session, resourcePackets("flow").saveAck(), flowId, requestId, revision, hash);
    }

    public void sendGraphSaveAck(Session session, String resourceType, String graphId, String requestId, long revision, String hash) {
        String type = Set.of("flow", "function", "command").contains(resourceType) ? resourceType : "flow";
        sendIdAck(session, resourcePackets(type).saveAck(), graphId, requestId, revision, hash);
    }

    public void sendGraphSaveAck(Session session, String resourceType, String graphId, String requestId) {
        String type = Set.of("flow", "function", "command").contains(resourceType) ? resourceType : "flow";
        sendIdAck(session, resourcePackets(type).saveAck(), graphId, requestId);
    }

    public void sendGuiSaveAck(Session session, String guiId) {
        sendIdAck(session, resourcePackets("gui").saveAck(), guiId);
    }

    public void sendGuiSaveAck(Session session, String guiId, String requestId) {
        sendIdAck(session, resourcePackets("gui").saveAck(), guiId, requestId);
    }

    public void sendScoreboardSaveAck(Session session, String scoreboardId) {
        sendIdAck(session, resourcePackets("scoreboard").saveAck(), scoreboardId);
    }

    public void sendScoreboardSaveAck(Session session, String scoreboardId, String requestId) {
        sendIdAck(session, resourcePackets("scoreboard").saveAck(), scoreboardId, requestId);
    }

    public void sendTabSaveAck(Session session, String tabId) {
        sendIdAck(session, resourcePackets("tab").saveAck(), tabId);
    }

    public void sendTabSaveAck(Session session, String tabId, String requestId) {
        sendIdAck(session, resourcePackets("tab").saveAck(), tabId, requestId);
    }

    public void sendCustomContentSaveAck(Session session, String contentId) {
        sendIdAck(session, resourcePackets("custom_content").saveAck(), contentId);
    }

    public void sendCustomContentSaveAck(Session session, String contentId, String requestId) {
        sendIdAck(session, resourcePackets("custom_content").saveAck(), contentId, requestId);
    }

    public void sendProjectMetadataSaveAck(Session session, String metadataId) {
        sendIdAck(session, resourcePackets("project_metadata").saveAck(), metadataId);
    }

    public void sendProjectMetadataSaveAck(Session session, String metadataId, String requestId) {
        sendIdAck(session, resourcePackets("project_metadata").saveAck(), metadataId, requestId);
    }

    public void sendJsonResourceSaveAck(Session session, byte packetId, String id) {
        sendIdAck(session, packetId, id);
    }

    public void sendJsonResourceSaveAck(Session session, byte packetId, String id, String requestId) {
        sendIdAck(session, packetId, id, requestId);
    }

    public void sendJsonResourceSaveAck(Session session, byte packetId, String id, String requestId, long revision, String hash) {
        sendIdAck(session, packetId, id, requestId, revision, hash);
    }

    public JobRecord<String> beginJob(Session session, String action, String target) {
        return beginJob(session, action, target, null);
    }

    public JobRecord<String> beginJob(Session session, String action, String target, String requestId) {
        JobRecord<String> job = jobManager.create(action, session != null ? session.getClientId() : "unknown", target == null ? "" : target, requestId);
        sendJob(session, "jobAccepted", job.snapshot());
        if (!job.markRunning()) {
            return null;
        }
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

    public void sendScheduledTaskSnapshot(Session session, List<FlowExecutor.ScheduledTaskSnapshot> snapshots) {
        List<Map<String, Object>> tasks = snapshots != null ? snapshots.stream().map(snapshot -> Map.<String, Object>of(
            "taskId", snapshot.taskId(),
            "kind", "scheduled_flow",
            "runtimeOwner", snapshot.runtimeOwner(),
            "graphId", snapshot.graphId(),
            "createdAt", snapshot.createdAt(),
            "nextFireAt", snapshot.nextFireAt(),
            "recurring", snapshot.recurring(),
            "status", snapshot.state().name().toLowerCase(Locale.ROOT),
            "lastFailure", snapshot.lastFailure()
        )).toList() : List.of();
        sendJob(session, "scheduledTaskSnapshot", tasks);
    }

    public void sendFlowList(Session session, List<String> flowIds) {
        sendStringList(session, resourcePackets("flow").list(), flowIds);
    }

    public void sendGuiList(Session session, List<String> guiIds) {
        sendStringList(session, resourcePackets("gui").list(), guiIds);
    }

    public void sendScoreboardList(Session session, List<String> scoreboardIds) {
        sendStringList(session, resourcePackets("scoreboard").list(), scoreboardIds);
    }

    public void sendTabList(Session session, List<String> tabIds) {
        sendStringList(session, resourcePackets("tab").list(), tabIds);
    }

    public void sendCustomContentList(Session session, List<String> contentIds) {
        sendStringList(session, resourcePackets("custom_content").list(), contentIds);
    }

    public void sendProjectMetadataList(Session session, List<String> metadataIds) {
        sendStringList(session, resourcePackets("project_metadata").list(), metadataIds);
    }

    public void sendJsonResourceList(Session session, byte packetId, List<String> ids) {
        sendStringList(session, packetId, ids);
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
        buffer.put(ReSyncProtocolContract.FLOW_PACKET_GUI_STATE);
        buffer.put((byte) (editable ? 1 : 0));
        buffer.putInt(guiBytes.length);
        buffer.put(guiBytes);
        buffer.putInt(flowBytes.length);
        buffer.put(flowBytes);
        sendRaw(session, buffer.array(), false);
    }

    public void sendEditTargetState(Session session, boolean editable, String type, String resourceId, String flowId) {
        if (type != null && type.length() > MAX_STRING_LENGTH) {
            sendError(session, "INVALID_EDIT_TARGET_TYPE", "Edit target type too long");
            return;
        }
        if (resourceId != null && resourceId.length() > MAX_STRING_LENGTH) {
            sendError(session, "INVALID_EDIT_TARGET_ID", "Edit target ID too long");
            return;
        }
        if (flowId != null && flowId.length() > MAX_STRING_LENGTH) {
            sendError(session, "INVALID_FLOW_ID", "Flow ID too long");
            return;
        }

        byte[] typeBytes = type != null ? type.getBytes(StandardCharsets.UTF_8) : new byte[0];
        byte[] idBytes = resourceId != null ? resourceId.getBytes(StandardCharsets.UTF_8) : new byte[0];
        byte[] flowBytes = flowId != null ? flowId.getBytes(StandardCharsets.UTF_8) : new byte[0];
        ByteBuffer buffer = ByteBuffer.allocate(1 + 1 + 4 + typeBytes.length + 4 + idBytes.length + 4 + flowBytes.length);
        buffer.put(ReSyncProtocolContract.FLOW_PACKET_EDIT_TARGET_STATE);
        buffer.put((byte) (editable ? 1 : 0));
        buffer.putInt(typeBytes.length);
        buffer.put(typeBytes);
        buffer.putInt(idBytes.length);
        buffer.put(idBytes);
        buffer.putInt(flowBytes.length);
        buffer.put(flowBytes);
        sendRaw(session, buffer.array(), false);
    }

    public void sendPlaceholderPreview(Session session, int requestId, String rendered) {
        byte[] renderedBytes = rendered.getBytes(StandardCharsets.UTF_8);
        ByteBuffer out = ByteBuffer.allocate(1 + 4 + 4 + renderedBytes.length);
        out.put(ReSyncProtocolContract.FLOW_PACKET_PLACEHOLDER_PREVIEW);
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
        byte packetId = snapshot.isFullSync() ? ReSyncProtocolContract.FLOW_PACKET_NODE_REGISTRY : ReSyncProtocolContract.FLOW_PACKET_NODE_REGISTRY_DELTA;
        ByteBuffer buffer = ByteBuffer.allocate(1 + jsonBytes.length);
        buffer.put(packetId);
        buffer.put(jsonBytes);
        sendRaw(session, buffer.array(), true);
    }

    public void sendOptionCatalog(Session session, String sourceId, List<String> values, String revision) {
        sendOptionCatalog(session, sourceId, values, List.of(), revision);
    }

    public void sendOptionCatalog(Session session, String sourceId, List<String> values, List<OptionCatalogItem> items, String revision) {
        sendOptionCatalog(session, sourceId, values, items, revision, 0L);
    }

    public void sendOptionCatalog(Session session, String sourceId, List<String> values, List<OptionCatalogItem> items, String revision, long sequence) {
        sendOptionCatalog(session, sourceId, "", values, items, revision, sequence);
    }

    public void sendOptionCatalog(Session session, String sourceId, String contextKey, List<String> values, List<OptionCatalogItem> items, String revision, long sequence) {
        sendOptionCatalog(session, sourceId, contextKey, values, items, revision, sequence, "available", "");
    }

    public void sendOptionCatalog(Session session, String sourceId, String contextKey, List<String> values, List<OptionCatalogItem> items, String revision, long sequence,
                                  String status, String diagnostic) {
        String json = gson.toJson(new OptionCatalogSnapshot(sourceId, contextKey, revision, sequence, values, items, status, diagnostic));
        byte[] jsonBytes = json.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(1 + jsonBytes.length);
        buffer.put(ReSyncProtocolContract.FLOW_PACKET_OPTION_CATALOG);
        buffer.put(jsonBytes);
        sendRaw(session, buffer.array(), true);
    }

    public void broadcastOptionCatalog(String sourceId, List<String> values, List<OptionCatalogItem> items, String revision) {
        broadcastOptionCatalog(sourceId, values, items, revision, 0L);
    }

    public void broadcastOptionCatalog(String sourceId, List<String> values, List<OptionCatalogItem> items, String revision, long sequence) {
        broadcastOptionCatalog(sourceId, values, items, revision, sequence, "available", "");
    }

    public void broadcastOptionCatalog(String sourceId, List<String> values, List<OptionCatalogItem> items, String revision, long sequence, String status, String diagnostic) {
        for (Session session : subscribedSessions) {
            sendOptionCatalog(session, sourceId, "", values, items, revision, sequence, status, diagnostic);
        }
    }

    public void sendTraceSnapshot(Session session, List<FlowTraceRecord> records) {
        sendTracePacket(session, ReSyncProtocolContract.FLOW_PACKET_TRACE_SNAPSHOT, records == null ? List.of() : records);
    }

    public void sendTraceEvent(Session session, FlowTraceRecord record) {
        sendTracePacket(session, ReSyncProtocolContract.FLOW_PACKET_TRACE_EVENT, record);
    }

    public void sendDebugSnapshot(Session session, Object payload) {
        sendTracePacket(session, ReSyncProtocolContract.FLOW_PACKET_DEBUG_EVENT, payload);
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
        buffer.put(ReSyncProtocolContract.FLOW_PACKET_JOB);
        buffer.put(jsonBytes);
        sendRaw(session, buffer.array(), false);
    }

    public void sendError(Session session, String errorCode, String message) {
        byte[] errorBytes = message.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(1 + 4 + errorBytes.length);
        buffer.put(ReSyncProtocolContract.FLOW_PACKET_ERROR);
        buffer.putInt(errorBytes.length);
        buffer.put(errorBytes);
        sendRaw(session, buffer.array(), false);
        Log.warn("Error sent to " + session.getClientId() + ": " + errorCode + " - " + message);
    }

    public void sendEditorError(Session session, EditorError error) {
        String payload = EditorError.PREFIX + gson.toJson(error);
        byte[] errorBytes = payload.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(1 + 4 + errorBytes.length);
        buffer.put(ReSyncProtocolContract.FLOW_PACKET_ERROR);
        buffer.putInt(errorBytes.length);
        buffer.put(errorBytes);
        sendRaw(session, buffer.array(), false);
        Log.warn("Editor issue sent to " + session.getClientId() + ": " + error.title() + " - " + error.diagnostics().size() + " issue"
            + (error.diagnostics().size() == 1 ? "" : "s"));
    }

    public void sendPresenceSnapshot(Session session, String json) {
        sendJsonPacket(session, ReSyncProtocolContract.FLOW_PACKET_PRESENCE_SNAPSHOT, json, "PRESENCE_TOO_LARGE", "Presence snapshot exceeds maximum size");
    }

    public void sendCollaborationMessage(Session session, String json) {
        sendJsonPacket(session, ReSyncProtocolContract.FLOW_PACKET_COLLABORATION_CHAT, json, "CHAT_TOO_LARGE", "Chat message exceeds maximum size");
    }

    public void sendResourceChanged(Session session, String json) {
        sendJsonPacket(session, ReSyncProtocolContract.FLOW_PACKET_RESOURCE_CHANGED, json, "RESOURCE_EVENT_TOO_LARGE", "Resource event exceeds maximum size");
    }

    public void sendResourceDeleted(Session session, String json) {
        sendJsonPacket(session, ReSyncProtocolContract.FLOW_PACKET_RESOURCE_DELETED, json, "RESOURCE_EVENT_TOO_LARGE", "Resource event exceeds maximum size");
    }

    public void sendWorkspaceSnapshot(Session session, String json) {
        sendJsonPacket(session, ReSyncProtocolContract.FLOW_PACKET_WORKSPACE_SNAPSHOT, json, "WORKSPACE_TOO_LARGE", "Workspace snapshot exceeds maximum size");
    }

    public void sendWorkspaceOperation(Session session, String json) {
        sendJsonPacket(session, ReSyncProtocolContract.FLOW_PACKET_WORKSPACE_OPERATION, json, "WORKSPACE_TOO_LARGE", "Workspace operation exceeds maximum size");
    }

    public void sendWorkspaceAwareness(Session session, String json) {
        sendJsonPacket(session, ReSyncProtocolContract.FLOW_PACKET_WORKSPACE_AWARENESS, json, "WORKSPACE_TOO_LARGE", "Workspace awareness exceeds maximum size");
    }

    public void sendWorkspaceResync(Session session, String json) {
        sendJsonPacket(session, ReSyncProtocolContract.FLOW_PACKET_WORKSPACE_RESYNC, json, "WORKSPACE_TOO_LARGE", "Workspace resync exceeds maximum size");
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

    private ReSyncProtocolContract.ResourceFlowPackets resourcePackets(String typeId) {
        ReSyncProtocolContract.ResourceContract resource = ReSyncProtocolContract.resource(typeId);
        if (resource == null || resource.flowPackets() == null) {
            throw new IllegalStateException("Missing Flow resource packet contract: " + typeId);
        }
        return resource.flowPackets();
    }

    private void sendIdAck(Session session, byte packetId, String id, String requestId) {
        if (id == null) {
            return;
        }
        if (requestId == null || requestId.isBlank()) {
            sendIdAck(session, packetId, id);
            return;
        }
        byte[] idBytes = id.getBytes(StandardCharsets.UTF_8);
        byte[] requestIdBytes = requestId.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(1 + 4 + idBytes.length + 4 + requestIdBytes.length);
        buffer.put(packetId);
        buffer.putInt(idBytes.length);
        buffer.put(idBytes);
        buffer.putInt(requestIdBytes.length);
        buffer.put(requestIdBytes);
        sendRaw(session, buffer.array(), false);
    }

    private void sendIdAck(Session session, byte packetId, String id, String requestId, long revision, String hash) {
        if (id == null) {
            return;
        }
        byte[] idBytes = id.getBytes(StandardCharsets.UTF_8);
        byte[] requestIdBytes = requestId != null ? requestId.getBytes(StandardCharsets.UTF_8) : new byte[0];
        byte[] hashBytes = hash != null ? hash.getBytes(StandardCharsets.UTF_8) : new byte[0];
        ByteBuffer buffer = ByteBuffer.allocate(1 + 4 + idBytes.length + 4 + requestIdBytes.length + Long.BYTES + 4 + hashBytes.length);
        buffer.put(packetId);
        buffer.putInt(idBytes.length);
        buffer.put(idBytes);
        buffer.putInt(requestIdBytes.length);
        buffer.put(requestIdBytes);
        buffer.putLong(Math.max(0L, revision));
        buffer.putInt(hashBytes.length);
        buffer.put(hashBytes);
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
        if (session == null || payload == null || session.getConnection() == null || !session.getConnection().isOpen()) {
            return;
        }
        DataMessage msg = new DataMessage();
        msg.setChannel(channelId);
        msg.setPayload(payload);
        codec.sendMessage(session.getConnection().getFrameSender(), msg, channelId, compress);
    }
}
