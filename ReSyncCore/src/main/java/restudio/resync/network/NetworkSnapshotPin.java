package restudio.resync.network;

public record NetworkSnapshotPin(String snapshotId, boolean pinned) {
    public NetworkSnapshotPin {
        snapshotId = NetworkValues.required(snapshotId, "Snapshot ID");
    }
}
