package restudio.resync.protocol;

import org.junit.jupiter.api.Test;
import restudio.resync.compression.CompressionPool;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.protocol.CanonicalPayload;
import restudio.resync.flow.protocol.ProtocolBody;
import restudio.resync.flow.protocol.ProtocolEnvelope;
import restudio.resync.flow.protocol.ResourceSaveRequest;
import restudio.resync.flow.resource.ResourcePayloadCodec;
import restudio.resync.flow.resource.ResourcePayloadCodecs;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.OperationId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.protocol.messages.DataMessage;
import restudio.resync.protocol.messages.Message;
import restudio.resync.protocol.messages.ProtocolEnvelopeMessage;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProtocolEnvelopeWireTest {
    private static final UUID SERVER_UUID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID MUTATION_UUID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID MESSAGE_UUID = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID REQUEST_UUID = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID CORRELATION_UUID = UUID.fromString("55555555-5555-4555-8555-555555555555");

    @Test
    void carriesTypedEnvelopeWithoutChangingLegacyDataFrames() {
        ResourcePayloadCodec<Map<String, Object>> payloadCodec = ResourcePayloadCodecs.json();
        ServerResourceLocator resource = resource("flow-a");
        CanonicalPayload<Map<String, Object>> payload = payloadCodec.canonicalize(Map.of("name", "draft"));
        ResourceSaveRequest<Map<String, Object>> save = new ResourceSaveRequest<>(resource, 4, payload, MUTATION_UUID);
        ProtocolEnvelope<Map<String, Object>> envelope = envelope(resource, payload.checksum(), save);
        ProtocolEnvelopeBoundary boundary = new ProtocolEnvelopeBoundary(payloadCodec);
        ProtocolEnvelopeMessage message = new ProtocolEnvelopeMessage();
        message.setPayload(boundary.encode(envelope));

        CompressionPool compressionPool = new CompressionPool(6, 1);
        try {
            Codec codec = new Codec(compressionPool);
            byte[] frame = codec.encodeFrame(message, 0, false);
            Message decoded = codec.decodePayload(codec.decodeFrame(frame));
            ProtocolEnvelopeMessage decodedMessage = assertInstanceOf(ProtocolEnvelopeMessage.class, decoded);
            ProtocolEnvelope<Map<String, Object>> decodedEnvelope = boundary.decode(decodedMessage.getPayload());

            assertArrayEquals(message.getPayload(), decodedMessage.getPayload());
            assertEquals(envelope.resource(), decodedEnvelope.resource());
            assertEquals(envelope.operation(), decodedEnvelope.operation());
            assertEquals(envelope.payload(), decodedEnvelope.payload());

            DataMessage legacy = new DataMessage();
            legacy.setPayload(new byte[]{ReSyncProtocolContract.FLOW_PACKET_REQUEST, 1, 2, 3});
            Message decodedLegacy = codec.decodePayload(codec.decodeFrame(codec.encodeFrame(legacy, 1, false)));
            assertArrayEquals(legacy.getPayload(), assertInstanceOf(DataMessage.class, decodedLegacy).getPayload());
        } finally {
            compressionPool.close();
        }
    }

    @Test
    void rejectsMalformedEnvelopeAtBoundary() {
        ProtocolEnvelopeBoundary boundary = new ProtocolEnvelopeBoundary();
        assertThrows(IllegalArgumentException.class, () -> boundary.decode("{\"kind\":\"request\"}".getBytes(StandardCharsets.UTF_8)));
    }

    private static ProtocolEnvelope<Map<String, Object>> envelope(ServerResourceLocator resource,
                                                                   restudio.resync.flow.identity.ContentHash hash,
                                                                   ResourceSaveRequest<Map<String, Object>> save) {
        return new ProtocolEnvelope<>(
            ProtocolEnvelope.Kind.REQUEST,
            new CatalogVersion(1, 0),
            MESSAGE_UUID,
            REQUEST_UUID,
            CORRELATION_UUID,
            CORRELATION_UUID,
            new ServerId(SERVER_UUID),
            resource,
            4,
            MUTATION_UUID,
            ContractRef.of(new OwnerId("restudio.resync"), OperationId.of("resource.save")),
            Set.of(),
            ContractRef.of(new OwnerId("restudio.resync"), ResourceTypeId.of("resource.document")),
            null,
            hash,
            false,
            null,
            null,
            null,
            null,
            null,
            0,
            ProtocolEnvelope.Status.ACCEPTED,
            List.of(),
            Map.of(),
            new ProtocolBody.ResourceRequest(save, Map.of())
        );
    }

    private static ServerResourceLocator resource(String id) {
        return new ServerResourceLocator(new ServerId(SERVER_UUID),
            ContractRef.of(new OwnerId("restudio.resync"), ResourceTypeId.of("flow")), id);
    }
}
