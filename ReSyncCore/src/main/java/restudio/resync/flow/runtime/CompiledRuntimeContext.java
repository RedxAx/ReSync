package restudio.resync.flow.runtime;

import restudio.resync.flow.identity.CanonicalText;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypedValue;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record CompiledRuntimeContext(PlayerIdentity player, EventIdentity event, Map<String, TypedValue> variables,
                                     RuntimePrincipal principal) {
    public CompiledRuntimeContext(PlayerIdentity player, EventIdentity event, Map<String, TypedValue> variables) {
        this(player, event, variables, null);
    }

    public CompiledRuntimeContext {
        variables = immutableTypedValues(variables, "Compiled Runtime Context Variables");
    }

    public static CompiledRuntimeContext empty() {
        return new CompiledRuntimeContext(null, null, Map.of());
    }

    public boolean isEmpty() {
        return player == null && event == null && variables.isEmpty() && principal == null;
    }

    public boolean supportedByCompiledCore() {
        return supportedMaterial(player, event, variables);
    }

    public String unsupportedReason() {
        return supportedByCompiledCore()
            ? ""
            : "The compiled Core runner cannot preserve one or more runtime context values";
    }

    public Map<String, Object> canonicalValue() {
        Map<String, Object> value = new LinkedHashMap<>();
        if (player != null) {
            value.put("player", player.canonicalValue());
        }
        if (event != null) {
            value.put("event", event.canonicalValue());
        }
        if (!variables.isEmpty()) {
            value.put("variables", variables.entrySet().stream()
                .collect(LinkedHashMap::new, (result, entry) -> result.put(entry.getKey(), entry.getValue().canonicalValue()), Map::putAll));
        }
        if (principal != null) {
            value.put("principal", Map.of(
                "kind", principal.kind().name().toLowerCase(Locale.ROOT),
                "identity", principal.identity()));
        }
        return Collections.unmodifiableMap(value);
    }

    public Map<String, Object> supportSummary() {
        return Map.of(
            "contract", "compiled-runtime-context-v1",
            "supported", supportedByCompiledCore(),
            "playerPresent", player != null,
            "eventPresent", event != null,
            "variablesPresent", !variables.isEmpty(),
            "principalPresent", principal != null
        );
    }

    public record PlayerIdentity(UUID uniqueId, String name) {
        public PlayerIdentity {
            uniqueId = Objects.requireNonNull(uniqueId, "Compiled Player Identity UUID Is Required");
            name = optionalText(name, "Compiled Player Identity Name", 64);
        }

        public Map<String, Object> canonicalValue() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("uniqueId", uniqueId.toString());
            if (name != null) {
                value.put("name", name);
            }
            return Collections.unmodifiableMap(value);
        }
    }

    public record EventIdentity(String type, Map<String, TypedValue> fields) {
        public EventIdentity {
            type = requiredText(type, "Compiled Event Identity Type", 256);
            fields = immutableTypedValues(fields, "Compiled Event Identity Fields");
        }

        public Map<String, Object> canonicalValue() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("type", type);
            if (!fields.isEmpty()) {
                value.put("fields", fields.entrySet().stream()
                    .collect(LinkedHashMap::new, (result, entry) -> result.put(entry.getKey(), entry.getValue().canonicalValue()), Map::putAll));
            }
            return Collections.unmodifiableMap(value);
        }
    }

    private static Map<String, TypedValue> immutableTypedValues(Map<String, TypedValue> values, String label) {
        Objects.requireNonNull(values, label + " Are Required");
        List<Map.Entry<String, TypedValue>> entries = new ArrayList<>(values.size());
        values.forEach((key, value) -> {
            key = requiredText(key, label + " Keys", 256);
            value = Objects.requireNonNull(value, label + " Values Cannot Be Null");
            if (value.state() == TypedValue.State.OPAQUE || containsOpaque(value.type()) || !supportedMaterial(value.value())) {
                throw new IllegalArgumentException(label + " Cannot Carry Opaque Typed Values");
            }
            entries.add(Map.entry(key, value));
        });
        entries.sort(Map.Entry.comparingByKey(CanonicalText.ORDER));
        Map<String, TypedValue> result = new LinkedHashMap<>();
        entries.forEach(entry -> result.put(entry.getKey(), entry.getValue()));
        return Collections.unmodifiableMap(result);
    }

    private static boolean supportedMaterial(PlayerIdentity player, EventIdentity event, Map<String, TypedValue> variables) {
        if (player != null && (player.uniqueId() == null || !supportedText(player.name()))) {
            return false;
        }
        if (event != null && (!supportedText(event.type()) || event.fields().entrySet().stream()
            .anyMatch(entry -> !supportedText(entry.getKey()) || !supportedTypedValue(entry.getValue())))) {
            return false;
        }
        return variables != null && variables.entrySet().stream()
            .allMatch(entry -> supportedText(entry.getKey()) && supportedTypedValue(entry.getValue()));
    }

    private static boolean supportedTypedValue(TypedValue value) {
        if (value == null || value.state() == TypedValue.State.OPAQUE || containsOpaque(value.type())) {
            return false;
        }
        return value.state() != TypedValue.State.VALUE || supportedMaterial(value.value());
    }

    private static boolean supportedMaterial(Object value) {
        if (value == null || value instanceof String || value instanceof Boolean || value instanceof Character
            || value instanceof java.math.BigInteger || value instanceof java.math.BigDecimal || value instanceof Byte
            || value instanceof Short || value instanceof Integer || value instanceof Long || value instanceof UUID
            || value instanceof restudio.resync.flow.identity.ServerResourceLocator) {
            return true;
        }
        if (value instanceof TypedValue typedValue) {
            return supportedTypedValue(typedValue);
        }
        if (value instanceof Map<?, ?> map) {
            return map.entrySet().stream().allMatch(entry -> entry.getKey() instanceof String key
                && supportedText(key) && supportedMaterial(entry.getValue()));
        }
        if (value instanceof Iterable<?> iterable) {
            for (Object entry : iterable) {
                if (!supportedMaterial(entry)) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    private static boolean supportedText(String value) {
        return value == null || !value.isBlank() && value.equals(value.strip())
            && value.indexOf('\u0000') < 0 && Normalizer.isNormalized(value, Normalizer.Form.NFC);
    }

    private static boolean containsOpaque(TypeExpr expression) {
        return switch (expression) {
            case TypeExpr.OpaqueType ignored -> true;
            case TypeExpr.Named named -> named.arguments().stream().anyMatch(CompiledRuntimeContext::containsOpaque);
            case TypeExpr.OptionalType optional -> containsOpaque(optional.element());
            case TypeExpr.ListType list -> containsOpaque(list.element());
            case TypeExpr.MapType map -> containsOpaque(map.key()) || containsOpaque(map.value());
            case TypeExpr.TupleType tuple -> tuple.elements().stream().anyMatch(CompiledRuntimeContext::containsOpaque);
            case TypeExpr.ResultType result -> containsOpaque(result.success()) || containsOpaque(result.failure());
            case TypeExpr.ResourceType ignored -> false;
            case TypeExpr.UnionType union -> union.variants().stream().map(TypeExpr.UnionVariant::type)
                .anyMatch(CompiledRuntimeContext::containsOpaque);
        };
    }

    private static String requiredText(String value, String label, int maximum) {
        Objects.requireNonNull(value, label + " Is Required");
        if (value.isBlank() || !value.equals(value.strip()) || value.length() > maximum
            || value.indexOf('\u0000') >= 0 || !Normalizer.isNormalized(value, Normalizer.Form.NFC)) {
            throw new IllegalArgumentException(label + " Must Be Canonical Non-Blank Text");
        }
        return value;
    }

    private static String optionalText(String value, String label, int maximum) {
        if (value == null) {
            return null;
        }
        if (value.isBlank() || !value.equals(value.strip()) || value.length() > maximum
            || value.indexOf('\u0000') >= 0 || !Normalizer.isNormalized(value, Normalizer.Form.NFC)) {
            throw new IllegalArgumentException(label + " Must Be Canonical Text");
        }
        return value;
    }
}
