package restudio.resync.flow.identity;

public record CaseId(String value) implements LocalId, Comparable<CaseId> {
    public CaseId {
        value = IdentityValidation.local(value, "Case ID");
    }

    public static CaseId of(String value) {
        return new CaseId(value);
    }

    @Override
    public int compareTo(CaseId other) {
        return CanonicalText.compare(value, other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
