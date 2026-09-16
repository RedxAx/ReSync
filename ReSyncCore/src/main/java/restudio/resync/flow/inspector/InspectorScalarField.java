package restudio.resync.flow.inspector;

import restudio.resync.flow.identity.InspectorFieldId;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypedValue;

import java.util.List;

public final class InspectorScalarField extends InspectorField {
    private final boolean required;
    private final TypedValue defaultValue;
    private final List<InspectorConstraint> constraints;

    public InspectorScalarField(InspectorFieldId id, String title, String description, TypeExpr valueType, boolean required, TypedValue defaultValue, List<InspectorConstraint> constraints, InspectorCondition visibility, InspectorFallback fallback, InspectorCapability editor) {
        super(id, title, description, valueType, visibility, fallback, editor);
        if (defaultValue != null && !defaultValue.type().equals(valueType)) {
            throw new IllegalArgumentException("Scalar default type must match the field type");
        }
        if (required && defaultValue != null && defaultValue.state() == TypedValue.State.ABSENT) {
            throw new IllegalArgumentException("Required scalar fields cannot use an absent default");
        }
        this.required = required;
        this.defaultValue = defaultValue;
        this.constraints = InspectorSupport.list(constraints == null ? List.of() : constraints, "scalar constraints");
        InspectorSupport.unique(this.constraints, InspectorConstraint::id, "scalar constraint");
    }

    public InspectorScalarField(InspectorFieldId id, String title, String description, TypeExpr valueType, boolean required, InspectorCondition visibility, InspectorFallback fallback, InspectorCapability editor) {
        this(id, title, description, valueType, required, null, List.of(), visibility, fallback, editor);
    }

    @Override
    public Kind kind() {
        return Kind.SCALAR;
    }

    public boolean required() {
        return required;
    }

    public TypedValue defaultValue() {
        return defaultValue;
    }

    public List<InspectorConstraint> constraints() {
        return constraints;
    }
}
