package restudio.resync.flow.resource;

import restudio.resync.contract.canonical.CanonicalArrays;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class ResourcePayloadCodecs {
    private ResourcePayloadCodecs() {
    }

    public static ResourcePayloadCodec<Map<String, Object>> json() {
        return new JsonPayloadCodec();
    }

    private static final class JsonPayloadCodec implements ResourcePayloadCodec<Map<String, Object>> {
        @Override
        public Map<String, Object> normalize(Map<String, Object> payload) {
            return freezeMap(Objects.requireNonNull(payload, "Payload is required"));
        }

        private static Object freeze(Object value) {
            if (value == null || value instanceof String || value instanceof Boolean || value instanceof Character
                || value instanceof Number || value instanceof java.util.UUID) {
                return value;
            }
            if (value instanceof Map<?, ?> map) {
                return freezeMap(map);
            }
            if (value instanceof Set<?> set) {
                LinkedHashSet<Object> copy = new LinkedHashSet<>();
                set.forEach(entry -> copy.add(freeze(entry)));
                return Collections.unmodifiableSet(copy);
            }
            if (value instanceof Collection<?> collection) {
                ArrayList<Object> copy = new ArrayList<>(collection.size());
                collection.forEach(entry -> copy.add(freeze(entry)));
                return Collections.unmodifiableList(copy);
            }
            Object[] array = CanonicalArrays.boxed(value);
            if (array != null) {
                ArrayList<Object> copy = new ArrayList<>(array.length);
                for (Object entry : array) {
                    copy.add(freeze(entry));
                }
                return Collections.unmodifiableList(copy);
            }
            if (value instanceof Enum<?> enumValue) {
                return enumValue.name();
            }
            throw new IllegalArgumentException("Unsupported JSON payload value");
        }

        private static Map<String, Object> freezeMap(Map<?, ?> map) {
            LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new IllegalArgumentException("Payload object keys must be strings");
                }
                copy.put(key, freeze(entry.getValue()));
            }
            return Collections.unmodifiableMap(copy);
        }
    }
}
