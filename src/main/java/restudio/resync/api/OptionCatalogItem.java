package restudio.resync.api;

import com.google.gson.JsonPrimitive;
import com.google.gson.internal.LazilyParsedNumber;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record OptionCatalogItem(String value, String label, String description, String icon, String group, Map<String, Object> metadata) {
    private static final int MAX_METADATA_DEPTH = 16;
    private static final int MAX_METADATA_VALUES = 4096;
    private static final int MAX_NUMBER_CHARACTERS = 4096;
    private static final int MAX_NUMBER_PRECISION = 1024;
    private static final int MAX_NUMBER_SCALE = 4096;

    public OptionCatalogItem(String value) {
        this(value, value, "", "", "", Map.of());
    }

    public OptionCatalogItem(String value, String label) {
        this(value, label, "", "", "", Map.of());
    }

    public OptionCatalogItem {
        value = value != null ? value : "";
        label = label != null && !label.isBlank() ? label : value;
        description = description != null ? description : "";
        icon = icon != null ? icon : "";
        group = group != null ? group : "";
        metadata = metadata != null ? new MetadataFreeze().map(metadata, 0) : Map.of();
    }

    private static final class MetadataFreeze {
        private final IdentityHashMap<Object, Boolean> path = new IdentityHashMap<>();
        private int values;

        private Map<String, Object> map(Map<?, ?> source, int depth) {
            enter(source, depth);
            try {
                Map<String, Object> copy = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : source.entrySet()) {
                    if (!(entry.getKey() instanceof String key)) {
                        throw new IllegalArgumentException("Option catalog metadata keys must be strings");
                    }
                    count();
                    copy.put(key, freeze(entry.getValue(), depth + 1));
                }
                return Collections.unmodifiableMap(copy);
            } finally {
                path.remove(source);
            }
        }

        private Object freeze(Object source, int depth) {
            if (source == null || source instanceof String || source instanceof Boolean || source instanceof UUID) {
                return source;
            }
            if (source instanceof JsonPrimitive primitive && primitive.isNumber()) {
                return number(primitive.getAsString());
            }
            if (source instanceof LazilyParsedNumber number) {
                return number(number.toString());
            }
            if (source instanceof BigInteger number) {
                requireNumberBounds(number.toString(), new BigDecimal(number));
                return number;
            }
            if (source instanceof BigDecimal number) {
                requireNumberBounds(number.toString(), number);
                return number;
            }
            if (source instanceof Byte || source instanceof Short || source instanceof Integer || source instanceof Long) {
                return source;
            }
            if (source instanceof Float number) {
                if (!Float.isFinite(number)) {
                    throw new IllegalArgumentException("Option catalog metadata numbers must be finite");
                }
                return number(number.toString());
            }
            if (source instanceof Double number) {
                if (!Double.isFinite(number)) {
                    throw new IllegalArgumentException("Option catalog metadata numbers must be finite");
                }
                return number(number.toString());
            }
            if (source instanceof Map<?, ?> map) {
                return map(map, depth);
            }
            if (source instanceof List<?> list) {
                enter(source, depth);
                try {
                    List<Object> copy = new ArrayList<>(list.size());
                    for (Object entry : list) {
                        count();
                        copy.add(freeze(entry, depth + 1));
                    }
                    return Collections.unmodifiableList(copy);
                } finally {
                    path.remove(source);
                }
            }
            if (source.getClass().isArray()) {
                enter(source, depth);
                try {
                    int length = Array.getLength(source);
                    List<Object> copy = new ArrayList<>(length);
                    for (int index = 0; index < length; index++) {
                        count();
                        copy.add(freeze(Array.get(source, index), depth + 1));
                    }
                    return Collections.unmodifiableList(copy);
                } finally {
                    path.remove(source);
                }
            }
            throw new IllegalArgumentException("Unsupported option catalog metadata value: " + source.getClass().getName());
        }

        private Object number(String value) {
            if (value == null || value.length() > MAX_NUMBER_CHARACTERS
                || !value.matches("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")) {
                throw new IllegalArgumentException("Option catalog metadata contains an invalid number");
            }
            try {
                BigDecimal decimal = new BigDecimal(value);
                requireNumberBounds(value, decimal);
                return value.indexOf('.') < 0 && value.indexOf('e') < 0 && value.indexOf('E') < 0
                    ? decimal.toBigIntegerExact() : decimal;
            } catch (ArithmeticException | NumberFormatException exception) {
                throw new IllegalArgumentException("Option catalog metadata contains an invalid number", exception);
            }
        }

        private void requireNumberBounds(String value, BigDecimal number) {
            long scale = number.scale();
            if (value.length() > MAX_NUMBER_CHARACTERS || number.precision() > MAX_NUMBER_PRECISION
                || scale < -MAX_NUMBER_SCALE || scale > MAX_NUMBER_SCALE) {
                throw new IllegalArgumentException("Option catalog metadata number exceeds the supported bounds");
            }
        }

        private void enter(Object source, int depth) {
            if (depth > MAX_METADATA_DEPTH) {
                throw new IllegalArgumentException("Option catalog metadata exceeds the maximum depth");
            }
            if (path.put(source, Boolean.TRUE) != null) {
                throw new IllegalArgumentException("Option catalog metadata contains a cycle");
            }
        }

        private void count() {
            if (++values > MAX_METADATA_VALUES) {
                throw new IllegalArgumentException("Option catalog metadata contains too many values");
            }
        }
    }
}
