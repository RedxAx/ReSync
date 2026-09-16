package restudio.resync.flow.graph;

import restudio.resync.contract.canonical.CanonicalArrays;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ImmutableData {
    private ImmutableData() {
    }

    static Map<String, Object> map(Map<String, ?> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (key == null || key.isEmpty()) {
                throw new IllegalArgumentException("Unknown data keys are required");
            }
            copy.put(key, freeze(value));
        });
        return Collections.unmodifiableMap(copy);
    }

    static Object freeze(Object value) {
        if (value == null || value instanceof String || value instanceof Boolean || value instanceof BigInteger || value instanceof BigDecimal) {
            return value;
        }
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            return value;
        }
        if (value instanceof Float || value instanceof Double) {
            double number = ((Number) value).doubleValue();
            if (!Double.isFinite(number)) {
                throw new IllegalArgumentException("Unknown data numbers must be finite");
            }
            return value;
        }
        if (value instanceof Map<?, ?> map) {
            LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, nested) -> {
                if (!(key instanceof String stringKey) || stringKey.isEmpty()) {
                    throw new IllegalArgumentException("Unknown data object keys must be non-empty strings");
                }
                copy.put(stringKey, freeze(nested));
            });
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof Iterable<?> iterable) {
            ArrayList<Object> copy = new ArrayList<>();
            iterable.forEach(item -> copy.add(freeze(item)));
            return Collections.unmodifiableList(copy);
        }
        Object[] array = CanonicalArrays.boxed(value);
        if (array != null) {
            ArrayList<Object> copy = new ArrayList<>(array.length);
            for (Object item : array) {
                copy.add(freeze(item));
            }
            return Collections.unmodifiableList(copy);
        }
        throw new IllegalArgumentException("Unknown data must contain JSON-compatible values");
    }

    static List<Object> list(List<?> source) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        ArrayList<Object> copy = new ArrayList<>(source.size());
        source.forEach(value -> copy.add(freeze(value)));
        return List.copyOf(copy);
    }
}
