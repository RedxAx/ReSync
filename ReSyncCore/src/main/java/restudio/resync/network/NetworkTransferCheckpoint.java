package restudio.resync.network;

public record NetworkTransferCheckpoint(String transferId, String snapshotId, String failure) {
    public NetworkTransferCheckpoint {
        transferId = NetworkValues.required(transferId, "Transfer ID");
        snapshotId = NetworkValues.normalized(snapshotId);
        failure = NetworkValues.normalized(failure);
    }

    public static NetworkTransferCheckpoint transfer(String transferId) {
        return new NetworkTransferCheckpoint(transferId, "", "");
    }

    public static NetworkTransferCheckpoint snapshot(String transferId, String snapshotId) {
        return new NetworkTransferCheckpoint(transferId, NetworkValues.required(snapshotId, "Snapshot ID"), "");
    }

    public static NetworkTransferCheckpoint abort(String transferId, String failure) {
        return new NetworkTransferCheckpoint(transferId, "", NetworkValues.required(failure, "Transfer Failure"));
    }
}
