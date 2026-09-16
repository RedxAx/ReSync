package restudio.resync.flow.migration;

import restudio.resync.contract.canonical.CanonicalCodec;
import restudio.resync.contract.canonical.JsonValue;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public final class FlowNodeMigrationMap {
    public static final String RESOURCE = "/restudio/resync/flow/migration/flow-node-id-map-v1.json";
    public static final int VERSION = 1;

    private FlowNodeMigrationMap() {
    }

    public static Map<String, String> load() {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        ClassLoader fallback = FlowNodeMigrationMap.class.getClassLoader();
        InputStream stream = loader != null ? loader.getResourceAsStream(RESOURCE.substring(1)) : null;
        if (stream == null && fallback != null) {
            stream = fallback.getResourceAsStream(RESOURCE.substring(1));
        }
        if (stream == null) {
            throw new IllegalStateException("Flow node migration map is missing: " + RESOURCE);
        }
        try (InputStream input = stream) {
            JsonValue value = CanonicalCodec.decodePermissive(input.readAllBytes());
            if (!(value instanceof JsonValue.JsonObject object)) {
                throw new IllegalStateException("Flow node migration map must be an object");
            }
            Map<String, String> result = new HashMap<>();
            for (Map.Entry<String, JsonValue> entry : object.fields().entrySet()) {
                if (!(entry.getValue() instanceof JsonValue.JsonString target)) {
                    throw new IllegalStateException("Flow node migration map target must be text: " + entry.getKey());
                }
                if (!target.value().contains("DRAFT")) {
                    String previous = result.putIfAbsent(entry.getKey(), target.value());
                    if (previous != null && !Objects.equals(previous, target.value())) {
                        throw new IllegalStateException("Flow node migration map contains a duplicate source: " + entry.getKey());
                    }
                }
            }
            return Map.copyOf(result);
        } catch (IOException | RuntimeException exception) {
            if (exception instanceof IllegalStateException state) {
                throw state;
            }
            throw new IllegalStateException("Failed to load flow node migration map", exception);
        }
    }
}
