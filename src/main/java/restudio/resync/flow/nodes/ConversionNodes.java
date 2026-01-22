package restudio.resync.flow.nodes;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import restudio.flow.data.FlowNode;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.FlowRegistry;
import restudio.resync.flow.NodeCategory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

public class ConversionNodes implements NodeCategory {
    
    @Override
    public void registerNodes(FlowRegistry registry) {
        registry.register("to_string", (ctx, node) -> {
            Object value = ctx.getInputValue(node, "value");
            String string = value != null ? value.toString() : "";
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "string", string);
        });
        
        registry.register("to_number", (ctx, node) -> {
            Object value = ctx.getInputValue(node, "value");
            Double number = 0.0;
            if (value instanceof Number) {
                number = ((Number) value).doubleValue();
            } else if (value instanceof String) {
                try {
                    number = Double.parseDouble((String) value);
                } catch (NumberFormatException e) {
                    number = 0.0;
                }
            }
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "number", number);
        });
        
        registry.register("to_boolean", (ctx, node) -> {
            Object value = ctx.getInputValue(node, "value");
            Boolean bool = false;
            if (value instanceof Boolean) {
                bool = (Boolean) value;
            } else if (value instanceof String) {
                String str = ((String) value).toLowerCase();
                bool = str.equals("true") || str.equals("yes") || str.equals("1");
            } else if (value instanceof Number) {
                bool = ((Number) value).doubleValue() != 0;
            }
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "boolean", bool);
        });
        
        registry.register("to_player", (ctx, node) -> {
            Object uuidOrName = ctx.getInputValue(node, "uuid_or_name");
            Player player = null;
            
            if (uuidOrName instanceof Player) {
                player = (Player) uuidOrName;
            } else if (uuidOrName instanceof String) {
                String str = (String) uuidOrName;
                try {
                    UUID uuid = UUID.fromString(str);
                    player = Bukkit.getPlayer(uuid);
                } catch (IllegalArgumentException e) {
                    player = Bukkit.getPlayerExact(str);
                }
            }
            
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "player", player);
        });
        
        registry.register("to_location", (ctx, node) -> {
            Double x = ctx.getInputValue(node, "x", Double.class, 0.0);
            Double y = ctx.getInputValue(node, "y", Double.class, 0.0);
            Double z = ctx.getInputValue(node, "z", Double.class, 0.0);
            String world = ctx.getInputValue(node, "world", String.class, "");
            
            Location location = null;
            if (!world.isEmpty()) {
                org.bukkit.World worldObj = Bukkit.getWorld(world);
                if (worldObj != null) {
                    location = new Location(worldObj, x, y, z);
                }
            } else if (!Bukkit.getWorlds().isEmpty()) {
                location = new Location(Bukkit.getWorlds().get(0), x, y, z);
            }
            
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "location", location);
        });
        
        registry.register("to_item", (ctx, node) -> {
            String material = ctx.getInputValue(node, "material", String.class, "STONE");
            Integer amount = ctx.getInputValue(node, "amount", Integer.class, 1);
            
            ItemStack item = null;
            try {
                Material mat = Material.valueOf(material.toUpperCase());
                item = new ItemStack(mat, amount);
            } catch (IllegalArgumentException e) {
                item = new ItemStack(Material.STONE, amount);
            }
            
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "item", item);
        });
        
        registry.register("to_list", (ctx, node) -> {
            Object valueOrSeparator = ctx.getInputValue(node, "value_or_separator");
            List<Object> list = new ArrayList<>();
            
            if (valueOrSeparator instanceof List) {
                list = (List<Object>) valueOrSeparator;
            } else if (valueOrSeparator instanceof String) {
                String str = (String) valueOrSeparator;
                list = Arrays.asList((Object[]) str.split(","));
            } else if (valueOrSeparator instanceof Object[]) {
                list = Arrays.asList((Object[]) valueOrSeparator);
            } else if (valueOrSeparator != null) {
                list.add(valueOrSeparator);
            }
            
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "list", list);
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
