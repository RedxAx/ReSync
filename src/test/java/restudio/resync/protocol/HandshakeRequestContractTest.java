package restudio.resync.protocol;

import org.junit.jupiter.api.Test;
import restudio.resync.protocol.messages.HandshakeRequest;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HandshakeRequestContractTest {
    @Test
    void roundTripsNegotiatedClientCapabilities() {
        HandshakeRequest source = new HandshakeRequest();
        source.setApiKey("key");
        source.setClientId("client");
        source.setProtocolVersion(2);
        source.setClientVersion("2.1.0");
        source.setCapabilitiesJson("[\"nodes\",\"resources\"]");
        source.setCollaborationProfileJson("{\"subjectId\":\"user-1\",\"displayName\":\"Alex\",\"avatar\":\"avatar.png\",\"source\":\"restudio\"}");

        HandshakeRequest decoded = new HandshakeRequest();
        decoded.deserialize(ByteBuffer.wrap(source.serialize()));

        assertEquals("key", decoded.getApiKey());
        assertEquals("client", decoded.getClientId());
        assertEquals(2, decoded.getProtocolVersion());
        assertEquals("2.1.0", decoded.getClientVersion());
        assertEquals("[\"nodes\",\"resources\"]", decoded.getCapabilitiesJson());
        assertEquals("{\"subjectId\":\"user-1\",\"displayName\":\"Alex\",\"avatar\":\"avatar.png\",\"source\":\"restudio\"}", decoded.getCollaborationProfileJson());
    }

    @Test
    void rejectsTruncatedHandshakeFields() {
        ByteBuffer malformed = ByteBuffer.allocate(8).putInt(64).putInt(1);
        malformed.flip();

        assertThrows(IllegalArgumentException.class, () -> new HandshakeRequest().deserialize(malformed));
    }
}
