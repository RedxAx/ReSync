package restudio.resync.network;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NetworkChatMessageCodecTest {
    @Test
    void roundTripsIdentityChannelAndComponents() {
        NetworkChatMessage message = new NetworkChatMessage(UUID.fromString("7d121302-d1a0-4bd5-8ce9-a0de26a9f26a"), "Redxa", "{\"text\":\"Redxa\",\"color\":\"gold\"}", "global", "{\"text\":\"Hello network\",\"color\":\"white\"}", 10000);

        assertEquals(message, NetworkChatMessageCodec.decode(NetworkChatMessageCodec.encode(message)));
    }

    @Test
    void rejectsTrailingAndOversizedData() {
        NetworkChatMessage message = new NetworkChatMessage(UUID.randomUUID(), "Redxa", "{\"text\":\"Redxa\"}", "global", "{\"text\":\"Hello\"}", 10000);
        byte[] encoded = NetworkChatMessageCodec.encode(message);

        assertThrows(IllegalArgumentException.class, () -> NetworkChatMessageCodec.decode(Arrays.copyOf(encoded, encoded.length + 1)));
        assertThrows(IllegalArgumentException.class, () -> NetworkChatMessageCodec.encode(new NetworkChatMessage(UUID.randomUUID(), "Redxa", "{\"text\":\"Redxa\"}", "global", "x".repeat(262145), 10000)));
    }
}
