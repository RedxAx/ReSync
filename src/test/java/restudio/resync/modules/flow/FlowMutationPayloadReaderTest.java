package restudio.resync.modules.flow;

import org.junit.jupiter.api.Test;
import restudio.resync.core.ConnectionInfo;
import restudio.resync.core.Session;
import restudio.resync.server.AuthorityEpoch;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowMutationPayloadReaderTest {
    @Test
    void stripsTransportEpochBeforeThePayloadReachesPersistence() {
        FlowMutationPayload payload = FlowMutationPayloadReader.read(packet("{\"id\":\"main\",\"authorityEpoch\":4}"));

        assertTrue(payload.hasAuthorityEpoch());
        assertTrue(payload.authorityEpochValid());
        assertEquals(4L, payload.authorityEpoch());
        assertEquals("{\"id\":\"main\"}", payload.payload());
    }

    @Test
    void extractsTheBoundedRequestIdFromTheRawTriggerEnvelope() {
        FlowMutationPayload payload = FlowMutationPayloadReader.read(packet(
            "{\"formatVersion\":1,\"authorityEpoch\":4,\"requestId\":\"trigger-request\",\"bindings\":[]}"));

        assertEquals("trigger-request", payload.requestId());
        assertTrue(payload.hasAuthorityEpoch());
        assertEquals(4L, payload.authorityEpoch());
        assertEquals("{\"formatVersion\":1,\"requestId\":\"trigger-request\",\"bindings\":[]}", payload.payload());
    }

    @Test
    void acceptsMatchingTransportAndPayloadRequestIds() {
        String envelope = "{\"formatVersion\":1,\"authorityEpoch\":4,\"requestId\":\"trigger-request\",\"bindings\":[]}";

        FlowMutationPayload payload = FlowMutationPayloadReader.read(packet("trigger-request", envelope));

        assertEquals("trigger-request", payload.requestId());
        assertEquals("{\"formatVersion\":1,\"requestId\":\"trigger-request\",\"bindings\":[]}", payload.payload());
    }

    @Test
    void rejectsMismatchedTransportAndPayloadRequestIds() {
        String envelope = "{\"formatVersion\":1,\"authorityEpoch\":4,\"requestId\":\"payload-request\",\"bindings\":[]}";

        FlowMutationPayload payload = FlowMutationPayloadReader.read(packet("transport-request", envelope));

        assertNull(payload.requestId());
        assertTrue(payload.hasAuthorityEpoch());
        assertTrue(payload.authorityEpochValid());
        assertEquals(4L, payload.authorityEpoch());
        assertEquals("{\"formatVersion\":1,\"requestId\":\"payload-request\",\"bindings\":[]}", payload.payload());
    }

    @Test
    void rejectsAnUnboundedRawTriggerRequestId() {
        String requestId = "x".repeat(257);
        String envelope = "{\"formatVersion\":1,\"authorityEpoch\":4,\"requestId\":\"" + requestId
            + "\",\"bindings\":[]}";

        FlowMutationPayload payload = FlowMutationPayloadReader.read(packet(envelope));

        assertNull(payload.requestId());
        assertTrue(payload.authorityEpochValid());
        assertEquals(4L, payload.authorityEpoch());
    }

    @Test
    void legacyPayloadRequiresAnAdvertisedCompatibilityCapability() {
        AuthorityEpoch authorityEpoch = AuthorityEpoch.fixed(4L);
        FlowMutationPayload payload = FlowMutationPayloadReader.read(packet("{\"id\":\"main\"}"));

        assertFalse(FlowMutationPayloadReader.legacyCompatible(null));
        assertFalse(FlowMutationPayloadReader.legacyCompatible(new Session("session", "client", null)));
        assertFalse(FlowMutationPayloadReader.validateAuthorityEpoch(payload, authorityEpoch, false).accepted());

        Session legacySession = sessionWith(FlowMutationPayloadReader.LEGACY_COMPATIBILITY_CAPABILITY);
        assertTrue(FlowMutationPayloadReader.legacyCompatible(legacySession));
        assertTrue(FlowMutationPayloadReader.validateAuthorityEpoch(payload, authorityEpoch,
            FlowMutationPayloadReader.legacyCompatible(legacySession)).accepted());
    }

    @Test
    void zeroEpochFollowsTheSameExplicitCompatibilityPolicy() {
        AuthorityEpoch authorityEpoch = AuthorityEpoch.fixed(4L);
        FlowMutationPayload payload = FlowMutationPayloadReader.read(packet("{\"id\":\"main\",\"authorityEpoch\":0}"));

        assertFalse(FlowMutationPayloadReader.validateAuthorityEpoch(payload, authorityEpoch, false).accepted());
        assertTrue(FlowMutationPayloadReader.validateAuthorityEpoch(payload, authorityEpoch, true).accepted());
    }

    @Test
    void malformedPayloadIsNotDowngradedToLegacy() {
        AuthorityEpoch authorityEpoch = AuthorityEpoch.fixed(4L);
        FlowMutationPayload payload = FlowMutationPayloadReader.read(packet("{\"id\":\"main\""));

        assertFalse(payload.authorityEpochValid());
        assertFalse(FlowMutationPayloadReader.validateAuthorityEpoch(payload, authorityEpoch, true).accepted());
    }

    private static ByteBuffer packet(String payload) {
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(bytes.length);
        buffer.put(bytes).flip();
        return buffer;
    }

    private static ByteBuffer packet(String requestId, String payload) {
        byte[] requestBytes = requestId.getBytes(StandardCharsets.UTF_8);
        byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(Integer.BYTES + requestBytes.length + payloadBytes.length);
        buffer.putInt(requestBytes.length).put(requestBytes).put(payloadBytes).flip();
        return buffer;
    }

    private static Session sessionWith(String capability) {
        ConnectionInfo connection = new ConnectionInfo(null, 1);
        connection.setClientCapabilities(Set.of(capability));
        return new Session("session", "client", connection);
    }
}
