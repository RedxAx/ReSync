package restudio.resync.network;

import java.util.UUID;

public record PlayerTransfer(String transferId, String networkId, UUID playerId, String sourceNodeId, String targetNodeId, long fenceEpoch, NetworkTransferStatus status, String snapshotId, String failure, long deadline, long createdAt, long updatedAt) {
    public PlayerTransfer {
        transferId = NetworkValues.required(transferId, "Transfer ID");
        networkId = NetworkValues.required(networkId, "Network ID");
        if (playerId == null) {
            throw new IllegalArgumentException("Player ID Is Required");
        }
        sourceNodeId = NetworkValues.required(sourceNodeId, "Source Node ID");
        targetNodeId = NetworkValues.required(targetNodeId, "Target Node ID");
        status = status == null ? NetworkTransferStatus.INTENT : status;
        snapshotId = NetworkValues.normalized(snapshotId);
        failure = NetworkValues.normalized(failure);
        if (fenceEpoch < 1) {
            throw new IllegalArgumentException("Fence Epoch Must Be Positive");
        }
        if (deadline < 1 || createdAt < 1 || updatedAt < createdAt) {
            throw new IllegalArgumentException("Transfer Timing Is Invalid");
        }
    }
}
