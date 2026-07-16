package restudio.resync.network;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public record NetworkRoutingGroup(String id, String name, NetworkRoutingStrategy strategy, List<String> nodeIds, Map<String, Integer> weights, String fallbackGroupId, Set<String> forcedHosts, String permission) {
    public NetworkRoutingGroup {
        id = NetworkValues.required(id, "Routing Group ID");
        name = NetworkValues.required(name, "Routing Group Name");
        strategy = strategy == null ? NetworkRoutingStrategy.ORDERED : strategy;
        List<String> normalizedNodes = nodeIds == null ? List.of() : List.copyOf(nodeIds);
        if (normalizedNodes.size() > 4096 || new LinkedHashSet<>(normalizedNodes).size() != normalizedNodes.size()) {
            throw new IllegalArgumentException("Network Routing Group Nodes Are Invalid");
        }
        for (String nodeId : normalizedNodes) {
            if (nodeId == null || nodeId.isBlank()) {
                throw new IllegalArgumentException("Network Routing Group Nodes Are Invalid");
            }
        }
        nodeIds = normalizedNodes;
        Map<String, Integer> normalizedWeights = new LinkedHashMap<>();
        Map<String, Integer> sourceWeights = weights;
        if (sourceWeights != null) {
            sourceWeights.forEach((nodeId, weight) -> {
                if (nodeId == null || nodeId.isBlank() || weight == null || weight < 1 || weight > 10_000 || normalizedWeights.put(nodeId.trim(), weight) != null) {
                    throw new IllegalArgumentException("Network Routing Group Weights Are Invalid");
                }
            });
        }
        for (String weightedNode : normalizedWeights.keySet()) {
            if (!normalizedNodes.contains(weightedNode)) {
                throw new IllegalArgumentException("Network Routing Group Weight Node Is Unknown");
            }
        }
        if (strategy == NetworkRoutingStrategy.WEIGHTED) {
            for (String nodeId : normalizedNodes) {
                if (!normalizedWeights.containsKey(nodeId)) {
                    throw new IllegalArgumentException("Network Weighted Routing Requires Every Node Weight");
                }
            }
        }
        weights = Map.copyOf(normalizedWeights);
        fallbackGroupId = fallbackGroupId == null ? "" : fallbackGroupId.trim();
        Set<String> normalizedHosts = new LinkedHashSet<>();
        Set<String> sourceHosts = forcedHosts;
        if (sourceHosts != null) {
            sourceHosts.forEach(host -> {
                String normalized = host == null ? "" : host.trim().toLowerCase(Locale.ROOT);
                if (normalized.isBlank() || !normalizedHosts.add(normalized)) {
                    throw new IllegalArgumentException("Network Routing Group Forced Hosts Are Invalid");
                }
            });
        }
        forcedHosts = Set.copyOf(normalizedHosts);
        permission = permission == null ? "" : permission.trim();
    }
}
