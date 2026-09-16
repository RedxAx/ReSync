package restudio.resync.flow.graph;

import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Collections;
import java.util.Set;

public final class OpaqueData {
    private static final OpaqueData EMPTY = new OpaqueData(Map.of());

    private final Map<String, Object> fields;

    private OpaqueData(Map<String, ?> fields) {
        this.fields = ImmutableData.map(fields);
    }

    public static OpaqueData empty() {
        return EMPTY;
    }

    public static OpaqueData of(Map<String, ?> fields) {
        if (fields == null || fields.isEmpty()) {
            return EMPTY;
        }
        return new OpaqueData(fields);
    }

    public Map<String, Object> fields() {
        return fields;
    }

    public boolean isEmpty() {
        return fields.isEmpty();
    }

    public boolean contains(String key) {
        return fields.containsKey(key);
    }

    public Object get(String key) {
        return fields.get(key);
    }

    public OpaqueData with(String key, Object value) {
        Objects.requireNonNull(key, "key");
        LinkedHashMap<String, Object> copy = new LinkedHashMap<>(fields);
        copy.put(key, value);
        return OpaqueData.of(copy);
    }

    void rejectKnownFields(String... knownFields) {
        rejectKnownFields(Set.of(knownFields));
    }

    void rejectKnownFields(Set<String> knownFields) {
        Objects.requireNonNull(knownFields, "knownFields");
        for (String key : knownFields) {
            if (key == null || key.isEmpty()) {
                throw new IllegalArgumentException("Known data field names are required");
            }
        }
        for (String key : fields.keySet()) {
            if (knownFields.contains(key)) {
                throw new IllegalArgumentException("Unknown data collides with known field: " + key);
            }
        }
    }

    static Map<String, Object> mergeKnownFields(OpaqueData unknown, Map<String, Object> known) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        if (unknown != null) {
            result.putAll(unknown.fields);
        }
        known.forEach((key, value) -> {
            if (result.containsKey(key)) {
                throw new IllegalArgumentException("Unknown data collides with known field: " + key);
            }
            result.put(key, value);
        });
        return Collections.unmodifiableMap(result);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof OpaqueData data && fields.equals(data.fields);
    }

    @Override
    public int hashCode() {
        return fields.hashCode();
    }

    @Override
    public String toString() {
        return fields.toString();
    }
}
