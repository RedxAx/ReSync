package restudio.resync.flow.handler.generic;

import org.bukkit.Location;
import org.bukkit.util.Vector;
import restudio.flow.data.FlowNode;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.NodeHandler;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

public class GenericMathHandler implements NodeHandler {

    private static final Random RANDOM = new Random();
    private final Map<String, BiConsumer<FlowContext, FlowNode>> operations = new ConcurrentHashMap<>();

    public GenericMathHandler() {
        registerBasicOperations();
        registerAdvancedOperations();
        registerVectorOperations();
        registerTrigOperations();
    }

    private void registerBasicOperations() {
        operations.put("add", (ctx, node) -> {
            Double a = ctx.getInputValue(node, "a", Double.class, 0.0);
            Double b = ctx.getInputValue(node, "b", Double.class, 0.0);
            ctx.setOutput(node, "result", a + b);
        });
        operations.put("subtract", (ctx, node) -> {
            Double a = ctx.getInputValue(node, "a", Double.class, 0.0);
            Double b = ctx.getInputValue(node, "b", Double.class, 0.0);
            ctx.setOutput(node, "result", a - b);
        });
        operations.put("multiply", (ctx, node) -> {
            Double a = ctx.getInputValue(node, "a", Double.class, 0.0);
            Double b = ctx.getInputValue(node, "b", Double.class, 0.0);
            ctx.setOutput(node, "result", a * b);
        });
        operations.put("divide", (ctx, node) -> {
            Double a = ctx.getInputValue(node, "a", Double.class, 0.0);
            Double b = ctx.getInputValue(node, "b", Double.class, 1.0);
            ctx.setOutput(node, "result", b != 0 ? a / b : 0.0);
        });
        operations.put("modulo", (ctx, node) -> {
            Double a = ctx.getInputValue(node, "a", Double.class, 0.0);
            Double b = ctx.getInputValue(node, "b", Double.class, 1.0);
            ctx.setOutput(node, "result", b != 0 ? a % b : 0.0);
        });
        operations.put("power", (ctx, node) -> {
            Double base = ctx.getInputValue(node, "base", Double.class, 0.0);
            Double exponent = ctx.getInputValue(node, "exponent", Double.class, 0.0);
            ctx.setOutput(node, "result", Math.pow(base, exponent));
        });
        operations.put("sqrt", (ctx, node) -> {
            Double value = ctx.getInputValue(node, "value", Double.class, 0.0);
            ctx.setOutput(node, "sqrt", Math.sqrt(value));
        });
        operations.put("abs", (ctx, node) -> {
            Double value = ctx.getInputValue(node, "value", Double.class, 0.0);
            ctx.setOutput(node, "absolute", Math.abs(value));
        });
        operations.put("floor", (ctx, node) -> {
            Double value = ctx.getInputValue(node, "value", Double.class, 0.0);
            ctx.setOutput(node, "floored", Math.floor(value));
        });
        operations.put("ceil", (ctx, node) -> {
            Double value = ctx.getInputValue(node, "value", Double.class, 0.0);
            ctx.setOutput(node, "ceiling", Math.ceil(value));
        });
        operations.put("round", (ctx, node) -> {
            Double value = ctx.getInputValue(node, "value", Double.class, 0.0);
            Integer decimalPlaces = ctx.getInputValue(node, "decimal_places", Integer.class, 0);
            double factor = Math.pow(10.0, Math.clamp(decimalPlaces, -15, 15));
            ctx.setOutput(node, "rounded", Math.round(value * factor) / factor);
        });
        operations.put("min", (ctx, node) -> {
            List<?> values = ctx.getInputValue(node, "values_list", List.class, List.of());
            ctx.setOutput(node, "min", values.stream().filter(Number.class::isInstance).map(Number.class::cast).mapToDouble(Number::doubleValue).min().orElse(0.0));
        });
        operations.put("max", (ctx, node) -> {
            List<?> values = ctx.getInputValue(node, "values_list", List.class, List.of());
            ctx.setOutput(node, "max", values.stream().filter(Number.class::isInstance).map(Number.class::cast).mapToDouble(Number::doubleValue).max().orElse(0.0));
        });
        operations.put("clamp", (ctx, node) -> {
            Double value = ctx.getInputValue(node, "value", Double.class, 0.0);
            Double min = ctx.getInputValue(node, "min", Double.class, 0.0);
            Double max = ctx.getInputValue(node, "max", Double.class, 1.0);
            ctx.setOutput(node, "clamped", Math.max(min, Math.min(max, value)));
        });
        operations.put("random", (ctx, node) -> {
            Double min = ctx.getInputValue(node, "min", Double.class, 0.0);
            Double max = ctx.getInputValue(node, "max", Double.class, 1.0);
            ctx.setOutput(node, "result", min + Math.random() * (max - min));
        });
        operations.put("negate", (ctx, node) -> {
            Double value = ctx.getInputValue(node, "value", Double.class, 0.0);
            ctx.setOutput(node, "result", -value);
        });
        operations.put("distance", (ctx, node) -> {
            Double x1 = ctx.getInputValue(node, "x1", Double.class, 0.0);
            Double y1 = ctx.getInputValue(node, "y1", Double.class, 0.0);
            Double z1 = ctx.getInputValue(node, "z1", Double.class, 0.0);
            Double x2 = ctx.getInputValue(node, "x2", Double.class, 0.0);
            Double y2 = ctx.getInputValue(node, "y2", Double.class, 0.0);
            Double z2 = ctx.getInputValue(node, "z2", Double.class, 0.0);
            double dx = x2 - x1, dy = y2 - y1, dz = z2 - z1;
            ctx.setOutput(node, "result", Math.sqrt(dx * dx + dy * dy + dz * dz));
        });
    }

