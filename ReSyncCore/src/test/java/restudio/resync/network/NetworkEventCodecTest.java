package restudio.resync.network;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NetworkEventCodecTest {
    @Test
    void roundTripsPublishDeliveryAndAcknowledgement() {
        byte[] payload = "{\"wave\":3}".getBytes(StandardCharsets.UTF_8);
        NetworkEventPublish publish = new NetworkEventPublish("event-one", "flows", "wave.started", payload, 10000, 20000);
        NetworkEvent event = new NetworkEvent("event-one", "network-one", "flows", "wave.started", payload, "lobby-one", 10000, 20000);

        assertEquals(publish, NetworkEventCodec.decodePublish(NetworkEventCodec.encodePublish(publish)));
        assertEquals(event, NetworkEventCodec.decodeEvent(NetworkEventCodec.encodeEvent(event)));
        assertEquals("event-one", NetworkEventCodec.decodeAcknowledgement(NetworkEventCodec.encodeAcknowledgement("event-one")));
    }

    @Test
    void rejectsTrailingAndOversizedData() {
        byte[] acknowledgement = NetworkEventCodec.encodeAcknowledgement("event-one");
        assertThrows(IllegalArgumentException.class, () -> NetworkEventCodec.decodeAcknowledgement(Arrays.copyOf(acknowledgement, acknowledgement.length + 1)));
        assertThrows(IllegalArgumentException.class, () -> NetworkEventCodec.encodePublish(new NetworkEventPublish("event-one", "flows", "", new byte[524289], 10000, 20000)));
    }
}
