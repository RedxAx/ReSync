package restudio.resync.flow.nodes;

import org.bukkit.Bukkit;
import org.bukkit.DyeColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import restudio.flow.data.FlowNode;
import restudio.flow.data.FlowType;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.registry.DefineNode;
import restudio.resync.flow.registry.FlowPin;
import restudio.resync.flow.registry.NodeDefinition;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Random;
import java.util.UUID;

public class RandomNodes {

    private static final Random RANDOM = new Random();

    @DefineNode(id = "random_number", displayName = "Random Number", category = NodeDefinition.NodeCategory.DATA,
            inputs = {
                    @FlowPin(name = "min", dataType = FlowType.NUMBER),
                    @FlowPin(name = "max", dataType = FlowType.NUMBER)
            },
            outputs = {
                    @FlowPin(name = "number", dataType = FlowType.NUMBER),
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)
            })
    public void randomNumber(FlowContext ctx, FlowNode node) {
        Double min = ctx.getInputValue(node, "min", Double.class, 0.0);
        Double max = ctx.getInputValue(node, "max", Double.class, 1.0);
        double value = min + RANDOM.nextDouble() * (max - min);
        ctx.setOutput(node, "number", value);
        ctx.triggerOutput("flow");
    }

    @DefineNode(id = "random_range", displayName = "Random Range", category = NodeDefinition.NodeCategory.DATA,
            inputs = {
                    @FlowPin(name = "min", dataType = FlowType.NUMBER),
                    @FlowPin(name = "max", dataType = FlowType.NUMBER)
            },
            outputs = {
                    @FlowPin(name = "number", dataType = FlowType.NUMBER),
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)
            })
    public void randomRange(FlowContext ctx, FlowNode node) {
        Integer min = ctx.getInputValue(node, "min", Integer.class, 0);
        Integer max = ctx.getInputValue(node, "max", Integer.class, 10);
        int lower = Math.min(min, max);
        int upper = Math.max(min, max);
        int value = lower + RANDOM.nextInt(upper - lower + 1);
        ctx.setOutput(node, "number", value);
        ctx.triggerOutput("flow");
    }

    @DefineNode(id = "random_choice", displayName = "Random Choice", category = NodeDefinition.NodeCategory.DATA,
            inputs = {@FlowPin(name = "list", dataType = FlowType.LIST)},
            outputs = {
                    @FlowPin(name = "element", dataType = FlowType.ANY),
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)
            })
    public void randomChoice(FlowContext ctx, FlowNode node) {
        List<?> list = ctx.getInputValue(node, "list", List.class, null);
        Object value = null;
        if (list != null && !list.isEmpty()) {
            value = list.get(RANDOM.nextInt(list.size()));
        }
        ctx.setOutput(node, "element", value);
        ctx.triggerOutput("flow");
    }

    @DefineNode(id = "random_chance", displayName = "Random Chance", category = NodeDefinition.NodeCategory.LOGIC,
            inputs = {@FlowPin(name = "chance_0_to_100", dataType = FlowType.NUMBER)},
            outputs = {
                    @FlowPin(name = "success", dataType = FlowType.BOOLEAN),
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)
            })
    public void randomChance(FlowContext ctx, FlowNode node) {
        Double chance = ctx.getInputValue(node, "chance_0_to_100", Double.class, 50.0);
        boolean success = RANDOM.nextDouble() * 100 < chance;
        ctx.setOutput(node, "success", success);
        ctx.triggerOutput("flow");
    }

    @DefineNode(id = "random_item", displayName = "Random Item", category = NodeDefinition.NodeCategory.INVENTORY,
            inputs = {@FlowPin(name = "items", dataType = FlowType.LIST)},
            outputs = {
                    @FlowPin(name = "item", dataType = FlowType.ITEMSTACK),
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)
            })
    public void randomItem(FlowContext ctx, FlowNode node) {
        List<?> itemsList = ctx.getInputValue(node, "items", List.class, null);
        ItemStack item = null;
        if (itemsList != null && !itemsList.isEmpty()) {
            Object chosen = itemsList.get(RANDOM.nextInt(itemsList.size()));
            if (chosen instanceof ItemStack itemStack) {
                item = itemStack;
            } else if (chosen instanceof String matName) {
                try {
                    Material material = Material.valueOf(matName);
                    item = new ItemStack(material);
                } catch (IllegalArgumentException e) {
                    item = new ItemStack(Material.STONE);
                }
            }
        }
        ctx.setOutput(node, "item", item);
        ctx.triggerOutput("flow");
    }

    @DefineNode(id = "random_player", displayName = "Random Player", category = NodeDefinition.NodeCategory.PLAYER,
            outputs = {
                    @FlowPin(name = "player", dataType = FlowType.PLAYER),
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)
            })
    public void randomPlayer(FlowContext ctx, FlowNode node) {
        Player player = null;
        Collection<? extends Player> players = Bukkit.getOnlinePlayers();
        if (!players.isEmpty()) {
            List<Player> playerList = new ArrayList<>(players);
            player = playerList.get(RANDOM.nextInt(playerList.size()));
        }
        ctx.setOutput(node, "player", player);
        ctx.triggerOutput("flow");
    }

    @DefineNode(id = "random_uuid", displayName = "Random Uuid", category = NodeDefinition.NodeCategory.DATA,
            outputs = {
                    @FlowPin(name = "uuid", dataType = FlowType.STRING),
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)
            })
    public void randomUuid(FlowContext ctx, FlowNode node) {
        ctx.setOutput(node, "uuid", UUID.randomUUID().toString());
        ctx.triggerOutput("flow");
    }

    @DefineNode(id = "random_color", displayName = "Random Color", category = NodeDefinition.NodeCategory.DATA,
            outputs = {
                    @FlowPin(name = "color", dataType = FlowType.STRING),
                    @FlowPin(name = "dye_color", dataType = FlowType.ANY),
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)
            })
    public void randomColor(FlowContext ctx, FlowNode node) {
        DyeColor[] colors = DyeColor.values();
        DyeColor color = colors[RANDOM.nextInt(colors.length)];
        ctx.setOutput(node, "color", color.name());
        ctx.setOutput(node, "dye_color", color);
        ctx.triggerOutput("flow");
    }
}
