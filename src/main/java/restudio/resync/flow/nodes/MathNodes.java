package restudio.resync.flow.nodes;

import restudio.flow.data.FlowNode;
import restudio.flow.data.FlowType;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.registry.FlowPin;
import restudio.resync.flow.registry.DefineNode;
import restudio.resync.flow.registry.NodeDefinition;

public class MathNodes {

    @DefineNode(id = "math_add", displayName = "Add", category = NodeDefinition.NodeCategory.LOGIC,
            inputs = {@FlowPin(name = "a", dataType = FlowType.NUMBER), @FlowPin(name = "b", dataType = FlowType.NUMBER)},
            outputs = {@FlowPin(name = "result", dataType = FlowType.NUMBER)})
    public void add(FlowContext ctx, FlowNode node) {
        Double a = ctx.getInputValue(node, "a", Double.class, 0.0);
        Double b = ctx.getInputValue(node, "b", Double.class, 0.0);
        ctx.setOutput(node, "result", a + b);
    }

    @DefineNode(id = "math_subtract", displayName = "Subtract", category = NodeDefinition.NodeCategory.LOGIC,
            inputs = {@FlowPin(name = "a", dataType = FlowType.NUMBER), @FlowPin(name = "b", dataType = FlowType.NUMBER)},
            outputs = {@FlowPin(name = "result", dataType = FlowType.NUMBER)})
    public void subtract(FlowContext ctx, FlowNode node) {
        Double a = ctx.getInputValue(node, "a", Double.class, 0.0);
        Double b = ctx.getInputValue(node, "b", Double.class, 0.0);
        ctx.setOutput(node, "result", a - b);
    }

    @DefineNode(id = "math_multiply", displayName = "Multiply", category = NodeDefinition.NodeCategory.LOGIC,
            inputs = {@FlowPin(name = "a", dataType = FlowType.NUMBER), @FlowPin(name = "b", dataType = FlowType.NUMBER)},
            outputs = {@FlowPin(name = "result", dataType = FlowType.NUMBER)})
    public void multiply(FlowContext ctx, FlowNode node) {
        Double a = ctx.getInputValue(node, "a", Double.class, 0.0);
        Double b = ctx.getInputValue(node, "b", Double.class, 0.0);
        ctx.setOutput(node, "result", a * b);
    }

    @DefineNode(id = "math_divide", displayName = "Divide", category = NodeDefinition.NodeCategory.LOGIC,
            inputs = {@FlowPin(name = "a", dataType = FlowType.NUMBER), @FlowPin(name = "b", dataType = FlowType.NUMBER)},
            outputs = {@FlowPin(name = "result", dataType = FlowType.NUMBER)})
    public void divide(FlowContext ctx, FlowNode node) {
        Double a = ctx.getInputValue(node, "a", Double.class, 0.0);
        Double b = ctx.getInputValue(node, "b", Double.class, 1.0);
        ctx.setOutput(node, "result", b != 0 ? a / b : 0.0);
    }

    @DefineNode(id = "math_modulo", displayName = "Modulo", category = NodeDefinition.NodeCategory.LOGIC,
            inputs = {@FlowPin(name = "a", dataType = FlowType.NUMBER), @FlowPin(name = "b", dataType = FlowType.NUMBER)},
            outputs = {@FlowPin(name = "result", dataType = FlowType.NUMBER)})
    public void modulo(FlowContext ctx, FlowNode node) {
        Double a = ctx.getInputValue(node, "a", Double.class, 0.0);
        Double b = ctx.getInputValue(node, "b", Double.class, 1.0);
        ctx.setOutput(node, "result", b != 0 ? a % b : 0.0);
    }

    @DefineNode(id = "math_power", displayName = "Power", category = NodeDefinition.NodeCategory.LOGIC,
            inputs = {@FlowPin(name = "base", dataType = FlowType.NUMBER), @FlowPin(name = "exponent", dataType = FlowType.NUMBER)},
            outputs = {@FlowPin(name = "result", dataType = FlowType.NUMBER)})
    public void power(FlowContext ctx, FlowNode node) {
        Double base = ctx.getInputValue(node, "base", Double.class, 0.0);
        Double exponent = ctx.getInputValue(node, "exponent", Double.class, 0.0);
        ctx.setOutput(node, "result", Math.pow(base, exponent));
    }

    @DefineNode(id = "math_sqrt", displayName = "Square Root", category = NodeDefinition.NodeCategory.LOGIC,
            inputs = {@FlowPin(name = "value", dataType = FlowType.NUMBER)},
            outputs = {@FlowPin(name = "result", dataType = FlowType.NUMBER)})
    public void sqrt(FlowContext ctx, FlowNode node) {
        Double value = ctx.getInputValue(node, "value", Double.class, 0.0);
        ctx.setOutput(node, "result", Math.sqrt(value));
    }

