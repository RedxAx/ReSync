package restudio.resync.flow.protocol;

import org.junit.jupiter.api.Test;
import restudio.resync.contract.canonical.JsonValue;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.OperationId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.resource.ResourcePayloadCodecs;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProtocolEnvelopeStatusCodecTest {
    @Test
    void encodesTypedStatusAndReadsWiresWithoutTheNewField() {
        ProtocolEnvelopeCodec<Map<String, Object>> codec = new ProtocolEnvelopeCodec<>(ResourcePayloadCodecs.json());
        ProtocolEnvelope<Map<String, Object>> envelope = envelope(ProtocolEnvelope.Status.ACCEPTED);

        JsonValue.JsonObject encoded = codec.encode(envelope);
        assertEquals("accepted", encoded.value("statusCode").toJava());
        assertEquals(envelope, codec.decode(encoded));

        Map<String, Object> oldWire = javaMap(encoded);
        oldWire.remove("statusCode");
        assertEquals(envelope, codec.decode(JsonValue.fromJava(oldWire)));
    }

    @Test
    void rejectsUnknownOrMismatchedTypedStatus() {
        ProtocolEnvelopeCodec<Map<String, Object>> codec = new ProtocolEnvelopeCodec<>(ResourcePayloadCodecs.json());
        Map<String, Object> encoded = javaMap(codec.encode(envelope(ProtocolEnvelope.Status.OK)));

        Map<String, Object> unknown = new LinkedHashMap<>(encoded);
        unknown.put("statusCode", "future.status");
        assertThrows(IllegalArgumentException.class, () -> codec.decode(JsonValue.fromJava(unknown)));

        Map<String, Object> mismatched = new LinkedHashMap<>(encoded);
        mismatched.put("statusCode", "rejected");
        assertThrows(IllegalArgumentException.class, () -> codec.decode(JsonValue.fromJava(mismatched)));
    }

    private static ProtocolEnvelope<Map<String, Object>> envelope(ProtocolEnvelope.Status status) {
        return new ProtocolEnvelope<>(
            ProtocolEnvelope.Kind.REQUEST,
            new CatalogVersion(1, 0),
            UUID.fromString("11111111-1111-4111-8111-111111111111"),
            UUID.fromString("22222222-2222-4222-8222-222222222222"),
            UUID.fromString("33333333-3333-4333-8333-333333333333"),
            UUID.fromString("44444444-4444-4444-8444-444444444444"),
            new ServerId(UUID.fromString("55555555-5555-4555-8555-555555555555")),
            null,
            0,
            null,
            ContractRef.of(new OwnerId("restudio.resync"), OperationId.of("control.test")),
            Set.of(),
            ContractRef.of(new OwnerId("restudio.resync"), ResourceTypeId.of("control.request")),
            null,
            null,
            false,
            null,
            null,
            null,
            null,
            null,
            0,
            status,
            List.of(),
            Map.of(),
            new ProtocolBody.ControlRequest("test", Map.of("value", true))
        );
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> javaMap(JsonValue.JsonObject value) {
        return new LinkedHashMap<>((Map<String, Object>) value.toJava());
    }
}
