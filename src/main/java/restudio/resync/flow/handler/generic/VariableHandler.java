package restudio.resync.flow.handler.generic;

import restudio.flow.data.FlowNode;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.NodeHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

public class VariableHandler implements NodeHandler {

    private final Map<String, BiConsumer<FlowContext, FlowNode>> operations = new ConcurrentHashMap<>();

    public VariableHandler() {
        registerOperations();
    }

    private void registerOperations() {
        operations.put("get", (ctx, node) -> {
            String name = ctx.getInputValue(node, "name", String.class, "");
            Object value = ctx.getVariable(name);
            ctx.setOutput(node, "value", value);
        });
        operations.put("set", (ctx, node) -> {
            String name = ctx.getInputValue(node, "name", String.class, "");
            Object value = ctx.getInputValue(node, "value", null);
            ctx.setVariable(name, value);
        });
        operations.put("has", (ctx, node) -> {
            String name = ctx.getInputValue(node, "name", String.class, "");
            Object value = ctx.getVariable(name);
            ctx.setOutput(node, "has", value != null);
        });
        operations.put("delete", (ctx, node) -> {
            String name = ctx.getInputValue(node, "name", String.class, "");
            ctx.setVariable(name, null);
        });
        operations.put("increment", (ctx, node) -> {
            String name = ctx.getInputValue(node, "name", String.class, "");
            double delta = ctx.getInputValue(node, "delta", Number.class, 1).doubleValue();
            Object current = ctx.getVariable(name);
            double base = current instanceof Number n ? n.doubleValue() : 0.0;
            ctx.setVariable(name, base + delta);
        });
        operations.put("decrement", (ctx, node) -> {
            String name = ctx.getInputValue(node, "name", String.class, "");
            double delta = ctx.getInputValue(node, "delta", Number.class, 1).doubleValue();
            Object current = ctx.getVariable(name);
            double base = current instanceof Number n ? n.doubleValue() : 0.0;
            ctx.setVariable(name, base - delta);
        });
        operations.put("multiply", (ctx, node) -> {
            String name = ctx.getInputValue(node, "name", String.class, "");
            double factor = ctx.getInputValue(node, "factor", Number.class, 1).doubleValue();
            Object current = ctx.getVariable(name);
            double base = current instanceof Number n ? n.doubleValue() : 0.0;
            ctx.setVariable(name, base * factor);
        });
        operations.put("divide", (ctx, node) -> {
            String name = ctx.getInputValue(node, "name", String.class, "");
            double divisor = ctx.getInputValue(node, "divisor", Number.class, 1).doubleValue();
            Object current = ctx.getVariable(name);
            double base = current instanceof Number n ? n.doubleValue() : 0.0;
            ctx.setVariable(name, divisor != 0 ? base / divisor : base);
        });
        operations.put("append", (ctx, node) -> {
            String name = ctx.getInputValue(node, "name", String.class, "");
            Object value = ctx.getInputValue(node, "value", null);
            Object current = ctx.getVariable(name);
            if (current instanceof List list) {
                list.add(value);
            } else if (current instanceof String s && value != null) {
                ctx.setVariable(name, s + value);
            } else {
                List<Object> list = new ArrayList<>();
                if (current != null) list.add(current);
                if (value != null) list.add(value);
                ctx.setVariable(name, list);
            }
        });
        operations.put("list_keys", (ctx, node) -> {
            String prefix = ctx.getInputValue(node, "prefix", String.class, "");
            List<String> keys = new ArrayList<>();
            for (Map.Entry<String, Object> entry : ctx.getLocalVariables().entrySet()) {
                if (entry.getKey().startsWith(prefix)) {
                    keys.add(entry.getKey());
                }
            }
            for (Map.Entry<String, Object> entry : ctx.getGlobalVariables().entrySet()) {
                if (entry.getKey().startsWith(prefix) && !keys.contains(entry.getKey())) {
                    keys.add(entry.getKey());
                }
            }
            ctx.setOutput(node, "keys", keys);
        });
    }

    public void registerTo(HandlerRegistry registry) {
        registry.register("VariableHandler", this);
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
