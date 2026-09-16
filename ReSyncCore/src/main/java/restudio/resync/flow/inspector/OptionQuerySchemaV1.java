package restudio.resync.flow.inspector;

import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypedValue;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

public record OptionQuerySchemaV1(ResourceSlot resource, Map<String, Field> context, Map<String, Field> dependencies,
                                  Map<String, Object> unknown) {
    public static final int SCHEMA_VERSION = 1;

    public OptionQuerySchemaV1 {
        context = fields(context, "context");
        dependencies = fields(dependencies, "dependencies");
        unknown = InspectorSupport.map(unknown, "option query schema unknown data");
        InspectorSupport.rejectKnownKeys(unknown, "option query schema unknown data", "schemaVersion", "resource", "context",
            "dependencies");
    }

    public OptionQuerySchemaV1(ResourceSlot resource, Map<String, Field> context, Map<String, Field> dependencies) {
        this(resource, context, dependencies, Map.of());
    }

    public static OptionQuerySchemaV1 empty() {
        return new OptionQuerySchemaV1(null, Map.of(), Map.of(), Map.of());
    }

    public static OptionQuerySchemaV1 requiredResource(TypeExpr.ResourceType type) {
        return new OptionQuerySchemaV1(new ResourceSlot(type, true), Map.of(), Map.of(), Map.of());
    }

    public static OptionQuerySchemaV1 fromLegacy(Object value) {
        Objects.requireNonNull(value, "legacy option query schema");
        if (value instanceof OptionQuerySchemaV1 schema) {
            return schema;
        }
        if (value instanceof Map<?, ?> map && map.isEmpty()) {
            return empty();
        }
        if (value instanceof TypeExpr.ResourceType resourceType) {
            return requiredResource(resourceType);
        }
        throw new IllegalArgumentException("Legacy option query schema must be an empty map or resource type");
    }

    public Map<String, Object> canonicalValue() {
        LinkedHashMap<String, Object> known = new LinkedHashMap<>();
        known.put("context", canonicalFields(context));
        known.put("dependencies", canonicalFields(dependencies));
        known.put("resource", resource == null ? null : resource.canonicalValue());
        known.put("schemaVersion", SCHEMA_VERSION);
        return merge(unknown, known);
    }

    public Normalized normalize(ServerId serverId, ServerResourceLocator resourceValue, Map<String, TypedValue> contextValues,
                                Map<String, TypedValue> dependencyValues) {
        ServerId server = Objects.requireNonNull(serverId, "serverId");
        ServerResourceLocator normalizedResource = normalizeResource(server, resourceValue);
        Map<String, TypedValue> normalizedContext = normalizeFields(server, context, contextValues, "context");
        Map<String, TypedValue> normalizedDependencies = normalizeFields(server, dependencies, dependencyValues, "dependencies");
        return new Normalized(normalizedResource, normalizedContext, normalizedDependencies);
    }

    private ServerResourceLocator normalizeResource(ServerId serverId, ServerResourceLocator value) {
        if (resource == null) {
            if (value != null) {
                throw new IllegalArgumentException("Option query resource is not declared by its schema");
            }
            return null;
        }
        if (value == null) {
            if (resource.required()) {
                throw new IllegalArgumentException("Option query resource is required");
            }
            return null;
        }
        requireResource(serverId, resource.type(), value, "resource");
        return value;
    }

    private static Map<String, TypedValue> normalizeFields(ServerId serverId, Map<String, Field> schema,
                                                            Map<String, TypedValue> supplied, String name) {
        Objects.requireNonNull(supplied, name);
        for (Map.Entry<String, TypedValue> suppliedEntry : supplied.entrySet()) {
            String key = suppliedEntry.getKey();
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("Option query " + name + " keys must be non-blank");
            }
            if (suppliedEntry.getValue() == null) {
                throw new IllegalArgumentException("Option query " + name + " values must not be null: " + key);
            }
            if (!schema.containsKey(key)) {
                throw new IllegalArgumentException("Option query " + name + " contains undeclared field: " + key);
            }
        }
        LinkedHashMap<String, TypedValue> result = new LinkedHashMap<>();
        for (Map.Entry<String, Field> entry : schema.entrySet()) {
            String key = entry.getKey();
            Field field = entry.getValue();
            TypedValue suppliedValue = supplied.containsKey(key) ? supplied.get(key) : null;
            if (suppliedValue != null) {
                field.validate(suppliedValue, name + "." + key);
            }
            TypedValue value = suppliedValue == null || suppliedValue.state() == TypedValue.State.ABSENT
                ? field.defaultValue() : suppliedValue;
            if (value == null) {
                if (field.required()) {
                    throw new IllegalArgumentException("Option query " + name + " field is required: " + key);
                }
                continue;
            }
            field.validate(value, name + "." + key);
            requireServer(serverId, value, name + "." + key);
            result.put(key, value);
        }
        return Collections.unmodifiableMap(result);
    }

    private static Map<String, Field> fields(Map<String, Field> values, String name) {
        Objects.requireNonNull(values, name);
        TreeMap<String, Field> sorted = new TreeMap<>();
        for (Map.Entry<String, Field> entry : values.entrySet()) {
            String key = Objects.requireNonNull(entry.getKey(), name + " key");
            if (key.isBlank()) {
                throw new IllegalArgumentException(name + " keys must be non-blank");
            }
            sorted.put(key, Objects.requireNonNull(entry.getValue(), name + " field"));
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(sorted));
    }

    private static Map<String, Object> canonicalFields(Map<String, Field> values) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        values.forEach((key, value) -> result.put(key, value.canonicalValue()));
        return Collections.unmodifiableMap(result);
    }

    private static Map<String, Object> merge(Map<String, Object> unknown, Map<String, Object> known) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>(unknown);
        result.putAll(known);
        return Collections.unmodifiableMap(result);
    }

    private static void requireServer(ServerId serverId, TypedValue value, String name) {
        TypeExpr selected = selectedType(value);
        switch (value.state()) {
            case ABSENT, NULL -> {
            }
            case OPAQUE -> requireOpaqueData(value.value(), name);
            case LOCATOR -> requireLocator(serverId, selected, value.locator(), name);
            case VALUE -> requireMaterial(serverId, selected, value.value(), name);
        }
    }

    private static TypeExpr selectedType(TypedValue value) {
        if (value.type() instanceof TypeExpr.UnionType union && value.state() != TypedValue.State.ABSENT
            && value.state() != TypedValue.State.NULL) {
            return union.variant(value.variantId()).type();
        }
        return value.type();
    }

    private static void requireLocator(ServerId serverId, TypeExpr type, ServerResourceLocator value, String name) {
        TypeExpr current = type;
        while (current instanceof TypeExpr.OptionalType optional) {
            current = optional.element();
        }
        if (!(current instanceof TypeExpr.ResourceType resourceType)) {
            throw new IllegalArgumentException("Option query " + name + " locator does not have a resource type");
        }
        requireResource(serverId, resourceType, value, name);
    }

    private static void requireMaterial(ServerId serverId, TypeExpr type, Object value, String name) {
        if (value == null) {
            if (!(type instanceof TypeExpr.OptionalType)) {
                throw new IllegalArgumentException("Option query " + name + " can be null only when its type is explicitly optional");
            }
            return;
        }
        switch (type) {
            case TypeExpr.ResourceType resourceType -> {
                if (!(value instanceof ServerResourceLocator locator)) {
                    throw new IllegalArgumentException("Option query " + name + " resource material must be a locator");
                }
                requireResource(serverId, resourceType, locator, name);
            }
            case TypeExpr.OptionalType optional -> requireMaterial(serverId, optional.element(), value, name);
            case TypeExpr.ListType list -> {
                if (!(value instanceof List<?> values)) {
                    throw new IllegalArgumentException("Option query " + name + " list material is invalid");
                }
                for (int index = 0; index < values.size(); index++) {
                    requireMaterial(serverId, list.element(), values.get(index), name + "[" + index + "]");
                }
            }
            case TypeExpr.MapType map -> {
                if (!(value instanceof Map<?, ?> values)) {
                    throw new IllegalArgumentException("Option query " + name + " map material is invalid");
                }
                for (Map.Entry<?, ?> entry : values.entrySet()) {
                    requireMaterial(serverId, map.key(), entry.getKey(), name + ".key");
                    requireMaterial(serverId, map.value(), entry.getValue(), name + ".value");
                }
            }
            case TypeExpr.TupleType tuple -> {
                if (!(value instanceof List<?> values) || values.size() != tuple.elements().size()) {
                    throw new IllegalArgumentException("Option query " + name + " tuple material is invalid");
                }
                for (int index = 0; index < values.size(); index++) {
                    requireMaterial(serverId, tuple.elements().get(index), values.get(index), name + "[" + index + "]");
                }
            }
            case TypeExpr.ResultType result -> {
                if (!(value instanceof Map<?, ?> values) || !(values.get("success") instanceof Boolean success)
                    || !values.containsKey("value")) {
                    throw new IllegalArgumentException("Option query " + name + " result material is invalid");
                }
                requireMaterial(serverId, success ? result.success() : result.failure(), values.get("value"), name + ".value");
            }
            case TypeExpr.Named named -> requireNamedMaterial(named, value, name);
            case TypeExpr.UnionType ignored -> throw new IllegalArgumentException(
                "Option query " + name + " nested union material requires an explicit typed value boundary");
            case TypeExpr.OpaqueType ignored -> {
            }
        }
    }

    private static void requireNamedMaterial(TypeExpr.Named type, Object value, String name) {
        requireBuiltinMaterial(type, value, name);
    }

    private static void requireBuiltinMaterial(TypeExpr.Named type, Object value, String name) {
        if (!type.arguments().isEmpty() || !"builtin".equals(type.reference().ownerId())) {
            throw new IllegalArgumentException("Option query " + name + " named material requires an explicit opaque type");
        }
        boolean valid = switch (type.reference().localId()) {
            case "string" -> value instanceof String;
            case "boolean" -> value instanceof Boolean;
            case "integer" -> value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long
                || value instanceof BigInteger;
            case "number" -> value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long
                || value instanceof BigInteger || value instanceof BigDecimal;
            case "uuid" -> value instanceof UUID;
            default -> false;
        };
        if (!valid) {
            throw new IllegalArgumentException("Option query " + name + " named material is not a supported builtin value");
        }
    }

    private static void requireOpaqueData(Object value, String name) {
        if (value instanceof ServerResourceLocator) {
            throw new IllegalArgumentException("Option query " + name + " opaque data cannot carry a typed resource locator");
        }
        if (value instanceof List<?> values) {
            for (int index = 0; index < values.size(); index++) {
                requireOpaqueData(values.get(index), name + "[" + index + "]");
            }
        } else if (value instanceof Map<?, ?> values) {
            for (Map.Entry<?, ?> entry : values.entrySet()) {
                requireOpaqueData(entry.getValue(), name + "." + entry.getKey());
            }
        }
    }

    private static void requireResource(ServerId serverId, TypeExpr.ResourceType type, ServerResourceLocator value, String name) {
        if (!serverId.equals(value.serverId())) {
            throw new IllegalArgumentException("Option query " + name + " contains a resource from another server");
        }
        if (!matches(type, value)) {
            throw new IllegalArgumentException("Option query " + name + " resource type does not match its schema");
        }
    }

    private static boolean matches(TypeExpr.ResourceType type, ServerResourceLocator value) {
        return type.resourceType().ownerId().equals(value.type().owner().value())
            && type.resourceType().localId().equals(value.type().id().value());
    }

    public record ResourceSlot(TypeExpr.ResourceType type, boolean required, Map<String, Object> unknown) {
        public ResourceSlot {
            type = Objects.requireNonNull(type, "resource type");
            unknown = InspectorSupport.map(unknown, "option query resource unknown data");
            InspectorSupport.rejectKnownKeys(unknown, "option query resource unknown data", "type", "required");
        }

        public ResourceSlot(TypeExpr.ResourceType type, boolean required) {
            this(type, required, Map.of());
        }

        public Map<String, Object> canonicalValue() {
            return merge(unknown, Map.of("required", required, "type", type.canonicalValue()));
        }
    }

    public record Field(TypeExpr type, boolean required, TypedValue defaultValue, Map<String, Object> unknown) {
        public Field {
            type = Objects.requireNonNull(type, "field type");
            if (defaultValue != null) {
                if (!type.equals(defaultValue.type())) {
                    throw new IllegalArgumentException("Option query field default type does not match its schema");
                }
                if (defaultValue.state() == TypedValue.State.ABSENT) {
                    throw new IllegalArgumentException("Option query field defaults cannot be absent");
                }
                requireExplicitOptionalNull(type, defaultValue, "Option query field default");
            }
            unknown = InspectorSupport.map(unknown, "option query field unknown data");
            InspectorSupport.rejectKnownKeys(unknown, "option query field unknown data", "type", "required", "defaultValue");
        }

        public Field(TypeExpr type, boolean required, TypedValue defaultValue) {
            this(type, required, defaultValue, Map.of());
        }

        public Field(TypeExpr type, boolean required) {
            this(type, required, null, Map.of());
        }

        public Map<String, Object> canonicalValue() {
            LinkedHashMap<String, Object> known = new LinkedHashMap<>();
            known.put("defaultValue", defaultValue == null ? null : defaultValue.canonicalValue());
            known.put("required", required);
            known.put("type", type.canonicalValue());
            return merge(unknown, known);
        }

        private void validate(TypedValue value, String name) {
            if (!type.equals(value.type())) {
                throw new IllegalArgumentException("Option query " + name + " type does not match its schema");
            }
            requireExplicitOptionalNull(type, value, "Option query " + name);
        }

        private static void requireExplicitOptionalNull(TypeExpr type, TypedValue value, String name) {
            if (value.state() == TypedValue.State.NULL && !(type instanceof TypeExpr.OptionalType)) {
                throw new IllegalArgumentException(name + " can be null only when its type is explicitly optional");
            }
        }
    }

    public record Normalized(ServerResourceLocator resource, Map<String, TypedValue> context,
                             Map<String, TypedValue> dependencies) {
        public Normalized {
            context = immutableValues(context, "context");
            dependencies = immutableValues(dependencies, "dependencies");
        }

        private static Map<String, TypedValue> immutableValues(Map<String, TypedValue> values, String name) {
            Objects.requireNonNull(values, name);
            LinkedHashMap<String, TypedValue> copy = new LinkedHashMap<>();
            values.forEach((key, value) -> copy.put(Objects.requireNonNull(key, name + " key"),
                Objects.requireNonNull(value, name + " value")));
            return Collections.unmodifiableMap(copy);
        }
    }
}
