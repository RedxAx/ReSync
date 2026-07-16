package restudio.resync.velocity;

import org.junit.jupiter.api.Test;
import restudio.resync.network.NetworkNodeMetrics;
import restudio.resync.network.NetworkNodeStatus;
import restudio.resync.network.NetworkRoute;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VelocityRouteRegistryTest {
    @Test
    void routeAvailabilityTracksNodeConnectionAndMode() {
        AtomicBoolean connected = new AtomicBoolean(true);
        NetworkNodeStatus[] status = {NetworkNodeStatus.ONLINE};
        NetworkRoute route = new NetworkRoute("node", "lobby", "127.0.0.1", 25565);
        VelocityRouteRegistry registry = new VelocityRouteRegistry(null, nodeState(connected, status), Map.of(route.routeName(), route), "");

        assertTrue(registry.accepts("lobby"));
        connected.set(false);
        assertFalse(registry.accepts("lobby"));
        connected.set(true);
        status[0] = NetworkNodeStatus.MAINTENANCE;
        assertFalse(registry.accepts("lobby"));
    }

    @Test
    void normalizesLookupAndRetainsUnknownRouteCompatibility() {
        NetworkRoute route = new NetworkRoute("node", "lobby", "127.0.0.1", 25565);
        VelocityRouteRegistry registry = new VelocityRouteRegistry(null, nodeState(new AtomicBoolean(true), new NetworkNodeStatus[]{NetworkNodeStatus.ONLINE}), Map.of(route.routeName(), route), "");

        assertSame(route, registry.route("LOBBY"));
        assertTrue(registry.containsNode("node"));
        assertTrue(registry.accepts("external"));
    }

    private VelocityRouteRegistry.NodeState nodeState(AtomicBoolean connected, NetworkNodeStatus[] status) {
        return new VelocityRouteRegistry.NodeState() {
            @Override
            public boolean managed(String nodeId) {
                return true;
            }

            @Override
            public boolean connected(String nodeId) {
                return connected.get();
            }

            @Override
            public NetworkNodeStatus status(String nodeId) {
                return status[0];
            }

            @Override
            public NetworkNodeMetrics metrics(String nodeId) {
                return null;
            }
        };
    }
}
