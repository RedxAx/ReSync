package restudio.resync.network;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public record NetworkRouteSet(long revision, String maintenanceRoute, List<NetworkRoute> routes, List<NetworkRoutingGroup> routingGroups) {
    public NetworkRouteSet {
        if (revision < 1) {
            throw new IllegalArgumentException("Network Route Revision Must Be Positive");
        }
        maintenanceRoute = maintenanceRoute == null ? "" : maintenanceRoute.trim().toLowerCase(Locale.ROOT);
        routes = routes == null ? List.of() : List.copyOf(routes);
        if (routes.size() > 4096) {
            throw new IllegalArgumentException("Network Route Set Is Too Large");
        }
        Set<String> names = new LinkedHashSet<>();
        Set<String> nodes = new LinkedHashSet<>();
        for (NetworkRoute route : routes) {
            if (route == null || !names.add(route.routeName()) || !nodes.add(route.nodeId())) {
                throw new IllegalArgumentException("Network Route Set Has Duplicate Routes");
            }
        }
        if (!maintenanceRoute.isBlank() && !names.contains(maintenanceRoute)) {
            throw new IllegalArgumentException("Network Maintenance Route Is Unknown");
        }
        routingGroups = routingGroups == null ? List.of() : List.copyOf(routingGroups);
        if (routingGroups.size() > 1024) {
            throw new IllegalArgumentException("Network Routing Group Set Is Too Large");
        }
        Set<String> groupIds = new LinkedHashSet<>();
        Map<String, NetworkRoutingGroup> groupsById = new LinkedHashMap<>();
        Set<String> forcedHosts = new LinkedHashSet<>();
        for (NetworkRoutingGroup group : routingGroups) {
            if (group == null || !groupIds.add(group.id()) || groupsById.put(group.id(), group) != null) {
                throw new IllegalArgumentException("Network Routing Group Set Has Duplicate Groups");
            }
            if (group.nodeIds().stream().anyMatch(nodeId -> !nodes.contains(nodeId)) || group.weights().keySet().stream().anyMatch(nodeId -> !group.nodeIds().contains(nodeId)) || group.forcedHosts().stream().anyMatch(host -> !forcedHosts.add(host))) {
                throw new IllegalArgumentException("Network Routing Group References Are Invalid");
            }
        }
        for (NetworkRoutingGroup group : routingGroups) {
            if (!group.fallbackGroupId().isBlank() && !groupIds.contains(group.fallbackGroupId())) {
                throw new IllegalArgumentException("Network Routing Group Fallback Is Unknown");
            }
            Set<String> visited = new LinkedHashSet<>();
            NetworkRoutingGroup current = group;
            while (current != null && !current.fallbackGroupId().isBlank()) {
                if (!visited.add(current.id())) {
                    throw new IllegalArgumentException("Network Routing Group Fallback Has A Cycle");
                }
                String fallbackId = current.fallbackGroupId();
                current = groupsById.get(fallbackId);
            }
        }
    }

    public NetworkRouteSet(long revision, String maintenanceRoute, List<NetworkRoute> routes) {
        this(revision, maintenanceRoute, routes, List.of());
    }
}
