package restudio.resync.flow.nodes;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import restudio.flow.data.FlowNode;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.FlowRegistry;

public class WorldNodes {
    public static void registerAll(FlowRegistry registry) {
        registry.register("get_location", (ctx, node) -> {
            Double x = ctx.getInputValue(node, "x", Double.class, 0.0);
            Double y = ctx.getInputValue(node, "y", Double.class, 0.0);
            Double z = ctx.getInputValue(node, "z", Double.class, 0.0);

            World world = null;
            Player player = ctx.getPlayer();
            if (player != null) {
                world = player.getWorld();
            } else if (!Bukkit.getWorlds().isEmpty()) {
                world = Bukkit.getWorlds().get(0);
            }

            if (world == null) {
                return;
            }

            String nodeId = findNodeId(ctx, node);
            if (nodeId == null) {
                return;
            }

            Location location = new Location(world, x, y, z);
            ctx.setNodeOutput(nodeId, "location", location);
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
