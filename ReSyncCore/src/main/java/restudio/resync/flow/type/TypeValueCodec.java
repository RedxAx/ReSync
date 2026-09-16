package restudio.resync.flow.type;

import restudio.resync.contract.canonical.CanonicalCodec;
import restudio.resync.contract.canonical.JsonValue;
import restudio.resync.contract.canonical.JsonValue.JsonArray;
import restudio.resync.contract.canonical.JsonValue.JsonBoolean;
import restudio.resync.contract.canonical.JsonValue.JsonNull;
import restudio.resync.contract.canonical.JsonValue.JsonObject;
import restudio.resync.contract.canonical.JsonValue.JsonString;
import restudio.resync.contract.identity.IdentityCodec;
import restudio.resync.flow.identity.ServerResourceLocator;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

public final class TypeValueCodec implements CanonicalCodec<TypedValue> {
    public static final TypeValueCodec INSTANCE = new TypeValueCodec();

    private static final Set<String> TYPED_VALUE_FIELDS = Set.of("state", "type", "variantId", "value", "locator");
    private static final Set<String> TYPE_REFERENCE_FIELDS = Set.of("ownerId", "localId");
    private static final Set<String> UNION_VARIANT_FIELDS = Set.of("variantId", "type", "displayName", "description");

    private TypeValueCodec() {
    }

    public static TypeValueCodec instance() {
        return INSTANCE;
    }

    @Override
    public JsonObject encode(TypedValue value) {
        Objects.requireNonNull(value, "Typed value is required");
        return requireObject(JsonValue.fromJava(value.canonicalValue()), "Typed value");
    }

    @Override
    public TypedValue decode(JsonValue value) {
        JsonObject object = requireObject(value, "Typed value");
        TypeExpr type = decodeType(required(object, "type"));
        TypedValue.State state = parseState(string(required(object, "state"), "typedValue.state"));
        String variantId = optionalString(object, "variantId", Function.identity());
        boolean hasValue = object.contains("value");
        JsonValue rawValueJson = hasValue ? object.value("value") : null;
        if (hasValue && rawValueJson instanceof JsonNull && state != TypedValue.State.OPAQUE) {
            throw new IllegalArgumentException("Only opaque typed values may carry a null value field");
        }
        Object rawValue = hasValue ? Objects.requireNonNull(rawValueJson, "typedValue.value").toJava() : null;
        ServerResourceLocator locator = object.contains("locator") ? IdentityCodec.decodeLocator(required(object, "locator")) : null;
        TypeExpr selectedType = selectType(type, variantId, state);
        Object decodedValue = hasValue ? decodeRawValue(selectedType, rawValue) : null;
        TypedValue decoded = new TypedValue(type, state, variantId, decodedValue, locator, unknown(object, TYPED_VALUE_FIELDS));
        return decoded;
    }

    public JsonObject encodeType(TypeExpr value) {
        Objects.requireNonNull(value, "Type expression is required");
        return requireObject(JsonValue.fromJava(value.canonicalValue()), "Type expression");
    }

    public TypeExpr decodeType(JsonValue value) {
        JsonObject object = requireObject(value, "type expression");
        String kind = string(required(object, "kind"), "type.kind");
        Set<String> known = knownTypeFields(kind);
        Map<String, Object> unknown = unknown(object, known);
        TypeExpr decoded = switch (kind) {
            case "named" -> new TypeExpr.Named(decodeTypeReference(required(object, "type")), decodeTypes(required(object, "arguments"), "type.arguments"), unknown);
            case "optional" -> new TypeExpr.OptionalType(decodeType(required(object, "element")), unknown);
            case "list" -> new TypeExpr.ListType(decodeType(required(object, "element")), unknown);
            case "map" -> new TypeExpr.MapType(decodeType(required(object, "key")), decodeType(required(object, "value")), unknown);
            case "tuple" -> new TypeExpr.TupleType(decodeTypes(required(object, "elements"), "type.elements"), unknown);
            case "result" -> new TypeExpr.ResultType(decodeType(required(object, "success")), decodeType(required(object, "failure")), unknown);
            case "resource" -> new TypeExpr.ResourceType(decodeTypeReference(required(object, "resourceType")), unknown);
            case "union" -> new TypeExpr.UnionType(decodeVariants(required(object, "variants")), unknown);
            case "opaque" -> {
                if (!bool(required(object, "raw"), "type.raw")) {
                    throw new IllegalArgumentException("Opaque type.raw must be true");
                }
                yield new TypeExpr.OpaqueType(decodeTypeReference(required(object, "type")), unknown);
            }
            default -> throw new IllegalArgumentException("Unknown type expression kind: " + kind);
        };
        return decoded;
    }

