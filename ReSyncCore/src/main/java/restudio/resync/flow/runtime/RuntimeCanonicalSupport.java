package restudio.resync.flow.runtime;

import restudio.resync.contract.canonical.CanonicalArrays;
import restudio.resync.contract.canonical.CanonicalText;
import restudio.resync.contract.diagnostic.DiagnosticCodeCatalog;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.type.TypeExpr;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

final class RuntimeCanonicalSupport {
    private static final Set<String> PRESENTATION_FIELDS = Set.of(
        "category", "description", "displayName", "editor", "order", "position", "presentation", "preview", "title");
    private static final Pattern LOCAL_ID = Pattern.compile("^[a-z][a-z0-9]{0,31}(?:[._-][a-z0-9][a-z0-9]{0,31})*$");
    private static final Pattern DIAGNOSTIC_CODE = Pattern.compile("^[A-Z][A-Z0-9_]{1,31}(?:\\.[A-Z][A-Z0-9_]{1,63})+$");

    private RuntimeCanonicalSupport() {
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
            unknown.forEach((key, value) -> result.put(key, canonical(value)));
        }
        known.forEach((key, value) -> {
            if (result.containsKey(key)) {
                throw new IllegalArgumentException("Unknown data collides with known field: " + key);
            }
            result.put(key, value);
        });
        return Collections.unmodifiableMap(result);
    }

    static void rejectCollisions(Map<String, Object> unknown, String name, Set<String> knownFields) {
        if (unknown == null) {
            return;
        }
        for (String field : knownFields) {
            if (unknown.containsKey(field)) {
                throw new IllegalArgumentException(name + " collides with known field: " + field);
            }
        }
    }

    static Map<String, Object> canonicalMap(Map<String, ?> values, String name) {
        Map<String, Object> checked = unknown(values, name);
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        checked.forEach((key, value) -> result.put(key, canonical(value)));
        return Collections.unmodifiableMap(result);
    }

    static Map<String, Object> combineUnknown(Map<String, ?> first, Map<String, ?> second, String name) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>(unknown(first, name + " first"));
        unknown(second, name + " second").forEach((key, value) -> {
            if (result.containsKey(key)) {
                throw new IllegalArgumentException(name + " contains duplicate unknown field: " + key);
            }
            result.put(key, value);
        });
        return Collections.unmodifiableMap(result);
    }

    static Map<String, Object> execution(Map<String, Object> unknown, Map<String, Object> known) {
        return mapExecution(merge(unknown, known));
    }

    private static Map<String, Object> mapExecution(Map<String, Object> values) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            if (!PRESENTATION_FIELDS.contains(key)) {
                result.put(key, execution(value));
            }
        });
        return Collections.unmodifiableMap(result);
    }

    private static Object execution(Object value) {
        if (value instanceof ContractRef<?> reference) {
            return execution(reference.canonicalValue());
        }
        if (value instanceof TypeExpr expression) {
            return execution(expression.canonicalValue());
        }
        if (value instanceof ContentHash hash) {
            return hash.canonicalText();
        }
        if (value instanceof Map<?, ?> map) {
            LinkedHashMap<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new IllegalArgumentException("Canonical runtime maps require string keys");
                }
                if (!PRESENTATION_FIELDS.contains(key)) {
                    result.put(key, execution(entry.getValue()));
                }
            }
            return Collections.unmodifiableMap(result);
        }
        if (value instanceof Set<?>) {
            throw new IllegalArgumentException("Canonical runtime values cannot contain an unordered collection");
        }
        if (value instanceof Iterable<?> iterable) {
            ArrayList<Object> result = new ArrayList<>();
            for (Object entry : iterable) {
                result.add(execution(entry));
            }
            return Collections.unmodifiableList(result);
        }
        return value;
    }

    static Set<String> localIds(Set<String> values, String label) {
        return identifiers(values, label, LOCAL_ID, "local ID");
    }

    static Set<String> diagnosticCodes(Set<String> values, String label) {
        return identifiers(values, label, DIAGNOSTIC_CODE, "diagnostic code");
    }

    private static Set<String> identifiers(Set<String> values, String label, Pattern pattern, String kind) {
        Objects.requireNonNull(values, label + " Are Required");
        TreeSet<String> sorted = new TreeSet<>();
        for (String value : values) {
            String checked = Objects.requireNonNull(value, label + " Cannot Contain Null");
            if (!checked.equals(checked.trim()) || checked.isEmpty() || checked.length() > 128
                || !CanonicalText.isNfc(checked) || !pattern.matcher(checked).matches()) {
                throw new IllegalArgumentException("Invalid " + kind + " in " + label + ": " + checked);
            }
            if (pattern == DIAGNOSTIC_CODE && !DiagnosticCodeCatalog.defaultCatalog().contains(checked)) {
                throw new IllegalArgumentException("Unknown diagnostic code in " + label + ": " + checked);
            }
            sorted.add(checked);
        }
        return Collections.unmodifiableSet(sorted);
    }

    private static Object freeze(Object value, String name) {
        if (value == null || value instanceof String || value instanceof Boolean || value instanceof Byte
            || value instanceof Short || value instanceof Integer || value instanceof Long
            || value instanceof BigInteger || value instanceof BigDecimal || value instanceof UUID
            || value instanceof ContentHash || value instanceof ContractRef<?> || value instanceof TypeExpr) {
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
                    throw new IllegalArgumentException(name + " keys must be strings");
                }
                copy.put(key, freeze(entry.getValue(), name + "." + key));
            }
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof Set<?>) {
            throw new IllegalArgumentException(name + " cannot contain an unordered collection");
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

    private static Object canonical(Object value) {
        if (value instanceof ContractRef<?> reference) {
            return reference.canonicalValue();
        }
        if (value instanceof TypeExpr expression) {
            return expression.canonicalValue();
        }
        if (value instanceof ContentHash hash) {
            return hash.canonicalText();
        }
        if (value instanceof Map<?, ?> map) {
            LinkedHashMap<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new IllegalArgumentException("Canonical runtime maps require string keys");
                }
                result.put(key, canonical(entry.getValue()));
            }
            return Collections.unmodifiableMap(result);
        }
        if (value instanceof Set<?>) {
            throw new IllegalArgumentException("Canonical runtime values cannot contain an unordered collection");
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
