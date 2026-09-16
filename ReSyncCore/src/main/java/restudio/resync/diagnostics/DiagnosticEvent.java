package restudio.resync.diagnostics;

import java.util.Map;
import java.util.Objects;

public record DiagnosticEvent(
    String stage,
    DiagnosticSink.Priority priority,
    DiagnosticIdentity identity,
    Map<String, DiagnosticValue> values,
    long elapsedMillis
) {
    public DiagnosticEvent {
        stage = stage(stage);
        priority = Objects.requireNonNull(priority, "Diagnostic priority is required");
        identity = identity == null ? DiagnosticIdentity.empty() : identity;
        values = values(values);
        if (elapsedMillis < 0L) {
            throw new IllegalArgumentException("Diagnostic elapsed time cannot be negative");
        }
    }

    public DiagnosticEvent(String stage, DiagnosticIdentity identity, Map<String, DiagnosticValue> values,
                           long elapsedMillis) {
        this(stage, DiagnosticSink.Priority.NORMAL, identity, values, elapsedMillis);
    }

    public static DiagnosticEvent of(String stage, DiagnosticSink.Priority priority, DiagnosticIdentity identity,
                                     Map<String, ?> values, long elapsedMillis) {
        return new DiagnosticEvent(stage, priority, identity, DiagnosticValue.fields(values), elapsedMillis);
    }

    public static DiagnosticEvent of(String stage, DiagnosticIdentity identity, Map<String, ?> values,
                                     long elapsedMillis) {
        return of(stage, DiagnosticSink.Priority.NORMAL, identity, values, elapsedMillis);
    }

    public DiagnosticValue value(String name) {
        return values.get(name);
    }

    private static String stage(String value) {
        Objects.requireNonNull(value, "Diagnostic stage is required");
        if (value.isEmpty() || value.length() > 128 || !Character.isLetter(value.charAt(0))) {
            throw new IllegalArgumentException("Invalid diagnostic stage: " + value);
        }
        for (int index = 1; index < value.length(); index++) {
            char character = value.charAt(index);
            if (!(Character.isLetterOrDigit(character) || character == '_' || character == '-' || character == '.')) {
                throw new IllegalArgumentException("Invalid diagnostic stage: " + value);
            }
        }
        return value;
    }

    private static Map<String, DiagnosticValue> values(Map<String, DiagnosticValue> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        return DiagnosticValue.fields(source);
    }
}
