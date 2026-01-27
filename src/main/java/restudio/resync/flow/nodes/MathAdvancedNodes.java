package restudio.resync.flow.nodes;

import org.bukkit.util.Vector;
import restudio.flow.data.FlowNode;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.FlowRegistry;
import restudio.resync.flow.NodeCategory;

import java.util.List;
import java.util.Random;

public class MathAdvancedNodes implements NodeCategory {

    private static final Random RANDOM = new Random();

    @Override
    public void registerNodes(FlowRegistry registry) {
        registry.register("math_random_range", (ctx, node) -> {
            Double min = ctx.getInputValue(node, "min", Double.class, 0.0);
            Double max = ctx.getInputValue(node, "max", Double.class, 1.0);
            double result = min + RANDOM.nextDouble() * (max - min);
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "result", result);
        });

        registry.register("math_random_chance", (ctx, node) -> {
            Double chancePercent = ctx.getInputValue(node, "chance_percent", Double.class, 50.0);
            boolean success = RANDOM.nextDouble() * 100 < chancePercent;
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "success", success);
        });

        registry.register("math_random_choice", (ctx, node) -> {
            List<?> itemsList = ctx.getInputValue(node, "items_list", List.class, null);
            Object chosenItem = null;
            if (itemsList != null && !itemsList.isEmpty()) {
                chosenItem = itemsList.get(RANDOM.nextInt(itemsList.size()));
            }
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "chosen_item", chosenItem);
        });

        registry.register("math_random_choice_weighted", (ctx, node) -> {
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
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "chosen_item", chosenItem);
        });

        registry.register("math_vector_create", (ctx, node) -> {
            Double x = ctx.getInputValue(node, "x", Double.class, 0.0);
            Double y = ctx.getInputValue(node, "y", Double.class, 0.0);
            Double z = ctx.getInputValue(node, "z", Double.class, 0.0);
            Vector vector = new Vector(x, y, z);
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "vector", vector);
        });

        registry.register("math_vector_add", (ctx, node) -> {
            Vector vector1 = ctx.getInputValue(node, "vector1", Vector.class, new Vector());
            Vector vector2 = ctx.getInputValue(node, "vector2", Vector.class, new Vector());
            Vector resultVector = vector1.clone().add(vector2);
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "result_vector", resultVector);
        });

        registry.register("math_vector_subtract", (ctx, node) -> {
            Vector vector1 = ctx.getInputValue(node, "vector1", Vector.class, new Vector());
            Vector vector2 = ctx.getInputValue(node, "vector2", Vector.class, new Vector());
            Vector resultVector = vector1.clone().subtract(vector2);
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "result_vector", resultVector);
        });

        registry.register("math_vector_multiply", (ctx, node) -> {
            Vector vector = ctx.getInputValue(node, "vector", Vector.class, new Vector());
            Double scalar = ctx.getInputValue(node, "scalar", Double.class, 1.0);
            Vector resultVector = vector.clone().multiply(scalar);
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "result_vector", resultVector);
        });

        registry.register("math_vector_divide", (ctx, node) -> {
            Vector vector = ctx.getInputValue(node, "vector", Vector.class, new Vector());
            Double scalar = ctx.getInputValue(node, "scalar", Double.class, 1.0);
            Vector resultVector = scalar != 0 ? vector.clone().multiply(1.0 / scalar) : new Vector();
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "result_vector", resultVector);
        });

        registry.register("math_vector_dot", (ctx, node) -> {
            Vector vector1 = ctx.getInputValue(node, "vector1", Vector.class, new Vector());
            Vector vector2 = ctx.getInputValue(node, "vector2", Vector.class, new Vector());
            double result = vector1.dot(vector2);
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "result", result);
        });

        registry.register("math_vector_cross", (ctx, node) -> {
            Vector vector1 = ctx.getInputValue(node, "vector1", Vector.class, new Vector());
            Vector vector2 = ctx.getInputValue(node, "vector2", Vector.class, new Vector());
            Vector resultVector = vector1.clone().crossProduct(vector2);
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "result_vector", resultVector);
        });

        registry.register("math_vector_distance", (ctx, node) -> {
            Vector vector1 = ctx.getInputValue(node, "vector1", Vector.class, new Vector());
            Vector vector2 = ctx.getInputValue(node, "vector2", Vector.class, new Vector());
            double distance = vector1.distance(vector2);
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "distance", distance);
        });

        registry.register("math_vector_length", (ctx, node) -> {
            Vector vector = ctx.getInputValue(node, "vector", Vector.class, new Vector());
            double length = vector.length();
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "length", length);
        });

        registry.register("math_vector_normalize", (ctx, node) -> {
            Vector vector = ctx.getInputValue(node, "vector", Vector.class, new Vector());
            Vector normalizedVector = vector.clone().normalize();
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "normalized_vector", normalizedVector);
        });

        registry.register("math_vector_angle_between", (ctx, node) -> {
            Vector vector1 = ctx.getInputValue(node, "vector1", Vector.class, new Vector());
            Vector vector2 = ctx.getInputValue(node, "vector2", Vector.class, new Vector());
            double dot = vector1.clone().normalize().dot(vector2.clone().normalize());
            double angle = Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, dot))));
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "angle", angle);
        });

        registry.register("math_vector_midpoint", (ctx, node) -> {
            Vector vector1 = ctx.getInputValue(node, "vector1", Vector.class, new Vector());
            Vector vector2 = ctx.getInputValue(node, "vector2", Vector.class, new Vector());
            Vector midpointVector = vector1.clone().add(vector2).multiply(0.5);
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "midpoint_vector", midpointVector);
        });

        registry.register("math_vector_rotate_x", (ctx, node) -> {
            Vector vector = ctx.getInputValue(node, "vector", Vector.class, new Vector());
            Double degrees = ctx.getInputValue(node, "degrees", Double.class, 0.0);
            double radians = Math.toRadians(degrees);
            double cos = Math.cos(radians);
            double sin = Math.sin(radians);
            double y = vector.getY() * cos - vector.getZ() * sin;
            double z = vector.getY() * sin + vector.getZ() * cos;
            Vector rotatedVector = new Vector(vector.getX(), y, z);
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "rotated_vector", rotatedVector);
        });

        registry.register("math_vector_rotate_y", (ctx, node) -> {
            Vector vector = ctx.getInputValue(node, "vector", Vector.class, new Vector());
            Double degrees = ctx.getInputValue(node, "degrees", Double.class, 0.0);
            double radians = Math.toRadians(degrees);
            double cos = Math.cos(radians);
            double sin = Math.sin(radians);
            double x = vector.getX() * cos + vector.getZ() * sin;
            double z = -vector.getX() * sin + vector.getZ() * cos;
            Vector rotatedVector = new Vector(x, vector.getY(), z);
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "rotated_vector", rotatedVector);
        });

        registry.register("math_vector_rotate_z", (ctx, node) -> {
            Vector vector = ctx.getInputValue(node, "vector", Vector.class, new Vector());
            Double degrees = ctx.getInputValue(node, "degrees", Double.class, 0.0);
            double radians = Math.toRadians(degrees);
            double cos = Math.cos(radians);
            double sin = Math.sin(radians);
            double x = vector.getX() * cos - vector.getY() * sin;
            double y = vector.getX() * sin + vector.getY() * cos;
            Vector rotatedVector = new Vector(x, y, vector.getZ());
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "rotated_vector", rotatedVector);
        });

        registry.register("math_lerp", (ctx, node) -> {
            Double a = ctx.getInputValue(node, "a", Double.class, 0.0);
            Double b = ctx.getInputValue(node, "b", Double.class, 0.0);
            Double t = ctx.getInputValue(node, "t", Double.class, 0.5);
            double result = a + (b - a) * Math.max(0.0, Math.min(1.0, t));
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "result", result);
        });

        registry.register("math_clamp", (ctx, node) -> {
            Double value = ctx.getInputValue(node, "value", Double.class, 0.0);
            Double min = ctx.getInputValue(node, "min", Double.class, 0.0);
            Double max = ctx.getInputValue(node, "max", Double.class, 1.0);
            double clamped = Math.max(min, Math.min(max, value));
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "clamped", clamped);
        });

        registry.register("math_abs", (ctx, node) -> {
            Double value = ctx.getInputValue(node, "value", Double.class, 0.0);
            double absolute = Math.abs(value);
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "absolute", absolute);
        });

        registry.register("math_min", (ctx, node) -> {
            List<Double> valuesList = ctx.getInputValue(node, "values_list", List.class, null);
            double min = valuesList != null && !valuesList.isEmpty() ? valuesList.stream().mapToDouble(d -> d).min().orElse(0.0) : 0.0;
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "min", min);
        });

        registry.register("math_max", (ctx, node) -> {
            List<Double> valuesList = ctx.getInputValue(node, "values_list", List.class, null);
            double max = valuesList != null && !valuesList.isEmpty() ? valuesList.stream().mapToDouble(d -> d).max().orElse(0.0) : 0.0;
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "max", max);
        });

        registry.register("math_round", (ctx, node) -> {
            Double value = ctx.getInputValue(node, "value", Double.class, 0.0);
            Integer decimalPlaces = ctx.getInputValue(node, "decimal_places", Integer.class, 0);
            double factor = Math.pow(10, decimalPlaces);
            double rounded = Math.round(value * factor) / factor;
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "rounded", rounded);
        });

        registry.register("math_floor", (ctx, node) -> {
            Double value = ctx.getInputValue(node, "value", Double.class, 0.0);
            double floored = Math.floor(value);
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "floored", floored);
        });

        registry.register("math_ceil", (ctx, node) -> {
            Double value = ctx.getInputValue(node, "value", Double.class, 0.0);
            double ceiling = Math.ceil(value);
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "ceiling", ceiling);
        });

        registry.register("math_hypotenuse", (ctx, node) -> {
            Double a = ctx.getInputValue(node, "a", Double.class, 0.0);
            Double b = ctx.getInputValue(node, "b", Double.class, 0.0);
            double hypotenuse = Math.hypot(a, b);
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "hypotenuse", hypotenuse);
        });

        registry.register("math_log", (ctx, node) -> {
            Double value = ctx.getInputValue(node, "value", Double.class, 1.0);
            double log = Math.log(value);
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "log", log);
        });

        registry.register("math_log10", (ctx, node) -> {
            Double value = ctx.getInputValue(node, "value", Double.class, 1.0);
            double log10 = Math.log10(value);
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "log10", log10);
        });

        registry.register("math_sqrt", (ctx, node) -> {
            Double value = ctx.getInputValue(node, "value", Double.class, 0.0);
            double sqrt = Math.sqrt(Math.max(0.0, value));
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "sqrt", sqrt);
        });

        registry.register("math_cbrt", (ctx, node) -> {
            Double value = ctx.getInputValue(node, "value", Double.class, 0.0);
            double cbrt = Math.cbrt(value);
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "cbrt", cbrt);
        });

        registry.register("math_pow", (ctx, node) -> {
            Double base = ctx.getInputValue(node, "base", Double.class, 0.0);
            Double exponent = ctx.getInputValue(node, "exponent", Double.class, 1.0);
            double result = Math.pow(base, exponent);
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "result", result);
        });

        registry.register("math_signum", (ctx, node) -> {
            Double value = ctx.getInputValue(node, "value", Double.class, 0.0);
            double sign = Math.signum(value);
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "sign", sign);
        });

        registry.register("math_to_radians", (ctx, node) -> {
            Double degrees = ctx.getInputValue(node, "degrees", Double.class, 0.0);
            double radians = Math.toRadians(degrees);
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "radians", radians);
        });

        registry.register("math_to_degrees", (ctx, node) -> {
            Double radians = ctx.getInputValue(node, "radians", Double.class, 0.0);
            double degrees = Math.toDegrees(radians);
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "degrees", degrees);
        });

        registry.register("math_sin", (ctx, node) -> {
            Double angleDegrees = ctx.getInputValue(node, "angle_degrees", Double.class, 0.0);
            double sin = Math.sin(Math.toRadians(angleDegrees));
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "sin", sin);
        });

        registry.register("math_cos", (ctx, node) -> {
            Double angleDegrees = ctx.getInputValue(node, "angle_degrees", Double.class, 0.0);
            double cos = Math.cos(Math.toRadians(angleDegrees));
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "cos", cos);
        });

        registry.register("math_tan", (ctx, node) -> {
            Double angleDegrees = ctx.getInputValue(node, "angle_degrees", Double.class, 0.0);
            double tan = Math.tan(Math.toRadians(angleDegrees));
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "tan", tan);
        });

        registry.register("math_asin", (ctx, node) -> {
            Double value = ctx.getInputValue(node, "value", Double.class, 0.0);
            double angleDegrees = Math.toDegrees(Math.asin(Math.max(-1.0, Math.min(1.0, value))));
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "angle_degrees", angleDegrees);
        });

        registry.register("math_acos", (ctx, node) -> {
            Double value = ctx.getInputValue(node, "value", Double.class, 0.0);
            double angleDegrees = Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, value))));
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "angle_degrees", angleDegrees);
        });

        registry.register("math_atan", (ctx, node) -> {
            Double value = ctx.getInputValue(node, "value", Double.class, 0.0);
            double angleDegrees = Math.toDegrees(Math.atan(value));
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "angle_degrees", angleDegrees);
        });

        registry.register("math_atan2", (ctx, node) -> {
            Double y = ctx.getInputValue(node, "y", Double.class, 0.0);
            Double x = ctx.getInputValue(node, "x", Double.class, 0.0);
            double angleDegrees = Math.toDegrees(Math.atan2(y, x));
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "angle_degrees", angleDegrees);
        });
    }

    private static String findNodeId(FlowContext ctx, FlowNode node) {
        for (var entry : ctx.getRuntime().getGraph().getNodes().entrySet()) {
            if (entry.getValue() == node) {
                return entry.getKey();
            }
        }
        return null;
    }
}
