package restudio.resync.network;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NetworkPresenceCodecTest {
    @Test
    void roundTripsBoundedNodeMetrics() {
        NetworkNodeMetrics metrics = new NetworkNodeMetrics("network", "lobby", 42, 100, 19.95, 24.5, 1024, 4096, 5000);

        NetworkNodeMetrics decoded = NetworkPresenceCodec.decode(metrics.networkId(), metrics.nodeId(), NetworkPresenceCodec.encode(metrics));

        assertEquals(metrics, decoded);
    }

    @Test
    void rejectsUnexpectedPresencePayloadSizes() {
        assertThrows(IllegalArgumentException.class, () -> NetworkPresenceCodec.decode("network", "lobby", new byte[8]));
    }
}
