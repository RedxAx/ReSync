package restudio.resync.flow.nodes;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.*;
import org.bukkit.persistence.PersistentDataType;
import restudio.flow.data.FlowNode;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.FlowRegistry;
import restudio.resync.flow.NodeCategory;
import restudio.resync.flow.util.TextFormatter;

import java.util.ArrayList;
import java.util.List;

public class ItemCreationNodes implements NodeCategory {

    @Override
    public void registerNodes(FlowRegistry registry) {
        registry.register("item_create", (ctx, node) -> {
            String materialName = ctx.getInputValue(node, "material", String.class, "STONE");
            Integer amount = ctx.getInputValue(node, "amount", Integer.class, 1);
            String nodeId = findNodeId(ctx, node);
            
            Material material = Material.getMaterial(materialName.toUpperCase());
            ItemStack item = null;
            if (material != null) {
                item = new ItemStack(material, Math.max(1, amount));
            }
            ctx.setNodeOutput(nodeId, "item", item);
            ctx.triggerOutput("flow");
        });

        registry.register("item_set_material", (ctx, node) -> {
            ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
            String materialName = ctx.getInputValue(node, "material", String.class, "STONE");
            String nodeId = findNodeId(ctx, node);
            
            if (item != null) {
                Material material = Material.getMaterial(materialName.toUpperCase());
                if (material != null) {
                    if (Bukkit.isPrimaryThread()) {
                        item.setType(material);
                    } else {
                        Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), () -> item.setType(material));
                    }
                }
            }
            ctx.setNodeOutput(nodeId, "item", item);
            ctx.triggerOutput("flow");
        });

        registry.register("item_set_amount", (ctx, node) -> {
            ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
            Integer amount = ctx.getInputValue(node, "amount", Integer.class, 1);
            String nodeId = findNodeId(ctx, node);
            
            if (item != null) {
                if (Bukkit.isPrimaryThread()) {
                    item.setAmount(Math.max(1, Math.min(64, amount)));
                } else {
                    Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), () -> 
                        item.setAmount(Math.max(1, Math.min(64, amount))));
                }
            }
            ctx.setNodeOutput(nodeId, "item", item);
            ctx.triggerOutput("flow");
        });

        registry.register("item_set_damage", (ctx, node) -> {
            ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
            Integer damage = ctx.getInputValue(node, "damage", Integer.class, 0);
            String nodeId = findNodeId(ctx, node);
            
            if (item != null) {
                if (Bukkit.isPrimaryThread()) {
                    item.setDurability((short) Math.max(0, damage));
                } else {
                    Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), () -> 
                        item.setDurability((short) Math.max(0, damage)));
                }
            }
            ctx.setNodeOutput(nodeId, "item", item);
            ctx.triggerOutput("flow");
        });

        registry.register("item_set_max_damage", (ctx, node) -> {
            ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
            Integer maxDamage = ctx.getInputValue(node, "max_damage", Integer.class, 100);
            String nodeId = findNodeId(ctx, node);
            
            ctx.setNodeOutput(nodeId, "item", item);
            ctx.triggerOutput("flow");
        });

        registry.register("item_set_unbreakable", (ctx, node) -> {
            ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
            Boolean unbreakable = ctx.getInputValue(node, "unbreakable", Boolean.class, true);
            String nodeId = findNodeId(ctx, node);
            
            if (item != null && item.hasItemMeta()) {
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
            ctx.setNodeOutput(nodeId, "item", item);
            ctx.triggerOutput("flow");
        });

        registry.register("item_set_custom_name", (ctx, node) -> {
            ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
            String name = ctx.getInputValue(node, "name", String.class, "");
            String nodeId = findNodeId(ctx, node);
            
            if (item != null && item.hasItemMeta()) {
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
            ctx.setNodeOutput(nodeId, "item", item);
            ctx.triggerOutput("flow");
        });

        registry.register("item_set_lore", (ctx, node) -> {
            ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
            String lore = ctx.getInputValue(node, "lore", String.class, "");
            String nodeId = findNodeId(ctx, node);
            
            if (item != null && item.hasItemMeta()) {
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
            ctx.setNodeOutput(nodeId, "item", item);
            ctx.triggerOutput("flow");
        });

        registry.register("item_add_lore", (ctx, node) -> {
            ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
            String loreLine = ctx.getInputValue(node, "lore", String.class, "");
            String nodeId = findNodeId(ctx, node);
            
            if (item != null && item.hasItemMeta()) {
                if (Bukkit.isPrimaryThread()) {
                    ItemMeta meta = item.getItemMeta();
                    if (meta != null) {
                        List<net.kyori.adventure.text.Component> loreList = meta.lore() != null ? new ArrayList<>(meta.lore()) : new ArrayList<>();
                        loreList.add(TextFormatter.parse(loreLine));
                        meta.lore(loreList);
                        item.setItemMeta(meta);
                    }
                } else {
                    Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), () -> {
                        ItemMeta meta = item.getItemMeta();
                        if (meta != null) {
                            List<net.kyori.adventure.text.Component> loreList = meta.lore() != null ? new ArrayList<>(meta.lore()) : new ArrayList<>();
                            loreList.add(TextFormatter.parse(loreLine));
                            meta.lore(loreList);
                            item.setItemMeta(meta);
                        }
                    });
                }
            }
            ctx.setNodeOutput(nodeId, "item", item);
            ctx.triggerOutput("flow");
        });

        registry.register("item_clear_lore", (ctx, node) -> {
            ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
            String nodeId = findNodeId(ctx, node);
            
            if (item != null && item.hasItemMeta()) {
                if (Bukkit.isPrimaryThread()) {
                    ItemMeta meta = item.getItemMeta();
                    if (meta != null) {
                        meta.lore(null);
                        item.setItemMeta(meta);
                    }
                } else {
                    Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), () -> {
                        ItemMeta meta = item.getItemMeta();
                        if (meta != null) {
                            meta.lore(null);
                            item.setItemMeta(meta);
                        }
                    });
                }
            }
            ctx.setNodeOutput(nodeId, "item", item);
            ctx.triggerOutput("flow");
        });

        registry.register("item_set_flags", (ctx, node) -> {
            ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
            String flags = ctx.getInputValue(node, "flags", String.class, "");
            String nodeId = findNodeId(ctx, node);
            
            if (item != null && item.hasItemMeta()) {
                if (Bukkit.isPrimaryThread()) {
                    ItemMeta meta = item.getItemMeta();
                    if (meta != null) {
                        meta.addItemFlags(org.bukkit.inventory.ItemFlag.values());
                        item.setItemMeta(meta);
                    }
                } else {
                    Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), () -> {
                        ItemMeta meta = item.getItemMeta();
                        if (meta != null) {
                            meta.addItemFlags(org.bukkit.inventory.ItemFlag.values());
                            item.setItemMeta(meta);
                        }
                    });
                }
            }
            ctx.setNodeOutput(nodeId, "item", item);
            ctx.triggerOutput("flow");
        });

        registry.register("item_add_flag", (ctx, node) -> {
            ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
            String flagName = ctx.getInputValue(node, "flag", String.class, "");
            String nodeId = findNodeId(ctx, node);
            
            if (item != null && item.hasItemMeta() && !flagName.isEmpty()) {
                try {
                    org.bukkit.inventory.ItemFlag flag = org.bukkit.inventory.ItemFlag.valueOf(flagName.toUpperCase());
                    if (Bukkit.isPrimaryThread()) {
                        ItemMeta meta = item.getItemMeta();
                        if (meta != null) {
                            meta.addItemFlags(flag);
                            item.setItemMeta(meta);
                        }
                    } else {
                        Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), () -> {
                            ItemMeta meta = item.getItemMeta();
                            if (meta != null) {
                                meta.addItemFlags(flag);
                                item.setItemMeta(meta);
                            }
                        });
                    }
                } catch (IllegalArgumentException e) {
                }
            }
            ctx.setNodeOutput(nodeId, "item", item);
            ctx.triggerOutput("flow");
        });

        registry.register("item_remove_flag", (ctx, node) -> {
            ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
            String flagName = ctx.getInputValue(node, "flag", String.class, "");
            String nodeId = findNodeId(ctx, node);
            
            if (item != null && item.hasItemMeta() && !flagName.isEmpty()) {
                try {
                    org.bukkit.inventory.ItemFlag flag = org.bukkit.inventory.ItemFlag.valueOf(flagName.toUpperCase());
                    if (Bukkit.isPrimaryThread()) {
                        ItemMeta meta = item.getItemMeta();
                        if (meta != null) {
                            meta.removeItemFlags(flag);
                            item.setItemMeta(meta);
                        }
                    } else {
                        Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), () -> {
                            ItemMeta meta = item.getItemMeta();
                            if (meta != null) {
                                meta.removeItemFlags(flag);
                                item.setItemMeta(meta);
                            }
                        });
                    }
                } catch (IllegalArgumentException e) {
                }
            }
            ctx.setNodeOutput(nodeId, "item", item);
            ctx.triggerOutput("flow");
        });

        registry.register("item_add_enchant", (ctx, node) -> {
            ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
            String enchantName = ctx.getInputValue(node, "enchantment", String.class, "");
            Integer level = ctx.getInputValue(node, "level", Integer.class, 1);
            String nodeId = findNodeId(ctx, node);
            
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
                            Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), () -> {
                                ItemMeta meta = item.getItemMeta();
                                if (meta != null) {
                                    meta.addEnchant(enchant, Math.max(1, level), true);
                                    item.setItemMeta(meta);
                                }
                            });
                        }
                    }
                } catch (Exception e) {
                }
            }
            ctx.setNodeOutput(nodeId, "item", item);
            ctx.triggerOutput("flow");
        });

        registry.register("item_remove_enchant", (ctx, node) -> {
            ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
            String enchantName = ctx.getInputValue(node, "enchantment", String.class, "");
            String nodeId = findNodeId(ctx, node);
            
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
            ctx.setNodeOutput(nodeId, "item", item);
            ctx.triggerOutput("flow");
        });

        registry.register("item_clear_enchants", (ctx, node) -> {
            ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
            String nodeId = findNodeId(ctx, node);
            
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
            ctx.setNodeOutput(nodeId, "item", item);
            ctx.triggerOutput("flow");
        });

        registry.register("item_set_custom_model", (ctx, node) -> {
            ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
            Integer modelData = ctx.getInputValue(node, "model_data", Integer.class, 0);
            String nodeId = findNodeId(ctx, node);
            
            if (item != null && item.hasItemMeta()) {
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
            ctx.setNodeOutput(nodeId, "item", item);
            ctx.triggerOutput("flow");
        });

        registry.register("item_set_color", (ctx, node) -> {
            ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
            Integer red = ctx.getInputValue(node, "red", Integer.class, 255);
            Integer green = ctx.getInputValue(node, "green", Integer.class, 255);
            Integer blue = ctx.getInputValue(node, "blue", Integer.class, 255);
            String nodeId = findNodeId(ctx, node);
            
            if (item != null && item.hasItemMeta() && item.getItemMeta() instanceof LeatherArmorMeta) {
                if (Bukkit.isPrimaryThread()) {
                    LeatherArmorMeta meta = (LeatherArmorMeta) item.getItemMeta();
                    if (meta != null) {
                        meta.setColor(Color.fromRGB(red, green, blue));
                        item.setItemMeta(meta);
                    }
                } else {
                    Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), () -> {
                        LeatherArmorMeta meta = (LeatherArmorMeta) item.getItemMeta();
                        if (meta != null) {
                            meta.setColor(Color.fromRGB(red, green, blue));
                            item.setItemMeta(meta);
                        }
                    });
                }
            }
            ctx.setNodeOutput(nodeId, "item", item);
            ctx.triggerOutput("flow");
        });

        registry.register("item_set_skull_owner", (ctx, node) -> {
            ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
            String owner = ctx.getInputValue(node, "owner", String.class, "");
            String nodeId = findNodeId(ctx, node);
            
            if (item != null && item.hasItemMeta() && item.getItemMeta() instanceof SkullMeta && !owner.isEmpty()) {
                if (Bukkit.isPrimaryThread()) {
                    SkullMeta meta = (SkullMeta) item.getItemMeta();
                    if (meta != null) {
                        meta.setOwningPlayer(Bukkit.getOfflinePlayer(owner));
                        item.setItemMeta(meta);
                    }
                } else {
                    Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), () -> {
                        SkullMeta meta = (SkullMeta) item.getItemMeta();
                        if (meta != null) {
                            meta.setOwningPlayer(Bukkit.getOfflinePlayer(owner));
                            item.setItemMeta(meta);
                        }
                    });
                }
            }
            ctx.setNodeOutput(nodeId, "item", item);
            ctx.triggerOutput("flow");
        });

        registry.register("item_set_book_pages", (ctx, node) -> {
            ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
            String title = ctx.getInputValue(node, "title", String.class, "");
            String author = ctx.getInputValue(node, "author", String.class, "");
            String pages = ctx.getInputValue(node, "pages", String.class, "");
            String nodeId = findNodeId(ctx, node);
            
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
                    Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), () -> {
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
            ctx.setNodeOutput(nodeId, "item", item);
            ctx.triggerOutput("flow");
        });

        registry.register("item_set_potion_effect", (ctx, node) -> {
            ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
            String effectName = ctx.getInputValue(node, "effect", String.class, "");
            Integer duration = ctx.getInputValue(node, "duration", Integer.class, 200);
            Integer amplifier = ctx.getInputValue(node, "amplifier", Integer.class, 0);
            String nodeId = findNodeId(ctx, node);
            
            if (item != null && item.hasItemMeta() && item.getItemMeta() instanceof PotionMeta && !effectName.isEmpty()) {
                try {
                    org.bukkit.potion.PotionEffectType type = org.bukkit.potion.PotionEffectType.getByName(effectName.toUpperCase());
                    if (type != null) {
                        org.bukkit.potion.PotionEffect effect = new org.bukkit.potion.PotionEffect(type, duration, amplifier);
                        if (Bukkit.isPrimaryThread()) {
                            PotionMeta meta = (PotionMeta) item.getItemMeta();
                            if (meta != null) {
                                meta.addCustomEffect(effect, true);
                                item.setItemMeta(meta);
                            }
                        } else {
                            Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), () -> {
                                PotionMeta meta = (PotionMeta) item.getItemMeta();
                                if (meta != null) {
                                    meta.addCustomEffect(effect, true);
                                    item.setItemMeta(meta);
                                }
                            });
                        }
                    }
                } catch (Exception e) {
                }
            }
            ctx.setNodeOutput(nodeId, "item", item);
            ctx.triggerOutput("flow");
        });

        registry.register("item_get_nbt", (ctx, node) -> {
            ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
            String nodeId = findNodeId(ctx, node);
            
            if (item != null && item.hasItemMeta()) {
                if (Bukkit.isPrimaryThread()) {
                    ItemMeta meta = item.getItemMeta();
                    if (meta != null && meta.getPersistentDataContainer().has(NamespacedKey.minecraft("nbt_data"), PersistentDataType.STRING)) {
                        String nbt = meta.getPersistentDataContainer().get(NamespacedKey.minecraft("nbt_data"), PersistentDataType.STRING);
                        ctx.setNodeOutput(nodeId, "nbt", nbt);
                    } else {
                        ctx.setNodeOutput(nodeId, "nbt", "");
                    }
                } else {
                    Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), () -> {
                        ItemMeta meta = item.getItemMeta();
                        if (meta != null && meta.getPersistentDataContainer().has(NamespacedKey.minecraft("nbt_data"), PersistentDataType.STRING)) {
                            String nbt = meta.getPersistentDataContainer().get(NamespacedKey.minecraft("nbt_data"), PersistentDataType.STRING);
                            ctx.setNodeOutput(nodeId, "nbt", nbt);
                        } else {
                            ctx.setNodeOutput(nodeId, "nbt", "");
                        }
                    });
                }
            } else {
                ctx.setNodeOutput(nodeId, "nbt", "");
            }
            ctx.triggerOutput("flow");
        });

        registry.register("item_set_nbt", (ctx, node) -> {
            ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
            String nbt = ctx.getInputValue(node, "nbt", String.class, "");
            String nodeId = findNodeId(ctx, node);
            
            if (item != null && item.hasItemMeta()) {
                if (Bukkit.isPrimaryThread()) {
                    ItemMeta meta = item.getItemMeta();
                    if (meta != null) {
                        meta.getPersistentDataContainer().set(NamespacedKey.minecraft("nbt_data"), PersistentDataType.STRING, nbt);
                        item.setItemMeta(meta);
                    }
                } else {
                    Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), () -> {
                        ItemMeta meta = item.getItemMeta();
                        if (meta != null) {
                            meta.getPersistentDataContainer().set(NamespacedKey.minecraft("nbt_data"), PersistentDataType.STRING, nbt);
                            item.setItemMeta(meta);
                        }
                    });
                }
            }
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
