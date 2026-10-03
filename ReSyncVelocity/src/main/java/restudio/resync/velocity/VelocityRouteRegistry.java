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
import java.util.stream.Collectors;

final class VelocityRouteRegistry {
    private final ProxyServer proxyServer;
    private final NodeState nodeState;
    private final Set<String> managedRoutes = new LinkedHashSet<>();
    private volatile RoutingState state;

    VelocityRouteRegistry(ProxyServer proxyServer, NodeState nodeState, Map<String, NetworkRoute> initialRoutes, String maintenanceRoute) {
        this.proxyServer = proxyServer;
        this.nodeState = nodeState;
        this.state = RoutingState.compile(0, "", initialRoutes, maintenanceRoute, List.of());
        this.managedRoutes.addAll(initialRoutes.keySet());
    }

    VelocityRouteRegistry(ProxyServer proxyServer, NodeState nodeState, NetworkRouteSet initial) {
        this(proxyServer, nodeState, initial.routes().stream().collect(Collectors.toMap(NetworkRoute::routeName, route -> route)), initial.maintenanceRoute());
        this.state = RoutingState.compile(0, "", state.routes(), initial.maintenanceRoute(), initial.routingGroups());
    }

    NetworkRoute route(String routeName) {
        return state.routes().get(routeName == null ? "" : routeName.toLowerCase(Locale.ROOT));
    }

    Map<String, NetworkRoute> routes() {
        return state.routes();
    }

    boolean contains(String routeName) {
        return route(routeName) != null;
    }

    boolean containsNode(String nodeId) {
        return state.byNode().containsKey(nodeId);
    }

    boolean accepts(String routeName) {
        NetworkRoute route = route(routeName);
        return accepts(route);
    }

    private boolean accepts(NetworkRoute route) {
        return route == null || nodeState.status(route.nodeId()) == NetworkNodeStatus.ONLINE && (!nodeState.managed(route.nodeId()) || nodeState.connected(route.nodeId()));
    }

    RegisteredServer serverForNode(String nodeId) {
        NetworkRoute route = state.byNode().get(nodeId);
        return route == null ? null : proxyServer.getServer(route.routeName()).orElse(null);
    }

    RoutingDecision initialDestination(Player player, String originalRoute) {
        RoutingState current = state;
        if (player == null || originalRoute == null || originalRoute.isBlank() || current.groups().isEmpty()) {
            return new RoutingDecision(false, null);
        }
        NetworkRoute original = current.routes().get(originalRoute.toLowerCase(Locale.ROOT));
        if (original == null) {
            return new RoutingDecision(false, null);
        }
        String virtualHost = player.getVirtualHost().map(address -> address.getHostString().toLowerCase(Locale.ROOT)).orElse("");
        NetworkRoutingGroup group = current.byHost().get(virtualHost);
        if (group == null) {
            group = current.primaryGroups().get(original.nodeId());
        }
        if (group == null) {
            group = current.memberGroups().get(original.nodeId());
        }
        boolean matched = group != null;
        Set<String> visited = new LinkedHashSet<>();
        while (group != null && visited.add(group.id())) {
            if (group.permission().isBlank() || player.hasPermission(group.permission())) {
                List<NetworkRoutingCandidate> candidates = group.nodeIds().stream().map(current.byNode()::get).filter(Objects::nonNull).map(this::candidate).toList();
                RegisteredServer selected = NetworkRouteSelector.select(player.getUniqueId(), group, candidates).map(NetworkRoutingCandidate::routeName).flatMap(proxyServer::getServer).orElse(null);
                if (selected != null) {
                    return new RoutingDecision(true, selected);
                }
            }
            String fallbackId = group.fallbackGroupId();
            group = fallbackId.isBlank() ? null : current.groups().get(fallbackId);
        }
        return new RoutingDecision(matched, null);
    }

    RegisteredServer maintenanceDestination(String sourceRoute) {
        RoutingState current = state;
        String configuredRoute = current.maintenanceRoute();
        if (!configuredRoute.isBlank() && !configuredRoute.equalsIgnoreCase(sourceRoute) && accepts(current.routes().get(configuredRoute)) && current.routes().containsKey(configuredRoute)) {
            RegisteredServer configured = proxyServer.getServer(configuredRoute).orElse(null);
            if (configured != null) {
                return configured;
            }
        }
        return current.routes().keySet().stream().filter(candidate -> !candidate.equalsIgnoreCase(sourceRoute) && accepts(current.routes().get(candidate))).sorted(String.CASE_INSENSITIVE_ORDER).map(proxyServer::getServer).flatMap(Optional::stream).findFirst().orElse(null);
    }

    synchronized void reconcile(NetworkRouteSet desired, String desiredFingerprint, Runnable reloadNodes, Runnable appendAudit) {
        RoutingState current = state;
        if (desired.revision() < current.revision() || desired.revision() == current.revision() && !current.fingerprint().isBlank() && !current.fingerprint().equals(desiredFingerprint)) {
            throw new IllegalStateException("Network Route Revision Is Stale");
        }
        if (desired.revision() == current.revision() && current.fingerprint().equals(desiredFingerprint)) {
            return;
        }
        reloadNodes.run();
        Map<String, NetworkRoute> desiredByName = new LinkedHashMap<>();
        desired.routes().forEach(route -> desiredByName.put(route.routeName(), route));
        RoutingState next = RoutingState.compile(desired.revision(), desiredFingerprint, desiredByName, desired.maintenanceRoute(), desired.routingGroups());
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
        state = next;
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
        return new NetworkRoutingCandidate(route.nodeId(), route.routeName(), players, capacity, server != null && capacityAvailable && accepts(route));
    }

    private record RoutingState(long revision, String fingerprint, Map<String, NetworkRoute> routes, Map<String, NetworkRoute> byNode,
                                String maintenanceRoute, Map<String, NetworkRoutingGroup> groups, Map<String, NetworkRoutingGroup> byHost,
                                Map<String, NetworkRoutingGroup> primaryGroups, Map<String, NetworkRoutingGroup> memberGroups) {
        private static RoutingState compile(long revision, String fingerprint, Map<String, NetworkRoute> routes, String maintenanceRoute, List<NetworkRoutingGroup> routingGroups) {
            Map<String, NetworkRoute> byNode = new LinkedHashMap<>();
            routes.values().forEach(route -> byNode.put(route.nodeId(), route));
            Map<String, NetworkRoutingGroup> groups = new LinkedHashMap<>();
            Map<String, NetworkRoutingGroup> byHost = new LinkedHashMap<>();
            Map<String, NetworkRoutingGroup> primaryGroups = new LinkedHashMap<>();
            Map<String, NetworkRoutingGroup> memberGroups = new LinkedHashMap<>();
            for (NetworkRoutingGroup group : routingGroups) {
                groups.put(group.id(), group);
                group.forcedHosts().forEach(host -> byHost.put(host, group));
                for (String nodeId : group.nodeIds()) {
                    memberGroups.putIfAbsent(nodeId, group);
                    if (group.forcedHosts().isEmpty()) primaryGroups.putIfAbsent(nodeId, group);
                }
            }
            return new RoutingState(revision, fingerprint, Map.copyOf(routes), Map.copyOf(byNode), maintenanceRoute == null ? "" : maintenanceRoute,
                    Map.copyOf(groups), Map.copyOf(byHost), Map.copyOf(primaryGroups), Map.copyOf(memberGroups));
        }
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
