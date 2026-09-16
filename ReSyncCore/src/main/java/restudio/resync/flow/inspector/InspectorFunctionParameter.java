package restudio.resync.flow.inspector;

import restudio.resync.flow.identity.FunctionParameterId;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypedValue;

import java.util.Objects;

public record InspectorFunctionParameter(FunctionParameterId id, String title, String description, TypeExpr type, boolean required, TypedValue defaultValue) {
    public InspectorFunctionParameter {
        id = Objects.requireNonNull(id, "function parameter id");
        title = InspectorDescription.title(title, "function parameter");
        description = InspectorDescription.description(title, description, "function parameter");
        type = Objects.requireNonNull(type, "function parameter type");
        if (defaultValue != null && !type.equals(defaultValue.type())) {
            throw new IllegalArgumentException("Function parameter default type must match the parameter type");
        }
        if (required && defaultValue != null && defaultValue.state() == TypedValue.State.ABSENT) {
            throw new IllegalArgumentException("Required function parameters cannot use an absent default");
        }
    }
}
