package restudio.resync.flow.handler.generic;

import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.FluidCollisionMode;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.Material;
import org.bukkit.entity.Animals;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Egg;
import org.bukkit.entity.Fireball;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.SmallFireball;
import org.bukkit.entity.Snowball;
import org.bukkit.entity.Trident;
import org.bukkit.event.Cancellable;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import restudio.flow.data.CustomContentDefinition;
import restudio.flow.data.FlowNode;
import restudio.resync.customcontent.CustomContentAccess;
import restudio.resync.customcontent.CustomContentListener;
import restudio.resync.customcontent.CustomContentService;
import restudio.resync.customcontent.CustomContentStorage;
import restudio.resync.flow.FlowMutations;
import restudio.resync.ReSync;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.NodeHandler;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class AbilityEffectHandler implements NodeHandler {
    private final Map<String, BiConsumer<FlowContext, FlowNode>> operations = new ConcurrentHashMap<>();
    private final ParticleHandler particleHandler = new ParticleHandler();
    private static final Map<String, Long> MARKS = new ConcurrentHashMap<>();
    private static final Map<UUID, ItemStack> DISARMED_ITEMS = new ConcurrentHashMap<>();
    private static final Map<String, Long> COOLDOWNS = new ConcurrentHashMap<>();

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
            Location location = areaCenter(ctx, node);
            double radius = number(ctx, node, "radius", 5.0);
            double damage = number(ctx, node, "damage", 4.0);
            String targetFilter = string(ctx, node, "target_filter", "any");
            boolean excludeCaster = bool(ctx, node, "exclude_caster", true);
            if (location != null && location.getWorld() != null) {
                for (Entity entity : location.getWorld().getNearbyEntities(location, radius, radius, radius)) {
                    if (entity instanceof LivingEntity living && acceptsTarget(entity, targetFilter) && (!excludeCaster || entity != ctx.getPlayer())) {
                        damage(living, damage, ctx.getPlayer());
                    }
                }
            }
            ctx.triggerOutput("flow");
        });
        operations.put("heal_area", (ctx, node) -> {
            Location location = areaCenter(ctx, node);
            double radius = number(ctx, node, "radius", 5.0);
            double amount = number(ctx, node, "amount", 4.0);
            String targetFilter = string(ctx, node, "target_filter", "any");
            boolean excludeCaster = bool(ctx, node, "exclude_caster", false);
            if (location != null && location.getWorld() != null) {
                for (Entity entity : location.getWorld().getNearbyEntities(location, radius, radius, radius)) {
                    if (entity instanceof LivingEntity living && acceptsTarget(entity, targetFilter) && (!excludeCaster || entity != ctx.getPlayer())) {
                        FlowMutations.heal(ctx, living, amount);
                    }
                }
            }
            ctx.triggerOutput("flow");
        });
        operations.put("knockback_area", (ctx, node) -> {
            Location location = areaCenter(ctx, node);
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
                    Vector direction = knockbackDirection(ctx, entity, location).multiply(strength);
                    direction.setY(Math.max(upwardStrength, direction.getY()));
                    FlowMutations.applyVelocity(ctx, entity, direction);
                }
            }
            ctx.triggerOutput("flow");
        });
        operations.put("find_entities_radius", (ctx, node) -> {
            Location location = areaCenter(ctx, node);
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
            String hitMode = string(ctx, node, "hit_mode", "any").toLowerCase(Locale.ROOT);
            String targetFilter = string(ctx, node, "target_filter", "any");
            String blockFilter = string(ctx, node, "block_filter", "any");
            if (player != null) {
                Location eye = player.getEyeLocation();
                Vector direction = eye.getDirection();
                RayTraceResult entityResult = stopOnEntity && !"block".equals(hitMode)
                    ? player.getWorld().rayTraceEntities(eye, direction, distance, 0.25, entity -> entity != player && acceptsTarget(entity, targetFilter))
                    : null;
                RayTraceResult blockResult = stopOnBlock && !"entity".equals(hitMode)
                    ? player.getWorld().rayTraceBlocks(eye, direction, distance, FluidCollisionMode.NEVER, true)
                    : null;
                if (blockResult != null && !acceptsBlock(blockResult.getHitBlock(), blockFilter)) {
                    blockResult = null;
                }
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
            executeParticle(ctx, node, "burst", Map.of());
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
            String projectileType = string(ctx, node, "projectile_type", "ARROW");
            boolean gravity = bool(ctx, node, "gravity", true);
            int fireTicks = integer(ctx, node, "fire_ticks", 0);
            double damage = number(ctx, node, "damage", 0.0);
            int pierce = integer(ctx, node, "pierce", 0);
            String pickupMode = string(ctx, node, "pickup_mode", "allowed");
            String markKey = string(ctx, node, "mark_key", "");
            if (player != null) {
                Projectile projectile = launchProjectile(player, projectileType);
                projectile.setVelocity(player.getEyeLocation().getDirection().multiply(speed));
                projectile.setGravity(gravity);
                projectile.setFireTicks(Math.max(0, fireTicks));
                if (projectile instanceof AbstractArrow arrow) {
                    if (damage > 0.0) {
                        arrow.setDamage(damage);
                    }
                    arrow.setPierceLevel(Math.max(0, pierce));
                    arrow.setPickupStatus(pickupStatus(pickupMode));
                }
                if (!markKey.isBlank()) {
                    projectile.addScoreboardTag("resync:" + markKey);
                }
                if (projectile instanceof Fireball fireball) {
                    fireball.setDirection(player.getEyeLocation().getDirection().multiply(speed));
                }
                ctx.setOutput(node, "projectile", projectile);
            }
            ctx.triggerOutput("flow");
        });
        operations.put("damage_target", (ctx, node) -> {
            Entity target = target(ctx, node);
            double amount = number(ctx, node, "amount", 4.0);
            boolean useSource = bool(ctx, node, "use_source", true);
            if (target instanceof LivingEntity living) {
                if (useSource && ctx.getPlayer() != null) {
                    damage(living, amount, ctx.getPlayer());
                } else {
                    damage(living, amount, null);
                }
            }
            ctx.triggerOutput("flow");
        });
        operations.put("heal_target", (ctx, node) -> {
            Entity target = target(ctx, node);
            double amount = number(ctx, node, "amount", 4.0);
            if (target instanceof LivingEntity living) {
                FlowMutations.heal(ctx, living, amount);
            }
            ctx.triggerOutput("flow");
        });
        operations.put("set_health", (ctx, node) -> {
            Entity target = target(ctx, node);
            double health = number(ctx, node, "health", 20.0);
            if (target instanceof LivingEntity living) {
                FlowMutations.setHealth(ctx, living, health);
            }
            ctx.triggerOutput("flow");
        });
        operations.put("cancel_damage", (ctx, node) -> {
            boolean cancelled = bool(ctx, node, "cancelled", true);
            if (ctx.getEvent() instanceof Cancellable cancellable) {
                cancellable.setCancelled(cancelled);
            }
            if (ctx.getEvent() instanceof EntityDamageEvent damageEvent && cancelled) {
                damageEvent.setDamage(0.0);
            }
            ctx.triggerOutput("flow");
        });
        operations.put("shield_target", (ctx, node) -> {
            Entity target = target(ctx, node);
            double amount = number(ctx, node, "amount", 4.0);
            int duration = integer(ctx, node, "duration_ticks", 100);
            if (target instanceof LivingEntity living) {
                FlowMutations.shield(ctx, living, amount, duration);
            }
            ctx.triggerOutput("flow");
        });
        operations.put("reflect_damage", (ctx, node) -> {
            double percent = number(ctx, node, "percent", 100.0);
            if (ctx.getEvent() instanceof EntityDamageByEntityEvent damageEvent && damageEvent.getDamager() instanceof LivingEntity damager) {
                damage(damager, damageEvent.getDamage() * Math.max(0.0, percent) / 100.0, damageEvent.getEntity());
            }
            ctx.triggerOutput("flow");
        });
        operations.put("launch_target", (ctx, node) -> {
            Entity target = target(ctx, node);
            if (target != null) {
                double strength = number(ctx, node, "strength", 1.0);
                double upwardStrength = number(ctx, node, "upward_strength", 0.5);
                Vector vector = normalizedDirection(ctx, node).multiply(strength);
                vector.setY(vector.getY() + upwardStrength);
                FlowMutations.applyVelocity(ctx, target, vector);
            }
            ctx.triggerOutput("flow");
        });
        operations.put("pull_target", (ctx, node) -> {
            Entity target = target(ctx, node);
            Location location = location(ctx, node);
            double strength = number(ctx, node, "strength", 1.0);
            if (target != null && location != null) {
                Vector vector = location.toVector().subtract(target.getLocation().toVector());
                if (vector.lengthSquared() > 0.0001) {
                    FlowMutations.applyVelocity(ctx, target, vector.normalize().multiply(strength));
                }
            }
            ctx.triggerOutput("flow");
        });
        operations.put("leap_to_location", (ctx, node) -> {
            Entity target = target(ctx, node);
            Location location = location(ctx, node);
            if (target != null && location != null) {
                LeapResult result = leapToLocation(ctx, node, target, location);
                ctx.setOutput(node, "destination", result != null ? result.destination() : null);
                ctx.setOutput(node, "blocked", result != null && result.blocked());
            }
            ctx.triggerOutput("flow");
        });
        operations.put("stun_target", (ctx, node) -> {
            Entity target = target(ctx, node);
            int duration = integer(ctx, node, "duration_ticks", 60);
            if (target instanceof LivingEntity living) {
                addEffect(living, "SLOW", duration, 10);
                addEffect(living, "JUMP", duration, 250);
            }
            ctx.triggerOutput("flow");
        });
        operations.put("root_target", (ctx, node) -> {
            Entity target = target(ctx, node);
            int duration = integer(ctx, node, "duration_ticks", 60);
            if (target instanceof LivingEntity living) {
                addEffect(living, "SLOW", duration, 10);
                addEffect(living, "JUMP", duration, 250);
            }
            ctx.triggerOutput("flow");
        });
        operations.put("silence_target", (ctx, node) -> {
            Entity target = target(ctx, node);
            int duration = integer(ctx, node, "duration_ticks", 100);
            if (target != null) {
                MARKS.put(markKey(target.getUniqueId(), "silenced"), System.currentTimeMillis() + duration * 50L);
            }
            ctx.triggerOutput("flow");
        });
        operations.put("disarm_target", (ctx, node) -> {
            Entity target = target(ctx, node);
            int duration = integer(ctx, node, "duration_ticks", 60);
            if (target instanceof Player player) {
                PlayerInventory inventory = player.getInventory();
                ItemStack item = inventory.getItemInMainHand();
                if (item != null && !item.getType().isAir()) {
                    DISARMED_ITEMS.put(player.getUniqueId(), item.clone());
                    inventory.setItemInMainHand(new ItemStack(Material.AIR));
                    ctx.runLater(() -> {
                        ItemStack stored = DISARMED_ITEMS.remove(player.getUniqueId());
                        if (stored != null && player.isOnline()) {
                            ItemStack current = inventory.getItemInMainHand();
                            if (current == null || current.getType().isAir()) {
                                inventory.setItemInMainHand(stored);
                            } else {
                                inventory.addItem(stored);
                            }
                        }
                    }, Math.max(1, duration));
                }
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
                FlowMutations.applyVelocity(ctx, target, new Vector(number(ctx, node, "x", 0), number(ctx, node, "y", 0), number(ctx, node, "z", 0)));
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
            Vector direction = normalizedDirection(ctx, node);
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
                Vector vector = normalizedDirection(ctx, node).multiply(strength);
                vector.setY(Math.max(vector.getY(), upwardStrength));
                FlowMutations.applyVelocity(ctx, target, vector);
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
        operations.put("find_blocks", (ctx, node) -> {
            Location center = location(ctx, node);
            int radius = integer(ctx, node, "radius", 5);
            String materialName = string(ctx, node, "material", "any");
            List<Block> blocks = new ArrayList<>();
            if (center != null && center.getWorld() != null) {
                Material material = parseMaterial(materialName, null);
                forEachBlock(center, radius, "cube", block -> {
                    if (material == null || block.getType() == material) {
                        blocks.add(block);
                    }
                });
            }
            ctx.setOutput(node, "blocks", blocks);
            ctx.setOutput(node, "count", blocks.size());
            ctx.triggerOutput("flow");
        });
        operations.put("break_blocks", (ctx, node) -> {
            Location center = location(ctx, node);
            int radius = integer(ctx, node, "radius", 3);
            String materialName = string(ctx, node, "material", "any");
            boolean drops = bool(ctx, node, "drops", true);
            int maxBlocks = integer(ctx, node, "max_blocks", 128);
            int[] count = {0};
            if (center != null && center.getWorld() != null) {
                Material material = parseMaterial(materialName, null);
                forEachBlock(center, radius, "sphere", block -> {
                    if (count[0] >= maxBlocks || material != null && block.getType() != material || block.getType().isAir()) {
                        return;
                    }
                    if (drops) {
                        block.breakNaturally();
                    } else {
                        block.setType(Material.AIR, false);
                    }
                    count[0]++;
                });
            }
            ctx.setOutput(node, "count", count[0]);
            ctx.triggerOutput("flow");
        });
        operations.put("replace_blocks", (ctx, node) -> {
            Location center = location(ctx, node);
            int radius = integer(ctx, node, "radius", 3);
            Material from = parseMaterial(string(ctx, node, "from", "any"), null);
            Material to = parseMaterial(string(ctx, node, "to", "STONE"), Material.STONE);
            int maxBlocks = integer(ctx, node, "max_blocks", 128);
            int[] count = {0};
            if (center != null && center.getWorld() != null && to != null) {
                forEachBlock(center, radius, "sphere", block -> {
                    if (count[0] >= maxBlocks || from != null && block.getType() != from) {
                        return;
                    }
                    block.setType(to, false);
                    count[0]++;
                });
            }
            ctx.setOutput(node, "count", count[0]);
            ctx.triggerOutput("flow");
        });
        operations.put("place_shape", (ctx, node) -> {
            Location center = location(ctx, node);
            int radius = integer(ctx, node, "radius", 3);
            String shape = string(ctx, node, "shape", "sphere");
            Material material = parseMaterial(string(ctx, node, "material", "STONE"), Material.STONE);
            int maxBlocks = integer(ctx, node, "max_blocks", 128);
            boolean replaceAirOnly = bool(ctx, node, "air_only", true);
            int[] count = {0};
            if (center != null && center.getWorld() != null && material != null) {
                forEachBlock(center, radius, shape, block -> {
                    if (count[0] >= maxBlocks || replaceAirOnly && !block.getType().isAir()) {
                        return;
                    }
                    block.setType(material, false);
                    count[0]++;
                });
            }
            ctx.setOutput(node, "count", count[0]);
            ctx.triggerOutput("flow");
        });
        operations.put("extinguish_area", (ctx, node) -> {
            Location center = location(ctx, node);
            int radius = integer(ctx, node, "radius", 5);
            int[] count = {0};
            if (center != null && center.getWorld() != null) {
                forEachBlock(center, radius, "sphere", block -> {
                    if (block.getType() == Material.FIRE || block.getType() == Material.SOUL_FIRE) {
                        block.setType(Material.AIR, false);
                        count[0]++;
                    }
                });
            }
            ctx.setOutput(node, "count", count[0]);
            ctx.triggerOutput("flow");
        });
        operations.put("grow_block", (ctx, node) -> {
            Location location = location(ctx, node);
            int attempts = integer(ctx, node, "attempts", 1);
            boolean success = false;
            if (location != null && location.getWorld() != null) {
                for (int i = 0; i < Math.max(1, attempts); i++) {
                    success |= location.getBlock().applyBoneMeal(BlockFace.UP);
                }
            }
            ctx.setOutput(node, "success", success);
            ctx.triggerOutput("flow");
        });
        operations.put("particle_shape", (ctx, node) -> {
            String mode = string(ctx, node, "mode", "ring");
            executeParticle(ctx, node, "orbit".equalsIgnoreCase(mode) ? "circle" : mode, Map.of());
        });
        operations.put("filter_entities", (ctx, node) -> {
            List<Entity> entities = entityList(ctx, node);
            String targetFilter = string(ctx, node, "target_filter", "any");
            boolean excludeCaster = bool(ctx, node, "exclude_caster", false);
            List<Entity> filtered = entities.stream()
                .filter(entity -> acceptsTarget(entity, targetFilter))
                .filter(entity -> !excludeCaster || entity != ctx.getPlayer())
                .toList();
            ctx.setOutput(node, "entities", filtered);
            ctx.setOutput(node, "count", filtered.size());
            ctx.triggerOutput("flow");
        });
        operations.put("sort_entities", (ctx, node) -> {
            List<Entity> entities = new ArrayList<>(entityList(ctx, node));
            Location origin = location(ctx, node);
            String mode = string(ctx, node, "mode", "nearest");
            if (origin != null) {
                entities.sort(Comparator.comparingDouble(entity -> entity.getLocation().distanceSquared(origin)));
                if ("farthest".equalsIgnoreCase(mode)) {
                    Collections.reverse(entities);
                }
            }
            ctx.setOutput(node, "entities", entities);
            ctx.triggerOutput("flow");
        });
        operations.put("limit_entities", (ctx, node) -> {
            List<Entity> entities = entityList(ctx, node);
            int limit = Math.max(0, integer(ctx, node, "limit", 1));
            List<Entity> limited = entities.stream().limit(limit).toList();
            ctx.setOutput(node, "entities", limited);
            ctx.setOutput(node, "count", limited.size());
            ctx.triggerOutput("flow");
        });
        operations.put("is_holding_content", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, ctx.getPlayer());
            String contentId = string(ctx, node, "content_id", "");
            String hand = string(ctx, node, "hand", "any");
            ItemStack item = matchingHeldItem(player, contentId, hand);
            boolean matches = item != null;
            ctx.setOutput(node, "item", item);
            ctx.setOutput(node, "matches", matches);
            ctx.triggerOutput(matches ? "true" : "false");
        });
        operations.put("is_wearing_content", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, ctx.getPlayer());
            String contentId = string(ctx, node, "content_id", "");
            String slot = string(ctx, node, "slot", "any");
            ItemStack item = matchingArmorItem(player, contentId, slot);
            boolean matches = item != null;
            ctx.setOutput(node, "item", item);
            ctx.setOutput(node, "matches", matches);
            ctx.triggerOutput(matches ? "true" : "false");
        });
        operations.put("has_content_set", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, ctx.getPlayer());
            String prefix = string(ctx, node, "content_prefix", "");
            boolean matches = hasContentSet(player, prefix);
            ctx.setOutput(node, "matches", matches);
            ctx.triggerOutput(matches ? "true" : "false");
        });
        operations.put("custom_block_at", (ctx, node) -> {
            Location location = location(ctx, node);
            CustomContentService service = CustomContentAccess.getService();
            String contentId = service != null ? service.identifyBlock(location) : null;
            CustomContentDefinition definition = definition(contentId);
            ctx.setOutput(node, "content_id", contentId);
            ctx.setOutput(node, "content_type", definition != null ? definition.getType() : "");
            ctx.setOutput(node, "exists", contentId != null);
            ctx.triggerOutput(contentId != null ? "true" : "false");
        });
        operations.put("trigger_content_ability", (ctx, node) -> {
            CustomContentService service = CustomContentAccess.getService();
            String contentId = string(ctx, node, "content_id", "");
            String trigger = string(ctx, node, "trigger", "item.use");
            Player player = ctx.getInputValue(node, "player", Player.class, ctx.getPlayer());
            if (service != null && !contentId.isBlank() && !trigger.isBlank()) {
                Map<String, Object> vars = new HashMap<>(ctx.getRuntime().getEventVariables());
                vars.put("event.location", location(ctx, node));
                vars.put("event.target", target(ctx, node));
                service.dispatch(contentId, trigger, player, ctx.getEvent(), vars);
                ctx.setOutput(node, "success", true);
            } else {
                ctx.setOutput(node, "success", false);
            }
            ctx.triggerOutput("flow");
        });
        operations.put("cooldown_remaining", (ctx, node) -> {
            String key = cooldownKey(ctx, node);
            long remaining = Math.max(0L, COOLDOWNS.getOrDefault(key, 0L) - System.currentTimeMillis());
            int ticks = (int) Math.ceil(remaining / 50.0);
            ctx.setOutput(node, "ticks", ticks);
            ctx.setOutput(node, "is_ready", ticks <= 0);
            ctx.triggerOutput(ticks <= 0 ? "ready" : "cooldown");
        });
        operations.put("set_cooldown", (ctx, node) -> {
            String key = cooldownKey(ctx, node);
            int ticks = integer(ctx, node, "ticks", 100);
            COOLDOWNS.put(key, System.currentTimeMillis() + Math.max(0, ticks) * 50L);
            ctx.triggerOutput("flow");
        });
        operations.put("get_charge", (ctx, node) -> {
            ItemStack item = item(ctx, node);
            String key = string(ctx, node, "key", "charge");
            double value = getCharge(item, key);
            ctx.setOutput(node, "value", value);
            ctx.triggerOutput("flow");
        });
        operations.put("set_charge", (ctx, node) -> {
            ItemStack item = item(ctx, node);
            String key = string(ctx, node, "key", "charge");
            double value = number(ctx, node, "value", 0.0);
            setCharge(item, key, value);
            ctx.setOutput(node, "item", item);
            ctx.setOutput(node, "value", value);
            ctx.triggerOutput("flow");
        });
        operations.put("add_charge", (ctx, node) -> {
            ItemStack item = item(ctx, node);
            String key = string(ctx, node, "key", "charge");
            double value = getCharge(item, key) + number(ctx, node, "amount", 1.0);
            setCharge(item, key, value);
            ctx.setOutput(node, "item", item);
            ctx.setOutput(node, "value", value);
            ctx.triggerOutput("flow");
        });
        operations.put("consume_charge", (ctx, node) -> {
            ItemStack item = item(ctx, node);
            String key = string(ctx, node, "key", "charge");
            double amount = number(ctx, node, "amount", 1.0);
            double value = getCharge(item, key);
            boolean success = value >= amount;
            if (success) {
                value -= amount;
                setCharge(item, key, value);
            }
            ctx.setOutput(node, "item", item);
            ctx.setOutput(node, "value", value);
            ctx.setOutput(node, "succeeded", success);
            ctx.triggerOutput(success ? "success" : "fail");
        });
        operations.put("entity_query", (ctx, node) -> {
            if (!cooldownReady(ctx, node, "entity_query")) {
                ctx.triggerOutput("cooldown");
                return;
            }
            List<Entity> entities = queryEntities(ctx, node);
            Entity entity = selectEntity(ctx, node, entities);
            ctx.setOutput(node, "entities", entities);
            ctx.setOutput(node, "entity", entity);
            ctx.setOutput(node, "count", entities.size());
            ctx.setOutput(node, "has_result", entity != null || !entities.isEmpty());
            ctx.setOutput(node, "location", entity != null ? entity.getLocation() : areaCenter(ctx, node));
            ctx.triggerOutput(entity != null || !entities.isEmpty() ? "found" : "empty");
            ctx.triggerOutput("flow");
        });
        operations.put("entity_effect", (ctx, node) -> {
            if (!cooldownReady(ctx, node, "entity_effect")) {
                ctx.triggerOutput("cooldown");
                return;
            }
            List<Entity> entities = entityList(ctx, node);
            String mode = string(ctx, node, "mode", "damage");
            int affected = 0;
            for (Entity entity : entities) {
                if (applyEntityEffect(ctx, node, entity, mode)) {
                    affected++;
                }
            }
            ctx.setOutput(node, "affected", affected);
            if (affected <= 0) {
                ctx.triggerOutput("empty");
            }
            ctx.triggerOutput("flow");
        });
        operations.put("area_effect", (ctx, node) -> {
            if (!cooldownReady(ctx, node, "area_effect")) {
                ctx.triggerOutput("cooldown");
                return;
            }
            Location center = areaCenter(ctx, node);
            String mode = string(ctx, node, "mode", "damage");
            List<Entity> entities = areaEntities(ctx, node, center);
            int affected = 0;
            for (Entity entity : entities) {
                if (applyEntityEffect(ctx, node, entity, mode)) {
                    affected++;
                }
            }
            applyAreaWorldEffect(ctx, node, center, mode);
            ctx.setOutput(node, "entities", entities);
            ctx.setOutput(node, "affected", affected);
            ctx.setOutput(node, "location", center);
            ctx.triggerOutput("flow");
        });
        operations.put("holding_effect", (ctx, node) -> {
            if (!cooldownReady(ctx, node, "holding_effect")) {
                ctx.triggerOutput("cooldown");
                return;
            }
            Player player = ctx.getInputValue(node, "player", Player.class, ctx.getPlayer());
            ItemStack heldItem = item(ctx, node);
            String mode = string(ctx, node, "mode", "potion");
            boolean success = applyHoldingEffect(ctx, node, player, heldItem, mode);
            ctx.setOutput(node, "item", heldItem);
            ctx.setOutput(node, "success", success);
            if (!success) {
                ctx.triggerOutput("fail");
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

    private boolean cooldownReady(FlowContext ctx, FlowNode node, String fallbackKey) {
        int ticks = Math.max(0, integer(ctx, node, "cooldown_ticks", 0));
        if (ticks <= 0) {
            return true;
        }
        String key = familyCooldownKey(ctx, node, fallbackKey);
        long now = System.currentTimeMillis();
        long readyAt = COOLDOWNS.getOrDefault(key, 0L);
        if (readyAt > now) {
            ctx.setOutput(node, "cooldown_ticks_left", (int) Math.ceil((readyAt - now) / 50.0));
            return false;
        }
        COOLDOWNS.put(key, now + ticks * 50L);
        ctx.setOutput(node, "cooldown_ticks_left", 0);
        return true;
    }

    private String familyCooldownKey(FlowContext ctx, FlowNode node, String fallbackKey) {
        String key = string(ctx, node, "cooldown_key", fallbackKey);
        String scope = string(ctx, node, "cooldown_scope", "player").toLowerCase(Locale.ROOT);
        return switch (scope) {
            case "global" -> key + ":global";
            case "content" -> key + ':' + String.valueOf(ctx.getRuntime().getEventVariables().getOrDefault("event.content_id", ""));
            case "target" -> key + ':' + (target(ctx, node) != null ? target(ctx, node).getUniqueId() : "none");
            case "item", "item instance" -> key + ':' + String.valueOf(ctx.getRuntime().getEventVariables().getOrDefault("event.instance_id", ""));
            default -> key + ':' + (ctx.getPlayer() != null ? ctx.getPlayer().getUniqueId() : "server");
        };
    }

    private List<Entity> queryEntities(FlowContext ctx, FlowNode node) {
        String mode = string(ctx, node, "mode", "radius").toLowerCase(Locale.ROOT);
        return switch (mode) {
            case "cone" -> coneEntities(ctx, node);
            case "line" -> lineEntities(ctx, node);
            case "box" -> boxEntities(ctx, node);
            case "raycast" -> raycastEntity(ctx, node);
            default -> entitiesAround(ctx, node);
        };
    }

    private Entity selectEntity(FlowContext ctx, FlowNode node, List<Entity> entities) {
        if (entities.isEmpty()) {
            return null;
        }
        String mode = string(ctx, node, "mode", "radius").toLowerCase(Locale.ROOT);
        String select = string(ctx, node, "select", switch (mode) {
            case "nearest" -> "nearest";
            case "random" -> "random";
            default -> "first";
        }).toLowerCase(Locale.ROOT);
        Location center = areaCenter(ctx, node);
        return switch (select) {
            case "nearest" -> center != null ? entities.stream().min(Comparator.comparingDouble(entity -> entity.getLocation().distanceSquared(center))).orElse(null) : entities.getFirst();
            case "farthest" -> center != null ? entities.stream().max(Comparator.comparingDouble(entity -> entity.getLocation().distanceSquared(center))).orElse(null) : entities.getFirst();
            case "random" -> entities.get(ThreadLocalRandom.current().nextInt(entities.size()));
            case "lowest_health" -> entities.stream()
                .min(Comparator.comparingDouble(entity -> entity instanceof LivingEntity living ? living.getHealth() : Double.MAX_VALUE))
                .orElse(entities.getFirst());
            default -> entities.getFirst();
        };
    }

    private List<Entity> coneEntities(FlowContext ctx, FlowNode node) {
        Player player = ctx.getPlayer();
        Location origin = areaCenter(ctx, node);
        Vector facing = direction(ctx, node);
        double range = number(ctx, node, "range", number(ctx, node, "radius", 8.0));
        double angle = Math.max(0.0, Math.min(180.0, number(ctx, node, "angle", 60.0)));
        String targetFilter = string(ctx, node, "target_filter", "living_entity");
        boolean excludeCaster = bool(ctx, node, "exclude_caster", true);
        List<Entity> entities = new ArrayList<>();
        if (origin == null || origin.getWorld() == null || facing.lengthSquared() <= 0.0) {
            return entities;
        }
        double cos = Math.cos(Math.toRadians(angle * 0.5));
        Vector normalizedFacing = facing.clone().normalize();
        for (Entity entity : origin.getWorld().getNearbyEntities(origin, range, range, range)) {
            if (!acceptsTarget(entity, targetFilter) || excludeCaster && entity == player) {
                continue;
            }
            Vector offset = entity.getLocation().toVector().subtract(origin.toVector());
            if (offset.lengthSquared() <= 0.0001 || offset.lengthSquared() > range * range) {
                continue;
            }
            if (offset.normalize().dot(normalizedFacing) >= cos) {
                entities.add(entity);
            }
        }
        return limitEntities(ctx, node, sortEntities(ctx, node, entities));
    }

    private List<Entity> lineEntities(FlowContext ctx, FlowNode node) {
        Player player = ctx.getPlayer();
        Location origin = areaCenter(ctx, node);
        Vector facing = direction(ctx, node);
        double range = number(ctx, node, "range", number(ctx, node, "radius", 12.0));
        double width = number(ctx, node, "width", 1.0);
        String targetFilter = string(ctx, node, "target_filter", "living_entity");
        boolean excludeCaster = bool(ctx, node, "exclude_caster", true);
        List<Entity> entities = new ArrayList<>();
        if (origin == null || origin.getWorld() == null || facing.lengthSquared() <= 0.0) {
            return entities;
        }
        Vector normalizedFacing = facing.clone().normalize();
        for (Entity entity : origin.getWorld().getNearbyEntities(origin, range, range, range)) {
            if (!acceptsTarget(entity, targetFilter) || excludeCaster && entity == player) {
                continue;
            }
            Vector offset = entity.getLocation().toVector().subtract(origin.toVector());
            double along = offset.dot(normalizedFacing);
            if (along < 0.0 || along > range) {
                continue;
            }
            Vector closest = normalizedFacing.clone().multiply(along);
            if (offset.subtract(closest).length() <= width) {
                entities.add(entity);
            }
        }
        return limitEntities(ctx, node, sortEntities(ctx, node, entities));
    }

    private List<Entity> boxEntities(FlowContext ctx, FlowNode node) {
        Location center = areaCenter(ctx, node);
        String targetFilter = string(ctx, node, "target_filter", "living_entity");
        boolean excludeCaster = bool(ctx, node, "exclude_caster", true);
        double width = Math.max(0.0, number(ctx, node, "width", number(ctx, node, "radius", 5.0) * 2.0));
        double height = Math.max(0.0, number(ctx, node, "height", width));
        double depth = Math.max(0.0, number(ctx, node, "depth", width));
        List<Entity> entities = new ArrayList<>();
        if (center == null || center.getWorld() == null) {
            return entities;
        }
        for (Entity entity : center.getWorld().getNearbyEntities(center, width * 0.5, height * 0.5, depth * 0.5)) {
            if (acceptsTarget(entity, targetFilter) && (!excludeCaster || entity != ctx.getPlayer())) {
                entities.add(entity);
            }
        }
        return limitEntities(ctx, node, sortEntities(ctx, node, entities));
    }

    private List<Entity> raycastEntity(FlowContext ctx, FlowNode node) {
        Player player = ctx.getPlayer();
        if (player == null) {
            return List.of();
        }
        double range = number(ctx, node, "range", 20.0);
        String targetFilter = string(ctx, node, "target_filter", "living_entity");
        RayTraceResult result = player.getWorld().rayTraceEntities(player.getEyeLocation(), player.getEyeLocation().getDirection(), range, entity -> entity != player && acceptsTarget(entity, targetFilter));
        return result != null && result.getHitEntity() != null ? List.of(result.getHitEntity()) : List.of();
    }

    private List<Entity> areaEntities(FlowContext ctx, FlowNode node, Location center) {
        String shape = string(ctx, node, "shape", "sphere").toLowerCase(Locale.ROOT);
        Map<String, Object> inputs = new HashMap<>(node.getInputValues() != null ? node.getInputValues() : Map.of());
        inputs.put("mode", switch (shape) {
            case "cone" -> "cone";
            case "line" -> "line";
            case "box" -> "box";
            default -> "radius";
        });
        if (center != null) {
            inputs.put("location", center);
        }
        FlowNode queryNode = new FlowNode("ability.entity_query", node.getX(), node.getY(), inputs);
        return queryEntities(ctx, queryNode);
    }

    private List<Entity> sortEntities(FlowContext ctx, FlowNode node, List<Entity> entities) {
        String sort = string(ctx, node, "sort", "none").toLowerCase(Locale.ROOT);
        Location center = areaCenter(ctx, node);
        List<Entity> sorted = new ArrayList<>(entities);
        if ("nearest".equals(sort) && center != null) {
            sorted.sort(Comparator.comparingDouble(entity -> entity.getLocation().distanceSquared(center)));
        } else if ("farthest".equals(sort) && center != null) {
            sorted.sort(Comparator.comparingDouble((Entity entity) -> entity.getLocation().distanceSquared(center)).reversed());
        } else if ("lowest_health".equals(sort)) {
            sorted.sort(Comparator.comparingDouble(entity -> entity instanceof LivingEntity living ? living.getHealth() : Double.MAX_VALUE));
        } else if ("random".equals(sort)) {
            Collections.shuffle(sorted);
        }
        return sorted;
    }

    private List<Entity> limitEntities(FlowContext ctx, FlowNode node, List<Entity> entities) {
        int limit = integer(ctx, node, "limit", 0);
        if (limit <= 0 || entities.size() <= limit) {
            return entities;
        }
        return new ArrayList<>(entities.subList(0, limit));
    }

    private boolean applyEntityEffect(FlowContext ctx, FlowNode node, Entity entity, String mode) {
        if (entity == null) {
            return false;
        }
        String normalized = mode == null ? "damage" : mode.toLowerCase(Locale.ROOT);
        switch (normalized) {
            case "damage" -> {
                if (entity instanceof LivingEntity living) {
                    damage(living, number(ctx, node, "amount", number(ctx, node, "damage", 4.0)), ctx.getPlayer());
                    return true;
                }
            }
            case "heal" -> {
                if (entity instanceof LivingEntity living) {
                    double amount = number(ctx, node, "amount", 4.0);
                    FlowMutations.heal(ctx, living, amount);
                    return true;
                }
            }
            case "potion" -> {
                if (entity instanceof LivingEntity living) {
                    addEffect(living, string(ctx, node, "effect", "SPEED"), integer(ctx, node, "duration_ticks", 100), integer(ctx, node, "amplifier", 0));
                    return true;
                }
            }
            case "ignite" -> {
                entity.setFireTicks(integer(ctx, node, "duration_ticks", integer(ctx, node, "ticks", 100)));
                return true;
            }
            case "freeze" -> {
                entity.setFreezeTicks(integer(ctx, node, "duration_ticks", integer(ctx, node, "ticks", 100)));
                return true;
            }
            case "stun" -> {
                if (entity instanceof LivingEntity living) {
                    addEffect(living, "SLOW", integer(ctx, node, "duration_ticks", 60), 10);
                    addEffect(living, "JUMP", integer(ctx, node, "duration_ticks", 60), 128);
                    return true;
                }
            }
            case "root" -> {
                if (entity instanceof LivingEntity living) {
                    addEffect(living, "SLOW", integer(ctx, node, "duration_ticks", 80), 20);
                    return true;
                }
            }
            case "silence" -> {
                MARKS.put(markKey(entity.getUniqueId(), "silenced"), System.currentTimeMillis() + integer(ctx, node, "duration_ticks", 100) * 50L);
                return true;
            }
            case "disarm" -> {
                if (entity instanceof Player player) {
                    PlayerInventory inventory = player.getInventory();
                    ItemStack item = inventory.getItemInMainHand();
                    if (item != null && !item.getType().isAir()) {
                        DISARMED_ITEMS.put(player.getUniqueId(), item.clone());
                        inventory.setItemInMainHand(new ItemStack(Material.AIR));
                        ctx.runLater(() -> {
                            ItemStack stored = DISARMED_ITEMS.remove(player.getUniqueId());
                            if (stored != null && player.isOnline()) {
                                ItemStack current = inventory.getItemInMainHand();
                                if (current == null || current.getType().isAir()) {
                                    inventory.setItemInMainHand(stored);
                                } else {
                                    inventory.addItem(stored);
                                }
                            }
                        }, Math.max(1, integer(ctx, node, "duration_ticks", 60)));
                        return true;
                    }
                }
            }
            case "launch" -> {
                Vector vector = normalizedDirection(ctx, node).multiply(number(ctx, node, "strength", 1.0));
                vector.setY(Math.max(number(ctx, node, "upward_strength", 0.5), vector.getY()));
                FlowMutations.applyVelocity(ctx, entity, vector);
                return true;
            }
            case "pull" -> {
                Location center = areaCenter(ctx, node);
                if (center != null) {
                    Vector vector = center.toVector().subtract(entity.getLocation().toVector());
                    if (vector.lengthSquared() <= 0.0001) {
                        return false;
                    }
                    FlowMutations.applyVelocity(ctx, entity, vector.normalize().multiply(number(ctx, node, "strength", 1.0)));
                    return true;
                }
            }
            case "push", "knockback" -> {
                Location center = areaCenter(ctx, node);
                if (center != null) {
                    Vector vector = knockbackDirection(ctx, entity, center).multiply(number(ctx, node, "strength", 1.0));
                    vector.setY(Math.max(number(ctx, node, "upward_strength", 0.2), vector.getY()));
                    FlowMutations.applyVelocity(ctx, entity, vector);
                    return true;
                }
            }
            case "set_velocity" -> {
                FlowMutations.applyVelocity(ctx, entity, new Vector(number(ctx, node, "x", 0), number(ctx, node, "y", 0), number(ctx, node, "z", 0)));
                return true;
            }
            case "no_damage_ticks" -> {
                if (entity instanceof LivingEntity living) {
                    FlowMutations.noDamageTicks(ctx, living, integer(ctx, node, "ticks", integer(ctx, node, "duration_ticks", 20)));
                    return true;
                }
            }
            case "shield" -> {
                if (entity instanceof LivingEntity living) {
                    FlowMutations.shield(ctx, living, number(ctx, node, "amount", 4.0), integer(ctx, node, "duration_ticks", 100));
                    return true;
                }
            }
            case "mark" -> {
                MARKS.put(markKey(entity.getUniqueId(), string(ctx, node, "key", "mark")), System.currentTimeMillis() + integer(ctx, node, "duration_ticks", 100) * 50L);
                return true;
            }
            case "remove_mark" -> {
                MARKS.remove(markKey(entity.getUniqueId(), string(ctx, node, "key", "mark")));
                return true;
            }
            case "life_steal" -> {
                Player player = ctx.getPlayer();
                if (player != null) {
                    double amount = number(ctx, node, "amount", ctx.getEvent() instanceof EntityDamageEvent event ? event.getDamage() : 1.0);
                    FlowMutations.heal(ctx, player, amount);
                    return true;
                }
            }
        }
        return false;
    }

    private void applyAreaWorldEffect(FlowContext ctx, FlowNode node, Location center, String mode) {
        if (center == null || center.getWorld() == null || mode == null) {
            return;
        }
        String normalized = mode.toLowerCase(Locale.ROOT);
        if ("particle".equals(normalized)) {
            center.getWorld().spawnParticle(parseParticle(string(ctx, node, "particle", "FLAME")), center, integer(ctx, node, "count", 20), number(ctx, node, "radius", 3.0), number(ctx, node, "radius", 3.0), number(ctx, node, "radius", 3.0), number(ctx, node, "speed", 0.0));
            return;
        }
        if ("sound".equals(normalized)) {
            center.getWorld().playSound(center, parseSound(string(ctx, node, "sound", "ENTITY_EXPERIENCE_ORB_PICKUP")), (float) number(ctx, node, "volume", 1.0), (float) number(ctx, node, "pitch", 1.0));
            return;
        }
        if ("extinguish".equals(normalized)) {
            int radius = Math.max(0, integer(ctx, node, "radius", 5));
            forEachBlock(center, radius, "sphere", block -> {
                if (block.getType() == Material.FIRE) {
                    block.setType(Material.AIR);
                }
            });
        }
    }

    private boolean applyHoldingEffect(FlowContext ctx, FlowNode node, Player player, ItemStack heldItem, String mode) {
        if (player == null) {
            return false;
        }
        String normalized = mode == null ? "potion" : mode.toLowerCase(Locale.ROOT);
        switch (normalized) {
            case "potion" -> {
                addEffect(player, string(ctx, node, "effect", "SPEED"), integer(ctx, node, "duration_ticks", 40), integer(ctx, node, "amplifier", 0));
                return true;
            }
            case "particle" -> {
                player.getWorld().spawnParticle(parseParticle(string(ctx, node, "particle", "FLAME")), player.getLocation().add(0.0, 1.0, 0.0), integer(ctx, node, "count", 4), number(ctx, node, "spread", 0.3), number(ctx, node, "spread", 0.3), number(ctx, node, "spread", 0.3), number(ctx, node, "speed", 0.0));
                return true;
            }
            case "sound" -> {
                player.playSound(player.getLocation(), parseSound(string(ctx, node, "sound", "ENTITY_EXPERIENCE_ORB_PICKUP")), (float) number(ctx, node, "volume", 1.0), (float) number(ctx, node, "pitch", 1.0));
                return true;
            }
            case "charge_drain" -> {
                String key = string(ctx, node, "key", "charge");
                double value = Math.max(0.0, getCharge(heldItem, key) - number(ctx, node, "amount", 1.0));
                setCharge(heldItem, key, value);
                ctx.setOutput(node, "value", value);
                return true;
            }
            case "charge_regen" -> {
                String key = string(ctx, node, "key", "charge");
                double max = number(ctx, node, "max", 100.0);
                double value = Math.min(max, getCharge(heldItem, key) + number(ctx, node, "amount", 1.0));
                setCharge(heldItem, key, value);
                ctx.setOutput(node, "value", value);
                return true;
            }
            case "durability_drain" -> {
                if (heldItem == null || !(heldItem.getItemMeta() instanceof Damageable damageable)) {
                    return false;
                }
                damageable.setDamage(damageable.getDamage() + Math.max(1, integer(ctx, node, "amount", 1)));
                heldItem.setItemMeta((ItemMeta) damageable);
                return true;
            }
        }
        return false;
    }

    private void moveArea(FlowContext ctx, FlowNode node, int direction) {
        Location location = areaCenter(ctx, node);
        double radius = number(ctx, node, "radius", 5.0);
        double strength = number(ctx, node, "strength", 1.0);
        String targetFilter = string(ctx, node, "target_filter", "any");
        boolean excludeCaster = bool(ctx, node, "exclude_caster", true);
        if (location != null && location.getWorld() != null) {
            for (Entity entity : location.getWorld().getNearbyEntities(location, radius, radius, radius)) {
                if (!acceptsTarget(entity, targetFilter) || excludeCaster && entity == ctx.getPlayer()) {
                    continue;
                }
                Vector vector = direction > 0 ? knockbackDirection(ctx, entity, location) : location.toVector().subtract(entity.getLocation().toVector());
                if (vector.lengthSquared() > 0.0001) {
                    FlowMutations.applyVelocity(ctx, entity, vector.normalize().multiply(strength));
                }
            }
        }
        ctx.triggerOutput("flow");
    }

    private LeapResult leapToLocation(FlowContext ctx, FlowNode node, Entity target, Location requestedLocation) {
        Location start = target.getLocation();
        if (start.getWorld() == null || requestedLocation.getWorld() == null || !start.getWorld().equals(requestedLocation.getWorld())) {
            return null;
        }
        int duration = Math.min(200, Math.max(1, integer(ctx, node, "duration_ticks", 20)));
        double arrivalRadius = Math.max(0.05, number(ctx, node, "arrival_radius", 0.35));
        LeapResult result = leapPath(target, start, requestedLocation, duration);
        target.setVelocity(new Vector(0.0, 0.0, 0.0));
        for (int tick = 1; tick <= result.points().size(); tick++) {
            int scheduledTick = tick;
            Location point = result.points().get(tick - 1);
            ctx.runLater(() -> moveAlongLeapPath(target, point), scheduledTick);
        }
        ctx.runLater(() -> {
            if (!target.isValid() || !(target instanceof Player)) {
                return;
            }
            Location current = target.getLocation();
            Location destination = result.destination();
            if (current.getWorld() != null && current.getWorld().equals(destination.getWorld()) && current.distanceSquared(destination) <= arrivalRadius * arrivalRadius) {
                target.setVelocity(new Vector(0.0, 0.0, 0.0));
                target.setFallDistance(0.0f);
            }
        }, duration + 1);
        return result;
    }

    private LeapResult leapPath(Entity target, Location start, Location requestedDestination, int duration) {
        Location destination = nearestLeapDestination(target, requestedDestination);
        boolean adjustedDestination = destination.distanceSquared(requestedDestination) > 0.01;
        double distance = Math.max(0.0, start.distance(destination));
        double baseHeight = Math.min(12.0, Math.max(1.25, distance * 0.28));
        for (int attempt = 0; attempt < 5; attempt++) {
            List<Location> points = leapPoints(start, destination, duration, baseHeight + attempt * 1.5);
            if (firstBlockedLeapPoint(target, points) == null) {
                return new LeapResult(destination, points, adjustedDestination);
            }
        }
        List<Location> points = leapPoints(start, destination, duration, baseHeight);
        List<Location> reachable = new ArrayList<>();
        Location lastClear = start.clone();
        for (Location point : points) {
            if (!hasEntitySpace(target, point)) {
                break;
            }
            lastClear = point;
            reachable.add(point);
        }
        Location fallback = nearestLeapDestination(target, lastClear);
        if (!sameBlock(lastClear, fallback) && hasEntitySpace(target, fallback)) {
            reachable.add(fallback);
        }
        return new LeapResult(fallback, reachable, true);
    }

    private List<Location> leapPoints(Location start, Location destination, int duration, double arcHeight) {
        List<Location> points = new ArrayList<>();
        for (int tick = 1; tick <= duration; tick++) {
            double progress = tick / (double) duration;
            double smooth = progress * progress * (3.0 - 2.0 * progress);
            points.add(start.clone().add(
                (destination.getX() - start.getX()) * smooth,
                (destination.getY() - start.getY()) * smooth + Math.sin(Math.PI * smooth) * arcHeight,
                (destination.getZ() - start.getZ()) * smooth
            ));
        }
        return points;
    }

    private Location firstBlockedLeapPoint(Entity target, List<Location> points) {
        for (Location point : points) {
            if (!hasEntitySpace(target, point)) {
                return point;
            }
        }
        return null;
    }

    private void moveAlongLeapPath(Entity target, Location point) {
        if (!target.isValid() || !hasEntitySpace(target, point)) {
            return;
        }
        Location current = target.getLocation();
        if (target instanceof Player) {
            target.setVelocity(point.toVector().subtract(current.toVector()));
        } else {
            Location targetLocation = point.clone();
            targetLocation.setYaw(current.getYaw());
            targetLocation.setPitch(current.getPitch());
            target.teleport(targetLocation);
            target.setVelocity(new Vector(0.0, 0.0, 0.0));
        }
        target.setFallDistance(0.0f);
    }

    private Location nearestLeapDestination(Entity target, Location destination) {
        if (hasEntitySpace(target, destination)) {
            return destination.clone();
        }
        if (destination.getWorld() == null) {
            return destination.clone();
        }
        Location best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int y = -2; y <= 3; y++) {
            for (int radius = 0; radius <= 2; radius++) {
                for (int x = -radius; x <= radius; x++) {
                    for (int z = -radius; z <= radius; z++) {
                        if (Math.abs(x) != radius && Math.abs(z) != radius) {
                            continue;
                        }
                        Location candidate = destination.clone().add(x, y, z);
                        if (!hasEntitySpace(target, candidate)) {
                            continue;
                        }
                        double distance = candidate.distanceSquared(destination);
                        if (distance < bestDistance) {
                            best = candidate;
                            bestDistance = distance;
                        }
                    }
                }
            }
        }
        return best != null ? best : destination.clone();
    }

    private boolean hasEntitySpace(Entity target, Location location) {
        World world = location.getWorld();
        if (world == null) {
            return false;
        }
        BoundingBox bounds = target.getBoundingBox();
        double width = Math.max(0.6, Math.max(bounds.getWidthX(), bounds.getWidthZ()));
        double height = Math.max(1.0, bounds.getHeight());
        double halfWidth = width * 0.5;
        int minX = (int) Math.floor(location.getX() - halfWidth);
        int maxX = (int) Math.floor(location.getX() + halfWidth);
        int minY = (int) Math.floor(location.getY());
        int maxY = (int) Math.floor(location.getY() + height);
        int minZ = (int) Math.floor(location.getZ() - halfWidth);
        int maxZ = (int) Math.floor(location.getZ() + halfWidth);
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (!world.getBlockAt(x, y, z).isPassable()) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private boolean sameBlock(Location first, Location second) {
        return first.getBlockX() == second.getBlockX() && first.getBlockY() == second.getBlockY() && first.getBlockZ() == second.getBlockZ();
    }

    private record LeapResult(Location destination, List<Location> points, boolean blocked) {
    }

    private List<Entity> entitiesAround(FlowContext ctx, FlowNode node) {
        Location location = areaCenter(ctx, node);
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

    private Location areaCenter(FlowContext ctx, FlowNode node) {
        Location location = ctx.getInputValue(node, "location", Location.class, null);
        if (location != null) {
            return location;
        }
        Object eventLocation = ctx.getRuntime().getEventVariables().get("event.location");
        if (eventLocation instanceof Location loc) {
            return loc;
        }
        Entity target = eventEntity(ctx, "event.target");
        if (target == null) {
            target = eventEntity(ctx, "event.hit_entity");
        }
        if (target == null) {
            target = eventEntity(ctx, "event.entity");
        }
        return target != null ? target.getLocation() : ctx.getPlayer() != null ? ctx.getPlayer().getLocation() : null;
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
        if (eventTarget instanceof Entity entity) {
            return entity;
        }
        Entity hitEntity = eventEntity(ctx, "event.hit_entity");
        if (hitEntity != null) {
            return hitEntity;
        }
        Entity entity = eventEntity(ctx, "event.entity");
        return entity != null ? entity : ctx.getPlayer();
    }

    private Entity eventEntity(FlowContext ctx, String key) {
        Object value = ctx.getRuntime().getEventVariables().get(key);
        return value instanceof Entity entity ? entity : null;
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

    private Vector normalizedDirection(FlowContext ctx, FlowNode node) {
        Vector direction = direction(ctx, node);
        if (direction == null || direction.lengthSquared() <= 0.0001) {
            return new Vector(0.0, 0.0, 1.0);
        }
        return direction.clone().normalize();
    }

    private Vector knockbackDirection(FlowContext ctx, Entity entity, Location center) {
        Vector direction = entity.getLocation().toVector().subtract(center.toVector());
        if (direction.lengthSquared() > 0.0001) {
            return direction.normalize();
        }
        Player player = ctx.getPlayer();
        if (player != null && player.getWorld().equals(entity.getWorld())) {
            direction = entity.getLocation().toVector().subtract(player.getLocation().toVector());
            if (direction.lengthSquared() > 0.0001) {
                return direction.normalize();
            }
            direction = player.getLocation().getDirection();
            direction.setY(0.0);
            if (direction.lengthSquared() > 0.0001) {
                return direction.normalize();
            }
        }
        return new Vector(0.0, 0.0, 1.0);
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

    private void damage(LivingEntity target, double amount, Entity source) {
        CustomContentListener.runSuppressingDamageAbilities(() -> {
            if (source != null) {
                target.damage(amount, source);
            } else {
                target.damage(amount);
            }
        });
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

    private void executeParticle(FlowContext ctx, FlowNode source, String mode, Map<String, Object> overrides) {
        Map<String, Object> inputs = new HashMap<>(source.getInputValues() != null ? source.getInputValues() : Map.of());
        inputs.put("mode", mode);
        inputs.putAll(overrides);
        FlowNode particleNode = new FlowNode("particle.apply", source.getX(), source.getY(), inputs);
        particleNode.setHandlerConfig(Map.of("operation", "particle_apply"));
        particleHandler.execute(ctx, particleNode);
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

    private void addEffect(LivingEntity living, String name, int duration, int amplifier) {
        PotionEffectType type = PotionEffectType.getByName(name);
        if (type != null) {
            living.addPotionEffect(new PotionEffect(type, duration, amplifier, false, false, false));
        }
    }

    private Projectile launchProjectile(Player player, String projectileType) {
        String normalized = projectileType == null ? "ARROW" : projectileType.toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "SNOWBALL" -> player.launchProjectile(Snowball.class);
            case "EGG" -> player.launchProjectile(Egg.class);
            case "TRIDENT" -> player.launchProjectile(Trident.class);
            case "FIREBALL", "SMALL_FIREBALL" -> player.launchProjectile(SmallFireball.class);
            default -> player.launchProjectile(Arrow.class);
        };
    }

    private AbstractArrow.PickupStatus pickupStatus(String pickupMode) {
        String normalized = pickupMode == null ? "allowed" : pickupMode.toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "creative_only", "creative only" -> AbstractArrow.PickupStatus.CREATIVE_ONLY;
            case "disallowed", "none" -> AbstractArrow.PickupStatus.DISALLOWED;
            default -> AbstractArrow.PickupStatus.ALLOWED;
        };
    }

    private boolean acceptsBlock(Block block, String filter) {
        if (block == null) {
            return false;
        }
        Material material = parseMaterial(filter, null);
        return material == null || block.getType() == material;
    }

    private void forEachBlock(Location center, int radius, String shape, Consumer<Block> consumer) {
        World world = center.getWorld();
        int safeRadius = Math.max(0, radius);
        String normalized = shape == null ? "cube" : shape.toLowerCase(Locale.ROOT);
        for (int x = -safeRadius; x <= safeRadius; x++) {
            for (int y = -safeRadius; y <= safeRadius; y++) {
                for (int z = -safeRadius; z <= safeRadius; z++) {
                    if (("sphere".equals(normalized) || "cylinder".equals(normalized)) && x * x + z * z + ("sphere".equals(normalized) ? y * y : 0) > safeRadius * safeRadius) {
                        continue;
                    }
                    consumer.accept(world.getBlockAt(center.getBlockX() + x, center.getBlockY() + y, center.getBlockZ() + z));
                }
            }
        }
    }

    private void spawnParticleShape(Location center, Particle particle, String mode, int count, double radius, double speed) {
        World world = center.getWorld();
        String normalized = mode == null ? "ring" : mode.toLowerCase(Locale.ROOT);
        if ("burst".equals(normalized)) {
            world.spawnParticle(particle, center, count, radius, radius, radius, speed);
            return;
        }
        if ("line".equals(normalized)) {
            Vector direction = center.getDirection().normalize().multiply(radius * 2.0 / count);
            Location point = center.clone().subtract(direction.clone().multiply(count / 2.0));
            for (int i = 0; i < count; i++) {
                world.spawnParticle(particle, point, 1, 0, 0, 0, speed);
                point.add(direction);
            }
            return;
        }
        for (int i = 0; i < count; i++) {
            double angle = Math.PI * 2.0 * i / count;
            double y = "orbit".equals(normalized) ? Math.sin(angle * 2.0) * radius * 0.5 : 0.0;
            Location point = center.clone().add(Math.cos(angle) * radius, y, Math.sin(angle) * radius);
            world.spawnParticle(particle, point, 1, 0, 0, 0, speed);
        }
    }

    private List<Entity> entityList(FlowContext ctx, FlowNode node) {
        String source = string(ctx, node, "target_source", "event target").toLowerCase(Locale.ROOT);
        if ("entities".equals(source)) {
            return connectedEntityList(ctx, node);
        }
        if ("target".equals(source)) {
            Entity target = ctx.getInputValue(node, "target", Entity.class, null);
            return target != null ? List.of(target) : List.of();
        }
        List<Entity> entities = connectedEntityList(ctx, node);
        if (!entities.isEmpty()) {
            return entities;
        }
        Entity target = target(ctx, node);
        return target != null ? List.of(target) : List.of();
    }

    private List<Entity> connectedEntityList(FlowContext ctx, FlowNode node) {
        Object value = ctx.getInputValue(node, "entities");
        if (value instanceof Collection<?> collection) {
            List<Entity> entities = new ArrayList<>();
            for (Object entry : collection) {
                if (entry instanceof Entity entity) {
                    entities.add(entity);
                }
            }
            return entities;
        }
        return List.of();
    }

    private ItemStack item(FlowContext ctx, FlowNode node) {
        ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
        if (item != null) {
            return item;
        }
        Object eventItem = ctx.getRuntime().getEventVariables().get("event.item");
        if (eventItem instanceof ItemStack stack) {
            return stack;
        }
        Player player = ctx.getPlayer();
        return player != null ? player.getInventory().getItemInMainHand() : null;
    }

    private ItemStack matchingHeldItem(Player player, String contentId, String hand) {
        if (player == null) {
            return null;
        }
        CustomContentService service = CustomContentAccess.getService();
        if (service == null) {
            return null;
        }
        String normalized = hand == null ? "any" : hand.toLowerCase(Locale.ROOT);
        if (!"offhand".equals(normalized)) {
            ItemStack item = player.getInventory().getItemInMainHand();
            if (matchesContent(service, item, contentId)) {
                return item;
            }
        }
        if (!"main hand".equals(normalized) && !"main_hand".equals(normalized)) {
            ItemStack item = player.getInventory().getItemInOffHand();
            if (matchesContent(service, item, contentId)) {
                return item;
            }
        }
        return null;
    }

    private ItemStack matchingArmorItem(Player player, String contentId, String slot) {
        if (player == null) {
            return null;
        }
        CustomContentService service = CustomContentAccess.getService();
        if (service == null) {
            return null;
        }
        PlayerInventory inventory = player.getInventory();
        String normalized = slot == null ? "any" : slot.toLowerCase(Locale.ROOT);
        Map<String, ItemStack> armor = Map.of(
            "head", inventory.getHelmet() != null ? inventory.getHelmet() : new ItemStack(Material.AIR),
            "chest", inventory.getChestplate() != null ? inventory.getChestplate() : new ItemStack(Material.AIR),
            "legs", inventory.getLeggings() != null ? inventory.getLeggings() : new ItemStack(Material.AIR),
            "feet", inventory.getBoots() != null ? inventory.getBoots() : new ItemStack(Material.AIR)
        );
        if (!"any".equals(normalized)) {
            ItemStack item = armor.get(normalized);
            return matchesContent(service, item, contentId) ? item : null;
        }
        for (ItemStack item : armor.values()) {
            if (matchesContent(service, item, contentId)) {
                return item;
            }
        }
        return null;
    }

    private boolean hasContentSet(Player player, String prefix) {
        if (player == null) {
            return false;
        }
        CustomContentService service = CustomContentAccess.getService();
        if (service == null) {
            return false;
        }
        for (ItemStack armor : player.getInventory().getArmorContents()) {
            String id = service.identifyItem(armor);
            if (id == null || !prefix.isBlank() && !id.startsWith(prefix)) {
                return false;
            }
        }
        return true;
    }

    private boolean matchesContent(CustomContentService service, ItemStack item, String contentId) {
        String id = service.identifyItem(item);
        return id != null && (contentId == null || contentId.isBlank() || id.equalsIgnoreCase(contentId));
    }

    private CustomContentDefinition definition(String contentId) {
        CustomContentStorage storage = CustomContentAccess.getStorage();
        return storage != null && contentId != null ? storage.get(contentId) : null;
    }

    private String cooldownKey(FlowContext ctx, FlowNode node) {
        String key = string(ctx, node, "key", "ability");
        String scope = string(ctx, node, "scope", "player").toLowerCase(Locale.ROOT);
        return switch (scope) {
            case "global" -> key + ":global";
            case "content" -> key + ':' + string(ctx, node, "content_id", String.valueOf(ctx.getRuntime().getEventVariables().getOrDefault("event.content_id", "")));
            default -> key + ':' + (ctx.getPlayer() != null ? ctx.getPlayer().getUniqueId() : "server");
        };
    }

    private double getCharge(ItemStack item, String key) {
        if (item == null || !item.hasItemMeta()) {
            return 0.0;
        }
        ItemMeta meta = item.getItemMeta();
        return meta != null ? meta.getPersistentDataContainer().getOrDefault(chargeKey(key), PersistentDataType.DOUBLE, 0.0) : 0.0;
    }

    private void setCharge(ItemStack item, String key, double value) {
        if (item == null) {
            return;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return;
        }
        PersistentDataContainer container = meta.getPersistentDataContainer();
        container.set(chargeKey(key), PersistentDataType.DOUBLE, value);
        item.setItemMeta(meta);
    }

    private NamespacedKey chargeKey(String key) {
        String safeKey = (key == null || key.isBlank() ? "charge" : key).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_./-]", "_");
        return new NamespacedKey(ReSync.getInstance(), "ability_" + safeKey);
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
