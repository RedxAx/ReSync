package restudio.resync.network;

import java.util.LinkedHashSet;
import java.util.Set;

public record NetworkNode(String networkId, String nodeId, String displayName, String role, Set<String> capabilities, NetworkNodeStatus status, long heartbeatAt, long revokedAt) {
    public NetworkNode {
        networkId = NetworkValues.required(networkId, "Network ID");
        nodeId = NetworkValues.required(nodeId, "Node ID");
        displayName = NetworkValues.normalized(displayName);
        role = NetworkValues.required(role, "Node Role");
        capabilities = capabilities == null ? Set.of() : Set.copyOf(new LinkedHashSet<>(capabilities));
        status = status == null ? NetworkNodeStatus.OFFLINE : status;
    }
}
