package restudio.resync.flow.nodes;

import org.bukkit.Bukkit;
import restudio.resync.Log;
import org.bukkit.DyeColor;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.AnimalTamer;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Axolotl;
import org.bukkit.entity.Cat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Fox;
import org.bukkit.entity.Frog;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Llama;
import org.bukkit.entity.Mob;
import org.bukkit.entity.MushroomCow;
import org.bukkit.entity.Parrot;
import org.bukkit.entity.Rabbit;
import org.bukkit.entity.Sittable;
import org.bukkit.entity.Tameable;
import org.bukkit.entity.TropicalFish;
import org.bukkit.entity.Villager;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import restudio.flow.data.FlowNode;
import restudio.flow.data.FlowType;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.registry.DefineNode;
import restudio.resync.flow.registry.FlowPin;
import restudio.resync.flow.registry.NodeDefinition;
import restudio.resync.flow.registry.VisibleWhen;

public class EntityControlNodes {

    @DefineNode(id = "entity_set_type", displayName = "Entity Set Type", category = NodeDefinition.NodeCategory.ENTITY,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "entity", dataType = FlowType.ENTITY),
                    @FlowPin(name = "entity_type", dataType = FlowType.STRING, widget = NodeDefinition.WidgetType.SEARCHABLE_LIST,
                            optionsSource = "minecraft:entity_type")
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void entitySetType(FlowContext ctx, FlowNode node) {
        Entity entity = ctx.getInputValue(node, "entity", Entity.class, null);
        String entityType = ctx.getInputValue(node, "entity_type", String.class, "PIG");
        if (entity != null && entityType != null) {
            try {
                Location loc = entity.getLocation();
                entity.remove();
                EntityType newType = EntityType.valueOf(entityType.toUpperCase());
                if (loc.getWorld() != null) {
                    loc.getWorld().spawnEntity(loc, newType);
                }
            } catch (IllegalArgumentException e) {
                Log.warn("[Flow] Invalid entity type: " + entityType);
            }
        }
        ctx.triggerOutput("flow");
    }

    @DefineNode(id = "entity_set_rotation", displayName = "Entity Set Rotation", category = NodeDefinition.NodeCategory.ENTITY,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "entity", dataType = FlowType.ENTITY),
                    @FlowPin(name = "yaw", dataType = FlowType.NUMBER),
                    @FlowPin(name = "pitch", dataType = FlowType.NUMBER)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void entitySetRotation(FlowContext ctx, FlowNode node) {
        Entity entity = ctx.getInputValue(node, "entity", Entity.class, null);
        if (entity != null) {
            Location location = entity.getLocation();
            Float yaw = ctx.getInputValue(node, "yaw", Float.class, location.getYaw());
            Float pitch = ctx.getInputValue(node, "pitch", Float.class, location.getPitch());
            location.setYaw(yaw);
            location.setPitch(pitch);
            entity.teleport(location);
        }
        ctx.triggerOutput("flow");
    }

    @DefineNode(id = "entity_set_damage", displayName = "Entity Set Damage", category = NodeDefinition.NodeCategory.ENTITY,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "entity", dataType = FlowType.ENTITY),
                    @FlowPin(name = "damage", dataType = FlowType.NUMBER)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void entitySetDamage(FlowContext ctx, FlowNode node) {
        Entity entity = ctx.getInputValue(node, "entity", Entity.class, null);
        Double damage = ctx.getInputValue(node, "damage", Double.class, 1.0);
        if (entity instanceof LivingEntity living && living.getAttribute(Attribute.GENERIC_ATTACK_DAMAGE) != null) {
            living.getAttribute(Attribute.GENERIC_ATTACK_DAMAGE).setBaseValue(damage);
        }
        ctx.triggerOutput("flow");
    }

