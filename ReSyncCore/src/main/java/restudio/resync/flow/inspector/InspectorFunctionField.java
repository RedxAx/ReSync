package restudio.resync.flow.inspector;

import restudio.resync.flow.identity.InspectorFieldId;
import restudio.resync.flow.type.TypeExpr;

import java.util.List;
import java.util.Objects;

public final class InspectorFunctionField extends InspectorField {
    private final InspectorFunctionSignature signature;

    public InspectorFunctionField(InspectorFieldId id, String title, String description, TypeExpr valueType, InspectorFunctionSignature signature, InspectorCondition visibility, InspectorFallback fallback, InspectorCapability editor) {
        super(id, title, description, valueType, visibility, fallback, editor);
        this.signature = Objects.requireNonNull(signature, "function signature");
    }

    @Override
    public Kind kind() {
        return Kind.FUNCTION;
    }

    public InspectorFunctionSignature signature() {
        return signature;
    }
}
