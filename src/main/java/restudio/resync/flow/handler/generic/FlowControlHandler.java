package restudio.resync.flow.handler.generic;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import restudio.flow.data.FlowNode;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.NodeHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

public class FlowControlHandler implements NodeHandler {
    private final Map<String, BiConsumer<FlowContext, FlowNode>> operations = new ConcurrentHashMap<>();

    public FlowControlHandler() {
        operations.put("if", (ctx, node) -> {
            Boolean condition = ctx.getInputValue(node, "condition", Boolean.class, false);
            ctx.triggerOutput(Boolean.TRUE.equals(condition) ? "true" : "false");
        });

        operations.put("switch_case", (ctx, node) -> {
            Object value = ctx.getInputValue(node, "value", Object.class, null);
            List<String> cases = ctx.getInputValue(node, "cases", List.class, List.of());
            for (int i = 0; i < cases.size(); i++) {
                if (String.valueOf(cases.get(i)).equals(String.valueOf(value))) {
                    ctx.setOutput(node, "matched", true);
                    ctx.setOutput(node, "index", i);
                    ctx.triggerOutput("case_" + i);
                    return;
                }
            }
            ctx.setOutput(node, "matched", false);
            ctx.setOutput(node, "index", -1);
            ctx.triggerOutput("default");
        });

        operations.put("branch_random", (ctx, node) -> {
            int branches = ctx.getInputValue(node, "branches", Integer.class, 2);
            int selected = (int) (Math.random() * branches);
            ctx.setOutput(node, "selected", selected);
            ctx.triggerOutput("branch_" + selected);
        });

        operations.put("branch_all", (ctx, node) -> {
            ctx.triggerOutput("branch_0");
            ctx.triggerOutput("branch_1");
            ctx.triggerOutput("branch_2");
            ctx.triggerOutput("branch_3");
        });

        operations.put("loop_count", (ctx, node) -> {
            int count = ctx.getInputValue(node, "count", Integer.class, 1);
            ctx.getRuntime().resetLoopControl();
            CompletableFuture<Void> loopFuture = CompletableFuture.completedFuture(null);
            for (int i = 0; i < count; i++) {
                final int index = i;
                loopFuture = loopFuture.thenCompose(v -> {
                    if (ctx.getRuntime().isBreakLoopRequested()) return CompletableFuture.completedFuture(null);
                    ctx.setOutput(node, "index", index);
                    ctx.getRuntime().resetLoopControl();
                    return ctx.runLater(() -> ctx.triggerOutput("loop"), 1);
                });
            }
            loopFuture.thenRun(() -> {
                ctx.getRuntime().resetLoopControl();
                ctx.setOutput(node, "completed", true);
                ctx.triggerOutput("completed");
            });
        });

        operations.put("loop_for_each", (ctx, node) -> {
            List<?> list = ctx.getInputValue(node, "list", List.class, List.of());
            ctx.getRuntime().resetLoopControl();
            if (list.isEmpty()) {
                ctx.getRuntime().resetLoopControl();
                ctx.setOutput(node, "completed", true);
                ctx.triggerOutput("completed");
                return;
            }
            CompletableFuture<Void> loopFuture = CompletableFuture.completedFuture(null);
            for (int i = 0; i < list.size(); i++) {
                final int index = i;
                final Object element = list.get(i);
                loopFuture = loopFuture.thenCompose(v -> {
                    if (ctx.getRuntime().isBreakLoopRequested()) return CompletableFuture.completedFuture(null);
                    if (ctx.getRuntime().isContinueLoopRequested()) return CompletableFuture.completedFuture(null);
                    ctx.setOutput(node, "index", index);
                    ctx.setOutput(node, "element", element);
                    ctx.getRuntime().resetLoopControl();
                    return ctx.runLater(() -> ctx.triggerOutput("loop"), 1);
                });
            }
            loopFuture.thenRun(() -> {
                ctx.getRuntime().resetLoopControl();
                ctx.setOutput(node, "completed", true);
                ctx.triggerOutput("completed");
            });
        });

        operations.put("loop_for_each_player", (ctx, node) -> {
            List<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());
            ctx.getRuntime().resetLoopControl();
            if (players.isEmpty()) {
                ctx.getRuntime().resetLoopControl();
                ctx.setOutput(node, "completed", true);
                ctx.triggerOutput("completed");
                return;
            }
            CompletableFuture<Void> loopFuture = CompletableFuture.completedFuture(null);
            for (int i = 0; i < players.size(); i++) {
                final int index = i;
                final Player player = players.get(i);
                loopFuture = loopFuture.thenCompose(v -> {
                    if (ctx.getRuntime().isBreakLoopRequested()) return CompletableFuture.completedFuture(null);
                    if (ctx.getRuntime().isContinueLoopRequested()) return CompletableFuture.completedFuture(null);
                    ctx.setOutput(node, "index", index);
                    ctx.setOutput(node, "player", player);
                    ctx.getRuntime().resetLoopControl();
                    return ctx.runLater(() -> ctx.triggerOutput("loop"), 1);
                });
            }
            loopFuture.thenRun(() -> {
                ctx.getRuntime().resetLoopControl();
                ctx.setOutput(node, "completed", true);
                ctx.triggerOutput("completed");
            });
        });

