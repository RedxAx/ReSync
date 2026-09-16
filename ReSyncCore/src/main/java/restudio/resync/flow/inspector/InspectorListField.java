package restudio.resync.flow.inspector;

import restudio.resync.flow.identity.InspectorFieldId;
import restudio.resync.flow.type.TypeExpr;

import java.util.List;
import java.util.Objects;

public final class InspectorListField extends InspectorField {
    private final TypeExpr elementType;
    private final int minimum;
    private final int maximum;
    private final boolean ordered;
    private final InspectorField element;

    public InspectorListField(InspectorFieldId id, String title, String description, TypeExpr valueType, TypeExpr elementType, int minimum, int maximum, boolean ordered, InspectorField element, InspectorCondition visibility, InspectorFallback fallback, InspectorCapability editor) {
        super(id, title, description, valueType, visibility, fallback, editor);
        if (!(valueType instanceof TypeExpr.ListType listType) || !listType.element().equals(elementType)) {
            throw new IllegalArgumentException("List field type must contain its declared element type");
        }
        if (minimum < 0 || maximum < minimum) {
            throw new IllegalArgumentException("Invalid list bounds");
        }
        if (element != null && !element.valueType().equals(elementType)) {
            throw new IllegalArgumentException("List element descriptor type must match the element type");
        }
        this.elementType = Objects.requireNonNull(elementType, "element type");
        this.minimum = minimum;
        this.maximum = maximum;
        this.ordered = ordered;
        this.element = element;
    }

    public InspectorListField(InspectorFieldId id, String title, String description, TypeExpr elementType, int minimum, int maximum, boolean ordered, InspectorField element, InspectorCondition visibility, InspectorFallback fallback, InspectorCapability editor) {
        this(id, title, description, TypeExpr.list(elementType), elementType, minimum, maximum, ordered, element, visibility, fallback, editor);
    }

    @Override
    public Kind kind() {
        return Kind.LIST;
    }

    public TypeExpr elementType() {
        return elementType;
    }

    public int minimum() {
        return minimum;
    }

    public int maximum() {
        return maximum;
    }

    public boolean ordered() {
        return ordered;
    }

    public InspectorField element() {
        return element;
    }

    @Override
    public List<InspectorField> children() {
        return element == null ? List.of() : List.of(element);
    }
}
