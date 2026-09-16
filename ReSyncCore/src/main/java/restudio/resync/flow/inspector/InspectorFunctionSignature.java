package restudio.resync.flow.inspector;

import restudio.resync.flow.identity.InspectorFieldId;
import restudio.resync.flow.type.TypeExpr;

import java.util.List;
import java.util.Objects;

public record InspectorFunctionSignature(InspectorFieldId id, String title, String description, List<InspectorFunctionParameter> parameters, TypeExpr returnType, InspectorCondition visibility) {
    public InspectorFunctionSignature {
        id = Objects.requireNonNull(id, "function signature id");
        title = InspectorDescription.title(title, "function signature");
        description = InspectorDescription.description(title, description, "function signature");
        parameters = InspectorSupport.list(parameters, "function parameters");
        InspectorSupport.unique(parameters, parameter -> parameter.id().value(), "function parameter");
        returnType = Objects.requireNonNull(returnType, "function return type");
        visibility = visibility == null ? InspectorCondition.always() : visibility;
    }
}
