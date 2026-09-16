package restudio.resync.flow.identity;

public record OperationId(String value) implements LocalId, Comparable<OperationId> {
    public OperationId {
        value = IdentityValidation.local(value, "Operation ID");
    }

    public static OperationId of(String value) {
        return new OperationId(value);
    }

    @Override
    public int compareTo(OperationId other) {
        return CanonicalText.compare(value, other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
