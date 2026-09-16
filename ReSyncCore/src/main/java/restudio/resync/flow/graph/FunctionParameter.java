package restudio.resync.flow.graph;

import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.identity.FunctionParameterId;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypedValue;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record FunctionParameter(FunctionParameterId parameterId, String name, TypeExpr type, String description, TypedValue defaultValue, OpaqueData unknown) {
    public FunctionParameter {
        parameterId = Objects.requireNonNull(parameterId, "parameterId");
        name = requireName(name);
        type = Objects.requireNonNull(type, "type");
        description = description == null || description.isBlank() ? "Provides the " + name + " value to this function." : description.trim();
        if (description.length() < 16 || description.length() > 240) {
            throw new IllegalArgumentException("Function parameter description must contain between 16 and 240 characters");
        }
        unknown = unknown != null ? unknown : OpaqueData.empty();
        unknown.rejectKnownFields("parameterId", "name", "type", "description", "default");
        if (defaultValue != null && !type.equals(defaultValue.type())) {
            throw new IllegalArgumentException("Function parameter default type must match the parameter type");
        }
    }

    public FunctionParameter(FunctionParameterId parameterId, String name, TypeExpr type) {
        this(parameterId, name, type, "", null, OpaqueData.empty());
    }

    public FunctionParameter(FunctionParameterId parameterId, String name, TypeExpr type, String description, TypedValue defaultValue) {
        this(parameterId, name, type, description, defaultValue, OpaqueData.empty());
    }

    public static FunctionParameter stable(UUID namespace, String scope, String name, TypeExpr type) {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(scope, "scope");
        String parameterName = requireName(name);
        FunctionParameterId id = FunctionParameterId.deterministic(namespace, "function-parameter", scope + "\u0000" + parameterName);
        return new FunctionParameter(id, parameterName, type);
    }

    Map<String, Object> canonicalValue() {
        LinkedHashMap<String, Object> values = new LinkedHashMap<>();
        values.put("parameterId", parameterId.canonicalText());
        values.put("name", name);
        values.put("type", CanonicalJson.parse(type.canonicalJson()));
        values.put("description", description);
        if (defaultValue != null) {
            values.put("default", CanonicalJson.parse(defaultValue.canonicalJson()));
        }
        return OpaqueData.mergeKnownFields(unknown, values);
    }

    private static String requireName(String value) {
        String result = Objects.requireNonNull(value, "name");
        if (result.isBlank() || result.length() > 128) {
            throw new IllegalArgumentException("Function parameter name is invalid");
        }
        return result;
    }
}
