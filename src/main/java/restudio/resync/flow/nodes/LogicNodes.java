package restudio.resync.flow.nodes;

import restudio.flow.data.FlowType;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.registry.DefineNode;
import restudio.resync.flow.registry.FlowPin;
import restudio.resync.flow.registry.NodeDefinition;

public class LogicNodes {

    @DefineNode(id = "logic_and", displayName = "And", category = NodeDefinition.NodeCategory.LOGIC,
            inputs = {@FlowPin(name = "a", dataType = FlowType.BOOLEAN), @FlowPin(name = "b", dataType = FlowType.BOOLEAN)},
            outputs = {@FlowPin(name = "result", dataType = FlowType.BOOLEAN)})
    public void and(FlowContext ctx, restudio.flow.data.FlowNode node) {
        Boolean a = ctx.getInputValue(node, "a", Boolean.class, false);
        Boolean b = ctx.getInputValue(node, "b", Boolean.class, false);
        ctx.setOutput(node, "result", a && b);
    }

    @DefineNode(id = "logic_or", displayName = "Or", category = NodeDefinition.NodeCategory.LOGIC,
            inputs = {@FlowPin(name = "a", dataType = FlowType.BOOLEAN), @FlowPin(name = "b", dataType = FlowType.BOOLEAN)},
            outputs = {@FlowPin(name = "result", dataType = FlowType.BOOLEAN)})
    public void or(FlowContext ctx, restudio.flow.data.FlowNode node) {
        Boolean a = ctx.getInputValue(node, "a", Boolean.class, false);
        Boolean b = ctx.getInputValue(node, "b", Boolean.class, false);
        ctx.setOutput(node, "result", a || b);
    }

    @DefineNode(id = "logic_not", displayName = "Not", category = NodeDefinition.NodeCategory.LOGIC,
            inputs = {@FlowPin(name = "value", dataType = FlowType.BOOLEAN)},
            outputs = {@FlowPin(name = "result", dataType = FlowType.BOOLEAN)})
    public void not(FlowContext ctx, restudio.flow.data.FlowNode node) {
        Boolean a = ctx.getInputValue(node, "value", Boolean.class, false);
        ctx.setOutput(node, "result", !a);
    }

    @DefineNode(id = "logic_xor", displayName = "Xor", category = NodeDefinition.NodeCategory.LOGIC,
            inputs = {@FlowPin(name = "a", dataType = FlowType.BOOLEAN), @FlowPin(name = "b", dataType = FlowType.BOOLEAN)},
            outputs = {@FlowPin(name = "result", dataType = FlowType.BOOLEAN)})
    public void xor(FlowContext ctx, restudio.flow.data.FlowNode node) {
        Boolean a = ctx.getInputValue(node, "a", Boolean.class, false);
        Boolean b = ctx.getInputValue(node, "b", Boolean.class, false);
        ctx.setOutput(node, "result", a ^ b);
    }

    @DefineNode(id = "logic_nand", displayName = "Nand", category = NodeDefinition.NodeCategory.LOGIC,
            inputs = {@FlowPin(name = "a", dataType = FlowType.BOOLEAN), @FlowPin(name = "b", dataType = FlowType.BOOLEAN)},
            outputs = {@FlowPin(name = "result", dataType = FlowType.BOOLEAN)})
    public void nand(FlowContext ctx, restudio.flow.data.FlowNode node) {
        Boolean a = ctx.getInputValue(node, "a", Boolean.class, false);
        Boolean b = ctx.getInputValue(node, "b", Boolean.class, false);
        ctx.setOutput(node, "result", !(a && b));
    }

    @DefineNode(id = "logic_nor", displayName = "Nor", category = NodeDefinition.NodeCategory.LOGIC,
            inputs = {@FlowPin(name = "a", dataType = FlowType.BOOLEAN), @FlowPin(name = "b", dataType = FlowType.BOOLEAN)},
            outputs = {@FlowPin(name = "result", dataType = FlowType.BOOLEAN)})
    public void nor(FlowContext ctx, restudio.flow.data.FlowNode node) {
        Boolean a = ctx.getInputValue(node, "a", Boolean.class, false);
        Boolean b = ctx.getInputValue(node, "b", Boolean.class, false);
        ctx.setOutput(node, "result", !(a || b));
    }

    @DefineNode(id = "logic_true", displayName = "True", category = NodeDefinition.NodeCategory.LOGIC,
            outputs = {@FlowPin(name = "value", dataType = FlowType.BOOLEAN)})
    public void logicTrue(FlowContext ctx, restudio.flow.data.FlowNode node) {
        ctx.setOutput(node, "value", true);
    }

    @DefineNode(id = "logic_false", displayName = "False", category = NodeDefinition.NodeCategory.LOGIC,
            outputs = {@FlowPin(name = "value", dataType = FlowType.BOOLEAN)})
    public void logicFalse(FlowContext ctx, restudio.flow.data.FlowNode node) {
        ctx.setOutput(node, "value", false);
    }

    @DefineNode(id = "compare_equals", displayName = "Equals", category = NodeDefinition.NodeCategory.LOGIC,
            inputs = {@FlowPin(name = "a", dataType = FlowType.ANY), @FlowPin(name = "b", dataType = FlowType.ANY)},
            outputs = {@FlowPin(name = "result", dataType = FlowType.BOOLEAN)})
    public void equals(FlowContext ctx, restudio.flow.data.FlowNode node) {
        Object a = ctx.getInputValue(node, "a", null);
        Object b = ctx.getInputValue(node, "b", null);
        ctx.setOutput(node, "result", (a != null && a.equals(b)) || (a == null && b == null));
    }

