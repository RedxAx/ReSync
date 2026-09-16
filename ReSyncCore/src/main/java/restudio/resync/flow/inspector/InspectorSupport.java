package restudio.resync.flow.inspector;

import restudio.resync.flow.canonical.CanonicalJson;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;

final class InspectorSupport {
    private InspectorSupport() {
    }

    static <T> List<T> list(Collection<T> values, String name) {
        Objects.requireNonNull(values, name);
        var copy = new ArrayList<T>(values.size());
        for (var value : values) {
            copy.add(Objects.requireNonNull(value, name + " entry"));
        }
        return List.copyOf(copy);
    }

    static Map<String, Object> map(Map<String, ?> values, String name) {
        Objects.requireNonNull(values, name);
        var copy = new TreeMap<String, Object>();
        for (var entry : values.entrySet()) {
            var key = Objects.requireNonNull(entry.getKey(), name + " key");
            copy.put(key, freeze(entry.getValue(), name + "." + key));
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(copy));
    }

    static void rejectKnownKeys(Map<String, ?> values, String name, String... knownKeys) {
        Objects.requireNonNull(values, name);
        var known = Set.of(knownKeys);
        for (var key : values.keySet()) {
            if (known.contains(key)) {
                throw new IllegalArgumentException(name + " collides with known field: " + key);
            }
        }
    }

    static Object freeze(Object value, String name) {
        if (value == null || value instanceof String || value instanceof Boolean || value instanceof BigInteger || value instanceof BigDecimal || value instanceof UUID) {
            return value;
        }
        if (value instanceof Instant instant) {
            return instant.toString();
        }
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            return value;
        }
        if (value instanceof Float floatValue) {
            if (!Float.isFinite(floatValue)) {
                throw new IllegalArgumentException(name + " contains a non-finite number");
            }
            return BigDecimal.valueOf(floatValue.doubleValue());
        }
        if (value instanceof Double doubleValue) {
            if (!Double.isFinite(doubleValue)) {
                throw new IllegalArgumentException(name + " contains a non-finite number");
            }
            return BigDecimal.valueOf(doubleValue);
        }
        if (value instanceof Enum<?> enumValue) {
            return enumValue.name();
        }
        if (value instanceof Map<?, ?> source) {
            var copy = new TreeMap<String, Object>();
            for (var entry : source.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new IllegalArgumentException(name + " keys must be strings");
                }
                copy.put(key, freeze(entry.getValue(), name + "." + key));
            }
            return Collections.unmodifiableMap(new LinkedHashMap<>(copy));
        }
        if (value instanceof Collection<?> source) {
            var copy = new ArrayList<Object>(source.size());
            for (var entry : source) {
                copy.add(freeze(entry, name + "[]"));
            }
            if (value instanceof Set<?>) {
                copy.sort(Comparator.comparing(CanonicalJson::canonicalize));
            }
            return List.copyOf(copy);
        }
        throw new IllegalArgumentException("Unsupported inspector value at " + name);
    }

    static <T> void unique(List<T> values, Function<T, ?> key, String name) {
        var seen = new HashSet<Object>();
        for (var value : values) {
            if (!seen.add(key.apply(value))) {
                throw new IllegalArgumentException("Duplicate " + name);
            }
        }
    }
}
