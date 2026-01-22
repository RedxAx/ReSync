package restudio.resync.flow.nodes;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import restudio.flow.data.FlowNode;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.FlowRegistry;
import restudio.resync.flow.NodeCategory;
import restudio.resync.flow.util.TextFormatter;

import java.util.HashMap;
import java.util.Map;

public class InventoryNodes implements NodeCategory {

    private static final Map<String, Inventory> openInventories = new HashMap<>();

    @Override
    public void registerNodes(FlowRegistry registry) {
        registry.register("inventory_open_gui", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            String title = ctx.getInputValue(node, "title", String.class, "Inventory");
            Integer rows = ctx.getInputValue(node, "rows", Integer.class, 1);
            
            if (player != null && rows >= 1 && rows <= 6) {
                if (Bukkit.isPrimaryThread()) {
                    Inventory inv = Bukkit.createInventory(null, rows * 9, TextFormatter.parse(title));
                    player.openInventory(inv);
                    openInventories.put(player.getUniqueId().toString(), inv);
                } else {
                    Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), () -> {
                        Inventory inv = Bukkit.createInventory(null, rows * 9, TextFormatter.parse(title));
                        player.openInventory(inv);
                        openInventories.put(player.getUniqueId().toString(), inv);
                    });
                }
            }
            ctx.triggerOutput("flow");
        });

        registry.register("inventory_close", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            if (player != null) {
                if (Bukkit.isPrimaryThread()) {
                    player.closeInventory();
                    openInventories.remove(player.getUniqueId().toString());
                } else {
                    Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), () -> {
                        player.closeInventory();
                        openInventories.remove(player.getUniqueId().toString());
                    });
                }
            }
            ctx.triggerOutput("flow");
        });

        registry.register("inventory_set_title", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            String title = ctx.getInputValue(node, "title", String.class, "Inventory");
            if (player != null) {
                if (Bukkit.isPrimaryThread()) {
                    player.closeInventory();
                } else {
                    Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), () -> player.closeInventory());
                }
            }
            ctx.triggerOutput("flow");
        });

        registry.register("inventory_set_rows", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            Integer rows = ctx.getInputValue(node, "rows", Integer.class, 1);
            if (player != null && rows >= 1 && rows <= 6) {
                if (Bukkit.isPrimaryThread()) {
                    String oldTitle = player.getOpenInventory().getTitle();
                    player.closeInventory();
                    Inventory inv = Bukkit.createInventory(null, rows * 9, oldTitle);
                    player.openInventory(inv);
                } else {
                    Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), () -> {
                        String oldTitle = player.getOpenInventory().getTitle();
                        player.closeInventory();
                        Inventory inv = Bukkit.createInventory(null, rows * 9, oldTitle);
                        player.openInventory(inv);
                    });
                }
            }
            ctx.triggerOutput("flow");
        });

        registry.register("inventory_get_contents", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            String nodeId = findNodeId(ctx, node);
            
            if (player != null && player.getOpenInventory().getTopInventory() != null) {
                ItemStack[] contents = player.getOpenInventory().getTopInventory().getContents();
                ctx.setNodeOutput(nodeId, "contents", contents);
            } else {
                ctx.setNodeOutput(nodeId, "contents", new ItemStack[0]);
            }
            ctx.triggerOutput("flow");
        });

        registry.register("inventory_set_contents", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            ItemStack[] contents = ctx.getInputValue(node, "contents", ItemStack[].class, new ItemStack[0]);
            
            if (player != null && player.getOpenInventory().getTopInventory() != null) {
                if (Bukkit.isPrimaryThread()) {
                    player.getOpenInventory().getTopInventory().setContents(contents);
                } else {
                    Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), () -> 
                        player.getOpenInventory().getTopInventory().setContents(contents));
                }
            }
            ctx.triggerOutput("flow");
        });

        registry.register("inventory_add_item", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
            
            if (player != null && item != null && player.getOpenInventory().getTopInventory() != null) {
                if (Bukkit.isPrimaryThread()) {
                    player.getOpenInventory().getTopInventory().addItem(item.clone());
                } else {
                    Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), () -> 
                        player.getOpenInventory().getTopInventory().addItem(item.clone()));
                }
            }
            ctx.triggerOutput("flow");
        });

        registry.register("inventory_remove_item", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
            
            if (player != null && item != null && player.getOpenInventory().getTopInventory() != null) {
                if (Bukkit.isPrimaryThread()) {
                    player.getOpenInventory().getTopInventory().removeItem(item.clone());
                } else {
                    Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), () -> 
                        player.getOpenInventory().getTopInventory().removeItem(item.clone()));
                }
            }
            ctx.triggerOutput("flow");
        });

        registry.register("inventory_has_item", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
            String nodeId = findNodeId(ctx, node);
            
            boolean hasItem = false;
            if (player != null && item != null && player.getOpenInventory().getTopInventory() != null) {
                for (ItemStack invItem : player.getOpenInventory().getTopInventory().getContents()) {
                    if (invItem != null && invItem.isSimilar(item)) {
                        hasItem = true;
                        break;
                    }
                }
            }
            ctx.setNodeOutput(nodeId, "has", hasItem);
            ctx.triggerOutput("flow");
        });

        registry.register("inventory_count_item", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            String materialName = ctx.getInputValue(node, "material", String.class, "");
            String nodeId = findNodeId(ctx, node);
            
            int count = 0;
            if (player != null && !materialName.isEmpty() && player.getOpenInventory().getTopInventory() != null) {
                Material material = Material.getMaterial(materialName.toUpperCase());
                if (material != null) {
                    for (ItemStack item : player.getOpenInventory().getTopInventory().getContents()) {
                        if (item != null && item.getType() == material) {
                            count += item.getAmount();
                        }
                    }
                }
            }
            ctx.setNodeOutput(nodeId, "count", count);
            ctx.triggerOutput("flow");
        });

        registry.register("inventory_get_slot", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            Integer slot = ctx.getInputValue(node, "slot", Integer.class, 0);
            String nodeId = findNodeId(ctx, node);
            
            ItemStack item = null;
            if (player != null && player.getOpenInventory().getTopInventory() != null) {
                ItemStack[] contents = player.getOpenInventory().getTopInventory().getContents();
                if (slot >= 0 && slot < contents.length) {
                    item = contents[slot];
                }
            }
            ctx.setNodeOutput(nodeId, "item", item);
            ctx.triggerOutput("flow");
        });

        registry.register("inventory_set_slot", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            Integer slot = ctx.getInputValue(node, "slot", Integer.class, 0);
            ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
            
            if (player != null && player.getOpenInventory().getTopInventory() != null) {
                ItemStack[] contents = player.getOpenInventory().getTopInventory().getContents();
                if (slot >= 0 && slot < contents.length) {
                    if (Bukkit.isPrimaryThread()) {
                        player.getOpenInventory().getTopInventory().setItem(slot, item);
                    } else {
                        Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), () -> 
                            player.getOpenInventory().getTopInventory().setItem(slot, item));
                    }
                }
            }
            ctx.triggerOutput("flow");
        });

        registry.register("inventory_clear_slot", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            Integer slot = ctx.getInputValue(node, "slot", Integer.class, 0);
            
            if (player != null && player.getOpenInventory().getTopInventory() != null) {
                ItemStack[] contents = player.getOpenInventory().getTopInventory().getContents();
                if (slot >= 0 && slot < contents.length) {
                    if (Bukkit.isPrimaryThread()) {
                        player.getOpenInventory().getTopInventory().setItem(slot, null);
                    } else {
                        Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), () -> 
                            player.getOpenInventory().getTopInventory().setItem(slot, null));
                    }
                }
            }
            ctx.triggerOutput("flow");
        });

        registry.register("inventory_move_item", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            Integer fromSlot = ctx.getInputValue(node, "from_slot", Integer.class, 0);
            Integer toSlot = ctx.getInputValue(node, "to_slot", Integer.class, 1);
            
            if (player != null && player.getOpenInventory().getTopInventory() != null) {
                Inventory inv = player.getOpenInventory().getTopInventory();
                ItemStack[] contents = inv.getContents();
                if (fromSlot >= 0 && fromSlot < contents.length && toSlot >= 0 && toSlot < contents.length) {
                    if (Bukkit.isPrimaryThread()) {
                        ItemStack item = inv.getItem(fromSlot);
                        inv.setItem(toSlot, item);
                        inv.setItem(fromSlot, null);
                    } else {
                        Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), () -> {
                            ItemStack item = inv.getItem(fromSlot);
                            inv.setItem(toSlot, item);
                            inv.setItem(fromSlot, null);
                        });
                    }
                }
            }
            ctx.triggerOutput("flow");
        });

        registry.register("inventory_swap_items", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            Integer slot1 = ctx.getInputValue(node, "slot1", Integer.class, 0);
            Integer slot2 = ctx.getInputValue(node, "slot2", Integer.class, 1);
            
            if (player != null && player.getOpenInventory().getTopInventory() != null) {
                Inventory inv = player.getOpenInventory().getTopInventory();
                ItemStack[] contents = inv.getContents();
                if (slot1 >= 0 && slot1 < contents.length && slot2 >= 0 && slot2 < contents.length) {
                    if (Bukkit.isPrimaryThread()) {
                        ItemStack item1 = inv.getItem(slot1);
                        ItemStack item2 = inv.getItem(slot2);
                        inv.setItem(slot1, item2);
                        inv.setItem(slot2, item1);
                    } else {
                        Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), () -> {
                            ItemStack item1 = inv.getItem(slot1);
                            ItemStack item2 = inv.getItem(slot2);
                            inv.setItem(slot1, item2);
                            inv.setItem(slot2, item1);
                        });
                    }
                }
            }
            ctx.triggerOutput("flow");
        });

        registry.register("inventory_clear", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            if (player != null && player.getOpenInventory().getTopInventory() != null) {
                if (Bukkit.isPrimaryThread()) {
                    player.getOpenInventory().getTopInventory().clear();
                } else {
                    Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), () -> 
                        player.getOpenInventory().getTopInventory().clear());
                }
            }
            ctx.triggerOutput("flow");
        });

        registry.register("inventory_update", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            if (player != null && player.getOpenInventory().getTopInventory() != null) {
                if (Bukkit.isPrimaryThread()) {
                    player.updateInventory();
                } else {
                    Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), () -> player.updateInventory());
                }
            }
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