    @DefineNode(id = "compare_not_equals", displayName = "Not Equals", category = NodeDefinition.NodeCategory.LOGIC,
            inputs = {@FlowPin(name = "a", dataType = FlowType.ANY), @FlowPin(name = "b", dataType = FlowType.ANY)},
            outputs = {@FlowPin(name = "result", dataType = FlowType.BOOLEAN)})
    public void notEquals(FlowContext ctx, restudio.flow.data.FlowNode node) {
        Object a = ctx.getInputValue(node, "a", null);
        Object b = ctx.getInputValue(node, "b", null);
        ctx.setOutput(node, "result", !((a != null && a.equals(b)) || (a == null && b == null)));
    }

    @DefineNode(id = "compare_greater", displayName = "Greater", category = NodeDefinition.NodeCategory.LOGIC,
            inputs = {@FlowPin(name = "a", dataType = FlowType.NUMBER), @FlowPin(name = "b", dataType = FlowType.NUMBER)},
            outputs = {@FlowPin(name = "result", dataType = FlowType.BOOLEAN)})
    public void greater(FlowContext ctx, restudio.flow.data.FlowNode node) {
        Double a = ctx.getInputValue(node, "a", Double.class, 0.0);
        Double b = ctx.getInputValue(node, "b", Double.class, 0.0);
        ctx.setOutput(node, "result", a > b);
    }

    @DefineNode(id = "compare_less", displayName = "Less", category = NodeDefinition.NodeCategory.LOGIC,
            inputs = {@FlowPin(name = "a", dataType = FlowType.NUMBER), @FlowPin(name = "b", dataType = FlowType.NUMBER)},
            outputs = {@FlowPin(name = "result", dataType = FlowType.BOOLEAN)})
    public void less(FlowContext ctx, restudio.flow.data.FlowNode node) {
        Double a = ctx.getInputValue(node, "a", Double.class, 0.0);
        Double b = ctx.getInputValue(node, "b", Double.class, 0.0);
        ctx.setOutput(node, "result", a < b);
    }

    @DefineNode(id = "compare_greater_or_equal", displayName = "Greater Or Equal", category = NodeDefinition.NodeCategory.LOGIC,
            inputs = {@FlowPin(name = "a", dataType = FlowType.NUMBER), @FlowPin(name = "b", dataType = FlowType.NUMBER)},
            outputs = {@FlowPin(name = "result", dataType = FlowType.BOOLEAN)})
    public void greaterOrEqual(FlowContext ctx, restudio.flow.data.FlowNode node) {
        Double a = ctx.getInputValue(node, "a", Double.class, 0.0);
        Double b = ctx.getInputValue(node, "b", Double.class, 0.0);
        ctx.setOutput(node, "result", a >= b);
    }

    @DefineNode(id = "compare_less_or_equal", displayName = "Less Or Equal", category = NodeDefinition.NodeCategory.LOGIC,
            inputs = {@FlowPin(name = "a", dataType = FlowType.NUMBER), @FlowPin(name = "b", dataType = FlowType.NUMBER)},
            outputs = {@FlowPin(name = "result", dataType = FlowType.BOOLEAN)})
    public void lessOrEqual(FlowContext ctx, restudio.flow.data.FlowNode node) {
        Double a = ctx.getInputValue(node, "a", Double.class, 0.0);
        Double b = ctx.getInputValue(node, "b", Double.class, 0.0);
        ctx.setOutput(node, "result", a <= b);
    }

    @DefineNode(id = "compare_between", displayName = "Between", category = NodeDefinition.NodeCategory.LOGIC,
            inputs = {@FlowPin(name = "value", dataType = FlowType.NUMBER), @FlowPin(name = "min", dataType = FlowType.NUMBER), @FlowPin(name = "max", dataType = FlowType.NUMBER)},
            outputs = {@FlowPin(name = "result", dataType = FlowType.BOOLEAN)})
    public void between(FlowContext ctx, restudio.flow.data.FlowNode node) {
        Double value = ctx.getInputValue(node, "value", Double.class, 0.0);
        Double min = ctx.getInputValue(node, "min", Double.class, 0.0);
        Double max = ctx.getInputValue(node, "max", Double.class, 0.0);
        ctx.setOutput(node, "result", value >= min && value <= max);
    }

    @DefineNode(id = "compare_type", displayName = "Compare Type", category = NodeDefinition.NodeCategory.LOGIC,
            inputs = {@FlowPin(name = "value", dataType = FlowType.ANY), @FlowPin(name = "type", dataType = FlowType.STRING)},
            outputs = {@FlowPin(name = "result", dataType = FlowType.BOOLEAN)})
    public void compareType(FlowContext ctx, restudio.flow.data.FlowNode node) {
        Object value = ctx.getInputValue(node, "value", null);
        String typeName = ctx.getInputValue(node, "type", String.class, "");
        boolean isType = false;
        if (value == null) {
            isType = typeName.equalsIgnoreCase("null");
        } else {
            Class<?> valueClass = value.getClass();
            String lowerType = typeName.toLowerCase();
            if (lowerType.equals("string")) {
                isType = valueClass == String.class;
            } else if (lowerType.equals("number") || lowerType.equals("double") || lowerType.equals("int") || lowerType.equals("float") || lowerType.equals("long")) {
                isType = value instanceof Number;
            } else if (lowerType.equals("boolean") || lowerType.equals("bool")) {
                isType = valueClass == Boolean.class;
            } else if (lowerType.equals("list") || lowerType.equals("array") || lowerType.equals("collection")) {
                isType = value instanceof java.util.Collection;
            } else if (lowerType.equals("map") || lowerType.equals("json")) {
                isType = value instanceof java.util.Map;
            }
        }
        ctx.setOutput(node, "result", isType);
    }
}
