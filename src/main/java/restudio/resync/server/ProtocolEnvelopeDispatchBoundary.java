package restudio.resync.server;

import restudio.resync.core.ConnectionInfo;
import restudio.resync.core.ConnectionState;
import restudio.resync.core.Session;
import restudio.resync.flow.protocol.ProtocolEnvelope;
import restudio.resync.flow.protocol.ProtocolBody;
import restudio.resync.flow.protocol.ProtocolRejectionCode;
import restudio.resync.protocol.ProtocolEnvelopeBoundary;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class ProtocolEnvelopeDispatchBoundary {
    private final ProtocolEnvelopeBoundary envelopeBoundary;
    private final AuthorityEpoch authorityEpoch;
    private volatile ProtocolEnvelopeHandler handler;

    public ProtocolEnvelopeDispatchBoundary(ProtocolEnvelopeHandler handler) {
        this(AuthorityEpoch.fixed(1L), handler);
    }

    public ProtocolEnvelopeDispatchBoundary(AuthorityEpoch authorityEpoch, ProtocolEnvelopeHandler handler) {
        this(new ProtocolEnvelopeBoundary(), authorityEpoch, handler);
    }

    public ProtocolEnvelopeDispatchBoundary(ProtocolEnvelopeBoundary envelopeBoundary, ProtocolEnvelopeHandler handler) {
        this(envelopeBoundary, AuthorityEpoch.fixed(1L), handler);
    }

    public ProtocolEnvelopeDispatchBoundary(ProtocolEnvelopeBoundary envelopeBoundary, AuthorityEpoch authorityEpoch,
                                            ProtocolEnvelopeHandler handler) {
        this.envelopeBoundary = Objects.requireNonNull(envelopeBoundary, "Envelope boundary is required");
        this.authorityEpoch = authorityEpoch != null ? authorityEpoch : AuthorityEpoch.fixed(1L);
        this.handler = handler;
    }

    public void setHandler(ProtocolEnvelopeHandler handler) {
        this.handler = handler;
    }

    public boolean isSupported() {
        return handler != null;
    }

    public ProtocolEnvelopeDispatchResult dispatch(ConnectionInfo connection, Session session, byte[] payload) {
        long started = TemporaryLifecycleDiagnostics.start();
        Map<String, Object> ingress = TemporaryLifecycleDiagnostics.with(
            TemporaryLifecycleDiagnostics.identity(null, "protocol", null, null, null, null, null, null),
            "connectionHash", TemporaryLifecycleDiagnostics.safeHash(
                connection == null ? null : connection.getConnectionId()),
            "requestBytes", payload == null ? 0 : payload.length);
        TemporaryLifecycleDiagnostics.event("protocol_dispatch_ingress", started,
            TemporaryLifecycleDiagnostics.with(ingress, "outcome", "received"));
        ProtocolEnvelope<Map<String, Object>> envelope;
        try {
            envelope = envelopeBoundary.decode(payload);
        } catch (RuntimeException exception) {
            TemporaryLifecycleDiagnostics.event("protocol_dispatch_decode", started,
                TemporaryLifecycleDiagnostics.with(ingress, "outcome", "rejected", "decoded", false,
                    "diagnosticCode", ProtocolRejectionCode.INVALID_PAYLOAD,
                    "failure", exception.getClass().getSimpleName()));
            return reject(ProtocolRejectionCode.INVALID_PAYLOAD, "Protocol envelope payload is malformed");
        }
        Map<String, Object> identity = diagnostic(connection, envelope);
        TemporaryLifecycleDiagnostics.event("protocol_dispatch_decode", started,
            TemporaryLifecycleDiagnostics.with(identity, "outcome", "accepted", "decoded", true,
                "requestBytes", payload == null ? 0 : payload.length));
        if (connection == null || connection.getState() != ConnectionState.AUTHENTICATED || session == null
            || session.getConnection() != connection) {
            gate(identity, started, "authentication", false, ProtocolRejectionCode.AUTHENTICATION_REQUIRED);
            return reject(envelope, ProtocolRejectionCode.AUTHENTICATION_REQUIRED,
                "Protocol envelope requires an authenticated connection");
        }
        gate(identity, started, "authentication", true, null);
        if (handler == null) {
            gate(identity, started, "handler", false, ProtocolRejectionCode.UNSUPPORTED_GENERATION);
            return reject(envelope, ProtocolRejectionCode.UNSUPPORTED_GENERATION,
                "Protocol envelope dispatch is unavailable");
        }
        gate(identity, started, "handler", true, null);
        if (requiresAuthorityEpoch(envelope) && !authorityEpoch.acceptsTyped(envelope.authorityEpoch())) {
            gate(identity, started, "authority_epoch", false, ProtocolRejectionCode.RESOURCE_REVISION_CONFLICT);
            return reject(envelope, ProtocolRejectionCode.RESOURCE_REVISION_CONFLICT,
                "Protocol envelope authority epoch is stale");
        }
        gate(identity, started, "authority_epoch", true, null);
        try {
            if (!handler.supports(envelope)) {
                gate(identity, started, "support", false, ProtocolRejectionCode.UNSUPPORTED_GENERATION);
                return reject(envelope, ProtocolRejectionCode.UNSUPPORTED_GENERATION,
                    "Protocol envelope operation is unsupported");
            }
            gate(identity, started, "support", true, null);
            if (!handler.authorize(connection, session, envelope)) {
                gate(identity, started, "authorization", false, ProtocolRejectionCode.AUTHORIZATION_DENIED);
                return reject(envelope, ProtocolRejectionCode.AUTHORIZATION_DENIED,
                    "Protocol envelope operation is not authorized");
            }
            gate(identity, started, "authorization", true, null);
            long handlerStarted = TemporaryLifecycleDiagnostics.start();
            ProtocolEnvelopeDispatchResult result = handler.handle(connection, session, envelope);
            if (result == null) {
                TemporaryLifecycleDiagnostics.event("protocol_handler_settlement", handlerStarted,
                    TemporaryLifecycleDiagnostics.with(identity, "outcome", "missing",
                        "diagnosticCode", ProtocolRejectionCode.INVALID_PAYLOAD, "responsePrepared", false));
                return reject(envelope, ProtocolRejectionCode.INVALID_PAYLOAD,
                    "Protocol envelope handler returned no result");
            }
            if (!result.handled()) {
                ProtocolEnvelopeDispatchResult rejected = correlateRejection(envelope, result);
                TemporaryLifecycleDiagnostics.event("protocol_handler_settlement", handlerStarted,
                    TemporaryLifecycleDiagnostics.with(identity, "outcome", rejected.status(),
                        "diagnosticCode", rejected.rejectionCode(), "responsePrepared", rejected.response() != null));
                return rejected;
            }
            ProtocolEnvelopeDispatchResult stamped = stampResponse(result);
            TemporaryLifecycleDiagnostics.event("protocol_handler_settlement", handlerStarted,
                TemporaryLifecycleDiagnostics.with(identity, "outcome", stamped.status(),
                    "responsePrepared", stamped.response() != null,
                    "responseRevision", stamped.response() == null ? null : stamped.response().revision(),
                    "responseMutationId", stamped.response() == null ? null : stamped.response().mutationId()));
            return stamped;
        } catch (SecurityException exception) {
            TemporaryLifecycleDiagnostics.event("protocol_handler_settlement", started,
                TemporaryLifecycleDiagnostics.with(identity, "outcome", "rejected",
                    "diagnosticCode", ProtocolRejectionCode.AUTHORIZATION_DENIED,
                    "failure", exception.getClass().getSimpleName(), "responsePrepared", false));
            return reject(envelope, ProtocolRejectionCode.AUTHORIZATION_DENIED,
                "Protocol envelope operation is not authorized");
        } catch (RuntimeException exception) {
            TemporaryLifecycleDiagnostics.event("protocol_handler_settlement", started,
                TemporaryLifecycleDiagnostics.with(identity, "outcome", "failed",
                    "diagnosticCode", ProtocolRejectionCode.RESOURCE_OPERATION_FAILED,
                    "failure", exception.getClass().getSimpleName(), "responsePrepared", false));
            return reject(envelope, ProtocolRejectionCode.RESOURCE_OPERATION_FAILED,
                "Protocol envelope handler failed");
        }
    }

    public ProtocolEnvelopeDispatchResult rejectPayload(byte[] payload, ProtocolRejectionCode code, String message) {
        try {
            return reject(envelopeBoundary.decode(payload), code, message);
        } catch (RuntimeException exception) {
            return reject(code, message);
        }
    }

    public ProtocolEnvelope<Map<String, Object>> decode(byte[] payload) {
        return envelopeBoundary.decode(payload);
    }

    long currentAuthorityEpoch() {
        return currentEpoch();
    }

    private ProtocolEnvelopeDispatchResult reject(ProtocolRejectionCode code, String message) {
        return ProtocolEnvelopeDispatchResult.rejected(code, message);
    }

    private ProtocolEnvelopeDispatchResult reject(ProtocolEnvelope<Map<String, Object>> request,
                                                  ProtocolRejectionCode code, String message) {
        return correlateRejection(request, ProtocolEnvelopeDispatchResult.rejected(code, message));
    }

    private ProtocolEnvelopeDispatchResult correlateRejection(ProtocolEnvelope<Map<String, Object>> request,
                                                              ProtocolEnvelopeDispatchResult result) {
        if (request == null || result == null || result.handled() || result.response() != null) {
            return result;
        }
        ProtocolRejectionCode rejectionCode = Objects.requireNonNull(result.rejectionCode(), "Rejection code is required");
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("rejectionCode", rejectionCode.wireValue());
        values.put("rejectionMessage", result.message());
        values.put("requestId", request.requestId() == null ? "" : request.requestId().toString());
        values.put("correlationId", request.correlationId().toString());
        values.put("traceId", request.traceId().toString());
        values.put("operation", request.operation().canonicalText());
        if (request.resource() != null) {
            values.put("resource", request.resource().canonicalText());
        }
        if (request.mutationId() != null) {
            values.put("mutationId", request.mutationId().toString());
        }
        ProtocolEnvelope<Map<String, Object>> response = new ProtocolEnvelope<>(
            ProtocolEnvelope.Kind.RESPONSE,
            request.contractVersion(),
            UUID.randomUUID(),
            request.requestId(),
            request.correlationId(),
            request.traceId(),
            request.serverId(),
            request.resource(),
            0L,
            currentEpoch(),
            request.mutationId(),
            request.operation(),
            request.capabilities(),
            request.payloadType(),
            null,
            request.payloadHash(),
            false,
            null,
            null,
            null,
            null,
            null,
            request.sequence(),
            ProtocolEnvelope.Status.REJECTED,
            List.of(),
            Map.of("rejectionCode", rejectionCode.wireValue(), "rejectionMessage", result.message()),
            new ProtocolBody.ControlResponse("resource.rejection", values));
        return new ProtocolEnvelopeDispatchResult(result.status(), result.transportCode(), result.statusCode(),
            rejectionCode, result.message(), response);
    }

    private ProtocolEnvelopeDispatchResult stampResponse(ProtocolEnvelopeDispatchResult result) {
        ProtocolEnvelope<Map<String, Object>> response = result.response();
        if (response == null) {
            return result;
        }
        long currentEpoch = currentEpoch();
        ProtocolEnvelope<Map<String, Object>> stamped = new ProtocolEnvelope<>(
            response.kind(),
            response.contractVersion(),
            response.messageId(),
            response.requestId(),
            response.correlationId(),
            response.traceId(),
            response.serverId(),
            response.resource(),
            response.revision(),
            currentEpoch,
            response.mutationId(),
            response.operation(),
            response.capabilities(),
            response.payloadType(),
            response.canonicalPayload(),
            response.payloadHash(),
            response.deleted(),
            response.selectedVersion(),
            response.catalogChecksum(),
            response.bindingManifestHash(),
            response.editability(),
            response.fallbackReason(),
            response.sequence(),
            response.status(),
            response.diagnostics(),
            response.unknown(),
            response.body());
        return new ProtocolEnvelopeDispatchResult(result.status(), result.transportCode(), result.statusCode(),
            result.rejectionCode(), result.message(), stamped);
    }

    private long currentEpoch() {
        long current = authorityEpoch.current();
        if (current < 1L) {
            throw new IllegalStateException("Protocol envelope authority epoch is not bound");
        }
        return current;
    }

    private boolean requiresAuthorityEpoch(ProtocolEnvelope<Map<String, Object>> envelope) {
        if (!(envelope.body() instanceof ProtocolBody.ResourceRequest request)) {
            return false;
        }
        return true;
    }

    private void gate(Map<String, Object> identity, long started, String phase, boolean accepted,
                      ProtocolRejectionCode rejection) {
        TemporaryLifecycleDiagnostics.event("protocol_dispatch_gate", started,
            TemporaryLifecycleDiagnostics.with(identity, "phase", phase,
                "outcome", accepted ? "accepted" : "rejected", "diagnosticCode", rejection));
    }

    private Map<String, Object> diagnostic(ConnectionInfo connection,
                                           ProtocolEnvelope<Map<String, Object>> envelope) {
        return TemporaryLifecycleDiagnostics.with(TemporaryLifecycleDiagnostics.identity(
                envelope == null ? null : envelope.serverId(),
                envelope == null || envelope.resource() == null ? null : envelope.resource().canonicalText(),
                envelope == null || envelope.operation() == null ? null : envelope.operation().canonicalText(),
                envelope == null ? null : envelope.mutationId(), envelope == null ? null : envelope.requestId(),
                envelope == null ? null : envelope.correlationId(), envelope == null ? null : envelope.traceId(),
                envelope == null ? null : envelope.revision(), envelope == null ? null : envelope.authorityEpoch(), null),
            "connectionHash", TemporaryLifecycleDiagnostics.safeHash(
                connection == null ? null : connection.getConnectionId()));
    }
}
