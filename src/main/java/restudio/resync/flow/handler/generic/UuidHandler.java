package restudio.resync.flow.handler.generic;

import restudio.flow.data.FlowNode;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.NodeHandler;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

public class UuidHandler implements NodeHandler {
    private final Map<String, BiConsumer<FlowContext, FlowNode>> operations = new ConcurrentHashMap<>();

    public UuidHandler() {
        operations.put("uuid_generate", (ctx, node) -> {
            ctx.setOutput(node, "uuid_string", UUID.randomUUID().toString());
        });

        operations.put("uuid_from_string", (ctx, node) -> {
            String uuidString = ctx.getInputValue(node, "uuid_string", String.class, "");
            UUID uuidObject = null;
            try {
                uuidObject = UUID.fromString(uuidString);
            } catch (Exception ignored) {
            }
            ctx.setOutput(node, "uuid_object", uuidObject);
        });

        operations.put("uuid_to_string", (ctx, node) -> {
            UUID uuidObject = ctx.getInputValue(node, "uuid_object", UUID.class, null);
            ctx.setOutput(node, "uuid_string", uuidObject != null ? uuidObject.toString() : "");
        });

        operations.put("uuid_version", (ctx, node) -> {
            UUID uuidObject = ctx.getInputValue(node, "uuid_object", UUID.class, null);
            ctx.setOutput(node, "version", uuidObject != null ? uuidObject.version() : -1);
        });

        operations.put("uuid_timestamp", (ctx, node) -> {
            UUID uuidObject = ctx.getInputValue(node, "uuid_object", UUID.class, null);
            ctx.setOutput(node, "timestamp", uuidObject != null ? uuidObject.timestamp() : 0L);
        });
    }

    public void registerTo(HandlerRegistry registry) {
        registry.register("UuidHandler", this);
    }

    @Override
    public void execute(FlowContext ctx, FlowNode node) {
        String operation = node.getHandlerConfig().getString("operation");
        BiConsumer<FlowContext, FlowNode> op = operation != null ? operations.get(operation) : null;
        if (op != null) {
            op.accept(ctx, node);
        }
        ctx.triggerOutput("flow");
    }
}
