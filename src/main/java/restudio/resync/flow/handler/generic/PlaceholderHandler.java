package restudio.resync.flow.handler.generic;

import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import restudio.flow.data.FlowNode;
import restudio.flow.data.FlowOperationResult;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.NodeHandler;

import java.util.Map;
import java.util.function.Supplier;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

public class PlaceholderHandler implements NodeHandler {
    private final Map<String, BiConsumer<FlowContext, FlowNode>> operations = new ConcurrentHashMap<>();

    public PlaceholderHandler() {
        operations.put("placeholder_parse", (ctx, node) -> {
            if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") == null) {
                complete(ctx, node, FlowOperationResult.failure("PLACEHOLDER_API_UNAVAILABLE", "PlaceholderAPI is unavailable", Map.of()), "");
                return;
            }
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            String text = ctx.getInputValue(node, "text", String.class, "");
            if (text == null || text.isEmpty()) {
                complete(ctx, node, FlowOperationResult.failure("PLACEHOLDER_TEXT_REQUIRED", "Placeholder text is required", Map.of()), "");
                return;
            }
            executePlaceholder(ctx, node, text, () -> PlaceholderAPI.setPlaceholders(player, text));
        });

        BiConsumer<FlowContext, FlowNode> relational = (ctx, node) -> {
            if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") == null) {
                complete(ctx, node, FlowOperationResult.failure("PLACEHOLDER_API_UNAVAILABLE", "PlaceholderAPI is unavailable", Map.of()), "");
                return;
            }
            Player playerOne = ctx.getInputValue(node, "player_one", Player.class, null);
            Player playerTwo = ctx.getInputValue(node, "player_two", Player.class, null);
            String text = ctx.getInputValue(node, "text", String.class, "");
            if (text == null || text.isEmpty()) {
                complete(ctx, node, FlowOperationResult.failure("PLACEHOLDER_TEXT_REQUIRED", "Placeholder text is required", Map.of()), "");
                return;
            }
            executePlaceholder(ctx, node, text, () -> PlaceholderAPI.setRelationalPlaceholders(playerOne, playerTwo, text));
        };
        operations.put("placeholder_set", relational);
        operations.put("placeholder_set_relational", relational);

        operations.put("placeholder_remove", (ctx, node) -> {
            if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") == null) {
                complete(ctx, node, FlowOperationResult.failure("PLACEHOLDER_API_UNAVAILABLE", "PlaceholderAPI is unavailable", Map.of()), "");
                return;
            }
            String text = ctx.getInputValue(node, "text", String.class, "");
            if (text == null) {
                complete(ctx, node, FlowOperationResult.failure("PLACEHOLDER_TEXT_REQUIRED", "Placeholder text is required", Map.of()), "");
                return;
            }
            executePlaceholder(ctx, node, text, () -> PlaceholderAPI.setBracketPlaceholders(null, text));
        });

        operations.put("placeholder_strip_brackets", (ctx, node) -> {
            String text = ctx.getInputValue(node, "text", String.class, "");
            String result = text.replace("%", "").replace("{", "").replace("}", "");
            complete(ctx, node, FlowOperationResult.success(result), result);
        });

    }

    public void registerTo(HandlerRegistry registry) {
        registry.register("PlaceholderHandler", this);
    }

    @Override
    public void execute(FlowContext ctx, FlowNode node) {
        String operation = node.getHandlerConfig().getString("operation");
        BiConsumer<FlowContext, FlowNode> op = operation != null ? operations.get(operation) : null;
        if (op == null) {
            throw new IllegalArgumentException("Unknown placeholder operation: " + operation);
        }
        op.accept(ctx, node);
    }

    private void executePlaceholder(FlowContext ctx, FlowNode node, String fallback, Supplier<String> operation) {
        try {
            String value = operation.get();
            complete(ctx, node, FlowOperationResult.success(value), value);
        } catch (RuntimeException exception) {
            String message = exception.getMessage() != null && !exception.getMessage().isBlank() ? exception.getMessage() : "Placeholder operation failed";
            complete(ctx, node, FlowOperationResult.failure("PLACEHOLDER_OPERATION_FAILED", message, Map.of()), fallback);
        }
    }

    private void complete(FlowContext ctx, FlowNode node, FlowOperationResult<String> operationResult, String value) {
        ctx.setOutput(node, "result", value);
        ctx.setOutput(node, "operation_result", operationResult);
        ctx.setOutput(node, "success", operationResult.success());
        ctx.setOutput(node, "error_code", operationResult.errorCode());
        ctx.setOutput(node, "message", operationResult.message());
        ctx.triggerOutput(operationResult.success() ? "flow" : "failed");
    }
}
