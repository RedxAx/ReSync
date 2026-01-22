package restudio.resync.flow.nodes;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import restudio.flow.data.FlowNode;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.FlowRegistry;
import restudio.resync.flow.NodeCategory;
import restudio.resync.flow.util.TextFormatter;

public class PlayerInventoryNodes implements NodeCategory {

    @Override
    public void registerNodes(FlowRegistry registry) {
        registry.register("player_give_item", (ctx, node) -> {
            Player target = ctx.getInputValue(node, "target", Player.class, null);
            if (target == null) {
                ctx.triggerOutput("flow");
                return;
            }
            ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
            if (item != null && item.getType() != Material.AIR) {
                if (Bukkit.isPrimaryThread()) {
                    target.getInventory().addItem(item.clone());
                } else {
                    Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), () -> target.getInventory().addItem(item.clone()));
                }
            }
            ctx.triggerOutput("flow");
        });

        registry.register("player_take_item", (ctx, node) -> {
            Player target = ctx.getInputValue(node, "target", Player.class, null);
            if (target == null) {
                ctx.triggerOutput("flow");
                return;
            }
            ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
            Integer amount = ctx.getInputValue(node, "amount", Integer.class, 1);
            if (item != null && amount > 0) {
                ItemStack toRemove = item.clone();
                toRemove.setAmount(amount);
                if (Bukkit.isPrimaryThread()) {
                    target.getInventory().removeItem(toRemove);
                } else {
                    Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), () -> target.getInventory().removeItem(toRemove));
                }
            }
            ctx.triggerOutput("flow");
        });

        registry.register("player_set_item", (ctx, node) -> {
            Player target = ctx.getInputValue(node, "target", Player.class, null);
            if (target == null) {
                ctx.triggerOutput("flow");
                return;
            }
            Integer slot = ctx.getInputValue(node, "slot", Integer.class, 0);
            ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
            if (slot >= 0 && slot < 36) {
                if (Bukkit.isPrimaryThread()) {
                    target.getInventory().setItem(slot, item);
                } else {
                    Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), () -> target.getInventory().setItem(slot, item));
                }
            }
            ctx.triggerOutput("flow");
        });

        registry.register("player_clear_slot", (ctx, node) -> {
            Player target = ctx.getInputValue(node, "target", Player.class, null);
            if (target == null) {
                ctx.triggerOutput("flow");
                return;
            }
            Integer slot = ctx.getInputValue(node, "slot", Integer.class, 0);
            if (slot >= 0 && slot < 36) {
                if (Bukkit.isPrimaryThread()) {
                    target.getInventory().setItem(slot, null);
                } else {
                    Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), () -> target.getInventory().setItem(slot, null));
                }
            }
            ctx.triggerOutput("flow");
        });

        registry.register("player_swap_items", (ctx, node) -> {
            Player target = ctx.getInputValue(node, "target", Player.class, null);
            if (target == null) {
                ctx.triggerOutput("flow");
                return;
            }
            Integer slot1 = ctx.getInputValue(node, "slot1", Integer.class, 0);
            Integer slot2 = ctx.getInputValue(node, "slot2", Integer.class, 1);
            if (slot1 >= 0 && slot1 < 36 && slot2 >= 0 && slot2 < 36) {
                if (Bukkit.isPrimaryThread()) {
                    ItemStack item1 = target.getInventory().getItem(slot1);
                    ItemStack item2 = target.getInventory().getItem(slot2);
                    target.getInventory().setItem(slot1, item2);
                    target.getInventory().setItem(slot2, item1);
                } else {
                    Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), () -> {
                        ItemStack item1 = target.getInventory().getItem(slot1);
                        ItemStack item2 = target.getInventory().getItem(slot2);
                        target.getInventory().setItem(slot1, item2);
                        target.getInventory().setItem(slot2, item1);
                    });
                }
            }
            ctx.triggerOutput("flow");
        });

        registry.register("player_set_helmet", (ctx, node) -> {
            Player target = ctx.getInputValue(node, "target", Player.class, null);
            if (target == null) {
                ctx.triggerOutput("flow");
                return;
            }
            ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
            if (Bukkit.isPrimaryThread()) {
                target.getInventory().setItem(EquipmentSlot.HEAD, item);
            } else {
                Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), () -> target.getInventory().setItem(EquipmentSlot.HEAD, item));
            }
            ctx.triggerOutput("flow");
        });

        registry.register("player_set_chestplate", (ctx, node) -> {
            Player target = ctx.getInputValue(node, "target", Player.class, null);
            if (target == null) {
                ctx.triggerOutput("flow");
                return;
            }
            ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
            if (Bukkit.isPrimaryThread()) {
                target.getInventory().setItem(EquipmentSlot.CHEST, item);
            } else {
                Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), () -> target.getInventory().setItem(EquipmentSlot.CHEST, item));
            }
            ctx.triggerOutput("flow");
        });

        registry.register("player_set_leggings", (ctx, node) -> {
            Player target = ctx.getInputValue(node, "target", Player.class, null);
            if (target == null) {
                ctx.triggerOutput("flow");
                return;
            }
            ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
            if (Bukkit.isPrimaryThread()) {
                target.getInventory().setItem(EquipmentSlot.LEGS, item);
            } else {
                Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), () -> target.getInventory().setItem(EquipmentSlot.LEGS, item));
            }
            ctx.triggerOutput("flow");
        });

        registry.register("player_set_boots", (ctx, node) -> {
            Player target = ctx.getInputValue(node, "target", Player.class, null);
            if (target == null) {
                ctx.triggerOutput("flow");
                return;
            }
            ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
            if (Bukkit.isPrimaryThread()) {
                target.getInventory().setItem(EquipmentSlot.FEET, item);
            } else {
                Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), () -> target.getInventory().setItem(EquipmentSlot.FEET, item));
            }
            ctx.triggerOutput("flow");
        });

        registry.register("player_set_mainhand", (ctx, node) -> {
            Player target = ctx.getInputValue(node, "target", Player.class, null);
            if (target == null) {
                ctx.triggerOutput("flow");
                return;
            }
            ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
            if (Bukkit.isPrimaryThread()) {
                target.getInventory().setItemInMainHand(item);
            } else {
                Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), () -> target.getInventory().setItemInMainHand(item));
            }
            ctx.triggerOutput("flow");
        });

        registry.register("player_set_offhand", (ctx, node) -> {
            Player target = ctx.getInputValue(node, "target", Player.class, null);
            if (target == null) {
                ctx.triggerOutput("flow");
                return;
            }
            ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
            if (Bukkit.isPrimaryThread()) {
                target.getInventory().setItemInOffHand(item);
            } else {
                Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), () -> target.getInventory().setItemInOffHand(item));
            }
            ctx.triggerOutput("flow");
        });

        registry.register("player_set_inventory_title", (ctx, node) -> {
            Player target = ctx.getInputValue(node, "target", Player.class, null);
            if (target == null) {
                ctx.triggerOutput("flow");
                return;
            }
            String title = ctx.getInputValue(node, "title", String.class, "Inventory");
            if (Bukkit.isPrimaryThread()) {
                target.openInventory(target.getInventory());
            } else {
                Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), () -> target.openInventory(target.getInventory()));
            }
            ctx.triggerOutput("flow");
        });

        registry.register("player_set_armor_color", (ctx, node) -> {
            Player target = ctx.getInputValue(node, "target", Player.class, null);
            if (target == null) {
                ctx.triggerOutput("flow");
                return;
            }
            EquipmentSlot slot = ctx.getInputValue(node, "slot", EquipmentSlot.class, EquipmentSlot.CHEST);
            Integer red = ctx.getInputValue(node, "red", Integer.class, 255);
            Integer green = ctx.getInputValue(node, "green", Integer.class, 255);
            Integer blue = ctx.getInputValue(node, "blue", Integer.class, 255);
            if (Bukkit.isPrimaryThread()) {
                ItemStack item = target.getInventory().getItem(slot);
                if (item != null && item.getItemMeta() instanceof LeatherArmorMeta meta) {
                    meta.setColor(Color.fromRGB(red, green, blue));
                    item.setItemMeta(meta);
                }
            } else {
                Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), () -> {
                    ItemStack item = target.getInventory().getItem(slot);
                    if (item != null && item.getItemMeta() instanceof LeatherArmorMeta meta) {
                        meta.setColor(Color.fromRGB(red, green, blue));
                        item.setItemMeta(meta);
                    }
                });
            }
            ctx.triggerOutput("flow");
        });

        registry.register("player_repair_item", (ctx, node) -> {
            ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
            if (item != null) {
                if (Bukkit.isPrimaryThread()) {
                    item.setDurability((short) 0);
                } else {
                    Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), () -> item.setDurability((short) 0));
                }
            }
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "item", item);
            ctx.triggerOutput("flow");
        });

        registry.register("player_enchant_item", (ctx, node) -> {
            ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
            String enchantName = ctx.getInputValue(node, "enchantment", String.class, "");
            Integer level = ctx.getInputValue(node, "level", Integer.class, 1);
            if (item != null && item.hasItemMeta()) {
                try {
                    Enchantment enchant = Enchantment.getByKey(org.bukkit.NamespacedKey.minecraft(enchantName.toLowerCase()));
                    if (enchant != null) {
                        if (Bukkit.isPrimaryThread()) {
                            ItemMeta meta = item.getItemMeta();
                            if (meta != null) {
                                meta.addEnchant(enchant, level, true);
                                item.setItemMeta(meta);
                            }
                        } else {
                            Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), () -> {
                                ItemMeta meta = item.getItemMeta();
                                if (meta != null) {
                                    meta.addEnchant(enchant, level, true);
                                    item.setItemMeta(meta);
                                }
                            });
                        }
                    }
                } catch (Exception e) {
                }
            }
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "item", item);
            ctx.triggerOutput("flow");
        });

        registry.register("player_unenchant_item", (ctx, node) -> {
            ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
            String enchantName = ctx.getInputValue(node, "enchantment", String.class, "");
            if (item != null && item.hasItemMeta()) {
                try {
                    Enchantment enchant = Enchantment.getByKey(org.bukkit.NamespacedKey.minecraft(enchantName.toLowerCase()));
                    if (enchant != null) {
                        if (Bukkit.isPrimaryThread()) {
                            ItemMeta meta = item.getItemMeta();
                            if (meta != null) {
                                meta.removeEnchant(enchant);
                                item.setItemMeta(meta);
                            }
                        } else {
                            Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), () -> {
                                ItemMeta meta = item.getItemMeta();
                                if (meta != null) {
                                    meta.removeEnchant(enchant);
                                    item.setItemMeta(meta);
                                }
                            });
                        }
                    }
                } catch (Exception e) {
                }
            }
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "item", item);
            ctx.triggerOutput("flow");
        });

        registry.register("player_clear_enchants", (ctx, node) -> {
            ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
            if (item != null && item.hasItemMeta()) {
                if (Bukkit.isPrimaryThread()) {
                    ItemMeta meta = item.getItemMeta();
                    if (meta != null) {
                        meta.getEnchants().keySet().forEach(meta::removeEnchant);
                        item.setItemMeta(meta);
                    }
                } else {
                    Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), () -> {
                        ItemMeta meta = item.getItemMeta();
                        if (meta != null) {
                            meta.getEnchants().keySet().forEach(meta::removeEnchant);
                            item.setItemMeta(meta);
                        }
                    });
                }
            }
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "item", item);
            ctx.triggerOutput("flow");
        });

        registry.register("player_set_item_name", (ctx, node) -> {
            ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
            String name = ctx.getInputValue(node, "name", String.class, "");
            if (item != null) {
                if (Bukkit.isPrimaryThread()) {
                    ItemMeta meta = item.getItemMeta();
                    if (meta != null) {
                        meta.displayName(TextFormatter.parse(name));
                        item.setItemMeta(meta);
                    }
                } else {
                    Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), () -> {
                        ItemMeta meta = item.getItemMeta();
                        if (meta != null) {
                            meta.displayName(TextFormatter.parse(name));
                            item.setItemMeta(meta);
                        }
                    });
                }
            }
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "item", item);
            ctx.triggerOutput("flow");
        });

        registry.register("player_set_item_lore", (ctx, node) -> {
            ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
            String lore = ctx.getInputValue(node, "lore", String.class, "");
            if (item != null) {
                if (Bukkit.isPrimaryThread()) {
                    ItemMeta meta = item.getItemMeta();
                    if (meta != null) {
                        meta.lore(TextFormatter.parseLines(lore));
                        item.setItemMeta(meta);
                    }
                } else {
                    Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), () -> {
                        ItemMeta meta = item.getItemMeta();
                        if (meta != null) {
                            meta.lore(TextFormatter.parseLines(lore));
                            item.setItemMeta(meta);
                        }
                    });
                }
            }
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "item", item);
            ctx.triggerOutput("flow");
        });

        registry.register("player_set_item_flags", (ctx, node) -> {
            ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
            String flags = ctx.getInputValue(node, "flags", String.class, "");
            if (item != null) {
                if (Bukkit.isPrimaryThread()) {
                    ItemMeta meta = item.getItemMeta();
                    if (meta != null) {
                        meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ATTRIBUTES);
                        meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS);
                        meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_UNBREAKABLE);
                        item.setItemMeta(meta);
                    }
                } else {
                    Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), () -> {
                        ItemMeta meta = item.getItemMeta();
                        if (meta != null) {
                            meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ATTRIBUTES);
                            meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS);
                            meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_UNBREAKABLE);
                            item.setItemMeta(meta);
                        }
                    });
                }
            }
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "item", item);
            ctx.triggerOutput("flow");
        });

        registry.register("player_set_item_custom_model", (ctx, node) -> {
            ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
            Integer modelData = ctx.getInputValue(node, "model_data", Integer.class, 0);
            if (item != null) {
                if (Bukkit.isPrimaryThread()) {
                    ItemMeta meta = item.getItemMeta();
                    if (meta != null) {
                        meta.setCustomModelData(modelData);
                        item.setItemMeta(meta);
                    }
                } else {
                    Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), () -> {
                        ItemMeta meta = item.getItemMeta();
                        if (meta != null) {
                            meta.setCustomModelData(modelData);
                            item.setItemMeta(meta);
                        }
                    });
                }
            }
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "item", item);
            ctx.triggerOutput("flow");
        });

        registry.register("player_set_item_unbreakable", (ctx, node) -> {
            ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
            Boolean unbreakable = ctx.getInputValue(node, "unbreakable", Boolean.class, true);
            if (item != null) {
                if (Bukkit.isPrimaryThread()) {
                    ItemMeta meta = item.getItemMeta();
                    if (meta != null) {
                        meta.setUnbreakable(unbreakable);
                        item.setItemMeta(meta);
                    }
                } else {
                    Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), () -> {
                        ItemMeta meta = item.getItemMeta();
                        if (meta != null) {
                            meta.setUnbreakable(unbreakable);
                            item.setItemMeta(meta);
                        }
                    });
                }
            }
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "item", item);
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
