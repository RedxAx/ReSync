package restudio.resync.modules.flow;

import java.util.Locale;
import java.util.Objects;

public final class FlowResourceKey implements Comparable<FlowResourceKey> {
    private final String typeId;
    private final String resourceId;

    public FlowResourceKey(String typeId, String resourceId) {
        this.typeId = normalizeType(typeId);
        this.resourceId = normalizeId(resourceId);
    }

    public static FlowResourceKey of(String typeId, String resourceId) {
        return new FlowResourceKey(typeId, resourceId);
    }

    public String typeId() {
        return typeId;
    }

    public String resourceId() {
        return resourceId;
    }

    public String type() {
        return typeId;
    }

    public String resourceType() {
        return typeId;
    }

    public String id() {
        return resourceId;
    }

    @Override
    public int compareTo(FlowResourceKey other) {
        Objects.requireNonNull(other, "other");
        int typeComparison = typeId.compareTo(other.typeId);
        return typeComparison != 0 ? typeComparison : resourceId.compareTo(other.resourceId);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof FlowResourceKey key)) {
            return false;
        }
        return typeId.equals(key.typeId) && resourceId.equals(key.resourceId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(typeId, resourceId);
    }

    @Override
    public String toString() {
        return typeId + ":" + resourceId;
    }

    private static String normalizeType(String typeId) {
        String normalized = requireText(typeId, "typeId");
        return normalized.toLowerCase(Locale.ROOT);
    }

    private static String normalizeId(String resourceId) {
        return requireText(resourceId, "resourceId");
    }

    private static String requireText(String value, String name) {
        String normalized = value != null ? value.strip() : "";
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return normalized;
    }
}