    private void registerAdvancedOperations() {
        operations.put("random_range", (ctx, node) -> {
            Double min = ctx.getInputValue(node, "min", Double.class, 0.0);
            Double max = ctx.getInputValue(node, "max", Double.class, 1.0);
            ctx.setOutput(node, "result", min + RANDOM.nextDouble() * (max - min));
        });
        operations.put("random_chance", (ctx, node) -> {
            Double chancePercent = ctx.getInputValue(node, "chance_percent", Double.class, 50.0);
            ctx.setOutput(node, "success", RANDOM.nextDouble() * 100 < chancePercent);
        });
        operations.put("random_choice", (ctx, node) -> {
            List<?> itemsList = ctx.getInputValue(node, "items_list", List.class, null);
            Object chosenItem = null;
            if (itemsList != null && !itemsList.isEmpty()) {
                chosenItem = itemsList.get(RANDOM.nextInt(itemsList.size()));
            }
            ctx.setOutput(node, "chosen_item", chosenItem);
        });
        operations.put("random_choice_weighted", (ctx, node) -> {
            List<?> itemsList = ctx.getInputValue(node, "items_list", List.class, null);
            List<Double> weightsList = ctx.getInputValue(node, "weights_list", List.class, null);
            Object chosenItem = null;
            if (itemsList != null && !itemsList.isEmpty() && weightsList != null && weightsList.size() == itemsList.size()) {
                double totalWeight = weightsList.stream().mapToDouble(d -> d).sum();
                double randomWeight = RANDOM.nextDouble() * totalWeight;
                double currentWeight = 0.0;
                for (int i = 0; i < itemsList.size(); i++) {
                    currentWeight += weightsList.get(i);
                    if (randomWeight <= currentWeight) {
                        chosenItem = itemsList.get(i);
                        break;
                    }
                }
            }
            ctx.setOutput(node, "chosen_item", chosenItem);
        });
        operations.put("lerp", (ctx, node) -> {
            Double a = ctx.getInputValue(node, "a", Double.class, 0.0);
            Double b = ctx.getInputValue(node, "b", Double.class, 0.0);
            Double t = ctx.getInputValue(node, "t", Double.class, 0.5);
            ctx.setOutput(node, "result", a + (b - a) * Math.max(0.0, Math.min(1.0, t)));
        });
        operations.put("hypotenuse", (ctx, node) -> {
            Double a = ctx.getInputValue(node, "a", Double.class, 0.0);
            Double b = ctx.getInputValue(node, "b", Double.class, 0.0);
            ctx.setOutput(node, "hypotenuse", Math.hypot(a, b));
        });
        operations.put("log", (ctx, node) -> {
            Double value = ctx.getInputValue(node, "value", Double.class, 1.0);
            ctx.setOutput(node, "log", Math.log(value));
        });
        operations.put("log10", (ctx, node) -> {
            Double value = ctx.getInputValue(node, "value", Double.class, 1.0);
            ctx.setOutput(node, "log10", Math.log10(value));
        });
        operations.put("cbrt", (ctx, node) -> {
            Double value = ctx.getInputValue(node, "value", Double.class, 0.0);
            ctx.setOutput(node, "cbrt", Math.cbrt(value));
        });
        operations.put("pow", (ctx, node) -> {
            Double base = ctx.getInputValue(node, "base", Double.class, 0.0);
            Double exponent = ctx.getInputValue(node, "exponent", Double.class, 1.0);
            ctx.setOutput(node, "result", Math.pow(base, exponent));
        });
        operations.put("signum", (ctx, node) -> {
            Double value = ctx.getInputValue(node, "value", Double.class, 0.0);
            ctx.setOutput(node, "sign", Math.signum(value));
        });
        operations.put("min_list", (ctx, node) -> {
            List<Double> valuesList = ctx.getInputValue(node, "values_list", List.class, null);
            double min = valuesList != null && !valuesList.isEmpty() ? valuesList.stream().mapToDouble(d -> d).min().orElse(0.0) : 0.0;
            ctx.setOutput(node, "min", min);
        });
        operations.put("max_list", (ctx, node) -> {
            List<Double> valuesList = ctx.getInputValue(node, "values_list", List.class, null);
            double max = valuesList != null && !valuesList.isEmpty() ? valuesList.stream().mapToDouble(d -> d).max().orElse(0.0) : 0.0;
            ctx.setOutput(node, "max", max);
        });
        operations.put("round_decimal", (ctx, node) -> {
            Double value = ctx.getInputValue(node, "value", Double.class, 0.0);
            Integer decimalPlaces = ctx.getInputValue(node, "decimal_places", Integer.class, 0);
            double factor = Math.pow(10, decimalPlaces);
            ctx.setOutput(node, "rounded", Math.round(value * factor) / factor);
        });
    }

