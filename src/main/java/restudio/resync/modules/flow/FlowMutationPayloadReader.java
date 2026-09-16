package restudio.resync.modules.flow;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import restudio.resync.core.Session;
import restudio.resync.protocol.ReSyncProtocolContract;
import restudio.resync.server.AuthorityEpoch;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public final class FlowMutationPayloadReader {
    private static final int MAX_REQUEST_ID_BYTES = 256;
    public static final String AUTHORITY_EPOCH_REQUIRED_CODE = "RESOURCE_AUTHORITY_EPOCH_REQUIRED";
    public static final String AUTHORITY_EPOCH_INVALID_CODE = "RESOURCE_AUTHORITY_EPOCH_INVALID";
    public static final String AUTHORITY_EPOCH_STALE_CODE = "RESOURCE_AUTHORITY_EPOCH_STALE";
    public static final String AUTHORITY_EPOCH_FUTURE_CODE = "RESOURCE_AUTHORITY_EPOCH_FUTURE";
    public static final String AUTHORITY_EPOCH_UNAVAILABLE_CODE = "RESOURCE_AUTHORITY_EPOCH_UNAVAILABLE";
    public static final String LEGACY_COMPATIBILITY_CAPABILITY = "legacy_flow_mutation_epoch_compatibility";

    private FlowMutationPayloadReader() {
    }

    public static FlowMutationPayload read(ByteBuffer buffer) {
        if (buffer == null || !buffer.hasRemaining()) {
            return new FlowMutationPayload(null, "");
        }
        byte first = buffer.get(buffer.position());
        if (first == '{' || first == '[' || first == '"' || first == '-' || Character.isDigit((char) first)) {
            return withAuthorityEpoch(null, readRemaining(buffer));
        }
        if (buffer.remaining() < Integer.BYTES) {
            return withAuthorityEpoch(null, readRemaining(buffer));
        }
        int start = buffer.position();
        int requestIdLength = buffer.getInt();
        if (requestIdLength <= 0 || requestIdLength > MAX_REQUEST_ID_BYTES || requestIdLength > buffer.remaining()) {
            buffer.position(start);
            return withAuthorityEpoch(null, readRemaining(buffer));
        }
        byte[] requestBytes = new byte[requestIdLength];
        buffer.get(requestBytes);
        return withAuthorityEpoch(new String(requestBytes, StandardCharsets.UTF_8), readRemaining(buffer));
    }

    public static EpochDecision validateAuthorityEpoch(FlowMutationPayload payload, AuthorityEpoch authorityEpoch,
                                                       boolean legacyCompatible) {
        if (payload == null || !payload.authorityEpochValid()) {
            return EpochDecision.rejected(AUTHORITY_EPOCH_INVALID_CODE,
                "Resource authority epoch is invalid");
        }
        if (authorityEpoch == null) {
            return EpochDecision.rejected(AUTHORITY_EPOCH_UNAVAILABLE_CODE,
                "Resource authority epoch is unavailable");
        }
        long current;
        try {
            current = authorityEpoch.current();
        } catch (RuntimeException exception) {
            return EpochDecision.rejected(AUTHORITY_EPOCH_UNAVAILABLE_CODE,
                "Resource authority epoch is unavailable");
        }
        if (!payload.hasAuthorityEpoch() || payload.authorityEpoch() == 0L) {
            return legacyCompatible
                ? EpochDecision.accepted(current, payload.authorityEpoch())
                : EpochDecision.rejected(AUTHORITY_EPOCH_REQUIRED_CODE,
                    "The current resource authority epoch is required for this peer");
        }
        if (payload.authorityEpoch() < current) {
            return EpochDecision.rejected(AUTHORITY_EPOCH_STALE_CODE,
                "Resource authority epoch is stale; refresh before retrying");
        }
        if (payload.authorityEpoch() > current) {
            return EpochDecision.rejected(AUTHORITY_EPOCH_FUTURE_CODE,
                "Resource authority epoch is from a future authority; refresh before retrying");
        }
        return EpochDecision.accepted(current, payload.authorityEpoch());
    }

    public static boolean legacyCompatible(Session session) {
        if (session == null || session.getConnection() == null) {
            return false;
        }
        return session.getConnection().getClientCapabilities().contains(LEGACY_COMPATIBILITY_CAPABILITY);
    }

    public static String deleteResourceId(String payload) {
        if (payload == null) {
            return "";
        }
        String value = payload.strip();
        if (!value.startsWith("{")) {
            return value;
        }
        try {
            JsonElement parsed = JsonParser.parseString(value);
            if (!parsed.isJsonObject()) {
                return value;
            }
            JsonElement id = parsed.getAsJsonObject().get("id");
            return id != null && id.isJsonPrimitive() && id.getAsJsonPrimitive().isString()
                ? id.getAsString() : "";
        } catch (RuntimeException exception) {
            return "";
        }
    }

    private static FlowMutationPayload withAuthorityEpoch(String requestId, String payload) {
        if (payload == null || payload.isBlank()) {
            return new FlowMutationPayload(requestId, payload, 0L, false, true, 0L, false, null);
        }
        try {
            JsonElement parsed = JsonParser.parseString(payload);
            if (!parsed.isJsonObject()) {
                return new FlowMutationPayload(requestId, payload, 0L, false, true, 0L, false, null);
            }
            JsonObject object = parsed.getAsJsonObject();
            requestId = resolveRequestId(requestId, object);
            boolean authorityEpochPresent = object.has(ReSyncProtocolContract.FLOW_TRIGGER_UPDATE_AUTHORITY_EPOCH_FIELD);
            JsonElement value = object.get(ReSyncProtocolContract.FLOW_TRIGGER_UPDATE_AUTHORITY_EPOCH_FIELD);
            long epoch;
            if (!authorityEpochPresent) {
                epoch = 0L;
            } else {
                if (value == null || value.isJsonNull() || !value.isJsonPrimitive()
                    || !value.getAsJsonPrimitive().isNumber()) {
                    return new FlowMutationPayload(requestId, payload, -1L, true, false, 0L, false, null);
                }
                try {
                    epoch = Long.parseLong(value.getAsString());
                } catch (RuntimeException exception) {
                    return new FlowMutationPayload(requestId, payload, -1L, true, false, 0L, false, null);
                }
            }
            boolean expectedBindingEpochPresent = object.has(
                ReSyncProtocolContract.FLOW_TRIGGER_UPDATE_EXPECTED_BINDING_EPOCH_FIELD);
            JsonElement expectedBindingEpochValue = object.get(
                ReSyncProtocolContract.FLOW_TRIGGER_UPDATE_EXPECTED_BINDING_EPOCH_FIELD);
            long expectedBindingEpoch = 0L;
            if (expectedBindingEpochPresent) {
                if (expectedBindingEpochValue == null || expectedBindingEpochValue.isJsonNull()
                    || !expectedBindingEpochValue.isJsonPrimitive()
                    || !expectedBindingEpochValue.getAsJsonPrimitive().isNumber()) {
                    return new FlowMutationPayload(requestId, payload, epoch, authorityEpochPresent,
                        false, -1L, true, null);
                }
                try {
                    expectedBindingEpoch = Long.parseLong(expectedBindingEpochValue.getAsString());
                } catch (RuntimeException exception) {
                    return new FlowMutationPayload(requestId, payload, epoch, authorityEpochPresent,
                        false, -1L, true, null);
                }
            }
            String expectedBindingHash = null;
            JsonElement expectedBindingHashValue = object.get(
                ReSyncProtocolContract.FLOW_TRIGGER_UPDATE_EXPECTED_BINDING_HASH_FIELD);
            if (expectedBindingHashValue != null && expectedBindingHashValue.isJsonPrimitive()
                && expectedBindingHashValue.getAsJsonPrimitive().isString()) {
                expectedBindingHash = expectedBindingHashValue.getAsString();
            } else if (expectedBindingHashValue != null) {
                return new FlowMutationPayload(requestId, payload, epoch, authorityEpochPresent,
                    false, expectedBindingEpoch, expectedBindingEpochPresent, null);
            }
            if (expectedBindingEpochPresent != (expectedBindingHash != null && !expectedBindingHash.isBlank())) {
                return new FlowMutationPayload(requestId, payload, epoch, authorityEpochPresent,
                    authorityEpochPresent ? epoch >= 0L : true, expectedBindingEpoch,
                    expectedBindingEpochPresent, expectedBindingHash);
            }
            object.remove(ReSyncProtocolContract.FLOW_TRIGGER_UPDATE_AUTHORITY_EPOCH_FIELD);
            object.remove(ReSyncProtocolContract.FLOW_TRIGGER_UPDATE_EXPECTED_BINDING_EPOCH_FIELD);
            object.remove(ReSyncProtocolContract.FLOW_TRIGGER_UPDATE_EXPECTED_BINDING_HASH_FIELD);
            return new FlowMutationPayload(requestId, object.toString(), epoch, authorityEpochPresent,
                !authorityEpochPresent || epoch >= 0L, expectedBindingEpoch, expectedBindingEpochPresent,
                expectedBindingHash);
        } catch (RuntimeException exception) {
            return new FlowMutationPayload(requestId, payload, -1L, true, false, 0L, false, null);
        }
    }

    private static String resolveRequestId(String transportRequestId, JsonObject object) {
        if (!object.has(ReSyncProtocolContract.FLOW_TRIGGER_UPDATE_REQUEST_ID_FIELD)) {
            return transportRequestId;
        }
        JsonElement value = object.get(ReSyncProtocolContract.FLOW_TRIGGER_UPDATE_REQUEST_ID_FIELD);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            return null;
        }
        String payloadRequestId = value.getAsString();
        if (payloadRequestId.isBlank()
            || payloadRequestId.getBytes(StandardCharsets.UTF_8).length > MAX_REQUEST_ID_BYTES) {
            return null;
        }
        if (transportRequestId != null && !transportRequestId.equals(payloadRequestId)) {
            return null;
        }
        return payloadRequestId;
    }

    private static String readRemaining(ByteBuffer buffer) {
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    public record EpochDecision(boolean accepted, String code, String message, long currentEpoch, long incomingEpoch) {
        private static EpochDecision accepted(long currentEpoch, long incomingEpoch) {
            return new EpochDecision(true, "", "", currentEpoch, incomingEpoch);
        }

        private static EpochDecision rejected(String code, String message) {
            return new EpochDecision(false, code, message, -1L, -1L);
        }
    }
}
