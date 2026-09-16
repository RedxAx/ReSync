package restudio.resync.flow.identity;

public record ResourceTypeId(String value) implements LocalId, Comparable<ResourceTypeId> {
    public ResourceTypeId {
        value = IdentityValidation.local(value, "Resource Type ID");
    }

    public static ResourceTypeId of(String value) {
        return new ResourceTypeId(value);
    }

    @Override
    public int compareTo(ResourceTypeId other) {
        return CanonicalText.compare(value, other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
