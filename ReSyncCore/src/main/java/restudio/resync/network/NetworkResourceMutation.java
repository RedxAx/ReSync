package restudio.resync.network;

import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;

public record NetworkResourceMutation(String type, String resourceId, long expectedRevision, byte[] payload, boolean deleted) {
    public NetworkResourceMutation {
        type = NetworkValues.required(type, "Resource Type").toLowerCase(Locale.ROOT);
        resourceId = NetworkValues.required(resourceId, "Resource ID");
        payload = deleted || payload == null ? new byte[0] : Arrays.copyOf(payload, payload.length);
        if (expectedRevision < 0) {
            throw new IllegalArgumentException("Expected Resource Revision Cannot Be Negative");
        }
    }

    public static NetworkResourceMutation save(String type, String resourceId, long expectedRevision, byte[] payload) {
        return new NetworkResourceMutation(type, resourceId, expectedRevision, payload, false);
    }

    public static NetworkResourceMutation delete(String type, String resourceId, long expectedRevision) {
        return new NetworkResourceMutation(type, resourceId, expectedRevision, new byte[0], true);
    }

    @Override
    public byte[] payload() {
        return Arrays.copyOf(payload, payload.length);
    }

    @Override
    public boolean equals(Object object) {
        return object instanceof NetworkResourceMutation other && expectedRevision == other.expectedRevision && deleted == other.deleted && type.equals(other.type) && resourceId.equals(other.resourceId) && Arrays.equals(payload, other.payload);
    }

    @Override
    public int hashCode() {
        return 31 * Objects.hash(type, resourceId, expectedRevision, deleted) + Arrays.hashCode(payload);
    }
}
