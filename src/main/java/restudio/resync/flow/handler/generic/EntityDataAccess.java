package restudio.resync.flow.handler.generic;

import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attributable;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Explosive;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.util.Vector;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.FlowMutations;

import java.util.Locale;
import java.util.Map;

final class EntityDataAccess {
    private EntityDataAccess() {
    }

    static void apply(FlowContext context, Entity entity, Object data) {
        if (data == null) {
            return;
        }
        if (!(data instanceof Map<?, ?> values)) {
            throw new IllegalArgumentException("Entity data must be a map");
        }
        for (Map.Entry<?, ?> entry : values.entrySet()) {
            if (entry.getKey() == null) {
                continue;
            }
            set(context, entity, entry.getKey().toString(), entry.getValue());
        }
    }

    static Object get(Entity entity, String rawProperty) {
        String property = property(rawProperty);
        if (property.startsWith("attribute:")) {
            return attribute(entity, property.substring("attribute:".length())).getBaseValue();
        }
        return switch (property) {
            case "type" -> entity.getType().getKey().toString();
            case "uuid" -> entity.getUniqueId().toString();
            case "custom_name", "name" -> entity.getCustomName();
            case "custom_name_visible", "name_visible" -> entity.isCustomNameVisible();
            case "glowing" -> entity.isGlowing();
            case "silent" -> entity.isSilent();
            case "invulnerable" -> entity.isInvulnerable();
            case "gravity" -> entity.hasGravity();
            case "visual_fire" -> entity.isVisualFire();
            case "fire_ticks", "burning" -> entity.getFireTicks();
            case "freeze_ticks", "frozen" -> entity.getFreezeTicks();
            case "ticks_lived" -> entity.getTicksLived();
            case "fall_distance" -> entity.getFallDistance();
            case "portal_cooldown" -> entity.getPortalCooldown();
            case "velocity" -> entity.getVelocity().clone();
            case "location" -> entity.getLocation().clone();
            case "health" -> living(entity, property).getHealth();
            case "absorption" -> living(entity, property).getAbsorptionAmount();
            case "ai" -> living(entity, property).hasAI();
            case "collidable" -> living(entity, property).isCollidable();
            case "can_pickup_items", "pickup_items" -> living(entity, property).getCanPickupItems();
            case "persistent" -> mob(entity, property).isPersistent();
            case "remove_when_far_away" -> mob(entity, property).getRemoveWhenFarAway();
            case "target" -> mob(entity, property).getTarget();
            case "age" -> ageable(entity, property).getAge();
            case "age_locked" -> ageable(entity, property).getAgeLock();
            case "baby" -> !ageable(entity, property).isAdult();
            case "fuse_ticks" -> fuseTicks(entity, property);
            case "yield" -> explosive(entity, property).getYield();
            case "incendiary" -> explosive(entity, property).isIncendiary();
            case "explosion_radius" -> creeper(entity, property).getExplosionRadius();
            case "max_fuse_ticks" -> creeper(entity, property).getMaxFuseTicks();
            case "powered" -> creeper(entity, property).isPowered();
            case "ignited" -> creeper(entity, property).isIgnited();
            default -> throw unsupported(entity, property);
        };
    }

    static void set(FlowContext context, Entity entity, String rawProperty, Object value) {
        String property = property(rawProperty);
        if (property.startsWith("attribute:")) {
            attribute(entity, property.substring("attribute:".length())).setBaseValue(decimal(value, property));
            return;
        }
        switch (property) {
            case "custom_name", "name" -> entity.setCustomName(value != null ? value.toString() : null);
            case "custom_name_visible", "name_visible" -> entity.setCustomNameVisible(bool(value, property));
            case "glowing" -> entity.setGlowing(bool(value, property));
            case "silent" -> entity.setSilent(bool(value, property));
            case "invulnerable" -> entity.setInvulnerable(bool(value, property));
            case "gravity" -> entity.setGravity(bool(value, property));
            case "visual_fire" -> entity.setVisualFire(bool(value, property));
            case "fire_ticks", "burning" -> entity.setFireTicks(integer(value, property, 0, Integer.MAX_VALUE));
            case "freeze_ticks", "frozen" -> entity.setFreezeTicks(integer(value, property, 0, entity.getMaxFreezeTicks()));
            case "ticks_lived" -> entity.setTicksLived(integer(value, property, 1, Integer.MAX_VALUE));
            case "fall_distance" -> entity.setFallDistance((float) nonNegative(value, property));
            case "portal_cooldown" -> entity.setPortalCooldown(integer(value, property, 0, Integer.MAX_VALUE));
            case "velocity" -> entity.setVelocity(vector(value, property));
            case "location" -> {
                if (!(value instanceof Location location) || location.getWorld() == null || !entity.teleport(location)) {
                    throw new IllegalArgumentException("Entity data location must be a valid world location");
                }
            }
            case "health" -> FlowMutations.setHealth(context, living(entity, property), nonNegative(value, property));
            case "absorption" -> living(entity, property).setAbsorptionAmount(nonNegative(value, property));
            case "ai" -> living(entity, property).setAI(bool(value, property));
            case "collidable" -> living(entity, property).setCollidable(bool(value, property));
            case "can_pickup_items", "pickup_items" -> living(entity, property).setCanPickupItems(bool(value, property));
            case "persistent" -> mob(entity, property).setPersistent(bool(value, property));
            case "remove_when_far_away" -> mob(entity, property).setRemoveWhenFarAway(bool(value, property));
            case "target" -> {
                if (value != null && !(value instanceof LivingEntity)) {
                    throw new IllegalArgumentException("Entity data target must be a living entity");
                }
                mob(entity, property).setTarget((LivingEntity) value);
            }
            case "age" -> ageable(entity, property).setAge(integer(value, property, Integer.MIN_VALUE, Integer.MAX_VALUE));
            case "age_locked" -> ageable(entity, property).setAgeLock(bool(value, property));
            case "baby" -> {
                if (bool(value, property)) {
                    ageable(entity, property).setBaby();
                } else {
                    ageable(entity, property).setAdult();
                }
            }
            case "fuse_ticks" -> setFuseTicks(entity, property, integer(value, property, 0, Integer.MAX_VALUE));
            case "yield" -> explosive(entity, property).setYield((float) nonNegative(value, property));
            case "incendiary" -> explosive(entity, property).setIsIncendiary(bool(value, property));
            case "explosion_radius" -> creeper(entity, property).setExplosionRadius(integer(value, property, 0, 127));
            case "max_fuse_ticks" -> creeper(entity, property).setMaxFuseTicks(integer(value, property, 0, Integer.MAX_VALUE));
            case "powered" -> creeper(entity, property).setPowered(bool(value, property));
            case "ignited" -> creeper(entity, property).setIgnited(bool(value, property));
            case "type", "uuid" -> throw new IllegalArgumentException("Entity data property is read-only: " + property);
            default -> throw unsupported(entity, property);
        }
    }

