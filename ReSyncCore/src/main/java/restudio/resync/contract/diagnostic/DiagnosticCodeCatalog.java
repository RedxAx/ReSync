package restudio.resync.contract.diagnostic;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

import restudio.resync.contract.canonical.CanonicalCodec;
import restudio.resync.contract.canonical.CanonicalHash;
import restudio.resync.contract.canonical.JsonValue;
import restudio.resync.flow.diagnostic.DiagnosticPhase;
import restudio.resync.flow.diagnostic.DiagnosticSeverity;

public final class DiagnosticCodeCatalog {
    public static final int SCHEMA_VERSION = 1;
    public static final String FROZEN_SOURCE_SHA256 = "fd7de157afb4b93c6be9764de4d388c17dd59d4dd204a0440f4792fcd33f8b6f";
    private static final Pattern CODE = Pattern.compile("^[A-Z][A-Z0-9_]{1,31}(?:\\.[A-Z][A-Z0-9_]{1,63})+$");
    private static final Pattern LOCAL_ID = Pattern.compile("^[a-z][a-z0-9]{0,31}(?:[._-][a-z0-9][a-z0-9]{0,31})*$");
    private static volatile DiagnosticCodeCatalog installed;

    private final int schemaVersion;
    private final Map<String, Definition> definitions;
    private final Map<String, Object> unknown;
    private final String sourceHash;
    private final boolean frozen;

    public DiagnosticCodeCatalog(Collection<Definition> definitions) {
        this(SCHEMA_VERSION, definitions, Map.of());
    }

    public DiagnosticCodeCatalog(int schemaVersion, Collection<Definition> definitions, Map<String, ?> unknown) {
        this(schemaVersion, definitions, unknown, false);
    }

    private DiagnosticCodeCatalog(int schemaVersion, Collection<Definition> definitions, Map<String, ?> unknown, boolean frozen) {
        if (schemaVersion != SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported diagnostic catalog schema version: " + schemaVersion);
        }
        this.schemaVersion = schemaVersion;
        Objects.requireNonNull(definitions, "Diagnostic definitions are required");
        LinkedHashMap<String, Definition> copied = new LinkedHashMap<>();
        for (Definition definition : definitions) {
            Definition normalized = Objects.requireNonNull(definition, "Diagnostic definition is required");
            if (copied.put(normalized.code(), normalized) != null) {
                throw new IllegalArgumentException("Duplicate diagnostic code: " + normalized.code());
            }
        }
        List<Definition> ordered = new ArrayList<>(copied.values());
        ordered.sort((left, right) -> compareCodePoints(left.code(), right.code()));
        copied.clear();
        for (Definition definition : ordered) {
            copied.put(definition.code(), definition);
        }
        this.definitions = Collections.unmodifiableMap(copied);
        this.unknown = immutableMap(unknown, "catalog unknown");
        rejectUnknownCollisions(this.unknown, Set.of("schemaVersion", "codes"), "catalog");
        this.sourceHash = CanonicalHash.sha256(JsonValue.fromJava(toMap()));
        this.frozen = frozen;
    }

    public static DiagnosticCodeCatalog of(Collection<Definition> definitions) {
        return new DiagnosticCodeCatalog(definitions);
    }

    public static DiagnosticCodeCatalog fromJson(String json) {
        Objects.requireNonNull(json, "Diagnostic catalog JSON is required");
        JsonValue value = CanonicalCodec.decodePermissive(json);
        if (!(value instanceof JsonValue.JsonObject object)) {
            throw new IllegalArgumentException("Diagnostic catalog must be a JSON object");
        }
        Map<String, Object> document = stringMap((Map<?, ?>) object.toJava(), "diagnostic catalog");
        int version = integer(document.get("schemaVersion"), "schemaVersion");
        Object rawCodes = document.get("codes");
        if (!(rawCodes instanceof List<?> values)) {
            throw new IllegalArgumentException("Diagnostic catalog codes must be an array");
        }
        List<Definition> definitions = new ArrayList<>(values.size());
        for (Object raw : values) {
            if (!(raw instanceof Map<?, ?> map)) {
                throw new IllegalArgumentException("Diagnostic catalog entries must be objects");
            }
            definitions.add(Definition.fromMap(stringMap(map, "diagnostic definition")));
        }
        requireStrictOrder(definitions);
        return new DiagnosticCodeCatalog(version, definitions, unknown(document, Set.of("schemaVersion", "codes")));
    }

    public static DiagnosticCodeCatalog fromBytes(byte[] bytes) {
        Objects.requireNonNull(bytes, "Diagnostic catalog bytes are required");
        String rawHash = CanonicalHash.rawSha256(bytes);
        if (!FROZEN_SOURCE_SHA256.equals(rawHash)) {
            throw new IllegalArgumentException("Diagnostic catalog does not match the frozen generation-1 source hash");
        }
        DiagnosticCodeCatalog parsed = fromJson(new String(bytes, StandardCharsets.UTF_8));
        return new DiagnosticCodeCatalog(parsed.schemaVersion, parsed.definitions.values(), parsed.unknown, true);
    }

