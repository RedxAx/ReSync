package restudio.resync.flow.handler.generic;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.entity.Player;
import restudio.flow.data.FlowNode;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.NodeHandler;
import restudio.resync.flow.util.TextFormatter;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

public class TitleHandler implements NodeHandler {
    private final ConcurrentHashMap<String, BiConsumer<FlowContext, FlowNode>> operations = new ConcurrentHashMap<>();

    public TitleHandler() {
        operations.put("title_send", (ctx, node) -> {
            Player player = requirePlayer(ctx, node);
            String title = ctx.getInputValue(node, "title", String.class, "");
            String subtitle = ctx.getInputValue(node, "subtitle", String.class, "");
            Integer fadeIn = ctx.getInputValue(node, "fade_in", Integer.class, 10);
            Integer stay = ctx.getInputValue(node, "stay", Integer.class, 70);
            Integer fadeOut = ctx.getInputValue(node, "fade_out", Integer.class, 20);
            if (title.isBlank() && subtitle.isBlank()) throw new IllegalArgumentException("Title or subtitle text is required");
            player.showTitle(Title.title(TextFormatter.parse(title), TextFormatter.parse(subtitle), titleTimes(fadeIn, stay, fadeOut)));
        });

        operations.put("actionbar_send", (ctx, node) -> {
            Player player = requirePlayer(ctx, node);
            String text = ctx.getInputValue(node, "text", String.class, "");
            Integer durationTicks = ctx.getInputValue(node, "duration_ticks", Integer.class, 60);
            if (text.isBlank()) throw new IllegalArgumentException("Action bar text is required");
            if (durationTicks < 1 || durationTicks > 6_000) throw new IllegalArgumentException("Action bar duration must be between 1 and 6000 ticks");
            sendActionBar(ctx, player, TextFormatter.parse(text), durationTicks);
        });

        operations.put("title_clear", (ctx, node) -> {
            requirePlayer(ctx, node).clearTitle();
        });

        operations.put("subtitle", (ctx, node) -> {
            Player player = requirePlayer(ctx, node);
            String subtitle = ctx.getInputValue(node, "subtitle", String.class, "");
            Integer fadeIn = ctx.getInputValue(node, "fade_in", Integer.class, 10);
            Integer stay = ctx.getInputValue(node, "stay", Integer.class, 70);
            Integer fadeOut = ctx.getInputValue(node, "fade_out", Integer.class, 20);
            if (subtitle.isBlank()) throw new IllegalArgumentException("Subtitle text is required");
            player.showTitle(Title.title(Component.empty(), TextFormatter.parse(subtitle), titleTimes(fadeIn, stay, fadeOut)));
        });

        operations.put("times", (ctx, node) -> {
            Player player = requirePlayer(ctx, node);
            Integer fadeIn = ctx.getInputValue(node, "fade_in", Integer.class, 10);
            Integer stay = ctx.getInputValue(node, "stay", Integer.class, 70);
            Integer fadeOut = ctx.getInputValue(node, "fade_out", Integer.class, 20);
            validateTitleTicks(fadeIn, "fade in");
            validateTitleTicks(stay, "stay");
            validateTitleTicks(fadeOut, "fade out");
            player.sendTitle(null, null, fadeIn, stay, fadeOut);
        });
    }

    public void registerTo(HandlerRegistry registry) {
        registry.register("TitleHandler", this);
    }

    @Override
    public void execute(FlowContext ctx, FlowNode node) {
        String operation = node.getHandlerConfig().getString("operation");
        BiConsumer<FlowContext, FlowNode> op = operation != null ? operations.get(operation) : null;
        if (op == null) {
            throw new IllegalArgumentException("Unknown title operation: " + operation);
        }
        op.accept(ctx, node);
        ctx.triggerOutput("flow");
    }

    private static Player requirePlayer(FlowContext context, FlowNode node) {
        Player player = context.getInputValue(node, "player", Player.class, context.getPlayer());
        if (player == null) throw new IllegalArgumentException("Player is required");
        return player;
    }

    private static Title.Times titleTimes(int fadeIn, int stay, int fadeOut) {
        validateTitleTicks(fadeIn, "fade in");
        validateTitleTicks(stay, "stay");
        validateTitleTicks(fadeOut, "fade out");
        return Title.Times.of(Duration.ofMillis(fadeIn * 50L), Duration.ofMillis(stay * 50L), Duration.ofMillis(fadeOut * 50L));
    }

    private static void validateTitleTicks(int ticks, String field) {
        if (ticks < 0 || ticks > 72_000) throw new IllegalArgumentException("Title " + field + " must be between 0 and 72000 ticks");
    }

    private static void sendActionBar(FlowContext context, Player player, Component component, int remainingTicks) {
        if (!player.isOnline()) return;
        player.sendActionBar(component);
        int delay = Math.min(40, remainingTicks);
        context.runLater(() -> {
            if (remainingTicks <= 40) {
                if (player.isOnline()) player.sendActionBar(Component.empty());
                return;
            }
            sendActionBar(context, player, component, remainingTicks - delay);
        }, delay);
    }
}
