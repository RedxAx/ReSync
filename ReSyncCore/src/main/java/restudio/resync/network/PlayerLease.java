package restudio.resync.network;

import java.util.UUID;

public record PlayerLease(String networkId, UUID playerId, String ownerNodeId, String pendingNodeId, long fenceEpoch, long leaseExpiresAt, long updatedAt) {
    public PlayerLease {
        networkId = NetworkValues.required(networkId, "Network ID");
        if (playerId == null) {
            throw new IllegalArgumentException("Player ID Is Required");
        }
        ownerNodeId = NetworkValues.normalized(ownerNodeId);
        pendingNodeId = NetworkValues.normalized(pendingNodeId);
        if (fenceEpoch < 1) {
            throw new IllegalArgumentException("Fence Epoch Must Be Positive");
        }
    }
}
