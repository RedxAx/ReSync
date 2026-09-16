package restudio.resync.flow.handler.generic;

import restudio.flow.data.FlowNode;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.canonical.CanonicalLimits;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.NodeHandler;
import restudio.resync.modules.flow.FlowPacketSender;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

public class JsonHandler implements NodeHandler {
    private static final CanonicalLimits CANONICAL_LIMITS = CanonicalLimits.standard();
    private final Map<String, BiConsumer<FlowContext, FlowNode>> operations = new ConcurrentHashMap<>();

    public JsonHandler() {
        operations.put("json_parse", (ctx, node) -> {
            String jsonString = ctx.getInputValue(node, "json_string", String.class, "{}");
            requireTextLength(jsonString, "JSON input");
            try {
                Object result = CanonicalJson.parse(jsonString, CANONICAL_LIMITS);
                requireCanonicalTextLength(result, "JSON output");
                ctx.setOutput(node, "object", result);
            } catch (RuntimeException exception) {
                throw new IllegalArgumentException("Invalid JSON input", exception);
            }
        });

        operations.put("json_to_string", (ctx, node) -> {
            Object object = ctx.getInputValue(node, "object", Object.class, null);
            String jsonString = canonicalize(object, "JSON output");
            ctx.setOutput(node, "string", jsonString);
        });

        operations.put("json_get", (ctx, node) -> {
            Object object = ctx.getInputValue(node, "object", Object.class, null);
            String path = ctx.getInputValue(node, "path", String.class, "");
            String[] keys = validatePath(path);
            Object canonicalObject = canonicalCopy(object, "JSON object");
            if (!(canonicalObject instanceof Map<?, ?> map)) {
                throw new IllegalArgumentException("JSON object must be a JSON object");
            }
            Object value = valueAt(map, keys);
            ctx.setOutput(node, "value", value);
        });

        operations.put("json_set", (ctx, node) -> {
            Object object = ctx.getInputValue(node, "object", Object.class, null);
            String path = ctx.getInputValue(node, "path", String.class, "");
            Object value = ctx.getInputValue(node, "value", Object.class, null);
            if (object instanceof Map) {
                Map<String, Object> map = (Map<String, Object>) object;
                String[] keys = path.split("\\.");
                if (keys.length == 1) {
                    map.put(keys[0], value);
                } else {
                    Map<String, Object> current = map;
                    for (int i = 0; i < keys.length - 1; i++) {
                        if (!current.containsKey(keys[i]) || !(current.get(keys[i]) instanceof Map)) {
                            current.put(keys[i], new HashMap<String, Object>());
                        }
                        current = (Map<String, Object>) current.get(keys[i]);
                    }
                    current.put(keys[keys.length - 1], value);
                }
            }
        });

        operations.put("json_delete", (ctx, node) -> {
            Object object = ctx.getInputValue(node, "object", Object.class, null);
            String path = ctx.getInputValue(node, "path", String.class, "");
            if (object instanceof Map) {
                Map<String, Object> map = (Map<String, Object>) object;
                String[] keys = path.split("\\.");
                if (keys.length == 1) {
                    map.remove(keys[0]);
                } else {
                    Map<String, Object> current = map;
                    for (int i = 0; i < keys.length - 1; i++) {
                        if (current.containsKey(keys[i]) && current.get(keys[i]) instanceof Map) {
                            current = (Map<String, Object>) current.get(keys[i]);
                        } else {
                            break;
                        }
                    }
                    current.remove(keys[keys.length - 1]);
                }
            }
        });

        operations.put("json_has", (ctx, node) -> {
            Object object = ctx.getInputValue(node, "object", Object.class, null);
            String path = ctx.getInputValue(node, "path", String.class, "");
            String[] keys = validatePath(path);
            Object canonicalObject = canonicalCopy(object, "JSON object");
            if (!(canonicalObject instanceof Map<?, ?> map)) {
                throw new IllegalArgumentException("JSON object must be a JSON object");
            }
            ctx.setOutput(node, "has", hasAt(map, keys));
        });

        operations.put("json_keys", (ctx, node) -> {
            Object object = ctx.getInputValue(node, "object", Object.class, null);
            List<String> keys = new ArrayList<>();
            Object canonicalObject = canonicalCopy(object, "JSON object");
            if (canonicalObject instanceof Map<?, ?> map) {
                for (Object key : map.keySet()) {
                    keys.add((String) key);
                }
            }
            ctx.setOutput(node, "keys", keys);
        });

        operations.put("json_merge", (ctx, node) -> {
            Object object1 = ctx.getInputValue(node, "object1", Object.class, null);
            Object object2 = ctx.getInputValue(node, "object2", Object.class, null);
            Object canonicalObject1 = canonicalCopy(object1, "JSON object1");
            Object canonicalObject2 = canonicalCopy(object2, "JSON object2");
            Map<String, Object> merged = new LinkedHashMap<>();
            if (canonicalObject1 instanceof Map<?, ?> map1) {
                for (Map.Entry<?, ?> entry : map1.entrySet()) {
                    merged.put((String) entry.getKey(), entry.getValue());
                }
            }
            if (canonicalObject2 instanceof Map<?, ?> map2) {
                for (Map.Entry<?, ?> entry : map2.entrySet()) {
                    merged.put((String) entry.getKey(), entry.getValue());
                }
            }
            ctx.setOutput(node, "merged", canonicalCopy(merged, "JSON merge output"));
        });

        operations.put("json_create", (ctx, node) -> {
            Map<String, Object> object = (Map<String, Object>) canonicalCopy(new LinkedHashMap<>(), "JSON object");
            ctx.setOutput(node, "object", object);
        });

        operations.put("json_set_array", (ctx, node) -> {
            List<Object> values = ctx.getInputValue(node, "values", List.class, new ArrayList<>());
            ctx.setOutput(node, "array", canonicalCopy(values, "JSON array"));
        });
    }

