package restudio.resync.flow.function;

import restudio.resync.contract.canonical.CanonicalCodec;
import restudio.resync.contract.canonical.JsonValue;
import restudio.resync.contract.identity.IdentityCodec;
import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.graph.GraphDocumentCodec;
import restudio.resync.flow.graph.OpaqueData;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.FunctionParameterId;
import restudio.resync.flow.identity.ResourceKey;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypeReference;
import restudio.resync.flow.type.TypedValue;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class FunctionSourceDocumentCodec implements CanonicalCodec<FunctionSourceDocument> {
    public static final FunctionSourceDocumentCodec INSTANCE = new FunctionSourceDocumentCodec(GraphDocumentCodec.INSTANCE);
    private static final Set<String> SOURCE_KNOWN = Set.of("signature", "graph");
    private static final Set<String> SIGNATURE_KNOWN = Set.of("function", "revision", "inputs", "outputs");
    private static final Set<String> PARAMETER_KNOWN = Set.of("id", "type", "required", "defaultValue");
    private static final Set<String> TYPED_VALUE_KNOWN = Set.of("type", "state", "variantId", "value", "locator");
    private final CanonicalCodec<GraphDocument> graphCodec;

    public FunctionSourceDocumentCodec(CanonicalCodec<GraphDocument> graphCodec) {
        this.graphCodec = Objects.requireNonNull(graphCodec, "Graph codec is required");
    }

    public static FunctionSourceDocumentCodec of(CanonicalCodec<GraphDocument> graphCodec) {
        return new FunctionSourceDocumentCodec(graphCodec);
    }

    public static FunctionSourceDocumentCodec instance() {
        return INSTANCE;
    }

    @Override
    public JsonValue.JsonObject encode(FunctionSourceDocument source) {
        Objects.requireNonNull(source, "Function source document is required");
        return object(Map.of(
            "signature", encodeSignature(source.signature()),
            "graph", graphCodec.encode(source.graph())), source.unknown().fields());
    }

    @Override
    public FunctionSourceDocument decode(JsonValue value) {
        Objects.requireNonNull(value, "Function source document is required");
        return decodeDetached(CanonicalCodec.decode(value.canonicalText()));
    }

    @Override
    public FunctionSourceDocument decodeBytes(byte[] input) {
        return decodeDetached(CanonicalCodec.decode(input));
    }

    @Override
    public FunctionSourceDocument decodeText(String input) {
        return decodeDetached(CanonicalCodec.decode(input));
    }

    private FunctionSourceDocument decodeDetached(JsonValue value) {
        JsonValue.JsonObject object = object(value, "Function source document");
        FunctionSignature signature = decodeSignature(require(object, "signature"));
        GraphDocument graph = graphCodec.decode(require(object, "graph"));
        FunctionSourceDocument decoded = new FunctionSourceDocument(signature, graph, OpaqueData.of(unknown(object, SOURCE_KNOWN)));
        String canonical = object.canonicalText();
        if (!encode(decoded).canonicalText().equals(canonical)) {
            throw new IllegalArgumentException("Function source document is not in the exact canonical shape");
        }
        if (graphCodec == GraphDocumentCodec.INSTANCE) {
            decoded.retainChecksum(ContentHash.of(CanonicalJson.sha256Canonical("function-source", canonical.getBytes(StandardCharsets.UTF_8))));
        }
        return decoded;
    }

    public JsonValue.JsonObject encodeSignature(FunctionSignature signature) {
        Objects.requireNonNull(signature, "Function signature is required");
        Map<String, Object> known = new LinkedHashMap<>();
        known.put("function", IdentityCodec.encode(signature.function().resource()));
        known.put("revision", signature.revision().value());
        known.put("inputs", signature.inputs().stream().map(this::encodeParameter).toList());
        known.put("outputs", signature.outputs().stream().map(this::encodeParameter).toList());
        return object(known, signature.unknown());
    }

    public FunctionSignature decodeSignature(JsonValue value) {
        JsonValue.JsonObject object = object(value, "Function signature");
        FunctionLocator function = new FunctionLocator(IdentityCodec.decodeLocator(require(object, "function")));
        FunctionRevision revision = new FunctionRevision(requireLong(object, "revision"));
        List<FunctionParameterContract> inputs = requireArray(object, "inputs").values().stream()
            .map(this::decodeParameter).toList();
        List<FunctionParameterContract> outputs = requireArray(object, "outputs").values().stream()
            .map(this::decodeParameter).toList();
        FunctionSignature decoded = new FunctionSignature(function, revision, inputs, outputs, unknown(object, SIGNATURE_KNOWN));
        if (!encodeSignature(decoded).canonicalText().equals(object.canonicalText())) {
            throw new IllegalArgumentException("Function signature is not in the exact canonical shape");
        }
        return decoded;
    }

    public ContentHash checksum(FunctionSourceDocument source) {
        return new ContentHash(CanonicalJson.sha256("function-source", encode(source).toJava()));
    }

    public byte[] canonicalBytes(FunctionSourceDocument source) {
        return encodeBytes(source);
    }

    public String canonicalText(FunctionSourceDocument source) {
        return encodeText(source);
    }

    private JsonValue.JsonObject encodeParameter(FunctionParameterContract parameter) {
        Map<String, Object> known = new LinkedHashMap<>();
        known.put("id", parameter.id().canonicalText());
        known.put("type", encodeType(parameter.type()));
        known.put("required", parameter.required());
        if (parameter.defaultValue() != null) {
            known.put("defaultValue", encodeTypedValue(parameter.defaultValue()));
        }
        return object(known, parameter.unknown());
    }

    private FunctionParameterContract decodeParameter(JsonValue value) {
        JsonValue.JsonObject object = object(value, "Function parameter");
        FunctionParameterId id = FunctionParameterId.parseCanonicalText(text(object, "id"));
        TypeExpr type = decodeType(require(object, "type"));
        boolean required = booleanValue(object, "required");
        TypedValue defaultValue = optional(object, "defaultValue").map(this::decodeTypedValue).orElse(null);
        return new FunctionParameterContract(id, type, required, defaultValue, unknown(object, PARAMETER_KNOWN));
    }

    private JsonValue encodeType(TypeExpr type) {
        return toJsonValue(Objects.requireNonNull(type, "Type expression is required").canonicalValue());
    }

    private TypeExpr decodeType(JsonValue value) {
        JsonValue.JsonObject object = object(value, "Type expression");
        String kind = text(object, "kind");
        Map<String, Object> unknown = unknown(object, typeKnown(kind));
        return switch (kind) {
            case "named" -> new TypeExpr.Named(decodeTypeReference(require(object, "type")),
                requireArray(object, "arguments").values().stream().map(this::decodeType).toList(), unknown);
            case "optional" -> new TypeExpr.OptionalType(decodeType(require(object, "element")), unknown);
            case "list" -> new TypeExpr.ListType(decodeType(require(object, "element")), unknown);
            case "map" -> new TypeExpr.MapType(decodeType(require(object, "key")), decodeType(require(object, "value")), unknown);
            case "tuple" -> new TypeExpr.TupleType(requireArray(object, "elements").values().stream().map(this::decodeType).toList(), unknown);
            case "result" -> new TypeExpr.ResultType(decodeType(require(object, "success")), decodeType(require(object, "failure")), unknown);
            case "resource" -> new TypeExpr.ResourceType(decodeTypeReference(require(object, "resourceType")), unknown);
            case "union" -> new TypeExpr.UnionType(requireArray(object, "variants").values().stream()
                .map(this::decodeUnionVariant).toList(), unknown);
            case "opaque" -> decodeOpaqueType(object, unknown);
            default -> throw new IllegalArgumentException("Unknown type expression kind: " + kind);
        };
    }

    private TypeExpr decodeOpaqueType(JsonValue.JsonObject object, Map<String, Object> unknown) {
        JsonValue raw = require(object, "raw");
        if (!(raw instanceof JsonValue.JsonBoolean booleanValue) || !booleanValue.value()) {
            throw new IllegalArgumentException("Opaque type raw marker must be true");
        }
        return new TypeExpr.OpaqueType(decodeTypeReference(require(object, "type")), unknown);
    }

    private TypeExpr.UnionVariant decodeUnionVariant(JsonValue value) {
        JsonValue.JsonObject object = object(value, "Union variant");
        return new TypeExpr.UnionVariant(text(object, "variantId"), decodeType(require(object, "type")),
            optionalText(object, "displayName"), optionalText(object, "description"),
            unknown(object, Set.of("variantId", "type", "displayName", "description")));
    }

    private TypeReference decodeTypeReference(JsonValue value) {
        JsonValue.JsonObject object = object(value, "Type reference");
        return new TypeReference(text(object, "ownerId"), text(object, "localId"), unknown(object, Set.of("ownerId", "localId")));
    }

    private JsonValue encodeTypedValue(TypedValue value) {
        return toJsonValue(Objects.requireNonNull(value, "Typed value is required").canonicalValue());
    }

    private TypedValue decodeTypedValue(JsonValue value) {
        JsonValue.JsonObject object = object(value, "Typed value");
        TypeExpr type = decodeType(require(object, "type"));
        TypedValue.State state = state(text(object, "state"));
        String variantId = optionalText(object, "variantId");
        validateTypedFields(object, state);
        TypeExpr selectedType = selectedType(type, variantId, state);
        Object material = optional(object, "value")
            .map(item -> decodeMaterial(item, selectedType, state == TypedValue.State.OPAQUE)).orElse(null);
        ServerResourceLocator locator = optional(object, "locator").map(IdentityCodec::decodeLocator).orElse(null);
        return new TypedValue(type, state, variantId, material, locator, unknown(object, TYPED_VALUE_KNOWN));
    }

    private static TypeExpr selectedType(TypeExpr type, String variantId, TypedValue.State state) {
        if (type instanceof TypeExpr.UnionType union && state != TypedValue.State.ABSENT && state != TypedValue.State.NULL) {
            if (variantId == null) {
                throw new IllegalArgumentException("A union typed value requires a variant ID");
            }
            return union.variant(variantId).type();
        }
        if (!(type instanceof TypeExpr.UnionType) && variantId != null) {
            throw new IllegalArgumentException("Only union typed values may carry a variant ID");
        }
        return type;
    }

    private static void validateTypedFields(JsonValue.JsonObject object, TypedValue.State state) {
        boolean valuePresent = object.contains("value");
        boolean locatorPresent = object.contains("locator");
        switch (state) {
            case ABSENT, NULL -> {
                if (valuePresent || locatorPresent) {
                    throw new IllegalArgumentException("Empty typed values cannot carry material");
                }
            }
            case VALUE -> {
                if (!valuePresent || locatorPresent) {
                    throw new IllegalArgumentException("Value typed values require value material only");
                }
            }
            case LOCATOR -> {
                if (!locatorPresent || valuePresent) {
                    throw new IllegalArgumentException("Locator typed values require locator material only");
                }
            }
            case OPAQUE -> {
                if (!valuePresent || locatorPresent) {
                    throw new IllegalArgumentException("Opaque typed values require value material only");
                }
            }
        }
    }

    private Object decodeMaterial(JsonValue value, TypeExpr type, boolean opaque) {
        if (opaque) {
            return value.toJava();
        }
        return switch (type) {
            case TypeExpr.Named named -> decodeNamedMaterial(value, named);
            case TypeExpr.OptionalType optional -> value instanceof JsonValue.JsonNull ? null : decodeMaterial(value, optional.element(), false);
            case TypeExpr.ListType list -> decodeListMaterial(value, list.element());
            case TypeExpr.MapType map -> decodeMapMaterial(value, map);
            case TypeExpr.TupleType tuple -> decodeTupleMaterial(value, tuple.elements());
            case TypeExpr.ResultType result -> decodeResultMaterial(value, result);
            case TypeExpr.ResourceType ignored -> IdentityCodec.decodeLocator(value);
            case TypeExpr.UnionType ignored -> throw new IllegalArgumentException("Union material requires a selected variant");
            case TypeExpr.OpaqueType ignored -> value.toJava();
        };
    }

    private Object decodeNamedMaterial(JsonValue value, TypeExpr.Named named) {
        if ("builtin".equals(named.reference().ownerId()) && named.arguments().isEmpty()) {
            return switch (named.reference().localId()) {
                case "integer" -> integer(value);
                case "number" -> number(value);
                case "uuid" -> uuidValue(value);
                default -> value.toJava();
            };
        }
        if (containsResource(named)) {
            if (value instanceof JsonValue.JsonObject object && object.contains("serverId") && object.contains("type") && object.contains("id")) {
                return IdentityCodec.decodeLocator(value);
            }
        }
        return value.toJava();
    }

    private List<Object> decodeListMaterial(JsonValue value, TypeExpr element) {
        JsonValue.JsonArray array = requireArray(value, "Typed list material");
        ArrayList<Object> result = new ArrayList<>(array.values().size());
        for (JsonValue item : array.values()) {
            result.add(item instanceof JsonValue.JsonNull ? null : decodeMaterial(item, element, false));
        }
        return Collections.unmodifiableList(result);
    }

    private Map<String, Object> decodeMapMaterial(JsonValue value, TypeExpr.MapType type) {
        JsonValue.JsonObject object = object(value, "Typed map material");
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        object.fields().forEach((key, item) -> {
            Object decodedKey = decodeMaterial(JsonValue.of(key), type.key(), false);
            if (!(decodedKey instanceof String stringKey)) {
                throw new IllegalArgumentException("Typed map keys must decode to text");
            }
            Object decodedValue = item instanceof JsonValue.JsonNull ? null : decodeMaterial(item, type.value(), false);
            if (result.containsKey(stringKey)) {
                throw new IllegalArgumentException("Duplicate typed map key: " + stringKey);
            }
            result.put(stringKey, decodedValue);
        });
        return Collections.unmodifiableMap(result);
    }

    private List<Object> decodeTupleMaterial(JsonValue value, List<TypeExpr> types) {
        JsonValue.JsonArray array = requireArray(value, "Typed tuple material");
        if (array.values().size() != types.size()) {
            throw new IllegalArgumentException("Typed tuple material length does not match its type");
        }
        ArrayList<Object> result = new ArrayList<>(types.size());
        for (int index = 0; index < types.size(); index++) {
            JsonValue item = array.values().get(index);
            result.add(item instanceof JsonValue.JsonNull ? null : decodeMaterial(item, types.get(index), false));
        }
        return Collections.unmodifiableList(result);
    }

    private Map<String, Object> decodeResultMaterial(JsonValue value, TypeExpr.ResultType result) {
        JsonValue.JsonObject object = object(value, "Typed result material");
        JsonValue success = require(object, "success");
        if (!(success instanceof JsonValue.JsonBoolean booleanValue)) {
            throw new IllegalArgumentException("Typed result success must be boolean");
        }
        LinkedHashMap<String, Object> decodedResult = new LinkedHashMap<>();
        object.fields().forEach((key, item) -> {
            if ("success".equals(key)) {
                decodedResult.put(key, booleanValue.value());
            } else if ("value".equals(key)) {
                decodedResult.put(key, item instanceof JsonValue.JsonNull ? null
                    : decodeMaterial(item, booleanValue.value() ? result.success() : result.failure(), false));
            } else {
                decodedResult.put(key, item.toJava());
            }
        });
        if (!decodedResult.containsKey("value")) {
            throw new IllegalArgumentException("Typed result material requires value");
        }
        return Collections.unmodifiableMap(decodedResult);
    }

    private static BigDecimal number(JsonValue value) {
        if (!(value instanceof JsonValue.JsonNumber number)) {
            throw new IllegalArgumentException("Typed number material must be numeric");
        }
        return number.value();
    }

    private static BigInteger integer(JsonValue value) {
        if (!(value instanceof JsonValue.JsonNumber number)) {
            throw new IllegalArgumentException("Typed integer material must be numeric");
        }
        try {
            return number.value().toBigIntegerExact();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Typed integer material must be integral", exception);
        }
    }

    private static UUID uuidValue(JsonValue value) {
        String text = text(value, "Typed UUID material");
        try {
            UUID uuid = UUID.fromString(text);
            if (!uuid.toString().equals(text)) {
                throw new IllegalArgumentException("Typed UUID material must be canonical");
            }
            return uuid;
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Typed UUID material must be a canonical UUID", exception);
        }
    }

    private static boolean containsResource(TypeExpr expression) {
        return switch (expression) {
            case TypeExpr.ResourceType ignored -> true;
            case TypeExpr.Named named -> named.arguments().stream().anyMatch(FunctionSourceDocumentCodec::containsResource);
            case TypeExpr.OptionalType optional -> containsResource(optional.element());
            case TypeExpr.ListType list -> containsResource(list.element());
            case TypeExpr.MapType map -> containsResource(map.key()) || containsResource(map.value());
            case TypeExpr.TupleType tuple -> tuple.elements().stream().anyMatch(FunctionSourceDocumentCodec::containsResource);
            case TypeExpr.ResultType result -> containsResource(result.success()) || containsResource(result.failure());
            case TypeExpr.UnionType union -> union.variants().stream().map(TypeExpr.UnionVariant::type)
                .anyMatch(FunctionSourceDocumentCodec::containsResource);
            case TypeExpr.OpaqueType ignored -> false;
        };
    }

    private static JsonValue.JsonObject object(Map<String, ?> known, Map<String, ?> unknown) {
        LinkedHashMap<String, JsonValue> knownValues = new LinkedHashMap<>();
        known.forEach((key, value) -> knownValues.put(key, toJsonValue(value)));
        LinkedHashMap<String, JsonValue> unknownValues = new LinkedHashMap<>();
        if (unknown != null) {
            unknown.forEach((key, value) -> unknownValues.put(key, toJsonValue(value)));
        }
        return CanonicalCodec.mergeKnownFields(knownValues, unknownValues);
    }

    private static JsonValue toJsonValue(Object value) {
        if (value instanceof JsonValue jsonValue) {
            return jsonValue;
        }
        if (value instanceof FunctionLocator locator) {
            return IdentityCodec.encode(locator.resource());
        }
        if (value instanceof FunctionRevision revision) {
            return JsonValue.of(revision.value());
        }
        if (value instanceof FunctionCancellation cancellation) {
            return toJsonValue(cancellation.canonicalValue());
        }
        if (value instanceof FunctionParameterId id) {
            return JsonValue.of(id.canonicalText());
        }
        if (value instanceof TypeExpr type) {
            return toJsonValue(type.canonicalValue());
        }
        if (value instanceof TypedValue typedValue) {
            return toJsonValue(typedValue.canonicalValue());
        }
        if (value instanceof TypeReference reference) {
            return toJsonValue(reference.canonicalValue());
        }
        if (value instanceof ServerResourceLocator locator) {
            return IdentityCodec.encode(locator);
        }
        if (value instanceof ResourceKey key) {
            return IdentityCodec.encodeResourceKey(key);
        }
        if (value instanceof ContractRef<?> reference) {
            return IdentityCodec.encode(reference);
        }
        if (value instanceof Map<?, ?> map) {
            LinkedHashMap<String, JsonValue> result = new LinkedHashMap<>();
            map.forEach((key, nested) -> {
                if (!(key instanceof String stringKey)) {
                    throw new IllegalArgumentException("Canonical function object keys must be text");
                }
                if (result.put(stringKey, toJsonValue(nested)) != null) {
                    throw new IllegalArgumentException("Canonical function object contains duplicate key: " + stringKey);
                }
            });
            return JsonValue.object(result);
        }
        if (value instanceof Iterable<?> iterable) {
            List<JsonValue> result = new ArrayList<>();
            iterable.forEach(item -> result.add(toJsonValue(item)));
            return JsonValue.array(result);
        }
        return JsonValue.fromJava(value);
    }

    private static Map<String, Object> unknown(JsonValue.JsonObject object, Set<String> known) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        object.fields().forEach((key, value) -> {
            if (!known.contains(key)) {
                result.put(key, value.toJava());
            }
        });
        return result;
    }

    private static Set<String> typeKnown(String kind) {
        return switch (kind) {
            case "named" -> Set.of("kind", "type", "arguments");
            case "optional", "list" -> Set.of("kind", "element");
            case "map" -> Set.of("kind", "key", "value");
            case "tuple" -> Set.of("kind", "elements");
            case "result" -> Set.of("kind", "success", "failure");
            case "resource" -> Set.of("kind", "resourceType");
            case "union" -> Set.of("kind", "variants");
            case "opaque" -> Set.of("kind", "type", "raw");
            default -> throw new IllegalArgumentException("Unknown type expression kind: " + kind);
        };
    }

    private static JsonValue.JsonObject object(JsonValue value, String name) {
        if (!(value instanceof JsonValue.JsonObject object)) {
            throw new IllegalArgumentException(name + " must be an object");
        }
        return object;
    }

    private static JsonValue require(JsonValue.JsonObject object, String field) {
        JsonValue value = object.value(field);
        if (value == null) {
            throw new IllegalArgumentException("Required function field is missing: " + field);
        }
        return value;
    }

    private static Optional<JsonValue> optional(JsonValue.JsonObject object, String field) {
        return Optional.ofNullable(object.value(field));
    }

    private static String text(JsonValue.JsonObject object, String field) {
        return text(require(object, field), field);
    }

    private static String text(JsonValue value, String field) {
        if (!(value instanceof JsonValue.JsonString string)) {
            throw new IllegalArgumentException("Function field must be text: " + field);
        }
        return string.value();
    }

    private static String optionalText(JsonValue.JsonObject object, String field) {
        return optional(object, field).map(value -> text(value, field)).orElse(null);
    }

    private static long requireLong(JsonValue.JsonObject object, String field) {
        JsonValue value = require(object, field);
        if (!(value instanceof JsonValue.JsonNumber number)) {
            throw new IllegalArgumentException("Function field must be an integer: " + field);
        }
        try {
            return number.value().longValueExact();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Function integer is out of range: " + field, exception);
        }
    }

    private static boolean booleanValue(JsonValue.JsonObject object, String field) {
        JsonValue value = require(object, field);
        if (!(value instanceof JsonValue.JsonBoolean booleanValue)) {
            throw new IllegalArgumentException("Function field must be boolean: " + field);
        }
        return booleanValue.value();
    }

    private static JsonValue.JsonArray requireArray(JsonValue.JsonObject object, String field) {
        return requireArray(require(object, field), field);
    }

    private static JsonValue.JsonArray requireArray(JsonValue value, String field) {
        if (!(value instanceof JsonValue.JsonArray array)) {
            throw new IllegalArgumentException("Function field must be an array: " + field);
        }
        return array;
    }

    private static TypedValue.State state(String value) {
        for (TypedValue.State candidate : TypedValue.State.values()) {
            if (candidate.wireName().equals(value)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException("Unknown typed value state: " + value);
    }

}
