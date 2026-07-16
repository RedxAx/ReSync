package restudio.resync.network;

import java.util.UUID;

public record NetworkTransferIntent(String transferId, UUID playerId, String sourceNodeId, String targetNodeId, long deadline) {
    public NetworkTransferIntent {
        transferId = NetworkValues.required(transferId, "Transfer ID");
        if (playerId == null) {
            throw new IllegalArgumentException("Player ID Is Required");
        }
        sourceNodeId = NetworkValues.required(sourceNodeId, "Source Node ID");
        targetNodeId = NetworkValues.required(targetNodeId, "Target Node ID");
        if (sourceNodeId.equals(targetNodeId)) {
            throw new IllegalArgumentException("Transfer Target Must Differ From Source");
        }
        if (deadline < 1) {
            throw new IllegalArgumentException("Transfer Deadline Must Be Positive");
        }
    }
}
