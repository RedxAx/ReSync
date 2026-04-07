package restudio.resync.flow.nodes;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import restudio.flow.data.FlowNode;
import restudio.flow.data.FlowType;
import restudio.resync.ReSync;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.registry.DefineNode;
import restudio.resync.flow.registry.FlowPin;
import restudio.resync.flow.registry.NodeDefinition;
import restudio.resync.flow.util.TextFormatter;

public class PlayerInventoryNodes {

    @DefineNode(id = "player_give_item", displayName = "Give Item", category = NodeDefinition.NodeCategory.INVENTORY,
            inputs = {@FlowPin(name = "target", dataType = FlowType.PLAYER), @FlowPin(name = "item", dataType = FlowType.ITEMSTACK)},
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void giveItem(FlowContext ctx, FlowNode node) {
        Player target = ctx.getInputValue(node, "target", Player.class, null);
        ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
        if (target != null && item != null && item.getType() != Material.AIR) {
            runSync(() -> target.getInventory().addItem(item.clone()));
        }
        ctx.triggerOutput("flow");
    }

    @DefineNode(id = "player_take_item", displayName = "Take Item", category = NodeDefinition.NodeCategory.INVENTORY,
            inputs = {@FlowPin(name = "target", dataType = FlowType.PLAYER), @FlowPin(name = "item", dataType = FlowType.ITEMSTACK), @FlowPin(name = "amount", dataType = FlowType.NUMBER)},
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void takeItem(FlowContext ctx, FlowNode node) {
        Player target = ctx.getInputValue(node, "target", Player.class, null);
        ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
        Integer amount = ctx.getInputValue(node, "amount", Integer.class, 1);
        if (target != null && item != null && amount > 0) {
            ItemStack toRemove = item.clone();
            toRemove.setAmount(amount);
            runSync(() -> target.getInventory().removeItem(toRemove));
        }
        ctx.triggerOutput("flow");
    }

    @DefineNode(id = "player_set_item", displayName = "Set Slot Item", category = NodeDefinition.NodeCategory.INVENTORY,
            inputs = {@FlowPin(name = "target", dataType = FlowType.PLAYER), @FlowPin(name = "slot", dataType = FlowType.NUMBER), @FlowPin(name = "item", dataType = FlowType.ITEMSTACK)},
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void setItem(FlowContext ctx, FlowNode node) {
        Player target = ctx.getInputValue(node, "target", Player.class, null);
        Integer slot = ctx.getInputValue(node, "slot", Integer.class, 0);
        ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
        if (target != null && slot >= 0 && slot < 36) {
            runSync(() -> target.getInventory().setItem(slot, item));
        }
        ctx.triggerOutput("flow");
    }

    @DefineNode(id = "player_clear_slot", displayName = "Clear Slot", category = NodeDefinition.NodeCategory.INVENTORY,
            inputs = {@FlowPin(name = "target", dataType = FlowType.PLAYER), @FlowPin(name = "slot", dataType = FlowType.NUMBER)},
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void clearSlot(FlowContext ctx, FlowNode node) {
        Player target = ctx.getInputValue(node, "target", Player.class, null);
        Integer slot = ctx.getInputValue(node, "slot", Integer.class, 0);
        if (target != null && slot >= 0 && slot < 36) {
            runSync(() -> target.getInventory().setItem(slot, null));
        }
        ctx.triggerOutput("flow");
    }

    @DefineNode(id = "player_swap_items", displayName = "Swap Slots", category = NodeDefinition.NodeCategory.INVENTORY,
            inputs = {@FlowPin(name = "target", dataType = FlowType.PLAYER), @FlowPin(name = "slot1", dataType = FlowType.NUMBER), @FlowPin(name = "slot2", dataType = FlowType.NUMBER)},
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void swapItems(FlowContext ctx, FlowNode node) {
        Player target = ctx.getInputValue(node, "target", Player.class, null);
        Integer slot1 = ctx.getInputValue(node, "slot1", Integer.class, 0);
        Integer slot2 = ctx.getInputValue(node, "slot2", Integer.class, 1);
        if (target != null && slot1 >= 0 && slot1 < 36 && slot2 >= 0 && slot2 < 36) {
            runSync(() -> {
                ItemStack item1 = target.getInventory().getItem(slot1);
                ItemStack item2 = target.getInventory().getItem(slot2);
                target.getInventory().setItem(slot1, item2);
                target.getInventory().setItem(slot2, item1);
            });
        }
        ctx.triggerOutput("flow");
    }

    @DefineNode(id = "player_set_helmet", displayName = "Set Helmet", category = NodeDefinition.NodeCategory.INVENTORY,
            inputs = {@FlowPin(name = "target", dataType = FlowType.PLAYER), @FlowPin(name = "item", dataType = FlowType.ITEMSTACK)},
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void setHelmet(FlowContext ctx, FlowNode node) {
        Player target = ctx.getInputValue(node, "target", Player.class, null);
        ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
        if (target != null) {
            runSync(() -> target.getInventory().setItem(EquipmentSlot.HEAD, item));
        }
        ctx.triggerOutput("flow");
    }

    @DefineNode(id = "player_set_chestplate", displayName = "Set Chestplate", category = NodeDefinition.NodeCategory.INVENTORY,
            inputs = {@FlowPin(name = "target", dataType = FlowType.PLAYER), @FlowPin(name = "item", dataType = FlowType.ITEMSTACK)},
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void setChestplate(FlowContext ctx, FlowNode node) {
        Player target = ctx.getInputValue(node, "target", Player.class, null);
        ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
        if (target != null) {
            runSync(() -> target.getInventory().setItem(EquipmentSlot.CHEST, item));
        }
        ctx.triggerOutput("flow");
    }

    @DefineNode(id = "player_set_leggings", displayName = "Set Leggings", category = NodeDefinition.NodeCategory.INVENTORY,
            inputs = {@FlowPin(name = "target", dataType = FlowType.PLAYER), @FlowPin(name = "item", dataType = FlowType.ITEMSTACK)},
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void setLeggings(FlowContext ctx, FlowNode node) {
        Player target = ctx.getInputValue(node, "target", Player.class, null);
        ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
        if (target != null) {
            runSync(() -> target.getInventory().setItem(EquipmentSlot.LEGS, item));
        }
        ctx.triggerOutput("flow");
    }

    @DefineNode(id = "player_set_boots", displayName = "Set Boots", category = NodeDefinition.NodeCategory.INVENTORY,
            inputs = {@FlowPin(name = "target", dataType = FlowType.PLAYER), @FlowPin(name = "item", dataType = FlowType.ITEMSTACK)},
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void setBoots(FlowContext ctx, FlowNode node) {
        Player target = ctx.getInputValue(node, "target", Player.class, null);
        ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
        if (target != null) {
            runSync(() -> target.getInventory().setItem(EquipmentSlot.FEET, item));
        }
        ctx.triggerOutput("flow");
    }

    @DefineNode(id = "player_set_mainhand", displayName = "Set MainHand", category = NodeDefinition.NodeCategory.INVENTORY,
            inputs = {@FlowPin(name = "target", dataType = FlowType.PLAYER), @FlowPin(name = "item", dataType = FlowType.ITEMSTACK)},
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void setMainHand(FlowContext ctx, FlowNode node) {
        Player target = ctx.getInputValue(node, "target", Player.class, null);
        ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
        if (target != null) {
            runSync(() -> target.getInventory().setItemInMainHand(item));
        }
        ctx.triggerOutput("flow");
    }

    @DefineNode(id = "player_set_offhand", displayName = "Set OffHand", category = NodeDefinition.NodeCategory.INVENTORY,
            inputs = {@FlowPin(name = "target", dataType = FlowType.PLAYER), @FlowPin(name = "item", dataType = FlowType.ITEMSTACK)},
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void setOffHand(FlowContext ctx, FlowNode node) {
        Player target = ctx.getInputValue(node, "target", Player.class, null);
        ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
        if (target != null) {
            runSync(() -> target.getInventory().setItemInOffHand(item));
        }
        ctx.triggerOutput("flow");
    }

    @DefineNode(id = "player_set_inventory_title", displayName = "Open Inventory", category = NodeDefinition.NodeCategory.INVENTORY,
            inputs = {@FlowPin(name = "target", dataType = FlowType.PLAYER), @FlowPin(name = "title", dataType = FlowType.STRING)},
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void setInventoryTitle(FlowContext ctx, FlowNode node) {
        Player target = ctx.getInputValue(node, "target", Player.class, null);
        ctx.getInputValue(node, "title", String.class, "Inventory");
        if (target != null) {
            runSync(() -> target.openInventory(target.getInventory()));
        }
        ctx.triggerOutput("flow");
    }

    @DefineNode(id = "player_set_armor_color", displayName = "Set Armor Color", category = NodeDefinition.NodeCategory.INVENTORY,
            inputs = {
                    @FlowPin(name = "target", dataType = FlowType.PLAYER),
                    @FlowPin(name = "slot", dataType = FlowType.ANY),
                    @FlowPin(name = "red", dataType = FlowType.NUMBER),
                    @FlowPin(name = "green", dataType = FlowType.NUMBER),
                    @FlowPin(name = "blue", dataType = FlowType.NUMBER)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void setArmorColor(FlowContext ctx, FlowNode node) {
        Player target = ctx.getInputValue(node, "target", Player.class, null);
        EquipmentSlot slot = ctx.getInputValue(node, "slot", EquipmentSlot.class, EquipmentSlot.CHEST);
        Integer red = ctx.getInputValue(node, "red", Integer.class, 255);
        Integer green = ctx.getInputValue(node, "green", Integer.class, 255);
        Integer blue = ctx.getInputValue(node, "blue", Integer.class, 255);
        if (target != null) {
            runSync(() -> {
                ItemStack item = target.getInventory().getItem(slot);
                if (item != null && item.getItemMeta() instanceof LeatherArmorMeta meta) {
                    meta.setColor(Color.fromRGB(red, green, blue));
                    item.setItemMeta(meta);
                }
            });
        }
        ctx.triggerOutput("flow");
    }

    @DefineNode(id = "player_repair_item", displayName = "Repair Item", category = NodeDefinition.NodeCategory.INVENTORY,
            inputs = {@FlowPin(name = "item", dataType = FlowType.ITEMSTACK)},
            outputs = {@FlowPin(name = "item", dataType = FlowType.ITEMSTACK), @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void repairItem(FlowContext ctx, FlowNode node) {
        ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
        if (item != null) {
            runSync(() -> item.setDurability((short) 0));
        }
        ctx.setOutput(node, "item", item);
        ctx.triggerOutput("flow");
    }

    @DefineNode(id = "player_enchant_item", displayName = "Enchant Item", category = NodeDefinition.NodeCategory.INVENTORY,
            inputs = {@FlowPin(name = "item", dataType = FlowType.ITEMSTACK), @FlowPin(name = "enchantment", dataType = FlowType.STRING), @FlowPin(name = "level", dataType = FlowType.NUMBER)},
            outputs = {@FlowPin(name = "item", dataType = FlowType.ITEMSTACK), @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void enchantItem(FlowContext ctx, FlowNode node) {
        ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
        String enchantName = ctx.getInputValue(node, "enchantment", String.class, "");
        Integer level = ctx.getInputValue(node, "level", Integer.class, 1);
        if (item != null && item.hasItemMeta()) {
            Enchantment enchant = Enchantment.getByKey(NamespacedKey.minecraft(enchantName.toLowerCase()));
            if (enchant != null) {
                runSync(() -> {
                    ItemMeta meta = item.getItemMeta();
                    if (meta != null) {
                        meta.addEnchant(enchant, level, true);
                        item.setItemMeta(meta);
                    }
                });
            }
        }
        ctx.setOutput(node, "item", item);
        ctx.triggerOutput("flow");
    }

    @DefineNode(id = "player_unenchant_item", displayName = "Unenchant Item", category = NodeDefinition.NodeCategory.INVENTORY,
            inputs = {@FlowPin(name = "item", dataType = FlowType.ITEMSTACK), @FlowPin(name = "enchantment", dataType = FlowType.STRING)},
            outputs = {@FlowPin(name = "item", dataType = FlowType.ITEMSTACK), @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void unenchantItem(FlowContext ctx, FlowNode node) {
        ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
        String enchantName = ctx.getInputValue(node, "enchantment", String.class, "");
        if (item != null && item.hasItemMeta()) {
            Enchantment enchant = Enchantment.getByKey(NamespacedKey.minecraft(enchantName.toLowerCase()));
            if (enchant != null) {
                runSync(() -> {
                    ItemMeta meta = item.getItemMeta();
                    if (meta != null) {
                        meta.removeEnchant(enchant);
                        item.setItemMeta(meta);
                    }
                });
            }
        }
        ctx.setOutput(node, "item", item);
        ctx.triggerOutput("flow");
    }

    @DefineNode(id = "player_clear_enchants", displayName = "Clear Enchants", category = NodeDefinition.NodeCategory.INVENTORY,
            inputs = {@FlowPin(name = "item", dataType = FlowType.ITEMSTACK)},
            outputs = {@FlowPin(name = "item", dataType = FlowType.ITEMSTACK), @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void clearEnchants(FlowContext ctx, FlowNode node) {
        ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
        if (item != null && item.hasItemMeta()) {
            runSync(() -> {
                ItemMeta meta = item.getItemMeta();
                if (meta != null) {
                    meta.getEnchants().keySet().forEach(meta::removeEnchant);
                    item.setItemMeta(meta);
                }
            });
        }
        ctx.setOutput(node, "item", item);
        ctx.triggerOutput("flow");
    }

    @DefineNode(id = "player_set_item_name", displayName = "Set Item Name", category = NodeDefinition.NodeCategory.INVENTORY,
            inputs = {@FlowPin(name = "item", dataType = FlowType.ITEMSTACK), @FlowPin(name = "name", dataType = FlowType.STRING)},
            outputs = {@FlowPin(name = "item", dataType = FlowType.ITEMSTACK), @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void setItemName(FlowContext ctx, FlowNode node) {
        ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
        String name = ctx.getInputValue(node, "name", String.class, "");
        if (item != null) {
            runSync(() -> {
                ItemMeta meta = item.getItemMeta();
                if (meta != null) {
                    meta.displayName(TextFormatter.parse(name));
                    item.setItemMeta(meta);
                }
            });
        }
        ctx.setOutput(node, "item", item);
        ctx.triggerOutput("flow");
    }

    @DefineNode(id = "player_set_item_lore", displayName = "Set Item Lore", category = NodeDefinition.NodeCategory.INVENTORY,
            inputs = {@FlowPin(name = "item", dataType = FlowType.ITEMSTACK), @FlowPin(name = "lore", dataType = FlowType.STRING)},
            outputs = {@FlowPin(name = "item", dataType = FlowType.ITEMSTACK), @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void setItemLore(FlowContext ctx, FlowNode node) {
        ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
        String lore = ctx.getInputValue(node, "lore", String.class, "");
        if (item != null) {
            runSync(() -> {
                ItemMeta meta = item.getItemMeta();
                if (meta != null) {
                    meta.lore(TextFormatter.parseLines(lore));
                    item.setItemMeta(meta);
                }
            });
        }
        ctx.setOutput(node, "item", item);
        ctx.triggerOutput("flow");
    }

    @DefineNode(id = "player_set_item_flags", displayName = "Set Item Flags", category = NodeDefinition.NodeCategory.INVENTORY,
            inputs = {@FlowPin(name = "item", dataType = FlowType.ITEMSTACK), @FlowPin(name = "flags", dataType = FlowType.STRING)},
            outputs = {@FlowPin(name = "item", dataType = FlowType.ITEMSTACK), @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void setItemFlags(FlowContext ctx, FlowNode node) {
        ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
        ctx.getInputValue(node, "flags", String.class, "");
        if (item != null) {
            runSync(() -> {
                ItemMeta meta = item.getItemMeta();
                if (meta != null) {
                    meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
                    meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
                    meta.addItemFlags(ItemFlag.HIDE_UNBREAKABLE);
                    item.setItemMeta(meta);
                }
            });
        }
        ctx.setOutput(node, "item", item);
        ctx.triggerOutput("flow");
    }

    @DefineNode(id = "player_set_item_custom_model", displayName = "Set Custom Model", category = NodeDefinition.NodeCategory.INVENTORY,
            inputs = {@FlowPin(name = "item", dataType = FlowType.ITEMSTACK), @FlowPin(name = "model_data", dataType = FlowType.NUMBER)},
            outputs = {@FlowPin(name = "item", dataType = FlowType.ITEMSTACK), @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void setItemCustomModel(FlowContext ctx, FlowNode node) {
        ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
        Integer modelData = ctx.getInputValue(node, "model_data", Integer.class, 0);
        if (item != null) {
            runSync(() -> {
                ItemMeta meta = item.getItemMeta();
                if (meta != null) {
                    meta.setCustomModelData(modelData);
                    item.setItemMeta(meta);
                }
            });
        }
        ctx.setOutput(node, "item", item);
        ctx.triggerOutput("flow");
    }

    @DefineNode(id = "player_set_item_unbreakable", displayName = "Set Unbreakable", category = NodeDefinition.NodeCategory.INVENTORY,
            inputs = {@FlowPin(name = "item", dataType = FlowType.ITEMSTACK), @FlowPin(name = "unbreakable", dataType = FlowType.BOOLEAN)},
            outputs = {@FlowPin(name = "item", dataType = FlowType.ITEMSTACK), @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void setItemUnbreakable(FlowContext ctx, FlowNode node) {
        ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
        Boolean unbreakable = ctx.getInputValue(node, "unbreakable", Boolean.class, true);
        if (item != null) {
            runSync(() -> {
                ItemMeta meta = item.getItemMeta();
                if (meta != null) {
                    meta.setUnbreakable(unbreakable);
                    item.setItemMeta(meta);
                }
            });
        }
        ctx.setOutput(node, "item", item);
        ctx.triggerOutput("flow");
    }

    private void runSync(Runnable runnable) {
        if (Bukkit.isPrimaryThread()) {
            runnable.run();
        } else {
            Bukkit.getScheduler().runTask(ReSync.getInstance(), runnable);
        }
    }
}
