package restudio.resync.flow.identity;

public record ProviderId(String value) implements LocalId, Comparable<ProviderId> {
    public ProviderId {
        value = IdentityValidation.local(value, "Provider ID");
    }

    public static ProviderId of(String value) {
        return new ProviderId(value);
    }

    @Override
    public int compareTo(ProviderId other) {
        return CanonicalText.compare(value, other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
