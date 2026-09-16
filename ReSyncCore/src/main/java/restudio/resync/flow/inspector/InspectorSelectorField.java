package restudio.resync.flow.inspector;

import restudio.resync.flow.identity.InspectorFieldId;
import restudio.resync.flow.type.TypeExpr;

public final class InspectorSelectorField extends InspectorField {
    private final InspectorOptionSource optionSource;
    private final boolean searchable;
    private final boolean allowAbsent;

    public InspectorSelectorField(InspectorFieldId id, String title, String description, TypeExpr valueType, InspectorOptionSource optionSource, boolean searchable, boolean allowAbsent, InspectorCondition visibility, InspectorFallback fallback, InspectorCapability editor) {
        super(id, title, description, valueType, visibility, fallback, editor);
        if (!optionSource.optionType().equals(valueType)) {
            throw new IllegalArgumentException("Selector option type must match the field type");
        }
        this.optionSource = optionSource;
        this.searchable = searchable;
        this.allowAbsent = allowAbsent;
    }

    public InspectorOptionSource optionSource() {
        return optionSource;
    }

    public boolean searchable() {
        return searchable;
    }

    public boolean allowAbsent() {
        return allowAbsent;
    }

    @Override
    public Kind kind() {
        return Kind.SELECTOR;
    }
}
