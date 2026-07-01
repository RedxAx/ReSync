package restudio.resync.flow.handler.generic;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import restudio.flow.data.FlowNode;
import restudio.resync.ReSync;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.NodeHandler;
import restudio.resync.flow.util.TextFormatter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

public class InventoryActionHandler implements NodeHandler {
    private final Map<String, BiConsumer<FlowContext, FlowNode>> operations = new ConcurrentHashMap<>();
    private final Map<String, Inventory> openInventories = new HashMap<>();

    public InventoryActionHandler() {
        operations.put("player_has_item", (ctx, node) -> {
            Player player = ctx.getPlayer();
            if (player == null) return;
            String matName = ctx.getInputValue(node, "material", String.class, "STONE");
            Material mat = Material.getMaterial(matName.toUpperCase());
            if (mat == null) return;
            boolean hasItem = false;
            int count = 0;
            for (ItemStack item : player.getInventory().getContents()) {
                if (item != null && item.getType() == mat) {
                    hasItem = true;
                    count += item.getAmount();
                }
            }
            ctx.setOutput(node, "has", hasItem);
            ctx.setOutput(node, "count", count);
        });

        operations.put("player_remove_item", (ctx, node) -> {
            Player player = ctx.getPlayer();
            if (player == null) return;
            String matName = ctx.getInputValue(node, "material", String.class, "STONE");
            int amount = ctx.getInputValue(node, "amount", Integer.class, 1);
            Material mat = Material.getMaterial(matName.toUpperCase());
            if (mat == null) return;
            ItemStack toRemove = new ItemStack(mat, amount);
            player.getInventory().removeItem(toRemove);
        });

        operations.put("player_clear_inv", (ctx, node) -> {
            Player player = ctx.getPlayer();
            if (player == null) return;
            player.getInventory().clear();
        });

        operations.put("inventory_open_gui", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            String title = ctx.getInputValue(node, "title", String.class, "Inventory");
            Integer rows = ctx.getInputValue(node, "rows", Integer.class, 1);
            if (player != null && rows >= 1 && rows <= 6) {
                if (Bukkit.isPrimaryThread()) {
                    Inventory inv = Bukkit.createInventory(null, rows * 9, TextFormatter.parse(title));
                    player.openInventory(inv);
                    openInventories.put(player.getUniqueId().toString(), inv);
                } else {
                    Bukkit.getScheduler().runTask(ReSync.getInstance(), () -> {
                        Inventory inv = Bukkit.createInventory(null, rows * 9, TextFormatter.parse(title));
                        player.openInventory(inv);
                        openInventories.put(player.getUniqueId().toString(), inv);
                    });
                }
            }
        });