    @DefineNode(id = "entity_set_armor_value", displayName = "Entity Set Armor Value", category = NodeDefinition.NodeCategory.ENTITY,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "entity", dataType = FlowType.ENTITY),
                    @FlowPin(name = "armor", dataType = FlowType.NUMBER)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void entitySetArmorValue(FlowContext ctx, FlowNode node) {
        Entity entity = ctx.getInputValue(node, "entity", Entity.class, null);
        Double armor = ctx.getInputValue(node, "armor", Double.class, 0.0);
        if (entity instanceof LivingEntity living && living.getAttribute(Attribute.GENERIC_ARMOR) != null) {
            living.getAttribute(Attribute.GENERIC_ARMOR).setBaseValue(armor);
        }
        ctx.triggerOutput("flow");
    }

    @DefineNode(id = "entity_set_follow_range", displayName = "Entity Set Follow Range", category = NodeDefinition.NodeCategory.ENTITY,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "entity", dataType = FlowType.ENTITY),
                    @FlowPin(name = "range", dataType = FlowType.NUMBER)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void entitySetFollowRange(FlowContext ctx, FlowNode node) {
        Entity entity = ctx.getInputValue(node, "entity", Entity.class, null);
        Double range = ctx.getInputValue(node, "range", Double.class, 32.0);
        if (entity instanceof LivingEntity living && living.getAttribute(Attribute.GENERIC_FOLLOW_RANGE) != null) {
            living.getAttribute(Attribute.GENERIC_FOLLOW_RANGE).setBaseValue(range);
        }
        ctx.triggerOutput("flow");
    }

    @DefineNode(id = "entity_set_knockback_resistance", displayName = "Entity Set Knockback Resistance", category = NodeDefinition.NodeCategory.ENTITY,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "entity", dataType = FlowType.ENTITY),
                    @FlowPin(name = "resistance", dataType = FlowType.NUMBER)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void entitySetKnockbackResistance(FlowContext ctx, FlowNode node) {
        Entity entity = ctx.getInputValue(node, "entity", Entity.class, null);
        Double resistance = ctx.getInputValue(node, "resistance", Double.class, 0.0);
        if (entity instanceof LivingEntity living && living.getAttribute(Attribute.GENERIC_KNOCKBACK_RESISTANCE) != null) {
            living.getAttribute(Attribute.GENERIC_KNOCKBACK_RESISTANCE).setBaseValue(resistance);
        }
        ctx.triggerOutput("flow");
    }

    @DefineNode(id = "entity_set_wet", displayName = "Entity Set Wet", category = NodeDefinition.NodeCategory.ENTITY,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "entity", dataType = FlowType.ENTITY),
                    @FlowPin(name = "wet", dataType = FlowType.BOOLEAN)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void entitySetWet(FlowContext ctx, FlowNode node) {
        Entity entity = ctx.getInputValue(node, "entity", Entity.class, null);
        Boolean wet = ctx.getInputValue(node, "wet", Boolean.class, false);
        if (entity != null) {
            entity.setVisualFire(wet);
        }
        ctx.triggerOutput("flow");
    }

    @DefineNode(id = "entity_set_shaking", displayName = "Entity Set Shaking", category = NodeDefinition.NodeCategory.ENTITY,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "entity", dataType = FlowType.ENTITY),
                    @FlowPin(name = "shaking", dataType = FlowType.BOOLEAN)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void entitySetShaking(FlowContext ctx, FlowNode node) {
        Entity entity = ctx.getInputValue(node, "entity", Entity.class, null);
        Boolean shaking = ctx.getInputValue(node, "shaking", Boolean.class, false);
        if (entity != null) {
            entity.setVisualFire(shaking);
        }
        ctx.triggerOutput("flow");
    }

    @DefineNode(id = "entity_set_owner", displayName = "Entity Set Owner", category = NodeDefinition.NodeCategory.ENTITY,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "entity", dataType = FlowType.ENTITY),
                    @FlowPin(name = "owner", dataType = FlowType.ENTITY)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void entitySetOwner(FlowContext ctx, FlowNode node) {
        Entity entity = ctx.getInputValue(node, "entity", Entity.class, null);
        Entity owner = ctx.getInputValue(node, "owner", Entity.class, null);
        if (entity instanceof Tameable tameable && owner instanceof AnimalTamer tamer) {
            tameable.setOwner(tamer);
        }
        ctx.triggerOutput("flow");
    }

