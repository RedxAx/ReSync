package restudio.resync.flow.handler.generic;

import org.bukkit.entity.Player;
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
            if (event == null) throw new IllegalArgumentException("Chat Cancel requires an active event");
            if (!ctx.isEventMutationOpen()) throw new IllegalStateException("Chat event mutation window is closed");
            boolean success = ctx.setEventCancelled(true);
            if (!success) throw new IllegalArgumentException("Active event cannot be cancelled");
            ctx.setOutput(node, "success", true);
        });
        operations.put("chat_set_message", (ctx, node) -> {
            ChatModule module = requireModule();
            requireMutationWindow(ctx);
            Player player = requirePlayer(ctx, node, "player");
            String message = ctx.getInputValue(node, "message", String.class, "");
            if (message.isBlank()) throw new IllegalArgumentException("Chat message is required");
            boolean success = module.setEventMessage(ctx.getEvent(), player, message);
            ctx.setOutput(node, "success", success);
        });
        operations.put("chat_add_viewer", (ctx, node) -> {
            ChatModule module = requireModule();
            requireMutationWindow(ctx);
            Player viewer = requirePlayer(ctx, node, "viewer");
            boolean success = module.addEventViewer(ctx.getEvent(), viewer);
            ctx.setOutput(node, "success", success);
        });
        operations.put("chat_remove_viewer", (ctx, node) -> {
            ChatModule module = requireModule();
            requireMutationWindow(ctx);
            Player viewer = requirePlayer(ctx, node, "viewer");
            boolean success = module.removeEventViewer(ctx.getEvent(), viewer);
            ctx.setOutput(node, "success", success);
        });
        operations.put("chat_send_channel", (ctx, node) -> {
            ChatModule module = requireModule();
            Player player = requirePlayer(ctx, node, "player");
            String channel = ctx.getInputValue(node, "channel", String.class, "");
            String message = ctx.getInputValue(node, "message", String.class, "");
            if (channel.isBlank()) throw new IllegalArgumentException("Chat channel is required");
            if (message.isBlank()) throw new IllegalArgumentException("Chat message is required");
            boolean success = module.sendChannelMessage(player, channel, message);
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
        if (op == null) {
            throw new IllegalArgumentException("Unknown chat operation: " + operation);
        }
        op.accept(ctx, node);
        ctx.triggerOutput("flow");
    }

    private ChatModule requireModule() {
        ReSync plugin = ReSync.getInstance();
        ChatModule module = plugin != null && plugin.getReSyncServer() != null
            ? plugin.getReSyncServer().getModuleContext().getService(ChatModule.class)
            : null;
        if (module == null) throw new IllegalStateException("Chat runtime is unavailable");
        return module;
    }

    private Player requirePlayer(FlowContext context, FlowNode node, String inputName) {
        Player player = context.getInputValue(node, inputName, Player.class, context.getPlayer());
        if (player == null) throw new IllegalArgumentException("Player input is required: " + inputName);
        return player;
    }

    private void requireMutationWindow(FlowContext context) {
        if (context.getEvent() == null) throw new IllegalArgumentException("Chat mutation requires an active event");
        if (!context.isEventMutationOpen()) throw new IllegalStateException("Chat event mutation window is closed");
    }
}
