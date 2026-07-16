package restudio.resync.velocity;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import restudio.resync.network.NetworkNodeMetrics;
import restudio.resync.network.NetworkNodeStatus;
import restudio.resync.network.NetworkRoute;
import restudio.resync.network.NetworkRouteSelector;
import restudio.resync.network.NetworkRouteSet;
import restudio.resync.network.NetworkRoutingCandidate;
import restudio.resync.network.NetworkRoutingGroup;

import java.net.InetSocketAddress;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

final class VelocityRouteRegistry {
    private final ProxyServer proxyServer;
    private final NodeState nodeState;
    private final Map<String, NetworkRoute> routes = new ConcurrentHashMap<>();
    private final Set<String> managedRoutes = ConcurrentHashMap.newKeySet();
    private long revision;
    private String fingerprint = "";
    private volatile String maintenanceRoute;
    private volatile List<NetworkRoutingGroup> routingGroups = List.of();

    VelocityRouteRegistry(ProxyServer proxyServer, NodeState nodeState, Map<String, NetworkRoute> initialRoutes, String maintenanceRoute) {
        this.proxyServer = proxyServer;
        this.nodeState = nodeState;
        this.routes.putAll(initialRoutes);
        this.managedRoutes.addAll(initialRoutes.keySet());
        this.maintenanceRoute = maintenanceRoute;
    }

    NetworkRoute route(String routeName) {
        return routes.get(routeName == null ? "" : routeName.toLowerCase(Locale.ROOT));
    }

    Map<String, NetworkRoute> routes() {
        return Map.copyOf(routes);
    }

    boolean contains(String routeName) {
        return route(routeName) != null;
    }

    boolean containsNode(String nodeId) {
        return routes.values().stream().anyMatch(route -> route.nodeId().equals(nodeId));
    }

    boolean accepts(String routeName) {
        NetworkRoute route = route(routeName);
        return route == null || nodeState.status(route.nodeId()) == NetworkNodeStatus.ONLINE && (!nodeState.managed(route.nodeId()) || nodeState.connected(route.nodeId()));
    }

    RegisteredServer serverForNode(String nodeId) {
        return routes.values().stream().filter(route -> route.nodeId().equals(nodeId)).map(NetworkRoute::routeName).map(proxyServer::getServer).flatMap(Optional::stream).findFirst().orElse(null);
    }

    RoutingDecision initialDestination(Player player, String originalRoute) {
        if (player == null || originalRoute == null || originalRoute.isBlank() || routingGroups.isEmpty()) {
            return new RoutingDecision(false, null);
        }
        NetworkRoute original = route(originalRoute);
        if (original == null) {
            return new RoutingDecision(false, null);
        }
        String virtualHost = player.getVirtualHost().map(address -> address.getHostString().toLowerCase(Locale.ROOT)).orElse("");
        NetworkRoutingGroup group = virtualHost.isBlank() ? null : routingGroups.stream().filter(candidate -> candidate.forcedHosts().contains(virtualHost)).findFirst().orElse(null);
        if (group == null) {
            group = routingGroups.stream().filter(candidate -> candidate.forcedHosts().isEmpty() && candidate.nodeIds().contains(original.nodeId())).findFirst().orElse(null);
        }
        if (group == null) {
            group = routingGroups.stream().filter(candidate -> candidate.nodeIds().contains(original.nodeId())).findFirst().orElse(null);
        }
        boolean matched = group != null;
        Map<String, NetworkRoute> routesByNode = routesByNode();
        Set<String> visited = new LinkedHashSet<>();
        while (group != null && visited.add(group.id())) {
            if (group.permission().isBlank() || player.hasPermission(group.permission())) {
                List<NetworkRoutingCandidate> candidates = group.nodeIds().stream().map(routesByNode::get).filter(Objects::nonNull).map(this::candidate).toList();
                RegisteredServer selected = NetworkRouteSelector.select(player.getUniqueId(), group, candidates).map(NetworkRoutingCandidate::routeName).flatMap(proxyServer::getServer).orElse(null);
                if (selected != null) {
                    return new RoutingDecision(true, selected);
                }
            }
            String fallbackId = group.fallbackGroupId();
            group = fallbackId.isBlank() ? null : routingGroups.stream().filter(candidate -> candidate.id().equals(fallbackId)).findFirst().orElse(null);
        }
        return new RoutingDecision(matched, null);
    }

    RegisteredServer maintenanceDestination(String sourceRoute) {
        String configuredRoute = maintenanceRoute;
        if (!configuredRoute.isBlank() && !configuredRoute.equalsIgnoreCase(sourceRoute) && accepts(configuredRoute) && contains(configuredRoute)) {
            RegisteredServer configured = proxyServer.getServer(configuredRoute).orElse(null);
            if (configured != null) {
                return configured;
            }
        }
        return routes.keySet().stream().filter(candidate -> !candidate.equalsIgnoreCase(sourceRoute) && accepts(candidate)).sorted(String.CASE_INSENSITIVE_ORDER).map(proxyServer::getServer).flatMap(Optional::stream).findFirst().orElse(null);
    }

