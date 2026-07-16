package restudio.resync.network;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkRouteSelectorTest {
    private static final UUID PLAYER_ID = UUID.fromString("b1943eb3-4d47-44f4-90bc-d32323f25a1a");

    @Test
    void selectsTheFirstAvailableOrderedRoute() {
        NetworkRoutingGroup group = group(NetworkRoutingStrategy.ORDERED, Map.of());
        List<NetworkRoutingCandidate> candidates = List.of(new NetworkRoutingCandidate("one", "one", 0, 100, false), new NetworkRoutingCandidate("two", "two", 20, 100, true), new NetworkRoutingCandidate("three", "three", 2, 100, true));

        assertEquals("two", NetworkRouteSelector.select(PLAYER_ID, group, candidates).orElseThrow().nodeId());
    }

    @Test
    void selectsTheLeastPopulatedAvailableRoute() {
        NetworkRoutingGroup group = group(NetworkRoutingStrategy.LEAST_PLAYERS, Map.of());
        List<NetworkRoutingCandidate> candidates = List.of(new NetworkRoutingCandidate("one", "one", 12, 100, true), new NetworkRoutingCandidate("two", "two", 3, 20, true), new NetworkRoutingCandidate("three", "three", 3, 100, true));

        assertEquals("three", NetworkRouteSelector.select(PLAYER_ID, group, candidates).orElseThrow().nodeId());
    }

    @Test
    void weightedSelectionIsStableForAPlayer() {
        NetworkRoutingGroup group = group(NetworkRoutingStrategy.WEIGHTED, Map.of("one", 1, "two", 20, "three", 1));
        List<NetworkRoutingCandidate> candidates = List.of(new NetworkRoutingCandidate("one", "one", 0, 100, true), new NetworkRoutingCandidate("two", "two", 0, 100, true), new NetworkRoutingCandidate("three", "three", 0, 100, true));

        String first = NetworkRouteSelector.select(PLAYER_ID, group, candidates).orElseThrow().nodeId();
        assertEquals(first, NetworkRouteSelector.select(PLAYER_ID, group, candidates).orElseThrow().nodeId());
    }

    @Test
    void weightedSelectionHonorsRelativeCapacity() {
        NetworkRoutingGroup group = group(NetworkRoutingStrategy.WEIGHTED, Map.of("one", 1, "two", 20, "three", 1));
        List<NetworkRoutingCandidate> candidates = List.of(new NetworkRoutingCandidate("one", "one", 0, 100, true), new NetworkRoutingCandidate("two", "two", 0, 100, true), new NetworkRoutingCandidate("three", "three", 0, 100, true));
        long selected = IntStream.range(0, 1000).mapToObj(index -> UUID.nameUUIDFromBytes(("player-" + index).getBytes(StandardCharsets.UTF_8))).map(playerId -> NetworkRouteSelector.select(playerId, group, candidates).orElseThrow().nodeId()).filter("two"::equals).count();

        assertTrue(selected > 800);
    }

    private NetworkRoutingGroup group(NetworkRoutingStrategy strategy, Map<String, Integer> weights) {
        return new NetworkRoutingGroup("fallback", "Fallback", strategy, List.of("one", "two", "three"), weights, "", Set.of(), "");
    }
}
