package restudio.resync.flow.handler.generic;

import org.bukkit.DyeColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
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
import restudio.flow.data.FlowNode;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.FlowMutations;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.NodeHandler;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

public class EntityActionHandler implements NodeHandler {
    private final Map<String, BiConsumer<FlowContext, FlowNode>> operations = new ConcurrentHashMap<>();

    public EntityActionHandler() {
        operations.put("entity_set_type", (ctx, node) -> {
            Entity entity = requireEntity(ctx, node);
            String typeName = ctx.getInputValue(node, "entity_type", String.class, "PIG");
            Location location = entity.getLocation();
            if (location.getWorld() == null) {
                throw new IllegalArgumentException("Entity world is unavailable");
            }
            EntityType newType = entityType(typeName);
            location.getWorld().spawnEntity(location, newType);
            entity.remove();
        });

        operations.put("entity_set_rotation", (ctx, node) -> {
            Entity entity = requireEntity(ctx, node);
            Location location = entity.getLocation();
            Float yaw = ctx.getInputValue(node, "yaw", Float.class, location.getYaw());
            Float pitch = ctx.getInputValue(node, "pitch", Float.class, location.getPitch());
            if (!Float.isFinite(yaw) || !Float.isFinite(pitch)) throw new IllegalArgumentException("Entity rotation must be finite");
            location.setYaw(yaw);
            location.setPitch(pitch);
            if (!entity.teleport(location)) throw new IllegalStateException("Entity rotation could not be applied");
        });

        operations.put("entity_set_damage", (ctx, node) -> {
            Double damage = ctx.getInputValue(node, "damage", Double.class, 1.0);
            requireAttribute(ctx, node, Attribute.ATTACK_DAMAGE).setBaseValue(requireNonNegative(damage, "Entity attack damage"));
        });

        operations.put("entity_set_armor_value", (ctx, node) -> {
            Double armor = ctx.getInputValue(node, "armor", Double.class, 0.0);
            requireAttribute(ctx, node, Attribute.ARMOR).setBaseValue(requireNonNegative(armor, "Entity armor"));
        });

        operations.put("entity_set_follow_range", (ctx, node) -> {
            Double range = ctx.getInputValue(node, "range", Double.class, 32.0);
            requireAttribute(ctx, node, Attribute.FOLLOW_RANGE).setBaseValue(requireNonNegative(range, "Entity follow range"));
        });

        operations.put("entity_set_knockback_resistance", (ctx, node) -> {
            Double resistance = ctx.getInputValue(node, "resistance", Double.class, 0.0);
            double value = requireNonNegative(resistance, "Entity knockback resistance");
            if (value > 1) throw new IllegalArgumentException("Entity knockback resistance cannot exceed 1");
            requireAttribute(ctx, node, Attribute.KNOCKBACK_RESISTANCE).setBaseValue(value);
        });

        operations.put("entity_set_wet", (ctx, node) -> {
            requireEntity(ctx, node);
            throw new UnsupportedOperationException("Minecraft does not expose a writable generic entity wet state");
        });

        operations.put("entity_set_shaking", (ctx, node) -> {
            Entity entity = requireEntity(ctx, node);
            Boolean shaking = ctx.getInputValue(node, "shaking", Boolean.class, false);
            entity.setFreezeTicks(Boolean.TRUE.equals(shaking) ? entity.getMaxFreezeTicks() : 0);
        });

        operations.put("entity_set_owner", (ctx, node) -> {
            Entity entity = requireEntity(ctx, node);
            Entity owner = ctx.getInputValue(node, "owner", Entity.class, null);
            if (!(entity instanceof Tameable tameable)) throw new IllegalArgumentException("Entity cannot be tamed");
            if (!(owner instanceof AnimalTamer tamer)) throw new IllegalArgumentException("Entity owner must be an animal tamer");
            tameable.setOwner(tamer);
        });

        operations.put("entity_set_angry", (ctx, node) -> {
            Entity entity = requireEntity(ctx, node);
            Boolean angry = ctx.getInputValue(node, "angry", Boolean.class, true);
            if (!(entity instanceof Mob mob)) {
                throw new IllegalArgumentException("Entity does not support anger state");
            }
            if (!Boolean.TRUE.equals(angry)) {
                mob.setTarget(null);
            } else if (mob.getTarget() == null) {
                throw new IllegalArgumentException("An angry entity requires an active target");
            }
        });

        operations.put("entity_set_love_mode", (ctx, node) -> {
            Entity entity = requireEntity(ctx, node);
            Integer ticks = ctx.getInputValue(node, "ticks", Integer.class, 600);
            if (!(entity instanceof Animals animals)) throw new IllegalArgumentException("Entity does not support love mode");
            if (ticks < 0 || ticks > 72_000) throw new IllegalArgumentException("Love mode ticks must be between 0 and 72000");
            animals.setLoveModeTicks(ticks);
        });

        operations.put("entity_set_color", (ctx, node) -> {
            Entity entity = requireEntity(ctx, node);
            String colorName = ctx.getInputValue(node, "color", String.class, "WHITE");
            if (colorName == null || colorName.isBlank()) throw new IllegalArgumentException("Entity dye color is required");
            DyeColor color;
            try {
                color = DyeColor.valueOf(colorName.toUpperCase(Locale.ROOT));
                entity.getClass().getMethod("setColor", DyeColor.class).invoke(entity, color);
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("Unknown dye color: " + colorName, exception);
            } catch (ReflectiveOperationException exception) {
                throw new IllegalArgumentException("Entity does not support dye color: " + entity.getType(), exception);
            }
        });

        operations.put("entity_set_variant", (ctx, node) -> {
            Entity entity = requireEntity(ctx, node);
            String variant = ctx.getInputValue(node, "variant", String.class, "");
            if (variant == null || variant.isBlank()) {
                throw new IllegalArgumentException("Entity variant is required");
            }
            String normalized = variant.toUpperCase(Locale.ROOT);
            switch (entity) {
                case Frog frog -> frog.setVariant(Frog.Variant.valueOf(normalized));
                case Villager villager -> villager.setVillagerType(Villager.Type.valueOf(normalized));
                case Cat cat -> cat.setCatType(Cat.Type.valueOf(normalized));
                case Fox fox -> fox.setFoxType(Fox.Type.valueOf(normalized));
                case MushroomCow cow -> cow.setVariant(MushroomCow.Variant.valueOf(normalized));
                case Llama llama -> llama.setColor(Llama.Color.valueOf(normalized));
                case Rabbit rabbit -> rabbit.setRabbitType(Rabbit.Type.valueOf(normalized));
                case Axolotl axolotl -> axolotl.setVariant(Axolotl.Variant.valueOf(normalized));
                case Parrot parrot -> parrot.setVariant(Parrot.Variant.valueOf(normalized));
                case TropicalFish fish -> fish.setPattern(TropicalFish.Pattern.valueOf(normalized));
                default -> throw new IllegalArgumentException("Entity does not support variants: " + entity.getType());
            }
        });

        operations.put("entity_set_held_item", (ctx, node) -> {
            Entity entity = requireEntity(ctx, node);
            ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
            if (!(entity instanceof Mob mob) || mob.getEquipment() == null) throw new IllegalArgumentException("Entity does not support held equipment");
            mob.getEquipment().setItemInMainHand(item);
        });

        operations.put("entity_set_armor", (ctx, node) -> {
            LivingEntity living = requireLivingEntity(ctx, node);
            String slot = ctx.getInputValue(node, "slot", String.class, "HEAD");
            ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
            if (living.getEquipment() == null) throw new IllegalArgumentException("Entity does not support equipment");
            switch (slot.toUpperCase(Locale.ROOT)) {
                case "HEAD" -> living.getEquipment().setHelmet(item);
                case "CHEST" -> living.getEquipment().setChestplate(item);
                case "LEGS" -> living.getEquipment().setLeggings(item);
                case "FEET" -> living.getEquipment().setBoots(item);
                case "HAND" -> living.getEquipment().setItemInMainHand(item);
                case "OFFHAND" -> living.getEquipment().setItemInOffHand(item);
                default -> throw new IllegalArgumentException("Unknown entity equipment slot: " + slot);
            }
        });

        operations.put("entity_set_drop_chances", (ctx, node) -> {
            Mob living = requireMob(ctx, node);
            Double chance = ctx.getInputValue(node, "chance", Double.class, 0.085);
            if (living.getEquipment() == null) throw new IllegalArgumentException("Entity does not support equipment drops");
            if (!Double.isFinite(chance) || chance < 0 || chance > 1) throw new IllegalArgumentException("Equipment drop chance must be between 0 and 1");
            float floatChance = chance.floatValue();
            living.getEquipment().setDropChance(EquipmentSlot.HAND, floatChance);
            living.getEquipment().setDropChance(EquipmentSlot.OFF_HAND, floatChance);
            living.getEquipment().setDropChance(EquipmentSlot.HEAD, floatChance);
            living.getEquipment().setDropChance(EquipmentSlot.CHEST, floatChance);
            living.getEquipment().setDropChance(EquipmentSlot.LEGS, floatChance);
            living.getEquipment().setDropChance(EquipmentSlot.FEET, floatChance);
        });

        operations.put("entity_add_drop", (ctx, node) -> {
            Entity entity = requireEntity(ctx, node);
            ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
            if (item == null || item.getType().isAir()) throw new IllegalArgumentException("Drop item is required");
            entity.getWorld().dropItemNaturally(entity.getLocation(), item.clone());
        });

        operations.put("entity_clear_drops", (ctx, node) -> {
            Mob living = requireMob(ctx, node);
            if (living.getEquipment() == null) throw new IllegalArgumentException("Entity does not support equipment drops");
            for (EquipmentSlot slot : List.of(EquipmentSlot.HAND, EquipmentSlot.OFF_HAND, EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET)) {
                living.getEquipment().setDropChance(slot, 0);
            }
        });

        operations.put("entity_pickup_item", (ctx, node) -> {
            LivingEntity living = requireLivingEntity(ctx, node);
            Boolean canPickup = ctx.getInputValue(node, "can_pickup", Boolean.class, true);
            living.setCanPickupItems(canPickup);
        });

        operations.put("entity_state", (ctx, node) -> {
            Entity entity = requireEntity(ctx, node);
            String property = ctx.getInputValue(node, "property", String.class, "");
            String action = ctx.getInputValue(node, "action", String.class, "get");
            String stringValue = ctx.getInputValue(node, "string_value", String.class, "");
            Boolean booleanValue = ctx.getInputValue(node, "boolean_value", Boolean.class, false);
            Double numberValue = ctx.getInputValue(node, "number_value", Double.class, 0.0);
            Entity entityValue = ctx.getInputValue(node, "entity_value", Entity.class, null);
            boolean success = false;
            Object result = null;

            if (property == null || property.isBlank()) throw new IllegalArgumentException("Entity state property is required");
            if (action == null || !List.of("get", "set", "do").contains(action.toLowerCase(Locale.ROOT))) throw new IllegalArgumentException("Unknown entity state action: " + action);
            switch (property.toLowerCase(Locale.ROOT)) {
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
                            int ticks = requireWholeNumber(numberValue, "Entity fire ticks", 0, Integer.MAX_VALUE);
                            entity.setFireTicks(ticks);
                            success = true;
                        } else if ("get".equalsIgnoreCase(action)) {
                            result = entity.getFireTicks();
                            success = true;
                        }
                    }
                    case "frozen" -> {
                        if ("set".equalsIgnoreCase(action)) {
                            int ticks = requireWholeNumber(numberValue, "Entity freeze ticks", 0, entity.getMaxFreezeTicks());
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
                        if (entity instanceof LivingEntity living) {
                            AttributeInstance maximumHealth = living.getAttribute(Attribute.MAX_HEALTH);
                            if (maximumHealth != null) {
                                if ("set".equalsIgnoreCase(action)) {
                                    double maxHealth = requirePositive(numberValue, "Entity maximum health");
                                    maximumHealth.setBaseValue(maxHealth);
                                    if (living.getHealth() > maximumHealth.getValue()) FlowMutations.setHealth(ctx, living, maximumHealth.getValue());
                                    success = true;
                                } else if ("get".equalsIgnoreCase(action)) {
                                    result = maximumHealth.getValue();
                                    success = true;
                                }
                            }
                        }
                    }
                    case "speed" -> {
                        if (entity instanceof LivingEntity living) {
                            AttributeInstance movementSpeed = living.getAttribute(Attribute.MOVEMENT_SPEED);
                            if (movementSpeed != null) {
                                if ("set".equalsIgnoreCase(action)) {
                                    movementSpeed.setBaseValue(requireNonNegative(numberValue, "Entity movement speed"));
                                    success = true;
                                } else if ("get".equalsIgnoreCase(action)) {
                                    result = movementSpeed.getBaseValue();
                                    success = true;
                                }
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
                                } else {
                                    throw new IllegalArgumentException("Entity target must be living");
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
                    case "ai" -> {
                        if (entity instanceof LivingEntity living) {
                            if ("set".equalsIgnoreCase(action)) {
                                living.setAI(Boolean.TRUE.equals(booleanValue));
                                success = true;
                            } else if ("get".equalsIgnoreCase(action)) {
                                result = living.hasAI();
                                success = true;
                            }
                        }
                    }
                    case "gravity" -> {
                        if ("set".equalsIgnoreCase(action)) {
                            entity.setGravity(Boolean.TRUE.equals(booleanValue));
                            success = true;
                        } else if ("get".equalsIgnoreCase(action)) {
                            result = entity.hasGravity();
                            success = true;
                        }
                    }
                    case "collidable" -> {
                        if (entity instanceof LivingEntity living) {
                            if ("set".equalsIgnoreCase(action)) {
                                living.setCollidable(Boolean.TRUE.equals(booleanValue));
                                success = true;
                            } else if ("get".equalsIgnoreCase(action)) {
                                result = living.isCollidable();
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

            if (!success) throw new IllegalArgumentException("Entity state property or action is unsupported for " + entity.getType() + ": " + property + "." + action);

            ctx.setOutput(node, "success", success);
            ctx.setOutput(node, "result", result);
            if ("get".equalsIgnoreCase(action) && result != null && property != null && !property.isBlank()) {
                ctx.setOutput(node, property, result);
            }
        });

        operations.put("entity_data", (ctx, node) -> {
            Entity entity = requireEntity(ctx, node);
            String property = ctx.getInputValue(node, "property", String.class, "");
            String action = ctx.getInputValue(node, "action", String.class, "get");
            if ("get".equalsIgnoreCase(action)) {
                ctx.setOutput(node, "value", EntityDataAccess.get(entity, property));
            } else if ("set".equalsIgnoreCase(action)) {
                EntityDataAccess.set(ctx, entity, property, ctx.getInputValue(node, "value", Object.class, null));
            } else {
                throw new IllegalArgumentException("Unknown entity data action: " + action);
            }
            ctx.setOutput(node, "entity", entity);
            ctx.setOutput(node, "success", true);
        });

        operations.put("entity_typed_data", (ctx, node) -> {
            Entity entity = requireEntity(ctx, node);
            String property = typedProperty(ctx, node);
            String action = ctx.getInputValue(node, "action", String.class, "get");
            String valuePin = node.getHandlerConfig().getString("valuePin", "value");
            if ("get".equalsIgnoreCase(action)) {
                ctx.setOutput(node, valuePin, EntityDataAccess.get(entity, property));
            } else if ("set".equalsIgnoreCase(action)) {
                EntityDataAccess.set(ctx, entity, property, ctx.getInputValue(node, valuePin, Object.class, null));
            } else {
                throw new IllegalArgumentException("Unknown entity data action: " + action);
            }
            ctx.setOutput(node, "entity", entity);
            ctx.setOutput(node, "success", true);
        });

        operations.put("entity_data_entry", (ctx, node) -> {
            Map<String, Object> data = new LinkedHashMap<>();
            Object existing = ctx.getInputValue(node, "data", Object.class, null);
            if (existing != null) {
                if (!(existing instanceof Map<?, ?> values)) throw new IllegalArgumentException("Entity data input must be Entity Data");
                for (Map.Entry<?, ?> entry : values.entrySet()) {
                    if (entry.getKey() != null) data.put(entry.getKey().toString(), entry.getValue());
                }
            }
            String valuePin = node.getHandlerConfig().getString("valuePin", "value");
            data.put(typedProperty(ctx, node), ctx.getInputValue(node, valuePin, Object.class, null));
            ctx.setOutput(node, "data", data);
        });

        operations.put("entity_apply_data", (ctx, node) -> {
            Entity entity = requireEntity(ctx, node);
            EntityDataAccess.apply(ctx, entity, ctx.getInputValue(node, "data", Object.class, null));
            ctx.setOutput(node, "entity", entity);
            ctx.setOutput(node, "success", true);
        });

        operations.put("entity_spawn", (ctx, node) -> {
            String typeName = ctx.getInputValue(node, "entity_type", String.class, "ZOMBIE");
            Location location = ctx.getInputValue(node, "location", Location.class, null);
            if (location == null || location.getWorld() == null) {
                throw new IllegalArgumentException("Entity spawn world location is required");
            }
            Entity entity = location.getWorld().spawnEntity(location, entityType(typeName));
            try {
                EntityDataAccess.apply(ctx, entity, ctx.getInputValue(node, "data", Object.class, null));
            } catch (RuntimeException exception) {
                entity.remove();
                throw exception;
            }
            ctx.setOutput(node, "entity", entity);
        });

        operations.put("entity_despawn", (ctx, node) -> {
            requireEntity(ctx, node).remove();
        });

        operations.put("entity_get_nearby", (ctx, node) -> {
            Location center = requireLocation(ctx, node, "center");
            Double radius = ctx.getInputValue(node, "radius", Double.class, 10.0);
            String typeFilter = ctx.getInputValue(node, "entity_type", String.class, null);
            List<Entity> entities = new ArrayList<>();
            requireRadius(radius);
            entities.addAll(center.getWorld().getNearbyEntities(center, radius, radius, radius));
            if (typeFilter != null && !typeFilter.isBlank()) {
                EntityType filterType = entityType(typeFilter);
                entities.removeIf(entity -> entity.getType() != filterType);
            }
            ctx.setOutput(node, "entities", entities);
        });

        operations.put("entity_get_all", (ctx, node) -> {
            World world = ctx.getInputValue(node, "world", World.class, null);
            if (world == null) throw new IllegalArgumentException("World is required");
            String typeFilter = ctx.getInputValue(node, "entity_type", String.class, null);
            List<Entity> entities = new ArrayList<>();
            if (typeFilter == null || typeFilter.isBlank()) {
                entities.addAll(world.getEntities());
            } else {
                EntityType filterType = entityType(typeFilter);
                for (Entity entity : world.getEntities()) {
                    if (entity.getType() == filterType) {
                        entities.add(entity);
                    }
                }
            }
            ctx.setOutput(node, "entities", entities);
        });

        operations.put("entity_teleport", (ctx, node) -> {
            Entity entity = requireEntity(ctx, node);
            Location location = requireLocation(ctx, node, "location");
            if (!entity.teleport(location)) throw new IllegalStateException("Entity could not be teleported");
        });

        operations.put("entity_remove", (ctx, node) -> {
            requireEntity(ctx, node).remove();
        });

        operations.put("entity_get_player_nearby", (ctx, node) -> {
            Location center = requireLocation(ctx, node, "center");
            Double radius = ctx.getInputValue(node, "radius", Double.class, 10.0);
            requireRadius(radius);
            List<Player> players = new ArrayList<>();
            for (Entity entity : center.getWorld().getNearbyEntities(center, radius, radius, radius)) {
                if (entity instanceof Player player) {
                    players.add(player);
                }
            }
            ctx.setOutput(node, "players", players);
        });

        operations.put("entity_get_mob_nearby", (ctx, node) -> {
            Location center = requireLocation(ctx, node, "center");
            Double radius = ctx.getInputValue(node, "radius", Double.class, 10.0);
            requireRadius(radius);
            String typeFilter = ctx.getInputValue(node, "entity_type", String.class, null);
            List<Entity> mobs = new ArrayList<>();
            EntityType filterType = typeFilter != null && !typeFilter.isBlank() ? entityType(typeFilter) : null;
            for (Entity entity : center.getWorld().getNearbyEntities(center, radius, radius, radius)) {
                if (entity instanceof Player) {
                    continue;
                }
                if (filterType == null || entity.getType() == filterType) {
                    mobs.add(entity);
                }
            }
            ctx.setOutput(node, "mobs", mobs);
        });

        operations.put("entity_is_alive", (ctx, node) -> {
            Entity entity = requireEntity(ctx, node);
            boolean isValid = entity.isValid();
            boolean isDead = entity.isDead();
            boolean isAlive = isValid && !isDead;
            ctx.setOutput(node, "is_alive", isAlive);
            ctx.setOutput(node, "is_valid", isValid);
            ctx.setOutput(node, "is_dead", isDead);
        });

        operations.put("entity_get_info", (ctx, node) -> {
            Entity entity = requireEntity(ctx, node);
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
            LivingEntity living = requireLivingEntity(ctx, node);
            AttributeInstance maxHealth = living.getAttribute(Attribute.MAX_HEALTH);
            if (maxHealth == null) throw new IllegalArgumentException("Entity does not expose maximum health");
            ctx.setOutput(node, "health", living.getHealth());
            ctx.setOutput(node, "max_health", maxHealth.getValue());
            ctx.setOutput(node, "absorption", living.getAbsorptionAmount());
        });

        operations.put("entity_get_velocity", (ctx, node) -> {
            ctx.setOutput(node, "velocity", requireEntity(ctx, node).getVelocity());
        });

        operations.put("entity_get_fire_ticks", (ctx, node) -> {
            ctx.setOutput(node, "fire_ticks", requireEntity(ctx, node).getFireTicks());
        });

        operations.put("entity_get_freeze_ticks", (ctx, node) -> {
            ctx.setOutput(node, "freeze_ticks", requireEntity(ctx, node).getFreezeTicks());
        });

        operations.put("entity_get_last_damage", (ctx, node) -> {
            LivingEntity living = requireLivingEntity(ctx, node);
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
            ctx.setOutput(node, "location", requireEntity(ctx, node).getLocation());
        });
    }

    public void registerTo(HandlerRegistry registry) {
        registry.register("EntityActionHandler", this);
    }

    @Override
    public void execute(FlowContext ctx, FlowNode node) {
        String operation = node.getHandlerConfig().getString("operation");
        BiConsumer<FlowContext, FlowNode> op = operation != null ? operations.get(operation) : null;
        if (op == null) {
            throw new IllegalArgumentException("Unknown entity action operation: " + operation);
        }
        op.accept(ctx, node);
        ctx.triggerOutput("flow");
    }

    private static EntityType entityType(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Entity type is required");
        }
        String normalized = value.toUpperCase(Locale.ROOT);
        if (normalized.startsWith("MINECRAFT:")) {
            normalized = normalized.substring("MINECRAFT:".length());
        }
        try {
            return EntityType.valueOf(normalized);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unknown entity type: " + value, exception);
        }
    }

    private static String typedProperty(FlowContext context, FlowNode node) {
        String property = context.getInputValue(node, "property", String.class, "");
        if (property == null || property.isBlank()) throw new IllegalArgumentException("Entity data property is required");
        String prefix = node.getHandlerConfig().getString("propertyPrefix", "");
        return prefix + property;
    }

    private static Entity requireEntity(FlowContext context, FlowNode node) {
        Entity entity = context.getInputValue(node, "entity", Entity.class, null);
        if (entity == null) throw new IllegalArgumentException("Entity is required");
        return entity;
    }

    private static LivingEntity requireLivingEntity(FlowContext context, FlowNode node) {
        Entity entity = requireEntity(context, node);
        if (!(entity instanceof LivingEntity living)) throw new IllegalArgumentException("Entity must be living");
        return living;
    }

    private static Mob requireMob(FlowContext context, FlowNode node) {
        LivingEntity living = requireLivingEntity(context, node);
        if (!(living instanceof Mob mob)) throw new IllegalArgumentException("Entity must be a mob");
        return mob;
    }

    private static AttributeInstance requireAttribute(FlowContext context, FlowNode node, Attribute attribute) {
        LivingEntity entity = requireLivingEntity(context, node);
        AttributeInstance instance = entity.getAttribute(attribute);
        if (instance == null) throw new IllegalArgumentException("Entity does not support attribute: " + attribute.getKey());
        return instance;
    }

    private static Location requireLocation(FlowContext context, FlowNode node, String inputName) {
        Location location = context.getInputValue(node, inputName, Location.class, null);
        if (location == null || location.getWorld() == null) throw new IllegalArgumentException("Location input is required: " + inputName);
        return location;
    }

    private static double requireNonNegative(double value, String field) {
        if (!Double.isFinite(value) || value < 0) throw new IllegalArgumentException(field + " must be a finite non-negative number");
        return value;
    }

    private static double requirePositive(double value, String field) {
        if (!Double.isFinite(value) || value <= 0) throw new IllegalArgumentException(field + " must be a finite positive number");
        return value;
    }

    private static int requireWholeNumber(double value, String field, int minimum, int maximum) {
        if (!Double.isFinite(value) || value != Math.rint(value) || value < minimum || value > maximum) {
            throw new IllegalArgumentException(field + " must be a whole number between " + minimum + " and " + maximum);
        }
        return (int) value;
    }

    private static void requireRadius(double radius) {
        if (!Double.isFinite(radius) || radius < 0 || radius > 128) throw new IllegalArgumentException("Entity search radius must be between 0 and 128");
    }

}
