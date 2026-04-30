package restudio.resync.flow.handler.generic;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.FluidCollisionMode;
import org.bukkit.block.Block;
import org.bukkit.Material;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import restudio.flow.data.FlowNode;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.NodeHandler;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BiConsumer;

public class AbilityEffectHandler implements NodeHandler {
    private final Map<String, BiConsumer<FlowContext, FlowNode>> operations = new ConcurrentHashMap<>();
    private static final Map<String, Long> MARKS = new ConcurrentHashMap<>();

    public AbilityEffectHandler() {
        operations.put("strike_lightning", (ctx, node) -> {
            Location location = location(ctx, node);
            if (location != null && location.getWorld() != null) {
                location.getWorld().strikeLightning(location);
            }
            ctx.triggerOutput("flow");
        });
        operations.put("fake_lightning", (ctx, node) -> {
            Location location = location(ctx, node);
            if (location != null && location.getWorld() != null) {
                location.getWorld().strikeLightningEffect(location);
            }
            ctx.triggerOutput("flow");
        });
        operations.put("damage_area", (ctx, node) -> {
            Location location = location(ctx, node);
            double radius = number(ctx, node, "radius", 5.0);
            double damage = number(ctx, node, "damage", 4.0);
            String targetFilter = string(ctx, node, "target_filter", "any");
            boolean excludeCaster = bool(ctx, node, "exclude_caster", true);
            if (location != null && location.getWorld() != null) {
                for (Entity entity : location.getWorld().getNearbyEntities(location, radius, radius, radius)) {
                    if (entity instanceof LivingEntity living && acceptsTarget(entity, targetFilter) && (!excludeCaster || entity != ctx.getPlayer())) {
                        living.damage(damage, ctx.getPlayer());
                    }
                }
            }
            ctx.triggerOutput("flow");
        });
        operations.put("heal_area", (ctx, node) -> {
            Location location = location(ctx, node);
            double radius = number(ctx, node, "radius", 5.0);
            double amount = number(ctx, node, "amount", 4.0);
            String targetFilter = string(ctx, node, "target_filter", "any");
            boolean excludeCaster = bool(ctx, node, "exclude_caster", false);
            if (location != null && location.getWorld() != null) {
                for (Entity entity : location.getWorld().getNearbyEntities(location, radius, radius, radius)) {
                    if (entity instanceof LivingEntity living && acceptsTarget(entity, targetFilter) && (!excludeCaster || entity != ctx.getPlayer())) {
                        living.setHealth(Math.min(living.getMaxHealth(), living.getHealth() + amount));
                    }
                }
            }
            ctx.triggerOutput("flow");
        });
        operations.put("knockback_area", (ctx, node) -> {
            Location location = location(ctx, node);
            double radius = number(ctx, node, "radius", 5.0);
            double strength = number(ctx, node, "strength", 1.2);
            double upwardStrength = number(ctx, node, "upward_strength", 0.2);
            String targetFilter = string(ctx, node, "target_filter", "any");
            boolean excludeCaster = bool(ctx, node, "exclude_caster", true);
            if (location != null && location.getWorld() != null) {
                for (Entity entity : location.getWorld().getNearbyEntities(location, radius, radius, radius)) {
                    if (!acceptsTarget(entity, targetFilter) || excludeCaster && entity == ctx.getPlayer()) {
                        continue;
                    }
                    Vector direction = entity.getLocation().toVector().subtract(location.toVector()).normalize().multiply(strength);
                    direction.setY(Math.max(upwardStrength, direction.getY()));
                    entity.setVelocity(direction);
                }
            }
            ctx.triggerOutput("flow");
        });
        operations.put("find_entities_radius", (ctx, node) -> {
            Location location = location(ctx, node);
            double radius = number(ctx, node, "radius", 5.0);
            String targetFilter = string(ctx, node, "target_filter", "any");
            boolean excludeCaster = bool(ctx, node, "exclude_caster", false);
            List<Entity> entities = new ArrayList<>();
            if (location != null && location.getWorld() != null) {
                for (Entity entity : location.getWorld().getNearbyEntities(location, radius, radius, radius)) {
                    if (acceptsTarget(entity, targetFilter) && (!excludeCaster || entity != ctx.getPlayer())) {
                        entities.add(entity);
                    }
                }
            }
            ctx.setOutput(node, "entities", entities);
            ctx.triggerOutput("flow");
        });
        operations.put("raycast", (ctx, node) -> {
            Player player = ctx.getPlayer();
            double distance = number(ctx, node, "distance", 20.0);
            boolean stopOnBlock = bool(ctx, node, "stop_on_block", true);
            boolean stopOnEntity = bool(ctx, node, "stop_on_entity", true);
            String targetFilter = string(ctx, node, "target_filter", "any");
            if (player != null) {
                Location eye = player.getEyeLocation();
                Vector direction = eye.getDirection();
                RayTraceResult entityResult = stopOnEntity
                    ? player.getWorld().rayTraceEntities(eye, direction, distance, 0.25, entity -> entity != player && acceptsTarget(entity, targetFilter))
                    : null;
                RayTraceResult blockResult = stopOnBlock
                    ? player.getWorld().rayTraceBlocks(eye, direction, distance, FluidCollisionMode.NEVER, true)
                    : null;
                RayTraceResult result = nearest(eye, entityResult, blockResult);
                Entity target = result != null ? result.getHitEntity() : null;
                Block block = result != null ? result.getHitBlock() : null;
                Location location = result != null && result.getHitPosition() != null
                    ? result.getHitPosition().toLocation(player.getWorld())
                    : eye.clone().add(direction.multiply(distance));
                ctx.setOutput(node, "target", target);
                ctx.setOutput(node, "block", block);
                ctx.setOutput(node, "location", location);
                ctx.setOutput(node, "hit", result != null);
            }
            ctx.triggerOutput("flow");
        });
        operations.put("particle_burst", (ctx, node) -> {
            Location location = location(ctx, node);
            String particleName = string(ctx, node, "particle", "FLAME");
            int count = integer(ctx, node, "count", 24);
            double spread = number(ctx, node, "spread", 0.6);
            double speed = number(ctx, node, "speed", 0.02);
            if (location != null && location.getWorld() != null) {
                Particle particle = parseParticle(particleName);
                location.getWorld().spawnParticle(particle, location, count, spread, spread, spread, speed);
            }
            ctx.triggerOutput("flow");
        });
        operations.put("play_sound", (ctx, node) -> {
            Location location = location(ctx, node);
            String soundName = ctx.getInputValue(node, "sound", String.class, "ENTITY_EXPERIENCE_ORB_PICKUP");
            float volume = (float) number(ctx, node, "volume", 1.0);
            float pitch = (float) number(ctx, node, "pitch", 1.0);
            if (location != null && location.getWorld() != null) {
                location.getWorld().playSound(location, parseSound(soundName), volume, pitch);
            }
            ctx.triggerOutput("flow");
        });
        operations.put("potion_effect", (ctx, node) -> {
            Entity target = target(ctx, node);
            String effectName = ctx.getInputValue(node, "effect", String.class, "SPEED");
            int duration = integer(ctx, node, "duration_ticks", 100);
            int amplifier = integer(ctx, node, "amplifier", 0);
            PotionEffectType type = PotionEffectType.getByName(effectName.toUpperCase(Locale.ROOT));
            if (target instanceof LivingEntity living && type != null) {
                living.addPotionEffect(new PotionEffect(type, duration, amplifier));
            }
            ctx.triggerOutput("flow");
        });
        operations.put("launch_projectile", (ctx, node) -> {
            Player player = ctx.getPlayer();
            double speed = number(ctx, node, "speed", 2.0);
            if (player != null) {
                Arrow arrow = player.launchProjectile(Arrow.class);
                arrow.setVelocity(player.getEyeLocation().getDirection().multiply(speed));
                ctx.setOutput(node, "projectile", arrow);
            }
            ctx.triggerOutput("flow");
        });
        operations.put("pull_entities", (ctx, node) -> moveArea(ctx, node, -1));
        operations.put("push_entities", (ctx, node) -> moveArea(ctx, node, 1));
        operations.put("ignite_target", (ctx, node) -> {
            Entity target = target(ctx, node);
            if (target != null) {
                target.setFireTicks(integer(ctx, node, "ticks", 100));
            }
            ctx.triggerOutput("flow");
        });
        operations.put("freeze_target", (ctx, node) -> {
            Entity target = target(ctx, node);
            if (target != null) {
                target.setFreezeTicks(integer(ctx, node, "ticks", 100));
            }
            ctx.triggerOutput("flow");
        });
        operations.put("set_velocity", (ctx, node) -> {
            Entity target = target(ctx, node);
            if (target != null) {
                target.setVelocity(new Vector(number(ctx, node, "x", 0), number(ctx, node, "y", 0), number(ctx, node, "z", 0)));
            }
            ctx.triggerOutput("flow");
        });
        operations.put("nearest_entity", (ctx, node) -> {
            List<Entity> entities = entitiesAround(ctx, node);
            Location location = location(ctx, node);
            Entity nearest = location == null ? null : entities.stream()
                .min(Comparator.comparingDouble(entity -> entity.getLocation().distanceSquared(location)))
                .orElse(null);
            ctx.setOutput(node, "entity", nearest);
            ctx.setOutput(node, "found", nearest != null);
            ctx.triggerOutput("flow");
        });
        operations.put("random_entity", (ctx, node) -> {
            List<Entity> entities = entitiesAround(ctx, node);
            Entity entity = entities.isEmpty() ? null : entities.get(ThreadLocalRandom.current().nextInt(entities.size()));
            ctx.setOutput(node, "entity", entity);
            ctx.setOutput(node, "found", entity != null);
            ctx.triggerOutput("flow");
        });
        operations.put("cone_entities", (ctx, node) -> {
            Player player = ctx.getPlayer();
            Location origin = location(ctx, node);
            Vector direction = direction(ctx, node);
            double range = number(ctx, node, "range", 8.0);
            double angle = Math.max(0.0, Math.min(180.0, number(ctx, node, "angle", 60.0)));
            String targetFilter = string(ctx, node, "target_filter", "living_entity");
            boolean excludeCaster = bool(ctx, node, "exclude_caster", true);
            List<Entity> entities = new ArrayList<>();
            if (origin != null && origin.getWorld() != null) {
                double cos = Math.cos(Math.toRadians(angle * 0.5));
                for (Entity entity : origin.getWorld().getNearbyEntities(origin, range, range, range)) {
                    if (!acceptsTarget(entity, targetFilter) || excludeCaster && entity == player) {
                        continue;
                    }
                    Vector offset = entity.getLocation().toVector().subtract(origin.toVector());
                    if (offset.lengthSquared() > range * range || offset.lengthSquared() == 0.0) {
                        continue;
                    }
                    if (offset.normalize().dot(direction.clone().normalize()) >= cos) {
                        entities.add(entity);
                    }
                }
            }
            ctx.setOutput(node, "entities", entities);
            ctx.triggerOutput("flow");
        });
        operations.put("line_entities", (ctx, node) -> {
            Player player = ctx.getPlayer();
            Location origin = location(ctx, node);
            Vector direction = direction(ctx, node).normalize();
            double range = number(ctx, node, "range", 12.0);
            double width = number(ctx, node, "width", 1.0);
            String targetFilter = string(ctx, node, "target_filter", "living_entity");
            boolean excludeCaster = bool(ctx, node, "exclude_caster", true);
            List<Entity> entities = new ArrayList<>();
            if (origin != null && origin.getWorld() != null) {
                for (Entity entity : origin.getWorld().getNearbyEntities(origin, range, range, range)) {
                    if (!acceptsTarget(entity, targetFilter) || excludeCaster && entity == player) {
                        continue;
                    }
                    Vector offset = entity.getLocation().toVector().subtract(origin.toVector());
                    double along = offset.dot(direction);
                    if (along < 0.0 || along > range) {
                        continue;
                    }
                    Vector closest = direction.clone().multiply(along);
                    if (offset.subtract(closest).length() <= width) {
                        entities.add(entity);
                    }
                }
            }
            ctx.setOutput(node, "entities", entities);
            ctx.triggerOutput("flow");
        });
        operations.put("dash", (ctx, node) -> {
            Entity target = target(ctx, node);
            if (target != null) {
                double strength = number(ctx, node, "strength", 1.4);
                double upwardStrength = number(ctx, node, "upward_strength", 0.15);
                Vector vector = direction(ctx, node).normalize().multiply(strength);
                vector.setY(Math.max(vector.getY(), upwardStrength));
                target.setVelocity(vector);
            }
            ctx.triggerOutput("flow");
        });
        operations.put("teleport_caster", (ctx, node) -> {
            Player player = ctx.getPlayer();
            Location location = location(ctx, node);
            boolean keepDirection = bool(ctx, node, "keep_direction", true);
            if (player != null && location != null) {
                Location target = location.clone();
                if (keepDirection) {
                    target.setYaw(player.getLocation().getYaw());
                    target.setPitch(player.getLocation().getPitch());
                }
                player.teleport(target);
            }
            ctx.triggerOutput("flow");
        });
        operations.put("teleport_target", (ctx, node) -> {
            Entity target = target(ctx, node);
            Location location = location(ctx, node);
            if (target != null && location != null) {
                target.teleport(location);
            }
            ctx.triggerOutput("flow");
        });
        operations.put("beam", (ctx, node) -> {
            Location start = ctx.getInputValue(node, "start", Location.class, null);
            Location end = ctx.getInputValue(node, "end", Location.class, null);
            if (start == null && ctx.getPlayer() != null) {
                start = ctx.getPlayer().getEyeLocation();
            }
            if (end == null) {
                end = location(ctx, node);
            }
            String particleName = string(ctx, node, "particle", "ELECTRIC_SPARK");
            int steps = Math.max(1, integer(ctx, node, "steps", 24));
            if (start != null && end != null && start.getWorld() != null) {
                Particle particle = parseParticle(particleName);
                Vector delta = end.toVector().subtract(start.toVector()).multiply(1.0 / steps);
                Location point = start.clone();
                for (int i = 0; i <= steps; i++) {
                    start.getWorld().spawnParticle(particle, point, 1, 0, 0, 0, 0);
                    point.add(delta);
                }
            }
            ctx.triggerOutput("flow");
        });
        operations.put("temporary_block", (ctx, node) -> {
            Location location = location(ctx, node);
            int duration = integer(ctx, node, "duration_ticks", 60);
            String materialName = string(ctx, node, "material", "ICE");
            if (location != null && location.getWorld() != null) {
                Block block = location.getBlock();
                Material previousType = block.getType();
                Material material = parseMaterial(materialName, Material.ICE);
                block.setType(material, false);
                ctx.runLater(() -> {
                    if (block.getType() == material) {
                        block.setType(previousType, false);
                    }
                }, duration);
            }
            ctx.triggerOutput("flow");
        });
        operations.put("mark_target", (ctx, node) -> {
            Entity target = target(ctx, node);
            String key = string(ctx, node, "key", "mark");
            int duration = integer(ctx, node, "duration_ticks", 100);
            if (target != null) {
                MARKS.put(markKey(target.getUniqueId(), key), System.currentTimeMillis() + duration * 50L);
            }
            ctx.triggerOutput("flow");
        });
        operations.put("has_mark", (ctx, node) -> {
            Entity target = target(ctx, node);
            String key = string(ctx, node, "key", "mark");
            boolean active = target != null && isMarked(target.getUniqueId(), key);
            ctx.setOutput(node, "active", active);
            ctx.triggerOutput(active ? "true" : "false");
        });
        operations.put("remove_mark", (ctx, node) -> {
            Entity target = target(ctx, node);
            String key = string(ctx, node, "key", "mark");
            if (target != null) {
                MARKS.remove(markKey(target.getUniqueId(), key));
            }
            ctx.triggerOutput("flow");
        });
    }

