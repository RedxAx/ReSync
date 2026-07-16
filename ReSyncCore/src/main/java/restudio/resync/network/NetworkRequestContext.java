package restudio.resync.network;

import java.util.LinkedHashSet;
import java.util.Set;

public record NetworkRequestContext(int protocolVersion, String networkId, String nodeId, String requestId, long deadline, Set<String> authorizationScopes) {
    public NetworkRequestContext {
        if (protocolVersion < 1) {
            throw new IllegalArgumentException("Protocol Version Must Be Positive");
        }
        networkId = NetworkValues.required(networkId, "Network ID");
        nodeId = NetworkValues.required(nodeId, "Node ID");
        requestId = NetworkValues.required(requestId, "Request ID");
        authorizationScopes = authorizationScopes == null ? Set.of() : Set.copyOf(new LinkedHashSet<>(authorizationScopes));
    }

    public boolean expired(long now) {
        return deadline > 0 && deadline <= now;
    }

    public void requireScope(String scope, long now) {
        if (expired(now)) {
            throw new IllegalStateException("Network Request Deadline Expired");
        }
        if (!authorizationScopes.contains(scope)) {
            throw new SecurityException("Network Request Requires " + scope);
        }
    }
}
