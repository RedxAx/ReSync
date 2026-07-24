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
            ctx.setOutput(node, "projectile", ctx.getRuntime().getEventVariables().get("event.projectile"));
            ctx.setOutput(node, "projectile_type", ctx.getRuntime().getEventVariables().get("event.projectile_type"));
            ctx.setOutput(node, "velocity", ctx.getRuntime().getEventVariables().get("event.velocity"));
            String trigger = String.valueOf(ctx.getRuntime().getEventVariables().get("event.trigger"));
            String pin = CustomContentGraphAdapter.pinForTrigger(trigger);
            ctx.triggerOutput(pin != null ? pin : "flow");
        });
        operations.put("content_cooldown_state", (ctx, node) -> {
            CustomContentService service = CustomContentAccess.getService();
            if (service == null) {
                ctx.setOutput(node, "supported", false);
                ctx.setOutput(node, "is_ready", true);
                ctx.setOutput(node, "remaining_ticks", 0L);
                ctx.setOutput(node, "remaining_seconds", 0.0);
                ctx.setOutput(node, "elapsed_ticks", 0L);
                ctx.setOutput(node, "cooldown_ticks", 0);
                ctx.setOutput(node, "progress", 1.0);
                ctx.setOutput(node, "progress_percent", 100.0);
                ctx.setOutput(node, "current_tick", 0L);
                ctx.setOutput(node, "ready_tick", 0L);
                ctx.setOutput(node, "scope", "");
                ctx.setOutput(node, "key", "");
                ctx.setOutput(node, "trigger", "");
                ctx.triggerOutput("ready");
                return;
            }
            Player player = ctx.getInputValue(node, "player", Player.class, ctx.getPlayer());
            String contentId = ctx.getInputValue(node, "content_id", String.class, String.valueOf(ctx.getRuntime().getEventVariables().getOrDefault("event.content_id", "")));
            String trigger = ctx.getInputValue(node, "trigger", String.class, String.valueOf(ctx.getRuntime().getEventVariables().getOrDefault("event.trigger", "")));
            String instanceId = ctx.getInputValue(node, "instance_id", String.class, String.valueOf(ctx.getRuntime().getEventVariables().getOrDefault("event.instance_id", "")));
            CustomContentService.CooldownState state = service.cooldownState(contentId, trigger, player, instanceId);
            long remainingTicks = Math.max(0L, state.readyTick() - state.currentTick());
            ctx.setOutput(node, "supported", state.supported());
            ctx.setOutput(node, "is_ready", state.ready());
            ctx.setOutput(node, "remaining_ticks", remainingTicks);
            ctx.setOutput(node, "remaining_seconds", remainingTicks / 20.0);
            ctx.setOutput(node, "elapsed_ticks", Math.max(0, state.cooldownTicks()) - remainingTicks);
            ctx.setOutput(node, "cooldown_ticks", state.cooldownTicks());
            ctx.setOutput(node, "progress", state.progress());
            ctx.setOutput(node, "progress_percent", state.progress() * 100.0);
            ctx.setOutput(node, "current_tick", state.currentTick());
            ctx.setOutput(node, "ready_tick", state.readyTick());
            ctx.setOutput(node, "scope", state.scope());
            ctx.setOutput(node, "key", state.key());
            ctx.setOutput(node, "trigger", state.trigger());
            ctx.triggerOutput(state.ready() ? "ready" : "cooldown");
        });
    }

    public void registerTo(HandlerRegistry registry) {
        registry.register("CustomContentHandler", this);
        registry.register(CustomContentGraphAdapter.ITEM_NODE, this);
        registry.register(CustomContentGraphAdapter.BLOCK_NODE, this);
        registry.register(CustomContentGraphAdapter.ARMOR_NODE, this);
        registry.register(CustomContentGraphAdapter.PROJECTILE_NODE, this);
    }

    @Override
    public void execute(FlowContext ctx, FlowNode node) {
        String operation = CustomContentGraphAdapter.typeFromNode(node.getType()) != null ? "content_start" : node.getHandlerConfig().getString("operation");
        BiConsumer<FlowContext, FlowNode> op = operation != null ? operations.get(operation) : null;
        if (op != null) {
            op.accept(ctx, node);
        } else {
            throw new IllegalArgumentException("Unknown custom content operation: " + operation);
        }
    }
}
