package restudio.resync.flow.nodes;

import restudio.resync.Log;
import org.bukkit.event.Cancellable;
import restudio.flow.data.FlowNode;
import restudio.flow.data.FlowType;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.registry.DefineNode;
import restudio.resync.flow.registry.FlowPin;
import restudio.resync.flow.registry.NodeDefinition;

public class CoreUtilityNodes {

    @DefineNode(id = "log", displayName = "Log", category = NodeDefinition.NodeCategory.UTILITY,
            inputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW), @FlowPin(name = "text", dataType = FlowType.STRING)},
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void log(FlowContext ctx, FlowNode node) {
        Object text = ctx.getInputValue(node, "text", String.class, "");
        Log.info("[Flow] " + text);
        ctx.triggerOutput("flow");
    }

    @DefineNode(id = "cancel_event", displayName = "Cancel Event", category = NodeDefinition.NodeCategory.UTILITY,
            inputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW), @FlowPin(name = "cancel", dataType = FlowType.BOOLEAN)},
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void cancelEvent(FlowContext ctx, FlowNode node) {
        Boolean cancel = ctx.getInputValue(node, "cancel", Boolean.class, true);
        if (Boolean.TRUE.equals(cancel) && ctx.getEvent() instanceof Cancellable cancellable) {
            cancellable.setCancelled(true);
        }
        ctx.triggerOutput("flow");
    }

    @DefineNode(id = "delay", displayName = "Delay", category = NodeDefinition.NodeCategory.UTILITY,
            inputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW), @FlowPin(name = "ticks", dataType = FlowType.NUMBER)},
            outputs = {
                    @FlowPin(name = "done", dataType = FlowType.BOOLEAN),
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)
            })
    public void delay(FlowContext ctx, FlowNode node) {
        int ticks = ctx.getInputValue(node, "ticks", Integer.class, 20);
        ctx.setOutput(node, "done", true);
        ctx.runLater(() -> ctx.triggerOutput("flow"), ticks);
    }
}
