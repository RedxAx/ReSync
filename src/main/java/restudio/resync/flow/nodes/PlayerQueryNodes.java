package restudio.resync.flow.nodes;

import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import restudio.flow.data.FlowNode;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.FlowRegistry;
import restudio.resync.flow.NodeCategory;

import java.util.ArrayList;
import java.util.List;

public class PlayerQueryNodes implements NodeCategory {

    @Override
    public void registerNodes(FlowRegistry registry) {
        registry.register("player_has_item", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            if (player == null) {
                return;
            }
            ItemStack itemStack = ctx.getInputValue(node, "material_or_item", ItemStack.class, null);
            if (itemStack == null) {
                String materialName = ctx.getInputValue(node, "material", String.class, "");
                Material material = Material.getMaterial(materialName.toUpperCase());
                if (material != null) {
                    itemStack = new ItemStack(material);
                } else {
                    return;
                }
            }
            boolean hasItem = player.getInventory().containsAtLeast(itemStack, 1);
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "has_item", hasItem);
        });

        registry.register("player_count_item", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            if (player == null) {
                return;
            }
            ItemStack itemStack = ctx.getInputValue(node, "material_or_item", ItemStack.class, null);
            if (itemStack == null) {
                String materialName = ctx.getInputValue(node, "material", String.class, "");
                Material material = Material.getMaterial(materialName.toUpperCase());
                if (material != null) {
                    itemStack = new ItemStack(material);
                } else {
                    return;
                }
            }
            int count = 0;
            ItemStack[] contents = player.getInventory().getStorageContents();
            if (contents != null) {
                for (ItemStack item : contents) {
                    if (item != null && item.isSimilar(itemStack)) {
                        count += item.getAmount();
                    }
                }
            }
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "count", count);
        });

        registry.register("player_get_first_empty_slot", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            if (player == null) {
                return;
            }
            int slot = player.getInventory().firstEmpty();
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "slot_index", slot);
        });

        registry.register("player_get_all_items", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            if (player == null) {
                return;
            }
            List<ItemStack> items = new ArrayList<>();
            ItemStack[] contents = player.getInventory().getStorageContents();
            if (contents != null) {
                for (ItemStack item : contents) {
                    if (item != null && item.getType() != Material.AIR) {
                        items.add(item);
                    }
                }
            }
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "items_list", items);
        });

        registry.register("player_get_hotbar_items", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            if (player == null) {
                return;
            }
            List<ItemStack> items = new ArrayList<>();
            for (int i = 0; i < 9; i++) {
                ItemStack item = player.getInventory().getItem(i);
                if (item != null && item.getType() != Material.AIR) {
                    items.add(item);
                } else {
                    items.add(new ItemStack(Material.AIR));
                }
            }
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "items_list", items);
        });

        registry.register("player_get_armor_items", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            if (player == null) {
                return;
            }
            ItemStack[] armorContents = player.getInventory().getArmorContents();
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "helmet", armorContents[3]);
            ctx.setNodeOutput(nodeId, "chestplate", armorContents[2]);
            ctx.setNodeOutput(nodeId, "leggings", armorContents[1]);
            ctx.setNodeOutput(nodeId, "boots", armorContents[0]);
        });

        registry.register("player_get_inventory_size", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            if (player == null) {
                return;
            }
            int size = player.getInventory().getSize();
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "size", size);
        });

        registry.register("player_get_mainhand_item", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            if (player == null) {
                return;
            }
            ItemStack mainhandItem = player.getInventory().getItemInMainHand();
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "item", mainhandItem);
        });

        registry.register("player_get_offhand_item", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            if (player == null) {
                return;
            }
            ItemStack offhandItem = player.getInventory().getItemInOffHand();
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "item", offhandItem);
        });

        registry.register("player_is_on_ground", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            if (player == null) {
                return;
            }
            boolean onGround = player.isOnGround();
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "on_ground", onGround);
        });

        registry.register("player_is_sleeping", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            if (player == null) {
                return;
            }
            boolean isSleeping = player.isSleeping();
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "is_sleeping", isSleeping);
        });

        registry.register("player_get_bed_location", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            if (player == null) {
                return;
            }
            org.bukkit.Location bedLocation = player.getBedSpawnLocation();
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "bed_location", bedLocation);
        });

        registry.register("player_get_last_damage", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            if (player == null) {
                return;
            }
            EntityDamageEvent lastDamageEvent = player.getLastDamageCause();
            String nodeId = findNodeId(ctx, node);
            if (lastDamageEvent != null) {
                ctx.setNodeOutput(nodeId, "damage_cause", lastDamageEvent.getCause().name());
                Entity damageSource = lastDamageEvent.getEntity();
                ctx.setNodeOutput(nodeId, "damage_source", damageSource);
            } else {
                ctx.setNodeOutput(nodeId, "damage_cause", null);
                ctx.setNodeOutput(nodeId, "damage_source", null);
            }
        });

        registry.register("player_get_killer", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            if (player == null) {
                return;
            }
            Player killer = player.getKiller();
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "killer", killer);
        });

        registry.register("player_get_ping", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            if (player == null) {
                return;
            }
            int ping = player.getPing();
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "ping_ms", ping);
        });



        registry.register("player_get_lore", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            if (player == null) {
                return;
            }
            String hand = ctx.getInputValue(node, "hand", String.class, "main");
            ItemStack item = switch (hand.toLowerCase()) {
                case "off" -> player.getInventory().getItemInOffHand();
                default -> player.getInventory().getItemInMainHand();
            };
            List<String> loreLines = new ArrayList<>();
            if (item != null && item.hasItemMeta()) {
                ItemMeta meta = item.getItemMeta();
                List<net.kyori.adventure.text.Component> lore = meta.lore();
                if (lore != null) {
                    for (net.kyori.adventure.text.Component line : lore) {
                        loreLines.add(net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(line));
                    }
                }
            }
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "lore_lines_list", loreLines);
        });

        registry.register("player_get_display_name", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            if (player == null) {
                return;
            }
            String displayName = player.getDisplayName();
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "display_name", displayName);
        });

        registry.register("player_get_player_list_name", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            if (player == null) {
                return;
            }
            String playerListName = player.getPlayerListName();
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "list_name", playerListName);
        });

        registry.register("player_is_op", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            if (player == null) {
                return;
            }
            boolean isOp = player.isOp();
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "is_op", isOp);
        });

        registry.register("player_get_allowed_flight", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            if (player == null) {
                return;
            }
            boolean canFly = player.getAllowFlight();
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "can_fly", canFly);
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
