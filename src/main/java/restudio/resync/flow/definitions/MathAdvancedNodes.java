package restudio.resync.flow.definitions;

import restudio.flow.data.FlowType;
import restudio.resync.flow.registry.NodeDefinition;
import restudio.resync.flow.registry.NodeDefinitionRegistry;

public class MathAdvancedNodes implements NodeDefinitionCategory {
    @Override
    public void registerNodes(NodeDefinitionRegistry registry) {
        registry.register(new NodeDefinition.Builder("math_random_range", "Random Range", NodeDefinition.NodeCategory.UTILITY)
            .input("min", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .input("max", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("result", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .build());

        registry.register(new NodeDefinition.Builder("math_random_chance", "Random Chance", NodeDefinition.NodeCategory.LOGIC)
            .input("chance_percent", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("success", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .build());

        registry.register(new NodeDefinition.Builder("math_random_choice", "Random Choice", NodeDefinition.NodeCategory.UTILITY)
            .input("items_list", NodeDefinition.PinType.DATA, FlowType.LIST)
            .output("chosen_item", NodeDefinition.PinType.DATA, FlowType.ANY)
            .build());

        registry.register(new NodeDefinition.Builder("math_random_choice_weighted", "Random Choice Weighted", NodeDefinition.NodeCategory.UTILITY)
            .input("items_list", NodeDefinition.PinType.DATA, FlowType.LIST)
            .input("weights_list", NodeDefinition.PinType.DATA, FlowType.LIST)
            .output("chosen_item", NodeDefinition.PinType.DATA, FlowType.ANY)
            .build());

        registry.register(new NodeDefinition.Builder("math_vector_create", "Vector Create", NodeDefinition.NodeCategory.DATA)
            .input("x", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .input("y", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .input("z", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("vector", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .build());

        registry.register(new NodeDefinition.Builder("math_vector_add", "Vector Add", NodeDefinition.NodeCategory.DATA)
            .input("vector1", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .input("vector2", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .output("result_vector", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .build());

        registry.register(new NodeDefinition.Builder("math_vector_subtract", "Vector Subtract", NodeDefinition.NodeCategory.DATA)
            .input("vector1", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .input("vector2", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .output("result_vector", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .build());

        registry.register(new NodeDefinition.Builder("math_vector_multiply", "Vector Multiply", NodeDefinition.NodeCategory.DATA)
            .input("vector", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .input("scalar", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("result_vector", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .build());

        registry.register(new NodeDefinition.Builder("math_vector_divide", "Vector Divide", NodeDefinition.NodeCategory.DATA)
            .input("vector", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .input("scalar", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("result_vector", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .build());

        registry.register(new NodeDefinition.Builder("math_vector_dot", "Vector Dot", NodeDefinition.NodeCategory.DATA)
            .input("vector1", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .input("vector2", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .output("result", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .build());

        registry.register(new NodeDefinition.Builder("math_vector_cross", "Vector Cross", NodeDefinition.NodeCategory.DATA)
            .input("vector1", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .input("vector2", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .output("result_vector", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .build());

        registry.register(new NodeDefinition.Builder("math_vector_distance", "Vector Distance", NodeDefinition.NodeCategory.DATA)
            .input("vector1", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .input("vector2", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .output("distance", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .build());

        registry.register(new NodeDefinition.Builder("math_vector_length", "Vector Length", NodeDefinition.NodeCategory.DATA)
            .input("vector", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .output("length", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .build());

        registry.register(new NodeDefinition.Builder("math_vector_normalize", "Vector Normalize", NodeDefinition.NodeCategory.DATA)
            .input("vector", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .output("normalized_vector", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .build());

        registry.register(new NodeDefinition.Builder("math_vector_angle_between", "Vector Angle Between", NodeDefinition.NodeCategory.DATA)
            .input("vector1", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .input("vector2", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .output("angle", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .build());

        registry.register(new NodeDefinition.Builder("math_vector_midpoint", "Vector Midpoint", NodeDefinition.NodeCategory.DATA)
            .input("vector1", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .input("vector2", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .output("midpoint_vector", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .build());

        registry.register(new NodeDefinition.Builder("math_vector_rotate_x", "Vector Rotate X", NodeDefinition.NodeCategory.DATA)
            .input("vector", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .input("degrees", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("rotated_vector", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .build());

        registry.register(new NodeDefinition.Builder("math_vector_rotate_y", "Vector Rotate Y", NodeDefinition.NodeCategory.DATA)
            .input("vector", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .input("degrees", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("rotated_vector", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .build());

        registry.register(new NodeDefinition.Builder("math_vector_rotate_z", "Vector Rotate Z", NodeDefinition.NodeCategory.DATA)
            .input("vector", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .input("degrees", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("rotated_vector", NodeDefinition.PinType.DATA, FlowType.LOCATION)
            .build());

        registry.register(new NodeDefinition.Builder("math_lerp", "Lerp", NodeDefinition.NodeCategory.DATA)
            .input("a", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .input("b", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .input("t", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("result", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .build());

        registry.register(new NodeDefinition.Builder("math_clamp", "Clamp", NodeDefinition.NodeCategory.DATA)
            .input("value", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .input("min", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .input("max", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("clamped", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .build());

        registry.register(new NodeDefinition.Builder("math_abs", "Absolute", NodeDefinition.NodeCategory.DATA)
            .input("value", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("absolute", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .build());

        registry.register(new NodeDefinition.Builder("math_min", "Min", NodeDefinition.NodeCategory.DATA)
            .input("values_list", NodeDefinition.PinType.DATA, FlowType.LIST)
            .output("min", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .build());

        registry.register(new NodeDefinition.Builder("math_max", "Max", NodeDefinition.NodeCategory.DATA)
            .input("values_list", NodeDefinition.PinType.DATA, FlowType.LIST)
            .output("max", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .build());

        registry.register(new NodeDefinition.Builder("math_round", "Round", NodeDefinition.NodeCategory.DATA)
            .input("value", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .input("decimal_places", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("rounded", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .build());

        registry.register(new NodeDefinition.Builder("math_floor", "Floor", NodeDefinition.NodeCategory.DATA)
            .input("value", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("floored", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .build());

        registry.register(new NodeDefinition.Builder("math_ceil", "Ceil", NodeDefinition.NodeCategory.DATA)
            .input("value", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("ceiling", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .build());

        registry.register(new NodeDefinition.Builder("math_hypotenuse", "Hypotenuse", NodeDefinition.NodeCategory.DATA)
            .input("a", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .input("b", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("hypotenuse", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .build());

        registry.register(new NodeDefinition.Builder("math_log", "Log", NodeDefinition.NodeCategory.DATA)
            .input("value", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("log", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .build());

        registry.register(new NodeDefinition.Builder("math_log10", "Log10", NodeDefinition.NodeCategory.DATA)
            .input("value", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("log10", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .build());

        registry.register(new NodeDefinition.Builder("math_sqrt", "Sqrt", NodeDefinition.NodeCategory.DATA)
            .input("value", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("sqrt", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .build());

        registry.register(new NodeDefinition.Builder("math_cbrt", "Cube Root", NodeDefinition.NodeCategory.DATA)
            .input("value", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("cbrt", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .build());

        registry.register(new NodeDefinition.Builder("math_pow", "Power", NodeDefinition.NodeCategory.DATA)
            .input("base", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .input("exponent", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("result", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .build());

        registry.register(new NodeDefinition.Builder("math_signum", "Signum", NodeDefinition.NodeCategory.DATA)
            .input("value", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("sign", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .build());

        registry.register(new NodeDefinition.Builder("math_to_radians", "To Radians", NodeDefinition.NodeCategory.DATA)
            .input("degrees", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("radians", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .build());

        registry.register(new NodeDefinition.Builder("math_to_degrees", "To Degrees", NodeDefinition.NodeCategory.DATA)
            .input("radians", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("degrees", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .build());

        registry.register(new NodeDefinition.Builder("math_sin", "Sin", NodeDefinition.NodeCategory.DATA)
            .input("angle_degrees", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("sin", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .build());

        registry.register(new NodeDefinition.Builder("math_cos", "Cos", NodeDefinition.NodeCategory.DATA)
            .input("angle_degrees", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("cos", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .build());

        registry.register(new NodeDefinition.Builder("math_tan", "Tan", NodeDefinition.NodeCategory.DATA)
            .input("angle_degrees", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("tan", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .build());

        registry.register(new NodeDefinition.Builder("math_asin", "Arc Sin", NodeDefinition.NodeCategory.DATA)
            .input("value", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("angle_degrees", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .build());

        registry.register(new NodeDefinition.Builder("math_acos", "Arc Cos", NodeDefinition.NodeCategory.DATA)
            .input("value", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("angle_degrees", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .build());

        registry.register(new NodeDefinition.Builder("math_atan", "Arc Tan", NodeDefinition.NodeCategory.DATA)
            .input("value", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("angle_degrees", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .build());

        registry.register(new NodeDefinition.Builder("math_atan2", "Arc Tan2", NodeDefinition.NodeCategory.DATA)
            .input("y", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .input("x", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("angle_degrees", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .build());
    }
}
