package restudio.resync.contract.canonical;

import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.canonical.CanonicalLimits;
import restudio.resync.contract.canonical.JsonValue.JsonObject;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

public interface CanonicalCodec<T> {
    JsonValue encode(T value);

    T decode(JsonValue value);

    default byte[] encodeBytes(T value) {
        return encodeBytes(value, CanonicalLimits.standard());
    }

    default byte[] encodeBytes(T value, CanonicalLimits limits) {
        return encode(value).canonicalBytes(Objects.requireNonNull(limits, "Limits are required"));
    }

    default String encodeText(T value) {
        return encodeText(value, CanonicalLimits.standard());
    }

    default String encodeText(T value, CanonicalLimits limits) {
        return encode(value).canonicalText(Objects.requireNonNull(limits, "Limits are required"));
    }

    default T decodeBytes(byte[] input) {
        return decode(CanonicalCodec.decode(input));
    }

    default T decodeText(String input) {
        return decode(CanonicalCodec.decode(input));
    }

    static JsonValue decode(byte[] input) {
        return decode(input, CanonicalLimits.standard());
    }

    static ValidatedJson decodeValidated(byte[] input) {
        return decodeValidated(input, CanonicalLimits.standard());
    }

    static JsonValue decode(byte[] input, CanonicalLimits limits) {
        return decodeValidated(input, limits).value();
    }

    static ValidatedJson decodeValidated(byte[] input, CanonicalLimits limits) {
        Objects.requireNonNull(input, "Input is required");
        Objects.requireNonNull(limits, "Limits are required");
        return validated(CanonicalJson.parseTreeResult(input, limits), "Input is not canonical JSON");
    }

    static JsonValue decodePermissive(byte[] input) {
        return decodePermissive(input, CanonicalLimits.standard());
    }

    static JsonValue decodePermissive(byte[] input, CanonicalLimits limits) {
        Objects.requireNonNull(input, "Input is required");
        Objects.requireNonNull(limits, "Limits are required");
        return CanonicalJson.parseTree(input, limits);
    }

    static JsonValue decode(String input) {
        return decode(input, CanonicalLimits.standard());
    }

    static ValidatedJson decodeValidated(String input) {
        return decodeValidated(input, CanonicalLimits.standard());
    }

    static JsonValue decode(String input, CanonicalLimits limits) {
        return decodeValidated(input, limits).value();
    }

    static ValidatedJson decodeValidated(String input, CanonicalLimits limits) {
        Objects.requireNonNull(input, "Input is required");
        Objects.requireNonNull(limits, "Limits are required");
        return validated(CanonicalJson.parseTreeResult(input, limits), "Input is not canonical JSON");
    }

    static JsonValue decodePermissive(String input) {
        return decodePermissive(input, CanonicalLimits.standard());
    }

    static JsonValue decodePermissive(String input, CanonicalLimits limits) {
        Objects.requireNonNull(input, "Input is required");
        Objects.requireNonNull(limits, "Limits are required");
        return CanonicalJson.parseTree(input, limits);
    }

    static JsonValue decodeOpaque(byte[] input) {
        return decodeOpaque(input, CanonicalLimits.standard());
    }

    static JsonValue decodeOpaque(byte[] input, CanonicalLimits limits) {
        return decodeOpaqueValidated(input, limits).value();
    }

    static ValidatedJson decodeOpaqueValidated(byte[] input, CanonicalLimits limits) {
        Objects.requireNonNull(input, "Input is required");
        Objects.requireNonNull(limits, "Limits are required");
        return validated(CanonicalJson.parseOpaqueTreeResult(input, limits), "Opaque input is not canonical JSON");
    }

    static JsonValue decodeOpaquePermissive(byte[] input) {
        return decodeOpaquePermissive(input, CanonicalLimits.standard());
    }

    static JsonValue decodeOpaquePermissive(byte[] input, CanonicalLimits limits) {
        Objects.requireNonNull(input, "Input is required");
        Objects.requireNonNull(limits, "Limits are required");
        return CanonicalJson.parseOpaqueTreeResult(input, limits).value();
    }

    static JsonValue decodeOpaque(String input) {
        return decodeOpaque(input, CanonicalLimits.standard());
    }

    static JsonValue decodeOpaque(String input, CanonicalLimits limits) {
        return decodeOpaqueValidated(input, limits).value();
    }

    static ValidatedJson decodeOpaqueValidated(String input, CanonicalLimits limits) {
        Objects.requireNonNull(input, "Input is required");
        Objects.requireNonNull(limits, "Limits are required");
        return validated(CanonicalJson.parseOpaqueTreeResult(input, limits), "Opaque input is not canonical JSON");
    }

    static JsonValue decodeOpaquePermissive(String input) {
        return decodeOpaquePermissive(input, CanonicalLimits.standard());
    }

    static JsonValue decodeOpaquePermissive(String input, CanonicalLimits limits) {
        Objects.requireNonNull(input, "Input is required");
        Objects.requireNonNull(limits, "Limits are required");
        return CanonicalJson.parseOpaqueTreeResult(input, limits).value();
    }

    static byte[] encode(JsonValue value) {
        return Objects.requireNonNull(value, "JSON value is required").canonicalBytes();
    }