    @DefineNode(id = "math_abs", displayName = "Absolute Value", category = NodeDefinition.NodeCategory.LOGIC,
            inputs = {@FlowPin(name = "value", dataType = FlowType.NUMBER)},
            outputs = {@FlowPin(name = "result", dataType = FlowType.NUMBER)})
    public void abs(FlowContext ctx, FlowNode node) {
        Double value = ctx.getInputValue(node, "value", Double.class, 0.0);
        ctx.setOutput(node, "result", Math.abs(value));
    }

    @DefineNode(id = "math_floor", displayName = "Floor", category = NodeDefinition.NodeCategory.LOGIC,
            inputs = {@FlowPin(name = "value", dataType = FlowType.NUMBER)},
            outputs = {@FlowPin(name = "result", dataType = FlowType.NUMBER)})
    public void floor(FlowContext ctx, FlowNode node) {
        Double value = ctx.getInputValue(node, "value", Double.class, 0.0);
        ctx.setOutput(node, "result", Math.floor(value));
    }

    @DefineNode(id = "math_ceil", displayName = "Ceiling", category = NodeDefinition.NodeCategory.LOGIC,
            inputs = {@FlowPin(name = "value", dataType = FlowType.NUMBER)},
            outputs = {@FlowPin(name = "result", dataType = FlowType.NUMBER)})
    public void ceil(FlowContext ctx, FlowNode node) {
        Double value = ctx.getInputValue(node, "value", Double.class, 0.0);
        ctx.setOutput(node, "result", Math.ceil(value));
    }

    @DefineNode(id = "math_round", displayName = "Round", category = NodeDefinition.NodeCategory.LOGIC,
            inputs = {@FlowPin(name = "value", dataType = FlowType.NUMBER)},
            outputs = {@FlowPin(name = "result", dataType = FlowType.NUMBER)})
    public void round(FlowContext ctx, FlowNode node) {
        Double value = ctx.getInputValue(node, "value", Double.class, 0.0);
        ctx.setOutput(node, "result", (double) Math.round(value));
    }

    @DefineNode(id = "math_min", displayName = "Min", category = NodeDefinition.NodeCategory.LOGIC,
            inputs = {@FlowPin(name = "a", dataType = FlowType.NUMBER), @FlowPin(name = "b", dataType = FlowType.NUMBER)},
            outputs = {@FlowPin(name = "result", dataType = FlowType.NUMBER)})
    public void min(FlowContext ctx, FlowNode node) {
        Double a = ctx.getInputValue(node, "a", Double.class, 0.0);
        Double b = ctx.getInputValue(node, "b", Double.class, 0.0);
        ctx.setOutput(node, "result", Math.min(a, b));
    }

    @DefineNode(id = "math_max", displayName = "Max", category = NodeDefinition.NodeCategory.LOGIC,
            inputs = {@FlowPin(name = "a", dataType = FlowType.NUMBER), @FlowPin(name = "b", dataType = FlowType.NUMBER)},
            outputs = {@FlowPin(name = "result", dataType = FlowType.NUMBER)})
    public void max(FlowContext ctx, FlowNode node) {
        Double a = ctx.getInputValue(node, "a", Double.class, 0.0);
        Double b = ctx.getInputValue(node, "b", Double.class, 0.0);
        ctx.setOutput(node, "result", Math.max(a, b));
    }

    @DefineNode(id = "math_clamp", displayName = "Clamp", category = NodeDefinition.NodeCategory.LOGIC,
            inputs = {@FlowPin(name = "value", dataType = FlowType.NUMBER), @FlowPin(name = "min", dataType = FlowType.NUMBER), @FlowPin(name = "max", dataType = FlowType.NUMBER)},
            outputs = {@FlowPin(name = "result", dataType = FlowType.NUMBER)})
    public void clamp(FlowContext ctx, FlowNode node) {
        Double value = ctx.getInputValue(node, "value", Double.class, 0.0);
        Double min = ctx.getInputValue(node, "min", Double.class, 0.0);
        Double max = ctx.getInputValue(node, "max", Double.class, 1.0);
        ctx.setOutput(node, "result", Math.max(min, Math.min(max, value)));
    }

    @DefineNode(id = "math_random", displayName = "Random", category = NodeDefinition.NodeCategory.LOGIC,
            inputs = {@FlowPin(name = "min", dataType = FlowType.NUMBER), @FlowPin(name = "max", dataType = FlowType.NUMBER)},
            outputs = {@FlowPin(name = "result", dataType = FlowType.NUMBER)})
    public void random(FlowContext ctx, FlowNode node) {
        Double min = ctx.getInputValue(node, "min", Double.class, 0.0);
        Double max = ctx.getInputValue(node, "max", Double.class, 1.0);
        ctx.setOutput(node, "result", min + Math.random() * (max - min));
    }

