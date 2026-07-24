package restudio.resync.flow.handler.generic;

import restudio.flow.data.FlowNode;
import restudio.flow.data.FlowOperationResult;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.NodeHandler;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class ResultHandler implements NodeHandler {
    private static final Set<String> OPERATIONS = Set.of("success", "failure", "is_success", "value", "error", "match");

    public void registerTo(HandlerRegistry registry) {
        registry.register("ResultHandler", this);
    }

    @Override
    public Set<String> getSupportedOperations() {
        return OPERATIONS;
    }

    @Override
    public void execute(FlowContext ctx, FlowNode node) {
        String operation = node.getHandlerConfig().getString("operation");
        switch (operation != null ? operation : "") {
            case "success" -> ctx.setOutput(node, "result", FlowOperationResult.success(ctx.getInputValue(node, "value", Object.class, null)));
            case "failure" -> ctx.setOutput(node, "result", FlowOperationResult.failure(
                ctx.getInputValue(node, "error_code", String.class, "FAILED"),
                ctx.getInputValue(node, "message", String.class, "Operation Failed"),
                ctx.getInputValue(node, "details", Map.class, Map.of())
            ));
            case "is_success" -> ctx.setOutput(node, "success", result(ctx, node).success());
            case "value" -> {
                FlowOperationResult<?> result = result(ctx, node);
                ctx.setOutput(node, "value", result.value());
                ctx.setOutput(node, "available", result.success());
            }
            case "error" -> publishError(ctx, node, result(ctx, node));
            case "match" -> {
                FlowOperationResult<?> result = result(ctx, node);
                ctx.setOutput(node, "value", result.value());
                publishError(ctx, node, result);
                ctx.triggerOutput(result.success() ? "success" : "failure");
            }
            default -> throw new IllegalArgumentException("Unknown result operation: " + operation);
        }
    }

    private FlowOperationResult<?> result(FlowContext ctx, FlowNode node) {
        Object value = ctx.getInputValue(node, "result", Object.class, null);
        if (value instanceof FlowOperationResult<?> result) {
            return result;
        }
        if (value instanceof Map<?, ?> map) {
            boolean success = Boolean.parseBoolean(String.valueOf(map.get("success")));
            Object detailsValue = map.get("details");
            Map<String, Object> details = detailsValue instanceof Map<?, ?> raw ? raw.entrySet().stream()
                .filter(entry -> entry.getKey() != null)
                .collect(Collectors.toMap(entry -> entry.getKey().toString(), Map.Entry::getValue)) : Map.of();
            return new FlowOperationResult<>(success, map.get("value"), text(map.get("errorCode")), text(map.get("message")), details);
        }
        return FlowOperationResult.failure("RESULT_REQUIRED", "Result Is Missing", Map.of());
    }

    private void publishError(FlowContext ctx, FlowNode node, FlowOperationResult<?> result) {
        ctx.setOutput(node, "error_code", result.errorCode());
        ctx.setOutput(node, "message", result.message());
        ctx.setOutput(node, "details", result.details());
    }

    private String text(Object value) {
        return value != null ? value.toString() : "";
    }
}
