package restudio.resync.velocity;

import org.junit.jupiter.api.Test;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import restudio.resync.network.NetworkRouteSet;
import restudio.resync.network.NetworkRoutingGroup;
import restudio.resync.network.NetworkRoutingStrategy;
import restudio.resync.network.NetworkNodeMetrics;
import restudio.resync.network.NetworkNodeStatus;
import restudio.resync.network.NetworkRoute;

import java.util.Map;
import java.util.LinkedHashMap;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Set;
import java.util.Optional;
import java.util.UUID;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    @Test
    void enforcesRestoredPermissionBeforeRuntimeReconciliation() {
        NetworkRoute route = new NetworkRoute("node", "lobby", "127.0.0.1", 40001);
        NetworkRoutingGroup group = new NetworkRoutingGroup("play", "Play", NetworkRoutingStrategy.ORDERED, List.of("node"), Map.of(), "", Set.of(), "network.play");
        RegisteredServer server = (RegisteredServer) Proxy.newProxyInstance(RegisteredServer.class.getClassLoader(), new Class<?>[]{RegisteredServer.class}, (object, method, arguments) -> method.getName().equals("getPlayersConnected") ? List.of() : null);
        ProxyServer proxy = (ProxyServer) Proxy.newProxyInstance(ProxyServer.class.getClassLoader(), new Class<?>[]{ProxyServer.class}, (object, method, arguments) -> method.getName().equals("getServer") ? Optional.of(server) : null);
        AtomicBoolean permitted = new AtomicBoolean(false);
        Player player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class}, (object, method, arguments) -> switch (method.getName()) {
            case "getVirtualHost" -> Optional.empty();
            case "hasPermission" -> permitted.get();
            case "getUniqueId" -> UUID.fromString("c917632a-99fa-471a-a292-1a9869e92b21");
            default -> null;
        });
        VelocityRouteRegistry registry = new VelocityRouteRegistry(proxy, nodeState(new AtomicBoolean(true), new NetworkNodeStatus[]{NetworkNodeStatus.ONLINE}), new NetworkRouteSet(1, "lobby", List.of(route), List.of(group)));

        assertTrue(registry.initialDestination(player, "lobby").matched());
        assertNull(registry.initialDestination(player, "lobby").destination());
        permitted.set(true);
        assertSame(server, registry.initialDestination(player, "lobby").destination());
    }

    @Test
    void failedAuditRestoresRoutesAndKeepsTheCommittedSnapshot() {
        Map<String, RegisteredServer> servers = new LinkedHashMap<>();
        ProxyServer proxy = proxy(servers);
        NetworkRoute lobby = new NetworkRoute("lobby-node", "lobby", "127.0.0.1", 40001);
        NetworkRoute survival = new NetworkRoute("survival-node", "survival", "127.0.0.1", 40002);
        proxy.registerServer(new ServerInfo("lobby", InetSocketAddress.createUnresolved("127.0.0.1", 40001)));
        VelocityRouteRegistry registry = new VelocityRouteRegistry(proxy, nodeState(new AtomicBoolean(true), new NetworkNodeStatus[]{NetworkNodeStatus.ONLINE}), new NetworkRouteSet(1, "lobby", List.of(lobby)));
        Map<String, NetworkRoute> before = registry.routes();
        NetworkRouteSet desired = new NetworkRouteSet(2, "survival", List.of(survival));

        assertThrows(IllegalStateException.class, () -> registry.reconcile(desired, "second", () -> {}, () -> { throw new IllegalStateException("Audit Failed"); }));
        assertEquals(before, registry.routes());
        assertTrue(servers.containsKey("lobby"));
        assertFalse(servers.containsKey("survival"));
        registry.reconcile(desired, "second", () -> {}, () -> {});
        assertFalse(registry.containsNode("lobby-node"));
        assertTrue(registry.containsNode("survival-node"));
        assertTrue(before.containsKey("lobby"));
        assertFalse(servers.containsKey("lobby"));
        assertSame(servers.get("survival"), registry.serverForNode("survival-node"));
        assertThrows(UnsupportedOperationException.class, () -> registry.routes().clear());
        registry.reconcile(desired, "second", () -> { throw new AssertionError("Repeated Reconciliation"); }, () -> { throw new AssertionError("Repeated Audit"); });
        assertThrows(IllegalStateException.class, () -> registry.reconcile(new NetworkRouteSet(1, "lobby", List.of(lobby)), "first", () -> {}, () -> {}));
        assertThrows(IllegalStateException.class, () -> registry.reconcile(desired, "changed", () -> {}, () -> {}));
    }

    private ProxyServer proxy(Map<String, RegisteredServer> servers) {
        return (ProxyServer) Proxy.newProxyInstance(ProxyServer.class.getClassLoader(), new Class<?>[]{ProxyServer.class}, (object, method, arguments) -> switch (method.getName()) {
            case "getServer" -> Optional.ofNullable(servers.get(arguments[0]));
            case "registerServer" -> {
                ServerInfo info = (ServerInfo) arguments[0];
                RegisteredServer server = (RegisteredServer) Proxy.newProxyInstance(RegisteredServer.class.getClassLoader(), new Class<?>[]{RegisteredServer.class}, (target, call, values) -> switch (call.getName()) {
                    case "getServerInfo" -> info;
                    case "getPlayersConnected" -> List.of();
                    default -> null;
                });
                servers.put(info.getName(), server);
                yield server;
            }
            case "unregisterServer" -> {
                servers.remove(((ServerInfo) arguments[0]).getName());
                yield null;
            }
            default -> null;
        });
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
