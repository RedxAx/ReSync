package restudio.resync.network;

import java.util.Locale;

public record NetworkRoute(String nodeId, String routeName, String address, int port) {
    public NetworkRoute {
        nodeId = NetworkValues.required(nodeId, "Network Route Node ID");
        routeName = NetworkValues.required(routeName, "Network Route Name").toLowerCase(Locale.ROOT);
        address = NetworkValues.required(address, "Network Route Address");
        if (!routeName.matches("[a-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("Network Route Name Is Invalid");
        }
        if (address.length() > 253 || port < 1 || port > 65535) {
            throw new IllegalArgumentException("Network Route Endpoint Is Invalid");
        }
    }
}
