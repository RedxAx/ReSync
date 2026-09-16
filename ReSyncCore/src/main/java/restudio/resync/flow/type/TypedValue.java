package restudio.resync.flow.type;

import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.identity.ServerResourceLocator;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class TypedValue {
    private final TypeExpr type;
    private final State state;
    private final String variantId;
    private final Object value;
    private final ServerResourceLocator locator;
    private final Map<String, Object> unknown;

    public TypedValue(TypeExpr type, State state, String variantId, Object value, ServerResourceLocator locator) {
        this(type, state, variantId, value, locator, Map.of());
    }

    public TypedValue(TypeExpr type, State state, String variantId, Object value, ServerResourceLocator locator, Map<String, ?> unknown) {
        this.type = Objects.requireNonNull(type, "type");
        this.state = Objects.requireNonNull(state, "state");
        this.variantId = variantId == null ? null : TypeReference.requireLocalId(variantId);
        this.value = value == null ? null : freeze(value);
        this.locator = locator;
        this.unknown = TypeSupport.unknown(unknown, "typed value unknown data");
        validate();
    }

    public static TypedValue absent(TypeExpr type) {
        return new TypedValue(type, State.ABSENT, null, null, null);
    }

    public static TypedValue absent(TypeExpr type, Map<String, ?> unknown) {
        return new TypedValue(type, State.ABSENT, null, null, null, unknown);
    }

    public static TypedValue nullValue(TypeExpr type) {
        return new TypedValue(type, State.NULL, null, null, null);
    }

    public static TypedValue nullValue(TypeExpr type, Map<String, ?> unknown) {
        return new TypedValue(type, State.NULL, null, null, null, unknown);
    }

    public static TypedValue value(TypeExpr type, Object value) {
        return new TypedValue(type, State.VALUE, null, value, null);
    }

    public static TypedValue value(TypeExpr type, Object value, Map<String, ?> unknown) {
        return new TypedValue(type, State.VALUE, null, value, null, unknown);
    }

    public static TypedValue unionValue(TypeExpr.UnionType type, String variantId, Object value) {
        return new TypedValue(type, State.VALUE, variantId, value, null);
    }

    public static TypedValue unionValue(TypeExpr.UnionType type, String variantId, Object value, Map<String, ?> unknown) {
        return new TypedValue(type, State.VALUE, variantId, value, null, unknown);
    }

    public static TypedValue locator(TypeExpr type, ServerResourceLocator locator) {
        return new TypedValue(type, State.LOCATOR, null, null, locator);
    }

    public static TypedValue locator(TypeExpr type, ServerResourceLocator locator, Map<String, ?> unknown) {
        return new TypedValue(type, State.LOCATOR, null, null, locator, unknown);
    }

    public static TypedValue unionLocator(TypeExpr.UnionType type, String variantId, ServerResourceLocator locator) {
        return new TypedValue(type, State.LOCATOR, variantId, null, locator);
    }

    public static TypedValue unionLocator(TypeExpr.UnionType type, String variantId, ServerResourceLocator locator, Map<String, ?> unknown) {
        return new TypedValue(type, State.LOCATOR, variantId, null, locator, unknown);
    }

    public static TypedValue opaque(TypeExpr.OpaqueType type, Object rawValue) {
        return new TypedValue(type, State.OPAQUE, null, rawValue, null);
    }

    public static TypedValue opaque(TypeExpr.OpaqueType type, Object rawValue, Map<String, ?> unknown) {
        return new TypedValue(type, State.OPAQUE, null, rawValue, null, unknown);
    }

    public static TypedValue unionOpaque(TypeExpr.UnionType type, String variantId, Object rawValue) {
        return new TypedValue(type, State.OPAQUE, variantId, rawValue, null);
    }

    public static TypedValue unionOpaque(TypeExpr.UnionType type, String variantId, Object rawValue, Map<String, ?> unknown) {
        return new TypedValue(type, State.OPAQUE, variantId, rawValue, null, unknown);
    }

    public TypeExpr type() {
        return type;
    }

    public State state() {
        return state;
    }

    public String variantId() {
        return variantId;
    }

    public Object value() {
        return value;
    }

    public ServerResourceLocator locator() {
        return locator;
    }

    public Map<String, Object> unknown() {
        return unknown;
    }

    public boolean hasValue() {
        return state == State.VALUE || state == State.OPAQUE;
    }

    public String canonicalJson() {
        return CanonicalJson.canonicalize(canonicalValue());
    }

    public Map<String, Object> canonicalValue() {
        var fields = new LinkedHashMap<String, Object>();
        if (locator != null) {
            fields.put("locator", locator.canonicalValue());
        }
        fields.put("state", state.wireName());
        fields.put("type", type.canonicalValue());
        if (hasValue()) {
            fields.put("value", TypeSupport.canonical(value));
        }
        if (variantId != null) {
            fields.put("variantId", variantId);
        }
        return TypeSupport.merge(unknown, fields);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof TypedValue that)) {
            return false;
        }
        return type.equals(that.type) && state == that.state && Objects.equals(variantId, that.variantId) && Objects.equals(value, that.value) && Objects.equals(locator, that.locator) && unknown.equals(that.unknown);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, state, variantId, value, locator, unknown);
    }

    @Override
    public String toString() {
        return canonicalJson();
    }

    private void validate() {
        boolean union = type instanceof TypeExpr.UnionType;
        boolean taggedState = state == State.VALUE || state == State.LOCATOR || state == State.OPAQUE;
        TypeExpr selectedType = type;
        if (union && taggedState) {
            if (variantId == null) {
                throw new IllegalArgumentException("A union value needs a variantId");
            }
            selectedType = ((TypeExpr.UnionType) type).variant(variantId).type();
        } else if (!union && variantId != null) {
            throw new IllegalArgumentException("Only union values may carry a variantId");
        }
        switch (state) {
            case ABSENT, NULL -> requireEmpty();
            case VALUE -> {
                if (value == null || locator != null || selectedType instanceof TypeExpr.OpaqueType || !matchesValue(selectedType, value)) {
                    throw new IllegalArgumentException("A value state needs value material and no locator");
                }
            }
            case LOCATOR -> {
                if (locator == null || value != null || !resourceLike(selectedType) || !matchesLocator(selectedType, locator)) {
                    throw new IllegalArgumentException("A locator state needs a resource type and no value material");
                }
            }
            case OPAQUE -> {
                if (locator != null || !(selectedType instanceof TypeExpr.OpaqueType)) {
                    throw new IllegalArgumentException("An opaque state needs opaque value material and no locator");
                }
            }
        }
    }

    private void requireEmpty() {
        if (value != null || locator != null || variantId != null) {
            throw new IllegalArgumentException("Absent and null states cannot carry value, locator, or variant material");
        }
    }

    private static boolean resourceLike(TypeExpr expression) {
        return switch (expression) {
            case TypeExpr.ResourceType ignored -> true;
            case TypeExpr.OptionalType optional -> resourceLike(optional.element());
            default -> false;
        };
    }

    private static boolean matchesValue(TypeExpr expression, Object value) {
        return switch (expression) {
            case TypeExpr.ResourceType resource -> value instanceof ServerResourceLocator locator && matchesLocator(resource, locator);
            case TypeExpr.OptionalType optional -> matchesValue(optional.element(), value);
            case TypeExpr.ListType list -> value instanceof List<?> values && values.stream()
                .allMatch(entry -> entry == null ? allowsNull(list.element()) : matchesValue(list.element(), entry));
            case TypeExpr.MapType map -> value instanceof Map<?, ?> values && values.entrySet().stream().allMatch(entry -> entry.getKey() != null
                && matchesValue(map.key(), entry.getKey())
                && (entry.getValue() == null ? allowsNull(map.value()) : matchesValue(map.value(), entry.getValue())));
            case TypeExpr.TupleType tuple -> value instanceof List<?> values && values.size() == tuple.elements().size() && matchesTuple(tuple.elements(), values);
            case TypeExpr.UnionType union -> false;
            case TypeExpr.ResultType result -> matchesResult(result, value);
            case TypeExpr.Named named -> matchesNamed(named, value);
            case TypeExpr.OpaqueType ignored -> false;
        };
    }

    private static boolean matchesResult(TypeExpr.ResultType result, Object value) {
        if (!(value instanceof Map<?, ?> values) || !(values.get("success") instanceof Boolean success) || !values.containsKey("value")) {
            return false;
        }
        Object branchValue = values.get("value");
        TypeExpr branchType = success ? result.success() : result.failure();
        return branchValue == null ? allowsNull(branchType) : matchesValue(branchType, branchValue);
    }

    private static boolean matchesNamed(TypeExpr.Named named, Object value) {
        if ("builtin".equals(named.reference().ownerId()) && named.arguments().isEmpty()) {
            return switch (named.reference().localId()) {
                case "any" -> true;
                case "string" -> value instanceof String;
                case "boolean" -> value instanceof Boolean;
                case "integer" -> value instanceof Byte || value instanceof Short || value instanceof Integer
                    || value instanceof Long || value instanceof BigInteger;
                case "number" -> value instanceof Byte || value instanceof Short || value instanceof Integer
                    || value instanceof Long || value instanceof BigInteger || value instanceof BigDecimal;
                case "uuid" -> value instanceof UUID;
                default -> !containsResource(named) || value instanceof Map<?, ?> || value instanceof List<?>
                    || value instanceof ServerResourceLocator locator && matchesNestedResource(named, locator);
            };
        }
        return !containsResource(named) || value instanceof Map<?, ?> || value instanceof List<?> || value instanceof ServerResourceLocator locator
            && matchesNestedResource(named, locator);
    }

    private static boolean matchesTuple(List<TypeExpr> types, List<?> values) {
        for (int index = 0; index < types.size(); index++) {
            Object value = values.get(index);
            if (value == null ? !allowsNull(types.get(index)) : !matchesValue(types.get(index), value)) {
                return false;
            }
        }
        return true;
    }

    private static boolean containsResource(TypeExpr expression) {
        return switch (expression) {
            case TypeExpr.ResourceType ignored -> true;
            case TypeExpr.Named named -> named.arguments().stream().anyMatch(TypedValue::containsResource);
            case TypeExpr.OptionalType optional -> containsResource(optional.element());
            case TypeExpr.ListType list -> containsResource(list.element());
            case TypeExpr.MapType map -> containsResource(map.key()) || containsResource(map.value());
            case TypeExpr.TupleType tuple -> tuple.elements().stream().anyMatch(TypedValue::containsResource);
            case TypeExpr.ResultType result -> containsResource(result.success()) || containsResource(result.failure());
            case TypeExpr.UnionType union -> union.variants().stream().map(TypeExpr.UnionVariant::type).anyMatch(TypedValue::containsResource);
            case TypeExpr.OpaqueType ignored -> false;
        };
    }

    private static boolean allowsNull(TypeExpr expression) {
        return !containsResource(expression) || expression instanceof TypeExpr.OptionalType;
    }

    private static boolean matchesNestedResource(TypeExpr expression, ServerResourceLocator locator) {
        return switch (expression) {
            case TypeExpr.ResourceType resource -> matchesLocator(resource, locator);
            case TypeExpr.Named named -> named.arguments().stream().anyMatch(argument -> matchesNestedResource(argument, locator));
            case TypeExpr.OptionalType optional -> matchesNestedResource(optional.element(), locator);
            case TypeExpr.ListType list -> matchesNestedResource(list.element(), locator);
            case TypeExpr.MapType map -> matchesNestedResource(map.key(), locator) || matchesNestedResource(map.value(), locator);
            case TypeExpr.TupleType tuple -> tuple.elements().stream().anyMatch(argument -> matchesNestedResource(argument, locator));
            case TypeExpr.ResultType result -> matchesNestedResource(result.success(), locator) || matchesNestedResource(result.failure(), locator);
            case TypeExpr.UnionType union -> union.variants().stream().map(TypeExpr.UnionVariant::type).anyMatch(argument -> matchesNestedResource(argument, locator));
            case TypeExpr.OpaqueType ignored -> false;
        };
    }

    private static boolean matchesLocator(TypeExpr expression, ServerResourceLocator locator) {
        return switch (expression) {
            case TypeExpr.ResourceType resource -> resource.resourceType().ownerId().equals(locator.type().owner().value())
                && resource.resourceType().localId().equals(locator.type().id().value());
            case TypeExpr.OptionalType optional -> matchesLocator(optional.element(), locator);
            default -> false;
        };
    }

    private static Object freeze(Object value) {
        if (value instanceof String || value instanceof Boolean || value instanceof UUID || value instanceof BigInteger || value instanceof BigDecimal || value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long || value instanceof ServerResourceLocator) {
            return value;
        }
        if (value instanceof Float floatValue) {
            if (!Float.isFinite(floatValue)) {
                throw new IllegalArgumentException("Non-finite number");
            }
            return BigDecimal.valueOf(floatValue.doubleValue());
        }
        if (value instanceof Double doubleValue) {
            if (!Double.isFinite(doubleValue)) {
                throw new IllegalArgumentException("Non-finite number");
            }
            return BigDecimal.valueOf(doubleValue);
        }
        if (value instanceof Character character) {
            return character;
        }
        if (value instanceof List<?> list) {
            var copy = new ArrayList<Object>(list.size());
            for (var entry : list) {
                copy.add(entry == null ? null : freeze(entry));
            }
            return Collections.unmodifiableList(copy);
        }
        if (value instanceof Map<?, ?> map) {
            var copy = new LinkedHashMap<String, Object>();
            for (var entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new IllegalArgumentException("Typed object keys must be strings");
                }
                copy.put(key, entry.getValue() == null ? null : freeze(entry.getValue()));
            }
            return Collections.unmodifiableMap(copy);
        }
        throw new IllegalArgumentException("Unsupported typed value");
    }

    public enum State {
        ABSENT("absent"),
        NULL("null"),
        VALUE("value"),
        LOCATOR("locator"),
        OPAQUE("opaque");

        private final String wireName;

        State(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }
    }
}
