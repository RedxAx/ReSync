package restudio.resync.flow.registry;

import restudio.resync.flow.identity.OwnerId;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record AuthoredSourceProvenance(String sourceUri, int rowIndex, String owner, String sourceHash) {
    public AuthoredSourceProvenance {
        sourceUri = text(sourceUri, "Authored source URI", 1024);
        if (rowIndex < 0) {
            throw new IllegalArgumentException("Authored source row must be non-negative");
        }
        owner = text(owner, "Authored source owner", 128);
        owner = OwnerId.of(owner).value();
        sourceHash = text(sourceHash, "Authored source hash", 128);
    }

    public int row() {
        return rowIndex;
    }

    public Map<String, Object> toMetadata() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("sourceUri", sourceUri);
        values.put("rowIndex", rowIndex);
        values.put("owner", owner);
        values.put("sourceHash", sourceHash);
        return Map.copyOf(values);
    }

    private static String text(String value, String field, int maximum) {
        Objects.requireNonNull(value, field + " is required");
        String normalized = value.strip();
        if (normalized.isBlank() || normalized.length() > maximum) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return normalized;
    }
}
