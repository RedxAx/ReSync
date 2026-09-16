package restudio.resync.flow.inspector;

import restudio.resync.flow.identity.InspectorFieldId;
import restudio.resync.flow.type.TypeExpr;

import java.util.List;

public final class InspectorSummaryField extends InspectorField {
    private final List<InspectorFieldId> sources;
    private final String presentation;

    public InspectorSummaryField(InspectorFieldId id, String title, String description, TypeExpr valueType, List<InspectorFieldId> sources, String presentation, InspectorCondition visibility, InspectorFallback fallback, InspectorCapability editor) {
        super(id, title, description, valueType, visibility, fallback, editor);
        this.sources = InspectorSupport.list(sources, "summary source");
        if (this.sources.isEmpty()) {
            throw new IllegalArgumentException("Summaries require at least one source field");
        }
        InspectorSupport.unique(this.sources, InspectorFieldId::value, "summary source");
        this.presentation = InspectorDescription.required(presentation, "summary presentation");
    }

    @Override
    public Kind kind() {
        return Kind.SUMMARY;
    }

    public List<InspectorFieldId> sources() {
        return sources;
    }

    public String presentation() {
        return presentation;
    }
}
