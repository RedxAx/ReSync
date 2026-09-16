package restudio.resync.flow.identity;

public record CapabilityId(String value) implements LocalId, Comparable<CapabilityId> {
    public CapabilityId {
        value = IdentityValidation.local(value, "Capability ID");
    }

    public static CapabilityId of(String value) {
        return new CapabilityId(value);
    }

    @Override
    public int compareTo(CapabilityId other) {
        return CanonicalText.compare(value, other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
