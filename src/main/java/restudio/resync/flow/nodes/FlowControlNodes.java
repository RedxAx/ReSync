package restudio.resync.flow.nodes;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import restudio.flow.data.FlowType;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.registry.DefineNode;
import restudio.resync.flow.registry.FlowPin;
import restudio.resync.flow.registry.NodeDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class FlowControlNodes {

    @DefineNode(id = "if", displayName = "If", category = NodeDefinition.NodeCategory.LOGIC,
            inputs = {@FlowPin(name = "condition", dataType = FlowType.BOOLEAN)},
            outputs = {@FlowPin(name = "true", type = NodeDefinition.PinType.FLOW), @FlowPin(name = "false", type = NodeDefinition.PinType.FLOW)})
    public void ifNode(FlowContext ctx, restudio.flow.data.FlowNode node) {
        Boolean condition = ctx.getInputValue(node, "condition", Boolean.class, false);
        ctx.triggerOutput(Boolean.TRUE.equals(condition) ? "true" : "false");
    }

    @DefineNode(id = "switch_case", displayName = "Switch", category = NodeDefinition.NodeCategory.LOGIC,
            inputs = {@FlowPin(name = "value", dataType = FlowType.ANY), @FlowPin(name = "cases", dataType = FlowType.LIST)},
            outputs = {
                    @FlowPin(name = "default", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "matched", dataType = FlowType.BOOLEAN),
                    @FlowPin(name = "index", dataType = FlowType.NUMBER)
            })
    public void switchCase(FlowContext ctx, restudio.flow.data.FlowNode node) {
        Object value = ctx.getInputValue(node, "value", null);
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
    }

    @DefineNode(id = "branch_random", displayName = "Random Branch", category = NodeDefinition.NodeCategory.LOGIC,
            inputs = {@FlowPin(name = "branches", dataType = FlowType.NUMBER)},
            outputs = {@FlowPin(name = "selected", dataType = FlowType.NUMBER), @FlowPin(name = "branch", type = NodeDefinition.PinType.FLOW)})
    public void branchRandom(FlowContext ctx, restudio.flow.data.FlowNode node) {
        int branches = ctx.getInputValue(node, "branches", Integer.class, 2);
        int selected = (int) (Math.random() * branches);
        ctx.setOutput(node, "selected", selected);
        ctx.triggerOutput("branch_" + selected);
    }

    @DefineNode(id = "branch_all", displayName = "Branch All", category = NodeDefinition.NodeCategory.LOGIC,
            outputs = {
                    @FlowPin(name = "branch_0", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "branch_1", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "branch_2", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "branch_3", type = NodeDefinition.PinType.FLOW)
            })
    public void branchAll(FlowContext ctx, restudio.flow.data.FlowNode node) {
        ctx.triggerOutput("branch_0");
        ctx.triggerOutput("branch_1");
        ctx.triggerOutput("branch_2");
        ctx.triggerOutput("branch_3");
    }

    @DefineNode(id = "loop_count", displayName = "Loop Count", category = NodeDefinition.NodeCategory.LOGIC,
            inputs = {@FlowPin(name = "count", dataType = FlowType.NUMBER)},
            outputs = {
                    @FlowPin(name = "loop", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "index", dataType = FlowType.NUMBER),
                    @FlowPin(name = "completed", type = NodeDefinition.PinType.FLOW, dataType = FlowType.BOOLEAN)
            })
    public void loopCount(FlowContext ctx, restudio.flow.data.FlowNode node) {
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
    }

    @DefineNode(id = "loop_for_each", displayName = "For Each", category = NodeDefinition.NodeCategory.LOGIC,
            inputs = {@FlowPin(name = "list", dataType = FlowType.LIST)},
            outputs = {
                    @FlowPin(name = "loop", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "index", dataType = FlowType.NUMBER),
                    @FlowPin(name = "element", dataType = FlowType.ANY),
                    @FlowPin(name = "completed", type = NodeDefinition.PinType.FLOW, dataType = FlowType.BOOLEAN)
            })
    public void loopForEach(FlowContext ctx, restudio.flow.data.FlowNode node) {
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
    }

    @DefineNode(id = "loop_for_each_player", displayName = "For Each Player", category = NodeDefinition.NodeCategory.LOGIC,
            outputs = {
                    @FlowPin(name = "loop", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "index", dataType = FlowType.NUMBER),
                    @FlowPin(name = "player", dataType = FlowType.PLAYER),
                    @FlowPin(name = "completed", type = NodeDefinition.PinType.FLOW, dataType = FlowType.BOOLEAN)
            })
    public void loopForEachPlayer(FlowContext ctx, restudio.flow.data.FlowNode node) {
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
    }

    @DefineNode(id = "loop_for_each_entity", displayName = "For Each Entity", category = NodeDefinition.NodeCategory.LOGIC,
            inputs = {@FlowPin(name = "radius", dataType = FlowType.NUMBER), @FlowPin(name = "center", dataType = FlowType.LOCATION)},
            outputs = {
                    @FlowPin(name = "loop", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "index", dataType = FlowType.NUMBER),
                    @FlowPin(name = "entity", dataType = FlowType.ENTITY),
                    @FlowPin(name = "completed", type = NodeDefinition.PinType.FLOW, dataType = FlowType.BOOLEAN)
            })
    public void loopForEachEntity(FlowContext ctx, restudio.flow.data.FlowNode node) {
        Double radius = ctx.getInputValue(node, "radius", Double.class, 10.0);
        org.bukkit.Location center = ctx.getInputValue(node, "center", org.bukkit.Location.class,
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
    }

    @DefineNode(id = "break_loop", displayName = "Break", category = NodeDefinition.NodeCategory.LOGIC,
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void breakLoop(FlowContext ctx, restudio.flow.data.FlowNode node) {
        ctx.getRuntime().setBreakLoopRequested(true);
        ctx.triggerOutput("flow");
    }

    @DefineNode(id = "continue_loop", displayName = "Continue", category = NodeDefinition.NodeCategory.LOGIC,
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void continueLoop(FlowContext ctx, restudio.flow.data.FlowNode node) {
        ctx.getRuntime().setContinueLoopRequested(true);
        ctx.triggerOutput("flow");
    }
}
