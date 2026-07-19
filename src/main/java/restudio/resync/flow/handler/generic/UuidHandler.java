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
            try {
                ctx.setOutput(node, "uuid_object", UUID.fromString(uuidString));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("Invalid UUID: " + uuidString, exception);
            }
        });

        operations.put("uuid_to_string", (ctx, node) -> {
            UUID uuidObject = ctx.getInputValue(node, "uuid_object", UUID.class, null);
            ctx.setOutput(node, "uuid_string", requireUuid(uuidObject).toString());
        });

        operations.put("uuid_version", (ctx, node) -> {
            UUID uuidObject = ctx.getInputValue(node, "uuid_object", UUID.class, null);
            ctx.setOutput(node, "version", requireUuid(uuidObject).version());
        });

        operations.put("uuid_timestamp", (ctx, node) -> {
            UUID uuidObject = ctx.getInputValue(node, "uuid_object", UUID.class, null);
            ctx.setOutput(node, "timestamp", requireUuid(uuidObject).timestamp());
        });
    }

    public void registerTo(HandlerRegistry registry) {
        registry.register("UuidHandler", this);
    }

    @Override
    public void execute(FlowContext ctx, FlowNode node) {
        String operation = node.getHandlerConfig().getString("operation");
        BiConsumer<FlowContext, FlowNode> op = operation != null ? operations.get(operation) : null;
        if (op == null) {
            throw new IllegalArgumentException("Unknown UUID operation: " + operation);
        }
        op.accept(ctx, node);
        ctx.triggerOutput("flow");
    }

    private static UUID requireUuid(UUID value) {
        if (value == null) {
            throw new IllegalArgumentException("UUID is required");
        }
        return value;
    }
}
