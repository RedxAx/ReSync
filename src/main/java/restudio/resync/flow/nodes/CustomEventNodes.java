package restudio.resync.flow.nodes;

import restudio.flow.data.FlowNode;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.FlowRegistry;
import restudio.resync.flow.NodeCategory;
import restudio.resync.flow.CustomEventManager;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CustomEventNodes implements NodeCategory {
    
    @Override
    public void registerNodes(FlowRegistry registry) {
        registry.register("custom_event_emit", (ctx, node) -> {
            String eventId = ctx.getInputValue(node, "event_id", String.class, "");
            Object dataPayload = ctx.getInputValue(node, "data_payload", Object.class, new HashMap<>());
            
            if (eventId != null && !eventId.isEmpty()) {
                Map<String, Object> data;
                if (dataPayload instanceof Map) {
                    data = (Map<String, Object>) dataPayload;
                } else {
                    data = new HashMap<>();
                    data.put("value", dataPayload);
                }
                CustomEventManager.getInstance().emit(eventId, data);
            }
            
            ctx.triggerOutput("flow");
        });

        registry.register("custom_event_listen", (ctx, node) -> {
            String nodeId = findNodeId(ctx, node);
            String eventId = ctx.getInputValue(node, "event_id", String.class, "");
            Integer timeoutTicks = ctx.getInputValue(node, "timeout_ticks", Integer.class, 0);
            
            if (eventId != null && !eventId.isEmpty()) {
                CustomEventManager.Listener listener = new CustomEventManager.Listener(ctx, nodeId, timeoutTicks);
                CustomEventManager.getInstance().listen(eventId, listener);
                
                ctx.setNodeOutput(nodeId, "listening", true);
            } else {
                ctx.setNodeOutput(nodeId, "listening", false);
            }
            
            ctx.triggerOutput("flow");
        });

        registry.register("custom_event_clear", (ctx, node) -> {
            String eventId = ctx.getInputValue(node, "event_id", String.class, "");
            
            if (eventId != null && !eventId.isEmpty()) {
                CustomEventManager.getInstance().clearListeners(eventId);
            }
            
            ctx.triggerOutput("flow");
        });

        registry.register("custom_event_get_data", (ctx, node) -> {
            String nodeId = findNodeId(ctx, node);
            String eventId = ctx.getInputValue(node, "event_id", String.class, "");
            String dataKey = ctx.getInputValue(node, "data_key", String.class, "");
            
            Map<String, Object> lastEventData = CustomEventManager.getInstance().getLastEventData(eventId);
            Object value = null;
            
            if (lastEventData != null && dataKey != null && !dataKey.isEmpty()) {
                value = lastEventData.get(dataKey);
            }
            
            ctx.setNodeOutput(nodeId, "value", value);
            ctx.triggerOutput("flow");
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
