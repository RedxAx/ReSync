package restudio.resync.flow.identity;

public record BranchId(String value) implements LocalId, Comparable<BranchId> {
    public BranchId {
        value = IdentityValidation.local(value, "Branch ID");
    }

    public static BranchId of(String value) {
        return new BranchId(value);
    }

    @Override
    public int compareTo(BranchId other) {
        return CanonicalText.compare(value, other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