    static String encodeText(JsonValue value) {
        return Objects.requireNonNull(value, "JSON value is required").canonicalText();
    }

    static byte[] canonicalBytes(byte[] input) {
        return canonicalBytes(input, CanonicalLimits.standard());
    }

    static byte[] canonicalBytes(byte[] input, CanonicalLimits limits) {
        Objects.requireNonNull(input, "Input is required");
        decodeValidated(input, limits);
        return input.clone();
    }

    static <T> T decode(byte[] input, CanonicalCodec<T> codec) {
        return Objects.requireNonNull(codec, "Codec is required").decode(decode(input));
    }

    static <T> T decode(String input, CanonicalCodec<T> codec) {
        return Objects.requireNonNull(codec, "Codec is required").decode(decode(input));
    }

    static <T> CanonicalCodec<T> of(Function<JsonValue, T> decoder, Function<T, JsonValue> encoder) {
        Objects.requireNonNull(decoder, "Decoder is required");
        Objects.requireNonNull(encoder, "Encoder is required");
        return new CanonicalCodec<>() {
            @Override
            public JsonValue encode(T value) {
                return Objects.requireNonNull(encoder.apply(Objects.requireNonNull(value, "Value is required")), "Encoded value is required");
            }

            @Override
            public T decode(JsonValue value) {
                return Objects.requireNonNull(decoder.apply(Objects.requireNonNull(value, "JSON value is required")), "Decoded value is required");
            }
        };
    }

    private static ValidatedJson validated(CanonicalJson.ParsedTree parsed, String message) {
        if (!parsed.exactCanonical()) {
            throw new IllegalArgumentException(message);
        }
        return new ValidatedJson(parsed);
    }

    final class ValidatedJson {
        private final CanonicalJson.ParsedTree parsed;

        private ValidatedJson(CanonicalJson.ParsedTree parsed) {
            this.parsed = parsed;
        }

        public JsonValue value() {
            return parsed.value();
        }

        public String canonicalText() {
            return canonicalText(value());
        }

        public String canonicalText(JsonValue subtree) {
            return parsed.canonicalText(subtree);
        }

        public byte[] canonicalBytes() {
            return canonicalBytes(value());
        }

        public byte[] canonicalBytes(JsonValue subtree) {
            return parsed.canonicalBytes(subtree);
        }
    }

    static JsonObject mergeKnownFields(Map<String, ? extends JsonValue> known, Map<String, ? extends JsonValue> unknown) {
        Objects.requireNonNull(known, "Known fields are required");
        Objects.requireNonNull(unknown, "Unknown fields are required");
        LinkedHashMap<String, JsonValue> merged = new LinkedHashMap<>();
        known.forEach((key, value) -> put(merged, key, value, "known"));
        unknown.forEach((key, value) -> {
            if (merged.containsKey(key)) {
                throw new IllegalArgumentException("CANON.UNKNOWN_COLLISION: " + key);
            }
            put(merged, key, value, "unknown");
        });
        return new JsonObject(merged);
    }

    static JsonObject mergeKnownFields(JsonObject known, Map<String, ? extends JsonValue> unknown) {
        Objects.requireNonNull(known, "Known object is required");
        return mergeKnownFields(known.values(), unknown);
    }

    static Map<String, JsonValue> unknownFields(JsonObject object, Set<String> knownFields) {
        Objects.requireNonNull(object, "Object is required");
        Objects.requireNonNull(knownFields, "Known fields are required");
        LinkedHashSet<String> known = new LinkedHashSet<>();
        knownFields.forEach(field -> known.add(Objects.requireNonNull(field, "Known field is required")));
        LinkedHashMap<String, JsonValue> unknown = new LinkedHashMap<>();
        object.values().forEach((key, value) -> {
            if (!known.contains(key)) {
                unknown.put(key, value);
            }
        });
        return Collections.unmodifiableMap(unknown);
    }

    static JsonObject requireObject(JsonValue value) {
        if (!(Objects.requireNonNull(value, "JSON value is required") instanceof JsonObject object)) {
            throw new IllegalArgumentException("A JSON object is required");
        }
        return object;
    }

    static JsonValue requireField(JsonObject object, String field) {
        Objects.requireNonNull(object, "Object is required");
        Objects.requireNonNull(field, "Field is required");
        JsonValue value = object.value(field);
        if (value == null) {
            throw new IllegalArgumentException("Required JSON field is missing: " + field);
        }
        return value;
    }

    static Optional<JsonValue> optionalField(JsonObject object, String field) {
        Objects.requireNonNull(object, "Object is required");
        Objects.requireNonNull(field, "Field is required");
        return Optional.ofNullable(object.value(field));
    }

    static void rejectUnknownFields(JsonObject object, Set<String> knownFields) {
        Map<String, JsonValue> unknown = unknownFields(object, knownFields);
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("Unknown JSON fields are not permitted: " + unknown.keySet());
        }
    }

    private static void put(Map<String, JsonValue> target, String key, JsonValue value, String kind) {
        Objects.requireNonNull(key, kind + " field name is required");
        Objects.requireNonNull(value, kind + " field value is required");
        if (target.put(key, value) != null) {
            throw new IllegalArgumentException("Duplicate " + kind + " field: " + key);
        }
    }
}
