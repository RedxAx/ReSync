package restudio.resync.network;

public record NetworkNodeMode(String nodeId, NetworkNodeStatus status) {
    public NetworkNodeMode {
        nodeId = NetworkValues.required(nodeId, "Network Node Mode ID");
        if (status != NetworkNodeStatus.ONLINE && status != NetworkNodeStatus.DRAINING && status != NetworkNodeStatus.MAINTENANCE) {
            throw new IllegalArgumentException("Network Node Mode Is Invalid");
        }
    }
}
