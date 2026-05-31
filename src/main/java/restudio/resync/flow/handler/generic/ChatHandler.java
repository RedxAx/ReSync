package restudio.resync.flow.handler.generic;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import restudio.flow.data.FlowNode;
import restudio.resync.ReSync;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.NodeHandler;
import restudio.resync.modules.ChatModule;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

public class ChatHandler implements NodeHandler {
    private final Map<String, BiConsumer<FlowContext, FlowNode>> operations = new ConcurrentHashMap<>();

    public ChatHandler() {
        operations.put("chat_cancel", (ctx, node) -> {
            Event event = ctx.getEvent();
            if (event instanceof Cancellable cancellable) {
                cancellable.setCancelled(true);
                ctx.setOutput(node, "success", true);
            } else {
                ctx.setOutput(node, "success", false);
            }
        });
        operations.put("chat_set_message", (ctx, node) -> {
            ChatModule module = module();
            Player player = ctx.getPlayerInput(node, "player");
            String message = ctx.getInputValue(node, "message", String.class, "");
            boolean success = module != null && module.setEventMessage(ctx.getEvent(), player, message);
            ctx.setOutput(node, "success", success);
        });
        operations.put("chat_add_viewer", (ctx, node) -> {
            ChatModule module = module();
            Player viewer = ctx.getInputValue(node, "viewer", Player.class, null);
            boolean success = module != null && module.addEventViewer(ctx.getEvent(), viewer);
            ctx.setOutput(node, "success", success);
        });
        operations.put("chat_remove_viewer", (ctx, node) -> {
            ChatModule module = module();
            Player viewer = ctx.getInputValue(node, "viewer", Player.class, null);
            boolean success = module != null && module.removeEventViewer(ctx.getEvent(), viewer);
            ctx.setOutput(node, "success", success);
        });
        operations.put("chat_send_channel", (ctx, node) -> {
            ChatModule module = module();
            Player player = ctx.getPlayerInput(node, "player");
            String channel = ctx.getInputValue(node, "channel", String.class, "");
            String message = ctx.getInputValue(node, "message", String.class, "");
            boolean success = module != null && module.sendChannelMessage(player, channel, message);
            ctx.setOutput(node, "success", success);
        });
    }

    public void registerTo(HandlerRegistry registry) {
        registry.register("ChatHandler", this);
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

    private ChatModule module() {
        ReSync plugin = ReSync.getInstance();
        return plugin != null && plugin.getReSyncServer() != null
            ? plugin.getReSyncServer().getModuleContext().getService(ChatModule.class)
            : null;
    }
}
