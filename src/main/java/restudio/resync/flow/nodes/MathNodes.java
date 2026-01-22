package restudio.resync.flow.nodes;

import restudio.flow.data.FlowNode;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.FlowRegistry;
import restudio.resync.flow.NodeCategory;

public class MathNodes implements NodeCategory {
    
    @Override
    public void registerNodes(FlowRegistry registry) {
        registry.register("math_add", (ctx, node) -> {
            Double a = ctx.getInputValue(node, "a", Double.class, 0.0);
            Double b = ctx.getInputValue(node, "b", Double.class, 0.0);
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "result", a + b);
        });
        
        registry.register("math_subtract", (ctx, node) -> {
            Double a = ctx.getInputValue(node, "a", Double.class, 0.0);
            Double b = ctx.getInputValue(node, "b", Double.class, 0.0);
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "result", a - b);
        });
        
        registry.register("math_multiply", (ctx, node) -> {
            Double a = ctx.getInputValue(node, "a", Double.class, 0.0);
            Double b = ctx.getInputValue(node, "b", Double.class, 0.0);
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "result", a * b);
        });
        
        registry.register("math_divide", (ctx, node) -> {
            Double a = ctx.getInputValue(node, "a", Double.class, 0.0);
            Double b = ctx.getInputValue(node, "b", Double.class, 1.0);
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "result", b != 0 ? a / b : 0.0);
        });
        
        registry.register("math_modulo", (ctx, node) -> {
            Double a = ctx.getInputValue(node, "a", Double.class, 0.0);
            Double b = ctx.getInputValue(node, "b", Double.class, 1.0);
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "result", b != 0 ? a % b : 0.0);
        });
        
        registry.register("math_power", (ctx, node) -> {
            Double a = ctx.getInputValue(node, "a", Double.class, 0.0);
            Double b = ctx.getInputValue(node, "b", Double.class, 1.0);
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "result", Math.pow(a, b));
        });
        
        registry.register("math_sqrt", (ctx, node) -> {
            Double a = ctx.getInputValue(node, "value", Double.class, 0.0);
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "result", Math.sqrt(Math.max(0, a)));
        });
        
        registry.register("math_abs", (ctx, node) -> {
            Double a = ctx.getInputValue(node, "value", Double.class, 0.0);
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "result", Math.abs(a));
        });
        
        registry.register("math_floor", (ctx, node) -> {
            Double a = ctx.getInputValue(node, "value", Double.class, 0.0);
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "result", Math.floor(a));
        });
        
        registry.register("math_ceil", (ctx, node) -> {
            Double a = ctx.getInputValue(node, "value", Double.class, 0.0);
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "result", Math.ceil(a));
        });
        
        registry.register("math_round", (ctx, node) -> {
            Double a = ctx.getInputValue(node, "value", Double.class, 0.0);
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "result", Math.round(a));
        });
        
        registry.register("math_min", (ctx, node) -> {
            Double a = ctx.getInputValue(node, "a", Double.class, 0.0);
            Double b = ctx.getInputValue(node, "b", Double.class, 0.0);
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "result", Math.min(a, b));
        });
        
        registry.register("math_max", (ctx, node) -> {
            Double a = ctx.getInputValue(node, "a", Double.class, 0.0);
            Double b = ctx.getInputValue(node, "b", Double.class, 0.0);
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "result", Math.max(a, b));
        });
        
        registry.register("math_clamp", (ctx, node) -> {
            Double value = ctx.getInputValue(node, "value", Double.class, 0.0);
            Double min = ctx.getInputValue(node, "min", Double.class, 0.0);
            Double max = ctx.getInputValue(node, "max", Double.class, 1.0);
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "result", Math.max(min, Math.min(max, value)));
        });
        
        registry.register("math_lerp", (ctx, node) -> {
            Double a = ctx.getInputValue(node, "a", Double.class, 0.0);
            Double b = ctx.getInputValue(node, "b", Double.class, 0.0);
            Double t = ctx.getInputValue(node, "t", Double.class, 0.5);
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "result", a + (b - a) * t);
        });
        
        registry.register("math_sin", (ctx, node) -> {
            Double a = ctx.getInputValue(node, "value", Double.class, 0.0);
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "result", Math.sin(a));
        });
        
        registry.register("math_cos", (ctx, node) -> {
            Double a = ctx.getInputValue(node, "value", Double.class, 0.0);
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "result", Math.cos(a));
        });
        
        registry.register("math_tan", (ctx, node) -> {
            Double a = ctx.getInputValue(node, "value", Double.class, 0.0);
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "result", Math.tan(a));
        });
        
        registry.register("math_to_radians", (ctx, node) -> {
            Double a = ctx.getInputValue(node, "value", Double.class, 0.0);
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "result", Math.toRadians(a));
        });
        
        registry.register("math_to_degrees", (ctx, node) -> {
            Double a = ctx.getInputValue(node, "value", Double.class, 0.0);
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "result", Math.toDegrees(a));
        });
        
        registry.register("math_distance", (ctx, node) -> {
            Double x1 = ctx.getInputValue(node, "x1", Double.class, 0.0);
            Double y1 = ctx.getInputValue(node, "y1", Double.class, 0.0);
            Double z1 = ctx.getInputValue(node, "z1", Double.class, 0.0);
            Double x2 = ctx.getInputValue(node, "x2", Double.class, 0.0);
            Double y2 = ctx.getInputValue(node, "y2", Double.class, 0.0);
            Double z2 = ctx.getInputValue(node, "z2", Double.class, 0.0);
            String nodeId = findNodeId(ctx, node);
            double dx = x2 - x1;
            double dy = y2 - y1;
            double dz = z2 - z1;
            ctx.setNodeOutput(nodeId, "result", Math.sqrt(dx * dx + dy * dy + dz * dz));
        });
        
        registry.register("math_negate", (ctx, node) -> {
            Double a = ctx.getInputValue(node, "value", Double.class, 0.0);
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "result", -a);
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
