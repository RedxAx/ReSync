package restudio.resync.flow;

import java.util.LinkedHashMap;
import java.util.Map;

public final class ItemStackPropertySelector {
    public static final String NODE_ID = "itemstack.properties";
    public static final String PROPERTY_PIN = "property";

    private ItemStackPropertySelector() {
    }

    public static boolean applies(String nodeType) {
        if (NODE_ID.equals(nodeType)) {
            return true;
        }
        if (nodeType == null || nodeType.isBlank()) {
            return false;
        }
        int separator = Math.max(nodeType.lastIndexOf('/'), nodeType.lastIndexOf(':'));
        return separator >= 0 && NODE_ID.equals(nodeType.substring(separator + 1));
    }

    public static Map<String, Object> effectiveInputs(String nodeType, Map<String, Object> inputValues,
                                                      Map<String, Object> handlerConfig) {
        Map<String, Object> inputs = inputValues == null ? Map.of() : inputValues;
        if (!applies(nodeType) || text(inputs.get(PROPERTY_PIN)) != null) {
            return inputs;
        }
        String property = text(handlerConfig == null ? null : handlerConfig.get(PROPERTY_PIN));
        if (property == null) {
            return inputs;
        }
        Map<String, Object> effective = new LinkedHashMap<>(inputs);
        effective.put(PROPERTY_PIN, property);
        return effective;
    }

    public static Map<String, Object> canonicalHandlerConfig(String nodeType, Map<String, Object> handlerConfig) {
        Map<String, Object> config = handlerConfig == null ? Map.of() : handlerConfig;
        if (!applies(nodeType) || !config.containsKey(PROPERTY_PIN)) {
            return config;
        }
        Map<String, Object> canonical = new LinkedHashMap<>(config);
        canonical.remove(PROPERTY_PIN);
        return canonical;
    }

    public static Map<String, Object> runtimeHandlerConfig(String nodeType, Map<String, Object> activeConfig,
                                                           Map<String, Object> authoredInputs,
                                                           Map<String, Object> authoredConfig) {
        Map<String, Object> active = activeConfig == null ? Map.of() : activeConfig;
        if (!applies(nodeType) || text(authoredInputs == null ? null : authoredInputs.get(PROPERTY_PIN)) != null) {
            return active;
        }
        String property = text(authoredConfig == null ? null : authoredConfig.get(PROPERTY_PIN));
        if (property == null) {
            return active;
        }
        Map<String, Object> runtime = new LinkedHashMap<>(active);
        runtime.put(PROPERTY_PIN, property);
        return runtime;
    }

    public static String text(Object value) {
        if (!(value instanceof String text)) {
            return null;
        }
        String normalized = text.strip();
        return normalized.isEmpty() ? null : normalized;
    }
}
