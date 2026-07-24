package restudio.resync.network;

import java.util.Locale;

public record NetworkResourceQuery(String afterType, String afterResourceId, int limit) {
    public NetworkResourceQuery {
        afterType = NetworkValues.normalized(afterType).toLowerCase(Locale.ROOT);
        afterResourceId = NetworkValues.normalized(afterResourceId);
        limit = Math.clamp(limit <= 0 ? 128 : limit, 1, 128);
        if (afterType.isBlank() && !afterResourceId.isBlank()) {
            throw new IllegalArgumentException("Resource Cursor Type Is Required");
        }
    }

    public static NetworkResourceQuery firstPage() {
        return new NetworkResourceQuery("", "", 128);
    }
}
