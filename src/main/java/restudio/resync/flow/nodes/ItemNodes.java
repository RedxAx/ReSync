package restudio.resync.flow.nodes;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.*;
import restudio.flow.data.FlowNode;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.FlowRegistry;
import restudio.resync.flow.NodeCategory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class ItemNodes implements NodeCategory {

    @Override
    public void registerNodes(FlowRegistry registry) {
        registry.register("item_add_attribute", (ctx, node) -> {
            ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
            String attributeName = ctx.getInputValue(node, "attribute_name", String.class, "generic.max_health");
            Double amount = ctx.getInputValue(node, "amount", Double.class, 1.0);
            String operationName = ctx.getInputValue(node, "operation", String.class, "add_number");
            String slotName = ctx.getInputValue(node, "slot", String.class, "mainhand");
            String nodeId = findNodeId(ctx, node);

            if (item != null && item.hasItemMeta()) {
                Attribute attribute = switch (attributeName.toLowerCase()) {
                    case "generic.movement_speed" -> Attribute.GENERIC_MOVEMENT_SPEED;
                    case "generic.attack_damage" -> Attribute.GENERIC_ATTACK_DAMAGE;
                    case "generic.attack_speed" -> Attribute.GENERIC_ATTACK_SPEED;
                    case "generic.knockback_resistance" -> Attribute.GENERIC_KNOCKBACK_RESISTANCE;
                    case "generic.luck" -> Attribute.GENERIC_LUCK;
                    case "generic.max_health" -> Attribute.GENERIC_MAX_HEALTH;
                    case "generic.armor" -> Attribute.GENERIC_ARMOR;
                    case "generic.armor_toughness" -> Attribute.GENERIC_ARMOR_TOUGHNESS;
                    case "generic.attack_knockback" -> Attribute.GENERIC_ATTACK_KNOCKBACK;
                    case "generic.flying_speed" -> Attribute.GENERIC_FLYING_SPEED;
                    default -> null;
                };

                AttributeModifier.Operation operation = switch (operationName.toLowerCase()) {
                    case "add_scalar" -> AttributeModifier.Operation.ADD_SCALAR;
                    case "multiply_scalar" -> AttributeModifier.Operation.MULTIPLY_SCALAR_1;
                    default -> AttributeModifier.Operation.ADD_NUMBER;
                };

                org.bukkit.inventory.EquipmentSlotGroup slot = switch (slotName.toLowerCase()) {
                    case "offhand" -> org.bukkit.inventory.EquipmentSlotGroup.OFFHAND;
                    case "feet" -> org.bukkit.inventory.EquipmentSlotGroup.FEET;
                    case "legs" -> org.bukkit.inventory.EquipmentSlotGroup.LEGS;
                    case "chest" -> org.bukkit.inventory.EquipmentSlotGroup.CHEST;
                    case "head" -> org.bukkit.inventory.EquipmentSlotGroup.HEAD;
                    default -> org.bukkit.inventory.EquipmentSlotGroup.MAINHAND;
                };

                if (attribute != null && operation != null) {
                    AttributeModifier modifier = new AttributeModifier(NamespacedKey.fromString("minecraft:" + attributeName.toLowerCase()), amount, operation, slot);
                    if (Bukkit.isPrimaryThread()) {
                        ItemMeta meta = item.getItemMeta();
                        if (meta != null) {
                            meta.addAttributeModifier(attribute, modifier);
                            item.setItemMeta(meta);
                        }
                    } else {
                        Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), () -> {
                            ItemMeta meta = item.getItemMeta();
                            if (meta != null) {
                                meta.addAttributeModifier(attribute, modifier);
                                item.setItemMeta(meta);
                            }
                        });
                    }
                }
            }
            ctx.setNodeOutput(nodeId, "item", item);
            ctx.triggerOutput("flow");
        });

        registry.register("item_remove_attribute", (ctx, node) -> {
            ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
            String attributeName = ctx.getInputValue(node, "attribute_name", String.class, "generic.max_health");
            String nodeId = findNodeId(ctx, node);

            if (item != null && item.hasItemMeta()) {
                Attribute attribute = switch (attributeName.toLowerCase()) {
                    case "generic.movement_speed" -> Attribute.GENERIC_MOVEMENT_SPEED;
                    case "generic.attack_damage" -> Attribute.GENERIC_ATTACK_DAMAGE;
                    case "generic.attack_speed" -> Attribute.GENERIC_ATTACK_SPEED;
                    case "generic.knockback_resistance" -> Attribute.GENERIC_KNOCKBACK_RESISTANCE;
                    case "generic.luck" -> Attribute.GENERIC_LUCK;
                    case "generic.max_health" -> Attribute.GENERIC_MAX_HEALTH;
                    case "generic.armor" -> Attribute.GENERIC_ARMOR;
                    case "generic.armor_toughness" -> Attribute.GENERIC_ARMOR_TOUGHNESS;
                    case "generic.attack_knockback" -> Attribute.GENERIC_ATTACK_KNOCKBACK;
                    case "generic.flying_speed" -> Attribute.GENERIC_FLYING_SPEED;
                    default -> null;
                };

                if (attribute != null) {
                    if (Bukkit.isPrimaryThread()) {
                        ItemMeta meta = item.getItemMeta();
                        if (meta != null) {
                            meta.removeAttributeModifier(attribute);
                            item.setItemMeta(meta);
                        }
                    } else {
                        Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), () -> {
                            ItemMeta meta = item.getItemMeta();
                            if (meta != null) {
                                meta.removeAttributeModifier(attribute);
                                item.setItemMeta(meta);
                            }
                        });
                    }
                }
            }
            ctx.setNodeOutput(nodeId, "item", item);
            ctx.triggerOutput("flow");
        });

        registry.register("item_get_attributes", (ctx, node) -> {
            ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
            String nodeId = findNodeId(ctx, node);
            List<String> attributes = new ArrayList<>();

            if (item != null && item.hasItemMeta()) {
                if (Bukkit.isPrimaryThread()) {
                    ItemMeta meta = item.getItemMeta();
                    if (meta != null) {
                        for (Attribute attr : Attribute.values()) {
                            if (meta.hasAttributeModifiers() && meta.getAttributeModifiers(attr) != null && !meta.getAttributeModifiers(attr).isEmpty()) {
                                attributes.add(attr.getKey().toString());
                            }
                        }
                    }
                } else {
                    Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), () -> {
                        ItemMeta meta = item.getItemMeta();
                        if (meta != null) {
                            for (Attribute attr : Attribute.values()) {
                                if (meta.hasAttributeModifiers() && meta.getAttributeModifiers(attr) != null && !meta.getAttributeModifiers(attr).isEmpty()) {
                                    attributes.add(attr.getKey().toString());
                                }
                            }
                        }
                    });
                }
            }
            ctx.setNodeOutput(nodeId, "attributes_list", attributes);
            ctx.triggerOutput("flow");
        });

        registry.register("item_set_trim", (ctx, node) -> {
            ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
            String materialPattern = ctx.getInputValue(node, "material_pattern", String.class, "sentry");
            String materialType = ctx.getInputValue(node, "material_type", String.class, "quartz");
            String nodeId = findNodeId(ctx, node);

            if (item != null && item.hasItemMeta()) {
                org.bukkit.inventory.meta.trim.TrimMaterial trimMaterial = switch (materialType.toLowerCase()) {
                    case "iron" -> org.bukkit.inventory.meta.trim.TrimMaterial.IRON;
                    case "netherite" -> org.bukkit.inventory.meta.trim.TrimMaterial.NETHERITE;
                    case "redstone" -> org.bukkit.inventory.meta.trim.TrimMaterial.REDSTONE;
                    case "copper" -> org.bukkit.inventory.meta.trim.TrimMaterial.COPPER;
                    case "gold" -> org.bukkit.inventory.meta.trim.TrimMaterial.GOLD;
                    case "emerald" -> org.bukkit.inventory.meta.trim.TrimMaterial.EMERALD;
                    case "diamond" -> org.bukkit.inventory.meta.trim.TrimMaterial.DIAMOND;
                    case "lapis" -> org.bukkit.inventory.meta.trim.TrimMaterial.LAPIS;
                    case "amethyst" -> org.bukkit.inventory.meta.trim.TrimMaterial.AMETHYST;
                    case "quartz" -> org.bukkit.inventory.meta.trim.TrimMaterial.QUARTZ;
                    default -> null;
                };

                org.bukkit.inventory.meta.trim.TrimPattern trimPattern = switch (materialPattern.toLowerCase()) {
                    case "coast" -> org.bukkit.inventory.meta.trim.TrimPattern.COAST;
                    case "dune" -> org.bukkit.inventory.meta.trim.TrimPattern.DUNE;
                    case "eye" -> org.bukkit.inventory.meta.trim.TrimPattern.EYE;
                    case "rib" -> org.bukkit.inventory.meta.trim.TrimPattern.RIB;
                    case "sentry" -> org.bukkit.inventory.meta.trim.TrimPattern.SENTRY;
                    case "shaper" -> org.bukkit.inventory.meta.trim.TrimPattern.SHAPER;
                    case "silence" -> org.bukkit.inventory.meta.trim.TrimPattern.SILENCE;
                    case "snout" -> org.bukkit.inventory.meta.trim.TrimPattern.SNOUT;
                    case "spire" -> org.bukkit.inventory.meta.trim.TrimPattern.SPIRE;
                    case "tide" -> org.bukkit.inventory.meta.trim.TrimPattern.TIDE;
                    case "vex" -> org.bukkit.inventory.meta.trim.TrimPattern.VEX;
                    case "ward" -> org.bukkit.inventory.meta.trim.TrimPattern.WARD;
                    case "wild" -> org.bukkit.inventory.meta.trim.TrimPattern.WILD;
                    case "host" -> org.bukkit.inventory.meta.trim.TrimPattern.HOST;
                    case "raiser" -> org.bukkit.inventory.meta.trim.TrimPattern.RAISER;
                    case "wayfinder" -> org.bukkit.inventory.meta.trim.TrimPattern.WAYFINDER;
                    default -> null;
                };

                if (trimMaterial != null && trimPattern != null) {
                    if (Bukkit.isPrimaryThread()) {
                        ItemMeta meta = item.getItemMeta();
                        if (meta instanceof ArmorMeta armorMeta) {
                            armorMeta.setTrim(new org.bukkit.inventory.meta.trim.ArmorTrim(trimMaterial, trimPattern));
                            item.setItemMeta(armorMeta);
                        }
                    } else {
                        Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), () -> {
                            ItemMeta meta = item.getItemMeta();
                            if (meta instanceof ArmorMeta armorMeta) {
                                armorMeta.setTrim(new org.bukkit.inventory.meta.trim.ArmorTrim(trimMaterial, trimPattern));
                                item.setItemMeta(armorMeta);
                            }
                        });
                    }
                }
            }
            ctx.setNodeOutput(nodeId, "item", item);
            ctx.triggerOutput("flow");
        });

        registry.register("item_remove_trim", (ctx, node) -> {
            ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
            String nodeId = findNodeId(ctx, node);

            if (item != null && item.hasItemMeta()) {
                if (Bukkit.isPrimaryThread()) {
                    ItemMeta meta = item.getItemMeta();
                    if (meta instanceof ArmorMeta armorMeta) {
                        armorMeta.setTrim(null);
                        item.setItemMeta(armorMeta);
                    }
                } else {
                    Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), () -> {
                        ItemMeta meta = item.getItemMeta();
                        if (meta instanceof ArmorMeta armorMeta) {
                            armorMeta.setTrim(null);
                            item.setItemMeta(armorMeta);
                        }
                    });
                }
            }
            ctx.setNodeOutput(nodeId, "item", item);
            ctx.triggerOutput("flow");
        });

        registry.register("item_crossbow_charged", (ctx, node) -> {
            ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
            Boolean charged = ctx.getInputValue(node, "charged", Boolean.class, true);
            ItemStack projectileItem = ctx.getInputValue(node, "projectile_item", ItemStack.class, null);
            String nodeId = findNodeId(ctx, node);

            if (item != null && item.hasItemMeta()) {
                if (Bukkit.isPrimaryThread()) {
                    ItemMeta meta = item.getItemMeta();
                    if (meta instanceof CrossbowMeta crossbowMeta) {
                        crossbowMeta.getChargedProjectiles().clear();
                        if (charged && projectileItem != null) {
                            crossbowMeta.addChargedProjectile(projectileItem);
                        } else if (charged) {
                            crossbowMeta.addChargedProjectile(new ItemStack(Material.ARROW));
                        }
                        item.setItemMeta(crossbowMeta);
                    }
                } else {
                    Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), () -> {
                        ItemMeta meta = item.getItemMeta();
                        if (meta instanceof CrossbowMeta crossbowMeta) {
                            crossbowMeta.getChargedProjectiles().clear();
                            if (charged && projectileItem != null) {
                                crossbowMeta.addChargedProjectile(projectileItem);
                            } else if (charged) {
                                crossbowMeta.addChargedProjectile(new ItemStack(Material.ARROW));
                            }
                            item.setItemMeta(crossbowMeta);
                        }
                    });
                }
            }
            ctx.setNodeOutput(nodeId, "item", item);
            ctx.triggerOutput("flow");
        });

        registry.register("item_copy_nbt", (ctx, node) -> {
            ItemStack sourceItem = ctx.getInputValue(node, "source_item", ItemStack.class, null);
            ItemStack targetItem = ctx.getInputValue(node, "target_item", ItemStack.class, null);
            String nbtKey = ctx.getInputValue(node, "nbt_key", String.class, null);
            String nodeId = findNodeId(ctx, node);

            if (sourceItem != null && targetItem != null && sourceItem.hasItemMeta() && targetItem.hasItemMeta()) {
                if (Bukkit.isPrimaryThread()) {
                    ItemMeta sourceMeta = sourceItem.getItemMeta();
                    ItemMeta targetMeta = targetItem.getItemMeta();
                    if (sourceMeta != null && targetMeta != null) {
                        if (nbtKey != null && !nbtKey.isEmpty()) {
                            NamespacedKey key = NamespacedKey.fromString(nbtKey);
                            if (key != null && sourceMeta.getPersistentDataContainer().has(key, org.bukkit.persistence.PersistentDataType.STRING)) {
                                String value = sourceMeta.getPersistentDataContainer().get(key, org.bukkit.persistence.PersistentDataType.STRING);
                                targetMeta.getPersistentDataContainer().set(key, org.bukkit.persistence.PersistentDataType.STRING, value);
                            }
                        } else {
                            for (NamespacedKey key : sourceMeta.getPersistentDataContainer().getKeys()) {
                                String value = sourceMeta.getPersistentDataContainer().get(key, org.bukkit.persistence.PersistentDataType.STRING);
                                if (value != null) {
                                    targetMeta.getPersistentDataContainer().set(key, org.bukkit.persistence.PersistentDataType.STRING, value);
                                }
                            }
                        }
                        targetItem.setItemMeta(targetMeta);
                    }
                } else {
                    Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), () -> {
                        ItemMeta sourceMeta = sourceItem.getItemMeta();
                        ItemMeta targetMeta = targetItem.getItemMeta();
                        if (sourceMeta != null && targetMeta != null) {
                            if (nbtKey != null && !nbtKey.isEmpty()) {
                                NamespacedKey key = NamespacedKey.fromString(nbtKey);
                                if (key != null && sourceMeta.getPersistentDataContainer().has(key, org.bukkit.persistence.PersistentDataType.STRING)) {
                                    String value = sourceMeta.getPersistentDataContainer().get(key, org.bukkit.persistence.PersistentDataType.STRING);
                                    targetMeta.getPersistentDataContainer().set(key, org.bukkit.persistence.PersistentDataType.STRING, value);
                                }
                            } else {
                                for (NamespacedKey key : sourceMeta.getPersistentDataContainer().getKeys()) {
                                    String value = sourceMeta.getPersistentDataContainer().get(key, org.bukkit.persistence.PersistentDataType.STRING);
                                    if (value != null) {
                                        targetMeta.getPersistentDataContainer().set(key, org.bukkit.persistence.PersistentDataType.STRING, value);
                                    }
                                }
                            }
                            targetItem.setItemMeta(targetMeta);
                        }
                    });
                }
            }
            ctx.setNodeOutput(nodeId, "target_item", targetItem);
            ctx.triggerOutput("flow");
        });

        registry.register("item_get_nbt", (ctx, node) -> {
            ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
            String nbtKey = ctx.getInputValue(node, "nbt_key", String.class, "nbt_data");
            String nodeId = findNodeId(ctx, node);
            String[] nbtValue = {""};

            if (item != null && item.hasItemMeta()) {
                NamespacedKey key = NamespacedKey.fromString(nbtKey);
                if (key != null) {
                    if (Bukkit.isPrimaryThread()) {
                        ItemMeta meta = item.getItemMeta();
                        if (meta != null && meta.getPersistentDataContainer().has(key, org.bukkit.persistence.PersistentDataType.STRING)) {
                            nbtValue[0] = meta.getPersistentDataContainer().get(key, org.bukkit.persistence.PersistentDataType.STRING);
                        }
                    } else {
                        Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), () -> {
                            ItemMeta meta = item.getItemMeta();
                            if (meta != null && meta.getPersistentDataContainer().has(key, org.bukkit.persistence.PersistentDataType.STRING)) {
                                nbtValue[0] = meta.getPersistentDataContainer().get(key, org.bukkit.persistence.PersistentDataType.STRING);
                            }
                        });
                    }
                }
            }
            ctx.setNodeOutput(nodeId, "nbt_value", nbtValue[0]);
            ctx.triggerOutput("flow");
        });

        registry.register("item_set_nbt", (ctx, node) -> {
            ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
            String nbtKey = ctx.getInputValue(node, "nbt_key", String.class, "nbt_data");
            String nbtValue = ctx.getInputValue(node, "nbt_value", String.class, "");
            String nodeId = findNodeId(ctx, node);

            if (item != null && item.hasItemMeta()) {
                NamespacedKey key = NamespacedKey.fromString(nbtKey);
                if (key != null) {
                    if (Bukkit.isPrimaryThread()) {
                        ItemMeta meta = item.getItemMeta();
                        if (meta != null) {
                            meta.getPersistentDataContainer().set(key, org.bukkit.persistence.PersistentDataType.STRING, nbtValue);
                            item.setItemMeta(meta);
                        }
                    } else {
                        Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), () -> {
                            ItemMeta meta = item.getItemMeta();
                            if (meta != null) {
                                meta.getPersistentDataContainer().set(key, org.bukkit.persistence.PersistentDataType.STRING, nbtValue);
                                item.setItemMeta(meta);
                            }
                        });
                    }
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
                int maxDurability = item.getType().getMaxDurability();
                int clampedDamage = Math.max(0, Math.min(maxDurability, damage));
                if (Bukkit.isPrimaryThread()) {
                    item.setDurability((short) clampedDamage);
                } else {
                    Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), () -> item.setDurability((short) clampedDamage));
                }
            }
            ctx.setNodeOutput(nodeId, "item", item);
            ctx.triggerOutput("flow");
        });

        registry.register("item_get_damage", (ctx, node) -> {
            ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
            String nodeId = findNodeId(ctx, node);
            Integer damage = 0;

            if (item != null) {
                damage = (int) item.getDurability();
            }
            ctx.setNodeOutput(nodeId, "damage", damage);
            ctx.triggerOutput("flow");
        });

        registry.register("item_get_material", (ctx, node) -> {
            ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
            String nodeId = findNodeId(ctx, node);
            String material = item != null ? item.getType().name() : "";
            ctx.setNodeOutput(nodeId, "material", material);
            ctx.triggerOutput("flow");
        });

        registry.register("item_set_max_stack_size", (ctx, node) -> {
            ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
            Integer stackSize = ctx.getInputValue(node, "stack_size", Integer.class, 64);
            String nodeId = findNodeId(ctx, node);

            if (item != null && item.hasItemMeta()) {
                if (Bukkit.isPrimaryThread()) {
                    ItemMeta meta = item.getItemMeta();
                    if (meta != null) {
                        meta.setMaxStackSize(Math.max(1, Math.min(99, stackSize)));
                        item.setItemMeta(meta);
                    }
                } else {
                    Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), () -> {
                        ItemMeta meta = item.getItemMeta();
                        if (meta != null) {
                            meta.setMaxStackSize(Math.max(1, Math.min(99, stackSize)));
                            item.setItemMeta(meta);
                        }
                    });
                }
            }
            ctx.setNodeOutput(nodeId, "item", item);
            ctx.triggerOutput("flow");
        });

        registry.register("item_can_destroy", (ctx, node) -> {
            ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
            List<String> blocksList = ctx.getInputValue(node, "blocks_list", List.class, new ArrayList<>());
            String nodeId = findNodeId(ctx, node);

            if (item != null && item.hasItemMeta()) {
                if (Bukkit.isPrimaryThread()) {
                    ItemMeta meta = item.getItemMeta();
                    if (meta != null) {
                        List<Material> materials = new ArrayList<>();
                        for (String blockName : blocksList) {
                            Material material = Material.getMaterial(blockName.toUpperCase());
                            if (material != null && material.isBlock()) {
                                materials.add(material);
                            }
                        }
                        try {
                            meta.getClass().getMethod("setDestroyable", List.class).invoke(meta, materials);
                        } catch (Exception e) {
                        }
                        item.setItemMeta(meta);
                    }
                } else {
                    Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), () -> {
                        ItemMeta meta = item.getItemMeta();
                        if (meta != null) {
                            List<Material> materials = new ArrayList<>();
                            for (String blockName : blocksList) {
                                Material material = Material.getMaterial(blockName.toUpperCase());
                                if (material != null && material.isBlock()) {
                                    materials.add(material);
                                }
                            }
                            try {
                                meta.getClass().getMethod("setDestroyable", List.class).invoke(meta, materials);
                            } catch (Exception e) {
                            }
                            item.setItemMeta(meta);
                        }
                    });
                }
            }
            ctx.setNodeOutput(nodeId, "item", item);
            ctx.triggerOutput("flow");
        });

        registry.register("item_can_place_on", (ctx, node) -> {
            ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
            List<String> blocksList = ctx.getInputValue(node, "blocks_list", List.class, new ArrayList<>());
            String nodeId = findNodeId(ctx, node);

            if (item != null && item.hasItemMeta()) {
                if (Bukkit.isPrimaryThread()) {
                    ItemMeta meta = item.getItemMeta();
                    if (meta != null) {
                        List<Material> materials = new ArrayList<>();
                        for (String blockName : blocksList) {
                            Material material = Material.getMaterial(blockName.toUpperCase());
                            if (material != null && material.isBlock()) {
                                materials.add(material);
                            }
                        }
                        try {
                            meta.getClass().getMethod("setPlaceable", List.class).invoke(meta, materials);
                        } catch (Exception e) {
                        }
                        item.setItemMeta(meta);
                    }
                } else {
                    Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), () -> {
                        ItemMeta meta = item.getItemMeta();
                        if (meta != null) {
                            List<Material> materials = new ArrayList<>();
                            for (String blockName : blocksList) {
                                Material material = Material.getMaterial(blockName.toUpperCase());
                                if (material != null && material.isBlock()) {
                                    materials.add(material);
                                }
                            }
                            try {
                                meta.getClass().getMethod("setPlaceable", List.class).invoke(meta, materials);
                            } catch (Exception e) {
                            }
                            item.setItemMeta(meta);
                        }
                    });
                }
            }
            ctx.setNodeOutput(nodeId, "item", item);
            ctx.triggerOutput("flow");
        });

        registry.register("item_set_rarity", (ctx, node) -> {
            ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
            String rarityName = ctx.getInputValue(node, "rarity", String.class, "common");
            String nodeId = findNodeId(ctx, node);

            if (item != null && item.hasItemMeta()) {
                if (Bukkit.isPrimaryThread()) {
                    ItemMeta meta = item.getItemMeta();
                    if (meta != null) {
                        try {
                            Class<?> potionRarityClass = Class.forName("io.papermc.paper.potion.PotionRarity");
                            Object rarity = switch (rarityName.toLowerCase()) {
                                case "uncommon" -> potionRarityClass.getField("UNCOMMON").get(null);
                                case "rare" -> potionRarityClass.getField("RARE").get(null);
                                case "epic" -> potionRarityClass.getField("EPIC").get(null);
                                default -> potionRarityClass.getField("COMMON").get(null);
                            };
                            meta.getClass().getMethod("setPotionRarity", potionRarityClass).invoke(meta, rarity);
                        } catch (Exception e) {
                        }
                        item.setItemMeta(meta);
                    }
                } else {
                    Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), () -> {
                        ItemMeta meta = item.getItemMeta();
                        if (meta != null) {
                            try {
                                Class<?> potionRarityClass = Class.forName("io.papermc.paper.potion.PotionRarity");
                                Object rarity = switch (rarityName.toLowerCase()) {
                                    case "uncommon" -> potionRarityClass.getField("UNCOMMON").get(null);
                                    case "rare" -> potionRarityClass.getField("RARE").get(null);
                                    case "epic" -> potionRarityClass.getField("EPIC").get(null);
                                    default -> potionRarityClass.getField("COMMON").get(null);
                                };
                                meta.getClass().getMethod("setPotionRarity", potionRarityClass).invoke(meta, rarity);
                            } catch (Exception e) {
                            }
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
