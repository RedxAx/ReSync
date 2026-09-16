package restudio.resync.flow.identity;

public record InspectorFieldId(String value) implements LocalId, Comparable<InspectorFieldId> {
    public InspectorFieldId {
        value = IdentityValidation.local(value, "Inspector Field ID");
    }

    public static InspectorFieldId of(String value) {
        return new InspectorFieldId(value);
    }

    @Override
    public int compareTo(InspectorFieldId other) {
        return CanonicalText.compare(value, other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
