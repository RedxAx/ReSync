package restudio.resync.network;

import java.util.UUID;

public record NetworkSnapshotMetadata(String snapshotId, String networkId, UUID playerId, long fenceEpoch, String family, int payloadBytes, String payloadHash, int schemaVersion, int dataVersion, String originNodeId, long createdAt, boolean pinned) {
    public NetworkSnapshotMetadata {
        snapshotId = NetworkValues.required(snapshotId, "Snapshot ID");
        networkId = NetworkValues.required(networkId, "Network ID");
        if (playerId == null) {
            throw new IllegalArgumentException("Player ID Is Required");
        }
        if (fenceEpoch < 1 || payloadBytes < 0 || schemaVersion < 0 || dataVersion < 0 || createdAt < 1) {
            throw new IllegalArgumentException("Network Snapshot Metadata Is Invalid");
        }
        family = NetworkValues.required(family, "State Family");
        payloadHash = NetworkValues.required(payloadHash, "Payload Hash").toLowerCase();
        originNodeId = NetworkValues.required(originNodeId, "Origin Node ID");
        if (snapshotId.length() > 256 || networkId.length() > 256 || family.length() > 256 || payloadHash.length() > 128 || originNodeId.length() > 256) {
            throw new IllegalArgumentException("Network Snapshot Metadata Text Is Too Large");
        }
    }

    public static NetworkSnapshotMetadata from(PlayerStateSnapshot snapshot) {
        return new NetworkSnapshotMetadata(snapshot.snapshotId(), snapshot.networkId(), snapshot.playerId(), snapshot.fenceEpoch(), snapshot.family(), snapshot.payload().length, snapshot.payloadHash(), snapshot.schemaVersion(), snapshot.dataVersion(), snapshot.originNodeId(), snapshot.createdAt(), snapshot.pinned());
    }
}