    @DefineNode(id = "entity_set_angry", displayName = "Entity Set Angry", category = NodeDefinition.NodeCategory.ENTITY,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "entity", dataType = FlowType.ENTITY),
                    @FlowPin(name = "angry", dataType = FlowType.BOOLEAN)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void entitySetAngry(FlowContext ctx, FlowNode node) {
        Entity entity = ctx.getInputValue(node, "entity", Entity.class, null);
        Boolean angry = ctx.getInputValue(node, "angry", Boolean.class, true);
        if (entity != null) {
            try {
                if (entity.getClass().getMethod("setAngry", int.class) != null) {
                    entity.getClass().getMethod("setAngry", int.class).invoke(entity, Boolean.TRUE.equals(angry) ? 1000 : 0);
                }
            } catch (Exception ignored) {
            }
        }
        ctx.triggerOutput("flow");
    }

    @DefineNode(id = "entity_set_love_mode", displayName = "Entity Set Love Mode", category = NodeDefinition.NodeCategory.ENTITY,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "entity", dataType = FlowType.ENTITY),
                    @FlowPin(name = "ticks", dataType = FlowType.NUMBER)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void entitySetLoveMode(FlowContext ctx, FlowNode node) {
        Entity entity = ctx.getInputValue(node, "entity", Entity.class, null);
        Integer ticks = ctx.getInputValue(node, "ticks", Integer.class, 600);
        if (entity instanceof Animals animals) {
            animals.setLoveModeTicks(ticks);
        }
        ctx.triggerOutput("flow");
    }

    @DefineNode(id = "entity_set_color", displayName = "Entity Set Color", category = NodeDefinition.NodeCategory.ENTITY,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "entity", dataType = FlowType.ENTITY),
                    @FlowPin(name = "color", dataType = FlowType.STRING)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void entitySetColor(FlowContext ctx, FlowNode node) {
        Entity entity = ctx.getInputValue(node, "entity", Entity.class, null);
        String colorName = ctx.getInputValue(node, "color", String.class, "WHITE");
        if (entity != null && colorName != null) {
            try {
                DyeColor color = DyeColor.valueOf(colorName.toUpperCase());
                try {
                    entity.getClass().getMethod("setColor", DyeColor.class).invoke(entity, color);
                } catch (Exception ignored) {
                }
            } catch (IllegalArgumentException e) {
                Log.warn("[Flow] Invalid dye color: " + colorName);
            }
        }
        ctx.triggerOutput("flow");
    }

