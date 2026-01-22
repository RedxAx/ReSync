package restudio.resync.flow.nodes;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import restudio.flow.data.FlowNode;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.FlowRegistry;
import restudio.resync.flow.NodeCategory;
import restudio.resync.flow.util.TextFormatter;

import java.time.Duration;

public class TitleNodes implements NodeCategory {

    @Override
    public void registerNodes(FlowRegistry registry) {
        registry.register("title_send", (ctx, node) -> {
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
                    Duration.ofMillis(fadeIn * 50),
                    Duration.ofMillis(stay * 50),
                    Duration.ofMillis(fadeOut * 50)
                );
                Title titleObj = Title.title(titleComponent, subtitleComponent, times);

                if (Bukkit.isPrimaryThread()) {
                    player.showTitle(titleObj);
                } else {
                    Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), () -> player.showTitle(titleObj));
                }
            }
            ctx.triggerOutput("flow");
        });

        registry.register("title_clear", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);

            if (player != null) {
                if (Bukkit.isPrimaryThread()) {
                    player.clearTitle();
                } else {
                    Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), () -> player.clearTitle());
                }
            }
            ctx.triggerOutput("flow");
        });

        registry.register("title_action_bar", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            String text = ctx.getInputValue(node, "text", String.class, "");
            Integer durationTicks = ctx.getInputValue(node, "duration_ticks", Integer.class, 60);

            if (player != null && !text.isEmpty()) {
                Component component = TextFormatter.parse(text);
                if (Bukkit.isPrimaryThread()) {
                    player.sendActionBar(component);
                } else {
                    Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), () -> player.sendActionBar(component));
                }
            }
            ctx.triggerOutput("flow");
        });

        registry.register("title_times", (ctx, node) -> {
            Integer fadeIn = ctx.getInputValue(node, "fade_in", Integer.class, 10);
            Integer stay = ctx.getInputValue(node, "stay", Integer.class, 70);
            Integer fadeOut = ctx.getInputValue(node, "fade_out", Integer.class, 20);
            String nodeId = findNodeId(ctx, node);

            Title.Times times = Title.Times.of(
                Duration.ofMillis(fadeIn * 50),
                Duration.ofMillis(stay * 50),
                Duration.ofMillis(fadeOut * 50)
            );
            ctx.setNodeOutput(nodeId, "times", times);
            ctx.triggerOutput("flow");
        });

        registry.register("title_subtitle", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            String subtitle = ctx.getInputValue(node, "subtitle", String.class, "");
            Integer fadeIn = ctx.getInputValue(node, "fade_in", Integer.class, 10);
            Integer stay = ctx.getInputValue(node, "stay", Integer.class, 70);
            Integer fadeOut = ctx.getInputValue(node, "fade_out", Integer.class, 20);

            if (player != null) {
                Component titleComponent = Component.text("");
                Component subtitleComponent = TextFormatter.parse(subtitle);
                Title.Times times = Title.Times.of(
                    Duration.ofMillis(fadeIn * 50),
                    Duration.ofMillis(stay * 50),
                    Duration.ofMillis(fadeOut * 50)
                );
                Title titleObj = Title.title(titleComponent, subtitleComponent, times);

                if (Bukkit.isPrimaryThread()) {
                    player.showTitle(titleObj);
                } else {
                    Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), () -> player.showTitle(titleObj));
                }
            }
            ctx.triggerOutput("flow");
        });
    }

    private static String findNodeId(FlowContext ctx, FlowNode node) {
        for (var entry : ctx.getRuntime().getGraph().getNodes().entrySet()) {
            if (entry.getValue() == node) {
                return entry.getKey();
            }
        }
        return null;
    }
}