    synchronized void reconcile(NetworkRouteSet desired, String desiredFingerprint, Runnable reloadNodes, Runnable appendAudit) {
        if (desired.revision() < revision || desired.revision() == revision && !fingerprint.isBlank() && !fingerprint.equals(desiredFingerprint)) {
            throw new IllegalStateException("Network Route Revision Is Stale");
        }
        if (desired.revision() == revision && fingerprint.equals(desiredFingerprint)) {
            return;
        }
        reloadNodes.run();
        Map<String, NetworkRoute> desiredByName = new LinkedHashMap<>();
        desired.routes().forEach(route -> desiredByName.put(route.routeName(), route));
        validate(desired.routes(), desiredByName);
        Map<String, ServerInfo> previous = new LinkedHashMap<>();
        managedRoutes.forEach(routeName -> proxyServer.getServer(routeName).ifPresent(server -> previous.put(routeName, server.getServerInfo())));
        try {
            for (String routeName : Set.copyOf(managedRoutes)) {
                NetworkRoute route = desiredByName.get(routeName);
                RegisteredServer existing = proxyServer.getServer(routeName).orElse(null);
                if (existing != null && (route == null || !sameEndpoint(existing.getServerInfo(), route))) {
                    proxyServer.unregisterServer(existing.getServerInfo());
                }
            }
            for (NetworkRoute route : desired.routes()) {
                if (proxyServer.getServer(route.routeName()).isEmpty()) {
                    proxyServer.registerServer(serverInfo(route));
                }
            }
            appendAudit.run();
        } catch (RuntimeException exception) {
            restore(previous, desiredByName.keySet());
            throw new IllegalStateException("Runtime Route Reconciliation Failed", exception);
        }
        managedRoutes.clear();
        managedRoutes.addAll(desiredByName.keySet());
        routes.clear();
        routes.putAll(desiredByName);
        maintenanceRoute = desired.maintenanceRoute();
        routingGroups = desired.routingGroups();
        revision = desired.revision();
        fingerprint = desiredFingerprint;
    }

    private void validate(List<NetworkRoute> desiredRoutes, Map<String, NetworkRoute> desiredByName) {
        for (NetworkRoute route : desiredRoutes) {
            RegisteredServer existing = proxyServer.getServer(route.routeName()).orElse(null);
            if (existing != null && !managedRoutes.contains(route.routeName())) {
                throw new IllegalStateException("Runtime Route Conflicts With Unmanaged Server " + route.routeName());
            }
            if (existing != null && !sameEndpoint(existing.getServerInfo(), route) && !existing.getPlayersConnected().isEmpty()) {
                throw new IllegalStateException("Runtime Route Has Connected Players " + route.routeName());
            }
        }
        for (String routeName : managedRoutes) {
            if (desiredByName.containsKey(routeName)) {
                continue;
            }
            RegisteredServer existing = proxyServer.getServer(routeName).orElse(null);
            if (existing != null && !existing.getPlayersConnected().isEmpty()) {
                throw new IllegalStateException("Runtime Route Has Connected Players " + routeName);
            }
        }
    }

    private void restore(Map<String, ServerInfo> previous, Set<String> attemptedRoutes) {
        Set<String> affected = new LinkedHashSet<>(attemptedRoutes);
        affected.addAll(previous.keySet());
        for (String routeName : affected) {
            proxyServer.getServer(routeName).ifPresent(server -> {
                if (managedRoutes.contains(routeName) || attemptedRoutes.contains(routeName)) {
                    proxyServer.unregisterServer(server.getServerInfo());
                }
            });
        }
        previous.values().forEach(server -> {
            if (proxyServer.getServer(server.getName()).isEmpty()) {
                proxyServer.registerServer(server);
            }
        });
    }

    private NetworkRoutingCandidate candidate(NetworkRoute route) {
        RegisteredServer server = proxyServer.getServer(route.routeName()).orElse(null);
        NetworkNodeMetrics metrics = nodeState.metrics(route.nodeId());
        int players = server == null ? metrics == null ? 0 : metrics.players() : server.getPlayersConnected().size();
        int capacity = metrics == null ? 0 : metrics.capacity();
        boolean capacityAvailable = capacity < 1 || players < capacity;
        return new NetworkRoutingCandidate(route.nodeId(), route.routeName(), players, capacity, server != null && capacityAvailable && accepts(route.routeName()));
    }

    private Map<String, NetworkRoute> routesByNode() {
        Map<String, NetworkRoute> byNode = new LinkedHashMap<>();
        routes.values().forEach(route -> byNode.put(route.nodeId(), route));
        return byNode;
    }

    private boolean sameEndpoint(ServerInfo existing, NetworkRoute desired) {
        return existing.getAddress().getHostString().equalsIgnoreCase(desired.address()) && existing.getAddress().getPort() == desired.port();
    }

    private ServerInfo serverInfo(NetworkRoute route) {
        return new ServerInfo(route.routeName(), InetSocketAddress.createUnresolved(route.address(), route.port()));
    }

    record RoutingDecision(boolean matched, RegisteredServer destination) {
    }

    interface NodeState {
        boolean managed(String nodeId);

        boolean connected(String nodeId);

        NetworkNodeStatus status(String nodeId);

        NetworkNodeMetrics metrics(String nodeId);
    }
}