    @DefineNode(id = "entity_set_variant", displayName = "Entity Set Variant", category = NodeDefinition.NodeCategory.ENTITY,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "entity", dataType = FlowType.ENTITY),
                    @FlowPin(name = "variant", dataType = FlowType.STRING)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void entitySetVariant(FlowContext ctx, FlowNode node) {
        Entity entity = ctx.getInputValue(node, "entity", Entity.class, null);
        String variant = ctx.getInputValue(node, "variant", String.class, "");
        if (entity != null && variant != null) {
            if (entity instanceof Frog frog) {
                try {
                    frog.setVariant(Frog.Variant.valueOf(variant.toUpperCase()));
                } catch (IllegalArgumentException ignored) {
                }
            } else if (entity instanceof Villager villager) {
                try {
                    villager.setVillagerType(Villager.Type.valueOf(variant.toUpperCase()));
                } catch (IllegalArgumentException ignored) {
                }
            } else if (entity instanceof Cat cat) {
                try {
                    cat.setCatType(Cat.Type.valueOf(variant.toUpperCase()));
                } catch (IllegalArgumentException ignored) {
                }
            } else if (entity instanceof Fox fox) {
                try {
                    fox.setFoxType(Fox.Type.valueOf(variant.toUpperCase()));
                } catch (IllegalArgumentException ignored) {
                }
            } else if (entity instanceof MushroomCow cow) {
                try {
                    cow.setVariant(MushroomCow.Variant.valueOf(variant.toUpperCase()));
                } catch (IllegalArgumentException ignored) {
                }
            } else if (entity instanceof Llama llama) {
                try {
                    llama.setColor(Llama.Color.valueOf(variant.toUpperCase()));
                } catch (IllegalArgumentException ignored) {
                }
            } else if (entity instanceof Rabbit rabbit) {
                try {
                    rabbit.setRabbitType(Rabbit.Type.valueOf(variant.toUpperCase()));
                } catch (IllegalArgumentException ignored) {
                }
            } else if (entity instanceof Axolotl axolotl) {
                try {
                    axolotl.setVariant(Axolotl.Variant.valueOf(variant.toUpperCase()));
                } catch (IllegalArgumentException ignored) {
                }
            } else if (entity instanceof Parrot parrot) {
                try {
                    parrot.setVariant(Parrot.Variant.valueOf(variant.toUpperCase()));
                } catch (IllegalArgumentException ignored) {
                }
            } else if (entity instanceof TropicalFish fish) {
                try {
                    fish.setPattern(TropicalFish.Pattern.valueOf(variant.toUpperCase()));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        ctx.triggerOutput("flow");
    }

    @DefineNode(id = "entity_set_held_item", displayName = "Entity Set Held Item", category = NodeDefinition.NodeCategory.ENTITY,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "entity", dataType = FlowType.ENTITY),
                    @FlowPin(name = "item", dataType = FlowType.ITEMSTACK)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void entitySetHeldItem(FlowContext ctx, FlowNode node) {
        Entity entity = ctx.getInputValue(node, "entity", Entity.class, null);
        ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
        if (entity instanceof Mob mob && item != null && mob.getEquipment() != null) {
            mob.getEquipment().setItemInMainHand(item);
        }
        ctx.triggerOutput("flow");
    }

    @DefineNode(id = "entity_set_armor", displayName = "Entity Set Armor", category = NodeDefinition.NodeCategory.ENTITY,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "entity", dataType = FlowType.ENTITY),
                    @FlowPin(name = "slot", dataType = FlowType.STRING),
                    @FlowPin(name = "item", dataType = FlowType.ITEMSTACK)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void entitySetArmorSlot(FlowContext ctx, FlowNode node) {
        Entity entity = ctx.getInputValue(node, "entity", Entity.class, null);
        String slot = ctx.getInputValue(node, "slot", String.class, "HEAD");
        ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
        if (entity instanceof LivingEntity living && item != null && living.getEquipment() != null) {
            switch (slot.toUpperCase()) {
                case "HEAD" -> living.getEquipment().setHelmet(item);
                case "CHEST" -> living.getEquipment().setChestplate(item);
                case "LEGS" -> living.getEquipment().setLeggings(item);
                case "FEET" -> living.getEquipment().setBoots(item);
                case "HAND" -> living.getEquipment().setItemInMainHand(item);
                case "OFFHAND" -> living.getEquipment().setItemInOffHand(item);
            }
        }
        ctx.triggerOutput("flow");
    }

    @DefineNode(id = "entity_set_drop_chances", displayName = "Entity Set Drop Chances", category = NodeDefinition.NodeCategory.ENTITY,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "entity", dataType = FlowType.ENTITY),
                    @FlowPin(name = "chance", dataType = FlowType.NUMBER)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void entitySetDropChances(FlowContext ctx, FlowNode node) {
        Entity entity = ctx.getInputValue(node, "entity", Entity.class, null);
        Double chance = ctx.getInputValue(node, "chance", Double.class, 0.085);
        if (entity instanceof LivingEntity living && living.getEquipment() != null) {
            float floatChance = chance.floatValue();
            living.getEquipment().setDropChance(EquipmentSlot.HAND, floatChance);
            living.getEquipment().setDropChance(EquipmentSlot.OFF_HAND, floatChance);
            living.getEquipment().setDropChance(EquipmentSlot.HEAD, floatChance);
            living.getEquipment().setDropChance(EquipmentSlot.CHEST, floatChance);
            living.getEquipment().setDropChance(EquipmentSlot.LEGS, floatChance);
            living.getEquipment().setDropChance(EquipmentSlot.FEET, floatChance);
        }
        ctx.triggerOutput("flow");
    }

    @DefineNode(id = "entity_add_drop", displayName = "Entity Add Drop", category = NodeDefinition.NodeCategory.ENTITY,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "entity", dataType = FlowType.ENTITY),
                    @FlowPin(name = "item", dataType = FlowType.ITEMSTACK)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void entityAddDrop(FlowContext ctx, FlowNode node) {
        Entity entity = ctx.getInputValue(node, "entity", Entity.class, null);
        ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
        if (entity != null && item != null) {
            entity.getWorld().dropItemNaturally(entity.getLocation(), item);
        }
        ctx.triggerOutput("flow");
    }

    @DefineNode(id = "entity_clear_drops", displayName = "Entity Clear Drops", category = NodeDefinition.NodeCategory.ENTITY,
            inputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW), @FlowPin(name = "entity", dataType = FlowType.ENTITY)},
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void entityClearDrops(FlowContext ctx, FlowNode node) {
        Entity entity = ctx.getInputValue(node, "entity", Entity.class, null);
        if (entity instanceof LivingEntity living && living.getEquipment() != null) {
            living.getEquipment().clear();
        }
        ctx.triggerOutput("flow");
    }

    @DefineNode(id = "entity_pickup_item", displayName = "Entity Pickup Item", category = NodeDefinition.NodeCategory.ENTITY,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "entity", dataType = FlowType.ENTITY),
                    @FlowPin(name = "can_pickup", dataType = FlowType.BOOLEAN)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void entityPickupItem(FlowContext ctx, FlowNode node) {
        Entity entity = ctx.getInputValue(node, "entity", Entity.class, null);
        Boolean canPickup = ctx.getInputValue(node, "can_pickup", Boolean.class, true);
        if (entity instanceof LivingEntity living) {
            living.setCanPickupItems(canPickup);
        }
        ctx.triggerOutput("flow");
    }

    @DefineNode(id = "entity_state", displayName = "Entity State", category = NodeDefinition.NodeCategory.ENTITY,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "entity", dataType = FlowType.ENTITY),
                    @FlowPin(name = "property", dataType = FlowType.STRING, widget = NodeDefinition.WidgetType.DROPDOWN,
                            options = {"name", "name_visible", "glowing", "silent", "invulnerable", "burning", "frozen", "persistent", "health", "max_health", "speed", "target", "baby", "tamed", "sitting", "swimming", "pickup_items", "kill", "remove", "exists"},
                            defaultValue = "name"),
                    @FlowPin(name = "action", dataType = FlowType.STRING, widget = NodeDefinition.WidgetType.DROPDOWN,
                            options = {"get", "set", "do"},
                            defaultValue = "get"),
                    @FlowPin(name = "string_value", dataType = FlowType.STRING,
                            visibleWhen = {
                                    @VisibleWhen(pin = "property", value = "name"),
                                    @VisibleWhen(pin = "action", value = "set")
                            }),
                    @FlowPin(name = "boolean_value", dataType = FlowType.BOOLEAN,
                            visibleWhen = {
                                    @VisibleWhen(pin = "property", value = "name_visible,glowing,silent,invulnerable,persistent,baby,tamed,sitting,swimming,pickup_items"),
                                    @VisibleWhen(pin = "action", value = "set")
                            }),
                    @FlowPin(name = "number_value", dataType = FlowType.NUMBER,
                            visibleWhen = {
                                    @VisibleWhen(pin = "property", value = "burning,frozen,health,max_health,speed"),
                                    @VisibleWhen(pin = "action", value = "set")
                            }),
                    @FlowPin(name = "entity_value", dataType = FlowType.ENTITY,
                            visibleWhen = {
                                    @VisibleWhen(pin = "property", value = "target"),
                                    @VisibleWhen(pin = "action", value = "set")
                            })
            },
            outputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "success", dataType = FlowType.BOOLEAN),
                    @FlowPin(name = "result", dataType = FlowType.ANY,
                            visibleWhen = {@VisibleWhen(pin = "action", value = "get")})
            })
    public void entityState(FlowContext ctx, FlowNode node) {
        Entity entity = ctx.getInputValue(node, "entity", Entity.class, null);
        String property = ctx.getInputValue(node, "property", String.class, "");
        String action = ctx.getInputValue(node, "action", String.class, "get");
        String stringValue = ctx.getInputValue(node, "string_value", String.class, "");
        Boolean booleanValue = ctx.getInputValue(node, "boolean_value", Boolean.class, false);
        Double numberValue = ctx.getInputValue(node, "number_value", Double.class, 0.0);
        Entity entityValue = ctx.getInputValue(node, "entity_value", Entity.class, null);
        boolean success = false;
        Object result = null;
        String nodeId = ctx.getRuntime().findNodeId(node);

        if (entity != null && property != null && action != null) {
            switch (property.toLowerCase()) {
                case "name" -> {
                    if ("set".equalsIgnoreCase(action)) {
                        entity.setCustomName(stringValue);
                        success = true;
                    } else if ("get".equalsIgnoreCase(action)) {
                        result = entity.getCustomName();
                        success = true;
                    }
                }
                case "name_visible" -> {
                    if ("set".equalsIgnoreCase(action)) {
                        entity.setCustomNameVisible(Boolean.TRUE.equals(booleanValue));
                        success = true;
                    } else if ("get".equalsIgnoreCase(action)) {
                        result = entity.isCustomNameVisible();
                        success = true;
                    }
                }
                case "glowing" -> {
                    if ("set".equalsIgnoreCase(action)) {
                        entity.setGlowing(Boolean.TRUE.equals(booleanValue));
                        success = true;
                    } else if ("get".equalsIgnoreCase(action)) {
                        result = entity.isGlowing();
                        success = true;
                    }
                }
                case "silent" -> {
                    if ("set".equalsIgnoreCase(action)) {
                        entity.setSilent(Boolean.TRUE.equals(booleanValue));
                        success = true;
                    } else if ("get".equalsIgnoreCase(action)) {
                        result = entity.isSilent();
                        success = true;
                    }
                }
                case "invulnerable" -> {
                    if ("set".equalsIgnoreCase(action)) {
                        entity.setInvulnerable(Boolean.TRUE.equals(booleanValue));
                        success = true;
                    } else if ("get".equalsIgnoreCase(action)) {
                        result = entity.isInvulnerable();
                        success = true;
                    }
                }
                case "burning" -> {
                    if ("set".equalsIgnoreCase(action)) {
                        int ticks = numberValue.intValue();
                        entity.setFireTicks(ticks);
                        success = true;
                    } else if ("get".equalsIgnoreCase(action)) {
                        result = entity.getFireTicks();
                        success = true;
                    }
                }
                case "frozen" -> {
                    if ("set".equalsIgnoreCase(action)) {
                        int ticks = numberValue.intValue();
                        entity.setFreezeTicks(ticks);
                        success = true;
                    } else if ("get".equalsIgnoreCase(action)) {
                        result = entity.getFreezeTicks();
                        success = true;
                    }
                }
                case "persistent" -> {
                    if (entity instanceof Mob mob) {
                        if ("set".equalsIgnoreCase(action)) {
                            mob.setPersistent(Boolean.TRUE.equals(booleanValue));
                            success = true;
                        } else if ("get".equalsIgnoreCase(action)) {
                            result = mob.isPersistent();
                            success = true;
                        }
                    }
                }
                case "health" -> {
                    if (entity instanceof LivingEntity living) {
                        if ("set".equalsIgnoreCase(action)) {
                            double health = numberValue;
                            living.setHealth(Math.min(health, living.getMaxHealth()));
                            success = true;
                        } else if ("get".equalsIgnoreCase(action)) {
                            result = living.getHealth();
                            success = true;
                        }
                    }
                }
                case "max_health" -> {
                    if (entity instanceof LivingEntity living && living.getAttribute(Attribute.GENERIC_MAX_HEALTH) != null) {
                        if ("set".equalsIgnoreCase(action)) {
                            double maxHealth = numberValue;
                            living.getAttribute(Attribute.GENERIC_MAX_HEALTH).setBaseValue(maxHealth);
                            success = true;
                        } else if ("get".equalsIgnoreCase(action)) {
                            result = living.getMaxHealth();
                            success = true;
                        }
                    }
                }
                case "speed" -> {
                    if (entity instanceof LivingEntity living && living.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED) != null) {
                        if ("set".equalsIgnoreCase(action)) {
                            double speed = numberValue;
                            living.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED).setBaseValue(speed);
                            success = true;
                        } else if ("get".equalsIgnoreCase(action)) {
                            result = living.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED).getBaseValue();
                            success = true;
                        }
                    }
                }
                case "target" -> {
                    if (entity instanceof Mob mob) {
                        if ("set".equalsIgnoreCase(action)) {
                            if (entityValue instanceof LivingEntity target) {
                                mob.setTarget(target);
                                success = true;
                            } else if (entityValue == null) {
                                mob.setTarget(null);
                                success = true;
                            }
                        } else if ("get".equalsIgnoreCase(action)) {
                            result = mob.getTarget();
                            success = true;
                        }
                    }
                }
                case "baby" -> {
                    if (entity instanceof Ageable ageable) {
                        if ("set".equalsIgnoreCase(action)) {
                            boolean isBaby = Boolean.TRUE.equals(booleanValue);
                            if (isBaby) {
                                ageable.setBaby();
                            } else {
                                ageable.setAdult();
                            }
                            success = true;
                        } else if ("get".equalsIgnoreCase(action)) {
                            result = !ageable.isAdult();
                            success = true;
                        }
                    }
                }
                case "tamed" -> {
                    if (entity instanceof Tameable tameable) {
                        if ("set".equalsIgnoreCase(action)) {
                            tameable.setTamed(Boolean.TRUE.equals(booleanValue));
                            success = true;
                        } else if ("get".equalsIgnoreCase(action)) {
                            result = tameable.isTamed();
                            success = true;
                        }
                    }
                }
                case "sitting" -> {
                    if (entity instanceof Sittable sittable) {
                        if ("set".equalsIgnoreCase(action)) {
                            sittable.setSitting(Boolean.TRUE.equals(booleanValue));
                            success = true;
                        } else if ("get".equalsIgnoreCase(action)) {
                            result = sittable.isSitting();
                            success = true;
                        }
                    }
                }
                case "swimming" -> {
                    if (entity instanceof LivingEntity living) {
                        if ("set".equalsIgnoreCase(action)) {
                            living.setSwimming(Boolean.TRUE.equals(booleanValue));
                            success = true;
                        } else if ("get".equalsIgnoreCase(action)) {
                            result = living.isSwimming();
                            success = true;
                        }
                    }
                }
                case "pickup_items" -> {
                    if (entity instanceof LivingEntity living) {
                        if ("set".equalsIgnoreCase(action)) {
                            living.setCanPickupItems(Boolean.TRUE.equals(booleanValue));
                            success = true;
                        } else if ("get".equalsIgnoreCase(action)) {
                            result = living.getCanPickupItems();
                            success = true;
                        }
                    }
                }
                case "kill" -> {
                    if ("do".equalsIgnoreCase(action) && entity instanceof LivingEntity living) {
                        living.setHealth(0);
                        success = true;
                    }
                }
                case "remove" -> {
                    if ("do".equalsIgnoreCase(action)) {
                        entity.remove();
                        success = true;
                    }
                }
                case "exists" -> {
                    if ("get".equalsIgnoreCase(action)) {
                        result = entity.isValid();
                        success = true;
                    }
                }
            }
        }

        ctx.setNodeOutput(nodeId, "success", success);
        ctx.setNodeOutput(nodeId, "result", result);
        ctx.triggerOutput("flow");
    }
}
