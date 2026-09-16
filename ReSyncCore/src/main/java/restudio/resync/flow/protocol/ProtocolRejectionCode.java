package restudio.resync.flow.protocol;

import java.util.HashMap;
import java.util.Map;

public enum ProtocolRejectionCode {
    INVALID_PAYLOAD("PROTO.INVALID_PAYLOAD", 400, ProtocolTransportClass.CLIENT_ERROR, ProtocolRetryability.NEVER,
        ProtocolRecoveryAction.FIX_REQUEST, "PROTO.INVALID_PAYLOAD"),
    INVALID_BODY("PROTO.INVALID_BODY", 400, ProtocolTransportClass.CLIENT_ERROR, ProtocolRetryability.NEVER,
        ProtocolRecoveryAction.FIX_REQUEST, "PROTO.INVALID_BODY"),
    INVALID_CURSOR("PROTO.INVALID_CURSOR", 400, ProtocolTransportClass.CLIENT_ERROR, ProtocolRetryability.NEVER,
        ProtocolRecoveryAction.FIX_REQUEST, "PROTO.INVALID_CURSOR"),
    AUTHENTICATION_REQUIRED("PROTO.AUTHENTICATION_REQUIRED", 401, ProtocolTransportClass.AUTHENTICATION,
        ProtocolRetryability.AFTER_AUTHENTICATION, ProtocolRecoveryAction.AUTHENTICATE, "RUNTIME.AUTHORIZATION_DENIED"),
    AUTHORIZATION_DENIED("RUNTIME.AUTHORIZATION_DENIED", 403, ProtocolTransportClass.AUTHORIZATION,
        ProtocolRetryability.NEVER, ProtocolRecoveryAction.AUTHORIZE, "RUNTIME.AUTHORIZATION_DENIED"),
    RESOURCE_AUTHORIZATION_DENIED("RESOURCE_AUTHORIZATION_DENIED", 403, ProtocolTransportClass.AUTHORIZATION,
        ProtocolRetryability.NEVER, ProtocolRecoveryAction.AUTHORIZE, "RESOURCE_AUTHORIZATION_DENIED"),
    RESOURCE_TYPE_UNAVAILABLE("RESOURCE_TYPE_UNAVAILABLE", 404, ProtocolTransportClass.NOT_FOUND,
        ProtocolRetryability.NEVER, ProtocolRecoveryAction.REFRESH_CAPABILITIES, "RESOURCE_TYPE_UNAVAILABLE"),
    RESOURCE_NOT_FOUND("RESOURCE_NOT_FOUND", 404, ProtocolTransportClass.NOT_FOUND, ProtocolRetryability.NEVER,
        ProtocolRecoveryAction.REFRESH_RESOURCE, "RESOURCE_NOT_FOUND"),
    UNSUPPORTED_OPERATION("PROTO.UNSUPPORTED_OPERATION", 405, ProtocolTransportClass.CLIENT_ERROR,
        ProtocolRetryability.NEVER, ProtocolRecoveryAction.FIX_REQUEST, "PROTO.UNSUPPORTED_OPERATION"),
    RESOURCE_OPERATION_UNSUPPORTED("RESOURCE_OPERATION_UNSUPPORTED", 405, ProtocolTransportClass.CLIENT_ERROR,
        ProtocolRetryability.NEVER, ProtocolRecoveryAction.REFRESH_CAPABILITIES, "RESOURCE_OPERATION_UNSUPPORTED"),
    UNSUPPORTED_QUERY("PROTO.UNSUPPORTED_QUERY", 405, ProtocolTransportClass.CLIENT_ERROR, ProtocolRetryability.NEVER,
        ProtocolRecoveryAction.REFRESH_CAPABILITIES, "PROTO.UNSUPPORTED_QUERY"),
    RESOURCE_ALREADY_EXISTS("RESOURCE_ALREADY_EXISTS", 409, ProtocolTransportClass.CONFLICT,
        ProtocolRetryability.AFTER_REFRESH, ProtocolRecoveryAction.REFRESH_RESOURCE, "RESOURCE_ALREADY_EXISTS"),
    RESOURCE_REVISION_CONFLICT("RESOURCE_REVISION_CONFLICT", 409, ProtocolTransportClass.CONFLICT,
        ProtocolRetryability.AFTER_REFRESH, ProtocolRecoveryAction.REFRESH_RESOURCE, "RESOURCE_REVISION_CONFLICT"),
    RESOURCE_IDEMPOTENCY_CONFLICT("RESOURCE_IDEMPOTENCY_CONFLICT", 409, ProtocolTransportClass.CONFLICT,
        ProtocolRetryability.NEVER, ProtocolRecoveryAction.FIX_REQUEST, "RESOURCE_IDEMPOTENCY_CONFLICT"),
    RESOURCE_MUTATION_ACTOR_CONFLICT("RESOURCE_MUTATION_ACTOR_CONFLICT", 409, ProtocolTransportClass.CONFLICT,
        ProtocolRetryability.NEVER, ProtocolRecoveryAction.AUTHORIZE, "RESOURCE_MUTATION_ACTOR_CONFLICT"),
    RESOURCE_OPERATION_FAILED("PROTO.RESOURCE_OPERATION_FAILED", 422, ProtocolTransportClass.CLIENT_ERROR,
        ProtocolRetryability.NEVER, ProtocolRecoveryAction.CONTACT_SERVER, "PROTO.RESOURCE_OPERATION_FAILED"),
    UNSUPPORTED_GENERATION("PROTO.UNSUPPORTED_GENERATION", 501, ProtocolTransportClass.SERVER_ERROR,
        ProtocolRetryability.AFTER_REFRESH, ProtocolRecoveryAction.REFRESH_CAPABILITIES, "PROTO.UNSUPPORTED_GENERATION"),
    RESOURCE_DURABILITY_UNAVAILABLE("RESOURCE_DURABILITY_UNAVAILABLE", 503, ProtocolTransportClass.UNAVAILABLE,
        ProtocolRetryability.LATER, ProtocolRecoveryAction.RETRY_LATER, "RESOURCE_DURABILITY_UNAVAILABLE"),
    RESOURCE_READ_UNAVAILABLE("RESOURCE_READ_UNAVAILABLE", 503, ProtocolTransportClass.UNAVAILABLE,
        ProtocolRetryability.LATER, ProtocolRecoveryAction.RETRY_LATER, "RESOURCE_READ_UNAVAILABLE"),
    RESOURCE_RECOVERY_BLOCKED("RESOURCE_RECOVERY_BLOCKED", 503, ProtocolTransportClass.UNAVAILABLE,
        ProtocolRetryability.LATER, ProtocolRecoveryAction.RETRY_LATER, "RESOURCE_RECOVERY_BLOCKED"),
    RESOURCE_AUTHORITY_CLOSED("RESOURCE_AUTHORITY_CLOSED", 503, ProtocolTransportClass.UNAVAILABLE,
        ProtocolRetryability.LATER, ProtocolRecoveryAction.RETRY_LATER, "RESOURCE_AUTHORITY_CLOSED"),
    RESOURCE_MUTATION_QUIESCED("RESOURCE_MUTATION_QUIESCED", 503, ProtocolTransportClass.UNAVAILABLE,
        ProtocolRetryability.LATER, ProtocolRecoveryAction.RETRY_LATER, "RESOURCE_MUTATION_QUIESCED"),
    RESOURCE_MUTATION_PENDING("RESOURCE_MUTATION_PENDING", 503, ProtocolTransportClass.UNAVAILABLE,
        ProtocolRetryability.LATER, ProtocolRecoveryAction.RETRY_LATER, "RESOURCE_MUTATION_PENDING"),
    RESOURCE_MUTATION_FAILED("RESOURCE_MUTATION_FAILED", 503, ProtocolTransportClass.UNAVAILABLE,
        ProtocolRetryability.LATER, ProtocolRecoveryAction.RETRY_LATER, "RESOURCE_MUTATION_FAILED"),
    UNKNOWN_STATUS("PROTO.UNKNOWN_STATUS", 500, ProtocolTransportClass.SERVER_ERROR, ProtocolRetryability.NEVER,
        ProtocolRecoveryAction.CONTACT_SERVER, "PROTO.UNKNOWN_STATUS");

