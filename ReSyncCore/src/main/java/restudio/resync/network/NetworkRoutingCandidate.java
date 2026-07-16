package restudio.resync.network;

public record NetworkRoutingCandidate(String nodeId, String routeName, int players, int capacity, boolean available) {
    public NetworkRoutingCandidate {
        nodeId = NetworkValues.required(nodeId, "Routing Candidate Node ID");
        routeName = NetworkValues.required(routeName, "Routing Candidate Route Name");
        players = Math.max(0, players);
        capacity = Math.max(0, capacity);
    }
}