    private static String canonicalize(Object value, String label) {
        String canonical = CanonicalJson.canonicalize(value, CANONICAL_LIMITS);
        requireTextLength(canonical, label);
        return canonical;
    }

    private static Object canonicalCopy(Object value, String label) {
        return CanonicalJson.parse(canonicalize(value, label), CANONICAL_LIMITS);
    }

    private static void requireCanonicalTextLength(Object value, String label) {
        canonicalize(value, label);
    }

    private static void requireTextLength(String value, String label) {
        if (value == null) {
            throw new IllegalArgumentException(label + " is required");
        }
        if (value.length() > FlowPacketSender.MAX_STRING_LENGTH) {
            throw new IllegalArgumentException(label + " cannot exceed " + FlowPacketSender.MAX_STRING_LENGTH + " characters");
        }
    }

    private static String[] validatePath(String path) {
        requireTextLength(path, "JSON path");
        requireTextLength(canonicalize(path, "JSON path"), "JSON path");
        if (path.isBlank()) {
            throw new IllegalArgumentException("JSON path cannot be blank");
        }
        String[] keys = path.split("\\.", -1);
        if (keys.length > 64) {
            throw new IllegalArgumentException("JSON path cannot contain more than 64 segments");
        }
        for (String key : keys) {
            if (key.isEmpty()) {
                throw new IllegalArgumentException("JSON path segments cannot be empty");
            }
        }
        return keys;
    }

    private static Object valueAt(Map<?, ?> root, String[] keys) {
        Object current = root;
        for (String key : keys) {
            if (!(current instanceof Map<?, ?> map)) {
                throw new IllegalArgumentException("JSON path traverses a non-object value");
            }
            if (!map.containsKey(key)) {
                return null;
            }
            current = map.get(key);
        }
        return current;
    }

    private static boolean hasAt(Map<?, ?> root, String[] keys) {
        Object current = root;
        for (int index = 0; index < keys.length; index++) {
            if (!(current instanceof Map<?, ?> map)) {
                throw new IllegalArgumentException("JSON path traverses a non-object value");
            }
            if (!map.containsKey(keys[index])) {
                return false;
            }
            if (index == keys.length - 1) {
                return true;
            }
            current = map.get(keys[index]);
        }
        return false;
    }

    public void registerTo(HandlerRegistry registry) {
        registry.register("JsonHandler", this);
    }

    @Override
    public void execute(FlowContext ctx, FlowNode node) {
        String operation = node.getHandlerConfig().getString("operation");
        BiConsumer<FlowContext, FlowNode> op = operation != null ? operations.get(operation) : null;
        if (op == null) {
            throw new IllegalArgumentException("Unknown JSON operation: " + operation);
        }
        op.accept(ctx, node);
        ctx.triggerOutput("flow");
    }
}
