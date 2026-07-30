package restudio.resync.resource;

import java.util.Locale;
import java.util.Objects;

public record ReSyncResourceKey(String type, String id) {
    public ReSyncResourceKey {
        type = normalize(type, "type").toLowerCase(Locale.ROOT);
        id = normalize(id, "id");
    }

    public String token() {
        return type + '\0' + id;
    }

    private static String normalize(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("Resource " + field + " is required");
        }
        return normalized;
    }
}
