package restudio.resync.flow.inspector;

import restudio.resync.flow.identity.InspectorFieldId;
import restudio.resync.flow.type.TypeExpr;

import java.util.List;
import java.util.Objects;

public final class InspectorPreviewField extends InspectorField {
    private final InspectorCapability previewCapability;
    private final List<InspectorFieldId> sources;
    private final boolean readOnly;

    public InspectorPreviewField(InspectorFieldId id, String title, String description, TypeExpr valueType, InspectorCapability editor, InspectorCapability previewCapability, List<InspectorFieldId> sources, InspectorCondition visibility, InspectorFallback fallback) {
        super(id, title, description, valueType, visibility, fallback, editor);
        this.previewCapability = Objects.requireNonNull(previewCapability, "preview capability");
        if (!previewCapability.outputSchema().type().equals(valueType)) {
            throw new IllegalArgumentException("Preview output schema must match the field type");
        }
        this.sources = InspectorSupport.list(sources, "preview source");
        if (this.sources.isEmpty()) {
            throw new IllegalArgumentException("Previews require at least one source field");
        }
        InspectorSupport.unique(this.sources, InspectorFieldId::value, "preview source");
        this.readOnly = true;
    }

    @Override
    public Kind kind() {
        return Kind.PREVIEW;
    }

    public InspectorCapability previewCapability() {
        return previewCapability;
    }

    public List<InspectorFieldId> sources() {
        return sources;
    }

    public boolean readOnly() {
        return readOnly;
    }
}
