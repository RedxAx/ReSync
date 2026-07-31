package restudio.resync.modules.flow;

import restudio.resync.core.Session;
import restudio.resync.customcontent.ItemAttributeValidationException;
import restudio.resync.flow.ResourceRevisionConflictException;
import restudio.resync.flow.contract.EditorDiagnostic;
import restudio.resync.flow.contract.EditorError;
import restudio.resync.flow.validation.FlowGraphValidationException;
import restudio.resync.jobs.JobRecord;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class FlowResourcePacketHandler<T> {
    private final FlowResourceAdapter<T> adapter;
    private final FlowPacketSender sender;
    private final FlowResourceRegistry registry;

    public FlowResourcePacketHandler(FlowResourceAdapter<T> adapter, FlowPacketSender sender, FlowResourceRegistry registry) {
        this.adapter = adapter;
        this.sender = sender;
        this.registry = registry;
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
        T value = null;
        String id = "";
        try {
            value = adapter.deserialize(payload.payload());
            id = value != null ? adapter.id(value) : null;
            if (id == null || id.isBlank()) {
                sender.failJob(job, adapter.missingIdMessage(), null);
                sender.sendError(session, adapter.invalidValueCode(), adapter.missingIdMessage());
                return;
            }
            adapter.validate(value);
            value = registry.saveFromSession(session, adapter, value);
            registry.completeSessionSave(session, adapter, value);
            adapter.sendSaveAck(session, id, payload.requestId());
            sender.succeedJob(job, id, "Saved");
        } catch (FlowGraphValidationException exception) {
            sender.failJob(job, "Fix the highlighted issues before saving", exception);
            List<EditorDiagnostic> diagnostics = exception.getResult().diagnostics().stream()
                .map(diagnostic -> new EditorDiagnostic(
                    EditorDiagnostic.Severity.valueOf(diagnostic.severity().name()),
                    diagnostic.code(), diagnostic.nodeId(), diagnostic.pin(), "", diagnostic.message(), diagnostic.remediation()))
                .toList();
            String displayName = adapter.descriptor().displayName();
            sender.sendEditorError(session, new EditorError(adapter.saveErrorCode(), adapter.descriptor().typeId(), id,
                displayName + " Needs Attention", "Fix the highlighted issues before saving.", diagnostics));
        } catch (ItemAttributeValidationException exception) {
            sender.failJob(job, "Review the highlighted components before saving", exception);
            sender.sendError(session, adapter.saveErrorCode(), adapter.saveFailureMessage(exception));
        } catch (ResourceRevisionConflictException exception) {
            sender.failJob(job, "Reload the latest version before saving", exception);
            String displayName = adapter.descriptor().displayName();
            EditorDiagnostic diagnostic = new EditorDiagnostic(EditorDiagnostic.Severity.ERROR, "RESOURCE_REVISION_CONFLICT", "", "", "",
                "The server has a newer version of this resource", "Reload the latest version and review the merged changes");
            sender.sendEditorError(session, new EditorError(adapter.saveErrorCode(), adapter.descriptor().typeId(), id,
                displayName + " Updated", "A newer version is available.", List.of(diagnostic)));
        } catch (IllegalArgumentException exception) {
            sender.failJob(job, "Review the editor before saving", exception);
            String displayName = adapter.descriptor().displayName();
            EditorDiagnostic diagnostic = new EditorDiagnostic(EditorDiagnostic.Severity.ERROR, adapter.invalidValueCode(), "", "", "",
                exception.getMessage(), "Review the editor and try again");
            sender.sendEditorError(session, new EditorError(adapter.saveErrorCode(), adapter.descriptor().typeId(), id,
                displayName + " Needs Attention", "Review the editor before saving.", List.of(diagnostic)));
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
            registry.deleteSerialized(adapter, id);
            registry.runLiveRefresh(() -> adapter.afterDelete(session, id));
            registry.notifyDeleted(session, adapter.descriptor().typeId(), id);
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
