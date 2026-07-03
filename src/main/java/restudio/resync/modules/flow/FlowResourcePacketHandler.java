package restudio.resync.modules.flow;

import restudio.resync.core.Session;
import restudio.resync.jobs.JobRecord;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public class FlowResourcePacketHandler<T> {
    private final FlowResourceAdapter<T> adapter;
    private final FlowPacketSender sender;

    public FlowResourcePacketHandler(FlowResourceAdapter<T> adapter, FlowPacketSender sender) {
        this.adapter = adapter;
        this.sender = sender;
    }

    public boolean handle(Session session, byte packetId, ByteBuffer buffer) {
        var packets = adapter.descriptor().flowPackets();
        if (packets == null || !packets.matches(packetId)) {
            return false;
        }
        if (packetId == packets.request()) {
            handleRequest(session, buffer);
        } else if (packetId == packets.listRequest()) {
            handleListRequest(session);
        } else if (packetId == packets.save()) {
            handleSave(session, buffer);
        } else if (packetId == packets.delete()) {
            handleDelete(session, buffer);
        }
        return true;
    }

    private void handleRequest(Session session, ByteBuffer buffer) {
        String id;
        if (!buffer.hasRemaining() && adapter.defaultRequestId() != null) {
            id = adapter.defaultRequestId();
        } else if (!buffer.hasRemaining()) {
            sender.sendError(session, "INVALID_REQUEST", adapter.requestMissingMessage());
            return;
        } else {
            id = readRemaining(buffer);
        }
        if (id.length() > FlowPacketSender.MAX_STRING_LENGTH) {
            sender.sendError(session, adapter.invalidIdCode(), adapter.descriptor().displayName() + " ID too long");
            return;
        }
        T value = adapter.get(id);
        if (value != null) {
            adapter.sendData(session, value);
        } else {
            sender.sendError(session, adapter.notFoundCode(), adapter.notFoundMessage(id));
        }
    }

    private void handleListRequest(Session session) {
        adapter.sendList(session, adapter.listIds());
    }

    private void handleSave(Session session, ByteBuffer buffer) {
        if (!buffer.hasRemaining()) {
            sender.sendError(session, "INVALID_SAVE", "No data provided");
            return;
        }
        if (buffer.remaining() > FlowPacketSender.MAX_PACKET_SIZE) {
            sender.sendError(session, "SAVE_TOO_LARGE", "Save data exceeds maximum size");
            return;
        }
        FlowMutationPayload payload = FlowMutationPayloadReader.read(buffer);
        JobRecord<String> job = sender.beginJob(session, adapter.saveAction(), "", payload.requestId());
        if (job == null) {
            return;
        }
        try {
            T value = adapter.deserialize(payload.payload());
            String id = value != null ? adapter.id(value) : null;
            if (id == null || id.isBlank()) {
                sender.failJob(job, adapter.missingIdMessage(), null);
                sender.sendError(session, adapter.invalidValueCode(), adapter.missingIdMessage());
                return;
            }
            adapter.save(value);
            adapter.afterSave(session, value);
            adapter.sendSaveAck(session, id, payload.requestId());
            sender.succeedJob(job, id, "Saved");
        } catch (Exception exception) {
            sender.failJob(job, exception.getMessage(), exception);
            sender.sendError(session, adapter.saveErrorCode(), adapter.saveFailureMessage(exception));
        }
    }

    private void handleDelete(Session session, ByteBuffer buffer) {
        if (!buffer.hasRemaining()) {
            return;
        }
        FlowMutationPayload payload = FlowMutationPayloadReader.read(buffer);
        String id = payload.payload();
        JobRecord<String> job = sender.beginJob(session, adapter.deleteAction(), id, payload.requestId());
        if (job == null) {
            return;
        }
        try {
            adapter.delete(id);
            adapter.afterDelete(session, id);
            sender.succeedJob(job, id, "Deleted");
        } catch (Exception exception) {
            sender.failJob(job, exception.getMessage(), exception);
            sender.sendError(session, adapter.deleteErrorCode(), adapter.deleteFailureMessage(exception));
        }
    }

    private String readRemaining(ByteBuffer buffer) {
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
