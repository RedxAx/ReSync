package restudio.resync.flow.identity;

public record InspectorSectionId(String value) implements LocalId, Comparable<InspectorSectionId> {
    public InspectorSectionId {
        value = IdentityValidation.local(value, "Inspector Section ID");
    }

    public static InspectorSectionId of(String value) {
        return new InspectorSectionId(value);
    }

    @Override
    public int compareTo(InspectorSectionId other) {
        return CanonicalText.compare(value, other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
