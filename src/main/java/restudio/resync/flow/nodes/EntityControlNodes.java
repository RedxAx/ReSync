package restudio.resync.flow.nodes;

import org.bukkit.Bukkit;
import org.bukkit.DyeColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.*;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import restudio.flow.data.FlowNode;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.FlowRegistry;
import restudio.resync.flow.NodeCategory;

public class EntityControlNodes implements NodeCategory {
    
    @Override
    public void registerNodes(FlowRegistry registry) {
        registry.register("entity_set_type", (ctx, node) -> {
            Object entityObj = ctx.getInputValue(node, "entity", Entity.class, null);
            Object entityTypeObj = ctx.getInputValue(node, "entity_type", String.class, "PIG");
            
            if (entityObj != null && entityTypeObj != null) {
                try {
                    Entity entity = (Entity)entityObj;
                    Location loc = entity.getLocation();
                    entity.remove();
                    EntityType newType = EntityType.valueOf(((String)entityTypeObj).toUpperCase());
                    if (loc.getWorld() != null) {
                        loc.getWorld().spawnEntity(loc, newType);
                    }
                } catch (IllegalArgumentException e) {
                    Bukkit.getLogger().warning("[Flow] Invalid entity type: " + entityTypeObj);
                }
            }
            
            ctx.triggerOutput("flow");
        });
        
        registry.register("entity_set_name", (ctx, node) -> {
            Object entityObj = ctx.getInputValue(node, "entity", Entity.class, null);
            Object nameObj = ctx.getInputValue(node, "name", String.class, "");
            
            if (entityObj != null) {
                ((Entity)entityObj).setCustomName((String)nameObj);
            }
            
            ctx.triggerOutput("flow");
        });
        
        registry.register("entity_set_custom_name_visible", (ctx, node) -> {
            Object entityObj = ctx.getInputValue(node, "entity", Entity.class, null);
            Boolean visible = ctx.getInputValue(node, "visible", Boolean.class, true);
            
            if (entityObj != null) {
                ((Entity)entityObj).setCustomNameVisible(visible);
            }
            
            ctx.triggerOutput("flow");
        });
        
        registry.register("entity_set_health", (ctx, node) -> {
            Object entityObj = ctx.getInputValue(node, "entity", Entity.class, null);
            Double health = ctx.getInputValue(node, "health", Double.class, 20.0);
            
            if (entityObj != null && entityObj instanceof LivingEntity) {
                ((LivingEntity)entityObj).setHealth(health);
            }
            
            ctx.triggerOutput("flow");
        });
        
        registry.register("entity_set_max_health", (ctx, node) -> {
            Object entityObj = ctx.getInputValue(node, "entity", Entity.class, null);
            Double maxHealth = ctx.getInputValue(node, "max_health", Double.class, 20.0);
            
            if (entityObj != null && entityObj instanceof LivingEntity) {
                LivingEntity living = (LivingEntity)entityObj;
                living.getAttribute(Attribute.GENERIC_MAX_HEALTH).setBaseValue(maxHealth);
            }
            
            ctx.triggerOutput("flow");
        });
        
        registry.register("entity_set_speed", (ctx, node) -> {
            Object entityObj = ctx.getInputValue(node, "entity", Entity.class, null);
            Double speed = ctx.getInputValue(node, "speed", Double.class, 0.2);
            
            if (entityObj != null && entityObj instanceof LivingEntity) {
                LivingEntity living = (LivingEntity)entityObj;
                if (living.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED) != null) {
                    living.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED).setBaseValue(speed);
                }
            }
            
            ctx.triggerOutput("flow");
        });
        
        registry.register("entity_set_damage", (ctx, node) -> {
            Object entityObj = ctx.getInputValue(node, "entity", Entity.class, null);
            Double damage = ctx.getInputValue(node, "damage", Double.class, 1.0);
            
            if (entityObj != null && entityObj instanceof LivingEntity) {
                LivingEntity living = (LivingEntity)entityObj;
                if (living.getAttribute(Attribute.GENERIC_ATTACK_DAMAGE) != null) {
                    living.getAttribute(Attribute.GENERIC_ATTACK_DAMAGE).setBaseValue(damage);
                }
            }
            
            ctx.triggerOutput("flow");
        });
        
        registry.register("entity_set_armor", (ctx, node) -> {
            Object entityObj = ctx.getInputValue(node, "entity", Entity.class, null);
            Double armor = ctx.getInputValue(node, "armor", Double.class, 0.0);
            
            if (entityObj != null && entityObj instanceof LivingEntity) {
                LivingEntity living = (LivingEntity)entityObj;
                if (living.getAttribute(Attribute.GENERIC_ARMOR) != null) {
                    living.getAttribute(Attribute.GENERIC_ARMOR).setBaseValue(armor);
                }
            }
            
            ctx.triggerOutput("flow");
        });
        
        registry.register("entity_set_follow_range", (ctx, node) -> {
            Object entityObj = ctx.getInputValue(node, "entity", Entity.class, null);
            Double range = ctx.getInputValue(node, "range", Double.class, 32.0);
            
            if (entityObj != null && entityObj instanceof LivingEntity) {
                LivingEntity living = (LivingEntity)entityObj;
                if (living.getAttribute(Attribute.GENERIC_FOLLOW_RANGE) != null) {
                    living.getAttribute(Attribute.GENERIC_FOLLOW_RANGE).setBaseValue(range);
                }
            }
            
            ctx.triggerOutput("flow");
        });
        
        registry.register("entity_set_knockback_resistance", (ctx, node) -> {
            Object entityObj = ctx.getInputValue(node, "entity", Entity.class, null);
            Double resistance = ctx.getInputValue(node, "resistance", Double.class, 0.0);
            
            if (entityObj != null && entityObj instanceof LivingEntity) {
                LivingEntity living = (LivingEntity)entityObj;
                if (living.getAttribute(Attribute.GENERIC_KNOCKBACK_RESISTANCE) != null) {
                    living.getAttribute(Attribute.GENERIC_KNOCKBACK_RESISTANCE).setBaseValue(resistance);
                }
            }
            
            ctx.triggerOutput("flow");
        });
        
        registry.register("entity_set_target", (ctx, node) -> {
            Object entityObj = ctx.getInputValue(node, "entity", Entity.class, null);
            Object targetObj = ctx.getInputValue(node, "target", Entity.class, null);
            
            if (entityObj != null && entityObj instanceof Mob && targetObj != null && targetObj instanceof LivingEntity) {
                ((Mob)entityObj).setTarget((LivingEntity)targetObj);
            }
            
            ctx.triggerOutput("flow");
        });
        
        registry.register("entity_clear_target", (ctx, node) -> {
            Object entityObj = ctx.getInputValue(node, "entity", Entity.class, null);
            
            if (entityObj != null && entityObj instanceof Mob) {
                ((Mob)entityObj).setTarget(null);
            }
            
            ctx.triggerOutput("flow");
        });
        
        registry.register("entity_set_persistent", (ctx, node) -> {
            Object entityObj = ctx.getInputValue(node, "entity", Entity.class, null);
            Boolean persistent = ctx.getInputValue(node, "persistent", Boolean.class, true);
            
            if (entityObj != null && entityObj instanceof Mob) {
                ((Mob)entityObj).setPersistent(persistent);
            }
            
            ctx.triggerOutput("flow");
        });
        
        registry.register("entity_set_invulnerable", (ctx, node) -> {
            Object entityObj = ctx.getInputValue(node, "entity", Entity.class, null);
            Boolean invulnerable = ctx.getInputValue(node, "invulnerable", Boolean.class, false);
            
            if (entityObj != null) {
                ((Entity)entityObj).setInvulnerable(invulnerable);
            }
            
            ctx.triggerOutput("flow");
        });
        
        registry.register("entity_set_silent", (ctx, node) -> {
            Object entityObj = ctx.getInputValue(node, "entity", Entity.class, null);
            Boolean silent = ctx.getInputValue(node, "silent", Boolean.class, false);
            
            if (entityObj != null) {
                ((Entity)entityObj).setSilent(silent);
            }
            
            ctx.triggerOutput("flow");
        });
        
        registry.register("entity_set_glowing", (ctx, node) -> {
            Object entityObj = ctx.getInputValue(node, "entity", Entity.class, null);
            Boolean glowing = ctx.getInputValue(node, "glowing", Boolean.class, false);
            
            if (entityObj != null) {
                ((Entity)entityObj).setGlowing(glowing);
            }
            
            ctx.triggerOutput("flow");
        });
        
        registry.register("entity_set_burning", (ctx, node) -> {
            Object entityObj = ctx.getInputValue(node, "entity", Entity.class, null);
            Integer ticks = ctx.getInputValue(node, "ticks", Integer.class, 0);
            
            if (entityObj != null) {
                ((Entity)entityObj).setFireTicks(ticks);
            }
            
            ctx.triggerOutput("flow");
        });
        
        registry.register("entity_set_frozen", (ctx, node) -> {
            Object entityObj = ctx.getInputValue(node, "entity", Entity.class, null);
            Integer ticks = ctx.getInputValue(node, "ticks", Integer.class, 0);
            
            if (entityObj != null) {
                ((Entity)entityObj).setFreezeTicks(ticks);
            }
            
            ctx.triggerOutput("flow");
        });
        
        registry.register("entity_set_wet", (ctx, node) -> {
            Object entityObj = ctx.getInputValue(node, "entity", Entity.class, null);
            Boolean wet = ctx.getInputValue(node, "wet", Boolean.class, false);
            
            if (entityObj != null) {
                ((Entity)entityObj).setVisualFire(wet);
            }
            
            ctx.triggerOutput("flow");
        });
        
        registry.register("entity_set_swimming", (ctx, node) -> {
            Object entityObj = ctx.getInputValue(node, "entity", Entity.class, null);
            Boolean swimming = ctx.getInputValue(node, "swimming", Boolean.class, false);
            
            if (entityObj != null && entityObj instanceof LivingEntity) {
                ((LivingEntity)entityObj).setSwimming(swimming);
            }
            
            ctx.triggerOutput("flow");
        });
        
        registry.register("entity_set_shaking", (ctx, node) -> {
            Object entityObj = ctx.getInputValue(node, "entity", Entity.class, null);
            Boolean shaking = ctx.getInputValue(node, "shaking", Boolean.class, false);
            
            if (entityObj != null) {
                ((Entity)entityObj).setVisualFire(shaking);
            }
            
            ctx.triggerOutput("flow");
        });
        
        registry.register("entity_set_baby", (ctx, node) -> {
            Object entityObj = ctx.getInputValue(node, "entity", Entity.class, null);
            Boolean isBaby = ctx.getInputValue(node, "is_baby", Boolean.class, false);
            
            if (entityObj != null && entityObj instanceof Ageable) {
                Ageable ageable = (Ageable)entityObj;
                ageable.setAdult();
                if (isBaby) {
                    ageable.setBaby();
                }
            }
            
            ctx.triggerOutput("flow");
        });
        
        registry.register("entity_set_tamed", (ctx, node) -> {
            Object entityObj = ctx.getInputValue(node, "entity", Entity.class, null);
            Boolean tamed = ctx.getInputValue(node, "tamed", Boolean.class, true);
            
            if (entityObj != null && entityObj instanceof Tameable) {
                ((Tameable)entityObj).setTamed(tamed);
            }
            
            ctx.triggerOutput("flow");
        });
        
        registry.register("entity_set_owner", (ctx, node) -> {
            Object entityObj = ctx.getInputValue(node, "entity", Entity.class, null);
            Object ownerObj = ctx.getInputValue(node, "owner", Entity.class, null);
            
            if (entityObj != null && entityObj instanceof Tameable && ownerObj != null && ownerObj instanceof AnimalTamer) {
                ((Tameable)entityObj).setOwner((AnimalTamer)ownerObj);
            }
            
            ctx.triggerOutput("flow");
        });
        
        registry.register("entity_set_sitting", (ctx, node) -> {
            Object entityObj = ctx.getInputValue(node, "entity", Entity.class, null);
            Boolean sitting = ctx.getInputValue(node, "sitting", Boolean.class, false);
            
            if (entityObj != null && entityObj instanceof Sittable) {
                ((Sittable)entityObj).setSitting(sitting);
            }
            
            ctx.triggerOutput("flow");
        });
        
        registry.register("entity_set_angry", (ctx, node) -> {
            Object entityObj = ctx.getInputValue(node, "entity", Entity.class, null);
            Boolean angry = ctx.getInputValue(node, "angry", Boolean.class, true);
            
            if (entityObj != null) {
                Entity entity = (Entity)entityObj;
                try {
                    if (entity.getClass().getMethod("setAngry", int.class) != null) {
                        entity.getClass().getMethod("setAngry", int.class).invoke(entity, angry ? 1000 : 0);
                    }
                } catch (Exception e) {
                }
            }
            
            ctx.triggerOutput("flow");
        });
        
        registry.register("entity_set_love_mode", (ctx, node) -> {
            Object entityObj = ctx.getInputValue(node, "entity", Entity.class, null);
            Integer ticks = ctx.getInputValue(node, "ticks", Integer.class, 600);
            
            if (entityObj != null && entityObj instanceof Animals) {
                ((Animals)entityObj).setLoveModeTicks(ticks);
            }
            
            ctx.triggerOutput("flow");
        });
        
        registry.register("entity_set_color", (ctx, node) -> {
            Object entityObj = ctx.getInputValue(node, "entity", Entity.class, null);
            Object colorObj = ctx.getInputValue(node, "color", String.class, "WHITE");
            
            if (entityObj != null && colorObj != null) {
                try {
                    DyeColor color = DyeColor.valueOf(((String)colorObj).toUpperCase());
                    Entity entity = (Entity)entityObj;
                    try {
                        entity.getClass().getMethod("setColor", DyeColor.class).invoke(entity, color);
                    } catch (Exception e) {
                    }
                } catch (IllegalArgumentException e) {
                    Bukkit.getLogger().warning("[Flow] Invalid dye color: " + colorObj);
                }
            }
            
            ctx.triggerOutput("flow");
        });
        
        registry.register("entity_set_variant", (ctx, node) -> {
            Object entityObj = ctx.getInputValue(node, "entity", Entity.class, null);
            Object variantObj = ctx.getInputValue(node, "variant", String.class, "");
            
            if (entityObj != null && variantObj != null) {
                Entity entity = (Entity)entityObj;
                String variant = (String)variantObj;
                
                if (entity instanceof Frog) {
                    try {
                        ((Frog)entity).setVariant(Frog.Variant.valueOf(variant.toUpperCase()));
                    } catch (IllegalArgumentException e) { }
                } else if (entity instanceof Villager) {
                    try {
                        ((Villager)entity).setVillagerType(Villager.Type.valueOf(variant.toUpperCase()));
                    } catch (IllegalArgumentException e) { }
                } else if (entity instanceof Cat) {
                    try {
                        ((Cat)entity).setCatType(Cat.Type.valueOf(variant.toUpperCase()));
                    } catch (IllegalArgumentException e) { }
                } else if (entity instanceof Fox) {
                    try {
                        ((Fox)entity).setFoxType(Fox.Type.valueOf(variant.toUpperCase()));
                    } catch (IllegalArgumentException e) { }
                } else if (entity instanceof MushroomCow) {
                    try {
                        ((MushroomCow)entity).setVariant(MushroomCow.Variant.valueOf(variant.toUpperCase()));
                    } catch (IllegalArgumentException e) { }
                } else if (entity instanceof Llama) {
                    try {
                        ((Llama)entity).setColor(Llama.Color.valueOf(variant.toUpperCase()));
                    } catch (IllegalArgumentException e) { }
                } else if (entity instanceof Rabbit) {
                    try {
                        ((Rabbit)entity).setRabbitType(Rabbit.Type.valueOf(variant.toUpperCase()));
                    } catch (IllegalArgumentException e) { }
                } else if (entity instanceof Axolotl) {
                    try {
                        ((Axolotl)entity).setVariant(Axolotl.Variant.valueOf(variant.toUpperCase()));
                    } catch (IllegalArgumentException e) { }
                } else if (entity instanceof Parrot) {
                    try {
                        ((Parrot)entity).setVariant(Parrot.Variant.valueOf(variant.toUpperCase()));
                    } catch (IllegalArgumentException e) { }
                } else if (entity instanceof TropicalFish) {
                    try {
                        ((TropicalFish)entity).setPattern(TropicalFish.Pattern.valueOf(variant.toUpperCase()));
                    } catch (IllegalArgumentException e) { }
                }
            }
            
            ctx.triggerOutput("flow");
        });
        
        registry.register("entity_set_held_item", (ctx, node) -> {
            Object entityObj = ctx.getInputValue(node, "entity", Entity.class, null);
            Object itemObj = ctx.getInputValue(node, "item", ItemStack.class, null);
            
            if (entityObj != null && itemObj != null && entityObj instanceof Mob) {
                ((Mob)entityObj).getEquipment().setItemInMainHand((ItemStack)itemObj);
            }
            
            ctx.triggerOutput("flow");
        });
        
        registry.register("entity_set_armor", (ctx, node) -> {
            Object entityObj = ctx.getInputValue(node, "entity", Entity.class, null);
            Object slotObj = ctx.getInputValue(node, "slot", String.class, "HEAD");
            Object itemObj = ctx.getInputValue(node, "item", ItemStack.class, null);
            
            if (entityObj != null && itemObj != null && entityObj instanceof LivingEntity) {
                LivingEntity living = (LivingEntity)entityObj;
                ItemStack item = (ItemStack)itemObj;
                String slot = ((String)slotObj).toUpperCase();
                
                switch (slot) {
                    case "HEAD":
                        living.getEquipment().setHelmet(item);
                        break;
                    case "CHEST":
                        living.getEquipment().setChestplate(item);
                        break;
                    case "LEGS":
                        living.getEquipment().setLeggings(item);
                        break;
                    case "FEET":
                        living.getEquipment().setBoots(item);
                        break;
                    case "HAND":
                        living.getEquipment().setItemInMainHand(item);
                        break;
                    case "OFFHAND":
                        living.getEquipment().setItemInOffHand(item);
                        break;
                }
            }
            
            ctx.triggerOutput("flow");
        });
        
        registry.register("entity_set_drop_chances", (ctx, node) -> {
            Object entityObj = ctx.getInputValue(node, "entity", Entity.class, null);
            Double chance = ctx.getInputValue(node, "chance", Double.class, 0.085);
            
            if (entityObj != null && entityObj instanceof LivingEntity) {
                LivingEntity living = (LivingEntity)entityObj;
                float floatChance = chance.floatValue();
                living.getEquipment().setDropChance(EquipmentSlot.HAND, floatChance);
                living.getEquipment().setDropChance(EquipmentSlot.OFF_HAND, floatChance);
                living.getEquipment().setDropChance(EquipmentSlot.HEAD, floatChance);
                living.getEquipment().setDropChance(EquipmentSlot.CHEST, floatChance);
                living.getEquipment().setDropChance(EquipmentSlot.LEGS, floatChance);
                living.getEquipment().setDropChance(EquipmentSlot.FEET, floatChance);
            }
            
            ctx.triggerOutput("flow");
        });
        
        registry.register("entity_add_drop", (ctx, node) -> {
            Object entityObj = ctx.getInputValue(node, "entity", Entity.class, null);
            Object itemObj = ctx.getInputValue(node, "item", ItemStack.class, null);
            
            if (entityObj != null && itemObj != null) {
                ((Entity)entityObj).getWorld().dropItemNaturally(((Entity)entityObj).getLocation(), (ItemStack)itemObj);
            }
            
            ctx.triggerOutput("flow");
        });
        
        registry.register("entity_clear_drops", (ctx, node) -> {
            Object entityObj = ctx.getInputValue(node, "entity", Entity.class, null);
            
            if (entityObj != null && entityObj instanceof LivingEntity) {
                LivingEntity living = (LivingEntity)entityObj;
                living.getEquipment().clear();
            }
            
            ctx.triggerOutput("flow");
        });
        
        registry.register("entity_pickup_item", (ctx, node) -> {
            Object entityObj = ctx.getInputValue(node, "entity", Entity.class, null);
            Boolean canPickup = ctx.getInputValue(node, "can_pickup", Boolean.class, true);
            
            if (entityObj != null && entityObj instanceof LivingEntity) {
                ((LivingEntity)entityObj).setCanPickupItems(canPickup);
            }
            
            ctx.triggerOutput("flow");
        });
        
        registry.register("entity_kill", (ctx, node) -> {
            Object entityObj = ctx.getInputValue(node, "entity", Entity.class, null);
            Object reasonObj = ctx.getInputValue(node, "reason", String.class, "CUSTOM");
            
            if (entityObj != null && entityObj instanceof LivingEntity) {
                ((LivingEntity)entityObj).setHealth(0);
            }
            
            ctx.triggerOutput("flow");
        });
    }
}
