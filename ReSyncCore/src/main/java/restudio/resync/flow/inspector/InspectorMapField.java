package restudio.resync.flow.inspector;

import restudio.resync.flow.identity.InspectorFieldId;
import restudio.resync.flow.type.TypeExpr;

import java.util.List;

public final class InspectorMapField extends InspectorField {
    private final TypeExpr keyType;
    private final TypeExpr valueType;
    private final int minimum;
    private final int maximum;
    private final InspectorField value;

    public InspectorMapField(InspectorFieldId id, String title, String description, TypeExpr valueType, TypeExpr keyType, TypeExpr mapValueType, int minimum, int maximum, InspectorField value, InspectorCondition visibility, InspectorFallback fallback, InspectorCapability editor) {
        super(id, title, description, valueType, visibility, fallback, editor);
        if (!(valueType instanceof TypeExpr.MapType mapType) || !mapType.key().equals(keyType) || !mapType.value().equals(mapValueType)) {
            throw new IllegalArgumentException("Map field type must contain its declared key and value types");
        }
        if (minimum < 0 || maximum < minimum) {
            throw new IllegalArgumentException("Invalid map bounds");
        }
        if (value != null && !value.valueType().equals(mapValueType)) {
            throw new IllegalArgumentException("Map value descriptor type must match the value type");
        }
        this.keyType = keyType;
        this.valueType = mapValueType;
        this.minimum = minimum;
        this.maximum = maximum;
        this.value = value;
    }

    public InspectorMapField(InspectorFieldId id, String title, String description, TypeExpr keyType, TypeExpr valueType, int minimum, int maximum, InspectorField value, InspectorCondition visibility, InspectorFallback fallback, InspectorCapability editor) {
        this(id, title, description, TypeExpr.map(keyType, valueType), keyType, valueType, minimum, maximum, value, visibility, fallback, editor);
    }

    @Override
    public Kind kind() {
        return Kind.MAP;
    }

    public TypeExpr keyType() {
        return keyType;
    }

    @Override
    public TypeExpr valueType() {
        return super.valueType();
    }

    public TypeExpr mapValueType() {
        return valueType;
    }

    public int minimum() {
        return minimum;
    }

    public int maximum() {
        return maximum;
    }

    public InspectorField value() {
        return value;
    }

    @Override
    public List<InspectorField> children() {
        return value == null ? List.of() : List.of(value);
    }
}
