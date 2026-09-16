package restudio.resync.flow.identity;

public record RepeatableGroupId(String value) implements LocalId, Comparable<RepeatableGroupId> {
    public RepeatableGroupId {
        value = IdentityValidation.local(value, "Repeatable Group ID");
    }

    public static RepeatableGroupId of(String value) {
        return new RepeatableGroupId(value);
    }

    @Override
    public int compareTo(RepeatableGroupId other) {
        return CanonicalText.compare(value, other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