        operations.put("inventory_close", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            if (player != null) {
                if (Bukkit.isPrimaryThread()) {
                    player.closeInventory();
                    openInventories.remove(player.getUniqueId().toString());
                } else {
                    Bukkit.getScheduler().runTask(ReSync.getInstance(), () -> {
                        player.closeInventory();
                        openInventories.remove(player.getUniqueId().toString());
                    });
                }
            }
        });

        operations.put("inventory_set_title", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            if (player != null) {
                if (Bukkit.isPrimaryThread()) {
                    player.closeInventory();
                } else {
                    Bukkit.getScheduler().runTask(ReSync.getInstance(), () -> player.closeInventory());
                }
            }
        });

        operations.put("inventory_set_rows", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            Integer rows = ctx.getInputValue(node, "rows", Integer.class, 1);
            if (player != null && rows >= 1 && rows <= 6) {
                if (Bukkit.isPrimaryThread()) {
                    String oldTitle = player.getOpenInventory().getTitle();
                    player.closeInventory();
                    Inventory inv = Bukkit.createInventory(null, rows * 9, oldTitle);
                    player.openInventory(inv);
                } else {
                    Bukkit.getScheduler().runTask(ReSync.getInstance(), () -> {
                        String oldTitle = player.getOpenInventory().getTitle();
                        player.closeInventory();
                        Inventory inv = Bukkit.createInventory(null, rows * 9, oldTitle);
                        player.openInventory(inv);
                    });
                }
            }
        });

        operations.put("inventory_get_contents", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            if (player != null && player.getOpenInventory().getTopInventory() != null) {
                ItemStack[] contents = player.getOpenInventory().getTopInventory().getContents();
                ctx.setOutput(node, "contents", contents);
            } else {
                ctx.setOutput(node, "contents", new ItemStack[0]);
            }
        });

        operations.put("inventory_set_contents", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            ItemStack[] contents = ctx.getInputValue(node, "contents", ItemStack[].class, new ItemStack[0]);
            if (player != null && player.getOpenInventory().getTopInventory() != null) {
                if (Bukkit.isPrimaryThread()) {
                    player.getOpenInventory().getTopInventory().setContents(contents);
                } else {
                    Bukkit.getScheduler().runTask(ReSync.getInstance(), () ->
                        player.getOpenInventory().getTopInventory().setContents(contents));
                }
            }
        });

        operations.put("inventory_add_item", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
            if (player != null && item != null && player.getOpenInventory().getTopInventory() != null) {
                if (Bukkit.isPrimaryThread()) {
                    player.getOpenInventory().getTopInventory().addItem(item.clone());
                } else {
                    Bukkit.getScheduler().runTask(ReSync.getInstance(), () ->
                        player.getOpenInventory().getTopInventory().addItem(item.clone()));
                }
            }
        });

        operations.put("inventory_remove_item", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
            if (player != null && item != null && player.getOpenInventory().getTopInventory() != null) {
                if (Bukkit.isPrimaryThread()) {
                    player.getOpenInventory().getTopInventory().removeItem(item.clone());
                } else {
                    Bukkit.getScheduler().runTask(ReSync.getInstance(), () ->
                        player.getOpenInventory().getTopInventory().removeItem(item.clone()));
                }
            }
        });

        operations.put("inventory_has_item", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
            boolean hasItem = false;
            if (player != null && item != null && player.getOpenInventory().getTopInventory() != null) {
                for (ItemStack invItem : player.getOpenInventory().getTopInventory().getContents()) {
                    if (invItem != null && invItem.isSimilar(item)) {
                        hasItem = true;
                        break;
                    }
                }
            }
            ctx.setOutput(node, "has", hasItem);
        });

        operations.put("inventory_count_item", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            String materialName = ctx.getInputValue(node, "material", String.class, "");
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
            ctx.setOutput(node, "count", count);
        });

        operations.put("inventory_get_slot", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            Integer slot = ctx.getInputValue(node, "slot", Integer.class, 0);
            ItemStack item = null;
            if (player != null && player.getOpenInventory().getTopInventory() != null) {
                ItemStack[] contents = player.getOpenInventory().getTopInventory().getContents();
                if (slot >= 0 && slot < contents.length) {
                    item = contents[slot];
                }
            }
            ctx.setOutput(node, "item", item);
        });

        operations.put("inventory_set_slot", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            Integer slot = ctx.getInputValue(node, "slot", Integer.class, 0);
            ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
            if (player != null && player.getOpenInventory().getTopInventory() != null) {
                ItemStack[] contents = player.getOpenInventory().getTopInventory().getContents();
                if (slot >= 0 && slot < contents.length) {
                    if (Bukkit.isPrimaryThread()) {
                        player.getOpenInventory().getTopInventory().setItem(slot, item);
                    } else {
                        Bukkit.getScheduler().runTask(ReSync.getInstance(), () ->
                            player.getOpenInventory().getTopInventory().setItem(slot, item));
                    }
                }
            }
        });

        operations.put("inventory_clear_slot", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            Integer slot = ctx.getInputValue(node, "slot", Integer.class, 0);
            if (player != null && player.getOpenInventory().getTopInventory() != null) {
                ItemStack[] contents = player.getOpenInventory().getTopInventory().getContents();
                if (slot >= 0 && slot < contents.length) {
                    if (Bukkit.isPrimaryThread()) {
                        player.getOpenInventory().getTopInventory().setItem(slot, null);
                    } else {
                        Bukkit.getScheduler().runTask(ReSync.getInstance(), () ->
                            player.getOpenInventory().getTopInventory().setItem(slot, null));
                    }
                }
            }
        });

        operations.put("inventory_move_item", (ctx, node) -> {
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
                        Bukkit.getScheduler().runTask(ReSync.getInstance(), () -> {
                            ItemStack item = inv.getItem(fromSlot);
                            inv.setItem(toSlot, item);
                            inv.setItem(fromSlot, null);
                        });
                    }
                }
            }
        });

        operations.put("inventory_swap_items", (ctx, node) -> {
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
                        Bukkit.getScheduler().runTask(ReSync.getInstance(), () -> {
                            ItemStack item1 = inv.getItem(slot1);
                            ItemStack item2 = inv.getItem(slot2);
                            inv.setItem(slot1, item2);
                            inv.setItem(slot2, item1);
                        });
                    }
                }
            }
        });

        operations.put("inventory_clear", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            if (player != null && player.getOpenInventory().getTopInventory() != null) {
                if (Bukkit.isPrimaryThread()) {
                    player.getOpenInventory().getTopInventory().clear();
                } else {
                    Bukkit.getScheduler().runTask(ReSync.getInstance(), () ->
                        player.getOpenInventory().getTopInventory().clear());
                }
            }
        });

        operations.put("inventory_update", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            if (player != null && player.getOpenInventory().getTopInventory() != null) {
                if (Bukkit.isPrimaryThread()) {
                    player.updateInventory();
                } else {
                    Bukkit.getScheduler().runTask(ReSync.getInstance(), () -> player.updateInventory());
                }
            }
        });

        operations.put("inventory_has_space", (ctx, node) -> {
            Inventory inventory = resolveInventory(ctx, node, "inventory");
            ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
            boolean hasSpace = inventory != null && item != null && inventory.firstEmpty() != -1;
            ctx.setOutput(node, "has_space", hasSpace);
        });

        operations.put("inventory_count_material", (ctx, node) -> {
            Inventory inventory = resolveInventory(ctx, node, "inventory");
            Material material = resolveMaterial(ctx, node, "material");
            int count = 0;
            if (inventory != null && material != null) {
                for (ItemStack item : inventory.getContents()) {
                    if (item != null && item.getType() == material) {
                        count += item.getAmount();
                    }
                }
            }
            ctx.setOutput(node, "count", count);
        });

        operations.put("inventory_get_first_empty", (ctx, node) -> {
            Inventory inventory = resolveInventory(ctx, node, "inventory");
            int slot = inventory != null ? inventory.firstEmpty() : -1;
            ctx.setOutput(node, "slot_index", slot);
        });

        operations.put("inventory_sort", (ctx, node) -> {
            Inventory inventory = resolveInventory(ctx, node, "inventory");
            if (inventory != null) {
                if (Bukkit.isPrimaryThread()) {
                    sortInventory(inventory);
                } else {
                    Bukkit.getScheduler().runTask(ReSync.getInstance(), () -> sortInventory(inventory));
                }
            }
        });

        operations.put("inventory_get_all", (ctx, node) -> {
            Inventory inventory = resolveInventory(ctx, node, "inventory");
            List<ItemStack> items = inventory != null ? Arrays.stream(inventory.getContents()).filter(item -> item != null && item.getType() != Material.AIR).toList() : List.of();
            ctx.setOutput(node, "items_list", new ArrayList<>(items));
        });

        operations.put("inventory_clear_all", (ctx, node) -> {
            Inventory inventory = resolveInventory(ctx, node, "inventory");
            if (inventory != null) {
                if (Bukkit.isPrimaryThread()) {
                    inventory.clear();
                } else {
                    Bukkit.getScheduler().runTask(ReSync.getInstance(), () -> inventory.clear());
                }
            }
        });

        operations.put("inventory_size", (ctx, node) -> {
            Inventory inventory = resolveInventory(ctx, node, "inventory");
            int size = inventory != null ? inventory.getSize() : 0;
            ctx.setOutput(node, "size", size);
        });

        operations.put("inventory_get_storage_contents", (ctx, node) -> {
            Inventory inventory = resolveInventory(ctx, node, "inventory");
            ItemStack[] storageContents = inventory != null ? inventory.getStorageContents() : new ItemStack[0];
            ctx.setOutput(node, "items_list", storageContents);
        });

        operations.put("inventory_get_max_stack_size", (ctx, node) -> {
            Material material = resolveMaterial(ctx, node, "material");
            int maxStackSize = material != null ? material.getMaxStackSize() : 0;
            ctx.setOutput(node, "max_size", maxStackSize);
        });

        operations.put("inventory_contains_at_least", (ctx, node) -> {
            Inventory inventory = resolveInventory(ctx, node, "inventory");
            Material material = resolveMaterial(ctx, node, "material");
            Integer amount = ctx.getInputValue(node, "amount", Integer.class, 1);
            boolean contains = false;
            if (inventory != null && material != null && amount > 0) {
                int total = 0;
                for (ItemStack item : inventory.getContents()) {
                    if (item != null && item.getType() == material) {
                        total += item.getAmount();
                        if (total >= amount) {
                            contains = true;
                            break;
                        }
                    }
                }
            }
            ctx.setOutput(node, "contains", contains);
        });

        operations.put("inventory_remove_any", (ctx, node) -> {
            Inventory inventory = resolveInventory(ctx, node, "inventory");
            Material material = resolveMaterial(ctx, node, "material");
            Integer amount = ctx.getInputValue(node, "amount", Integer.class, 1);
            if (inventory != null && material != null && amount > 0) {
                if (Bukkit.isPrimaryThread()) {
                    removeAny(inventory, material, amount);
                } else {
                    Bukkit.getScheduler().runTask(ReSync.getInstance(), () -> removeAny(inventory, material, amount));
                }
            }
        });

        operations.put("inventory_set_all_contents", (ctx, node) -> {
            Inventory inventory = resolveInventory(ctx, node, "inventory");
            ItemStack[] contents = ctx.getInputValue(node, "items_list", ItemStack[].class, new ItemStack[0]);
            if (inventory != null) {
                if (Bukkit.isPrimaryThread()) {
                    inventory.setContents(contents);
                } else {
                    Bukkit.getScheduler().runTask(ReSync.getInstance(), () -> inventory.setContents(contents));
                }
            }
        });

        operations.put("inventory_add_to_slot", (ctx, node) -> {
            Inventory inventory = resolveInventory(ctx, node, "inventory");
            Integer slot = ctx.getInputValue(node, "slot", Integer.class, 0);
            ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
            if (inventory != null && item != null && slot >= 0 && slot < inventory.getSize()) {
                if (Bukkit.isPrimaryThread()) {
                    inventory.setItem(slot, item.clone());
                } else {
                    Bukkit.getScheduler().runTask(ReSync.getInstance(), () -> inventory.setItem(slot, item.clone()));
                }
            }
        });

        operations.put("item_create", (ctx, node) -> {
            String materialName = ctx.getInputValue(node, "material", String.class, "STONE");
            Integer amount = ctx.getInputValue(node, "amount", Integer.class, 1);
            Material material = Material.getMaterial(materialName.toUpperCase());
            ItemStack item = null;
            if (material != null) {
                item = new ItemStack(material, Math.max(1, amount));
            }
            ctx.setOutput(node, "item", item);
        });

        operations.put("item_set_material", (ctx, node) -> {
            ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
            String materialName = ctx.getInputValue(node, "material", String.class, "STONE");
            if (item != null) {
                Material material = Material.getMaterial(materialName.toUpperCase());
                if (material != null) {
                    if (Bukkit.isPrimaryThread()) {
                        item.setType(material);
                    } else {
                        Bukkit.getScheduler().runTask(ReSync.getInstance(), () -> item.setType(material));
                    }
                }
            }
            ctx.setOutput(node, "item", item);
        });

        operations.put("item_set_amount", (ctx, node) -> {
            ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
            Integer amount = ctx.getInputValue(node, "amount", Integer.class, 1);
            if (item != null) {
                if (Bukkit.isPrimaryThread()) {
                    item.setAmount(Math.max(1, Math.min(64, amount)));
                } else {
                    Bukkit.getScheduler().runTask(ReSync.getInstance(), () ->
                        item.setAmount(Math.max(1, Math.min(64, amount))));
                }
            }
            ctx.setOutput(node, "item", item);
        });

        operations.put("item_set_damage", (ctx, node) -> {
            ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
            Integer damage = ctx.getInputValue(node, "damage", Integer.class, 0);
            if (item != null) {
                if (Bukkit.isPrimaryThread()) {
                    item.setDurability((short) Math.max(0, damage));
                } else {
                    Bukkit.getScheduler().runTask(ReSync.getInstance(), () ->
                        item.setDurability((short) Math.max(0, damage)));
                }
            }
            ctx.setOutput(node, "item", item);
        });

        operations.put("item_set_max_damage", (ctx, node) -> {
            ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
            ctx.setOutput(node, "item", item);
        });

        operations.put("item_set_unbreakable", (ctx, node) -> {
            ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
            Boolean unbreakable = ctx.getInputValue(node, "unbreakable", Boolean.class, true);
            if (item != null && item.hasItemMeta()) {
                if (Bukkit.isPrimaryThread()) {
                    ItemMeta meta = item.getItemMeta();
                    if (meta != null) {
                        meta.setUnbreakable(unbreakable);
                        item.setItemMeta(meta);
                    }
                } else {
                    Bukkit.getScheduler().runTask(ReSync.getInstance(), () -> {
                        ItemMeta meta = item.getItemMeta();
                        if (meta != null) {
                            meta.setUnbreakable(unbreakable);
                            item.setItemMeta(meta);
                        }
                    });
                }
            }
            ctx.setOutput(node, "item", item);
        });

        operations.put("item_set_custom_name", (ctx, node) -> {
            ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
            String name = ctx.getInputValue(node, "name", String.class, "");
            if (item != null && item.hasItemMeta()) {
                if (Bukkit.isPrimaryThread()) {
                    ItemMeta meta = item.getItemMeta();
                    if (meta != null) {
                        meta.displayName(TextFormatter.parseItemName(name));
                        item.setItemMeta(meta);
                    }
                } else {
                    Bukkit.getScheduler().runTask(ReSync.getInstance(), () -> {
                        ItemMeta meta = item.getItemMeta();
                        if (meta != null) {
                            meta.displayName(TextFormatter.parseItemName(name));
                            item.setItemMeta(meta);
                        }
                    });
                }
            }
            ctx.setOutput(node, "item", item);
        });

        operations.put("item_set_lore", (ctx, node) -> {
            ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
            String lore = ctx.getInputValue(node, "lore", String.class, "");
            if (item != null && item.hasItemMeta()) {
                if (Bukkit.isPrimaryThread()) {
                    ItemMeta meta = item.getItemMeta();
                    if (meta != null) {
                        meta.lore(TextFormatter.parseItemLoreLines(lore));
                        item.setItemMeta(meta);
                    }
                } else {
                    Bukkit.getScheduler().runTask(ReSync.getInstance(), () -> {
                        ItemMeta meta = item.getItemMeta();
                        if (meta != null) {
                            meta.lore(TextFormatter.parseItemLoreLines(lore));
                            item.setItemMeta(meta);
                        }
                    });
                }
            }
            ctx.setOutput(node, "item", item);
        });

        operations.put("item_add_lore", (ctx, node) -> {
            ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
            String loreLine = ctx.getInputValue(node, "lore", String.class, "");
            if (item != null && item.hasItemMeta()) {
                if (Bukkit.isPrimaryThread()) {
                    ItemMeta meta = item.getItemMeta();
                    if (meta != null) {
                        List<Component> loreList = meta.lore() != null ? new ArrayList<>(meta.lore()) : new ArrayList<>();
                        loreList.add(TextFormatter.parseItemLore(loreLine));
                        meta.lore(loreList);
                        item.setItemMeta(meta);
                    }
                } else {
                    Bukkit.getScheduler().runTask(ReSync.getInstance(), () -> {
                        ItemMeta meta = item.getItemMeta();
                        if (meta != null) {
                            List<Component> loreList = meta.lore() != null ? new ArrayList<>(meta.lore()) : new ArrayList<>();
                            loreList.add(TextFormatter.parseItemLore(loreLine));
                            meta.lore(loreList);
                            item.setItemMeta(meta);
                        }
                    });
                }
            }
            ctx.setOutput(node, "item", item);
        });

        operations.put("item_clear_lore", (ctx, node) -> {
            ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
            if (item != null && item.hasItemMeta()) {
                if (Bukkit.isPrimaryThread()) {
                    ItemMeta meta = item.getItemMeta();
                    if (meta != null) {
                        meta.lore(null);
                        item.setItemMeta(meta);
                    }
                } else {
                    Bukkit.getScheduler().runTask(ReSync.getInstance(), () -> {
                        ItemMeta meta = item.getItemMeta();
                        if (meta != null) {
                            meta.lore(null);
                            item.setItemMeta(meta);
                        }
                    });
                }
            }
            ctx.setOutput(node, "item", item);
        });

        operations.put("item_set_flags", (ctx, node) -> {
            ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
            if (item != null && item.hasItemMeta()) {
                if (Bukkit.isPrimaryThread()) {
                    ItemMeta meta = item.getItemMeta();
                    if (meta != null) {
                        meta.addItemFlags(ItemFlag.values());
                        item.setItemMeta(meta);
                    }
                } else {
                    Bukkit.getScheduler().runTask(ReSync.getInstance(), () -> {
                        ItemMeta meta = item.getItemMeta();
                        if (meta != null) {
                            meta.addItemFlags(ItemFlag.values());
                            item.setItemMeta(meta);
                        }
                    });
                }
            }
            ctx.setOutput(node, "item", item);
        });

        operations.put("item_add_flag", (ctx, node) -> {
            ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
            String flagName = ctx.getInputValue(node, "flag", String.class, "");
            if (item != null && item.hasItemMeta() && !flagName.isEmpty()) {
                try {
                    ItemFlag flag = ItemFlag.valueOf(flagName.toUpperCase());
                    if (Bukkit.isPrimaryThread()) {
                        ItemMeta meta = item.getItemMeta();
                        if (meta != null) {
                            meta.addItemFlags(flag);
                            item.setItemMeta(meta);
                        }
                    } else {
                        Bukkit.getScheduler().runTask(ReSync.getInstance(), () -> {
                            ItemMeta meta = item.getItemMeta();
                            if (meta != null) {
                                meta.addItemFlags(flag);
                                item.setItemMeta(meta);
                            }
                        });
                    }
                } catch (IllegalArgumentException ignored) {
                }
            }
            ctx.setOutput(node, "item", item);
        });

        operations.put("item_remove_flag", (ctx, node) -> {
            ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
            String flagName = ctx.getInputValue(node, "flag", String.class, "");
            if (item != null && item.hasItemMeta() && !flagName.isEmpty()) {
                try {
                    ItemFlag flag = ItemFlag.valueOf(flagName.toUpperCase());
                    if (Bukkit.isPrimaryThread()) {
                        ItemMeta meta = item.getItemMeta();
                        if (meta != null) {
                            meta.removeItemFlags(flag);
                            item.setItemMeta(meta);
                        }
                    } else {
                        Bukkit.getScheduler().runTask(ReSync.getInstance(), () -> {
                            ItemMeta meta = item.getItemMeta();
                            if (meta != null) {
                                meta.removeItemFlags(flag);
                                item.setItemMeta(meta);
                            }
                        });
                    }
                } catch (IllegalArgumentException ignored) {
                }
            }
            ctx.setOutput(node, "item", item);
        });

        operations.put("item_add_enchant", (ctx, node) -> {
            ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
            String enchantName = ctx.getInputValue(node, "enchantment", String.class, "");
            Integer level = ctx.getInputValue(node, "level", Integer.class, 1);
            if (item != null && item.hasItemMeta() && !enchantName.isEmpty()) {
                try {
                    Enchantment enchant = Enchantment.getByKey(NamespacedKey.minecraft(enchantName.toLowerCase()));
                    if (enchant != null) {
                        if (Bukkit.isPrimaryThread()) {
                            ItemMeta meta = item.getItemMeta();
                            if (meta != null) {
                                meta.addEnchant(enchant, Math.max(1, level), true);
                                item.setItemMeta(meta);
                            }
                        } else {
                            Bukkit.getScheduler().runTask(ReSync.getInstance(), () -> {
                                ItemMeta meta = item.getItemMeta();
                                if (meta != null) {
                                    meta.addEnchant(enchant, Math.max(1, level), true);
                                    item.setItemMeta(meta);
                                }
                            });
                        }
                    }
                } catch (Exception ignored) {
                }
            }
            ctx.setOutput(node, "item", item);
        });

        operations.put("item_remove_enchant", (ctx, node) -> {
            ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
            String enchantName = ctx.getInputValue(node, "enchantment", String.class, "");
            if (item != null && item.hasItemMeta() && !enchantName.isEmpty()) {
                try {
                    Enchantment enchant = Enchantment.getByKey(NamespacedKey.minecraft(enchantName.toLowerCase()));
                    if (enchant != null) {
                        if (Bukkit.isPrimaryThread()) {
                            ItemMeta meta = item.getItemMeta();
                            if (meta != null) {
                                meta.removeEnchant(enchant);
                                item.setItemMeta(meta);
                            }
                        } else {
                            Bukkit.getScheduler().runTask(ReSync.getInstance(), () -> {
                                ItemMeta meta = item.getItemMeta();
                                if (meta != null) {
                                    meta.removeEnchant(enchant);
                                    item.setItemMeta(meta);
                                }
                            });
                        }
                    }
                } catch (Exception ignored) {
                }
            }
            ctx.setOutput(node, "item", item);
        });

        operations.put("item_clear_enchants", (ctx, node) -> {
            ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
            if (item != null && item.hasItemMeta()) {
                if (Bukkit.isPrimaryThread()) {
                    ItemMeta meta = item.getItemMeta();
                    if (meta != null) {
                        meta.getEnchants().keySet().forEach(meta::removeEnchant);
                        item.setItemMeta(meta);
                    }
                } else {
                    Bukkit.getScheduler().runTask(ReSync.getInstance(), () -> {
                        ItemMeta meta = item.getItemMeta();
                        if (meta != null) {
                            meta.getEnchants().keySet().forEach(meta::removeEnchant);
                            item.setItemMeta(meta);
                        }
                    });
                }
            }
            ctx.setOutput(node, "item", item);
        });

        operations.put("item_set_custom_model", (ctx, node) -> {
            ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
            Integer modelData = ctx.getInputValue(node, "model_data", Integer.class, 0);
            if (item != null && item.hasItemMeta()) {
                if (Bukkit.isPrimaryThread()) {
                    ItemMeta meta = item.getItemMeta();
                    if (meta != null) {
                        meta.setCustomModelData(modelData);
                        item.setItemMeta(meta);
                    }
                } else {
                    Bukkit.getScheduler().runTask(ReSync.getInstance(), () -> {
                        ItemMeta meta = item.getItemMeta();
                        if (meta != null) {
                            meta.setCustomModelData(modelData);
                            item.setItemMeta(meta);
                        }
                    });
                }
            }
            ctx.setOutput(node, "item", item);
        });

        operations.put("item_set_color", (ctx, node) -> {
            ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
            Integer red = ctx.getInputValue(node, "red", Integer.class, 255);
            Integer green = ctx.getInputValue(node, "green", Integer.class, 255);
            Integer blue = ctx.getInputValue(node, "blue", Integer.class, 255);
            if (item != null && item.hasItemMeta() && item.getItemMeta() instanceof LeatherArmorMeta) {
                if (Bukkit.isPrimaryThread()) {
                    LeatherArmorMeta meta = (LeatherArmorMeta) item.getItemMeta();
                    if (meta != null) {
                        meta.setColor(Color.fromRGB(red, green, blue));
                        item.setItemMeta(meta);
                    }
                } else {
                    Bukkit.getScheduler().runTask(ReSync.getInstance(), () -> {
                        LeatherArmorMeta meta = (LeatherArmorMeta) item.getItemMeta();
                        if (meta != null) {
                            meta.setColor(Color.fromRGB(red, green, blue));
                            item.setItemMeta(meta);
                        }
                    });
                }
            }
            ctx.setOutput(node, "item", item);
        });

        operations.put("item_set_skull_owner", (ctx, node) -> {
            ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
            String owner = ctx.getInputValue(node, "owner", String.class, "");
            if (item != null && item.hasItemMeta() && item.getItemMeta() instanceof SkullMeta && !owner.isEmpty()) {
                if (Bukkit.isPrimaryThread()) {
                    SkullMeta meta = (SkullMeta) item.getItemMeta();
                    if (meta != null) {
                        meta.setOwningPlayer(Bukkit.getOfflinePlayer(owner));
                        item.setItemMeta(meta);
                    }
                } else {
                    Bukkit.getScheduler().runTask(ReSync.getInstance(), () -> {
                        SkullMeta meta = (SkullMeta) item.getItemMeta();
                        if (meta != null) {
                            meta.setOwningPlayer(Bukkit.getOfflinePlayer(owner));
                            item.setItemMeta(meta);
                        }
                    });
                }
            }
            ctx.setOutput(node, "item", item);
        });

        operations.put("item_set_book_pages", (ctx, node) -> {
            ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
            String title = ctx.getInputValue(node, "title", String.class, "");
            String author = ctx.getInputValue(node, "author", String.class, "");
            String pages = ctx.getInputValue(node, "pages", String.class, "");
            if (item != null && item.hasItemMeta() && item.getItemMeta() instanceof BookMeta) {
                if (Bukkit.isPrimaryThread()) {
                    BookMeta meta = (BookMeta) item.getItemMeta();
                    if (meta != null) {
                        if (!title.isEmpty()) meta.setTitle(TextFormatter.formatLegacy(title));
                        if (!author.isEmpty()) meta.setAuthor(TextFormatter.formatLegacy(author));
                        List<String> pageList = new ArrayList<>();
                        for (String page : pages.split("\n---\n")) {
                            pageList.add(TextFormatter.formatLegacy(page));
                        }
                        if (!pageList.isEmpty()) meta.setPages(pageList);
                        item.setItemMeta(meta);
                    }
                } else {
                    Bukkit.getScheduler().runTask(ReSync.getInstance(), () -> {
                        BookMeta meta = (BookMeta) item.getItemMeta();
                        if (meta != null) {
                            if (!title.isEmpty()) meta.setTitle(TextFormatter.formatLegacy(title));
                            if (!author.isEmpty()) meta.setAuthor(TextFormatter.formatLegacy(author));
                            List<String> pageList = new ArrayList<>();
                            for (String page : pages.split("\n---\n")) {
                                pageList.add(TextFormatter.formatLegacy(page));
                            }
                            if (!pageList.isEmpty()) meta.setPages(pageList);
                            item.setItemMeta(meta);
                        }
                    });
                }
            }
            ctx.setOutput(node, "item", item);
        });

        operations.put("item_set_potion_effect", (ctx, node) -> {
            ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
            String effectName = ctx.getInputValue(node, "effect", String.class, "");
            Integer duration = ctx.getInputValue(node, "duration", Integer.class, 200);
            Integer amplifier = ctx.getInputValue(node, "amplifier", Integer.class, 0);
            if (item != null && item.hasItemMeta() && item.getItemMeta() instanceof PotionMeta && !effectName.isEmpty()) {
                try {
                    PotionEffectType type = PotionEffectType.getByName(effectName.toUpperCase());
                    if (type != null) {
                        PotionEffect effect = new PotionEffect(type, duration, amplifier);
                        if (Bukkit.isPrimaryThread()) {
                            PotionMeta meta = (PotionMeta) item.getItemMeta();
                            if (meta != null) {
                                meta.addCustomEffect(effect, true);
                                item.setItemMeta(meta);
                            }
                        } else {
                            Bukkit.getScheduler().runTask(ReSync.getInstance(), () -> {
                                PotionMeta meta = (PotionMeta) item.getItemMeta();
                                if (meta != null) {
                                    meta.addCustomEffect(effect, true);
                                    item.setItemMeta(meta);
                                }
                            });
                        }
                    }
                } catch (Exception ignored) {
                }
            }
            ctx.setOutput(node, "item", item);
        });

        operations.put("item_get_nbt", (ctx, node) -> {
            ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
            if (item != null && item.hasItemMeta()) {
                if (Bukkit.isPrimaryThread()) {
                    ItemMeta meta = item.getItemMeta();
                    if (meta != null && meta.getPersistentDataContainer().has(NamespacedKey.minecraft("nbt_data"), PersistentDataType.STRING)) {
                        String nbt = meta.getPersistentDataContainer().get(NamespacedKey.minecraft("nbt_data"), PersistentDataType.STRING);
                        ctx.setOutput(node, "nbt", nbt);
                    } else {
                        ctx.setOutput(node, "nbt", "");
                    }
                } else {
                    Bukkit.getScheduler().runTask(ReSync.getInstance(), () -> {
                        ItemMeta meta = item.getItemMeta();
                        if (meta != null && meta.getPersistentDataContainer().has(NamespacedKey.minecraft("nbt_data"), PersistentDataType.STRING)) {
                            String nbt = meta.getPersistentDataContainer().get(NamespacedKey.minecraft("nbt_data"), PersistentDataType.STRING);
                            ctx.setOutput(node, "nbt", nbt);
                        } else {
                            ctx.setOutput(node, "nbt", "");
                        }
                    });
                }
            } else {
                ctx.setOutput(node, "nbt", "");
            }
        });

        operations.put("item_set_nbt", (ctx, node) -> {
            ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
            String nbt = ctx.getInputValue(node, "nbt", String.class, "");
            if (item != null && item.hasItemMeta()) {
                if (Bukkit.isPrimaryThread()) {
                    ItemMeta meta = item.getItemMeta();
                    if (meta != null) {
                        meta.getPersistentDataContainer().set(NamespacedKey.minecraft("nbt_data"), PersistentDataType.STRING, nbt);
                        item.setItemMeta(meta);
                    }
                } else {
                    Bukkit.getScheduler().runTask(ReSync.getInstance(), () -> {
                        ItemMeta meta = item.getItemMeta();
                        if (meta != null) {
                            meta.getPersistentDataContainer().set(NamespacedKey.minecraft("nbt_data"), PersistentDataType.STRING, nbt);
                            item.setItemMeta(meta);
                        }
                    });
                }
            }
            ctx.setOutput(node, "item", item);
        });
    }

    public void registerTo(HandlerRegistry registry) {
        registry.register("InventoryActionHandler", this);
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

    private Inventory resolveInventory(FlowContext ctx, FlowNode node, String inputName) {
        Object input = ctx.getInputValue(node, inputName, Object.class, null);
        if (input == null) return null;
        return switch (input) {
            case Inventory inv -> inv;
            case Player p -> p.getOpenInventory().getTopInventory();
            default -> null;
        };
    }

    private Material resolveMaterial(FlowContext ctx, FlowNode node, String inputName) {
        Object input = ctx.getInputValue(node, inputName, Object.class, null);
        if (input == null) return null;
        return switch (input) {
            case Material m -> m;
            case ItemStack i -> i.getType();
            case String s -> Material.getMaterial(s.toUpperCase());
            default -> null;
        };
    }

    private void sortInventory(Inventory inventory) {
        List<ItemStack> items = new ArrayList<>();
        for (ItemStack item : inventory.getContents()) {
            if (item != null && item.getType() != Material.AIR) {
                items.add(item.clone());
            }
        }
        items.sort(Comparator.comparing((ItemStack i) -> i.getType().name()).thenComparing(i -> i.getAmount(), Comparator.reverseOrder()));
        inventory.clear();
        for (ItemStack item : items) {
            inventory.addItem(item);
        }
    }

    private void removeAny(Inventory inventory, Material material, int amount) {
        int remaining = amount;
        for (int i = 0; i < inventory.getSize() && remaining > 0; i++) {
            ItemStack item = inventory.getItem(i);
            if (item != null && item.getType() == material) {
                int stackAmount = item.getAmount();
                if (stackAmount <= remaining) {
                    inventory.setItem(i, null);
                    remaining -= stackAmount;
                } else {
                    item.setAmount(stackAmount - remaining);
                    remaining = 0;
                }
            }
        }
    }
}
