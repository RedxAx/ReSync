package restudio.resync.modules.flow;

import restudio.resync.core.Session;
import restudio.resync.flow.FlowStorage;
import restudio.resync.jobs.JobRecord;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public class FlowProjectMetadataPacketHandler {
    private final FlowStorage storage;
    private final FlowPacketSender sender;

    public FlowProjectMetadataPacketHandler(FlowStorage storage, FlowPacketSender sender) {
        this.storage = storage;
        this.sender = sender;
    }

    public void handleRequest(Session session, ByteBuffer buffer) {
        String metadataId = buffer.hasRemaining() ? readRemaining(buffer) : "project";
        if (metadataId.length() > FlowPacketSender.MAX_STRING_LENGTH) {
            sender.sendError(session, "INVALID_PROJECT_METADATA_ID", "Project metadata ID too long");
            return;
        }
        String json = storage.getProjectMetadata(metadataId);
        if (json == null && !"project".equals(metadataId)) {
            json = storage.getProjectMetadata("project");
        }
        if (json != null) {
            sender.sendProjectMetadataData(session, json);
        } else {
            sender.sendError(session, "PROJECT_METADATA_NOT_FOUND", "Project metadata not found: " + metadataId);
        }
    }

    public void handleListRequest(Session session) {
        sender.sendProjectMetadataList(session, storage.listProjectMetadataIds());
    }

    public void handleSave(Session session, ByteBuffer buffer) {
        if (!buffer.hasRemaining()) {
            sender.sendError(session, "INVALID_PROJECT_METADATA_SAVE", "No project metadata provided");
            return;
        }
        if (buffer.remaining() > FlowPacketSender.MAX_PACKET_SIZE) {
            sender.sendError(session, "PROJECT_METADATA_TOO_LARGE", "Project metadata exceeds maximum size");
            return;
        }
        FlowMutationPayload payload = FlowMutationPayloadReader.read(buffer);
        String json = payload.payload();
        JobRecord<String> job = sender.beginJob(session, "saveProjectMetadata", "project", payload.requestId());
        if (job == null) {
            return;
        }
        try {
            storage.saveProjectMetadata(json);
            sender.sendProjectMetadataSaveAck(session, "project");
            sender.succeedJob(job, "project", "Saved");
        } catch (Exception e) {
            sender.failJob(job, e.getMessage(), e);
            sender.sendError(session, "PROJECT_METADATA_SAVE_FAILED", e.getMessage());
        }
    }

    public void handleDelete(Session session, ByteBuffer buffer) {
        if (!buffer.hasRemaining()) {
            return;
        }
        FlowMutationPayload payload = FlowMutationPayloadReader.read(buffer);
        String metadataId = payload.payload();
        JobRecord<String> job = sender.beginJob(session, "deleteProjectMetadata", metadataId, payload.requestId());
        if (job == null) {
            return;
        }
        try {
            storage.deleteProjectMetadata(metadataId);
            sender.succeedJob(job, metadataId, "Deleted");
        } catch (Exception e) {
            sender.failJob(job, e.getMessage(), e);
            sender.sendError(session, "PROJECT_METADATA_DELETE_FAILED", e.getMessage());
        }
    }

    private String readRemaining(ByteBuffer buffer) {
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
