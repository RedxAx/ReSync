package restudio.resync.network;

import java.util.Locale;

public record NetworkResourceMetadata(String type, String resourceId, long revision, String payloadHash, boolean deleted, String originNodeId, long updatedAt) {
    public NetworkResourceMetadata {
        type = NetworkValues.required(type, "Resource Type").toLowerCase(Locale.ROOT);
        resourceId = NetworkValues.required(resourceId, "Resource ID");
        payloadHash = NetworkValues.required(payloadHash, "Resource Payload Hash");
        originNodeId = NetworkValues.required(originNodeId, "Resource Origin Node ID");
        if (revision < 1) {
            throw new IllegalArgumentException("Network Resource Revision Must Be Positive");
        }
        if (updatedAt < 0) {
            throw new IllegalArgumentException("Network Resource Update Time Cannot Be Negative");
        }
    }

    public String key() {
        return type + "\u0000" + resourceId;
    }
}
