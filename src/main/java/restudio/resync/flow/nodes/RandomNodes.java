package restudio.resync.flow.nodes;

import org.bukkit.Bukkit;
import org.bukkit.DyeColor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import restudio.flow.data.FlowNode;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.FlowRegistry;
import restudio.resync.flow.NodeCategory;

import java.util.List;
import java.util.UUID;
import java.util.Random;

public class RandomNodes implements NodeCategory {
    
    private static final Random RANDOM = new Random();
    
    @Override
    public void registerNodes(FlowRegistry registry) {
        registry.register("random_number", (ctx, node) -> {
            Double min = ctx.getInputValue(node, "min", Double.class, 0.0);
            Double max = ctx.getInputValue(node, "max", Double.class, 1.0);
            double value = min + RANDOM.nextDouble() * (max - min);
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "number", value);
            ctx.triggerOutput("flow");
        });
        
        registry.register("random_range", (ctx, node) -> {
            Integer min = ctx.getInputValue(node, "min", Integer.class, 0);
            Integer max = ctx.getInputValue(node, "max", Integer.class, 10);
            int value = min + RANDOM.nextInt(max - min + 1);
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "number", value);
            ctx.triggerOutput("flow");
        });
        
        registry.register("random_choice", (ctx, node) -> {
            List<?> list = ctx.getInputValue(node, "list", List.class, null);
            Object value = null;
            if (list != null && !list.isEmpty()) {
                value = list.get(RANDOM.nextInt(list.size()));
            }
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "element", value);
            ctx.triggerOutput("flow");
        });
        
        registry.register("random_chance", (ctx, node) -> {
            Double chance = ctx.getInputValue(node, "chance_0_to_100", Double.class, 50.0);
            boolean success = RANDOM.nextDouble() * 100 < chance;
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "success", success);
            ctx.triggerOutput("flow");
        });
        
        registry.register("random_item", (ctx, node) -> {
            List<?> itemsList = ctx.getInputValue(node, "items", List.class, null);
            ItemStack item = null;
            if (itemsList != null && !itemsList.isEmpty()) {
                Object chosen = itemsList.get(RANDOM.nextInt(itemsList.size()));
                if (chosen instanceof ItemStack) {
                    item = (ItemStack) chosen;
                } else if (chosen instanceof String) {
                    try {
                        org.bukkit.Material mat = org.bukkit.Material.valueOf((String) chosen);
                        item = new ItemStack(mat);
                    } catch (IllegalArgumentException e) {
                        item = new ItemStack(org.bukkit.Material.STONE);
                    }
                }
            }
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "item", item);
            ctx.triggerOutput("flow");
        });
        
        registry.register("random_player", (ctx, node) -> {
            Player player = null;
            java.util.Collection<? extends Player> players = Bukkit.getOnlinePlayers();
            if (!players.isEmpty()) {
                java.util.List<Player> playerList = new java.util.ArrayList<>(players);
                player = playerList.get(RANDOM.nextInt(playerList.size()));
            }
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "player", player);
            ctx.triggerOutput("flow");
        });
        
        registry.register("random_uuid", (ctx, node) -> {
            String uuid = UUID.randomUUID().toString();
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "uuid", uuid);
            ctx.triggerOutput("flow");
        });
        
        registry.register("random_color", (ctx, node) -> {
            DyeColor[] colors = DyeColor.values();
            DyeColor color = colors[RANDOM.nextInt(colors.length)];
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "color", color.name());
            ctx.setNodeOutput(nodeId, "dye_color", color);
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