    public JsonObject encodeTypeReference(TypeReference value) {
        Objects.requireNonNull(value, "Type reference is required");
        return requireObject(JsonValue.fromJava(value.canonicalValue()), "Type reference");
    }

    public TypeReference decodeTypeReference(JsonValue value) {
        JsonObject object = requireObject(value, "type reference");
        TypeReference decoded = new TypeReference(string(required(object, "ownerId"), "type.ownerId"),
            string(required(object, "localId"), "type.localId"), unknown(object, TYPE_REFERENCE_FIELDS));
        return decoded;
    }

    private static TypedValue.State parseState(String value) {
        return switch (value) {
            case "absent" -> TypedValue.State.ABSENT;
            case "null" -> TypedValue.State.NULL;
            case "value" -> TypedValue.State.VALUE;
            case "locator" -> TypedValue.State.LOCATOR;
            case "opaque" -> TypedValue.State.OPAQUE;
            default -> throw new IllegalArgumentException("Invalid typedValue.state: " + value);
        };
    }

    private static TypeExpr selectType(TypeExpr type, String variantId, TypedValue.State state) {
        if (type instanceof TypeExpr.UnionType union && state != TypedValue.State.ABSENT && state != TypedValue.State.NULL) {
            if (variantId == null) {
                throw new IllegalArgumentException("Union typed values require variantId");
            }
            return union.variant(variantId).type();
        }
        if (!(type instanceof TypeExpr.UnionType) && variantId != null) {
            throw new IllegalArgumentException("Non-union typed values cannot carry variantId");
        }
        return type;
    }

    private static Object decodeRawValue(TypeExpr type, Object raw) {
        if (raw == null) {
            return null;
        }
        return switch (type) {
            case TypeExpr.ResourceType ignored -> IdentityCodec.decodeLocator(JsonValue.fromJava(raw));
            case TypeExpr.OptionalType optional -> decodeRawValue(optional.element(), raw);
            case TypeExpr.ListType list -> decodeListRaw(raw, list.element());
            case TypeExpr.MapType map -> decodeMapRaw(raw, map);
            case TypeExpr.TupleType tuple -> decodeTupleRaw(raw, tuple);
            case TypeExpr.ResultType result -> decodeResultRaw(raw, result);
            case TypeExpr.Named named -> decodeNamedRaw(raw, named);
            case TypeExpr.UnionType ignored -> throw new IllegalArgumentException("Union branch type is unresolved");
            case TypeExpr.OpaqueType ignored -> raw;
        };
    }

    private static Object decodeNamedRaw(Object raw, TypeExpr.Named type) {
        if ("builtin".equals(type.reference().ownerId()) && type.arguments().isEmpty()) {
            return switch (type.reference().localId()) {
                case "uuid" -> raw instanceof String text ? uuid(text, "typedValue.value") : fail("typedValue.uuid must be a string");
                case "integer" -> integral(raw, "typedValue.integer");
                case "number" -> decimal(raw, "typedValue.number");
                default -> raw;
            };
        }
        if (raw instanceof Map<?, ?> map && containsResource(type) && looksLikeLocator(map)) {
            return IdentityCodec.decodeLocator(JsonValue.fromJava(map));
        }
        return raw;
    }

