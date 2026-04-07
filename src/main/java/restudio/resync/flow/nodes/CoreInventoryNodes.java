package restudio.resync.flow.nodes;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import restudio.flow.data.FlowNode;
import restudio.flow.data.FlowType;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.registry.DefineNode;
import restudio.resync.flow.registry.FlowPin;
import restudio.resync.flow.registry.NodeDefinition;

public class CoreInventoryNodes {

    @DefineNode(id = "player_has_item", displayName = "Player Has Item", category = NodeDefinition.NodeCategory.INVENTORY,
            inputs = {@FlowPin(name = "material", dataType = FlowType.STRING), @FlowPin(name = "amount", dataType = FlowType.NUMBER)},
            outputs = {
                    @FlowPin(name = "has", dataType = FlowType.BOOLEAN),
                    @FlowPin(name = "count", dataType = FlowType.NUMBER),
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)
            })
    public void playerHasItem(FlowContext ctx, FlowNode node) {
        Player player = ctx.getPlayer();
        if (player == null) return;

        String matName = ctx.getInputValue(node, "material", String.class, "STONE");
        Material mat = Material.getMaterial(matName.toUpperCase());
        if (mat == null) return;

        PlayerInventory inv = player.getInventory();
        boolean hasItem = false;
        int count = 0;
        for (ItemStack item : inv.getContents()) {
            if (item != null && item.getType() == mat) {
                hasItem = true;
                count += item.getAmount();
            }
        }

        ctx.setOutput(node, "has", hasItem);
        ctx.setOutput(node, "count", count);
        ctx.triggerOutput("flow");
    }

    @DefineNode(id = "player_remove_item", displayName = "Player Remove Item", category = NodeDefinition.NodeCategory.INVENTORY,
            inputs = {@FlowPin(name = "material", dataType = FlowType.STRING), @FlowPin(name = "amount", dataType = FlowType.NUMBER)},
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void playerRemoveItem(FlowContext ctx, FlowNode node) {
        Player player = ctx.getPlayer();
        if (player == null) return;

        String matName = ctx.getInputValue(node, "material", String.class, "STONE");
        int amount = ctx.getInputValue(node, "amount", Integer.class, 1);
        Material mat = Material.getMaterial(matName.toUpperCase());
        if (mat == null) return;

        ItemStack toRemove = new ItemStack(mat, amount);
        player.getInventory().removeItem(toRemove);
        ctx.triggerOutput("flow");
    }

    @DefineNode(id = "player_clear_inv", displayName = "Player Clear Inventory", category = NodeDefinition.NodeCategory.INVENTORY,
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void playerClearInv(FlowContext ctx, FlowNode node) {
        Player player = ctx.getPlayer();
        if (player == null) return;
        player.getInventory().clear();
        ctx.triggerOutput("flow");
    }
}
