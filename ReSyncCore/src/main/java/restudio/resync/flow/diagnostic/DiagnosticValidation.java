package restudio.resync.flow.diagnostic;

import restudio.resync.contract.canonical.CanonicalArrays;
import restudio.resync.contract.canonical.CanonicalUuids;
import restudio.resync.contract.diagnostic.DiagnosticCodeCatalog;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;

final class DiagnosticValidation {
    private static final Pattern CODE = Pattern.compile("^[A-Z][A-Z0-9_]{1,31}(?:\\.[A-Z][A-Z0-9_]{1,63})+$");
    private static final Pattern LOCAL_ID = Pattern.compile("^[a-z][a-z0-9]{0,31}(?:[._-][a-z0-9][a-z0-9]{0,31})*$");
    private static final Pattern HASH = Pattern.compile("^[0-9a-f]{64}$");
    private static final String REDACTED = "[REDACTED]";

    private DiagnosticValidation() {
    }

    static String code(String value) {
        String normalized = required(value, "code");
        if (!CODE.matcher(normalized).matches()) {
            throw new IllegalArgumentException("Invalid diagnostic code: " + normalized);
        }
        return normalized;
    }

    static String code(String value, DiagnosticCodeCatalog catalog) {
        String normalized = code(value);
        Objects.requireNonNull(catalog, "Diagnostic catalog is required").require(normalized);
        return normalized;
    }

    static String localId(String value, String field) {
        String normalized = required(value, field);
        if (normalized.length() > 128 || !LOCAL_ID.matcher(normalized).matches()) {
            throw new IllegalArgumentException("Invalid diagnostic local ID: " + normalized);
        }
        return normalized;
    }

    static String sourceHash(String value) {
        String normalized = required(value, "sourceHash");
        if (!HASH.matcher(normalized).matches()) {
            throw new IllegalArgumentException("Invalid diagnostic source hash");
        }
        return normalized;
    }

    static String text(String value, String field, int maximum) {
        String normalized = required(value, field);
        if (normalized.length() > maximum) {
            throw new IllegalArgumentException(field + " exceeds " + maximum + " characters");
        }
        return normalized;
    }

    static String optionalText(String value, String field, int maximum) {
        if (value == null) {
            return null;
        }
        return text(value, field, maximum);
    }

    static UUID uuid(UUID value, String field) {
        UUID normalized = Objects.requireNonNull(value, field);
        if (CanonicalUuids.variant(normalized) != 2 || CanonicalUuids.version(normalized) < 1 || CanonicalUuids.version(normalized) > 8) {
            throw new IllegalArgumentException(field + " must be an RFC 4122 UUID");
        }
        return normalized;
    }

    static UUID optionalUuid(UUID value, String field) {
        return value == null ? null : uuid(value, field);
    }

    static Map<String, Object> immutableMap(Map<String, ?> source, String field) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        TreeMap<String, Object> ordered = new TreeMap<>();
        for (Map.Entry<String, ?> entry : source.entrySet()) {
            String key = Objects.requireNonNull(entry.getKey(), field + " key");
            ordered.put(key, immutableValue(entry.getValue(), field + "." + key));
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(ordered));
    }

    static void rejectUnknownCollisions(Map<String, ?> unknown, String field, Set<String> known) {
        for (String key : unknown.keySet()) {
            if (known.contains(key)) {
                throw new IllegalArgumentException(field + " unknown field collides with known field: " + key);
            }
        }
    }

    static Object immutableValue(Object value, String field) {
        if (value == null || value instanceof String || value instanceof Boolean || value instanceof BigInteger || value instanceof BigDecimal) {
            return value;
        }
        if (value instanceof Character character) {
            return character.toString();
        }
        if (value instanceof UUID || value instanceof Instant || value instanceof Enum<?>) {
            return value.toString();
        }
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            return value;
        }
        if (value instanceof Float floatValue) {
            if (!Float.isFinite(floatValue)) {
                throw new IllegalArgumentException(field + " must contain finite numbers");
            }
            return BigDecimal.valueOf(floatValue.doubleValue());
        }
        if (value instanceof Double doubleValue) {
            if (!Double.isFinite(doubleValue)) {
                throw new IllegalArgumentException(field + " must contain finite numbers");
            }
            return BigDecimal.valueOf(doubleValue);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> normalized = new TreeMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new IllegalArgumentException(field + " keys must be strings");
                }
                normalized.put(key, immutableValue(entry.getValue(), field + "." + key));
            }
            return Collections.unmodifiableMap(new LinkedHashMap<>(normalized));
        }
        if (value instanceof Collection<?> collection) {
            List<Object> normalized = new ArrayList<>(collection.size());
            for (Object element : collection) {
                normalized.add(immutableValue(element, field + "[]"));
            }
            if (value instanceof Set<?>) {
                normalized.sort(Comparator.comparing(DiagnosticJson::write));
            }
            return List.copyOf(normalized);
        }
        Object[] array = CanonicalArrays.boxed(value);
        if (array != null) {
            List<Object> normalized = new ArrayList<>(array.length);
            for (Object element : array) {
                normalized.add(immutableValue(element, field + "[]"));
            }
            return List.copyOf(normalized);
        }
        throw new IllegalArgumentException("Unsupported diagnostic value at " + field);
    }

    static Map<String, Object> redactedMap(Map<String, Object> source, DiagnosticRedaction level) {
        if (source.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> redacted = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            redacted.put(entry.getKey(), redactValue(entry.getKey(), entry.getValue(), level));
        }
        return Collections.unmodifiableMap(redacted);
    }

    static Object redactValue(String key, Object value, DiagnosticRedaction level) {
        if (sensitiveKey(key)) {
            return REDACTED;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> nested = new TreeMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                nested.put((String) entry.getKey(), redactValue((String) entry.getKey(), entry.getValue(), level));
            }
            return Collections.unmodifiableMap(new LinkedHashMap<>(nested));
        }
        if (value instanceof List<?> list) {
            List<Object> nested = new ArrayList<>(list.size());
            for (Object element : list) {
                nested.add(redactValue("", element, level));
            }
            return List.copyOf(nested);
        }
        return value;
    }

    static String redactedUri(String uri, DiagnosticRedaction level) {
        if (uri == null) {
            return uri;
        }
        int query = uri.indexOf('?');
        int fragment = uri.indexOf('#');
        int end = query >= 0 ? query : fragment;
        if (query >= 0 && fragment >= 0) {
            end = Math.min(query, fragment);
        }
        return end < 0 ? uri : uri.substring(0, end) + REDACTED;
    }

    static boolean sensitiveKey(String key) {
        String normalized = key == null ? "" : key.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
        return normalized.contains("password")
            || normalized.contains("passwd")
            || normalized.contains("secret")
            || normalized.contains("token")
            || normalized.contains("authorization")
            || normalized.contains("credential")
            || normalized.contains("privatekey")
            || normalized.contains("apikey")
            || normalized.contains("accesskey")
            || normalized.contains("cookie");
    }

    private static String required(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized;
    }
}
