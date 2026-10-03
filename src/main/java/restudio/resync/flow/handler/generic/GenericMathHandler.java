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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

public class GenericMathHandler implements NodeHandler {

    private static final Set<String> DATA_ONLY_OPERATIONS = Set.of(
        "abs", "floor", "ceil", "sqrt", "cbrt", "signum", "to_radians", "to_degrees",
        "add", "subtract", "multiply", "negate", "hypotenuse", "sin", "cos", "tan", "atan",
        "clamp", "lerp", "round", "asin", "acos", "atan2", "distance", "min", "max",
        "log", "log10", "pow", "power", "round_decimal", "divide", "modulo");
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
            double a = finiteScalar(ctx, node, "a", 0.0, "Add input a");
            double b = finiteScalar(ctx, node, "b", 0.0, "Add input b");
            ctx.setOutput(node, "result", finiteResult(a + b, "Add result"));
        });
        operations.put("subtract", (ctx, node) -> {
            double a = finiteScalar(ctx, node, "a", 0.0, "Subtract input a");
            double b = finiteScalar(ctx, node, "b", 0.0, "Subtract input b");
            ctx.setOutput(node, "result", finiteResult(a - b, "Subtract result"));
        });
        operations.put("multiply", (ctx, node) -> {
            double a = finiteScalar(ctx, node, "a", 0.0, "Multiply input a");
            double b = finiteScalar(ctx, node, "b", 0.0, "Multiply input b");
            ctx.setOutput(node, "result", finiteResult(a * b, "Multiply result"));
        });
        operations.put("divide", (ctx, node) -> {
            double a = finiteScalar(ctx, node, "a", 0.0, "Divide input a");
            double b = finiteScalar(ctx, node, "b", 1.0, "Divide input b");
            ctx.setOutput(node, "result", finiteResult(b != 0 ? a / b : 0.0, "Divide result"));
        });
        operations.put("modulo", (ctx, node) -> {
            double a = finiteScalar(ctx, node, "a", 0.0, "Modulo input a");
            double b = finiteScalar(ctx, node, "b", 1.0, "Modulo input b");
            ctx.setOutput(node, "result", finiteResult(b != 0 ? a % b : 0.0, "Modulo result"));
        });
        operations.put("power", (ctx, node) -> {
            double base = finiteScalar(ctx, node, "base", 0.0, "Power base");
            double exponent = finiteScalar(ctx, node, "exponent", 0.0, "Power exponent");
            ctx.setOutput(node, "result", finiteResult(Math.pow(base, exponent), "Power result"));
        });
        operations.put("sqrt", (ctx, node) -> {
            double value = finiteScalar(ctx, node, "value", 0.0, "Square root input");
            if (value < 0) {
                throw new IllegalArgumentException("Square root input must be a finite non-negative number");
            }
            ctx.setOutput(node, "sqrt", Math.sqrt(value));
        });
        operations.put("abs", (ctx, node) -> {
            double value = finiteScalar(ctx, node, "value", 0.0, "Absolute input");
            ctx.setOutput(node, "absolute", Math.abs(value));
        });
        operations.put("floor", (ctx, node) -> {
            double value = finiteScalar(ctx, node, "value", 0.0, "Floor input");
            ctx.setOutput(node, "floored", Math.floor(value));
        });
        operations.put("ceil", (ctx, node) -> {
            double value = finiteScalar(ctx, node, "value", 0.0, "Ceiling input");
            ctx.setOutput(node, "ceiling", Math.ceil(value));
        });
        operations.put("round", (ctx, node) -> {
            double value = finiteScalar(ctx, node, "value", 0.0, "Round input");
            Integer decimalPlaces = ctx.getInputValue(node, "decimal_places", Integer.class, 0);
            ctx.setOutput(node, "rounded", roundFinite(value, decimalPlaces));
        });
        operations.put("min", (ctx, node) -> {
            List<?> values = ctx.getInputValue(node, "values_list", List.class, List.of());
            ctx.setOutput(node, "min", finiteListExtremum(values, true, "Minimum"));
        });
        operations.put("max", (ctx, node) -> {
            List<?> values = ctx.getInputValue(node, "values_list", List.class, List.of());
            ctx.setOutput(node, "max", finiteListExtremum(values, false, "Maximum"));
        });
        operations.put("clamp", (ctx, node) -> {
            double value = finiteScalar(ctx, node, "value", 0.0, "Clamp value");
            double min = finiteScalar(ctx, node, "min", 0.0, "Clamp minimum");
            double max = finiteScalar(ctx, node, "max", 1.0, "Clamp maximum");
            ctx.setOutput(node, "clamped", Math.max(min, Math.min(max, value)));
        });
        operations.put("random", (ctx, node) -> {
            double min = finiteScalar(ctx, node, "min", 0.0, "Random minimum");
            double max = finiteScalar(ctx, node, "max", 1.0, "Random maximum");
            ctx.setOutput(node, "result", interpolate(min, max, RANDOM.nextDouble()));
        });
        operations.put("negate", (ctx, node) -> {
            double value = finiteScalar(ctx, node, "value", 0.0, "Negate input");
            ctx.setOutput(node, "result", -value);
        });
        operations.put("distance", (ctx, node) -> {
            double x1 = finiteScalar(ctx, node, "x1", 0.0, "Distance input x1");
            double y1 = finiteScalar(ctx, node, "y1", 0.0, "Distance input y1");
            double z1 = finiteScalar(ctx, node, "z1", 0.0, "Distance input z1");
            double x2 = finiteScalar(ctx, node, "x2", 0.0, "Distance input x2");
            double y2 = finiteScalar(ctx, node, "y2", 0.0, "Distance input y2");
            double z2 = finiteScalar(ctx, node, "z2", 0.0, "Distance input z2");
            double dx = x2 - x1, dy = y2 - y1, dz = z2 - z1;
            ctx.setOutput(node, "result", finiteResult(Math.hypot(Math.hypot(dx, dy), dz), "Distance result"));
        });
    }

    private void registerAdvancedOperations() {
        operations.put("random_range", (ctx, node) -> {
            double min = finiteScalar(ctx, node, "min", 0.0, "Random minimum");
            double max = finiteScalar(ctx, node, "max", 1.0, "Random maximum");
            ctx.setOutput(node, "result", interpolate(min, max, RANDOM.nextDouble()));
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
            List<?> weightsList = ctx.getInputValue(node, "weights_list", List.class, null);
            Object chosenItem = null;
            if (itemsList != null && !itemsList.isEmpty() && weightsList != null) {
                if (weightsList.size() != itemsList.size()) {
                    throw new IllegalArgumentException("Each item requires one weight");
                }
                double[] weights = new double[weightsList.size()];
                double maximum = 0.0;
                for (int i = 0; i < weights.length; i++) {
                    Object value = weightsList.get(i);
                    if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue()) || number.doubleValue() < 0) {
                        throw new IllegalArgumentException("Weights must be finite non-negative numbers");
                    }
                    weights[i] = number.doubleValue();
                    maximum = Math.max(maximum, weights[i]);
                }
                if (maximum > 0) {
                    double total = 0.0;
                    for (int i = 0; i < weights.length; i++) {
                        weights[i] /= maximum;
                        total += weights[i];
                    }
                    double sample = RANDOM.nextDouble() * total;
                    for (int i = 0; i < weights.length; i++) {
                        if (weights[i] > 0) {
                            chosenItem = itemsList.get(i);
                            sample -= weights[i];
                            if (sample < 0) {
                                break;
                            }
                        }
                    }
                }
            }
            ctx.setOutput(node, "chosen_item", chosenItem);
        });
        operations.put("lerp", (ctx, node) -> {
            double a = finiteScalar(ctx, node, "a", 0.0, "Lerp input a");
            double b = finiteScalar(ctx, node, "b", 0.0, "Lerp input b");
            double t = finiteScalar(ctx, node, "t", 0.5, "Lerp input t");
            ctx.setOutput(node, "result", interpolate(a, b, Math.clamp(t, 0.0, 1.0)));
        });
        operations.put("hypotenuse", (ctx, node) -> {
            double a = finiteScalar(ctx, node, "a", 0.0, "Hypotenuse input a");
            double b = finiteScalar(ctx, node, "b", 0.0, "Hypotenuse input b");
            ctx.setOutput(node, "hypotenuse", finiteResult(Math.hypot(a, b), "Hypotenuse result"));
        });
        operations.put("log", (ctx, node) -> {
            double value = finiteScalar(ctx, node, "value", 1.0, "Log input");
            if (value <= 0) {
                throw new IllegalArgumentException("Log input must be greater than zero");
            }
            ctx.setOutput(node, "log", Math.log(value));
        });
        operations.put("log10", (ctx, node) -> {
            double value = finiteScalar(ctx, node, "value", 1.0, "Log10 input");
            if (value <= 0) {
                throw new IllegalArgumentException("Log10 input must be greater than zero");
            }
            ctx.setOutput(node, "log10", Math.log10(value));
        });
        operations.put("cbrt", (ctx, node) -> {
            double value = finiteScalar(ctx, node, "value", 0.0, "Cube root input");
            ctx.setOutput(node, "cbrt", Math.cbrt(value));
        });
        operations.put("pow", (ctx, node) -> {
            double base = finiteScalar(ctx, node, "base", 0.0, "Pow base");
            double exponent = finiteScalar(ctx, node, "exponent", 1.0, "Pow exponent");
            ctx.setOutput(node, "result", finiteResult(Math.pow(base, exponent), "Pow result"));
        });
        operations.put("signum", (ctx, node) -> {
            double value = finiteScalar(ctx, node, "value", 0.0, "Signum input");
            ctx.setOutput(node, "sign", Math.signum(value));
        });
        operations.put("min_list", (ctx, node) -> {
            List<?> valuesList = ctx.getInputValue(node, "values_list", List.class, null);
            double min = finiteListExtremum(valuesList, true, "Minimum");
            ctx.setOutput(node, "min", min);
        });
        operations.put("max_list", (ctx, node) -> {
            List<?> valuesList = ctx.getInputValue(node, "values_list", List.class, null);
            double max = finiteListExtremum(valuesList, false, "Maximum");
            ctx.setOutput(node, "max", max);
        });
        operations.put("round_decimal", (ctx, node) -> {
            double value = finiteScalar(ctx, node, "value", 0.0, "Round decimal input");
            Integer decimalPlaces = ctx.getInputValue(node, "decimal_places", Integer.class, 0);
            ctx.setOutput(node, "rounded", roundFinite(value, decimalPlaces));
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

    private double finiteScalar(FlowContext ctx, FlowNode node, String pinName, double defaultValue, String label) {
        Double value = ctx.getInputValue(node, pinName, Double.class, defaultValue);
        if (value == null || !Double.isFinite(value)) {
            throw new IllegalArgumentException(label + " must be finite");
        }
        return value;
    }

    private double roundFinite(double value, int places) {
        double factor = Math.pow(10.0, Math.clamp(places, -15, 15));
        double scaled = value * factor;
        return Math.abs(scaled) >= 0x1.0p52 ? value : finiteResult(Math.round(scaled) / factor, "Round result");
    }

    private double interpolate(double first, double second, double fraction) {
        double result = (1.0 - fraction) * first + fraction * second;
        return Math.clamp(result, Math.min(first, second), Math.max(first, second));
    }

    private double finiteResult(double value, String label) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(label + " must be finite");
        }
        return value;
    }

    private double finiteListExtremum(List<?> values, boolean minimum, String label) {
        double extremum = minimum ? Double.POSITIVE_INFINITY : Double.NEGATIVE_INFINITY;
        boolean found = false;
        if (values != null) {
            for (Object value : values) {
                if (value instanceof Number number) {
                    double numericValue = number.doubleValue();
                    if (!Double.isFinite(numericValue)) {
                        throw new IllegalArgumentException(label + " values must be finite");
                    }
                    extremum = minimum ? Math.min(extremum, numericValue) : Math.max(extremum, numericValue);
                    found = true;
                }
            }
        }
        return found ? extremum : 0.0;
    }

    private void registerTrigOperations() {
        operations.put("sin", (ctx, node) -> {
            double angle = finiteScalar(ctx, node, "angle_degrees", 0.0, "Sine angle");
            ctx.setOutput(node, "sin", Math.sin(Math.toRadians(angle)));
        });
        operations.put("cos", (ctx, node) -> {
            double angle = finiteScalar(ctx, node, "angle_degrees", 0.0, "Cosine angle");
            ctx.setOutput(node, "cos", Math.cos(Math.toRadians(angle)));
        });
        operations.put("tan", (ctx, node) -> {
            double angle = finiteScalar(ctx, node, "angle_degrees", 0.0, "Tangent angle");
            ctx.setOutput(node, "tan", Math.tan(Math.toRadians(angle)));
        });
        operations.put("to_radians", (ctx, node) -> {
            double degrees = finiteScalar(ctx, node, "degrees", 0.0, "Degrees input");
            ctx.setOutput(node, "radians", Math.toRadians(degrees));
        });
        operations.put("to_degrees", (ctx, node) -> {
            double radians = finiteScalar(ctx, node, "radians", 0.0, "Radians input");
            ctx.setOutput(node, "degrees", Math.toDegrees(radians));
        });
        operations.put("asin", (ctx, node) -> {
            double value = finiteScalar(ctx, node, "value", 0.0, "Asin input");
            ctx.setOutput(node, "angle_degrees", Math.toDegrees(Math.asin(Math.max(-1.0, Math.min(1.0, value)))));
        });
        operations.put("acos", (ctx, node) -> {
            double value = finiteScalar(ctx, node, "value", 0.0, "Acos input");
            ctx.setOutput(node, "angle_degrees", Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, value)))));
        });
        operations.put("atan", (ctx, node) -> {
            double value = finiteScalar(ctx, node, "value", 0.0, "Arctangent input");
            ctx.setOutput(node, "angle_degrees", Math.toDegrees(Math.atan(value)));
        });
        operations.put("atan2", (ctx, node) -> {
            double y = finiteScalar(ctx, node, "y", 0.0, "Atan2 input y");
            double x = finiteScalar(ctx, node, "x", 0.0, "Atan2 input x");
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
        if (!DATA_ONLY_OPERATIONS.contains(operation)) {
            ctx.triggerOutput("flow");
        }
    }
}
