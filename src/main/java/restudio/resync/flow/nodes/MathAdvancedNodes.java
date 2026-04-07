package restudio.resync.flow.nodes;

import org.bukkit.util.Vector;
import restudio.flow.data.FlowNode;
import restudio.flow.data.FlowType;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.FlowRegistry;
import restudio.resync.flow.registry.DefineNode;
import restudio.resync.flow.registry.FlowPin;
import restudio.resync.flow.registry.NodeDefinition;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

public class MathAdvancedNodes {

    private static final Random RANDOM = new Random();
    private static final Map<String, BiConsumer<FlowContext, FlowNode>> LEGACY_EXECUTORS = new ConcurrentHashMap<>();
    private static volatile boolean initialized;

    private static void registerLegacyNodes(FlowRegistry registry) {
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

    public void registerNodes(FlowRegistry registry) {
        registerLegacyNodes(registry);
    }

    private static void ensureLegacyInitialized() {
        if (initialized) {
            return;
        }
        synchronized (MathAdvancedNodes.class) {
            if (initialized) {
                return;
            }
            FlowRegistry legacyRegistry = new FlowRegistry();
            registerLegacyNodes(legacyRegistry);
            for (String type : legacyRegistry.getRegisteredTypes()) {
                LEGACY_EXECUTORS.put(type, legacyRegistry.getExecutor(type));
            }
            initialized = true;
        }
    }

    private void executeLegacy(String id, FlowContext ctx, FlowNode node) {
        ensureLegacyInitialized();
        BiConsumer<FlowContext, FlowNode> executor = LEGACY_EXECUTORS.get(id);
        if (executor == null) {
            return;
        }
        executor.accept(ctx, node);
    }

    @DefineNode(id = "math_random_range", displayName = "Random Range", category = NodeDefinition.NodeCategory.UTILITY,
            inputs = {
                    @FlowPin(name = "min", type = NodeDefinition.PinType.DATA, dataType = FlowType.NUMBER),
                    @FlowPin(name = "max", type = NodeDefinition.PinType.DATA, dataType = FlowType.NUMBER)
            },
            outputs = {
                    @FlowPin(name = "result", type = NodeDefinition.PinType.DATA, dataType = FlowType.NUMBER)
            })
    public void nmath_random_range(FlowContext ctx, FlowNode node) {
        executeLegacy("math_random_range", ctx, node);
    }

    @DefineNode(id = "math_random_chance", displayName = "Random Chance", category = NodeDefinition.NodeCategory.LOGIC,
            inputs = {
                    @FlowPin(name = "chance_percent", type = NodeDefinition.PinType.DATA, dataType = FlowType.NUMBER)
            },
            outputs = {
                    @FlowPin(name = "success", type = NodeDefinition.PinType.DATA, dataType = FlowType.BOOLEAN)
            })
    public void nmath_random_chance(FlowContext ctx, FlowNode node) {
        executeLegacy("math_random_chance", ctx, node);
    }

    @DefineNode(id = "math_random_choice", displayName = "Random Choice", category = NodeDefinition.NodeCategory.UTILITY,
            inputs = {
                    @FlowPin(name = "items_list", type = NodeDefinition.PinType.DATA, dataType = FlowType.LIST)
            },
            outputs = {
                    @FlowPin(name = "chosen_item", type = NodeDefinition.PinType.DATA, dataType = FlowType.ANY)
            })
    public void nmath_random_choice(FlowContext ctx, FlowNode node) {
        executeLegacy("math_random_choice", ctx, node);
    }

    @DefineNode(id = "math_random_choice_weighted", displayName = "Random Choice Weighted", category = NodeDefinition.NodeCategory.UTILITY,
            inputs = {
                    @FlowPin(name = "items_list", type = NodeDefinition.PinType.DATA, dataType = FlowType.LIST),
                    @FlowPin(name = "weights_list", type = NodeDefinition.PinType.DATA, dataType = FlowType.LIST)
            },
            outputs = {
                    @FlowPin(name = "chosen_item", type = NodeDefinition.PinType.DATA, dataType = FlowType.ANY)
            })
    public void nmath_random_choice_weighted(FlowContext ctx, FlowNode node) {
        executeLegacy("math_random_choice_weighted", ctx, node);
    }

    @DefineNode(id = "math_vector_create", displayName = "Vector Create", category = NodeDefinition.NodeCategory.DATA,
            inputs = {
                    @FlowPin(name = "x", type = NodeDefinition.PinType.DATA, dataType = FlowType.NUMBER),
                    @FlowPin(name = "y", type = NodeDefinition.PinType.DATA, dataType = FlowType.NUMBER),
                    @FlowPin(name = "z", type = NodeDefinition.PinType.DATA, dataType = FlowType.NUMBER)
            },
            outputs = {
                    @FlowPin(name = "vector", type = NodeDefinition.PinType.DATA, dataType = FlowType.LOCATION)
            })
    public void nmath_vector_create(FlowContext ctx, FlowNode node) {
        executeLegacy("math_vector_create", ctx, node);
    }

    @DefineNode(id = "math_vector_add", displayName = "Vector Add", category = NodeDefinition.NodeCategory.DATA,
            inputs = {
                    @FlowPin(name = "vector1", type = NodeDefinition.PinType.DATA, dataType = FlowType.LOCATION),
                    @FlowPin(name = "vector2", type = NodeDefinition.PinType.DATA, dataType = FlowType.LOCATION)
            },
            outputs = {
                    @FlowPin(name = "result_vector", type = NodeDefinition.PinType.DATA, dataType = FlowType.LOCATION)
            })
    public void nmath_vector_add(FlowContext ctx, FlowNode node) {
        executeLegacy("math_vector_add", ctx, node);
    }

    @DefineNode(id = "math_vector_subtract", displayName = "Vector Subtract", category = NodeDefinition.NodeCategory.DATA,
            inputs = {
                    @FlowPin(name = "vector1", type = NodeDefinition.PinType.DATA, dataType = FlowType.LOCATION),
                    @FlowPin(name = "vector2", type = NodeDefinition.PinType.DATA, dataType = FlowType.LOCATION)
            },
            outputs = {
                    @FlowPin(name = "result_vector", type = NodeDefinition.PinType.DATA, dataType = FlowType.LOCATION)
            })
    public void nmath_vector_subtract(FlowContext ctx, FlowNode node) {
        executeLegacy("math_vector_subtract", ctx, node);
    }

    @DefineNode(id = "math_vector_multiply", displayName = "Vector Multiply", category = NodeDefinition.NodeCategory.DATA,
            inputs = {
                    @FlowPin(name = "vector", type = NodeDefinition.PinType.DATA, dataType = FlowType.LOCATION),
                    @FlowPin(name = "scalar", type = NodeDefinition.PinType.DATA, dataType = FlowType.NUMBER)
            },
            outputs = {
                    @FlowPin(name = "result_vector", type = NodeDefinition.PinType.DATA, dataType = FlowType.LOCATION)
            })
    public void nmath_vector_multiply(FlowContext ctx, FlowNode node) {
        executeLegacy("math_vector_multiply", ctx, node);
    }

    @DefineNode(id = "math_vector_divide", displayName = "Vector Divide", category = NodeDefinition.NodeCategory.DATA,
            inputs = {
                    @FlowPin(name = "vector", type = NodeDefinition.PinType.DATA, dataType = FlowType.LOCATION),
                    @FlowPin(name = "scalar", type = NodeDefinition.PinType.DATA, dataType = FlowType.NUMBER)
            },
            outputs = {
                    @FlowPin(name = "result_vector", type = NodeDefinition.PinType.DATA, dataType = FlowType.LOCATION)
            })
    public void nmath_vector_divide(FlowContext ctx, FlowNode node) {
        executeLegacy("math_vector_divide", ctx, node);
    }

    @DefineNode(id = "math_vector_dot", displayName = "Vector Dot", category = NodeDefinition.NodeCategory.DATA,
            inputs = {
                    @FlowPin(name = "vector1", type = NodeDefinition.PinType.DATA, dataType = FlowType.LOCATION),
                    @FlowPin(name = "vector2", type = NodeDefinition.PinType.DATA, dataType = FlowType.LOCATION)
            },
            outputs = {
                    @FlowPin(name = "result", type = NodeDefinition.PinType.DATA, dataType = FlowType.NUMBER)
            })
    public void nmath_vector_dot(FlowContext ctx, FlowNode node) {
        executeLegacy("math_vector_dot", ctx, node);
    }

    @DefineNode(id = "math_vector_cross", displayName = "Vector Cross", category = NodeDefinition.NodeCategory.DATA,
            inputs = {
                    @FlowPin(name = "vector1", type = NodeDefinition.PinType.DATA, dataType = FlowType.LOCATION),
                    @FlowPin(name = "vector2", type = NodeDefinition.PinType.DATA, dataType = FlowType.LOCATION)
            },
            outputs = {
                    @FlowPin(name = "result_vector", type = NodeDefinition.PinType.DATA, dataType = FlowType.LOCATION)
            })
    public void nmath_vector_cross(FlowContext ctx, FlowNode node) {
        executeLegacy("math_vector_cross", ctx, node);
    }

    @DefineNode(id = "math_vector_distance", displayName = "Vector Distance", category = NodeDefinition.NodeCategory.DATA,
            inputs = {
                    @FlowPin(name = "vector1", type = NodeDefinition.PinType.DATA, dataType = FlowType.LOCATION),
                    @FlowPin(name = "vector2", type = NodeDefinition.PinType.DATA, dataType = FlowType.LOCATION)
            },
            outputs = {
                    @FlowPin(name = "distance", type = NodeDefinition.PinType.DATA, dataType = FlowType.NUMBER)
            })
    public void nmath_vector_distance(FlowContext ctx, FlowNode node) {
        executeLegacy("math_vector_distance", ctx, node);
    }

    @DefineNode(id = "math_vector_length", displayName = "Vector Length", category = NodeDefinition.NodeCategory.DATA,
            inputs = {
                    @FlowPin(name = "vector", type = NodeDefinition.PinType.DATA, dataType = FlowType.LOCATION)
            },
            outputs = {
                    @FlowPin(name = "length", type = NodeDefinition.PinType.DATA, dataType = FlowType.NUMBER)
            })
    public void nmath_vector_length(FlowContext ctx, FlowNode node) {
        executeLegacy("math_vector_length", ctx, node);
    }

    @DefineNode(id = "math_vector_normalize", displayName = "Vector Normalize", category = NodeDefinition.NodeCategory.DATA,
            inputs = {
                    @FlowPin(name = "vector", type = NodeDefinition.PinType.DATA, dataType = FlowType.LOCATION)
            },
            outputs = {
                    @FlowPin(name = "normalized_vector", type = NodeDefinition.PinType.DATA, dataType = FlowType.LOCATION)
            })
    public void nmath_vector_normalize(FlowContext ctx, FlowNode node) {
        executeLegacy("math_vector_normalize", ctx, node);
    }

    @DefineNode(id = "math_vector_angle_between", displayName = "Vector Angle Between", category = NodeDefinition.NodeCategory.DATA,
            inputs = {
                    @FlowPin(name = "vector1", type = NodeDefinition.PinType.DATA, dataType = FlowType.LOCATION),
                    @FlowPin(name = "vector2", type = NodeDefinition.PinType.DATA, dataType = FlowType.LOCATION)
            },
            outputs = {
                    @FlowPin(name = "angle", type = NodeDefinition.PinType.DATA, dataType = FlowType.NUMBER)
            })
    public void nmath_vector_angle_between(FlowContext ctx, FlowNode node) {
        executeLegacy("math_vector_angle_between", ctx, node);
    }

    @DefineNode(id = "math_vector_midpoint", displayName = "Vector Midpoint", category = NodeDefinition.NodeCategory.DATA,
            inputs = {
                    @FlowPin(name = "vector1", type = NodeDefinition.PinType.DATA, dataType = FlowType.LOCATION),
                    @FlowPin(name = "vector2", type = NodeDefinition.PinType.DATA, dataType = FlowType.LOCATION)
            },
            outputs = {
                    @FlowPin(name = "midpoint_vector", type = NodeDefinition.PinType.DATA, dataType = FlowType.LOCATION)
            })
    public void nmath_vector_midpoint(FlowContext ctx, FlowNode node) {
        executeLegacy("math_vector_midpoint", ctx, node);
    }

    @DefineNode(id = "math_vector_rotate_x", displayName = "Vector Rotate X", category = NodeDefinition.NodeCategory.DATA,
            inputs = {
                    @FlowPin(name = "vector", type = NodeDefinition.PinType.DATA, dataType = FlowType.LOCATION),
                    @FlowPin(name = "degrees", type = NodeDefinition.PinType.DATA, dataType = FlowType.NUMBER)
            },
            outputs = {
                    @FlowPin(name = "rotated_vector", type = NodeDefinition.PinType.DATA, dataType = FlowType.LOCATION)
            })
    public void nmath_vector_rotate_x(FlowContext ctx, FlowNode node) {
        executeLegacy("math_vector_rotate_x", ctx, node);
    }

    @DefineNode(id = "math_vector_rotate_y", displayName = "Vector Rotate Y", category = NodeDefinition.NodeCategory.DATA,
            inputs = {
                    @FlowPin(name = "vector", type = NodeDefinition.PinType.DATA, dataType = FlowType.LOCATION),
                    @FlowPin(name = "degrees", type = NodeDefinition.PinType.DATA, dataType = FlowType.NUMBER)
            },
            outputs = {
                    @FlowPin(name = "rotated_vector", type = NodeDefinition.PinType.DATA, dataType = FlowType.LOCATION)
            })
    public void nmath_vector_rotate_y(FlowContext ctx, FlowNode node) {
        executeLegacy("math_vector_rotate_y", ctx, node);
    }

    @DefineNode(id = "math_vector_rotate_z", displayName = "Vector Rotate Z", category = NodeDefinition.NodeCategory.DATA,
            inputs = {
                    @FlowPin(name = "vector", type = NodeDefinition.PinType.DATA, dataType = FlowType.LOCATION),
                    @FlowPin(name = "degrees", type = NodeDefinition.PinType.DATA, dataType = FlowType.NUMBER)
            },
            outputs = {
                    @FlowPin(name = "rotated_vector", type = NodeDefinition.PinType.DATA, dataType = FlowType.LOCATION)
            })
    public void nmath_vector_rotate_z(FlowContext ctx, FlowNode node) {
        executeLegacy("math_vector_rotate_z", ctx, node);
    }

    @DefineNode(id = "math_lerp", displayName = "Lerp", category = NodeDefinition.NodeCategory.DATA,
            inputs = {
                    @FlowPin(name = "a", type = NodeDefinition.PinType.DATA, dataType = FlowType.NUMBER),
                    @FlowPin(name = "b", type = NodeDefinition.PinType.DATA, dataType = FlowType.NUMBER),
                    @FlowPin(name = "t", type = NodeDefinition.PinType.DATA, dataType = FlowType.NUMBER)
            },
            outputs = {
                    @FlowPin(name = "result", type = NodeDefinition.PinType.DATA, dataType = FlowType.NUMBER)
            })
    public void nmath_lerp(FlowContext ctx, FlowNode node) {
        executeLegacy("math_lerp", ctx, node);
    }

    @DefineNode(id = "math_clamp", displayName = "Clamp", category = NodeDefinition.NodeCategory.DATA,
            inputs = {
                    @FlowPin(name = "value", type = NodeDefinition.PinType.DATA, dataType = FlowType.NUMBER),
                    @FlowPin(name = "min", type = NodeDefinition.PinType.DATA, dataType = FlowType.NUMBER),
                    @FlowPin(name = "max", type = NodeDefinition.PinType.DATA, dataType = FlowType.NUMBER)
            },
            outputs = {
                    @FlowPin(name = "clamped", type = NodeDefinition.PinType.DATA, dataType = FlowType.NUMBER)
            })
    public void nmath_clamp(FlowContext ctx, FlowNode node) {
        executeLegacy("math_clamp", ctx, node);
    }

    @DefineNode(id = "math_abs", displayName = "Absolute", category = NodeDefinition.NodeCategory.DATA,
            inputs = {
                    @FlowPin(name = "value", type = NodeDefinition.PinType.DATA, dataType = FlowType.NUMBER)
            },
            outputs = {
                    @FlowPin(name = "absolute", type = NodeDefinition.PinType.DATA, dataType = FlowType.NUMBER)
            })
    public void nmath_abs(FlowContext ctx, FlowNode node) {
        executeLegacy("math_abs", ctx, node);
    }

    @DefineNode(id = "math_min", displayName = "Min", category = NodeDefinition.NodeCategory.DATA,
            inputs = {
                    @FlowPin(name = "values_list", type = NodeDefinition.PinType.DATA, dataType = FlowType.LIST)
            },
            outputs = {
                    @FlowPin(name = "min", type = NodeDefinition.PinType.DATA, dataType = FlowType.NUMBER)
            })
    public void nmath_min(FlowContext ctx, FlowNode node) {
        executeLegacy("math_min", ctx, node);
    }

    @DefineNode(id = "math_max", displayName = "Max", category = NodeDefinition.NodeCategory.DATA,
            inputs = {
                    @FlowPin(name = "values_list", type = NodeDefinition.PinType.DATA, dataType = FlowType.LIST)
            },
            outputs = {
                    @FlowPin(name = "max", type = NodeDefinition.PinType.DATA, dataType = FlowType.NUMBER)
            })
    public void nmath_max(FlowContext ctx, FlowNode node) {
        executeLegacy("math_max", ctx, node);
    }

    @DefineNode(id = "math_round", displayName = "Round", category = NodeDefinition.NodeCategory.DATA,
            inputs = {
                    @FlowPin(name = "value", type = NodeDefinition.PinType.DATA, dataType = FlowType.NUMBER),
                    @FlowPin(name = "decimal_places", type = NodeDefinition.PinType.DATA, dataType = FlowType.NUMBER)
            },
            outputs = {
                    @FlowPin(name = "rounded", type = NodeDefinition.PinType.DATA, dataType = FlowType.NUMBER)
            })
    public void nmath_round(FlowContext ctx, FlowNode node) {
        executeLegacy("math_round", ctx, node);
    }

    @DefineNode(id = "math_floor", displayName = "Floor", category = NodeDefinition.NodeCategory.DATA,
            inputs = {
                    @FlowPin(name = "value", type = NodeDefinition.PinType.DATA, dataType = FlowType.NUMBER)
            },
            outputs = {
                    @FlowPin(name = "floored", type = NodeDefinition.PinType.DATA, dataType = FlowType.NUMBER)
            })
    public void nmath_floor(FlowContext ctx, FlowNode node) {
        executeLegacy("math_floor", ctx, node);
    }

    @DefineNode(id = "math_ceil", displayName = "Ceil", category = NodeDefinition.NodeCategory.DATA,
            inputs = {
                    @FlowPin(name = "value", type = NodeDefinition.PinType.DATA, dataType = FlowType.NUMBER)
            },
            outputs = {
                    @FlowPin(name = "ceiling", type = NodeDefinition.PinType.DATA, dataType = FlowType.NUMBER)
            })
    public void nmath_ceil(FlowContext ctx, FlowNode node) {
        executeLegacy("math_ceil", ctx, node);
    }

    @DefineNode(id = "math_hypotenuse", displayName = "Hypotenuse", category = NodeDefinition.NodeCategory.DATA,
            inputs = {
                    @FlowPin(name = "a", type = NodeDefinition.PinType.DATA, dataType = FlowType.NUMBER),
                    @FlowPin(name = "b", type = NodeDefinition.PinType.DATA, dataType = FlowType.NUMBER)
            },
            outputs = {
                    @FlowPin(name = "hypotenuse", type = NodeDefinition.PinType.DATA, dataType = FlowType.NUMBER)
            })
    public void nmath_hypotenuse(FlowContext ctx, FlowNode node) {
        executeLegacy("math_hypotenuse", ctx, node);
    }

    @DefineNode(id = "math_log", displayName = "Log", category = NodeDefinition.NodeCategory.DATA,
            inputs = {
                    @FlowPin(name = "value", type = NodeDefinition.PinType.DATA, dataType = FlowType.NUMBER)
            },
            outputs = {
                    @FlowPin(name = "log", type = NodeDefinition.PinType.DATA, dataType = FlowType.NUMBER)
            })
    public void nmath_log(FlowContext ctx, FlowNode node) {
        executeLegacy("math_log", ctx, node);
    }

    @DefineNode(id = "math_log10", displayName = "Log10", category = NodeDefinition.NodeCategory.DATA,
            inputs = {
                    @FlowPin(name = "value", type = NodeDefinition.PinType.DATA, dataType = FlowType.NUMBER)
            },
            outputs = {
                    @FlowPin(name = "log10", type = NodeDefinition.PinType.DATA, dataType = FlowType.NUMBER)
            })
    public void nmath_log10(FlowContext ctx, FlowNode node) {
        executeLegacy("math_log10", ctx, node);
    }

    @DefineNode(id = "math_sqrt", displayName = "Sqrt", category = NodeDefinition.NodeCategory.DATA,
            inputs = {
                    @FlowPin(name = "value", type = NodeDefinition.PinType.DATA, dataType = FlowType.NUMBER)
            },
            outputs = {
                    @FlowPin(name = "sqrt", type = NodeDefinition.PinType.DATA, dataType = FlowType.NUMBER)
            })
    public void nmath_sqrt(FlowContext ctx, FlowNode node) {
        executeLegacy("math_sqrt", ctx, node);
    }

    @DefineNode(id = "math_cbrt", displayName = "Cube Root", category = NodeDefinition.NodeCategory.DATA,
            inputs = {
                    @FlowPin(name = "value", type = NodeDefinition.PinType.DATA, dataType = FlowType.NUMBER)
            },
            outputs = {
                    @FlowPin(name = "cbrt", type = NodeDefinition.PinType.DATA, dataType = FlowType.NUMBER)
            })
    public void nmath_cbrt(FlowContext ctx, FlowNode node) {
        executeLegacy("math_cbrt", ctx, node);
    }

    @DefineNode(id = "math_pow", displayName = "Power", category = NodeDefinition.NodeCategory.DATA,
            inputs = {
                    @FlowPin(name = "base", type = NodeDefinition.PinType.DATA, dataType = FlowType.NUMBER),
                    @FlowPin(name = "exponent", type = NodeDefinition.PinType.DATA, dataType = FlowType.NUMBER)
            },
            outputs = {
                    @FlowPin(name = "result", type = NodeDefinition.PinType.DATA, dataType = FlowType.NUMBER)
            })
    public void nmath_pow(FlowContext ctx, FlowNode node) {
        executeLegacy("math_pow", ctx, node);
    }

    @DefineNode(id = "math_signum", displayName = "Signum", category = NodeDefinition.NodeCategory.DATA,
            inputs = {
                    @FlowPin(name = "value", type = NodeDefinition.PinType.DATA, dataType = FlowType.NUMBER)
            },
            outputs = {
                    @FlowPin(name = "sign", type = NodeDefinition.PinType.DATA, dataType = FlowType.NUMBER)
            })
    public void nmath_signum(FlowContext ctx, FlowNode node) {
        executeLegacy("math_signum", ctx, node);
    }

    @DefineNode(id = "math_to_radians", displayName = "To Radians", category = NodeDefinition.NodeCategory.DATA,
            inputs = {
                    @FlowPin(name = "degrees", type = NodeDefinition.PinType.DATA, dataType = FlowType.NUMBER)
            },
            outputs = {
                    @FlowPin(name = "radians", type = NodeDefinition.PinType.DATA, dataType = FlowType.NUMBER)
            })
    public void nmath_to_radians(FlowContext ctx, FlowNode node) {
        executeLegacy("math_to_radians", ctx, node);
    }

    @DefineNode(id = "math_to_degrees", displayName = "To Degrees", category = NodeDefinition.NodeCategory.DATA,
            inputs = {
                    @FlowPin(name = "radians", type = NodeDefinition.PinType.DATA, dataType = FlowType.NUMBER)
            },
            outputs = {
                    @FlowPin(name = "degrees", type = NodeDefinition.PinType.DATA, dataType = FlowType.NUMBER)
            })
    public void nmath_to_degrees(FlowContext ctx, FlowNode node) {
        executeLegacy("math_to_degrees", ctx, node);
    }

    @DefineNode(id = "math_sin", displayName = "Sin", category = NodeDefinition.NodeCategory.DATA,
            inputs = {
                    @FlowPin(name = "angle_degrees", type = NodeDefinition.PinType.DATA, dataType = FlowType.NUMBER)
            },
            outputs = {
                    @FlowPin(name = "sin", type = NodeDefinition.PinType.DATA, dataType = FlowType.NUMBER)
            })
    public void nmath_sin(FlowContext ctx, FlowNode node) {
        executeLegacy("math_sin", ctx, node);
    }

    @DefineNode(id = "math_cos", displayName = "Cos", category = NodeDefinition.NodeCategory.DATA,
            inputs = {
                    @FlowPin(name = "angle_degrees", type = NodeDefinition.PinType.DATA, dataType = FlowType.NUMBER)
            },
            outputs = {
                    @FlowPin(name = "cos", type = NodeDefinition.PinType.DATA, dataType = FlowType.NUMBER)
            })
    public void nmath_cos(FlowContext ctx, FlowNode node) {
        executeLegacy("math_cos", ctx, node);
    }

    @DefineNode(id = "math_tan", displayName = "Tan", category = NodeDefinition.NodeCategory.DATA,
            inputs = {
                    @FlowPin(name = "angle_degrees", type = NodeDefinition.PinType.DATA, dataType = FlowType.NUMBER)
            },
            outputs = {
                    @FlowPin(name = "tan", type = NodeDefinition.PinType.DATA, dataType = FlowType.NUMBER)
            })
    public void nmath_tan(FlowContext ctx, FlowNode node) {
        executeLegacy("math_tan", ctx, node);
    }

    @DefineNode(id = "math_asin", displayName = "Arc Sin", category = NodeDefinition.NodeCategory.DATA,
            inputs = {
                    @FlowPin(name = "value", type = NodeDefinition.PinType.DATA, dataType = FlowType.NUMBER)
            },
            outputs = {
                    @FlowPin(name = "angle_degrees", type = NodeDefinition.PinType.DATA, dataType = FlowType.NUMBER)
            })
    public void nmath_asin(FlowContext ctx, FlowNode node) {
        executeLegacy("math_asin", ctx, node);
    }

    @DefineNode(id = "math_acos", displayName = "Arc Cos", category = NodeDefinition.NodeCategory.DATA,
            inputs = {
                    @FlowPin(name = "value", type = NodeDefinition.PinType.DATA, dataType = FlowType.NUMBER)
            },
            outputs = {
                    @FlowPin(name = "angle_degrees", type = NodeDefinition.PinType.DATA, dataType = FlowType.NUMBER)
            })
    public void nmath_acos(FlowContext ctx, FlowNode node) {
        executeLegacy("math_acos", ctx, node);
    }

    @DefineNode(id = "math_atan", displayName = "Arc Tan", category = NodeDefinition.NodeCategory.DATA,
            inputs = {
                    @FlowPin(name = "value", type = NodeDefinition.PinType.DATA, dataType = FlowType.NUMBER)
            },
            outputs = {
                    @FlowPin(name = "angle_degrees", type = NodeDefinition.PinType.DATA, dataType = FlowType.NUMBER)
            })
    public void nmath_atan(FlowContext ctx, FlowNode node) {
        executeLegacy("math_atan", ctx, node);
    }

    @DefineNode(id = "math_atan2", displayName = "Arc Tan2", category = NodeDefinition.NodeCategory.DATA,
            inputs = {
                    @FlowPin(name = "y", type = NodeDefinition.PinType.DATA, dataType = FlowType.NUMBER),
                    @FlowPin(name = "x", type = NodeDefinition.PinType.DATA, dataType = FlowType.NUMBER)
            },
            outputs = {
                    @FlowPin(name = "angle_degrees", type = NodeDefinition.PinType.DATA, dataType = FlowType.NUMBER)
            })
    public void nmath_atan2(FlowContext ctx, FlowNode node) {
        executeLegacy("math_atan2", ctx, node);
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
