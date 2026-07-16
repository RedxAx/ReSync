package restudio.resync.network;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NetworkPlayerLifecycleCodecTest {
    @Test
    void roundTripsPlayerLifecycleEvents() {
        NetworkPlayerLifecycle lifecycle = new NetworkPlayerLifecycle(NetworkPlayerLifecycleType.TRANSFER_COMPLETED, UUID.randomUUID(), "Player", "lobby", "survival", "", 10000);
        assertEquals(lifecycle, NetworkPlayerLifecycleCodec.decode(NetworkPlayerLifecycleCodec.encode(lifecycle)));
    }

    @Test
    void rejectsTrailingData() {
        byte[] encoded = NetworkPlayerLifecycleCodec.encode(new NetworkPlayerLifecycle(NetworkPlayerLifecycleType.JOINED, UUID.randomUUID(), "Player", "", "", "", 10000));
        assertThrows(IllegalArgumentException.class, () -> NetworkPlayerLifecycleCodec.decode(Arrays.copyOf(encoded, encoded.length + 1)));
    }
}
