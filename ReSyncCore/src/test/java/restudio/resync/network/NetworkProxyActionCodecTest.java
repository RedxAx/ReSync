package restudio.resync.network;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NetworkProxyActionCodecTest {
    @Test
    void roundTripsCommandsAndBroadcasts() {
        assertEquals(new NetworkProxyAction(NetworkProxyActionType.COMMAND, "velocity version"), NetworkProxyActionCodec.decode(NetworkProxyActionCodec.encode(new NetworkProxyAction(NetworkProxyActionType.COMMAND, "/velocity version"))));
        NetworkProxyAction broadcast = new NetworkProxyAction(NetworkProxyActionType.BROADCAST, "Restart In Five Minutes");
        assertEquals(broadcast, NetworkProxyActionCodec.decode(NetworkProxyActionCodec.encode(broadcast)));
    }

    @Test
    void rejectsTrailingDataAndEmptyActions() {
        byte[] encoded = NetworkProxyActionCodec.encode(new NetworkProxyAction(NetworkProxyActionType.COMMAND, "velocity version"));
        assertThrows(IllegalArgumentException.class, () -> NetworkProxyActionCodec.decode(Arrays.copyOf(encoded, encoded.length + 1)));
        assertThrows(IllegalArgumentException.class, () -> new NetworkProxyAction(NetworkProxyActionType.BROADCAST, ""));
    }
}
