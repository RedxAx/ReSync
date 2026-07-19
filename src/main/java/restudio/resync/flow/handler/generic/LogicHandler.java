package restudio.resync.flow.handler.generic;

import restudio.flow.data.FlowNode;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.NodeHandler;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

public class LogicHandler implements NodeHandler {

    private final Map<String, BiConsumer<FlowContext, FlowNode>> operations = new ConcurrentHashMap<>();

    public LogicHandler() {
        registerLogicOperations();
        registerComparisonOperations();
    }

    private void registerLogicOperations() {
        operations.put("and", (ctx, node) -> {
            Boolean a = ctx.getInputValue(node, "a", Boolean.class, false);
            Boolean b = ctx.getInputValue(node, "b", Boolean.class, false);
            ctx.setOutput(node, "result", a && b);
        });
        operations.put("or", (ctx, node) -> {
            Boolean a = ctx.getInputValue(node, "a", Boolean.class, false);
            Boolean b = ctx.getInputValue(node, "b", Boolean.class, false);
            ctx.setOutput(node, "result", a || b);
        });
        operations.put("not", (ctx, node) -> {
            Boolean a = ctx.getInputValue(node, "value", Boolean.class, false);
            ctx.setOutput(node, "result", !a);
        });
        operations.put("xor", (ctx, node) -> {
            Boolean a = ctx.getInputValue(node, "a", Boolean.class, false);
            Boolean b = ctx.getInputValue(node, "b", Boolean.class, false);
            ctx.setOutput(node, "result", a ^ b);
        });
        operations.put("nand", (ctx, node) -> {
            Boolean a = ctx.getInputValue(node, "a", Boolean.class, false);
            Boolean b = ctx.getInputValue(node, "b", Boolean.class, false);
            ctx.setOutput(node, "result", !(a && b));
        });
        operations.put("nor", (ctx, node) -> {
            Boolean a = ctx.getInputValue(node, "a", Boolean.class, false);
            Boolean b = ctx.getInputValue(node, "b", Boolean.class, false);
            ctx.setOutput(node, "result", !(a || b));
        });
        operations.put("true", (ctx, node) -> {
            ctx.setOutput(node, "value", true);
        });
        operations.put("false", (ctx, node) -> {
            ctx.setOutput(node, "value", false);
        });
    }

    private void registerComparisonOperations() {
        operations.put("equals", (ctx, node) -> {
            Object a = ctx.getInputValue(node, "a", null);
            Object b = ctx.getInputValue(node, "b", null);
            ctx.setOutput(node, "result", dynamicEquals(a, b));
        });
        operations.put("not_equals", (ctx, node) -> {
            Object a = ctx.getInputValue(node, "a", null);
            Object b = ctx.getInputValue(node, "b", null);
            ctx.setOutput(node, "result", !dynamicEquals(a, b));
        });
        operations.put("greater", (ctx, node) -> {
            Double a = ctx.getInputValue(node, "a", Double.class, 0.0);
            Double b = ctx.getInputValue(node, "b", Double.class, 0.0);
            ctx.setOutput(node, "result", a > b);
        });
        operations.put("less", (ctx, node) -> {
            Double a = ctx.getInputValue(node, "a", Double.class, 0.0);
            Double b = ctx.getInputValue(node, "b", Double.class, 0.0);
            ctx.setOutput(node, "result", a < b);
        });
        operations.put("greater_or_equal", (ctx, node) -> {
            Double a = ctx.getInputValue(node, "a", Double.class, 0.0);
            Double b = ctx.getInputValue(node, "b", Double.class, 0.0);
            ctx.setOutput(node, "result", a >= b);
        });
        operations.put("less_or_equal", (ctx, node) -> {
            Double a = ctx.getInputValue(node, "a", Double.class, 0.0);
            Double b = ctx.getInputValue(node, "b", Double.class, 0.0);
            ctx.setOutput(node, "result", a <= b);
        });
        operations.put("between", (ctx, node) -> {
            Double value = ctx.getInputValue(node, "value", Double.class, 0.0);
            Double min = ctx.getInputValue(node, "min", Double.class, 0.0);
            Double max = ctx.getInputValue(node, "max", Double.class, 0.0);
            ctx.setOutput(node, "result", value >= min && value <= max);
        });
        operations.put("type", (ctx, node) -> {
            Object value = ctx.getInputValue(node, "value", null);
            String typeName = ctx.getInputValue(node, "type", String.class, "");
            ctx.setOutput(node, "result", matchesType(value, typeName));
        });
    }

    private static boolean dynamicEquals(Object first, Object second) {
        if (first == null || second == null) {
            return first == second;
        }
        BigDecimal firstNumber = numberValue(first);
        BigDecimal secondNumber = numberValue(second);
        if (firstNumber != null && secondNumber != null) {
            return firstNumber.compareTo(secondNumber) == 0;
        }
        Boolean firstBoolean = booleanValue(first);
        Boolean secondBoolean = booleanValue(second);
        if (firstBoolean != null && secondBoolean != null) {
            return firstBoolean.equals(secondBoolean);
        }
        return Objects.equals(first, second);
    }

    private static BigDecimal numberValue(Object value) {
        if (value instanceof Number number) {
            try {
                return new BigDecimal(number.toString());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        if (value instanceof String string) {
            String trimmed = string.trim();
            if (trimmed.isEmpty()) {
                return null;
            }
            try {
                return new BigDecimal(trimmed);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static Boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String string) {
            String trimmed = string.trim();
            if ("true".equalsIgnoreCase(trimmed)) {
                return true;
            }
            if ("false".equalsIgnoreCase(trimmed)) {
                return false;
            }
        }
        return null;
    }

    private static boolean matchesType(Object value, String typeName) {
        if (value == null) return typeName.equalsIgnoreCase("null");
        String lower = typeName.toLowerCase();
        return switch (lower) {
            case "string" -> value.getClass() == String.class;
            case "number", "double", "int", "float", "long" -> value instanceof Number;
            case "boolean", "bool" -> value.getClass() == Boolean.class;
            case "list", "array", "collection" -> value instanceof Collection;
            case "map", "json" -> value instanceof Map;
            default -> false;
        };
    }

    public void registerTo(HandlerRegistry registry) {
        registry.register("LogicHandler", this);
    }

    @Override
    public void execute(FlowContext ctx, FlowNode node) {
        String operation = node.getHandlerConfig().getString("operation");
        BiConsumer<FlowContext, FlowNode> op = operation != null ? operations.get(operation) : null;
        if (op == null) {
            throw new IllegalArgumentException("Unknown logic operation: " + operation);
        }
        op.accept(ctx, node);
        ctx.triggerOutput("flow");
    }
}
