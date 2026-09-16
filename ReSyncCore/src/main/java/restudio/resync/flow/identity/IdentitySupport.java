package restudio.resync.flow.identity;

import restudio.resync.contract.canonical.CanonicalArrays;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class IdentitySupport {
    private IdentitySupport() {
    }

    public static Map<String, Object> unknown(Map<String, ?> values, String name) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : values.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException(name + " keys are required");
            }
            copy.put(key, freeze(entry.getValue(), name + "." + key, new IdentityHashMap<>()));
        }
        return Collections.unmodifiableMap(copy);
    }

    public static Map<String, Object> merge(Map<String, Object> unknown, Map<String, Object> known) {
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

    private static Object freeze(Object value, String name, IdentityHashMap<Object, Boolean> active) {
        if (value == null || value instanceof String || value instanceof Boolean || value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long || value instanceof BigInteger || value instanceof BigDecimal || value instanceof UUID || value instanceof ContractRef<?> || value instanceof ResourceKey || value instanceof ServerResourceLocator) {
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
            enter(value, name, active);
            try {
                LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : source.entrySet()) {
                    if (!(entry.getKey() instanceof String key)) {
                        throw new IllegalArgumentException(name + " keys must be strings");
                    }
                    copy.put(key, freeze(entry.getValue(), name + "." + key, active));
                }
                return Collections.unmodifiableMap(copy);
            } finally {
                active.remove(value);
            }
        }
        if (value instanceof Iterable<?> iterable) {
            enter(value, name, active);
            try {
                ArrayList<Object> copy = new ArrayList<>();
                for (Object entry : iterable) {
                    copy.add(freeze(entry, name + "[]", active));
                }
                return Collections.unmodifiableList(copy);
            } finally {
                active.remove(value);
            }
        }
        Object[] array = CanonicalArrays.boxed(value);
        if (array != null) {
            enter(value, name, active);
            try {
                ArrayList<Object> copy = new ArrayList<>(array.length);
                for (Object entry : array) {
                    copy.add(freeze(entry, name + "[]", active));
                }
                return Collections.unmodifiableList(copy);
            } finally {
                active.remove(value);
            }
        }
        throw new IllegalArgumentException("Unsupported " + name + " value");
    }

    private static void enter(Object value, String name, IdentityHashMap<Object, Boolean> active) {
        if (active.put(value, Boolean.TRUE) != null) {
            throw new IllegalArgumentException(name + " contains a cycle");
        }
    }

    private static Object canonical(Object value) {
        if (value instanceof ContractRef<?> reference) {
            return reference.canonicalValue();
        }
        if (value instanceof ResourceKey key) {
            return key.canonicalValue();
        }
        if (value instanceof ServerResourceLocator locator) {
            return locator.canonicalValue();
        }
        if (value instanceof Map<?, ?> map) {
            LinkedHashMap<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new IllegalArgumentException("Canonical identity maps require string keys");
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
        return value;
    }
}
