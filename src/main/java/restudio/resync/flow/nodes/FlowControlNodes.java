package restudio.resync.flow.nodes;

import restudio.flow.data.FlowNode;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.FlowRegistry;
import restudio.resync.flow.NodeCategory;

import java.util.concurrent.CompletableFuture;
import java.util.List;

public class FlowControlNodes implements NodeCategory {
    
    @Override
    public void registerNodes(FlowRegistry registry) {
        registry.register("if_else", (ctx, node) -> {
            Boolean condition = ctx.getInputValue(node, "condition", Boolean.class, false);
            if (condition) {
                ctx.triggerOutput("true");
            } else {
                ctx.triggerOutput("false");
            }
        });
        
        registry.register("switch_case", (ctx, node) -> {
            Object value = ctx.getInputValue(node, "value", null);
            List<String> cases = ctx.getInputValue(node, "cases", List.class, List.of());
            String nodeId = findNodeId(ctx, node);
            
            for (int i = 0; i < cases.size(); i++) {
                if (String.valueOf(cases.get(i)).equals(String.valueOf(value))) {
                    ctx.setNodeOutput(nodeId, "matched", true);
                    ctx.setNodeOutput(nodeId, "index", i);
                    ctx.triggerOutput("case_" + i);
                    return;
                }
            }
            
            ctx.setNodeOutput(nodeId, "matched", false);
            ctx.setNodeOutput(nodeId, "index", -1);
            ctx.triggerOutput("default");
        });
        
        registry.register("branch_random", (ctx, node) -> {
            int branches = ctx.getInputValue(node, "branches", Integer.class, 2);
            int selected = (int) (Math.random() * branches);
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "selected", selected);
            ctx.triggerOutput("branch_" + selected);
        });
        
        registry.register("branch_all", (ctx, node) -> {
            ctx.triggerOutput("branch_0");
            ctx.triggerOutput("branch_1");
            ctx.triggerOutput("branch_2");
            ctx.triggerOutput("branch_3");
        });
        
        registry.register("loop_count", (ctx, node) -> {
            int count = ctx.getInputValue(node, "count", Integer.class, 1);
            String nodeId = findNodeId(ctx, node);
            
            CompletableFuture<Void> loopFuture = CompletableFuture.completedFuture(null);
            for (int i = 0; i < count; i++) {
                final int index = i;
                loopFuture = loopFuture.thenCompose(v -> {
                    ctx.setNodeOutput(nodeId, "index", index);
                    return ctx.runLater(() -> ctx.triggerOutput("loop"), 1);
                });
            }
            
            loopFuture.thenRun(() -> {
                ctx.setNodeOutput(nodeId, "completed", true);
                ctx.triggerOutput("completed");
            });
        });
        
        registry.register("loop_for_each", (ctx, node) -> {
            List<?> list = ctx.getInputValue(node, "list", List.class, List.of());
            String nodeId = findNodeId(ctx, node);
            
            if (list.isEmpty()) {
                ctx.setNodeOutput(nodeId, "completed", true);
                ctx.triggerOutput("completed");
                return;
            }
            
            CompletableFuture<Void> loopFuture = CompletableFuture.completedFuture(null);
            for (int i = 0; i < list.size(); i++) {
                final int index = i;
                final Object element = list.get(i);
                loopFuture = loopFuture.thenCompose(v -> {
                    ctx.setNodeOutput(nodeId, "index", index);
                    ctx.setNodeOutput(nodeId, "element", element);
                    return ctx.runLater(() -> ctx.triggerOutput("loop"), 1);
                });
            }
            
            loopFuture.thenRun(() -> {
                ctx.setNodeOutput(nodeId, "completed", true);
                ctx.triggerOutput("completed");
            });
        });
        
        registry.register("loop_for_each_player", (ctx, node) -> {
            List<org.bukkit.entity.Player> players = new java.util.ArrayList<>(org.bukkit.Bukkit.getOnlinePlayers());
            String nodeId = findNodeId(ctx, node);
            
            if (players.isEmpty()) {
                ctx.setNodeOutput(nodeId, "completed", true);
                ctx.triggerOutput("completed");
                return;
            }
            
            CompletableFuture<Void> loopFuture = CompletableFuture.completedFuture(null);
            for (int i = 0; i < players.size(); i++) {
                final int index = i;
                final org.bukkit.entity.Player player = players.get(i);
                loopFuture = loopFuture.thenCompose(v -> {
                    ctx.setNodeOutput(nodeId, "index", index);
                    ctx.setNodeOutput(nodeId, "player", player);
                    return ctx.runLater(() -> ctx.triggerOutput("loop"), 1);
                });
            }
            
            loopFuture.thenRun(() -> {
                ctx.setNodeOutput(nodeId, "completed", true);
                ctx.triggerOutput("completed");
            });
        });
        
        registry.register("loop_for_each_entity", (ctx, node) -> {
            Double radius = ctx.getInputValue(node, "radius", Double.class, 10.0);
            org.bukkit.Location center = ctx.getInputValue(node, "center", org.bukkit.Location.class, 
                ctx.getPlayer() != null ? ctx.getPlayer().getLocation() : null);
            
            if (center == null) {
                ctx.triggerOutput("completed");
                return;
            }
            
            List<org.bukkit.entity.Entity> entities = new java.util.ArrayList<>(
                center.getWorld().getNearbyEntities(center, radius, radius, radius));
            String nodeId = findNodeId(ctx, node);
            
            if (entities.isEmpty()) {
                ctx.setNodeOutput(nodeId, "completed", true);
                ctx.triggerOutput("completed");
                return;
            }
            
            CompletableFuture<Void> loopFuture = CompletableFuture.completedFuture(null);
            for (int i = 0; i < entities.size(); i++) {
                final int index = i;
                final org.bukkit.entity.Entity entity = entities.get(i);
                loopFuture = loopFuture.thenCompose(v -> {
                    ctx.setNodeOutput(nodeId, "index", index);
                    ctx.setNodeOutput(nodeId, "entity", entity);
                    return ctx.runLater(() -> ctx.triggerOutput("loop"), 1);
                });
            }
            
            loopFuture.thenRun(() -> {
                ctx.setNodeOutput(nodeId, "completed", true);
                ctx.triggerOutput("completed");
            });
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
