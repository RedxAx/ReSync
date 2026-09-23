package restudio.resync.flow;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import restudio.flow.data.FlowConnection;
import restudio.flow.data.FlowNode;
import restudio.resync.flow.registry.NodeDefinition;

import java.util.List;
import java.util.function.Consumer;

public final class ItemWriteback {
    private ItemWriteback() {
    }

    public static Consumer<ItemStack> resolve(FlowContext context, FlowNode node, ItemStack item) {
        FlowRuntime runtime = context.getRuntime();
        String nodeId = runtime == null ? null : runtime.findNodeId(node);
        if (nodeId != null && runtime.getGraph() != null) {
            for (FlowConnection connection : runtime.getGraph().getConnectionsToTarget(nodeId)) {
                if (!"target".equals(runtime.normalizeInputPin(node, connection.getTargetPin()))) {
                    continue;
                }
                FlowNode source = runtime.getGraph().getNodes().get(connection.getSourceNodeId());
                if (source == null) {
                    continue;
                }
                NodeDefinition definition = runtime.getDefinition(source);
                if (definition == null || !"player".equals(definition.getHandler())) {
                    continue;
                }
                String sourcePin = runtime.normalizeOutputPin(source, connection.getSourcePin());
                Player player = player(context, runtime, source);
                if (player != null && List.of("item_in_hand", "item_in_mainhand").contains(sourcePin)) {
                    return updated -> player.getInventory().setItemInMainHand(updated);
                }
                if (player != null && List.of("offhand_item", "item_in_offhand").contains(sourcePin)) {
                    return updated -> player.getInventory().setItemInOffHand(updated);
                }
            }
        }
        Player player = context.getPlayer();
        if (player == null) {
            return ignored -> {};
        }
        ItemStack baseline = item.clone();
        if (same(player.getInventory().getItemInMainHand(), baseline)) {
            return updated -> player.getInventory().setItemInMainHand(updated);
        }
        if (same(player.getInventory().getItemInOffHand(), baseline)) {
            return updated -> player.getInventory().setItemInOffHand(updated);
        }
        return ignored -> {};
    }

    private static Player player(FlowContext context, FlowRuntime runtime, FlowNode node) {
        Object raw = runtime.resolveInput(node, "target");
        if (raw == null && !runtime.hasInputConnection(node, "target")) {
            return context.getPlayer();
        }
        Object resolved = runtime.resolveInput(node, "target", Player.class);
        return resolved instanceof Player player ? player : null;
    }

    private static boolean same(ItemStack first, ItemStack second) {
        return first != null && second != null && first.getAmount() == second.getAmount() && first.isSimilar(second);
    }
}
