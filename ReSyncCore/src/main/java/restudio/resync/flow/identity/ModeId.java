package restudio.resync.flow.identity;

public record ModeId(String value) implements LocalId, Comparable<ModeId> {
    public ModeId {
        value = IdentityValidation.local(value, "Mode ID");
    }

    public static ModeId of(String value) {
        return new ModeId(value);
    }

    @Override
    public int compareTo(ModeId other) {
        return CanonicalText.compare(value, other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
