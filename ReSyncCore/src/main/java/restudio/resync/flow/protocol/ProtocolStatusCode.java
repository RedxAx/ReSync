package restudio.resync.flow.protocol;

import java.util.Map;
import java.util.Objects;

public enum ProtocolStatusCode {
    OK("ok", "OK", 200, ProtocolTransportClass.SUCCESS, ProtocolRetryability.NEVER, ProtocolRecoveryAction.NONE,
        ProtocolEnvelope.Status.OK),
    ACCEPTED("accepted", "ACCEPTED", 202, ProtocolTransportClass.SUCCESS, ProtocolRetryability.NEVER, ProtocolRecoveryAction.NONE,
        ProtocolEnvelope.Status.ACCEPTED),
    REJECTED("rejected", "REJECTED", 400, ProtocolTransportClass.CLIENT_ERROR, ProtocolRetryability.NEVER,
        ProtocolRecoveryAction.FIX_REQUEST, ProtocolEnvelope.Status.REJECTED),
    CONFLICT("conflict", "CONFLICT", 409, ProtocolTransportClass.CONFLICT, ProtocolRetryability.AFTER_REFRESH,
        ProtocolRecoveryAction.REFRESH_RESOURCE, ProtocolEnvelope.Status.CONFLICT),
    NOT_FOUND("not_found", "NOT_FOUND", 404, ProtocolTransportClass.NOT_FOUND, ProtocolRetryability.NEVER,
        ProtocolRecoveryAction.REFRESH_RESOURCE, ProtocolEnvelope.Status.NOT_FOUND),
    READ_ONLY("read_only", "READ_ONLY", 403, ProtocolTransportClass.AUTHORIZATION, ProtocolRetryability.NEVER,
        ProtocolRecoveryAction.AUTHORIZE, ProtocolEnvelope.Status.READ_ONLY),
    UPGRADE_REQUIRED("upgrade_required", "UPGRADE_REQUIRED", 426, ProtocolTransportClass.CLIENT_ERROR,
        ProtocolRetryability.NEVER, ProtocolRecoveryAction.REFRESH_CAPABILITIES, ProtocolEnvelope.Status.UPGRADE_REQUIRED);

    private static final Map<String, ProtocolStatusCode> WIRE = Map.ofEntries(
        Map.entry("ok", OK),
        Map.entry("OK", OK),
        Map.entry("accepted", ACCEPTED),
        Map.entry("ACCEPTED", ACCEPTED),
        Map.entry("rejected", REJECTED),
        Map.entry("REJECTED", REJECTED),
        Map.entry("conflict", CONFLICT),
        Map.entry("CONFLICT", CONFLICT),
        Map.entry("not_found", NOT_FOUND),
        Map.entry("NOT_FOUND", NOT_FOUND),
        Map.entry("read_only", READ_ONLY),
        Map.entry("READ_ONLY", READ_ONLY),
        Map.entry("upgrade_required", UPGRADE_REQUIRED),
        Map.entry("UPGRADE_REQUIRED", UPGRADE_REQUIRED)
    );

    private final String wireValue;
    private final String legacyValue;
    private final int transportCode;
    private final ProtocolTransportClass transportClass;
    private final ProtocolRetryability retryability;
    private final ProtocolRecoveryAction recoveryAction;
    private final ProtocolEnvelope.Status envelopeStatus;

    ProtocolStatusCode(String wireValue, String legacyValue, int transportCode, ProtocolTransportClass transportClass,
                       ProtocolRetryability retryability, ProtocolRecoveryAction recoveryAction,
                       ProtocolEnvelope.Status envelopeStatus) {
        this.wireValue = wireValue;
        this.legacyValue = legacyValue;
        this.transportCode = transportCode;
        this.transportClass = transportClass;
        this.retryability = retryability;
        this.recoveryAction = recoveryAction;
        this.envelopeStatus = envelopeStatus;
    }

    public String wireValue() {
        return wireValue;
    }

    public String legacyValue() {
        return legacyValue;
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

    public ProtocolEnvelope.Status envelopeStatus() {
        return envelopeStatus;
    }

    public static ProtocolStatusCode fromWire(String value) {
        ProtocolStatusCode result = WIRE.get(value);
        if (result == null) {
            throw new IllegalArgumentException("Unknown protocol status code: " + value);
        }
        return result;
    }

    public static ProtocolStatusCode fromEnvelopeStatus(ProtocolEnvelope.Status status) {
        Objects.requireNonNull(status, "Status is required");
        for (ProtocolStatusCode value : values()) {
            if (value.envelopeStatus == status) {
                return value;
            }
        }
        throw new IllegalArgumentException("Unsupported protocol envelope status: " + status);
    }
}
