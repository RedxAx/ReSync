package restudio.resync.flow.nodes;

import restudio.flow.data.FlowNode;
import restudio.flow.data.FlowType;
import restudio.resync.flow.CustomEventManager;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.FlowRegistry;
import restudio.resync.flow.registry.DefineNode;
import restudio.resync.flow.registry.FlowPin;
import restudio.resync.flow.registry.NodeDefinition;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

public class CustomEventNodes {

    private static final Map<String, BiConsumer<FlowContext, FlowNode>> LEGACY_EXECUTORS = new ConcurrentHashMap<>();
    private static volatile boolean initialized;

    private static void registerLegacyNodes(FlowRegistry registry) {
        registry.register("custom_event_emit", (ctx, node) -> {
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

    public void registerNodes(FlowRegistry registry) {
        registerLegacyNodes(registry);
    }

    private static void ensureLegacyInitialized() {
        if (initialized) {
            return;
        }
        synchronized (CustomEventNodes.class) {
            if (initialized) {
                return;
            }
            FlowRegistry tempRegistry = new FlowRegistry();
            registerLegacyNodes(tempRegistry);
            for (String type : tempRegistry.getRegisteredTypes()) {
                LEGACY_EXECUTORS.put(type, tempRegistry.getExecutor(type));
            }
            initialized = true;
        }
    }

    private static void executeLegacy(String id, FlowContext ctx, FlowNode node) {
        ensureLegacyInitialized();
        BiConsumer<FlowContext, FlowNode> executor = LEGACY_EXECUTORS.get(id);
        if (executor != null) {
            executor.accept(ctx, node);
        } else {
            ctx.triggerOutput("flow");
        }
    }

    @DefineNode(
        id = "custom_event_emit",
        displayName = "Custom Event Emit",
        category = NodeDefinition.NodeCategory.EVENT,
        inputs = {
            @FlowPin(name = "event_id", dataType = FlowType.STRING),
            @FlowPin(name = "data_payload", dataType = FlowType.ANY)
        },
        outputs = {
            @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION)
        }
    )
    public void customEventEmit(FlowContext ctx, FlowNode node) {
        executeLegacy("custom_event_emit", ctx, node);
    }

    @DefineNode(
        id = "custom_event_listen",
        displayName = "Custom Event Listen",
        category = NodeDefinition.NodeCategory.EVENT,
        inputs = {
            @FlowPin(name = "event_id", dataType = FlowType.STRING),
            @FlowPin(name = "timeout_ticks", dataType = FlowType.NUMBER)
        },
        outputs = {
            @FlowPin(name = "listening", dataType = FlowType.BOOLEAN),
            @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
            @FlowPin(name = "next", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION)
        }
    )
    public void customEventListen(FlowContext ctx, FlowNode node) {
        executeLegacy("custom_event_listen", ctx, node);
    }

    @DefineNode(
        id = "custom_event_clear",
        displayName = "Custom Event Clear",
        category = NodeDefinition.NodeCategory.EVENT,
        inputs = {
            @FlowPin(name = "event_id", dataType = FlowType.STRING)
        },
        outputs = {
            @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION)
        }
    )
    public void customEventClear(FlowContext ctx, FlowNode node) {
        executeLegacy("custom_event_clear", ctx, node);
    }

    @DefineNode(
        id = "custom_event_get_data",
        displayName = "Custom Event Get Data",
        category = NodeDefinition.NodeCategory.EVENT,
        inputs = {
            @FlowPin(name = "event_id", dataType = FlowType.STRING),
            @FlowPin(name = "data_key", dataType = FlowType.STRING)
        },
        outputs = {
            @FlowPin(name = "value", dataType = FlowType.ANY),
            @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION)
        }
    )
    public void customEventGetData(FlowContext ctx, FlowNode node) {
        executeLegacy("custom_event_get_data", ctx, node);
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