    @DefineNode(id = "math_sin", displayName = "Sin", category = NodeDefinition.NodeCategory.LOGIC,
            inputs = {@FlowPin(name = "value", dataType = FlowType.NUMBER)},
            outputs = {@FlowPin(name = "result", dataType = FlowType.NUMBER)})
    public void sin(FlowContext ctx, FlowNode node) {
        Double value = ctx.getInputValue(node, "value", Double.class, 0.0);
        ctx.setOutput(node, "result", Math.sin(value));
    }

    @DefineNode(id = "math_cos", displayName = "Cos", category = NodeDefinition.NodeCategory.LOGIC,
            inputs = {@FlowPin(name = "value", dataType = FlowType.NUMBER)},
            outputs = {@FlowPin(name = "result", dataType = FlowType.NUMBER)})
    public void cos(FlowContext ctx, FlowNode node) {
        Double value = ctx.getInputValue(node, "value", Double.class, 0.0);
        ctx.setOutput(node, "result", Math.cos(value));
    }

    @DefineNode(id = "math_tan", displayName = "Tan", category = NodeDefinition.NodeCategory.LOGIC,
            inputs = {@FlowPin(name = "value", dataType = FlowType.NUMBER)},
            outputs = {@FlowPin(name = "result", dataType = FlowType.NUMBER)})
    public void tan(FlowContext ctx, FlowNode node) {
        Double value = ctx.getInputValue(node, "value", Double.class, 0.0);
        ctx.setOutput(node, "result", Math.tan(value));
    }

    @DefineNode(id = "math_to_radians", displayName = "To Radians", category = NodeDefinition.NodeCategory.LOGIC,
            inputs = {@FlowPin(name = "value", dataType = FlowType.NUMBER)},
            outputs = {@FlowPin(name = "result", dataType = FlowType.NUMBER)})
    public void toRadians(FlowContext ctx, FlowNode node) {
        Double value = ctx.getInputValue(node, "value", Double.class, 0.0);
        ctx.setOutput(node, "result", Math.toRadians(value));
    }

    @DefineNode(id = "math_to_degrees", displayName = "To Degrees", category = NodeDefinition.NodeCategory.LOGIC,
            inputs = {@FlowPin(name = "value", dataType = FlowType.NUMBER)},
            outputs = {@FlowPin(name = "result", dataType = FlowType.NUMBER)})
    public void toDegrees(FlowContext ctx, FlowNode node) {
        Double value = ctx.getInputValue(node, "value", Double.class, 0.0);
        ctx.setOutput(node, "result", Math.toDegrees(value));
    }

    @DefineNode(id = "math_distance", displayName = "Distance", category = NodeDefinition.NodeCategory.LOGIC,
            inputs = {
                    @FlowPin(name = "x1", dataType = FlowType.NUMBER), @FlowPin(name = "y1", dataType = FlowType.NUMBER), @FlowPin(name = "z1", dataType = FlowType.NUMBER),
                    @FlowPin(name = "x2", dataType = FlowType.NUMBER), @FlowPin(name = "y2", dataType = FlowType.NUMBER), @FlowPin(name = "z2", dataType = FlowType.NUMBER)
            },
            outputs = {@FlowPin(name = "result", dataType = FlowType.NUMBER)})
    public void distance(FlowContext ctx, FlowNode node) {
        Double x1 = ctx.getInputValue(node, "x1", Double.class, 0.0);
        Double y1 = ctx.getInputValue(node, "y1", Double.class, 0.0);
        Double z1 = ctx.getInputValue(node, "z1", Double.class, 0.0);
        Double x2 = ctx.getInputValue(node, "x2", Double.class, 0.0);
        Double y2 = ctx.getInputValue(node, "y2", Double.class, 0.0);
        Double z2 = ctx.getInputValue(node, "z2", Double.class, 0.0);
        double dx = x2 - x1, dy = y2 - y1, dz = z2 - z1;
        ctx.setOutput(node, "result", Math.sqrt(dx * dx + dy * dy + dz * dz));
    }

    @DefineNode(id = "math_negate", displayName = "Negate", category = NodeDefinition.NodeCategory.LOGIC,
            inputs = {@FlowPin(name = "value", dataType = FlowType.NUMBER)},
            outputs = {@FlowPin(name = "result", dataType = FlowType.NUMBER)})
    public void negate(FlowContext ctx, FlowNode node) {
        Double value = ctx.getInputValue(node, "value", Double.class, 0.0);
        ctx.setOutput(node, "result", -value);
    }
}
