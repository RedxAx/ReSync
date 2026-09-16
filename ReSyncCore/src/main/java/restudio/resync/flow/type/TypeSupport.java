package restudio.resync.flow.type;

import restudio.resync.contract.canonical.CanonicalArrays;
import restudio.resync.flow.identity.ServerResourceLocator;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class TypeSupport {
    private TypeSupport() {
    }

    static Map<String, Object> unknown(Map<String, ?> values, String name) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : values.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException(name + " keys are required");
            }
            copy.put(key, freeze(entry.getValue(), name + "." + key));
        }
        return Collections.unmodifiableMap(copy);
    }

    static Map<String, Object> merge(Map<String, Object> unknown, Map<String, Object> known) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        if (unknown != null) {
            for (Map.Entry<String, Object> entry : unknown.entrySet()) {
                result.put(entry.getKey(), canonical(entry.getValue()));
            }
        }
        for (Map.Entry<String, Object> entry : known.entrySet()) {
            if (result.containsKey(entry.getKey())) {
                throw new IllegalArgumentException("Unknown data collides with known field: " + entry.getKey());
            }
            result.put(entry.getKey(), entry.getValue());
        }
        return Collections.unmodifiableMap(result);
    }

    static Object freeze(Object value, String name) {
        if (value == null || value instanceof String || value instanceof Boolean || value instanceof BigInteger || value instanceof BigDecimal || value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long || value instanceof UUID || value instanceof TypeReference || value instanceof TypeExpr || value instanceof TypedValue || value instanceof ServerResourceLocator) {
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
        if (value instanceof Map<?, ?> source) {
            LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : source.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new IllegalArgumentException(name + " keys must be non-empty strings");
                }
                copy.put(key, freeze(entry.getValue(), name + "." + key));
            }
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof Iterable<?> iterable) {
            ArrayList<Object> copy = new ArrayList<>();
            for (Object entry : iterable) {
                copy.add(freeze(entry, name + "[]"));
            }
            return Collections.unmodifiableList(copy);
        }
        Object[] array = CanonicalArrays.boxed(value);
        if (array != null) {
            ArrayList<Object> copy = new ArrayList<>(array.length);
            for (Object entry : array) {
                copy.add(freeze(entry, name + "[]"));
            }
            return Collections.unmodifiableList(copy);
        }
        throw new IllegalArgumentException("Unsupported " + name + " value");
    }

    static Object canonical(Object value) {
        if (value instanceof TypeReference reference) {
            return reference.canonicalValue();
        }
        if (value instanceof TypeExpr expression) {
            return expression.canonicalValue();
        }
        if (value instanceof TypedValue typedValue) {
            return typedValue.canonicalValue();
        }
        if (value instanceof ServerResourceLocator locator) {
            return locator.canonicalValue();
        }
        if (value instanceof Map<?, ?> map) {
            LinkedHashMap<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new IllegalArgumentException("Canonical type maps require string keys");
                }
                result.put(key, canonical(entry.getValue()));
            }
            return Collections.unmodifiableMap(result);
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> result = new ArrayList<>();
            for (Object entry : iterable) {
                result.add(canonical(entry));
            }
            return Collections.unmodifiableList(result);
        }
        if (value instanceof Enum<?> enumValue) {
            return enumValue.name();
        }
        return value;
    }
}
