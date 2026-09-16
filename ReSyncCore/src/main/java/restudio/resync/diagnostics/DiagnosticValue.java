package restudio.resync.diagnostics;

import restudio.resync.contract.identity.Revision;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.LocalId;
import restudio.resync.flow.identity.ResourceKey;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public sealed interface DiagnosticValue permits DiagnosticValue.Text, DiagnosticValue.IntegerValue,
    DiagnosticValue.DecimalValue, DiagnosticValue.BooleanValue, DiagnosticValue.ListValue, DiagnosticValue.MapValue {

    Kind kind();

    default Object toJava() {
        if (this instanceof Text value) {
            return value.value();
        }
        if (this instanceof IntegerValue value) {
            return value.value();
        }
        if (this instanceof DecimalValue value) {
            return value.value();
        }
        if (this instanceof BooleanValue value) {
            return value.value();
        }
        if (this instanceof ListValue value) {
            List<Object> result = new ArrayList<>(value.values().size());
            for (DiagnosticValue item : value.values()) {
                result.add(item.toJava());
            }
            return Collections.unmodifiableList(result);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, DiagnosticValue> entry : ((MapValue) this).values().entrySet()) {
            result.put(entry.getKey(), entry.getValue().toJava());
        }
        return Collections.unmodifiableMap(result);
    }

    static DiagnosticValue text(String value) {
        return new Text(value);
    }

    static DiagnosticValue integer(long value) {
        return new IntegerValue(value);
    }

    static DiagnosticValue decimal(BigDecimal value) {
        return new DecimalValue(value);
    }

    static DiagnosticValue decimal(double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("Diagnostic decimal must be finite");
        }
        return decimal(BigDecimal.valueOf(value));
    }

    static DiagnosticValue booleanValue(boolean value) {
        return new BooleanValue(value);
    }

    static DiagnosticValue list(List<?> values) {
        Objects.requireNonNull(values, "Diagnostic value list is required");
        return copy(values, new IdentityHashMap<>());
    }

    static DiagnosticValue map(Map<String, ?> values) {
        Objects.requireNonNull(values, "Diagnostic value map is required");
        return copy(values, new IdentityHashMap<>());
    }

    static Map<String, DiagnosticValue> fields(Map<String, ?> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        return ((MapValue) map(values)).values();
    }

    static DiagnosticValue of(Object value) {
        return copy(value, new IdentityHashMap<>());
    }

    private static DiagnosticValue copy(Object value, IdentityHashMap<Object, Boolean> active) {
        if (value instanceof DiagnosticValue diagnosticValue) {
            return diagnosticValue;
        }
        if (value instanceof String text) {
            return text(text);
        }
        if (value instanceof Character character) {
            return text(character.toString());
        }
        if (value instanceof Boolean booleanValue) {
            return booleanValue(booleanValue);
        }
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            return integer(((Number) value).longValue());
        }
        if (value instanceof BigInteger integer) {
            if (integer.compareTo(BigInteger.valueOf(Long.MIN_VALUE)) < 0
                || integer.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0) {
                throw new IllegalArgumentException("Diagnostic integer is outside the supported range");
            }
            return integer(integer.longValue());
        }
        if (value instanceof BigDecimal decimal) {
            return decimal(decimal);
        }
        if (value instanceof Float decimal) {
            if (!Float.isFinite(decimal)) {
                throw new IllegalArgumentException("Diagnostic decimal must be finite");
            }
            return decimal(BigDecimal.valueOf(decimal.doubleValue()));
        }
        if (value instanceof Double decimal) {
            return decimal(decimal);
        }
        if (value instanceof UUID uuid) {
            return text(uuid.toString());
        }
        if (value instanceof Enum<?> enumeration) {
            return text(enumeration.name());
        }
        if (value instanceof ServerId serverId) {
            return text(serverId.canonicalText());
        }
        if (value instanceof ServerResourceLocator locator) {
            return text(locator.canonicalText());
        }
        if (value instanceof ResourceKey key) {
            return text(key.canonicalText());
        }
        if (value instanceof ContractRef<?> reference) {
            return text(reference.canonicalText());
        }
        if (value instanceof LocalId localId) {
            return text(localId.canonicalText());
        }
        if (value instanceof Revision revision) {
            return integer(revision.value());
        }
        if (value instanceof Map<?, ?> map) {
            enter(map, active);
            try {
                LinkedHashMap<String, DiagnosticValue> copy = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (!(entry.getKey() instanceof String key)) {
                        throw new IllegalArgumentException("Diagnostic value map keys must be strings");
                    }
                    copy.put(field(key), copy(entry.getValue(), active));
                }
                return new MapValue(copy);
            } finally {
                active.remove(map);
            }
        }
        if (value instanceof List<?> list) {
            enter(list, active);
            try {
                List<DiagnosticValue> copy = new ArrayList<>(list.size());
                for (Object item : list) {
                    copy.add(copy(item, active));
                }
                return new ListValue(copy);
            } finally {
                active.remove(list);
            }
        }
        throw new IllegalArgumentException("Unsupported diagnostic value");
    }

    private static void enter(Object value, IdentityHashMap<Object, Boolean> active) {
        if (active.put(value, Boolean.TRUE) != null) {
            throw new IllegalArgumentException("Diagnostic values cannot contain cycles");
        }
    }

    private static String field(String value) {
        Objects.requireNonNull(value, "Diagnostic field name is required");
        if (value.isEmpty() || value.length() > 128 || !Character.isLetter(value.charAt(0))) {
            throw new IllegalArgumentException("Invalid diagnostic field name: " + value);
        }
        for (int index = 1; index < value.length(); index++) {
            char character = value.charAt(index);
            if (!(Character.isLetterOrDigit(character) || character == '_' || character == '-' || character == '.')) {
                throw new IllegalArgumentException("Invalid diagnostic field name: " + value);
            }
        }
        return value;
    }

    enum Kind {
        TEXT,
        INTEGER,
        DECIMAL,
        BOOLEAN,
        LIST,
        MAP
    }

    record Text(String value) implements DiagnosticValue {
        public Text {
            Objects.requireNonNull(value, "Diagnostic text is required");
        }

        @Override
        public Kind kind() {
            return Kind.TEXT;
        }
    }

    record IntegerValue(long value) implements DiagnosticValue {
        @Override
        public Kind kind() {
            return Kind.INTEGER;
        }
    }

    record DecimalValue(BigDecimal value) implements DiagnosticValue {
        public DecimalValue {
            Objects.requireNonNull(value, "Diagnostic decimal is required");
        }

        @Override
        public Kind kind() {
            return Kind.DECIMAL;
        }
    }

    record BooleanValue(boolean value) implements DiagnosticValue {
        @Override
        public Kind kind() {
            return Kind.BOOLEAN;
        }
    }

    record ListValue(List<DiagnosticValue> values) implements DiagnosticValue {
        public ListValue {
            values = List.copyOf(Objects.requireNonNull(values, "Diagnostic value list is required"));
        }

        @Override
        public Kind kind() {
            return Kind.LIST;
        }
    }

    record MapValue(Map<String, DiagnosticValue> values) implements DiagnosticValue {
        public MapValue {
            Objects.requireNonNull(values, "Diagnostic value map is required");
            LinkedHashMap<String, DiagnosticValue> copy = new LinkedHashMap<>();
            values.forEach((key, value) -> copy.put(field(key), Objects.requireNonNull(value, "Diagnostic map value is required")));
            values = Collections.unmodifiableMap(copy);
        }

        @Override
        public Kind kind() {
            return Kind.MAP;
        }
    }
}
