package restudio.resync.network;

public record NetworkNodeMetrics(String networkId, String nodeId, int players, int capacity, double tps, double mspt, long heapUsed, long heapMaximum, long observedAt) {
    public NetworkNodeMetrics {
        networkId = NetworkValues.required(networkId, "Network ID");
        nodeId = NetworkValues.required(nodeId, "Node ID");
        players = Math.max(0, players);
        capacity = Math.max(0, capacity);
        tps = Double.isFinite(tps) ? tps : -1;
        mspt = Double.isFinite(mspt) ? mspt : -1;
        heapUsed = Math.max(0, heapUsed);
        heapMaximum = Math.max(0, heapMaximum);
    }
}