    private static AttributeInstance attribute(Entity entity, String rawKey) {
        NamespacedKey key = NamespacedKey.fromString(rawKey.contains(":") ? rawKey : "minecraft:" + rawKey);
        Attribute attribute = key != null ? Registry.ATTRIBUTE.get(key) : null;
        if (attribute == null) {
            throw new IllegalArgumentException("Unknown entity attribute: " + rawKey);
        }
        if (!(entity instanceof Attributable attributable)) {
            throw new IllegalArgumentException("Entity does not support attributes: " + entity.getType());
        }
        AttributeInstance instance = attributable.getAttribute(attribute);
        if (instance == null) {
            throw new IllegalArgumentException("Entity does not support attribute: " + key);
        }
        return instance;
    }

    private static LivingEntity living(Entity entity, String property) {
        if (entity instanceof LivingEntity living) {
            return living;
        }
        throw unsupported(entity, property);
    }

    private static Mob mob(Entity entity, String property) {
        if (entity instanceof Mob mob) {
            return mob;
        }
        throw unsupported(entity, property);
    }

    private static Ageable ageable(Entity entity, String property) {
        if (entity instanceof Ageable ageable) {
            return ageable;
        }
        throw unsupported(entity, property);
    }

    private static int fuseTicks(Entity entity, String property) {
        return switch (entity) {
            case TNTPrimed tnt -> tnt.getFuseTicks();
            case Creeper creeper -> creeper.getFuseTicks();
            default -> throw unsupported(entity, property);
        };
    }

    private static void setFuseTicks(Entity entity, String property, int ticks) {
        switch (entity) {
            case TNTPrimed tnt -> tnt.setFuseTicks(ticks);
            case Creeper creeper -> creeper.setFuseTicks(ticks);
            default -> throw unsupported(entity, property);
        }
    }

    private static Explosive explosive(Entity entity, String property) {
        if (entity instanceof Explosive explosive) {
            return explosive;
        }
        throw unsupported(entity, property);
    }

    private static Creeper creeper(Entity entity, String property) {
        if (entity instanceof Creeper creeper) {
            return creeper;
        }
        throw unsupported(entity, property);
    }

    private static Vector vector(Object value, String property) {
        if (value instanceof Vector vector) {
            return vector.clone();
        }
        if (value instanceof Map<?, ?> map) {
            return new Vector(decimal(map.get("x"), property + ".x"), decimal(map.get("y"), property + ".y"), decimal(map.get("z"), property + ".z"));
        }
        throw new IllegalArgumentException("Entity data property requires a vector: " + property);
    }

    private static boolean bool(Object value, String property) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.doubleValue() != 0;
        }
        if (value != null && ("true".equalsIgnoreCase(value.toString()) || "false".equalsIgnoreCase(value.toString()))) {
            return Boolean.parseBoolean(value.toString());
        }
        throw new IllegalArgumentException("Entity data property requires a boolean: " + property);
    }

    private static int integer(Object value, String property, int minimum, int maximum) {
        double number = decimal(value, property);
        if (number != Math.rint(number) || number < minimum || number > maximum) {
            throw new IllegalArgumentException("Entity data property requires a whole number between " + minimum + " and " + maximum + ": " + property);
        }
        return (int) number;
    }

    private static double nonNegative(Object value, String property) {
        double number = decimal(value, property);
        if (number < 0) {
            throw new IllegalArgumentException("Entity data property requires a non-negative number: " + property);
        }
        return number;
    }

    private static double decimal(Object value, String property) {
        double number;
        if (value instanceof Number numeric) {
            number = numeric.doubleValue();
        } else if (value != null) {
            try {
                number = Double.parseDouble(value.toString());
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("Entity data property requires a number: " + property, exception);
            }
        } else {
            throw new IllegalArgumentException("Entity data property requires a value: " + property);
        }
        if (!Double.isFinite(number)) {
            throw new IllegalArgumentException("Entity data property requires a finite number: " + property);
        }
        return number;
    }

    private static String property(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Entity data property is required");
        }
        return value.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
    }

    private static IllegalArgumentException unsupported(Entity entity, String property) {
        return new IllegalArgumentException("Entity data property is unsupported for " + entity.getType() + ": " + property);
    }
}
