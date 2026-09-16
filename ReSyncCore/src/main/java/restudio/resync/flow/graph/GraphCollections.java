package restudio.resync.flow.graph;

import restudio.resync.flow.identity.PinId;
import restudio.resync.flow.identity.InspectorFieldId;
import restudio.resync.flow.type.TypedValue;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

final class GraphCollections {
    private GraphCollections() {
    }

    static Map<PinId, PinValue> pinValues(Map<PinId, PinValue> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<PinId, PinValue> copy = new LinkedHashMap<>();
        source.forEach((key, pinValue) -> {
            PinId normalized = Objects.requireNonNull(key, "pin ID");
            PinValue value = Objects.requireNonNull(pinValue, "pinValue");
            if (!normalized.equals(value.pinId())) {
                throw new IllegalArgumentException("Pin value key does not match its ID: " + key);
            }
            if (copy.put(normalized, value) != null) {
                throw new IllegalArgumentException("Duplicate pin value: " + normalized);
            }
        });
        return Collections.unmodifiableMap(copy);
    }

    static InspectorValues inspectorValues(Map<?, ?> source) {
        if (source == null || source.isEmpty()) {
            return new InspectorValues(Map.of(), Map.of());
        }
        LinkedHashMap<PinId, PinValue> pinValues = new LinkedHashMap<>();
        LinkedHashMap<InspectorFieldId, TypedValue> fields = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            Objects.requireNonNull(key, "inspector field ID");
            Objects.requireNonNull(value, "inspector value");
            if (key instanceof InspectorFieldId fieldId) {
                TypedValue typed = value instanceof TypedValue typedValue
                    ? typedValue
                    : value instanceof PinValue pinValue ? pinValue.value() : null;
                if (typed == null) {
                    throw new IllegalArgumentException("Inspector field values must be typed values");
                }
                if (fields.put(fieldId, typed) != null) {
                    throw new IllegalArgumentException("Duplicate inspector field: " + fieldId);
                }
                return;
            }
            if (key instanceof PinId pinId && value instanceof PinValue pinValue) {
                if (!pinId.equals(pinValue.pinId())) {
                    throw new IllegalArgumentException("Inspector pin value key does not match its ID: " + pinId);
                }
                if (pinValues.put(pinId, pinValue) != null) {
                    throw new IllegalArgumentException("Duplicate inspector pin value: " + pinId);
                }
                return;
            }
            throw new IllegalArgumentException("Inspector values must use InspectorFieldId and TypedValue");
        });
        return new InspectorValues(Collections.unmodifiableMap(pinValues), Collections.unmodifiableMap(fields));
    }

    record InspectorValues(Map<PinId, PinValue> pinValues, Map<InspectorFieldId, TypedValue> fields) {
    }
}