    private void registerVectorOperations() {
        operations.put("vector_create", (ctx, node) -> {
            Double x = ctx.getInputValue(node, "x", Double.class, 0.0);
            Double y = ctx.getInputValue(node, "y", Double.class, 0.0);
            Double z = ctx.getInputValue(node, "z", Double.class, 0.0);
            ctx.setOutput(node, "vector", new Vector(x, y, z));
        });
        operations.put("vector_create_int", (ctx, node) -> {
            Double x = ctx.getInputValue(node, "x", Double.class, 0.0);
            Double y = ctx.getInputValue(node, "y", Double.class, 0.0);
            Double z = ctx.getInputValue(node, "z", Double.class, 0.0);
            ctx.setOutput(node, "vector", new Vector(x.intValue(), y.intValue(), z.intValue()));
        });
        operations.put("vector_split", (ctx, node) -> {
            Vector vector = ctx.getInputValue(node, "vector", Vector.class, new Vector());
            ctx.setOutput(node, "x", vector.getX());
            ctx.setOutput(node, "y", vector.getY());
            ctx.setOutput(node, "z", vector.getZ());
            ctx.setOutput(node, "block_x", vector.getBlockX());
            ctx.setOutput(node, "block_y", vector.getBlockY());
            ctx.setOutput(node, "block_z", vector.getBlockZ());
        });
        operations.put("vector_set", (ctx, node) -> {
            Object source = ctx.getInputValue(node, "vector");
            Vector vector = vectorFrom(source).clone();
            String component = ctx.getInputValue(node, "component", String.class, "x");
            Double value = ctx.getInputValue(node, "value", Double.class, 0.0);
            switch (component != null ? component.toLowerCase(Locale.ROOT) : "x") {
                case "y" -> vector.setY(value);
                case "z" -> vector.setZ(value);
                default -> vector.setX(value);
            }
            ctx.setOutput(node, "vector", preserveVectorShape(source, vector));
        });
        operations.put("vector_add", (ctx, node) -> {
            Object source = ctx.getInputValue(node, "vector1");
            Vector vector1 = vectorFrom(source);
            Vector vector2 = ctx.getInputValue(node, "vector2", Vector.class, new Vector());
            ctx.setOutput(node, "result_vector", preserveVectorShape(source, vector1.clone().add(vector2)));
        });
        operations.put("vector_subtract", (ctx, node) -> {
            Object source = ctx.getInputValue(node, "vector1");
            Vector vector1 = vectorFrom(source);
            Vector vector2 = ctx.getInputValue(node, "vector2", Vector.class, new Vector());
            ctx.setOutput(node, "result_vector", preserveVectorShape(source, vector1.clone().subtract(vector2)));
        });
        operations.put("vector_multiply", (ctx, node) -> {
            Object source = ctx.getInputValue(node, "vector");
            Vector vector = vectorFrom(source);
            Double scalar = ctx.getInputValue(node, "scalar", Double.class, 1.0);
            ctx.setOutput(node, "result_vector", preserveVectorShape(source, vector.clone().multiply(scalar)));
        });
        operations.put("vector_divide", (ctx, node) -> {
            Object source = ctx.getInputValue(node, "vector");
            Vector vector = vectorFrom(source);
            Double scalar = ctx.getInputValue(node, "scalar", Double.class, 1.0);
            ctx.setOutput(node, "result_vector", preserveVectorShape(source, scalar != 0 ? vector.clone().multiply(1.0 / scalar) : new Vector()));
        });
        operations.put("vector_multiply_components", (ctx, node) -> {
            Vector vector1 = ctx.getInputValue(node, "vector1", Vector.class, new Vector());
            Vector vector2 = ctx.getInputValue(node, "vector2", Vector.class, new Vector());
            ctx.setOutput(node, "result_vector", new Vector(vector1.getX() * vector2.getX(), vector1.getY() * vector2.getY(), vector1.getZ() * vector2.getZ()));
        });
        operations.put("vector_divide_components", (ctx, node) -> {
            Vector vector1 = ctx.getInputValue(node, "vector1", Vector.class, new Vector());
            Vector vector2 = ctx.getInputValue(node, "vector2", Vector.class, new Vector());
            ctx.setOutput(node, "result_vector", new Vector(safeDivide(vector1.getX(), vector2.getX()), safeDivide(vector1.getY(), vector2.getY()), safeDivide(vector1.getZ(), vector2.getZ())));
        });
        operations.put("vector_min", (ctx, node) -> {
            Vector vector1 = ctx.getInputValue(node, "vector1", Vector.class, new Vector());
            Vector vector2 = ctx.getInputValue(node, "vector2", Vector.class, new Vector());
            ctx.setOutput(node, "result_vector", new Vector(Math.min(vector1.getX(), vector2.getX()), Math.min(vector1.getY(), vector2.getY()), Math.min(vector1.getZ(), vector2.getZ())));
        });
        operations.put("vector_max", (ctx, node) -> {
            Vector vector1 = ctx.getInputValue(node, "vector1", Vector.class, new Vector());
            Vector vector2 = ctx.getInputValue(node, "vector2", Vector.class, new Vector());
            ctx.setOutput(node, "result_vector", new Vector(Math.max(vector1.getX(), vector2.getX()), Math.max(vector1.getY(), vector2.getY()), Math.max(vector1.getZ(), vector2.getZ())));
        });
        operations.put("vector_floor", (ctx, node) -> {
            Object source = ctx.getInputValue(node, "vector");
            Vector vector = vectorFrom(source);
            ctx.setOutput(node, "result_vector", preserveVectorShape(source, new Vector(Math.floor(vector.getX()), Math.floor(vector.getY()), Math.floor(vector.getZ()))));
        });
        operations.put("vector_ceil", (ctx, node) -> {
            Object source = ctx.getInputValue(node, "vector");
            Vector vector = vectorFrom(source);
            ctx.setOutput(node, "result_vector", preserveVectorShape(source, new Vector(Math.ceil(vector.getX()), Math.ceil(vector.getY()), Math.ceil(vector.getZ()))));
        });
        operations.put("vector_round", (ctx, node) -> {
            Object source = ctx.getInputValue(node, "vector");
            Vector vector = vectorFrom(source);
            ctx.setOutput(node, "result_vector", preserveVectorShape(source, new Vector(Math.round(vector.getX()), Math.round(vector.getY()), Math.round(vector.getZ()))));
        });
        operations.put("vector_dot", (ctx, node) -> {
            Vector vector1 = ctx.getInputValue(node, "vector1", Vector.class, new Vector());
            Vector vector2 = ctx.getInputValue(node, "vector2", Vector.class, new Vector());
            ctx.setOutput(node, "result", vector1.dot(vector2));
        });
        operations.put("vector_cross", (ctx, node) -> {
            Vector vector1 = ctx.getInputValue(node, "vector1", Vector.class, new Vector());
            Vector vector2 = ctx.getInputValue(node, "vector2", Vector.class, new Vector());
            ctx.setOutput(node, "result_vector", vector1.clone().crossProduct(vector2));
        });
        operations.put("vector_distance", (ctx, node) -> {
            Vector vector1 = ctx.getInputValue(node, "vector1", Vector.class, new Vector());
            Vector vector2 = ctx.getInputValue(node, "vector2", Vector.class, new Vector());
            ctx.setOutput(node, "distance", vector1.distance(vector2));
        });
        operations.put("vector_length", (ctx, node) -> {
            Vector vector = ctx.getInputValue(node, "vector", Vector.class, new Vector());
            ctx.setOutput(node, "length", vector.length());
        });
        operations.put("vector_normalize", (ctx, node) -> {
            Object source = ctx.getInputValue(node, "vector");
            Vector vector = vectorFrom(source);
            ctx.setOutput(node, "normalized_vector", preserveVectorShape(source, vector.clone().normalize()));
        });
        operations.put("vector_angle_between", (ctx, node) -> {
            Vector vector1 = ctx.getInputValue(node, "vector1", Vector.class, new Vector());
            Vector vector2 = ctx.getInputValue(node, "vector2", Vector.class, new Vector());
            double dot = vector1.clone().normalize().dot(vector2.clone().normalize());
            ctx.setOutput(node, "angle", Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, dot)))));
        });
        operations.put("vector_midpoint", (ctx, node) -> {
            Vector vector1 = ctx.getInputValue(node, "vector1", Vector.class, new Vector());
            Vector vector2 = ctx.getInputValue(node, "vector2", Vector.class, new Vector());
            ctx.setOutput(node, "midpoint_vector", vector1.clone().add(vector2).multiply(0.5));
        });
        operations.put("vector_rotate_x", (ctx, node) -> {
            Object source = ctx.getInputValue(node, "vector");
            Vector vector = vectorFrom(source);
            Double degrees = ctx.getInputValue(node, "degrees", Double.class, 0.0);
            double radians = Math.toRadians(degrees);
            double cos = Math.cos(radians);
            double sin = Math.sin(radians);
            ctx.setOutput(node, "rotated_vector", preserveVectorShape(source, new Vector(vector.getX(), vector.getY() * cos - vector.getZ() * sin, vector.getY() * sin + vector.getZ() * cos)));
        });
        operations.put("vector_rotate_y", (ctx, node) -> {
            Object source = ctx.getInputValue(node, "vector");
            Vector vector = vectorFrom(source);
            Double degrees = ctx.getInputValue(node, "degrees", Double.class, 0.0);
            double radians = Math.toRadians(degrees);
            double cos = Math.cos(radians);
            double sin = Math.sin(radians);
            ctx.setOutput(node, "rotated_vector", preserveVectorShape(source, new Vector(vector.getX() * cos + vector.getZ() * sin, vector.getY(), -vector.getX() * sin + vector.getZ() * cos)));
        });
        operations.put("vector_rotate_z", (ctx, node) -> {
            Object source = ctx.getInputValue(node, "vector");
            Vector vector = vectorFrom(source);
            Double degrees = ctx.getInputValue(node, "degrees", Double.class, 0.0);
            double radians = Math.toRadians(degrees);
            double cos = Math.cos(radians);
            double sin = Math.sin(radians);
            ctx.setOutput(node, "rotated_vector", preserveVectorShape(source, new Vector(vector.getX() * cos - vector.getY() * sin, vector.getX() * sin + vector.getY() * cos, vector.getZ())));
        });
    }

    private Vector vectorFrom(Object value) {
        if (value instanceof Location location) {
            return location.toVector();
        }
        if (value instanceof Vector vector) {
            return vector;
        }
        return new Vector();
    }

    private Object preserveVectorShape(Object source, Vector result) {
        if (source instanceof Location location) {
            Location preserved = location.clone();
            preserved.setX(result.getX());
            preserved.setY(result.getY());
            preserved.setZ(result.getZ());
            return preserved;
        }
        return result;
    }

    private double safeDivide(double dividend, double divisor) {
        return divisor != 0 ? dividend / divisor : 0.0;
    }

    private void registerTrigOperations() {
        operations.put("sin", (ctx, node) -> {
            Double angle = ctx.getInputValue(node, "angle_degrees", Double.class, 0.0);
            ctx.setOutput(node, "sin", Math.sin(Math.toRadians(angle)));
        });
        operations.put("cos", (ctx, node) -> {
            Double angle = ctx.getInputValue(node, "angle_degrees", Double.class, 0.0);
            ctx.setOutput(node, "cos", Math.cos(Math.toRadians(angle)));
        });
        operations.put("tan", (ctx, node) -> {
            Double angle = ctx.getInputValue(node, "angle_degrees", Double.class, 0.0);
            ctx.setOutput(node, "tan", Math.tan(Math.toRadians(angle)));
        });
        operations.put("to_radians", (ctx, node) -> {
            Double degrees = ctx.getInputValue(node, "degrees", Double.class, 0.0);
            ctx.setOutput(node, "radians", Math.toRadians(degrees));
        });
        operations.put("to_degrees", (ctx, node) -> {
            Double radians = ctx.getInputValue(node, "radians", Double.class, 0.0);
            ctx.setOutput(node, "degrees", Math.toDegrees(radians));
        });
        operations.put("asin", (ctx, node) -> {
            Double value = ctx.getInputValue(node, "value", Double.class, 0.0);
            ctx.setOutput(node, "angle_degrees", Math.toDegrees(Math.asin(Math.max(-1.0, Math.min(1.0, value)))));
        });
        operations.put("acos", (ctx, node) -> {
            Double value = ctx.getInputValue(node, "value", Double.class, 0.0);
            ctx.setOutput(node, "angle_degrees", Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, value)))));
        });
        operations.put("atan", (ctx, node) -> {
            Double value = ctx.getInputValue(node, "value", Double.class, 0.0);
            ctx.setOutput(node, "angle_degrees", Math.toDegrees(Math.atan(value)));
        });
        operations.put("atan2", (ctx, node) -> {
            Double y = ctx.getInputValue(node, "y", Double.class, 0.0);
            Double x = ctx.getInputValue(node, "x", Double.class, 0.0);
            ctx.setOutput(node, "angle_degrees", Math.toDegrees(Math.atan2(y, x)));
        });
    }

    public void registerTo(HandlerRegistry registry) {
        registry.register("GenericMathHandler", this);
    }

    @Override
    public void execute(FlowContext ctx, FlowNode node) {
        String operation = node.getHandlerConfig().getString("operation");
        BiConsumer<FlowContext, FlowNode> op = operation != null ? operations.get(operation) : null;
        if (op != null) {
            op.accept(ctx, node);
        } else {
            throw new IllegalArgumentException("Unknown math operation: " + operation);
        }
        ctx.triggerOutput("flow");
    }
}
