package restudio.resync.flow.inspector;

public record InspectorDraftSerialization(String format, int version, boolean canonical, boolean preservesUnknown) {
    public InspectorDraftSerialization {
        format = InspectorDescription.required(format, "draft serialization format");
        if (version < 1) {
            throw new IllegalArgumentException("Draft serialization version must be positive");
        }
    }

    public static InspectorDraftSerialization canonicalJson() {
        return new InspectorDraftSerialization("typed-json", 1, true, true);
    }
}
