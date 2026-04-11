package restudio.resync.flow.nodes;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import restudio.flow.data.FlowNode;
import restudio.flow.data.FlowType;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.FlowRegistry;
import restudio.resync.flow.registry.DefineNode;
import restudio.resync.flow.registry.FlowPin;
import restudio.resync.flow.registry.NodeDefinition;
import restudio.resync.flow.util.TextFormatter;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

public class TitleNodes {

    private static final Map<String, BiConsumer<FlowContext, FlowNode>> LEGACY_EXECUTORS = new ConcurrentHashMap<>();
    private static volatile boolean initialized;

    private static void registerLegacyNodes(FlowRegistry registry) {
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
                    Duration.ofMillis(fadeIn * 50L),
                    Duration.ofMillis(stay * 50L),
                    Duration.ofMillis(fadeOut * 50L)
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
                    Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), player::clearTitle);
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
                Duration.ofMillis(fadeIn * 50L),
                Duration.ofMillis(stay * 50L),
                Duration.ofMillis(fadeOut * 50L)
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
                    Duration.ofMillis(fadeIn * 50L),
                    Duration.ofMillis(stay * 50L),
                    Duration.ofMillis(fadeOut * 50L)
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

    public void registerNodes(FlowRegistry registry) {
        registerLegacyNodes(registry);
    }

    private static void ensureLegacyInitialized() {
        if (initialized) {
            return;
        }
        synchronized (TitleNodes.class) {
            if (initialized) {
                return;
            }
            FlowRegistry tempRegistry = new FlowRegistry();
            registerLegacyNodes(tempRegistry);
            for (String type : tempRegistry.getRegisteredTypes()) {
                LEGACY_EXECUTORS.put(type, tempRegistry.getExecutor(type));
            }
            initialized = true;
        }
    }

    private static void executeLegacy(String id, FlowContext ctx, FlowNode node) {
        ensureLegacyInitialized();
        BiConsumer<FlowContext, FlowNode> executor = LEGACY_EXECUTORS.get(id);
        if (executor != null) {
            executor.accept(ctx, node);
        } else {
            ctx.triggerOutput("flow");
        }
    }

    @DefineNode(
        id = "title_send",
        displayName = "Send Title",
        category = NodeDefinition.NodeCategory.VISUAL,
        inputs = {
            @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
            @FlowPin(name = "player", dataType = FlowType.PLAYER),
            @FlowPin(name = "title", dataType = FlowType.STRING),
            @FlowPin(name = "subtitle", dataType = FlowType.STRING),
            @FlowPin(name = "fade_in", dataType = FlowType.NUMBER),
            @FlowPin(name = "stay", dataType = FlowType.NUMBER),
            @FlowPin(name = "fade_out", dataType = FlowType.NUMBER)
        },
        outputs = {
            @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)
        }
    )
    public void titleSend(FlowContext ctx, FlowNode node) {
        executeLegacy("title_send", ctx, node);
    }

    @DefineNode(
        id = "title_clear",
        displayName = "Clear Title",
        category = NodeDefinition.NodeCategory.VISUAL,
        inputs = {
            @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
            @FlowPin(name = "player", dataType = FlowType.PLAYER)
        },
        outputs = {
            @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)
        }
    )
    public void titleClear(FlowContext ctx, FlowNode node) {
        executeLegacy("title_clear", ctx, node);
    }

    @DefineNode(
        id = "title_action_bar",
        displayName = "Show Action Bar",
        category = NodeDefinition.NodeCategory.VISUAL,
        inputs = {
            @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
            @FlowPin(name = "player", dataType = FlowType.PLAYER),
            @FlowPin(name = "text", dataType = FlowType.STRING),
            @FlowPin(name = "duration_ticks", dataType = FlowType.NUMBER)
        },
        outputs = {
            @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)
        }
    )
    public void titleActionBar(FlowContext ctx, FlowNode node) {
        executeLegacy("title_action_bar", ctx, node);
    }

    @DefineNode(
        id = "title_times",
        displayName = "Set Title Times",
        category = NodeDefinition.NodeCategory.VISUAL,
        inputs = {
            @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
            @FlowPin(name = "fade_in", dataType = FlowType.NUMBER),
            @FlowPin(name = "stay", dataType = FlowType.NUMBER),
            @FlowPin(name = "fade_out", dataType = FlowType.NUMBER)
        },
        outputs = {
            @FlowPin(name = "times", dataType = FlowType.ANY),
            @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)
        }
    )
    public void titleTimes(FlowContext ctx, FlowNode node) {
        executeLegacy("title_times", ctx, node);
    }

    @DefineNode(
        id = "title_subtitle",
        displayName = "Send Subtitle",
        category = NodeDefinition.NodeCategory.VISUAL,
        inputs = {
            @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
            @FlowPin(name = "player", dataType = FlowType.PLAYER),
            @FlowPin(name = "subtitle", dataType = FlowType.STRING),
            @FlowPin(name = "fade_in", dataType = FlowType.NUMBER),
            @FlowPin(name = "stay", dataType = FlowType.NUMBER),
            @FlowPin(name = "fade_out", dataType = FlowType.NUMBER)
        },
        outputs = {
            @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)
        }
    )
    public void titleSubtitle(FlowContext ctx, FlowNode node) {
        executeLegacy("title_subtitle", ctx, node);
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
