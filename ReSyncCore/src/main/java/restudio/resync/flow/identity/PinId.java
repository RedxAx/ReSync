package restudio.resync.flow.identity;

public record PinId(String value) implements LocalId, Comparable<PinId> {
    public PinId {
        value = IdentityValidation.pin(value);
    }

    public static PinId of(String value) {
        return new PinId(value);
    }

    @Override
    public int compareTo(PinId other) {
        return CanonicalText.compare(value, other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
