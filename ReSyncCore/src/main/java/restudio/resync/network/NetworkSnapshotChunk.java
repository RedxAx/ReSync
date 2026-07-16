package restudio.resync.network;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

public record NetworkSnapshotChunk(String transferId, String snapshotId, String networkId, UUID playerId, long fenceEpoch, String family, String payloadHash, int schemaVersion, int dataVersion, String originNodeId, long createdAt, int totalBytes, int chunkIndex, int chunkCount, byte[] payload) {
    public NetworkSnapshotChunk {
        transferId = NetworkValues.required(transferId, "Transfer ID");
        snapshotId = NetworkValues.required(snapshotId, "Snapshot ID");
        networkId = NetworkValues.required(networkId, "Network ID");
        if (playerId == null) {
            throw new IllegalArgumentException("Player ID Is Required");
        }
        if (fenceEpoch < 1) {
            throw new IllegalArgumentException("Fence Epoch Must Be Positive");
        }
        family = NetworkValues.required(family, "State Family");
        payloadHash = NetworkValues.required(payloadHash, "Payload Hash");
        originNodeId = NetworkValues.required(originNodeId, "Origin Node ID");
        if (schemaVersion < 0 || dataVersion < 0) {
            throw new IllegalArgumentException("Snapshot Versions Cannot Be Negative");
        }
        if (createdAt < 1) {
            throw new IllegalArgumentException("Snapshot Time Must Be Positive");
        }
        if (totalBytes < 0 || chunkCount < 1 || chunkIndex < 0 || chunkIndex >= chunkCount) {
            throw new IllegalArgumentException("Snapshot Chunk Position Is Invalid");
        }
        payload = payload == null ? new byte[0] : Arrays.copyOf(payload, payload.length);
        if (payload.length > totalBytes || totalBytes == 0 && (chunkCount != 1 || payload.length != 0)) {
            throw new IllegalArgumentException("Snapshot Chunk Size Is Invalid");
        }
    }

    @Override
    public byte[] payload() {
        return Arrays.copyOf(payload, payload.length);
    }

    @Override
    public boolean equals(Object object) {
        return object instanceof NetworkSnapshotChunk other && fenceEpoch == other.fenceEpoch && schemaVersion == other.schemaVersion && dataVersion == other.dataVersion && createdAt == other.createdAt && totalBytes == other.totalBytes && chunkIndex == other.chunkIndex && chunkCount == other.chunkCount && transferId.equals(other.transferId) && snapshotId.equals(other.snapshotId) && networkId.equals(other.networkId) && playerId.equals(other.playerId) && family.equals(other.family) && payloadHash.equals(other.payloadHash) && originNodeId.equals(other.originNodeId) && Arrays.equals(payload, other.payload);
    }

    @Override
    public int hashCode() {
        return 31 * Objects.hash(transferId, snapshotId, networkId, playerId, fenceEpoch, family, payloadHash, schemaVersion, dataVersion, originNodeId, createdAt, totalBytes, chunkIndex, chunkCount) + Arrays.hashCode(payload);
    }
}
