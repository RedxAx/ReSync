package restudio.resync.flow.handler.generic;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import restudio.flow.data.FlowNode;
import restudio.resync.ReSync;
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
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            String title = ctx.getInputValue(node, "title", String.class, "");
            String subtitle = ctx.getInputValue(node, "subtitle", String.class, "");
            Integer fadeIn = ctx.getInputValue(node, "fade_in", Integer.class, 10);
            Integer stay = ctx.getInputValue(node, "stay", Integer.class, 70);
            Integer fadeOut = ctx.getInputValue(node, "fade_out", Integer.class, 20);

            if (player != null) {
                Component titleComponent = TextFormatter.parse(title);
                Component subtitleComponent = TextFormatter.parse(subtitle);
                Title.Times times = Title.Times.of(
                        Duration.ofMillis(fadeIn * 50L),
                        Duration.ofMillis(stay * 50L),
                        Duration.ofMillis(fadeOut * 50L)
                );
                Title titleObj = Title.title(titleComponent, subtitleComponent, times);

                if (Bukkit.isPrimaryThread()) {
                    player.showTitle(titleObj);
                } else {
                    Bukkit.getScheduler().runTask(ReSync.getInstance(), () -> player.showTitle(titleObj));
                }
            }
        });

        operations.put("actionbar_send", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            String text = ctx.getInputValue(node, "text", String.class, "");
            Integer durationTicks = ctx.getInputValue(node, "duration_ticks", Integer.class, 60);

            if (player != null && !text.isEmpty()) {
                Component component = TextFormatter.parse(text);
                if (Bukkit.isPrimaryThread()) {
                    player.sendActionBar(component);
                } else {
                    Bukkit.getScheduler().runTask(ReSync.getInstance(), () -> player.sendActionBar(component));
                }
            }
        });

        operations.put("title_clear", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);

            if (player != null) {
                if (Bukkit.isPrimaryThread()) {
                    player.clearTitle();
                } else {
                    Bukkit.getScheduler().runTask(ReSync.getInstance(), player::clearTitle);
                }
            }
        });

        operations.put("subtitle", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            String subtitle = ctx.getInputValue(node, "subtitle", String.class, "");
            Integer fadeIn = ctx.getInputValue(node, "fade_in", Integer.class, 10);
            Integer stay = ctx.getInputValue(node, "stay", Integer.class, 70);
            Integer fadeOut = ctx.getInputValue(node, "fade_out", Integer.class, 20);

            if (player != null) {
                Component subtitleComponent = TextFormatter.parse(subtitle);
                Title.Times times = Title.Times.of(
                        Duration.ofMillis(fadeIn * 50L),
                        Duration.ofMillis(stay * 50L),
                        Duration.ofMillis(fadeOut * 50L)
                );
                Title titleObj = Title.title(Component.empty(), subtitleComponent, times);

                if (Bukkit.isPrimaryThread()) {
                    player.showTitle(titleObj);
                } else {
                    Bukkit.getScheduler().runTask(ReSync.getInstance(), () -> player.showTitle(titleObj));
                }
            }
        });

        operations.put("times", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            Integer fadeIn = ctx.getInputValue(node, "fade_in", Integer.class, 10);
            Integer stay = ctx.getInputValue(node, "stay", Integer.class, 70);
            Integer fadeOut = ctx.getInputValue(node, "fade_out", Integer.class, 20);

            if (player != null) {
                Title.Times times = Title.Times.of(
                        Duration.ofMillis(fadeIn * 50L),
                        Duration.ofMillis(stay * 50L),
                        Duration.ofMillis(fadeOut * 50L)
                );
                Title titleObj = Title.title(Component.empty(), Component.empty(), times);

                if (Bukkit.isPrimaryThread()) {
                    player.showTitle(titleObj);
                } else {
                    Bukkit.getScheduler().runTask(ReSync.getInstance(), () -> player.showTitle(titleObj));
                }
            }
        });
    }

    public void registerTo(HandlerRegistry registry) {
        registry.register("TitleHandler", this);
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