        operations.put("loop_for_each_entity", (ctx, node) -> {
            Double radius = ctx.getInputValue(node, "radius", Double.class, 10.0);
            Location center = ctx.getInputValue(node, "center", Location.class,
                    ctx.getPlayer() != null ? ctx.getPlayer().getLocation() : null);
            if (center == null) {
                ctx.triggerOutput("completed");
                return;
            }
            List<Entity> entities = new ArrayList<>(center.getWorld().getNearbyEntities(center, radius, radius, radius));
            ctx.getRuntime().resetLoopControl();
            if (entities.isEmpty()) {
                ctx.getRuntime().resetLoopControl();
                ctx.setOutput(node, "completed", true);
                ctx.triggerOutput("completed");
                return;
            }
            CompletableFuture<Void> loopFuture = CompletableFuture.completedFuture(null);
            for (int i = 0; i < entities.size(); i++) {
                final int index = i;
                final Entity entity = entities.get(i);
                loopFuture = loopFuture.thenCompose(v -> {
                    if (ctx.getRuntime().isBreakLoopRequested()) return CompletableFuture.completedFuture(null);
                    if (ctx.getRuntime().isContinueLoopRequested()) return CompletableFuture.completedFuture(null);
                    ctx.setOutput(node, "index", index);
                    ctx.setOutput(node, "entity", entity);
                    ctx.getRuntime().resetLoopControl();
                    return ctx.runLater(() -> ctx.triggerOutput("loop"), 1);
                });
            }
            loopFuture.thenRun(() -> {
                ctx.getRuntime().resetLoopControl();
                ctx.setOutput(node, "completed", true);
                ctx.triggerOutput("completed");
            });
        });

        operations.put("break_loop", (ctx, node) -> {
            ctx.getRuntime().setBreakLoopRequested(true);
            ctx.triggerOutput("flow");
        });

        operations.put("continue_loop", (ctx, node) -> {
            ctx.getRuntime().setContinueLoopRequested(true);
            ctx.triggerOutput("flow");
        });
    }

    public void registerTo(HandlerRegistry registry) {
        registry.register("FlowControlHandler", this);
    }

    @Override
    public void execute(FlowContext ctx, FlowNode node) {
        String operation = node.getHandlerConfig().getString("operation");
        BiConsumer<FlowContext, FlowNode> op = operation != null ? operations.get(operation) : null;
        if (op != null) {
            op.accept(ctx, node);
        } else {
            ctx.triggerOutput("flow");
        }
    }
}
