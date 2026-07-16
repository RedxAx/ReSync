package restudio.resync.network;

public record NetworkNodePresence(String networkId, String nodeId, NetworkNodeStatus status, int players, int capacity, double tps, double mspt, long heapUsed, long heapMaximum, long observedAt) {
    public NetworkNodePresence {
        networkId = NetworkValues.required(networkId, "Network ID");
        nodeId = NetworkValues.required(nodeId, "Node ID");
        status = status == null ? NetworkNodeStatus.OFFLINE : status;
        NetworkNodeMetrics metrics = new NetworkNodeMetrics(networkId, nodeId, players, capacity, tps, mspt, heapUsed, heapMaximum, observedAt);
        players = metrics.players();
        capacity = metrics.capacity();
        tps = metrics.tps();
        mspt = metrics.mspt();
        heapUsed = metrics.heapUsed();
        heapMaximum = metrics.heapMaximum();
    }

    public NetworkNodeMetrics metrics() {
        return new NetworkNodeMetrics(networkId, nodeId, players, capacity, tps, mspt, heapUsed, heapMaximum, observedAt);
    }
}
