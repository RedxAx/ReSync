package restudio.resync.network;

import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;

public record NetworkResource(String networkId, String type, String resourceId, long revision, String payloadHash, byte[] payload, boolean deleted, String originNodeId, long updatedAt) {
    public NetworkResource {
        networkId = NetworkValues.required(networkId, "Network ID");
        type = NetworkValues.required(type, "Resource Type").toLowerCase(Locale.ROOT);
        resourceId = NetworkValues.required(resourceId, "Resource ID");
        payload = deleted || payload == null ? new byte[0] : Arrays.copyOf(payload, payload.length);
        payloadHash = NetworkValues.required(payloadHash, "Resource Payload Hash");
        originNodeId = NetworkValues.required(originNodeId, "Resource Origin Node ID");
        if (revision < 1) {
            throw new IllegalArgumentException("Network Resource Revision Must Be Positive");
        }
        if (updatedAt < 0) {
            throw new IllegalArgumentException("Network Resource Update Time Cannot Be Negative");
        }
        if (!payloadHash.equals(NetworkPayloads.sha256(payload))) {
            throw new IllegalArgumentException("Network Resource Payload Hash Does Not Match");
        }
    }

    @Override
    public byte[] payload() {
        return Arrays.copyOf(payload, payload.length);
    }

    public NetworkResourceMetadata metadata() {
        return new NetworkResourceMetadata(type, resourceId, revision, payloadHash, deleted, originNodeId, updatedAt);
    }

    @Override
    public boolean equals(Object object) {
        return object instanceof NetworkResource other && revision == other.revision && deleted == other.deleted && updatedAt == other.updatedAt && networkId.equals(other.networkId) && type.equals(other.type) && resourceId.equals(other.resourceId) && payloadHash.equals(other.payloadHash) && originNodeId.equals(other.originNodeId) && Arrays.equals(payload, other.payload);
    }

    @Override
    public int hashCode() {
        return 31 * Objects.hash(networkId, type, resourceId, revision, payloadHash, deleted, originNodeId, updatedAt) + Arrays.hashCode(payload);
    }
}
