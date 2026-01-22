package restudio.resync.flow.nodes;

import restudio.flow.data.FlowNode;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.FlowRegistry;
import restudio.resync.flow.NodeCategory;

public class LogicNodes implements NodeCategory {
    
    @Override
    public void registerNodes(FlowRegistry registry) {
        registry.register("logic_and", (ctx, node) -> {
            Boolean a = ctx.getInputValue(node, "a", Boolean.class, false);
            Boolean b = ctx.getInputValue(node, "b", Boolean.class, false);
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "result", a && b);
        });
        
        registry.register("logic_or", (ctx, node) -> {
            Boolean a = ctx.getInputValue(node, "a", Boolean.class, false);
            Boolean b = ctx.getInputValue(node, "b", Boolean.class, false);
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "result", a || b);
        });
        
        registry.register("logic_not", (ctx, node) -> {
            Boolean a = ctx.getInputValue(node, "value", Boolean.class, false);
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "result", !a);
        });
        
        registry.register("logic_xor", (ctx, node) -> {
            Boolean a = ctx.getInputValue(node, "a", Boolean.class, false);
            Boolean b = ctx.getInputValue(node, "b", Boolean.class, false);
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "result", a ^ b);
        });
        
        registry.register("logic_nand", (ctx, node) -> {
            Boolean a = ctx.getInputValue(node, "a", Boolean.class, false);
            Boolean b = ctx.getInputValue(node, "b", Boolean.class, false);
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "result", !(a && b));
        });
        
        registry.register("logic_nor", (ctx, node) -> {
            Boolean a = ctx.getInputValue(node, "a", Boolean.class, false);
            Boolean b = ctx.getInputValue(node, "b", Boolean.class, false);
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "result", !(a || b));
        });
        
        registry.register("logic_true", (ctx, node) -> {
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "value", true);
        });
        
        registry.register("logic_false", (ctx, node) -> {
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "value", false);
        });
        
        registry.register("compare_equals", (ctx, node) -> {
            Object a = ctx.getInputValue(node, "a", null);
            Object b = ctx.getInputValue(node, "b", null);
            String nodeId = findNodeId(ctx, node);
            boolean equal = (a != null && a.equals(b)) || (a == null && b == null);
            ctx.setNodeOutput(nodeId, "result", equal);
        });
        
        registry.register("compare_not_equals", (ctx, node) -> {
            Object a = ctx.getInputValue(node, "a", null);
            Object b = ctx.getInputValue(node, "b", null);
            String nodeId = findNodeId(ctx, node);
            boolean notEqual = !((a != null && a.equals(b)) || (a == null && b == null));
            ctx.setNodeOutput(nodeId, "result", notEqual);
        });
        
        registry.register("compare_greater", (ctx, node) -> {
            Double a = ctx.getInputValue(node, "a", Double.class, 0.0);
            Double b = ctx.getInputValue(node, "b", Double.class, 0.0);
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "result", a > b);
        });
        
        registry.register("compare_less", (ctx, node) -> {
            Double a = ctx.getInputValue(node, "a", Double.class, 0.0);
            Double b = ctx.getInputValue(node, "b", Double.class, 0.0);
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "result", a < b);
        });
        
        registry.register("compare_greater_or_equal", (ctx, node) -> {
            Double a = ctx.getInputValue(node, "a", Double.class, 0.0);
            Double b = ctx.getInputValue(node, "b", Double.class, 0.0);
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "result", a >= b);
        });
        
        registry.register("compare_less_or_equal", (ctx, node) -> {
            Double a = ctx.getInputValue(node, "a", Double.class, 0.0);
            Double b = ctx.getInputValue(node, "b", Double.class, 0.0);
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "result", a <= b);
        });
        
        registry.register("compare_between", (ctx, node) -> {
            Double value = ctx.getInputValue(node, "value", Double.class, 0.0);
            Double min = ctx.getInputValue(node, "min", Double.class, 0.0);
            Double max = ctx.getInputValue(node, "max", Double.class, 0.0);
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "result", value >= min && value <= max);
        });
        
        registry.register("compare_type", (ctx, node) -> {
            Object value = ctx.getInputValue(node, "value", null);
            String typeName = ctx.getInputValue(node, "type", String.class, "");
            String nodeId = findNodeId(ctx, node);
            boolean isType = false;
            if (value == null) {
                isType = typeName.equalsIgnoreCase("null");
            } else {
                Class<?> valueClass = value.getClass();
                String lowerType = typeName.toLowerCase();
                if (lowerType.equals("string")) {
                    isType = valueClass == String.class;
                } else if (lowerType.equals("number") || lowerType.equals("double") || 
                           lowerType.equals("int") || lowerType.equals("float") || lowerType.equals("long")) {
                    isType = value instanceof Number;
                } else if (lowerType.equals("boolean") || lowerType.equals("bool")) {
                    isType = valueClass == Boolean.class;
                } else if (lowerType.equals("list") || lowerType.equals("array") || lowerType.equals("collection")) {
                    isType = value instanceof java.util.Collection;
                } else if (lowerType.equals("map") || lowerType.equals("json")) {
                    isType = value instanceof java.util.Map;
                }
            }
            ctx.setNodeOutput(nodeId, "result", isType);
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
