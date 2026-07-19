package restudio.resync.flow.handler.generic;

import restudio.flow.data.FlowNode;
import restudio.resync.flow.CustomEventManager;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.NodeHandler;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

public class CustomEventHandler implements NodeHandler {
    private final Map<String, BiConsumer<FlowContext, FlowNode>> operations = new ConcurrentHashMap<>();

    public CustomEventHandler() {
        operations.put("custom_event_define", (ctx, node) -> {
            String eventId = ctx.getInputValue(node, "event_id", String.class, "");
            if (eventId != null && !eventId.isEmpty()) {
                CustomEventManager.getInstance().getLastEventData(eventId);
            }
        });

        operations.put("custom_event_trigger", (ctx, node) -> {
            String eventId = ctx.getInputValue(node, "event_id", String.class, "");
            Object dataPayload = ctx.getInputValue(node, "data_payload", Object.class, new HashMap<>());

            if (eventId != null && !eventId.isEmpty()) {
                Map<String, Object> data;
                if (dataPayload instanceof Map<?, ?> map) {
                    data = new HashMap<>();
                    for (Map.Entry<?, ?> entry : map.entrySet()) {
                        if (entry.getKey() != null) {
                            data.put(String.valueOf(entry.getKey()), entry.getValue());
                        }
                    }
                } else {
                    data = new HashMap<>();
                    data.put("value", dataPayload);
                }
                CustomEventManager.getInstance().emit(eventId, data);
            }
        });

        operations.put("custom_event_listen", (ctx, node) -> {
            String nodeId = findNodeId(ctx, node);
            String eventId = ctx.getInputValue(node, "event_id", String.class, "");
            Integer timeoutTicks = ctx.getInputValue(node, "timeout_ticks", Integer.class, 0);

            if (eventId != null && !eventId.isEmpty()) {
                CustomEventManager.Listener listener = new CustomEventManager.Listener(ctx, nodeId, timeoutTicks);
                CustomEventManager.getInstance().listen(eventId, listener);
                ctx.setOutput(node, "listening", true);
            } else {
                ctx.setOutput(node, "listening", false);
            }
        });

        operations.put("custom_event_remove_listener", (ctx, node) -> {
            String eventId = ctx.getInputValue(node, "event_id", String.class, "");

            if (eventId != null && !eventId.isEmpty()) {
                CustomEventManager.getInstance().clearListeners(eventId);
            }
        });

        operations.put("custom_event_clear", (ctx, node) -> {
            String eventId = ctx.getInputValue(node, "event_id", String.class, "");
            if (eventId != null && !eventId.isEmpty()) {
                CustomEventManager.getInstance().clearListeners(eventId);
            }
        });

        operations.put("custom_event_get_data", (ctx, node) -> {
            String eventId = ctx.getInputValue(node, "event_id", String.class, "");
            if (eventId != null && !eventId.isEmpty()) {
                Map<String, Object> data = CustomEventManager.getInstance().getLastEventData(eventId);
                ctx.setOutput(node, "data", data != null ? data : new HashMap<>());
            } else {
                ctx.setOutput(node, "data", new HashMap<>());
            }
        });

    }

    public void registerTo(HandlerRegistry registry) {
        registry.register("CustomEventHandler", this);
    }

    @Override
    public void execute(FlowContext ctx, FlowNode node) {
        String operation = node.getHandlerConfig().getString("operation");
        BiConsumer<FlowContext, FlowNode> op = operation != null ? operations.get(operation) : null;
        if (op == null) {
            throw new IllegalArgumentException("Unknown custom event operation: " + operation);
        }
        op.accept(ctx, node);
        ctx.triggerOutput("flow");
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
