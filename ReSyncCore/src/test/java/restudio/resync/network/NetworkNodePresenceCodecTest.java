package restudio.resync.network;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NetworkNodePresenceCodecTest {
    @Test
    void roundTripsNodeIdentityStatusAndMetrics() {
        NetworkNodePresence presence = new NetworkNodePresence("network", "lobby", NetworkNodeStatus.DRAINING, 42, 100, 19.95, 24.5, 1024, 4096, 5000);

        assertEquals(presence, NetworkNodePresenceCodec.decode("network", NetworkNodePresenceCodec.encode(presence)));
    }

    @Test
    void rejectsTruncatedNodePresence() {
        assertThrows(IllegalArgumentException.class, () -> NetworkNodePresenceCodec.decode("network", new byte[4]));
    }
}
