package restudio.resync.flow.nodes;

import restudio.flow.data.FlowNode;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.FlowRegistry;

public class LogicNodes {
    public static void registerAll(FlowRegistry registry) {
        registry.register("if", (ctx, node) -> {
            Boolean condition = ctx.getInputValue(node, "condition", Boolean.class, false);
            if (condition != null && condition) {
                ctx.triggerOutput("true");
            } else {
                ctx.triggerOutput("false");
            }
        });

        registry.register("compare", (ctx, node) -> {
            Double a = ctx.getInputValue(node, "a", Double.class, 0.0);
            Double b = ctx.getInputValue(node, "b", Double.class, 0.0);
            String nodeId = findNodeId(ctx, node);
            if (nodeId == null) {
                return;
            }
            ctx.setNodeOutput(nodeId, "equals", a.equals(b));
            ctx.setNodeOutput(nodeId, "greater", a > b);
            ctx.setNodeOutput(nodeId, "less", a < b);
        });

        registry.register("loop", (ctx, node) -> {
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