    public void registerTo(HandlerRegistry registry) {
        registry.register("AbilityEffectHandler", this);
    }

    @Override
    public void execute(FlowContext ctx, FlowNode node) {
        String operation = node.getHandlerConfig().getString("operation");
        BiConsumer<FlowContext, FlowNode> op = operation != null ? operations.get(operation) : null;
        if (op != null) {
            op.accept(ctx, node);
        } else {
            ctx.triggerOutput("flow");
        }
    }

    private void moveArea(FlowContext ctx, FlowNode node, int direction) {
        Location location = location(ctx, node);
        double radius = number(ctx, node, "radius", 5.0);
        double strength = number(ctx, node, "strength", 1.0);
        String targetFilter = string(ctx, node, "target_filter", "any");
        boolean excludeCaster = bool(ctx, node, "exclude_caster", true);
        if (location != null && location.getWorld() != null) {
            for (Entity entity : location.getWorld().getNearbyEntities(location, radius, radius, radius)) {
                if (!acceptsTarget(entity, targetFilter) || excludeCaster && entity == ctx.getPlayer()) {
                    continue;
                }
                Vector vector = entity.getLocation().toVector().subtract(location.toVector()).normalize().multiply(strength * direction);
                entity.setVelocity(vector);
            }
        }
        ctx.triggerOutput("flow");
    }

