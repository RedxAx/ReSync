package restudio.resync.flow.protocol;

import restudio.resync.flow.type.TypedValue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

final class ProtocolValues {
    private ProtocolValues() {
    }

    static <T> List<T> list(Collection<? extends T> values, String name) {
        Objects.requireNonNull(values, name);
        ArrayList<T> copy = new ArrayList<>(values.size());
        for (T value : values) {
            copy.add(Objects.requireNonNull(value, name + " entry"));
        }
        return List.copyOf(copy);
    }

    static <T> Set<T> set(Collection<? extends T> values, String name) {
        Objects.requireNonNull(values, name);
        LinkedHashSet<T> copy = new LinkedHashSet<>();
        for (T value : values) {
            copy.add(Objects.requireNonNull(value, name + " entry"));
        }
        return Collections.unmodifiableSet(copy);
    }

    static <T> Map<String, T> stringMap(Map<String, ? extends T> values, String name) {
        Objects.requireNonNull(values, name);
        LinkedHashMap<String, T> copy = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException(name + " keys must be non-blank");
            }
            copy.put(key, Objects.requireNonNull(value, name + " values"));
        });
        return Collections.unmodifiableMap(copy);
    }

    static Map<String, TypedValue> unknown(Map<String, ? extends TypedValue> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, TypedValue> copy = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("Unknown field names must be non-blank");
            }
            copy.put(key, Objects.requireNonNull(value, "Unknown field values"));
        });
        return Collections.unmodifiableMap(copy);
    }

    static String requiredText(String value, String name, int maximum) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.length() > maximum) {
            throw new IllegalArgumentException(name + " must contain 1 to " + maximum + " characters");
        }
        return value;
    }

    static String optionalText(String value, String name, int maximum) {
        if (value == null) {
            return null;
        }
        if (value.length() > maximum) {
            throw new IllegalArgumentException(name + " must not exceed " + maximum + " characters");
        }
        return value;
    }

    static long revision(long revision, String name) {
        if (revision < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return revision;
    }

    static int limit(int limit) {
        if (limit < 1 || limit > 500) {
            throw new IllegalArgumentException("Limit must be between 1 and 500");
        }
        return limit;
    }

}
