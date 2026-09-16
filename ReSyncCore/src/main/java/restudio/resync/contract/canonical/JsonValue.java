package restudio.resync.contract.canonical;

import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.canonical.CanonicalLimits;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public sealed interface JsonValue permits JsonValue.JsonNull, JsonValue.JsonBoolean, JsonValue.JsonNumber, JsonValue.JsonString, JsonValue.JsonArray, JsonValue.JsonObject {
    JsonNull NULL = new JsonNull();

    static JsonNull nullValue() {
        return NULL;
    }

    static JsonBoolean of(boolean value) {
        return new JsonBoolean(value);
    }

    static JsonNumber of(BigDecimal value) {
        return new JsonNumber(value);
    }

    static JsonNumber of(Number value) {
        return new JsonNumber(toDecimal(value));
    }

    static JsonString of(String value) {
        return new JsonString(value);
    }

    static JsonArray array(List<? extends JsonValue> values) {
        return new JsonArray(new ArrayList<>(values));
    }

    static JsonObject object(Map<String, ? extends JsonValue> values) {
        return new JsonObject(new LinkedHashMap<>(values));
    }

    static JsonValue parse(byte[] input) {
        return CanonicalCodec.decode(input);
    }

    static JsonValue parse(String input) {
        return CanonicalCodec.decode(input);
    }

    static JsonValue fromJava(Object value) {
        return fromJava(value, new IdentityHashMap<>());
    }

    private static JsonValue fromJava(Object value, IdentityHashMap<Object, Boolean> active) {
        if (value == null) {
            return NULL;
        }
        if (value instanceof JsonValue jsonValue) {
            return jsonValue;
        }
        if (value instanceof String string) {
            return new JsonString(string);
        }
        if (value instanceof Character character) {
            return new JsonString(character.toString());
        }
        if (value instanceof UUID uuid) {
            return new JsonString(uuid.toString());
        }
        if (value instanceof Boolean booleanValue) {
            return new JsonBoolean(booleanValue);
        }
        if (value instanceof Number number) {
            return new JsonNumber(toDecimal(number));
        }
        if (value instanceof Map<?, ?> map) {
            enter(value, active);
            try {
                LinkedHashMap<String, JsonValue> converted = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (!(entry.getKey() instanceof String key)) {
                        throw new IllegalArgumentException("Canonical object keys must be strings");
                    }
                    JsonValue previous = converted.put(key, fromJava(entry.getValue(), active));
                    if (previous != null) {
                        throw new IllegalArgumentException("Map contains duplicate canonical keys: " + key);
                    }
                }
                return new JsonObject(converted);
            } finally {
                active.remove(value);
            }
        }
        if (value instanceof Set<?> set) {
            enter(value, active);
            try {
                List<JsonValue> converted = new ArrayList<>(set.size());
                for (Object member : set) {
                    converted.add(fromJava(member, active));
                }
                converted.sort(JsonValue::compareCanonical);
                for (int index = 1; index < converted.size(); index++) {
                    if (converted.get(index - 1).equals(converted.get(index))) {
                        throw new IllegalArgumentException("Set contains canonically equal values");
                    }
                }
                return new JsonArray(converted);
            } finally {
                active.remove(value);
            }
        }
        if (value instanceof Iterable<?> iterable) {
            enter(value, active);
            try {
                List<JsonValue> converted = new ArrayList<>();
                for (Object member : iterable) {
                    converted.add(fromJava(member, active));
                }
                return new JsonArray(converted);
            } finally {
                active.remove(value);
            }
        }
        Object[] array = boxedArray(value);
        if (array != null) {
            enter(value, active);
            try {
                List<JsonValue> converted = new ArrayList<>(array.length);
                for (Object member : array) {
                    converted.add(fromJava(member, active));
                }
                return new JsonArray(converted);
            } finally {
                active.remove(value);
            }
        }
        throw new IllegalArgumentException("Unsupported canonical value");
    }

    private static void enter(Object value, IdentityHashMap<Object, Boolean> active) {
        if (active.put(value, Boolean.TRUE) != null) {
            throw new IllegalArgumentException("Canonical value graph contains a cycle");
        }
    }

    private static Object[] boxedArray(Object value) {
        if (value instanceof Object[] array) {
            return array;
        }
        if (value instanceof boolean[] array) {
            Object[] boxed = new Object[array.length];
            for (int index = 0; index < array.length; index++) {
                boxed[index] = array[index];
            }
            return boxed;
        }
        if (value instanceof byte[] array) {
            Object[] boxed = new Object[array.length];
            for (int index = 0; index < array.length; index++) {
                boxed[index] = array[index];
            }
            return boxed;
        }
        if (value instanceof short[] array) {
            Object[] boxed = new Object[array.length];
            for (int index = 0; index < array.length; index++) {
                boxed[index] = array[index];
            }
            return boxed;
        }
        if (value instanceof char[] array) {
            Object[] boxed = new Object[array.length];
            for (int index = 0; index < array.length; index++) {
                boxed[index] = array[index];
            }
            return boxed;
        }
        if (value instanceof int[] array) {
            Object[] boxed = new Object[array.length];
            for (int index = 0; index < array.length; index++) {
                boxed[index] = array[index];
            }
            return boxed;
        }
        if (value instanceof long[] array) {
            Object[] boxed = new Object[array.length];
            for (int index = 0; index < array.length; index++) {
                boxed[index] = array[index];
            }
            return boxed;
        }
        if (value instanceof float[] array) {
            Object[] boxed = new Object[array.length];
            for (int index = 0; index < array.length; index++) {
                boxed[index] = array[index];
            }
            return boxed;
        }
        if (value instanceof double[] array) {
            Object[] boxed = new Object[array.length];
            for (int index = 0; index < array.length; index++) {
                boxed[index] = array[index];
            }
            return boxed;
        }
        return null;
    }

    private static BigDecimal toDecimal(Number value) {
        Objects.requireNonNull(value, "Number is required");
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof BigInteger integer) {
            return new BigDecimal(integer);
        }
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            return BigDecimal.valueOf(value.longValue());
        }
        if (value instanceof Float || value instanceof Double) {
            double floating = value.doubleValue();
            if (!Double.isFinite(floating)) {
                throw new IllegalArgumentException("Canonical numbers must be finite");
            }
            return BigDecimal.valueOf(floating);
        }
        try {
            return new BigDecimal(value.toString());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Unsupported number", exception);
        }
    }

    private static int compareCanonical(JsonValue left, JsonValue right) {
        return CanonicalJson.compareCodePoints(left.canonicalText(), right.canonicalText());
    }

    default String canonicalText() {
        return CanonicalJson.canonicalize(this);
    }

    default byte[] canonicalBytes() {
        return canonicalBytes(CanonicalLimits.standard());
    }

    default String canonicalText(CanonicalLimits limits) {
        return CanonicalJson.canonicalize(this, Objects.requireNonNull(limits, "Limits are required"));
    }

    default byte[] canonicalBytes(CanonicalLimits limits) {
        return CanonicalJson.canonicalBytes(this, Objects.requireNonNull(limits, "Limits are required"));
    }

    Object toJava();

    record JsonNull() implements JsonValue {
        @Override
        public Object toJava() {
            return null;
        }

        @Override
        public String toString() {
            return canonicalText();
        }
    }

    record JsonBoolean(boolean value) implements JsonValue {
        @Override
        public Object toJava() {
            return value;
        }

        @Override
        public String toString() {
            return canonicalText();
        }
    }

    record JsonNumber(BigDecimal value) implements JsonValue {
        public JsonNumber {
            Objects.requireNonNull(value, "Number is required");
            value = value.signum() == 0 ? BigDecimal.ZERO : value.stripTrailingZeros();
        }

        @Override
        public Object toJava() {
            return value;
        }

        @Override
        public String toString() {
            return canonicalText();
        }
    }

    record JsonString(String value) implements JsonValue {
        public JsonString {
            Objects.requireNonNull(value, "String is required");
            validateUnicode(value);
        }

        @Override
        public Object toJava() {
            return value;
        }

        @Override
        public String toString() {
            return canonicalText();
        }

        private static void validateUnicode(String value) {
            for (int index = 0; index < value.length(); index++) {
                char current = value.charAt(index);
                if (Character.isHighSurrogate(current)) {
                    if (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1))) {
                        throw new IllegalArgumentException("String contains an invalid Unicode surrogate");
                    }
                    index++;
                } else if (Character.isLowSurrogate(current)) {
                    throw new IllegalArgumentException("String contains an invalid Unicode surrogate");
                }
            }
        }
    }

    record JsonArray(List<JsonValue> values) implements JsonValue {
        public JsonArray {
            Objects.requireNonNull(values, "Array values are required");
            values = Collections.unmodifiableList(new ArrayList<>(values));
            if (values.stream().anyMatch(Objects::isNull)) {
                throw new IllegalArgumentException("Array values cannot be null");
            }
        }

        @Override
        public Object toJava() {
            return values.stream().map(JsonValue::toJava).toList();
        }

        @Override
        public String toString() {
            return canonicalText();
        }
    }

    record JsonObject(Map<String, JsonValue> values) implements JsonValue {
        public JsonObject {
            Objects.requireNonNull(values, "Object values are required");
            LinkedHashMap<String, JsonValue> copy = new LinkedHashMap<>();
            for (Map.Entry<String, JsonValue> entry : values.entrySet()) {
                String key = Objects.requireNonNull(entry.getKey(), "Object key is required");
                JsonString.validateUnicode(key);
                JsonValue value = Objects.requireNonNull(entry.getValue(), "Object value is required");
                if (copy.put(key, value) != null) {
                    throw new IllegalArgumentException("Object contains duplicate key: " + key);
                }
            }
            values = Collections.unmodifiableMap(copy);
        }

        public Map<String, JsonValue> fields() {
            return values;
        }

        public boolean contains(String key) {
            return values.containsKey(Objects.requireNonNull(key, "Object key is required"));
        }

        public JsonValue value(String key) {
            return values.get(Objects.requireNonNull(key, "Object key is required"));
        }

        @Override
        public Object toJava() {
            LinkedHashMap<String, Object> converted = new LinkedHashMap<>();
            values.forEach((key, value) -> converted.put(key, value.toJava()));
            return Collections.unmodifiableMap(converted);
        }

        @Override
        public String toString() {
            return canonicalText();
        }
    }
}
