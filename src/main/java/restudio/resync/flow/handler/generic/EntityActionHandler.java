package restudio.resync.flow.handler.generic;

import org.bukkit.Bukkit;
import org.bukkit.DyeColor;
import org.bukkit.Location;
import org.bukkit.World;
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
import org.bukkit.entity.Player;
import org.bukkit.entity.Rabbit;
import org.bukkit.entity.Sittable;
import org.bukkit.entity.Tameable;
import org.bukkit.entity.TropicalFish;
import org.bukkit.entity.Villager;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;
import restudio.flow.data.FlowNode;
import restudio.resync.Log;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.FlowMutations;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.NodeHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

public class EntityActionHandler implements NodeHandler {
    private final Map<String, BiConsumer<FlowContext, FlowNode>> operations = new ConcurrentHashMap<>();

    public EntityActionHandler() {
        operations.put("entity_set_type", (ctx, node) -> {
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
        });

        operations.put("entity_set_rotation", (ctx, node) -> {
            Entity entity = ctx.getInputValue(node, "entity", Entity.class, null);
            if (entity != null) {
                Location location = entity.getLocation();
                Float yaw = ctx.getInputValue(node, "yaw", Float.class, location.getYaw());
                Float pitch = ctx.getInputValue(node, "pitch", Float.class, location.getPitch());
                location.setYaw(yaw);
                location.setPitch(pitch);
                entity.teleport(location);
            }
        });

        operations.put("entity_set_damage", (ctx, node) -> {
            Entity entity = ctx.getInputValue(node, "entity", Entity.class, null);
            Double damage = ctx.getInputValue(node, "damage", Double.class, 1.0);
            if (entity instanceof LivingEntity living && living.getAttribute(Attribute.GENERIC_ATTACK_DAMAGE) != null) {
                living.getAttribute(Attribute.GENERIC_ATTACK_DAMAGE).setBaseValue(damage);
            }
        });

        operations.put("entity_set_armor_value", (ctx, node) -> {
            Entity entity = ctx.getInputValue(node, "entity", Entity.class, null);
            Double armor = ctx.getInputValue(node, "armor", Double.class, 0.0);
            if (entity instanceof LivingEntity living && living.getAttribute(Attribute.GENERIC_ARMOR) != null) {
                living.getAttribute(Attribute.GENERIC_ARMOR).setBaseValue(armor);
            }
        });

        operations.put("entity_set_follow_range", (ctx, node) -> {
            Entity entity = ctx.getInputValue(node, "entity", Entity.class, null);
            Double range = ctx.getInputValue(node, "range", Double.class, 32.0);
            if (entity instanceof LivingEntity living && living.getAttribute(Attribute.GENERIC_FOLLOW_RANGE) != null) {
                living.getAttribute(Attribute.GENERIC_FOLLOW_RANGE).setBaseValue(range);
            }
        });

        operations.put("entity_set_knockback_resistance", (ctx, node) -> {
            Entity entity = ctx.getInputValue(node, "entity", Entity.class, null);
            Double resistance = ctx.getInputValue(node, "resistance", Double.class, 0.0);
            if (entity instanceof LivingEntity living && living.getAttribute(Attribute.GENERIC_KNOCKBACK_RESISTANCE) != null) {
                living.getAttribute(Attribute.GENERIC_KNOCKBACK_RESISTANCE).setBaseValue(resistance);
            }
        });

        operations.put("entity_set_wet", (ctx, node) -> {
            Entity entity = ctx.getInputValue(node, "entity", Entity.class, null);
            Boolean wet = ctx.getInputValue(node, "wet", Boolean.class, false);
            if (entity != null) {
                entity.setVisualFire(wet);
            }
        });

        operations.put("entity_set_shaking", (ctx, node) -> {
            Entity entity = ctx.getInputValue(node, "entity", Entity.class, null);
            Boolean shaking = ctx.getInputValue(node, "shaking", Boolean.class, false);
            if (entity != null) {
                entity.setVisualFire(shaking);
            }
        });

        operations.put("entity_set_owner", (ctx, node) -> {
            Entity entity = ctx.getInputValue(node, "entity", Entity.class, null);
            Entity owner = ctx.getInputValue(node, "owner", Entity.class, null);
            if (entity instanceof Tameable tameable && owner instanceof AnimalTamer tamer) {
                tameable.setOwner(tamer);
            }
        });

        operations.put("entity_set_angry", (ctx, node) -> {
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
        });

        operations.put("entity_set_love_mode", (ctx, node) -> {
            Entity entity = ctx.getInputValue(node, "entity", Entity.class, null);
            Integer ticks = ctx.getInputValue(node, "ticks", Integer.class, 600);
            if (entity instanceof Animals animals) {
                animals.setLoveModeTicks(ticks);
            }
        });

        operations.put("entity_set_color", (ctx, node) -> {
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
        });

        operations.put("entity_set_variant", (ctx, node) -> {
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
        });

        operations.put("entity_set_held_item", (ctx, node) -> {
            Entity entity = ctx.getInputValue(node, "entity", Entity.class, null);
            ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
            if (entity instanceof Mob mob && item != null && mob.getEquipment() != null) {
                mob.getEquipment().setItemInMainHand(item);
            }
        });

        operations.put("entity_set_armor", (ctx, node) -> {
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
        });

        operations.put("entity_set_drop_chances", (ctx, node) -> {
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
        });

        operations.put("entity_add_drop", (ctx, node) -> {
            Entity entity = ctx.getInputValue(node, "entity", Entity.class, null);
            ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
            if (entity != null && item != null) {
                entity.getWorld().dropItemNaturally(entity.getLocation(), item);
            }
        });

        operations.put("entity_clear_drops", (ctx, node) -> {
            Entity entity = ctx.getInputValue(node, "entity", Entity.class, null);
            if (entity instanceof LivingEntity living && living.getEquipment() != null) {
                living.getEquipment().clear();
            }
        });

        operations.put("entity_pickup_item", (ctx, node) -> {
            Entity entity = ctx.getInputValue(node, "entity", Entity.class, null);
            Boolean canPickup = ctx.getInputValue(node, "can_pickup", Boolean.class, true);
            if (entity instanceof LivingEntity living) {
                living.setCanPickupItems(canPickup);
            }
        });

        operations.put("entity_state", (ctx, node) -> {
            Entity entity = ctx.getInputValue(node, "entity", Entity.class, null);
            String property = ctx.getInputValue(node, "property", String.class, "");
            String action = ctx.getInputValue(node, "action", String.class, "get");
            String stringValue = ctx.getInputValue(node, "string_value", String.class, "");
            Boolean booleanValue = ctx.getInputValue(node, "boolean_value", Boolean.class, false);
            Double numberValue = ctx.getInputValue(node, "number_value", Double.class, 0.0);
            Entity entityValue = ctx.getInputValue(node, "entity_value", Entity.class, null);
            boolean success = false;
            Object result = null;

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
                                FlowMutations.setHealth(ctx, living, health);
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
                            FlowMutations.setHealth(ctx, living, 0.0);
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

            ctx.setOutput(node, "success", success);
            ctx.setOutput(node, "result", result);
            if ("get".equalsIgnoreCase(action) && result != null && property != null && !property.isBlank()) {
                ctx.setOutput(node, property, result);
            }
        });

        operations.put("entity_spawn", (ctx, node) -> {
            String entityType = ctx.getInputValue(node, "entity_type", String.class, "ZOMBIE");
            Location location = ctx.getInputValue(node, "location", Location.class, null);
            Entity spawned = null;
            if (location != null && location.getWorld() != null) {
                try {
                    spawned = location.getWorld().spawnEntity(location, EntityType.valueOf(entityType.toUpperCase()));
                } catch (IllegalArgumentException e) {
                    Log.warn("[Flow] Invalid entity type: " + entityType);
                }
            }
            ctx.setOutput(node, "entity", spawned);
        });

        operations.put("entity_despawn", (ctx, node) -> {
            Entity entity = ctx.getInputValue(node, "entity", Entity.class, null);
            if (entity != null) {
                entity.remove();
            }
        });

        operations.put("entity_get_nearby", (ctx, node) -> {
            Location center = ctx.getInputValue(node, "center", Location.class, null);
            Double radius = ctx.getInputValue(node, "radius", Double.class, 10.0);
            String typeFilter = ctx.getInputValue(node, "entity_type", String.class, null);
            List<Entity> entities = new ArrayList<>();
            if (center != null && center.getWorld() != null) {
                entities.addAll(center.getWorld().getNearbyEntities(center, radius, radius, radius));
                if (typeFilter != null) {
                    try {
                        EntityType filterType = EntityType.valueOf(typeFilter.toUpperCase());
                        entities.removeIf(entity -> entity.getType() != filterType);
                    } catch (IllegalArgumentException e) {
                        Log.warn("[Flow] Invalid entity type filter: " + typeFilter);
                    }
                }
            }
            ctx.setOutput(node, "entities", entities);
        });

        operations.put("entity_get_all", (ctx, node) -> {
            World world = ctx.getInputValue(node, "world", World.class, null);
            String typeFilter = ctx.getInputValue(node, "entity_type", String.class, null);
            List<Entity> entities = new ArrayList<>();
            if (world != null) {
                if (typeFilter == null) {
                    entities.addAll(world.getEntities());
                } else {
                    try {
                        EntityType filterType = EntityType.valueOf(typeFilter.toUpperCase());
                        for (Entity entity : world.getEntities()) {
                            if (entity.getType() == filterType) {
                                entities.add(entity);
                            }
                        }
                    } catch (IllegalArgumentException e) {
                        Log.warn("[Flow] Invalid entity type filter: " + typeFilter);
                    }
                }
            }
            ctx.setOutput(node, "entities", entities);
        });

        operations.put("entity_teleport", (ctx, node) -> {
            Entity entity = ctx.getInputValue(node, "entity", Entity.class, null);
            Location location = ctx.getInputValue(node, "location", Location.class, null);
            if (entity != null && location != null) {
                entity.teleport(location);
            }
        });

        operations.put("entity_remove", (ctx, node) -> {
            Entity entity = ctx.getInputValue(node, "entity", Entity.class, null);
            if (entity != null) {
                entity.remove();
            }
        });

        operations.put("entity_get_player_nearby", (ctx, node) -> {
            Location center = ctx.getInputValue(node, "center", Location.class, null);
            Double radius = ctx.getInputValue(node, "radius", Double.class, 10.0);
            List<Player> players = new ArrayList<>();
            if (center != null && center.getWorld() != null) {
                for (Entity entity : center.getWorld().getNearbyEntities(center, radius, radius, radius)) {
                    if (entity instanceof Player player) {
                        players.add(player);
                    }
                }
            }
            ctx.setOutput(node, "players", players);
        });

        operations.put("entity_get_mob_nearby", (ctx, node) -> {
            Location center = ctx.getInputValue(node, "center", Location.class, null);
            Double radius = ctx.getInputValue(node, "radius", Double.class, 10.0);
            String typeFilter = ctx.getInputValue(node, "entity_type", String.class, null);
            List<Entity> mobs = new ArrayList<>();
            if (center != null && center.getWorld() != null) {
                EntityType filterType = null;
                if (typeFilter != null) {
                    try {
                        filterType = EntityType.valueOf(typeFilter.toUpperCase());
                    } catch (IllegalArgumentException e) {
                        Log.warn("[Flow] Invalid entity type filter: " + typeFilter);
                    }
                }
                for (Entity entity : center.getWorld().getNearbyEntities(center, radius, radius, radius)) {
                    if (entity instanceof Player) {
                        continue;
                    }
                    if (filterType == null || entity.getType() == filterType) {
                        mobs.add(entity);
                    }
                }
            }
            ctx.setOutput(node, "mobs", mobs);
        });

        operations.put("entity_is_alive", (ctx, node) -> {
            Entity entity = ctx.getInputValue(node, "entity", Entity.class, null);
            boolean isValid = entity != null && entity.isValid();
            boolean isDead = entity == null || entity.isDead();
            boolean isAlive = isValid && !isDead;
            ctx.setOutput(node, "is_alive", isAlive);
            ctx.setOutput(node, "is_valid", isValid);
            ctx.setOutput(node, "is_dead", isDead);
        });

        operations.put("entity_get_info", (ctx, node) -> {
            Entity entity = ctx.getInputValue(node, "entity", Entity.class, null);
            if (entity == null) {
                ctx.setOutput(node, "entity_type", "");
                ctx.setOutput(node, "uuid", "");
                ctx.setOutput(node, "name", "");
                ctx.setOutput(node, "custom_name", "");
                ctx.setOutput(node, "location", null);
                ctx.setOutput(node, "world_name", "");
                ctx.setOutput(node, "ticks_lived", 0);
                ctx.setOutput(node, "is_dead", true);
                ctx.setOutput(node, "is_valid", false);
                return;
            }
            Location location = entity.getLocation();
            ctx.setOutput(node, "entity_type", entity.getType().name());
            ctx.setOutput(node, "uuid", entity.getUniqueId().toString());
            ctx.setOutput(node, "name", entity.getName());
            ctx.setOutput(node, "custom_name", entity.getCustomName());
            ctx.setOutput(node, "location", location);
            ctx.setOutput(node, "world_name", location.getWorld() != null ? location.getWorld().getName() : "");
            ctx.setOutput(node, "ticks_lived", entity.getTicksLived());
            boolean valid = entity.isValid();
            ctx.setOutput(node, "is_dead", entity.isDead() || !valid);
            ctx.setOutput(node, "is_valid", valid);
        });

        operations.put("entity_get_health", (ctx, node) -> {
            Entity entity = ctx.getInputValue(node, "entity", Entity.class, null);
            if (entity instanceof LivingEntity living) {
                double maxHealth = 0.0;
                if (living.getAttribute(Attribute.GENERIC_MAX_HEALTH) != null) {
                    maxHealth = living.getAttribute(Attribute.GENERIC_MAX_HEALTH).getValue();
                }
                ctx.setOutput(node, "health", living.getHealth());
                ctx.setOutput(node, "max_health", maxHealth);
                ctx.setOutput(node, "absorption", living.getAbsorptionAmount());
                return;
            }
            ctx.setOutput(node, "health", 0.0);
            ctx.setOutput(node, "max_health", 0.0);
            ctx.setOutput(node, "absorption", 0.0);
        });

        operations.put("entity_get_velocity", (ctx, node) -> {
            Entity entity = ctx.getInputValue(node, "entity", Entity.class, null);
            Vector velocity = entity != null ? entity.getVelocity() : null;
            ctx.setOutput(node, "velocity", velocity);
        });

        operations.put("entity_get_fire_ticks", (ctx, node) -> {
            Entity entity = ctx.getInputValue(node, "entity", Entity.class, null);
            ctx.setOutput(node, "fire_ticks", entity != null ? entity.getFireTicks() : 0);
        });

        operations.put("entity_get_freeze_ticks", (ctx, node) -> {
            Entity entity = ctx.getInputValue(node, "entity", Entity.class, null);
            ctx.setOutput(node, "freeze_ticks", entity != null ? entity.getFreezeTicks() : 0);
        });

        operations.put("entity_get_last_damage", (ctx, node) -> {
            Entity entity = ctx.getInputValue(node, "entity", Entity.class, null);
            if (!(entity instanceof LivingEntity living)) {
                ctx.setOutput(node, "damage", 0.0);
                ctx.setOutput(node, "cause", "");
                ctx.setOutput(node, "damager", null);
                return;
            }
            EntityDamageEvent damageEvent = living.getLastDamageCause();
            double damage = 0.0;
            String cause = "";
            Entity damager = null;
            if (damageEvent != null) {
                damage = damageEvent.getDamage();
                cause = damageEvent.getCause().name();
                if (damageEvent instanceof EntityDamageByEntityEvent byEntityEvent) {
                    damager = byEntityEvent.getDamager();
                }
            }
            ctx.setOutput(node, "damage", damage);
            ctx.setOutput(node, "cause", cause);
            ctx.setOutput(node, "damager", damager);
        });

        operations.put("entity_get_location", (ctx, node) -> {
            Entity entity = ctx.getInputValue(node, "entity", Entity.class, null);
            ctx.setOutput(node, "location", entity != null ? entity.getLocation() : null);
        });
    }

    public void registerTo(HandlerRegistry registry) {
        registry.register("EntityActionHandler", this);
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

}
