package restudio.resync.network;

public record NetworkSnapshotRestore(String snapshotId, String targetNodeId, long deadline) {
    public NetworkSnapshotRestore {
        snapshotId = NetworkValues.required(snapshotId, "Snapshot ID");
        targetNodeId = NetworkValues.required(targetNodeId, "Target Node ID");
        if (deadline < 1) {
            throw new IllegalArgumentException("Snapshot Restore Deadline Is Required");
        }
    }
}
