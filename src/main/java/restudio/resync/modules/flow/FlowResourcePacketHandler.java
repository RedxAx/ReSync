package restudio.resync.modules.flow;

import restudio.resync.core.Session;
import restudio.resync.customcontent.ItemAttributeValidationException;
import restudio.resync.flow.ResourceRevisionConflictException;
import restudio.resync.flow.contract.EditorDiagnostic;
import restudio.resync.flow.contract.EditorError;
import restudio.resync.flow.validation.FlowGraphValidationException;
import restudio.resync.jobs.JobRecord;
import restudio.resync.server.AuthorityEpoch;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class FlowResourcePacketHandler<T> {
    private final FlowResourceAdapter<T> adapter;
    private final FlowPacketSender sender;
    private final FlowResourceRegistry registry;
    private final FlowResourceProtocolAuthority protocolAuthority;
    private final AuthorityEpoch authorityEpoch;
    private final Predicate<Session> legacyCompatibility;

    public FlowResourcePacketHandler(FlowResourceAdapter<T> adapter, FlowPacketSender sender, FlowResourceRegistry registry) {
        this(adapter, sender, registry, FlowResourceProtocolAuthority.shared(), requireExplicitAuthorityEpoch(),
            FlowMutationPayloadReader::legacyCompatible);
    }

    public FlowResourcePacketHandler(FlowResourceAdapter<T> adapter, FlowPacketSender sender, FlowResourceRegistry registry,
                                     AuthorityEpoch authorityEpoch, boolean legacyCompatible) {
        this(adapter, sender, registry, FlowResourceProtocolAuthority.shared(), authorityEpoch,
            legacyCompatible ? FlowMutationPayloadReader::legacyCompatible : ignored -> false);
    }

    public FlowResourcePacketHandler(FlowResourceAdapter<T> adapter, FlowPacketSender sender, FlowResourceRegistry registry,
                                     AuthorityEpoch authorityEpoch) {
        this(adapter, sender, registry, authorityEpoch, false);
    }

    FlowResourcePacketHandler(FlowResourceAdapter<T> adapter, FlowPacketSender sender, FlowResourceRegistry registry,
                              FlowResourceProtocolAuthority protocolAuthority) {
        this(adapter, sender, registry, protocolAuthority, requireExplicitAuthorityEpoch(),
            FlowMutationPayloadReader::legacyCompatible);
    }

    FlowResourcePacketHandler(FlowResourceAdapter<T> adapter, FlowPacketSender sender, FlowResourceRegistry registry,
                              FlowResourceProtocolAuthority protocolAuthority, AuthorityEpoch authorityEpoch,
                              Predicate<Session> legacyCompatibility) {
        this.adapter = adapter;
        this.sender = sender;
        this.registry = registry;
        this.protocolAuthority = Objects.requireNonNull(protocolAuthority, "Protocol authority is required");
        AuthorityEpoch boundAuthorityEpoch = Objects.requireNonNull(authorityEpoch, "Authority epoch is required");
        if (boundAuthorityEpoch.current() < 1L) {
            throw new IllegalArgumentException("Authority epoch must be positive");
        }
        this.authorityEpoch = boundAuthorityEpoch;
        Predicate<Session> configuredLegacyCompatibility = Objects.requireNonNull(legacyCompatibility,
            "Legacy compatibility policy is required");
        this.legacyCompatibility = session -> configuredLegacyCompatibility.test(session)
            && FlowMutationPayloadReader.legacyCompatible(session);
        this.protocolAuthority.validateDescriptor(adapter.descriptor());
    }

    public boolean handle(Session session, byte packetId, ByteBuffer buffer) {
        if (!protocolAuthority.matches(adapter.descriptor(), packetId)) {
            return false;
        }
        var packets = protocolAuthority.requireFlowPackets(adapter.descriptor().typeId());
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
        if (registry.genericMutationAuthorityRequired()) {
            sender.sendError(session, "RESOURCE_GENERIC_AUTHORITY_REQUIRED", registry.genericMutationAuthorityReason());
            return;
        }
        if (!buffer.hasRemaining()) {
            sender.sendError(session, "INVALID_SAVE", "No data provided");
            return;
        }
        if (buffer.remaining() > FlowPacketSender.MAX_PACKET_SIZE) {
            sender.sendError(session, "SAVE_TOO_LARGE", "Save data exceeds maximum size");
            return;
        }
        FlowMutationPayload payload = FlowMutationPayloadReader.read(buffer);
        if (!validateMutationEpoch(session, payload)) {
            return;
        }
        MutationCancellation cancellation = new MutationCancellation();
        JobRecord<String> job = sender.beginJob(session, adapter.saveAction(), "", payload.requestId());
        if (job == null) {
            return;
        }
        sender.setJobCancellation(job, cancellation::request);
        T value = null;
        String id = "";
        AtomicBoolean settled = new AtomicBoolean();
        AtomicBoolean executionCompleted = new AtomicBoolean();
        AtomicBoolean completionOwned = new AtomicBoolean();
        try {
            if (cancellation.requested()) {
                return;
            }
            value = adapter.deserialize(payload.payload());
            id = value != null ? adapter.id(value) : null;
            if (id == null || id.isBlank()) {
                if (!cancellation.requested()) {
                    sender.failJob(job, adapter.missingIdMessage(), null);
                    sender.sendError(session, adapter.invalidValueCode(), adapter.missingIdMessage());
                }
                return;
            }
            adapter.validate(value);
            String savedId = id;
            completionOwned.set(true);
            try {
                registry.saveFromSession(session, adapter, value,
                    cancellation::bind,
                    () -> completeSave(session, job, savedId, payload.requestId(), cancellation, settled, executionCompleted),
                    exception -> failSave(session, job, exception, cancellation, settled, executionCompleted));
            } catch (RuntimeException exception) {
                completionOwned.set(false);
                throw exception;
            }
        } catch (FlowGraphValidationException exception) {
            if (settled.compareAndSet(false, true) && !cancellation.requested()) {
                sender.failJob(job, "Fix the highlighted issues before saving", exception);
                List<EditorDiagnostic> diagnostics = exception.getResult().diagnostics().stream()
                    .map(diagnostic -> new EditorDiagnostic(
                        EditorDiagnostic.Severity.valueOf(diagnostic.severity().name()),
                        diagnostic.code(), diagnostic.nodeId(), diagnostic.pin(), "", diagnostic.message(), diagnostic.remediation()))
                    .toList();
                String displayName = adapter.descriptor().displayName();
                sender.sendEditorError(session, new EditorError(adapter.saveErrorCode(), adapter.descriptor().typeId(), id,
                    displayName + " Needs Attention", "Fix the highlighted issues before saving.", diagnostics));
            }
        } catch (ItemAttributeValidationException exception) {
            if (settled.compareAndSet(false, true) && !cancellation.requested()) {
                sender.failJob(job, "Review the highlighted components before saving", exception);
                sender.sendError(session, adapter.saveErrorCode(), adapter.saveFailureMessage(exception));
            }
        } catch (ResourceRevisionConflictException exception) {
            if (settled.compareAndSet(false, true) && !cancellation.requested()) {
                sender.failJob(job, "Reload the latest version before saving", exception);
                String displayName = adapter.descriptor().displayName();
                EditorDiagnostic diagnostic = new EditorDiagnostic(EditorDiagnostic.Severity.ERROR, "RESOURCE_REVISION_CONFLICT", "", "", "",
                    "The server has a newer version of this resource", "Reload the latest version and review the merged changes");
                sender.sendEditorError(session, new EditorError(adapter.saveErrorCode(), adapter.descriptor().typeId(), id,
                    displayName + " Updated", "A newer version is available.", List.of(diagnostic)));
            }
        } catch (IllegalArgumentException exception) {
            if (settled.compareAndSet(false, true) && !cancellation.requested()) {
                sender.failJob(job, "Review the editor before saving", exception);
                String displayName = adapter.descriptor().displayName();
                EditorDiagnostic diagnostic = new EditorDiagnostic(EditorDiagnostic.Severity.ERROR, adapter.invalidValueCode(), "", "", "",
                    exception.getMessage(), "Review the editor and try again");
                sender.sendEditorError(session, new EditorError(adapter.saveErrorCode(), adapter.descriptor().typeId(), id,
                    displayName + " Needs Attention", "Review the editor before saving.", List.of(diagnostic)));
            }
        } catch (Exception exception) {
            if (settled.compareAndSet(false, true) && !cancellation.requested()) {
                sender.failJob(job, exception.getMessage(), exception);
                sender.sendError(session, adapter.saveErrorCode(), adapter.saveFailureMessage(exception));
            }
        } finally {
            if (!completionOwned.get()) {
                completeJob(job, executionCompleted);
            }
        }
    }

    private void handleDelete(Session session, ByteBuffer buffer) {
        if (registry.genericMutationAuthorityRequired()) {
            sender.sendError(session, "RESOURCE_GENERIC_AUTHORITY_REQUIRED", registry.genericMutationAuthorityReason());
            return;
        }
        if (!buffer.hasRemaining()) {
            return;
        }
        FlowMutationPayload payload = FlowMutationPayloadReader.read(buffer);
        if (!validateMutationEpoch(session, payload)) {
            return;
        }
        String id = FlowMutationPayloadReader.deleteResourceId(payload.payload());
        MutationCancellation cancellation = new MutationCancellation();
        JobRecord<String> job = sender.beginJob(session, adapter.deleteAction(), id, payload.requestId());
        if (job == null) {
            return;
        }
        sender.setJobCancellation(job, cancellation::request);
        AtomicBoolean settled = new AtomicBoolean();
        AtomicBoolean executionCompleted = new AtomicBoolean();
        AtomicBoolean completionOwned = new AtomicBoolean();
        try {
            if (cancellation.requested()) {
                return;
            }
            completionOwned.set(true);
            var result = registry.deleteFromSession(session, adapter, id,
                new FlowResourceMutationContext("protocol", "", "", session != null ? session.getClientId() : "server"),
                cancellation::bind,
                () -> completeDelete(job, id, cancellation, settled, executionCompleted),
                exception -> failDelete(session, job, exception, cancellation, settled, executionCompleted));
            if (!result.success()) {
                completionOwned.set(false);
                throw new IllegalStateException(result.message());
            }
        } catch (Exception exception) {
            if (settled.compareAndSet(false, true) && !cancellation.requested()) {
                sender.failJob(job, exception.getMessage(), exception);
                sender.sendError(session, adapter.deleteErrorCode(), adapter.deleteFailureMessage(exception));
            }
        } finally {
            if (!completionOwned.get()) {
                completeJob(job, executionCompleted);
            }
        }
    }

    private void completeSave(Session session, JobRecord<String> job, String id, String requestId,
                              MutationCancellation cancellation,
                              AtomicBoolean settled, AtomicBoolean executionCompleted) {
        try {
            if (settled.compareAndSet(false, true) && !cancellation.requested()) {
                adapter.sendSaveAck(session, id, requestId);
                sender.succeedJob(job, id, "Saved");
            }
        } finally {
            completeJob(job, executionCompleted);
        }
    }

    private void failSave(Session session, JobRecord<String> job, RuntimeException exception,
                          MutationCancellation cancellation,
                          AtomicBoolean settled, AtomicBoolean executionCompleted) {
        try {
            if (settled.compareAndSet(false, true) && !cancellation.requested()) {
                sender.failJob(job, exception.getMessage(), exception);
                sender.sendError(session, adapter.saveErrorCode(), adapter.saveFailureMessage(exception));
            }
        } finally {
            completeJob(job, executionCompleted);
        }
    }

    private void completeDelete(JobRecord<String> job, String id, MutationCancellation cancellation,
                                AtomicBoolean settled,
                                AtomicBoolean executionCompleted) {
        try {
            if (settled.compareAndSet(false, true) && !cancellation.requested()) {
                sender.succeedJob(job, id, "Deleted");
            }
        } finally {
            completeJob(job, executionCompleted);
        }
    }

    private void failDelete(Session session, JobRecord<String> job, RuntimeException exception,
                            MutationCancellation cancellation,
                            AtomicBoolean settled, AtomicBoolean executionCompleted) {
        try {
            if (settled.compareAndSet(false, true) && !cancellation.requested()) {
                sender.failJob(job, exception.getMessage(), exception);
                sender.sendError(session, adapter.deleteErrorCode(), adapter.deleteFailureMessage(exception));
            }
        } finally {
            completeJob(job, executionCompleted);
        }
    }

    private void completeJob(JobRecord<?> job, AtomicBoolean executionCompleted) {
        if (executionCompleted.compareAndSet(false, true)) {
            sender.completeJobExecution(job);
        }
    }

    private boolean validateMutationEpoch(Session session, FlowMutationPayload payload) {
        FlowMutationPayloadReader.EpochDecision decision = FlowMutationPayloadReader.validateAuthorityEpoch(
            payload, authorityEpoch, legacyCompatibility.test(session));
        if (decision.accepted()) {
            return true;
        }
        sender.sendError(session, decision.code(), decision.message());
        return false;
    }

    private static final class MutationCancellation {
        private final AtomicBoolean requested = new AtomicBoolean();
        private final AtomicBoolean invoked = new AtomicBoolean();
        private final AtomicReference<Runnable> finalizer = new AtomicReference<>();

        private void request() {
            requested.set(true);
            invoke();
        }

        private void bind(Runnable action) {
            if (!finalizer.compareAndSet(null, Objects.requireNonNull(action, "Mutation Finalizer Is Required"))) {
                throw new IllegalStateException("Mutation finalizer is already bound");
            }
            invoke();
        }

        private boolean requested() {
            return requested.get();
        }

        private void invoke() {
            Runnable action = finalizer.get();
            if (action != null && requested.get() && invoked.compareAndSet(false, true)) {
                action.run();
            }
        }
    }

    private String readRemaining(ByteBuffer buffer) {
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static AuthorityEpoch requireExplicitAuthorityEpoch() {
        throw new IllegalStateException("Authority epoch is required; use the bound-epoch constructor");
    }
}
