package restudio.resync.server;

import restudio.resync.contract.identity.Revision;
import restudio.resync.diagnostics.DiagnosticEvent;
import restudio.resync.diagnostics.DiagnosticSink;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

final class LifecycleDiagnosticPolicy {
    static final String MODE_PROPERTY = "resync.diagnostics.lifecycle.mode";
    static final String MODE_ENVIRONMENT = "RESYNC_DIAGNOSTICS_LIFECYCLE";
    static final String LEGACY_PROPERTY = "resync.lifecycleDiagnostics";
    static final String LEGACY_ENVIRONMENT = "RESYNC_LIFECYCLE_DIAGNOSTICS";
    static final int MAX_FIELDS = 32;
    static final int MAX_VALUE_CHARS = 240;
    static final int MAX_LINE_BYTES = 4096;
    static final int MAX_COLLECTION_ITEMS = 16;
    static final int MAX_COLLECTION_DEPTH = 3;
    static final int CRITICAL_CAPACITY = 512;
    static final int NORMAL_CAPACITY = 4096;
    static final int MAX_BATCH_SIZE = 256;
    static final long MAX_BATCH_DELAY_MILLIS = 100L;
    static final long MAX_FILE_BYTES = 16L * 1024L * 1024L;
    static final long RETENTION_AGE_MILLIS = 7L * 24L * 60L * 60L * 1000L;
    static final long RETENTION_BYTES = 128L * 1024L * 1024L;
    static final long CLOSE_TIMEOUT_MILLIS = 2_000L;
    static final long PROGRESS_THROTTLE_NANOS = 250_000_000L;
    static final Set<String> SENSITIVE_FIELDS = Set.of(
        "payload", "canonicalpayload", "authorization", "token", "secret", "password", "credential",
        "rawsession", "session", "user", "actor", "client", "connection", "address", "endpoint", "ip",
        "path", "sql", "stack", "exception"
    );
    private static final Pattern SENSITIVE_ASSIGNMENT = Pattern.compile(
        "(?i)\\b(?:payload|canonicalpayload|authorization|token|secret|password|credential|rawsession|session|user|address|endpoint|ip|path|sql|stack|exception)\\b\\s*[:=]"
    );
    private static final Pattern ABSOLUTE_PATH = Pattern.compile(
        "(?i)[a-z]:[\\\\/]|/(?:[^\\s/]+/)+"
    );
    private static final Pattern SQL_TEXT = Pattern.compile(
        "(?i)\\b(?:select|insert|update|delete|alter|create|drop|pragma)\\b.+\\b(?:from|into|table|where|set)\\b"
    );
    private static final Pattern STACK_TEXT = Pattern.compile(
        "(?i)(?:^|\\s)(?:at\\s+[A-Za-z0-9_$.-]+\\.[A-Za-z0-9_$.-]+\\(|[A-Za-z0-9_$.-]+Exception(?:\\s|$))"
    );
    private static final Pattern FAILURE_TYPE = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)*");

    private LifecycleDiagnosticPolicy() {
    }

    static DiagnosticSink.Mode resolveMode(String modeProperty, String modeEnvironment,
                                           String legacyProperty, String legacyEnvironment) {
        String explicit = firstNonBlank(modeProperty, modeEnvironment);
        if (!explicit.isBlank()) {
            return parseMode(explicit);
        }
        String legacy = firstNonBlank(legacyProperty, legacyEnvironment);
        return legacy.isBlank() || truthy(legacy) ? DiagnosticSink.Mode.RECOVERY : DiagnosticSink.Mode.OFF;
    }

    static DiagnosticSink.Mode resolveFromProcess() {
        return resolveMode(
            readProperty(MODE_PROPERTY), readEnvironment(MODE_ENVIRONMENT),
            readProperty(LEGACY_PROPERTY), readEnvironment(LEGACY_ENVIRONMENT));
    }

    static DiagnosticSink.Mode parseMode(String value) {
        if (value == null || value.isBlank()) {
            return DiagnosticSink.Mode.OFF;
        }
        return switch (value.strip().toLowerCase(Locale.ROOT)) {
            case "on", "true", "yes", "1", "recovery" -> DiagnosticSink.Mode.RECOVERY;
            case "normal", "important" -> DiagnosticSink.Mode.NORMAL;
            case "verbose", "trace", "all" -> DiagnosticSink.Mode.VERBOSE;
            case "off", "false", "no", "0", "disabled" -> DiagnosticSink.Mode.OFF;
            default -> DiagnosticSink.Mode.OFF;
        };
    }

    static DiagnosticSink.Priority priority(String stage, Map<String, ?> values, boolean terminal) {
        if (terminal) {
            return DiagnosticSink.Priority.TERMINAL;
        }
        String normalizedStage = stage == null ? "" : stage.toLowerCase(Locale.ROOT);
        if (normalizedStage.contains("terminal")) {
            return DiagnosticSink.Priority.TERMINAL;
        }
        String outcome = text(values == null ? null : values.get("outcome"));
        String failure = (normalizedStage + " " + outcome + " "
            + text(values == null ? null : values.get("reason")) + " "
            + text(values == null ? null : values.get("status"))).toLowerCase(Locale.ROOT);
        if (containsAny(failure, "fail", "error", "timeout", "conflict", "reject", "blocked", "unavailable", "exception", "exhausted", "stalled")) {
            return DiagnosticSink.Priority.IMPORTANT;
        }
        return DiagnosticSink.Priority.NORMAL;
    }

    static boolean healthyProgress(DiagnosticEvent event) {
        if (event == null || event.elapsedMillis() >= 250L || event.priority().atLeast(DiagnosticSink.Priority.IMPORTANT)) {
            return false;
        }
        String stage = event.stage().toLowerCase(Locale.ROOT);
        return stage.endsWith("_list_page") || stage.endsWith("_list_progress")
            || stage.equals("render_index_published") || stage.endsWith("_render_publication");
    }

    static String progressKey(DiagnosticEvent event) {
        StringBuilder key = new StringBuilder(event.stage());
        if (event.identity() != null && event.identity().serverId() != null) {
            key.append('|').append(event.identity().serverId().canonicalText());
        }
        if (event.identity() != null && event.identity().requestId() != null) {
            key.append('|').append(event.identity().requestId());
        }
        if (event.identity() != null && event.identity().generation() != null) {
            key.append('|').append(event.identity().generation());
        }
        if (event.identity() != null && event.identity().typedKey() != null) {
            key.append('|').append(event.identity().typedKey().canonicalText());
        }
        if (event.identity() != null && event.identity().operation() != null) {
            key.append('|').append(event.identity().operation().canonicalText());
        }
        return key.toString();
    }

    static boolean sensitiveField(String field) {
        if (field == null || field.isBlank()) {
            return true;
        }
        String normalized = field.strip().toLowerCase(Locale.ROOT);
        if (normalized.endsWith("hash")) {
            return false;
        }
        if (SENSITIVE_FIELDS.contains(normalized)) {
            return true;
        }
        String tokenized = field.strip().replaceAll("([a-z0-9])([A-Z])", "$1_$2")
            .toLowerCase(Locale.ROOT);
        for (String token : tokenized.split("[_\\-.]")) {
            if (SENSITIVE_FIELDS.contains(token)) {
                return true;
            }
        }
        return false;
    }

    static Map<String, Object> sanitizeValues(Map<String, ?> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        IdentityHashMap<Object, Boolean> active = new IdentityHashMap<>();
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            if (result.size() >= MAX_FIELDS) {
                break;
            }
            String field = entry.getKey();
            if (!validField(field) || sensitiveField(field)) {
                continue;
            }
            Object value = entry.getValue();
            if (value instanceof String text) {
                result.put(field, safeFieldText(field, text));
            } else {
                result.put(field, sanitizeValue(value, 0, active));
            }
        }
        return result;
    }

    static String safeFieldText(String field, String value) {
        if (("errorType".equals(field) || "failureType".equals(field)) && value != null
            && value.length() <= MAX_VALUE_CHARS && FAILURE_TYPE.matcher(value).matches()) {
            return value;
        }
        return safeText(value);
    }

    static String safeText(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        String text = value.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ').strip();
        if (text.isEmpty()) {
            return "";
        }
        if (structured(text) || SENSITIVE_ASSIGNMENT.matcher(text).find() || ABSOLUTE_PATH.matcher(text).find()
            || SQL_TEXT.matcher(text).find() || STACK_TEXT.matcher(text).find()) {
            return "[redacted]";
        }
        return text.length() <= MAX_VALUE_CHARS ? text : text.substring(0, MAX_VALUE_CHARS - 3) + "...";
    }

    static String safeThread(String value) {
        String safe = safeText(value);
        return safe.isBlank() ? "unknown" : safe;
    }

    static boolean truthy(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        return switch (value.strip().toLowerCase(Locale.ROOT)) {
            case "0", "false", "no", "off", "disabled" -> false;
            default -> true;
        };
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second == null ? "" : second;
    }

    private static boolean structured(String text) {
        return text.length() > 1 && ((text.charAt(0) == '{' && text.charAt(text.length() - 1) == '}')
            || (text.charAt(0) == '[' && text.charAt(text.length() - 1) == ']'));
    }

    private static String text(Object value) {
        return value == null ? "" : safeText(String.valueOf(value));
    }

    private static Object sanitizeValue(Object value, int depth, IdentityHashMap<Object, Boolean> active) {
        if (value == null) {
            return "";
        }
        if (value instanceof String text) {
            return safeText(text);
        }
        if (value instanceof Character character) {
            return safeText(character.toString());
        }
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            return ((Number) value).longValue();
        }
        if (value instanceof BigInteger integer) {
            return integer.toString().length() <= MAX_VALUE_CHARS ? integer : "[redacted]";
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.toString().length() <= MAX_VALUE_CHARS ? decimal : "[redacted]";
        }
        if (value instanceof Float || value instanceof Double) {
            double decimal = ((Number) value).doubleValue();
            return Double.isFinite(decimal) ? BigDecimal.valueOf(decimal) : "[redacted]";
        }
        if (value instanceof Number number) {
            return safeText(number.toString());
        }
        if (value instanceof Revision revision) {
            return revision.value();
        }
        if (value instanceof UUID || value instanceof Enum<?>) {
            return safeText(value.toString());
        }
        if (depth >= MAX_COLLECTION_DEPTH) {
            return "[nested]";
        }
        if (value instanceof Map<?, ?> map) {
            if (active.put(value, Boolean.TRUE) != null) {
                return "[nested]";
            }
            try {
                LinkedHashMap<String, Object> result = new LinkedHashMap<>();
                int visited = 0;
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (visited >= MAX_COLLECTION_ITEMS) {
                        break;
                    }
                    visited++;
                    if (!(entry.getKey() instanceof String field) || !validField(field) || sensitiveField(field)) {
                        continue;
                    }
                    result.put(field, sanitizeValue(entry.getValue(), depth + 1, active));
                }
                if (map.size() > visited) {
                    result.put("truncated", true);
                }
                return result;
            } finally {
                active.remove(value);
            }
        }
        if (value instanceof Iterable<?> iterable) {
            if (active.put(value, Boolean.TRUE) != null) {
                return "[nested]";
            }
            try {
                List<Object> result = new ArrayList<>();
                Iterator<?> iterator = iterable.iterator();
                while (result.size() < MAX_COLLECTION_ITEMS && iterator.hasNext()) {
                    result.add(sanitizeValue(iterator.next(), depth + 1, active));
                }
                if (iterator.hasNext()) {
                    result.add("[truncated]");
                }
                return result;
            } finally {
                active.remove(value);
            }
        }
        if (value.getClass().isArray()) {
            if (active.put(value, Boolean.TRUE) != null) {
                return "[nested]";
            }
            try {
                int length = Array.getLength(value);
                List<Object> result = new ArrayList<>(Math.min(length, MAX_COLLECTION_ITEMS) + 1);
                int limit = Math.min(length, MAX_COLLECTION_ITEMS);
                for (int index = 0; index < limit; index++) {
                    result.add(sanitizeValue(Array.get(value, index), depth + 1, active));
                }
                if (length > limit) {
                    result.add("[truncated]");
                }
                return result;
            } finally {
                active.remove(value);
            }
        }
        return safeText(value.toString());
    }

    private static boolean validField(String field) {
        if (field == null || field.isEmpty() || field.length() > 128 || !Character.isLetter(field.charAt(0))) {
            return false;
        }
        for (int index = 1; index < field.length(); index++) {
            char character = field.charAt(index);
            if (!(Character.isLetterOrDigit(character) || character == '_' || character == '-' || character == '.')) {
                return false;
            }
        }
        return true;
    }

    private static boolean containsAny(String value, String... needles) {
        for (String needle : needles) {
            if (value.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static String readProperty(String name) {
        try {
            return System.getProperty(name);
        } catch (SecurityException ignored) {
            return null;
        }
    }

    private static String readEnvironment(String name) {
        try {
            return System.getenv(name);
        } catch (SecurityException ignored) {
            return null;
        }
    }
}
