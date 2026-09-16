package restudio.resync.flow.graph;

import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.identity.PinId;
import restudio.resync.flow.type.TypedValue;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record PinValue(PinId pinId, TypedValue value, OpaqueData unknown) {
    public PinValue {
        pinId = Objects.requireNonNull(pinId, "pinId");
        value = Objects.requireNonNull(value, "value");
        unknown = unknown != null ? unknown : OpaqueData.empty();
        unknown.rejectKnownFields("value");
    }

    public PinValue(PinId pinId, TypedValue value) {
        this(pinId, value, OpaqueData.empty());
    }

    static Map<PinId, PinValue> immutableValues(Map<PinId, PinValue> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        return Map.copyOf(values);
    }

    static Map<String, Object> canonicalValues(Map<PinId, PinValue> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        values.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> result.put(entry.getKey().canonicalText(), entry.getValue().canonicalValue()));
        return result;
    }

    Map<String, Object> canonicalValue() {
        return OpaqueData.mergeKnownFields(unknown, Map.of("value", CanonicalJson.parse(value.canonicalJson())));
    }
}
