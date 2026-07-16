package restudio.resync.network;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NetworkRouteSetCodecTest {
    @Test
    void roundTripsARevisionedRouteSet() {
        NetworkRouteSet expected = new NetworkRouteSet(42, "lobby", List.of(new NetworkRoute("lobby-node", "lobby", "127.0.0.1", 25566), new NetworkRoute("survival-node", "survival", "10.0.0.8", 25567)), List.of(new NetworkRoutingGroup("fallback", "Fallback", NetworkRoutingStrategy.WEIGHTED, List.of("lobby-node", "survival-node"), Map.of("lobby-node", 2, "survival-node", 5), "", Set.of("play.example.com"), "network.join")));

        assertEquals(expected, NetworkRouteSetCodec.decode(NetworkRouteSetCodec.encode(expected)));
    }

    @Test
    void rejectsDuplicateRoutesAndTrailingData() {
        assertThrows(IllegalArgumentException.class, () -> new NetworkRouteSet(1, "lobby", List.of(new NetworkRoute("one", "lobby", "127.0.0.1", 25566), new NetworkRoute("two", "lobby", "127.0.0.1", 25567))));
        assertThrows(IllegalArgumentException.class, () -> new NetworkRouteSet(1, "missing", List.of(new NetworkRoute("one", "lobby", "127.0.0.1", 25566))));
        byte[] encoded = NetworkRouteSetCodec.encode(new NetworkRouteSet(1, "lobby", List.of(new NetworkRoute("one", "lobby", "127.0.0.1", 25566))));

        assertThrows(IllegalArgumentException.class, () -> NetworkRouteSetCodec.decode(Arrays.copyOf(encoded, encoded.length + 1)));
    }
}
