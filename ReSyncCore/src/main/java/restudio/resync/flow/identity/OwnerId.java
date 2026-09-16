package restudio.resync.flow.identity;

public record OwnerId(String value) implements Comparable<OwnerId> {
    public OwnerId {
        value = IdentityValidation.owner(value);
    }

    public static OwnerId of(String value) {
        return new OwnerId(value);
    }

    public String canonicalText() {
        return value;
    }

    @Override
    public int compareTo(OwnerId other) {
        return CanonicalText.compare(value, other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