    private List<Entity> entitiesAround(FlowContext ctx, FlowNode node) {
        Location location = location(ctx, node);
        double radius = number(ctx, node, "radius", 8.0);
        String targetFilter = string(ctx, node, "target_filter", "living_entity");
        boolean excludeCaster = bool(ctx, node, "exclude_caster", true);
        List<Entity> entities = new ArrayList<>();
        if (location == null || location.getWorld() == null) {
            return entities;
        }
        for (Entity entity : location.getWorld().getNearbyEntities(location, radius, radius, radius)) {
            if (acceptsTarget(entity, targetFilter) && (!excludeCaster || entity != ctx.getPlayer())) {
                entities.add(entity);
            }
        }
        return entities;
    }

    private Location location(FlowContext ctx, FlowNode node) {
        Location location = ctx.getInputValue(node, "location", Location.class, null);
        if (location != null) {
            return location;
        }
        Object eventLocation = ctx.getRuntime().getEventVariables().get("event.location");
        if (eventLocation instanceof Location loc) {
            return loc;
        }
        return ctx.getPlayer() != null ? ctx.getPlayer().getLocation() : null;
    }

    private Entity target(FlowContext ctx, FlowNode node) {
        Entity target = ctx.getInputValue(node, "target", Entity.class, null);
        if (target != null) {
            return target;
        }
        Object eventTarget = ctx.getRuntime().getEventVariables().get("event.target");
        return eventTarget instanceof Entity entity ? entity : ctx.getPlayer();
    }

