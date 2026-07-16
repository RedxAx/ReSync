package restudio.resync.network;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

public record PlayerStateSnapshot(String snapshotId, String networkId, UUID playerId, long fenceEpoch, String family, byte[] payload, String payloadHash, int schemaVersion, int dataVersion, String originNodeId, long createdAt, boolean pinned) {
    public PlayerStateSnapshot {
        snapshotId = NetworkValues.required(snapshotId, "Snapshot ID");
        networkId = NetworkValues.required(networkId, "Network ID");
        if (playerId == null) {
            throw new IllegalArgumentException("Player ID Is Required");
        }
        if (fenceEpoch < 1) {
            throw new IllegalArgumentException("Fence Epoch Must Be Positive");
        }
        family = NetworkValues.required(family, "State Family");
        payload = payload == null ? new byte[0] : Arrays.copyOf(payload, payload.length);
        payloadHash = NetworkValues.required(payloadHash, "Payload Hash").toLowerCase();
        originNodeId = NetworkValues.required(originNodeId, "Origin Node ID");
        if (schemaVersion < 0 || dataVersion < 0) {
            throw new IllegalArgumentException("Snapshot Versions Cannot Be Negative");
        }
        if (createdAt < 1) {
            throw new IllegalArgumentException("Snapshot Time Must Be Positive");
        }
    }

    @Override
    public byte[] payload() {
        return Arrays.copyOf(payload, payload.length);
    }

    @Override
    public boolean equals(Object object) {
        return object instanceof PlayerStateSnapshot other && fenceEpoch == other.fenceEpoch && schemaVersion == other.schemaVersion && dataVersion == other.dataVersion && createdAt == other.createdAt && pinned == other.pinned && snapshotId.equals(other.snapshotId) && networkId.equals(other.networkId) && playerId.equals(other.playerId) && family.equals(other.family) && Arrays.equals(payload, other.payload) && payloadHash.equals(other.payloadHash) && originNodeId.equals(other.originNodeId);
    }

    @Override
    public int hashCode() {
        return 31 * Objects.hash(snapshotId, networkId, playerId, fenceEpoch, family, payloadHash, schemaVersion, dataVersion, originNodeId, createdAt, pinned) + Arrays.hashCode(payload);
    }
}