    private static final Map<String, ProtocolRejectionCode> WIRE = createWireMap();

    private final String wireValue;
    private final int transportCode;
    private final ProtocolTransportClass transportClass;
    private final ProtocolRetryability retryability;
    private final ProtocolRecoveryAction recoveryAction;
    private final String legacyValue;

    ProtocolRejectionCode(String wireValue, int transportCode, ProtocolTransportClass transportClass,
                          ProtocolRetryability retryability, ProtocolRecoveryAction recoveryAction, String legacyValue) {
        this.wireValue = wireValue;
        this.transportCode = transportCode;
        this.transportClass = transportClass;
        this.retryability = retryability;
        this.recoveryAction = recoveryAction;
        this.legacyValue = legacyValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public int transportCode() {
        return transportCode;
    }

    public ProtocolTransportClass transportClass() {
        return transportClass;
    }

    public ProtocolRetryability retryability() {
        return retryability;
    }

    public ProtocolRecoveryAction recoveryAction() {
        return recoveryAction;
    }

    public String legacyValue() {
        return legacyValue;
    }

    public static ProtocolRejectionCode fromWire(String value) {
        ProtocolRejectionCode result = WIRE.get(value);
        if (result == null) {
            throw new IllegalArgumentException("Unknown protocol rejection code: " + value);
        }
        return result;
    }

    public static ProtocolRejectionCode fromLegacy(String value, int transportCode) {
        if (value == null || value.isBlank()) {
            return UNKNOWN_STATUS;
        }
        ProtocolRejectionCode result = WIRE.get(value);
        if (result == null) {
            return UNKNOWN_STATUS;
        }
        if ("RUNTIME.AUTHORIZATION_DENIED".equals(value) && transportCode == AUTHENTICATION_REQUIRED.transportCode) {
            return AUTHENTICATION_REQUIRED;
        }
        return result;
    }

    public static ProtocolRejectionCode fromResourceError(String value) {
        if (value == null || value.isBlank()) {
            return RESOURCE_OPERATION_FAILED;
        }
        return WIRE.getOrDefault(value, RESOURCE_OPERATION_FAILED);
    }

    private static Map<String, ProtocolRejectionCode> createWireMap() {
        Map<String, ProtocolRejectionCode> values = new HashMap<>();
        for (ProtocolRejectionCode code : values()) {
            values.put(code.wireValue, code);
        }
        values.put("RUNTIME.AUTHORIZATION_DENIED", AUTHORIZATION_DENIED);
        values.put("PROTO.INVALID_PAYLOAD", INVALID_PAYLOAD);
        values.put("INVALID_PAYLOAD", INVALID_PAYLOAD);
        values.put("PROTO.INVALID_BODY", INVALID_BODY);
        values.put("PROTO.INVALID_CURSOR", INVALID_CURSOR);
        values.put("INVALID_BODY", INVALID_BODY);
        values.put("INVALID_CURSOR", INVALID_CURSOR);
        values.put("PROTO.UNSUPPORTED_OPERATION", UNSUPPORTED_OPERATION);
        values.put("PROTO.UNSUPPORTED_QUERY", UNSUPPORTED_QUERY);
        values.put("PROTO.RESOURCE_OPERATION_FAILED", RESOURCE_OPERATION_FAILED);
        values.put("UNSUPPORTED_OPERATION", UNSUPPORTED_OPERATION);
        values.put("UNSUPPORTED_QUERY", UNSUPPORTED_QUERY);
        values.put("RESOURCE_OPERATION_FAILED", RESOURCE_OPERATION_FAILED);
        values.put("PROTO.UNSUPPORTED_GENERATION", UNSUPPORTED_GENERATION);
        values.put("UNSUPPORTED_GENERATION", UNSUPPORTED_GENERATION);
        values.put("RESOURCE_OPERATION_UNSUPPORTED", RESOURCE_OPERATION_UNSUPPORTED);
        values.put("RESOURCE_DURABILITY_UNAVAILABLE", RESOURCE_DURABILITY_UNAVAILABLE);
        values.put("RESOURCE_READ_UNAVAILABLE", RESOURCE_READ_UNAVAILABLE);
        values.put("RESOURCE_TYPE_UNAVAILABLE", RESOURCE_TYPE_UNAVAILABLE);
        values.put("RESOURCE_NOT_FOUND", RESOURCE_NOT_FOUND);
        values.put("RESOURCE_AUTHORIZATION_DENIED", RESOURCE_AUTHORIZATION_DENIED);
        values.put("RESOURCE_ALREADY_EXISTS", RESOURCE_ALREADY_EXISTS);
        values.put("RESOURCE_REVISION_CONFLICT", RESOURCE_REVISION_CONFLICT);
        values.put("RESOURCE_IDEMPOTENCY_CONFLICT", RESOURCE_IDEMPOTENCY_CONFLICT);
        values.put("RESOURCE_MUTATION_ACTOR_CONFLICT", RESOURCE_MUTATION_ACTOR_CONFLICT);
        return Map.copyOf(values);
    }
}