    private Vector direction(FlowContext ctx, FlowNode node) {
        Vector direction = ctx.getInputValue(node, "direction", Vector.class, null);
        if (direction != null && direction.lengthSquared() > 0.0) {
            return direction;
        }
        Entity target = target(ctx, node);
        if (target != null) {
            return target.getLocation().getDirection();
        }
        return new Vector(0, 0, 1);
    }

    private RayTraceResult nearest(Location source, RayTraceResult first, RayTraceResult second) {
        if (first == null) {
            return second;
        }
        if (second == null) {
            return first;
        }
        double firstDistance = source.toVector().distanceSquared(first.getHitPosition());
        double secondDistance = source.toVector().distanceSquared(second.getHitPosition());
        return firstDistance <= secondDistance ? first : second;
    }

    private boolean acceptsTarget(Entity entity, String filter) {
        if (entity == null) {
            return false;
        }
        String normalized = filter == null ? "any" : filter.toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "player" -> entity instanceof Player;
            case "living_entity", "living entity", "living" -> entity instanceof LivingEntity;
            case "hostile" -> entity instanceof Monster;
            case "passive" -> entity instanceof Animals;
            default -> true;
        };
    }

    private boolean bool(FlowContext ctx, FlowNode node, String pin, boolean fallback) {
        Boolean value = ctx.getInputValue(node, pin, Boolean.class, fallback);
        return value != null ? value : fallback;
    }

    private String string(FlowContext ctx, FlowNode node, String pin, String fallback) {
        String value = ctx.getInputValue(node, pin, String.class, fallback);
        return value != null ? value : fallback;
    }

    private double number(FlowContext ctx, FlowNode node, String pin, double fallback) {
        Double value = ctx.getInputValue(node, pin, Double.class, fallback);
        return value != null ? value : fallback;
    }

    private int integer(FlowContext ctx, FlowNode node, String pin, int fallback) {
        Integer value = ctx.getInputValue(node, pin, Integer.class, fallback);
        return value != null ? value : fallback;
    }

    private Particle parseParticle(String name) {
        try {
            return Particle.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (Exception ignored) {
            return Particle.FLAME;
        }
    }

    private Sound parseSound(String name) {
        try {
            return Sound.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (Exception ignored) {
            return Sound.ENTITY_EXPERIENCE_ORB_PICKUP;
        }
    }

    private Material parseMaterial(String name, Material fallback) {
        try {
            String normalized = name != null && name.contains(":") ? name.substring(name.indexOf(':') + 1) : name;
            return Material.valueOf(normalized.toUpperCase(Locale.ROOT));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private String markKey(UUID uuid, String key) {
        return uuid + ":" + key.toLowerCase(Locale.ROOT);
    }

    private boolean isMarked(UUID uuid, String key) {
        String markKey = markKey(uuid, key);
        Long expiresAt = MARKS.get(markKey);
        if (expiresAt == null) {
            return false;
        }
        if (expiresAt < System.currentTimeMillis()) {
            MARKS.remove(markKey);
            return false;
        }
        return true;
    }
}
