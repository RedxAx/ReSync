package restudio.resync.flow.handler.generic;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import restudio.flow.data.CustomContentGraphAdapter;
import restudio.flow.data.FlowNode;
import restudio.resync.customcontent.CustomContentAccess;
import restudio.resync.customcontent.CustomContentService;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.NodeHandler;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

public class CustomContentHandler implements NodeHandler {
    private final Map<String, BiConsumer<FlowContext, FlowNode>> operations = new ConcurrentHashMap<>();

    public CustomContentHandler() {
        operations.put("give_content", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, ctx.getPlayer());
            String contentId = ctx.getInputValue(node, "content_id", String.class, "");
            Integer amount = ctx.getInputValue(node, "amount", Integer.class, 1);
            CustomContentService service = CustomContentAccess.getService();
            if (player != null && service != null && contentId != null && !contentId.isBlank()) {
                ItemStack item = service.createItem(contentId, amount != null ? amount : 1);
                if (item != null) {
                    player.getInventory().addItem(item);
                    ctx.setOutput(node, "item", item);
                    ctx.setOutput(node, "success", true);
                } else {
                    ctx.setOutput(node, "success", false);
                }
            } else {
                ctx.setOutput(node, "success", false);
            }
            ctx.triggerOutput("flow");
        });
        operations.put("current_content", (ctx, node) -> {
            ctx.setOutput(node, "content_id", ctx.getRuntime().getEventVariables().get("event.content_id"));
            ctx.setOutput(node, "content_type", ctx.getRuntime().getEventVariables().get("event.content_type"));
            ctx.setOutput(node, "trigger", ctx.getRuntime().getEventVariables().get("event.trigger"));
            ctx.setOutput(node, "item", ctx.getRuntime().getEventVariables().get("event.item"));
            ctx.setOutput(node, "block", ctx.getRuntime().getEventVariables().get("event.block"));
            ctx.setOutput(node, "target", ctx.getRuntime().getEventVariables().get("event.target"));
            ctx.setOutput(node, "location", ctx.getRuntime().getEventVariables().get("event.location"));
            ctx.triggerOutput("flow");
        });
        operations.put("content_start", (ctx, node) -> {
            ctx.setOutput(node, "player", ctx.getRuntime().getEventVariables().get("event.player"));
            ctx.setOutput(node, "content_id", ctx.getRuntime().getEventVariables().get("event.content_id"));
            ctx.setOutput(node, "content_type", ctx.getRuntime().getEventVariables().get("event.content_type"));
            ctx.setOutput(node, "trigger", ctx.getRuntime().getEventVariables().get("event.trigger"));
            ctx.setOutput(node, "item", ctx.getRuntime().getEventVariables().get("event.item"));
            ctx.setOutput(node, "block", ctx.getRuntime().getEventVariables().get("event.block"));
            ctx.setOutput(node, "target", ctx.getRuntime().getEventVariables().get("event.target"));
            ctx.setOutput(node, "location", ctx.getRuntime().getEventVariables().get("event.location"));
            ctx.setOutput(node, "damage", ctx.getRuntime().getEventVariables().get("event.damage"));
            String trigger = String.valueOf(ctx.getRuntime().getEventVariables().get("event.trigger"));
            String pin = CustomContentGraphAdapter.pinForTrigger(trigger);
            ctx.triggerOutput(pin != null ? pin : "flow");
        });
    }

    public void registerTo(HandlerRegistry registry) {
        registry.register("CustomContentHandler", this);
        registry.register(CustomContentGraphAdapter.ITEM_NODE, this);
        registry.register(CustomContentGraphAdapter.BLOCK_NODE, this);
        registry.register(CustomContentGraphAdapter.ARMOR_NODE, this);
    }

    @Override
    public void execute(FlowContext ctx, FlowNode node) {
        String operation = CustomContentGraphAdapter.typeFromNode(node.getType()) != null ? "content_start" : node.getHandlerConfig().getString("operation");
        BiConsumer<FlowContext, FlowNode> op = operation != null ? operations.get(operation) : null;
        if (op != null) {
            op.accept(ctx, node);
        } else {
            ctx.triggerOutput("flow");
        }
    }
}
