package restudio.resync.flow.handler.generic;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import restudio.flow.data.FlowNode;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.NodeHandler;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

public class ConversionHandler implements NodeHandler {
    private final ConcurrentHashMap<String, BiConsumer<FlowContext, FlowNode>> operations = new ConcurrentHashMap<>();

    public ConversionHandler() {
        operations.put("to_string", (ctx, node) -> {
            Object value = ctx.getInputValue(node, "value", Object.class, null);
            String string = value != null ? value.toString() : "";
            ctx.setOutput(node, "string", string);
        });

        operations.put("to_number", (ctx, node) -> {
            Object value = ctx.getInputValue(node, "value", Object.class, null);
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
            ctx.setOutput(node, "number", number);
        });

        operations.put("to_boolean", (ctx, node) -> {
            Object value = ctx.getInputValue(node, "value", Object.class, null);
            Boolean bool = false;
            if (value instanceof Boolean) {
                bool = (Boolean) value;
            } else if (value instanceof String) {
                String str = ((String) value).toLowerCase();
                bool = str.equals("true") || str.equals("yes") || str.equals("1");
            } else if (value instanceof Number) {
                bool = ((Number) value).doubleValue() != 0;
            }
            ctx.setOutput(node, "boolean", bool);
        });

        operations.put("to_player", (ctx, node) -> {
            Object uuidOrName = ctx.getInputValue(node, "uuid_or_name", Object.class, null);
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
            ctx.setOutput(node, "player", player);
        });

        operations.put("to_location", (ctx, node) -> {
            Double x = ctx.getInputValue(node, "x", Double.class, 0.0);
            Double y = ctx.getInputValue(node, "y", Double.class, 0.0);
            Double z = ctx.getInputValue(node, "z", Double.class, 0.0);
            String world = ctx.getInputValue(node, "world", String.class, "");
            Location location = null;
            if (!world.isEmpty()) {
                World worldObj = Bukkit.getWorld(world);
                if (worldObj != null) {
                    location = new Location(worldObj, x, y, z);
                }
            } else if (!Bukkit.getWorlds().isEmpty()) {
                location = new Location(Bukkit.getWorlds().getFirst(), x, y, z);
            }
            ctx.setOutput(node, "location", location);
        });

        operations.put("to_item", (ctx, node) -> {
            String material = ctx.getInputValue(node, "material", String.class, "STONE");
            Integer amount = ctx.getInputValue(node, "amount", Integer.class, 1);
            ItemStack item;
            try {
                Material mat = Material.valueOf(material.toUpperCase());
                item = new ItemStack(mat, amount);
            } catch (IllegalArgumentException e) {
                item = new ItemStack(Material.STONE, amount);
            }
            ctx.setOutput(node, "item", item);
        });

        operations.put("to_list", (ctx, node) -> {
            Object valueOrSeparator = ctx.getInputValue(node, "value_or_separator", Object.class, null);
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
            ctx.setOutput(node, "list", list);
        });
    }

    public void registerTo(HandlerRegistry registry) {
        registry.register("ConversionHandler", this);
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
