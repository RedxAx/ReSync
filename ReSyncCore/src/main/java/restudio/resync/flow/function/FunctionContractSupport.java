package restudio.resync.flow.function;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import restudio.resync.contract.canonical.CanonicalArrays;
import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.identity.FunctionParameterId;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypedValue;

final class FunctionContractSupport {
    private FunctionContractSupport() {
    }

    static String text(String value, String label, int maximum) {
        Objects.requireNonNull(value, label + " Is Required");
        String normalized = CanonicalJson.requireNfc(value);
        if (normalized.isBlank() || !normalized.equals(normalized.strip()) || normalized.indexOf('\u0000') >= 0 || normalized.length() > maximum) {
            throw new IllegalArgumentException(label + " Must Be Canonical Non-Blank Text");
        }
        return normalized;
    }

    static String optionalText(String value, String label, int maximum) {
        return value == null ? null : text(value, label, maximum);
    }

    static Map<String, Object> immutableMap(Map<String, ?> source, String label) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            String normalized = text(key, label + " Key", 256);
            copy.put(normalized, freeze(value, label + " Value"));
        });
        return Collections.unmodifiableMap(copy);
    }

    static Object freeze(Object value, String label) {
        if (value == null || value instanceof String || value instanceof Boolean || value instanceof Character
            || value instanceof BigInteger || value instanceof BigDecimal || value instanceof Byte || value instanceof Short
            || value instanceof Integer || value instanceof Long || value instanceof UUID || value instanceof FunctionParameterId
            || value instanceof TypeExpr || value instanceof TypedValue || value instanceof FunctionLocator
            || value instanceof FunctionRevision || value instanceof FunctionCancellation) {
            return value;
        }
        if (value instanceof Float floatValue) {
            if (!Float.isFinite(floatValue)) {
                throw new IllegalArgumentException(label + " Contains A Non-Finite Number");
            }
            return BigDecimal.valueOf(floatValue.doubleValue());
        }
        if (value instanceof Double doubleValue) {
            if (!Double.isFinite(doubleValue)) {
                throw new IllegalArgumentException(label + " Contains A Non-Finite Number");
            }
            return BigDecimal.valueOf(doubleValue);
        }
        if (value instanceof Map<?, ?> source) {
            LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
            source.forEach((key, nested) -> {
                if (!(key instanceof String stringKey)) {
                    throw new IllegalArgumentException(label + " Map Keys Must Be Text");
                }
                copy.put(text(stringKey, label + " Map Key", 256), freeze(nested, label + " Map Value"));
            });
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof Iterable<?> iterable) {
            ArrayList<Object> copy = new ArrayList<>();
            iterable.forEach(item -> copy.add(freeze(item, label + " List Value")));
            return Collections.unmodifiableList(copy);
        }
        Object[] array = CanonicalArrays.boxed(value);
        if (array != null) {
            ArrayList<Object> copy = new ArrayList<>(array.length);
            for (Object item : array) {
                copy.add(freeze(item, label + " Array Value"));
            }
            return Collections.unmodifiableList(copy);
        }
        throw new IllegalArgumentException(label + " Contains Unsupported Data");
    }

    static Map<FunctionParameterId, TypedValue> typedValues(Map<FunctionParameterId, TypedValue> source, String label) {
        Objects.requireNonNull(source, label + " Are Required");
        List<Map.Entry<FunctionParameterId, TypedValue>> entries = new ArrayList<>(source.size());
        source.forEach((key, value) -> entries.add(Map.entry(
            Objects.requireNonNull(key, label + " Parameter IDs Cannot Be Null"),
            Objects.requireNonNull(value, label + " Values Cannot Be Null"))));
        entries.sort(Map.Entry.comparingByKey());
        LinkedHashMap<FunctionParameterId, TypedValue> copy = new LinkedHashMap<>();
        entries.forEach(entry -> copy.put(entry.getKey(), entry.getValue()));
        return Collections.unmodifiableMap(copy);
    }

    static Map<String, Object> canonicalTypedValues(Map<FunctionParameterId, TypedValue> values) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        values.forEach((key, value) -> result.put(key.canonicalText(), value.canonicalValue()));
        return Collections.unmodifiableMap(result);
    }

    static String canonicalJson(Object value) {
        return CanonicalJson.canonicalize(canonical(value));
    }

    static Object canonical(Object value) {
        if (value instanceof FunctionLocator locator) {
            return locator.canonicalValue();
        }
        if (value instanceof FunctionRevision revision) {
            return revision.value();
        }
        if (value instanceof FunctionCancellation cancellation) {
            return cancellation.canonicalValue();
        }
        if (value instanceof TypeExpr expression) {
            return expression.canonicalValue();
        }
        if (value instanceof TypedValue typedValue) {
            return typedValue.canonicalValue();
        }
        if (value instanceof Map<?, ?> source) {
            LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
            source.forEach((key, nested) -> {
                if (!(key instanceof String stringKey)) {
                    throw new IllegalArgumentException("Canonical Function Data Map Keys Must Be Text");
                }
                copy.put(stringKey, canonical(nested));
            });
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof Iterable<?> iterable) {
            ArrayList<Object> copy = new ArrayList<>();
            iterable.forEach(item -> copy.add(canonical(item)));
            return Collections.unmodifiableList(copy);
        }
        return value;
    }
}
