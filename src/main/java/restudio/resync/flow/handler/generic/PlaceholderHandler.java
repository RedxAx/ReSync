package restudio.resync.flow.handler.generic;

import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import restudio.flow.data.FlowNode;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.NodeHandler;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

public class PlaceholderHandler implements NodeHandler {
    private final Map<String, BiConsumer<FlowContext, FlowNode>> operations = new ConcurrentHashMap<>();

    public PlaceholderHandler() {
        operations.put("placeholder_parse", (ctx, node) -> {
            if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") == null) {
                ctx.setOutput(node, "success", false);
                ctx.setOutput(node, "result", "");
                return;
            }
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            String text = ctx.getInputValue(node, "text", String.class, "");
            if (text == null || text.isEmpty()) {
                ctx.setOutput(node, "success", false);
                ctx.setOutput(node, "result", "");
                return;
            }
            try {
                String result = PlaceholderAPI.setPlaceholders(player, text);
                ctx.setOutput(node, "success", true);
                ctx.setOutput(node, "result", result);
            } catch (Exception e) {
                ctx.setOutput(node, "success", false);
                ctx.setOutput(node, "result", text);
            }
        });

        operations.put("placeholder_set", (ctx, node) -> {
            if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") == null) {
                ctx.setOutput(node, "success", false);
                ctx.setOutput(node, "result", "");
                return;
            }
            Player playerOne = ctx.getInputValue(node, "player_one", Player.class, null);
            Player playerTwo = ctx.getInputValue(node, "player_two", Player.class, null);
            String text = ctx.getInputValue(node, "text", String.class, "");
            if (text == null || text.isEmpty()) {
                ctx.setOutput(node, "success", false);
                ctx.setOutput(node, "result", "");
                return;
            }
            try {
                String result = PlaceholderAPI.setRelationalPlaceholders(playerOne, playerTwo, text);
                ctx.setOutput(node, "success", true);
                ctx.setOutput(node, "result", result);
            } catch (Exception e) {
                ctx.setOutput(node, "success", false);
                ctx.setOutput(node, "result", text);
            }
        });

        operations.put("placeholder_remove", (ctx, node) -> {
            if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") == null) {
                ctx.setOutput(node, "success", false);
                ctx.setOutput(node, "result", "");
                return;
            }
            String text = ctx.getInputValue(node, "text", String.class, "");
            if (text == null) {
                ctx.setOutput(node, "success", false);
                ctx.setOutput(node, "result", "");
                return;
            }
            try {
                String result = PlaceholderAPI.setBracketPlaceholders(null, text);
                ctx.setOutput(node, "success", true);
                ctx.setOutput(node, "result", result);
            } catch (Exception e) {
                ctx.setOutput(node, "success", false);
                ctx.setOutput(node, "result", text);
            }
        });

        operations.put("placeholder_set_relational", (ctx, node) -> {
            String key = ctx.getInputValue(node, "key", String.class, "");
            String value = ctx.getInputValue(node, "value", String.class, "");
            // Relational placeholder requires placeholder API integration
        });

        operations.put("placeholder_strip_brackets", (ctx, node) -> {
            String text = ctx.getInputValue(node, "text", String.class, "");
            String result = text.replace("%", "").replace("{", "").replace("}", "");
            ctx.setOutput(node, "result", result);
        });

    }

    public void registerTo(HandlerRegistry registry) {
        registry.register("PlaceholderHandler", this);
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
