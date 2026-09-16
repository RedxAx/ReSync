package restudio.resync.flow.inspector;

import restudio.resync.flow.identity.InspectorFieldId;
import restudio.resync.flow.identity.RepeatableGroupId;
import restudio.resync.flow.type.TypeExpr;

import java.util.List;
import java.util.Objects;

public final class InspectorRepeatableField extends InspectorField {
    private final RepeatableGroupId groupId;
    private final int minimum;
    private final int maximum;
    private final boolean ordered;
    private final InspectorField element;

    public InspectorRepeatableField(InspectorFieldId id, String title, String description, TypeExpr valueType, RepeatableGroupId groupId, int minimum, int maximum, boolean ordered, InspectorField element, InspectorCondition visibility, InspectorFallback fallback, InspectorCapability editor) {
        super(id, title, description, valueType, visibility, fallback, editor);
        if (!(valueType instanceof TypeExpr.ListType listType) || !listType.element().equals(element.valueType())) {
            throw new IllegalArgumentException("Repeatable fields require a list type matching their element descriptor");
        }
        if (minimum < 0 || maximum < minimum) {
            throw new IllegalArgumentException("Invalid repeatable bounds");
        }
        this.groupId = Objects.requireNonNull(groupId, "repeatable group id");
        this.minimum = minimum;
        this.maximum = maximum;
        this.ordered = ordered;
        this.element = Objects.requireNonNull(element, "repeatable element");
    }

    @Override
    public Kind kind() {
        return Kind.REPEATABLE;
    }

    public RepeatableGroupId groupId() {
        return groupId;
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
        return List.of(element);
    }
}
