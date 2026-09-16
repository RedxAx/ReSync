package restudio.resync.server;

import restudio.resync.flow.protocol.ProtocolEnvelope;
import restudio.resync.flow.protocol.ProtocolRejectionCode;
import restudio.resync.flow.protocol.ProtocolStatusCode;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record ProtocolEnvelopeDispatchResult(Status status, int transportCode, ProtocolStatusCode statusCode,
                                             ProtocolRejectionCode rejectionCode, String message,
                                             ProtocolEnvelope<Map<String, Object>> response) {
    public ProtocolEnvelopeDispatchResult(Status status, int transportCode, String code, String message,
                                           ProtocolEnvelope<Map<String, Object>> response) {
        this(status, transportCode, status == Status.HANDLED ? ProtocolStatusCode.OK : ProtocolStatusCode.REJECTED,
            status == Status.HANDLED ? null : ProtocolRejectionCode.fromLegacy(code, transportCode), message, response);
    }

    public ProtocolEnvelopeDispatchResult(Status status, int transportCode, String code, String message) {
        this(status, transportCode, code, message, null);
    }

    public ProtocolEnvelopeDispatchResult {
        status = Objects.requireNonNull(status, "Status is required");
        statusCode = Objects.requireNonNull(statusCode, "Status code is required");
        if (transportCode < 100 || transportCode > 599) {
            throw new IllegalArgumentException("Transport code must be an HTTP status code");
        }
        message = requiredText(message, "Message", 512);
        if (status == Status.HANDLED) {
            if (rejectionCode != null || statusCode == ProtocolStatusCode.REJECTED
                || transportCode != statusCode.transportCode()) {
                throw new IllegalArgumentException("Handled dispatch results cannot carry a rejection");
            }
        } else if (statusCode != ProtocolStatusCode.REJECTED || rejectionCode == null
            || transportCode != rejectionCode.transportCode()) {
            throw new IllegalArgumentException("Rejected dispatch results require a typed rejection");
        }
    }

    public static ProtocolEnvelopeDispatchResult accepted() {
        return handled(null);
    }

    public static ProtocolEnvelopeDispatchResult handled(ProtocolEnvelope<Map<String, Object>> response) {
        return new ProtocolEnvelopeDispatchResult(Status.HANDLED, ProtocolStatusCode.OK.transportCode(),
            ProtocolStatusCode.OK, null, "Protocol envelope handled", response);
    }

    public static ProtocolEnvelopeDispatchResult rejected(int transportCode, String code, String message) {
        return rejected(ProtocolRejectionCode.fromLegacy(code, transportCode), message);
    }

    public static ProtocolEnvelopeDispatchResult rejected(ProtocolRejectionCode code, String message) {
        ProtocolRejectionCode rejection = Objects.requireNonNull(code, "Rejection code is required");
        String safeMessage = message == null || message.isBlank() ? rejection.wireValue() : message;
        return new ProtocolEnvelopeDispatchResult(Status.REJECTED, rejection.transportCode(), ProtocolStatusCode.REJECTED,
            rejection, safeMessage, null);
    }

    public boolean handled() {
        return status == Status.HANDLED;
    }

    public String code() {
        return rejectionCode == null ? statusCode.legacyValue() : rejectionCode.legacyValue();
    }

    public Map<String, Object> structured() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("status", status.name().toLowerCase());
        value.put("code", code());
        value.put("message", message);
        value.put("statusCode", statusCode.wireValue());
        value.put("transportCode", transportCode);
        if (rejectionCode != null) {
            value.put("rejectionCode", rejectionCode.wireValue());
            value.put("transportClass", rejectionCode.transportClass().wireValue());
            value.put("retryability", rejectionCode.retryability().wireValue());
            value.put("recoveryAction", rejectionCode.recoveryAction().wireValue());
        } else {
            value.put("transportClass", statusCode.transportClass().wireValue());
            value.put("retryability", statusCode.retryability().wireValue());
            value.put("recoveryAction", statusCode.recoveryAction().wireValue());
        }
        return Map.copyOf(value);
    }

    private static String requiredText(String value, String field, int limit) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isBlank() || normalized.length() > limit) {
            throw new IllegalArgumentException(field + " is empty or exceeds " + limit + " characters");
        }
        return normalized;
    }

    public enum Status {
        HANDLED,
        REJECTED
    }
}
