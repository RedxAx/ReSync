package restudio.resync.flow.graph;

import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypedValue;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public class GraphVariable {
    private final UUID variableId;
    private final String name;
    private final TypeExpr type;
    private final TypedValue value;
    private final OpaqueData unknown;

    public GraphVariable(UUID variableId, String name, TypeExpr type, TypedValue value, OpaqueData unknown) {
        this.variableId = Objects.requireNonNull(variableId, "variableId");
        this.name = requireName(name);
        this.type = Objects.requireNonNull(type, "type");
        if (value != null && !type.equals(value.type())) {
            throw new IllegalArgumentException("Variable value type must match the variable type");
        }
        this.value = value;
        this.unknown = unknown != null ? unknown : OpaqueData.empty();
        this.unknown.rejectKnownFields("variableId", "name", "type", "value");
    }

    public GraphVariable(UUID variableId, String name, TypeExpr type) {
        this(variableId, name, type, null, OpaqueData.empty());
    }

    public GraphVariable(UUID variableId, String name, TypeExpr type, TypedValue value) {
        this(variableId, name, type, value, OpaqueData.empty());
    }

    public UUID variableId() {
        return variableId;
    }

    public String name() {
        return name;
    }

    public TypeExpr type() {
        return type;
    }

    public TypedValue value() {
        return value;
    }

    public OpaqueData unknown() {
        return unknown;
    }

    Map<String, Object> canonicalValue() {
        LinkedHashMap<String, Object> values = new LinkedHashMap<>();
        values.put("variableId", variableId.toString());
        values.put("name", name);
        values.put("type", type.canonicalValue());
        if (value != null) {
            values.put("value", CanonicalJson.parse(value.canonicalJson()));
        }
        return OpaqueData.mergeKnownFields(unknown, values);
    }

    private static String requireName(String value) {
        String normalized = Objects.requireNonNull(value, "name").trim();
        if (normalized.isEmpty() || normalized.length() > 128) {
            throw new IllegalArgumentException("Variable name must contain between 1 and 128 characters");
        }
        return normalized;
    }
}
