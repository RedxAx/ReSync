package restudio.resync.network;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NetworkOwnershipCodecTest {
    @Test
    void roundTripsPlayerOwnership() {
        PlayerLease lease = new PlayerLease("network", UUID.randomUUID(), "survival", "target", 4, 5000, 1000);
        assertEquals(lease, NetworkOwnershipCodec.decode(NetworkOwnershipCodec.encode(lease)));
    }

    @Test
    void rejectsTrailingData() {
        byte[] encoded = NetworkOwnershipCodec.encode(new PlayerLease("network", UUID.randomUUID(), "survival", "", 1, 0, 1000));
        assertThrows(IllegalArgumentException.class, () -> NetworkOwnershipCodec.decode(Arrays.copyOf(encoded, encoded.length + 1)));
    }
}
