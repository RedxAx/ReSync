package restudio.resync.flow.handler.generic;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import restudio.flow.data.FlowNode;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.NodeHandler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

public class JsonHandler implements NodeHandler {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final Map<String, BiConsumer<FlowContext, FlowNode>> operations = new ConcurrentHashMap<>();

    public JsonHandler() {
        operations.put("json_parse", (ctx, node) -> {
            String jsonString = ctx.getInputValue(node, "json_string", String.class, "{}");
            try {
                JsonElement element = JsonParser.parseString(jsonString);
                Object result = GSON.fromJson(element, Object.class);
                ctx.setOutput(node, "object", result);
            } catch (RuntimeException exception) {
                throw new IllegalArgumentException("Invalid JSON input", exception);
            }
        });

        operations.put("json_to_string", (ctx, node) -> {
            Object object = ctx.getInputValue(node, "object", Object.class, null);
            String jsonString = GSON.toJson(object);
            ctx.setOutput(node, "string", jsonString);
        });

        operations.put("json_get", (ctx, node) -> {
            Object object = ctx.getInputValue(node, "object", Object.class, null);
            String path = ctx.getInputValue(node, "path", String.class, "");
            Object value = null;
            if (object instanceof Map) {
                Map<?, ?> map = (Map<?, ?>) object;
                String[] keys = path.split("\\.");
                Object current = map;
                for (String key : keys) {
                    if (current instanceof Map) {
                        current = ((Map<?, ?>) current).get(key);
                    } else {
                        current = null;
                        break;
                    }
                }
                value = current;
            }
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
            boolean hasKey = false;
            if (object instanceof Map) {
                Map<?, ?> map = (Map<?, ?>) object;
                String[] keys = path.split("\\.");
                Object current = map;
                for (int i = 0; i < keys.length; i++) {
                    if (current instanceof Map) {
                        if (!((Map<?, ?>) current).containsKey(keys[i])) {
                            current = null;
                            break;
                        }
                        if (i == keys.length - 1) {
                            hasKey = true;
                        } else {
                            current = ((Map<?, ?>) current).get(keys[i]);
                        }
                    } else {
                        break;
                    }
                }
            }
            ctx.setOutput(node, "has", hasKey);
        });

        operations.put("json_keys", (ctx, node) -> {
            Object object = ctx.getInputValue(node, "object", Object.class, null);
            List<String> keys = new ArrayList<>();
            if (object instanceof Map) {
                keys.addAll(((Map<?, ?>) object).keySet().stream()
                        .map(k -> k != null ? k.toString() : "null")
                        .collect(Collectors.toList()));
            }
            ctx.setOutput(node, "keys", keys);
        });

        operations.put("json_merge", (ctx, node) -> {
            Object object1 = ctx.getInputValue(node, "object1", Object.class, null);
            Object object2 = ctx.getInputValue(node, "object2", Object.class, null);
            Map<String, Object> merged = new HashMap<>();
            if (object1 instanceof Map) {
                merged.putAll((Map<String, Object>) object1);
            }
            if (object2 instanceof Map) {
                merged.putAll((Map<String, Object>) object2);
            }
            ctx.setOutput(node, "merged", merged);
        });

        operations.put("json_create", (ctx, node) -> {
            Map<String, Object> object = new HashMap<>();
            ctx.setOutput(node, "object", object);
        });

        operations.put("json_set_array", (ctx, node) -> {
            List<Object> values = ctx.getInputValue(node, "values", List.class, new ArrayList<>());
            ctx.setOutput(node, "array", values);
        });
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
