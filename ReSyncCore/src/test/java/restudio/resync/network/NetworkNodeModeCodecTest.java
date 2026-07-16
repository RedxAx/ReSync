package restudio.resync.network;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NetworkNodeModeCodecTest {
    @Test
    void roundTripsOperationalModes() {
        for (NetworkNodeStatus status : new NetworkNodeStatus[] {NetworkNodeStatus.ONLINE, NetworkNodeStatus.DRAINING, NetworkNodeStatus.MAINTENANCE}) {
            NetworkNodeMode expected = new NetworkNodeMode("lobby-node", status);
            assertEquals(expected, NetworkNodeModeCodec.decode(NetworkNodeModeCodec.encode(expected)));
        }
    }

    @Test
    void rejectsTerminalStatusAndTrailingData() {
        assertThrows(IllegalArgumentException.class, () -> new NetworkNodeMode("lobby-node", NetworkNodeStatus.OFFLINE));
        byte[] encoded = NetworkNodeModeCodec.encode(new NetworkNodeMode("lobby-node", NetworkNodeStatus.DRAINING));
        assertThrows(IllegalArgumentException.class, () -> NetworkNodeModeCodec.decode(Arrays.copyOf(encoded, encoded.length + 1)));
    }
}