    public static void installDefault(DiagnosticCodeCatalog catalog) {
        DiagnosticCodeCatalog normalized = Objects.requireNonNull(catalog, "Diagnostic catalog is required");
        if (!normalized.frozen) {
            throw new IllegalArgumentException("The default diagnostic catalog must be loaded from the frozen generation-1 source");
        }
        installed = normalized;
    }

    public static DiagnosticCodeCatalog defaultCatalog() {
        DiagnosticCodeCatalog current = installed;
        if (current != null) {
            return current;
        }
        DiagnosticCodeCatalog discovered = discoverDefault();
        installed = discovered;
        return discovered;
    }

    public static Optional<DiagnosticCodeCatalog> tryLoadDefault() {
        DiagnosticCodeCatalog catalog = defaultCatalog();
        return catalog.authoritative() ? Optional.of(catalog) : Optional.empty();
    }

    public int schemaVersion() {
        return schemaVersion;
    }

    public boolean authoritative() {
        return frozen && !definitions.isEmpty();
    }

    public int size() {
        return definitions.size();
    }

    public Set<String> codes() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(definitions.keySet()));
    }

    public Optional<Definition> find(String code) {
        return Optional.ofNullable(definitions.get(Objects.requireNonNull(code, "Diagnostic code is required")));
    }

    public Definition require(String code) {
        String normalized = Objects.requireNonNull(code, "Diagnostic code is required");
        Definition definition = definitions.get(normalized);
        if (definition == null) {
            throw new IllegalArgumentException("Diagnostic code is not present in the frozen catalog: " + normalized);
        }
        return definition;
    }

    public boolean contains(String code) {
        return code != null && definitions.containsKey(code);
    }

    public String sourceHash() {
        return sourceHash;
    }

    public String canonicalText() {
        return JsonValue.fromJava(toMap()).canonicalText();
    }

    public byte[] canonicalBytes() {
        return JsonValue.fromJava(toMap()).canonicalBytes();
    }

    public Map<String, Object> toMap() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("schemaVersion", schemaVersion);
        value.put("codes", definitions.values().stream().map(Definition::toMap).toList());
        value.putAll(unknown);
        return Collections.unmodifiableMap(value);
    }

    private static DiagnosticCodeCatalog discoverDefault() {
        String resource = catalogResource();
        try (InputStream stream = DiagnosticCodeCatalog.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException("The bundled diagnostic catalog is unavailable: " + resource);
            }
            return fromBytes(stream.readAllBytes());
        } catch (IOException exception) {
            throw new IllegalStateException("Bundled diagnostic catalog cannot be read: " + resource, exception);
        }
    }

    private static String catalogResource() {
        StringBuilder path = new StringBuilder(48);
        path.append('/');
        path.append("restudio");
        path.append('/');
        path.append("resync");
        path.append("/diagnostics/codes.json");
        return path.toString();
    }

    private static int integer(Object value, String field) {
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException(field + " must be an integer");
        }
        try {
            return new BigDecimal(number.toString()).intValueExact();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(field + " must be an integer", exception);
        }
    }

    private static Map<String, Object> stringMap(Map<?, ?> source, String field) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException(field + " keys must be strings");
            }
            result.put(key, entry.getValue());
        }
        return result;
    }

    private static Map<String, Object> unknown(Map<String, Object> source, Set<String> known) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (!known.contains(key)) {
                result.put(key, value);
            }
        });
        return result;
    }

    private static Map<String, Object> immutableMap(Map<String, ?> source, String field) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException(field + " contains an invalid key");
            }
            result.put(key, freeze(value, field + "." + key));
        });
        return Collections.unmodifiableMap(result);
    }

    private static Object freeze(Object value, String field) {
        if (value == null || value instanceof String || value instanceof Boolean || value instanceof Number) {
            return value;
        }
        if (value instanceof Map<?, ?> map) {
            return immutableMap(stringMap(map, field), field);
        }
        if (value instanceof Collection<?> collection) {
            return List.copyOf(collection.stream().map(item -> freeze(item, field + "[]")).toList());
        }
        throw new IllegalArgumentException("Unsupported " + field + " value");
    }

    private static int compareCodePoints(String left, String right) {
        int leftIndex = 0;
        int rightIndex = 0;
        while (leftIndex < left.length() && rightIndex < right.length()) {
            int leftPoint = left.codePointAt(leftIndex);
            int rightPoint = right.codePointAt(rightIndex);
            if (leftPoint != rightPoint) {
                return Integer.compare(leftPoint, rightPoint);
            }
            leftIndex += Character.charCount(leftPoint);
            rightIndex += Character.charCount(rightPoint);
        }
        return Integer.compare(left.length(), right.length());
    }

    private static void requireStrictOrder(List<Definition> definitions) {
        for (int index = 1; index < definitions.size(); index++) {
            if (compareCodePoints(definitions.get(index - 1).code(), definitions.get(index).code()) >= 0) {
                throw new IllegalArgumentException("Diagnostic catalog codes must be unique and strictly ordered");
            }
        }
    }

    private static void rejectUnknownCollisions(Map<String, ?> unknown, Set<String> known, String field) {
        for (String key : unknown.keySet()) {
            if (known.contains(key)) {
                throw new IllegalArgumentException(field + " unknown field collides with known field: " + key);
            }
        }
    }

    public record Definition(
        String code,
        DiagnosticSeverity severity,
        DiagnosticPhase phase,
        String stage,
        String messageKey,
        String message,
        boolean durable,
        String remediation,
        Retryability retryability,
        Map<String, ArgumentType> argumentTypes,
        Map<String, Object> unknown
    ) {
        public Definition {
            code = Objects.requireNonNull(code, "Diagnostic code is required");
            if (!CODE.matcher(code).matches()) {
                throw new IllegalArgumentException("Invalid diagnostic code: " + code);
            }
            severity = Objects.requireNonNull(severity, "Diagnostic severity is required");
            phase = Objects.requireNonNull(phase, "Diagnostic phase is required");
            stage = validLocal(stage, "Diagnostic stage");
            messageKey = validLocal(messageKey, "Diagnostic message key");
            message = requiredText(message, "Diagnostic message", 512);
            remediation = requiredText(remediation, "Diagnostic remediation", 2048);
            retryability = retryability == null ? Retryability.NEVER : retryability;
            argumentTypes = argumentTypes == null ? Map.of() : immutableArgumentTypes(argumentTypes);
            unknown = immutableMap(unknown, "diagnostic definition unknown");
            rejectUnknownCollisions(unknown, Set.of("code", "severity", "phase", "stage", "messageKey", "message", "durable", "remediation", "retryability", "argumentTypes"), "diagnostic definition");
        }

        public Definition(
            String code,
            DiagnosticSeverity severity,
            DiagnosticPhase phase,
            String stage,
            String messageKey,
            String message,
            boolean durable,
            String remediation
        ) {
            this(code, severity, phase, stage, messageKey, message, durable, remediation, Retryability.NEVER, Map.of(), Map.of());
        }

        public Definition(
            String code,
            DiagnosticSeverity severity,
            DiagnosticPhase phase,
            String stage,
            String messageKey,
            String message,
            boolean durable,
            String remediation,
            Map<String, ?> unknown
        ) {
            this(code, severity, phase, stage, messageKey, message, durable, remediation, Retryability.NEVER, Map.of(), unknownMap(unknown));
        }

        public Definition(
            String code,
            DiagnosticSeverity severity,
            DiagnosticPhase phase,
            String stage,
            String messageKey,
            String message,
            boolean durable,
            String remediation,
            Retryability retryability,
            Map<String, ArgumentType> argumentTypes
        ) {
            this(code, severity, phase, stage, messageKey, message, durable, remediation, retryability, argumentTypes, Map.of());
        }

        static Definition fromMap(Map<String, Object> value) {
            Set<String> known = Set.of("code", "severity", "phase", "stage", "messageKey", "message", "durable", "remediation", "retryability", "argumentTypes");
            return new Definition(
                requiredString(value.get("code"), "code"),
                enumValue(value.get("severity"), DiagnosticSeverity.values(), DiagnosticSeverity::wireName, "severity"),
                enumValue(value.get("phase"), DiagnosticPhase.values(), DiagnosticPhase::wireName, "phase"),
                requiredString(value.get("stage"), "stage"),
                requiredString(value.get("messageKey"), "messageKey"),
                requiredString(value.get("message"), "message"),
                requiredBoolean(value.get("durable"), "durable"),
                requiredString(value.get("remediation"), "remediation"),
                value.containsKey("retryability")
                    ? enumValue(value.get("retryability"), Retryability.values(), Retryability::wireName, "retryability")
                    : Retryability.NEVER,
                value.containsKey("argumentTypes") ? argumentTypes(value.get("argumentTypes")) : Map.of(),
                DiagnosticCodeCatalog.unknown(value, known)
            );
        }

        public Map<String, Object> toMap() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("code", code);
            value.put("severity", severity.wireName());
            value.put("phase", phase.wireName());
            value.put("stage", stage);
            value.put("messageKey", messageKey);
            value.put("message", message);
            value.put("durable", durable);
            value.put("remediation", remediation);
            if (retryability != Retryability.NEVER) {
                value.put("retryability", retryability.wireName());
            }
            if (!argumentTypes.isEmpty()) {
                value.put("argumentTypes", argumentTypes.entrySet().stream().collect(LinkedHashMap::new,
                    (map, entry) -> map.put(entry.getKey(), entry.getValue().wireName()), Map::putAll));
            }
            value.putAll(unknown);
            return Collections.unmodifiableMap(value);
        }

        public void validateArguments(Map<String, ?> arguments) {
            if (arguments == null || arguments.isEmpty() || argumentTypes.isEmpty()) {
                return;
            }
            arguments.forEach((key, value) -> {
                ArgumentType type = argumentTypes.get(key);
                if (type == null) {
                    throw new IllegalArgumentException("Diagnostic argument is not declared in the catalog for " + code + ": " + key);
                }
                if (!type.accepts(value)) {
                    throw new IllegalArgumentException("Diagnostic argument does not match the catalog type for " + code + ": " + key);
                }
            });
        }

        private static Map<String, ArgumentType> argumentTypes(Object value) {
            if (!(value instanceof Map<?, ?> map)) {
                throw new IllegalArgumentException("argumentTypes must be an object");
            }
            LinkedHashMap<String, ArgumentType> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key) || key.isBlank()) {
                    throw new IllegalArgumentException("argumentTypes keys must be nonblank text");
                }
                result.put(key, enumValue(entry.getValue(), ArgumentType.values(), ArgumentType::wireName, "argumentTypes." + key));
            }
            return result;
        }

        private static Map<String, ArgumentType> immutableArgumentTypes(Map<String, ArgumentType> source) {
            LinkedHashMap<String, ArgumentType> result = new LinkedHashMap<>();
            source.forEach((key, value) -> {
                if (key == null || key.isBlank()) {
                    throw new IllegalArgumentException("Diagnostic argument type key must be nonblank");
                }
                result.put(key, Objects.requireNonNull(value, "Diagnostic argument type is required"));
            });
            return Collections.unmodifiableMap(result);
        }

        private static Map<String, Object> unknownMap(Map<String, ?> source) {
            if (source == null || source.isEmpty()) {
                return Map.of();
            }
            LinkedHashMap<String, Object> result = new LinkedHashMap<>();
            source.forEach(result::put);
            return result;
        }

        private static String requiredString(Object value, String field) {
            if (!(value instanceof String text)) {
                throw new IllegalArgumentException(field + " must be text");
            }
            return text;
        }

        private static boolean requiredBoolean(Object value, String field) {
            if (!(value instanceof Boolean result)) {
                throw new IllegalArgumentException(field + " must be boolean");
            }
            return result;
        }

        private static String validLocal(String value, String field) {
            String normalized = requiredText(value, field, 128);
            if (!LOCAL_ID.matcher(normalized).matches()) {
                throw new IllegalArgumentException(field + " is not a valid local ID: " + normalized);
            }
            return normalized;
        }

        private static String requiredText(String value, String field, int max) {
            String normalized = Objects.requireNonNull(value, field).trim();
            if (normalized.isBlank() || normalized.length() > max) {
                throw new IllegalArgumentException(field + " is empty or exceeds " + max + " characters");
            }
            return normalized;
        }

        private static <E> E enumValue(Object raw, E[] values, Function<E, String> wire, String field) {
            String expected = requiredString(raw, field);
            for (E value : values) {
                if (wire.apply(value).equals(expected)) {
                    return value;
                }
            }
            throw new IllegalArgumentException("Unknown " + field + ": " + expected);
        }
    }

    public enum Retryability {
        NEVER("never"),
        SAFE("safe"),
        AFTER_CORRECTION("after-correction"),
        AFTER_RECONCILIATION("after-reconciliation");

        private final String wireName;

        Retryability(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }
    }

    public enum ArgumentType {
        TEXT("text"),
        INTEGER("integer"),
        DECIMAL("decimal"),
        BOOLEAN("boolean"),
        IDENTITY("identity"),
        LIST("list"),
        MAP("map"),
        ANY("any");

        private final String wireName;

        ArgumentType(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }

        private boolean accepts(Object value) {
            return switch (this) {
                case TEXT, IDENTITY -> value instanceof String || (this == IDENTITY && value instanceof Map<?, ?>);
                case INTEGER -> value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long
                    || value instanceof BigInteger
                    || value instanceof BigDecimal decimal && decimal.stripTrailingZeros().scale() <= 0;
                case DECIMAL -> value instanceof Number;
                case BOOLEAN -> value instanceof Boolean;
                case LIST -> value instanceof Collection<?>;
                case MAP -> value instanceof Map<?, ?>;
                case ANY -> true;
            };
        }
    }
}