    private static boolean looksLikeLocator(Map<?, ?> value) {
        return value.containsKey("serverId") && value.containsKey("type") && value.containsKey("id");
    }

    private static List<Object> decodeListRaw(Object raw, TypeExpr element) {
        if (!(raw instanceof List<?> list)) {
            throw new IllegalArgumentException("typedValue list must be an array");
        }
        ArrayList<Object> result = new ArrayList<>(list.size());
        for (Object value : list) {
            result.add(value == null ? null : decodeRawValue(element, value));
        }
        return Collections.unmodifiableList(result);
    }

    private static Map<String, Object> decodeMapRaw(Object raw, TypeExpr.MapType type) {
        if (!(raw instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("typedValue map must be an object");
        }
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException("typedValue map keys must be strings");
            }
            Object decodedKey = decodeRawValue(type.key(), key);
            if (!(decodedKey instanceof String stringKey)) {
                throw new IllegalArgumentException("typedValue map key type must decode to a string");
            }
            Object decodedValue = entry.getValue() == null ? null : decodeRawValue(type.value(), entry.getValue());
            if (result.put(stringKey, decodedValue) != null) {
                throw new IllegalArgumentException("Duplicate typedValue map key: " + stringKey);
            }
        }
        return Collections.unmodifiableMap(result);
    }

    private static List<Object> decodeTupleRaw(Object raw, TypeExpr.TupleType type) {
        if (!(raw instanceof List<?> list) || list.size() != type.elements().size()) {
            throw new IllegalArgumentException("typedValue tuple shape is invalid");
        }
        ArrayList<Object> result = new ArrayList<>(list.size());
        for (int index = 0; index < list.size(); index++) {
            Object value = list.get(index);
            result.add(value == null ? null : decodeRawValue(type.elements().get(index), value));
        }
        return Collections.unmodifiableList(result);
    }

    private static Map<String, Object> decodeResultRaw(Object raw, TypeExpr.ResultType type) {
        if (!(raw instanceof Map<?, ?> map) || !(map.get("success") instanceof Boolean success) || !map.containsKey("value")) {
            throw new IllegalArgumentException("typedValue result shape is invalid");
        }
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException("typedValue result keys must be strings");
            }
            Object value = entry.getValue();
            result.put(key, "value".equals(key) && value != null ? decodeRawValue(success ? type.success() : type.failure(), value) : value);
        }
        return Collections.unmodifiableMap(result);
    }

    private static boolean containsResource(TypeExpr expression) {
        return switch (expression) {
            case TypeExpr.ResourceType ignored -> true;
            case TypeExpr.Named named -> named.arguments().stream().anyMatch(TypeValueCodec::containsResource);
            case TypeExpr.OptionalType optional -> containsResource(optional.element());
            case TypeExpr.ListType list -> containsResource(list.element());
            case TypeExpr.MapType map -> containsResource(map.key()) || containsResource(map.value());
            case TypeExpr.TupleType tuple -> tuple.elements().stream().anyMatch(TypeValueCodec::containsResource);
            case TypeExpr.ResultType result -> containsResource(result.success()) || containsResource(result.failure());
            case TypeExpr.UnionType union -> union.variants().stream().map(TypeExpr.UnionVariant::type).anyMatch(TypeValueCodec::containsResource);
            case TypeExpr.OpaqueType ignored -> false;
        };
    }

    private List<TypeExpr> decodeTypes(JsonValue value, String name) {
        return decodeList(value, this::decodeType, name);
    }

    private List<TypeExpr.UnionVariant> decodeVariants(JsonValue value) {
        return decodeList(value, this::decodeVariant, "type.variants");
    }

    private TypeExpr.UnionVariant decodeVariant(JsonValue value) {
        JsonObject object = requireObject(value, "union variant");
        TypeExpr.UnionVariant decoded = new TypeExpr.UnionVariant(string(required(object, "variantId"), "variant.variantId"),
            decodeType(required(object, "type")), optionalString(object, "displayName", Function.identity()),
            optionalString(object, "description", Function.identity()), unknown(object, UNION_VARIANT_FIELDS));
        return decoded;
    }

    private static Set<String> knownTypeFields(String kind) {
        return switch (kind) {
            case "named" -> Set.of("kind", "type", "arguments");
            case "optional", "list" -> Set.of("kind", "element");
            case "map" -> Set.of("kind", "key", "value");
            case "tuple" -> Set.of("kind", "elements");
            case "result" -> Set.of("kind", "success", "failure");
            case "resource" -> Set.of("kind", "resourceType");
            case "union" -> Set.of("kind", "variants");
            case "opaque" -> Set.of("kind", "raw", "type");
            default -> throw new IllegalArgumentException("Unknown type expression kind: " + kind);
        };
    }

    private static <T> List<T> decodeList(JsonValue value, Function<JsonValue, T> decoder, String name) {
        JsonArray array = requireArray(value, name);
        ArrayList<T> result = new ArrayList<>(array.values().size());
        for (JsonValue member : array.values()) {
            result.add(Objects.requireNonNull(decoder.apply(member), name + " member"));
        }
        return List.copyOf(result);
    }

    private static Map<String, Object> unknown(JsonObject object, Set<String> known) {
        LinkedHashMap<String, Object> values = new LinkedHashMap<>();
        object.fields().forEach((key, value) -> {
            if (!known.contains(key)) {
                values.put(key, value.toJava());
            }
        });
        return values;
    }

    private static JsonValue required(JsonObject object, String field) {
        JsonValue value = object.value(field);
        if (value == null) {
            throw new IllegalArgumentException("Required JSON field is missing: " + field);
        }
        if (value instanceof JsonNull) {
            throw new IllegalArgumentException("Required JSON field cannot be null: " + field);
        }
        return value;
    }

    private static JsonObject requireObject(JsonValue value, String name) {
        if (!(Objects.requireNonNull(value, name + " is required") instanceof JsonObject object)) {
            throw new IllegalArgumentException(name + " must be an object");
        }
        return object;
    }

    private static JsonArray requireArray(JsonValue value, String name) {
        if (!(Objects.requireNonNull(value, name + " is required") instanceof JsonArray array)) {
            throw new IllegalArgumentException(name + " must be an array");
        }
        return array;
    }

    private static String string(JsonValue value, String name) {
        if (!(Objects.requireNonNull(value, name + " is required") instanceof JsonString string)) {
            throw new IllegalArgumentException(name + " must be a string");
        }
        return string.value();
    }

    private static boolean bool(JsonValue value, String name) {
        if (!(Objects.requireNonNull(value, name + " is required") instanceof JsonBoolean booleanValue)) {
            throw new IllegalArgumentException(name + " must be a boolean");
        }
        return booleanValue.value();
    }

    private static BigDecimal decimal(Object value, String name) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof BigInteger integer) {
            return new BigDecimal(integer);
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        throw new IllegalArgumentException(name + " must be numeric");
    }

    private static BigInteger integral(Object value, String name) {
        try {
            return decimal(value, name).toBigIntegerExact();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(name + " must be integral", exception);
        }
    }

    private static UUID uuid(String value, String name) {
        try {
            UUID result = UUID.fromString(value);
            if (!result.toString().equals(value)) {
                throw new IllegalArgumentException(name + " must be canonical");
            }
            return result;
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(name + " must be a canonical UUID", exception);
        }
    }

    private static <T> T optionalString(JsonObject object, String field, Function<String, T> factory) {
        return object.contains(field) ? factory.apply(string(required(object, field), field)) : null;
    }

    private static <T> T fail(String message) {
        throw new IllegalArgumentException(message);
    }
}
