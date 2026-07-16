package restudio.resync.network;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkPlayerRouteCodecTest {
    @Test
    void roundTripsRequestsAndResults() {
        NetworkPlayerRoute request = new NetworkPlayerRoute(UUID.randomUUID(), "survival-one");
        NetworkPlayerRouteResult result = new NetworkPlayerRouteResult(NetworkPlayerRouteStatus.ALREADY_CONNECTED, "survival-one");

        assertEquals(request, NetworkPlayerRouteCodec.decode(NetworkPlayerRouteCodec.encode(request)));
        assertEquals(result, NetworkPlayerRouteCodec.decodeResult(NetworkPlayerRouteCodec.encodeResult(result)));
        assertTrue(result.successful());
    }

    @Test
    void rejectsTrailingData() {
        byte[] encoded = NetworkPlayerRouteCodec.encode(new NetworkPlayerRoute(UUID.randomUUID(), "survival-one"));
        assertThrows(IllegalArgumentException.class, () -> NetworkPlayerRouteCodec.decode(Arrays.copyOf(encoded, encoded.length + 1)));
    }
}
