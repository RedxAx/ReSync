package restudio.resync.flow.handler.generic;

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

public class GenericMapHandler implements NodeHandler {

    private final Map<String, BiConsumer<FlowContext, FlowNode>> operations = new ConcurrentHashMap<>();

    public GenericMapHandler() {
        registerOperations();
    }

    private void registerOperations() {
        operations.put("create", (ctx, node) -> {
            ctx.setOutput(node, "map", new HashMap<>());
        });
        operations.put("get", (ctx, node) -> {
            Map<String, Object> map = ctx.getInputValue(node, "map", Map.class, Map.of());
            String key = ctx.getInputValue(node, "key", String.class, "");
            ctx.setOutput(node, "value", map.get(key));
        });
        operations.put("set", (ctx, node) -> {
            Map<String, Object> map = ctx.getInputValue(node, "map", Map.class, new HashMap<>());
            String key = ctx.getInputValue(node, "key", String.class, "");
            Object value = ctx.getInputValue(node, "value", null);
            map.put(key, value);
            ctx.setOutput(node, "map", map);
        });
        operations.put("contains_key", (ctx, node) -> {
            Map<String, Object> map = ctx.getInputValue(node, "map", Map.class, Map.of());
            String key = ctx.getInputValue(node, "key", String.class, "");
            ctx.setOutput(node, "contains", map.containsKey(key));
        });
        operations.put("contains_value", (ctx, node) -> {
            Map<String, Object> map = ctx.getInputValue(node, "map", Map.class, Map.of());
            Object value = ctx.getInputValue(node, "value", null);
            ctx.setOutput(node, "contains", map.containsValue(value));
        });
        operations.put("keys", (ctx, node) -> {
            Map<String, Object> map = ctx.getInputValue(node, "map", Map.class, Map.of());
            ctx.setOutput(node, "keys", new ArrayList<>(map.keySet()));
        });
        operations.put("values", (ctx, node) -> {
            Map<String, Object> map = ctx.getInputValue(node, "map", Map.class, Map.of());
            ctx.setOutput(node, "values", new ArrayList<>(map.values()));
        });
        operations.put("size", (ctx, node) -> {
            Map<String, Object> map = ctx.getInputValue(node, "map", Map.class, Map.of());
            ctx.setOutput(node, "size", map.size());
        });
        operations.put("is_empty", (ctx, node) -> {
            Map<String, Object> map = ctx.getInputValue(node, "map", Map.class, Map.of());
            ctx.setOutput(node, "is_empty", map.isEmpty());
        });
        operations.put("remove", (ctx, node) -> {
            Map<String, Object> map = ctx.getInputValue(node, "map", Map.class, new HashMap<>());
            String key = ctx.getInputValue(node, "key", String.class, "");
            map.remove(key);
            ctx.setOutput(node, "map", map);
        });
        operations.put("clear", (ctx, node) -> {
            Map<String, Object> map = ctx.getInputValue(node, "map", Map.class, new HashMap<>());
            map.clear();
            ctx.setOutput(node, "map", map);
        });
        operations.put("merge", (ctx, node) -> {
            Map<String, Object> mapA = ctx.getInputValue(node, "mapA", Map.class, new HashMap<>());
            Map<String, Object> mapB = ctx.getInputValue(node, "mapB", Map.class, new HashMap<>());
            Map<String, Object> result = new HashMap<>(mapA);
            result.putAll(mapB);
            ctx.setOutput(node, "map", result);
        });
        operations.put("put_all", (ctx, node) -> {
            Map<String, Object> map = ctx.getInputValue(node, "map", Map.class, new HashMap<>());
            Map<String, Object> other = ctx.getInputValue(node, "other", Map.class, Map.of());
            map.putAll(other);
            ctx.setOutput(node, "map", map);
        });
    }

    public void registerTo(HandlerRegistry registry) {
        registry.register("GenericMapHandler", this);
    }

    @Override
    public void execute(FlowContext ctx, FlowNode node) {
        String operation = node.getHandlerConfig().getString("operation");
        BiConsumer<FlowContext, FlowNode> op = operation != null ? operations.get(operation) : null;
        if (op != null) {
            op.accept(ctx, node);
        }
        ctx.triggerOutput("flow");
    }
}
