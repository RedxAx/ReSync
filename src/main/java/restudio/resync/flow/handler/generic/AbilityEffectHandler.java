package restudio.resync.flow.handler.generic;

import org.bukkit.Bukkit;
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
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
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

public class AbilityEffectHandler implements NodeHandler, Listener {
    private final Map<String, BiConsumer<FlowContext, FlowNode>> operations = new ConcurrentHashMap<>();
    private final ParticleHandler particleHandler = new ParticleHandler();
    private final Map<String, Long> marks = new ConcurrentHashMap<>();
    private final Map<UUID, ItemStack> disarmedItems = new ConcurrentHashMap<>();
    private final Map<String, Long> cooldowns = new ConcurrentHashMap<>();

    public AbilityEffectHandler() {
        ReSync plugin = ReSync.getInstance();
        if (plugin != null) Bukkit.getPluginManager().registerEvents(this, plugin);
        operations.put("strike_lightning", (ctx, node) -> {
            Location location = requireLocation(ctx, node);
            location.getWorld().strikeLightning(location);
            ctx.triggerOutput("flow");
        });
        operations.put("fake_lightning", (ctx, node) -> {
            Location location = requireLocation(ctx, node);
            location.getWorld().strikeLightningEffect(location);
            ctx.triggerOutput("flow");
        });
        operations.put("damage_area", (ctx, node) -> {
            Location location = requireAreaCenter(ctx, node);
            double radius = number(ctx, node, "radius", 5.0);
            double damage = number(ctx, node, "damage", 4.0);
            String targetFilter = string(ctx, node, "target_filter", "any");
            boolean excludeCaster = bool(ctx, node, "exclude_caster", true);
            requireRadius(radius);
            if (damage < 0 || !Double.isFinite(damage)) throw new IllegalArgumentException("Area damage must be a finite non-negative number");
            for (Entity entity : location.getWorld().getNearbyEntities(location, radius, radius, radius)) {
                if (entity instanceof LivingEntity living && acceptsTarget(entity, targetFilter) && (!excludeCaster || entity != ctx.getPlayer())) {
                    damage(living, damage, ctx.getPlayer());
                }
            }
            ctx.triggerOutput("flow");
        });
        operations.put("heal_area", (ctx, node) -> {
            Location location = requireAreaCenter(ctx, node);
            double radius = number(ctx, node, "radius", 5.0);
            double amount = number(ctx, node, "amount", 4.0);
            String targetFilter = string(ctx, node, "target_filter", "any");
            boolean excludeCaster = bool(ctx, node, "exclude_caster", false);
            requireRadius(radius);
            if (amount < 0 || !Double.isFinite(amount)) throw new IllegalArgumentException("Area healing must be a finite non-negative number");
            for (Entity entity : location.getWorld().getNearbyEntities(location, radius, radius, radius)) {
                if (entity instanceof LivingEntity living && acceptsTarget(entity, targetFilter) && (!excludeCaster || entity != ctx.getPlayer())) {
                    FlowMutations.heal(ctx, living, amount);
                }
            }
            ctx.triggerOutput("flow");
        });
        operations.put("knockback_area", (ctx, node) -> {
            Location location = requireAreaCenter(ctx, node);
            double radius = number(ctx, node, "radius", 5.0);
            double strength = number(ctx, node, "strength", 1.2);
            double upwardStrength = number(ctx, node, "upward_strength", 0.2);
            String targetFilter = string(ctx, node, "target_filter", "any");
            boolean excludeCaster = bool(ctx, node, "exclude_caster", true);
            requireRadius(radius);
            for (Entity entity : location.getWorld().getNearbyEntities(location, radius, radius, radius)) {
                if (!acceptsTarget(entity, targetFilter) || excludeCaster && entity == ctx.getPlayer()) {
                    continue;
                }
                Vector direction = knockbackDirection(ctx, entity, location).multiply(strength);
                direction.setY(Math.max(upwardStrength, direction.getY()));
                FlowMutations.applyVelocity(ctx, entity, direction);
            }
            ctx.triggerOutput("flow");
        });
        operations.put("find_entities_radius", (ctx, node) -> {
            Location location = requireAreaCenter(ctx, node);
            double radius = number(ctx, node, "radius", 5.0);
            String targetFilter = string(ctx, node, "target_filter", "any");
            boolean excludeCaster = bool(ctx, node, "exclude_caster", false);
            List<Entity> entities = new ArrayList<>();
            requireRadius(radius);
            for (Entity entity : location.getWorld().getNearbyEntities(location, radius, radius, radius)) {
                if (acceptsTarget(entity, targetFilter) && (!excludeCaster || entity != ctx.getPlayer())) {
                    entities.add(entity);
                }
            }
            ctx.setOutput(node, "entities", entities);
            ctx.triggerOutput("flow");
        });
        operations.put("raycast", (ctx, node) -> {
            Player player = requirePlayer(ctx, node, "player");
            double distance = number(ctx, node, "distance", 20.0);
            if (!Double.isFinite(distance) || distance < 0 || distance > 1024) throw new IllegalArgumentException("Raycast distance must be between 0 and 1024");
            boolean stopOnBlock = bool(ctx, node, "stop_on_block", true);
            boolean stopOnEntity = bool(ctx, node, "stop_on_entity", true);
            String hitMode = string(ctx, node, "hit_mode", "any").toLowerCase(Locale.ROOT);
            String targetFilter = string(ctx, node, "target_filter", "any");
            String blockFilter = string(ctx, node, "block_filter", "any");
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
            ctx.triggerOutput("flow");
        });
        operations.put("particle_burst", (ctx, node) -> {
            executeParticle(ctx, node, "burst", Map.of());
        });
        operations.put("play_sound", (ctx, node) -> {
            Location location = requireLocation(ctx, node);
            String soundName = ctx.getInputValue(node, "sound", String.class, "ENTITY_EXPERIENCE_ORB_PICKUP");
            float volume = (float) number(ctx, node, "volume", 1.0);
            float pitch = (float) number(ctx, node, "pitch", 1.0);
            if (!Float.isFinite(volume) || volume < 0) throw new IllegalArgumentException("Sound volume must be a finite non-negative number");
            if (!Float.isFinite(pitch) || pitch < 0.5f || pitch > 2.0f) throw new IllegalArgumentException("Sound pitch must be between 0.5 and 2.0");
            location.getWorld().playSound(location, parseSound(soundName), volume, pitch);
            ctx.triggerOutput("flow");
        });
        operations.put("potion_effect", (ctx, node) -> {
            LivingEntity target = requireLivingTarget(ctx, node);
            String effectName = ctx.getInputValue(node, "effect", String.class, "SPEED");
            int duration = integer(ctx, node, "duration_ticks", 100);
            int amplifier = integer(ctx, node, "amplifier", 0);
            PotionEffectType type = requirePotionEffect(effectName);
            requireEffectValues(duration, amplifier);
            target.addPotionEffect(new PotionEffect(type, duration, amplifier));
            ctx.triggerOutput("flow");
        });
        operations.put("launch_projectile", (ctx, node) -> {
            Player player = requirePlayer(ctx, node, "player");
            double speed = number(ctx, node, "speed", 2.0);
            String projectileType = string(ctx, node, "projectile_type", "ARROW");
            boolean gravity = bool(ctx, node, "gravity", true);
            int fireTicks = integer(ctx, node, "fire_ticks", 0);
            double damage = number(ctx, node, "damage", 0.0);
            int pierce = integer(ctx, node, "pierce", 0);
            String pickupMode = string(ctx, node, "pickup_mode", "allowed");
            String markKey = string(ctx, node, "mark_key", "");
            if (!Double.isFinite(speed) || speed < 0 || speed > 10) throw new IllegalArgumentException("Projectile speed must be between 0 and 10");
            if (fireTicks < 0 || fireTicks > 72_000) throw new IllegalArgumentException("Projectile fire ticks must be between 0 and 72000");
            if (!Double.isFinite(damage) || damage < 0) throw new IllegalArgumentException("Projectile damage must be a finite non-negative number");
            if (pierce < 0 || pierce > 127) throw new IllegalArgumentException("Projectile pierce level must be between 0 and 127");
            Projectile projectile = launchProjectile(player, projectileType);
            try {
                projectile.setVelocity(player.getEyeLocation().getDirection().multiply(speed));
                projectile.setGravity(gravity);
                projectile.setFireTicks(fireTicks);
                if (projectile instanceof AbstractArrow arrow) {
                    if (damage > 0.0) {
                        arrow.setDamage(damage);
                    }
                    arrow.setPierceLevel(pierce);
                    arrow.setPickupStatus(pickupStatus(pickupMode));
                }
                if (!markKey.isBlank()) {
                    projectile.addScoreboardTag("resync:" + markKey);
                }
                if (projectile instanceof Fireball fireball) {
                    fireball.setDirection(player.getEyeLocation().getDirection().multiply(speed));
                }
            } catch (RuntimeException exception) {
                projectile.remove();
                throw exception;
            }
            ctx.setOutput(node, "projectile", projectile);
            ctx.triggerOutput("flow");
        });
        operations.put("damage_target", (ctx, node) -> {
            LivingEntity target = requireLivingTarget(ctx, node);
            double amount = number(ctx, node, "amount", 4.0);
            boolean useSource = bool(ctx, node, "use_source", true);
            if (amount < 0 || !Double.isFinite(amount)) throw new IllegalArgumentException("Damage amount must be a finite non-negative number");
            damage(target, amount, useSource ? ctx.getPlayer() : null);
            ctx.triggerOutput("flow");
        });
        operations.put("heal_target", (ctx, node) -> {
            LivingEntity target = requireLivingTarget(ctx, node);
            double amount = number(ctx, node, "amount", 4.0);
            if (amount < 0 || !Double.isFinite(amount)) throw new IllegalArgumentException("Healing amount must be a finite non-negative number");
            FlowMutations.heal(ctx, target, amount);
            ctx.triggerOutput("flow");
        });
        operations.put("set_health", (ctx, node) -> {
            LivingEntity target = requireLivingTarget(ctx, node);
            double health = number(ctx, node, "health", 20.0);
            FlowMutations.setHealth(ctx, target, health);
            ctx.triggerOutput("flow");
        });
        operations.put("cancel_damage", (ctx, node) -> {
            boolean cancelled = bool(ctx, node, "cancelled", true);
            if (!(ctx.getEvent() instanceof EntityDamageEvent damageEvent)) throw new IllegalArgumentException("Cancel Damage requires an entity damage event");
            if (!ctx.setEventCancelled(cancelled)) throw new IllegalStateException("The damage event mutation window is closed");
            if (cancelled) damageEvent.setDamage(0.0);
            ctx.triggerOutput("flow");
        });
        operations.put("shield_target", (ctx, node) -> {
            double amount = number(ctx, node, "amount", 4.0);
            int duration = integer(ctx, node, "duration_ticks", 100);
            LivingEntity living = requireLivingTarget(ctx, node);
            FlowMutations.shield(ctx, living, amount, duration);
            ctx.triggerOutput("flow");
        });
        operations.put("reflect_damage", (ctx, node) -> {
            double percent = number(ctx, node, "percent", 100.0);
            if (!Double.isFinite(percent) || percent < 0) throw new IllegalArgumentException("Reflected damage percent must be a finite non-negative number");
            if (!(ctx.getEvent() instanceof EntityDamageByEntityEvent damageEvent) || !(damageEvent.getDamager() instanceof LivingEntity damager)) throw new IllegalArgumentException("Reflect Damage requires an entity damage event with a living damager");
            damage(damager, damageEvent.getDamage() * percent / 100.0, damageEvent.getEntity());
            ctx.triggerOutput("flow");
        });
        operations.put("launch_target", (ctx, node) -> {
            Entity target = requireTarget(ctx, node);
            double strength = number(ctx, node, "strength", 1.0);
            double upwardStrength = number(ctx, node, "upward_strength", 0.5);
            Vector vector = normalizedDirection(ctx, node).multiply(strength);
            vector.setY(vector.getY() + upwardStrength);
            FlowMutations.applyVelocity(ctx, target, vector);
            ctx.triggerOutput("flow");
        });
        operations.put("pull_target", (ctx, node) -> {
            Entity target = requireTarget(ctx, node);
            Location location = requireLocation(ctx, node);
            double strength = number(ctx, node, "strength", 1.0);
            Vector vector = location.toVector().subtract(target.getLocation().toVector());
            if (vector.lengthSquared() <= 0.0001) throw new IllegalArgumentException("Pull target is already at the destination");
            FlowMutations.applyVelocity(ctx, target, vector.normalize().multiply(strength));
            ctx.triggerOutput("flow");
        });
        operations.put("leap_to_location", (ctx, node) -> {
            Entity target = requireTarget(ctx, node);
            Location location = requireLocation(ctx, node);
            LeapResult result = leapToLocation(ctx, node, target, location);
            ctx.setOutput(node, "destination", result.destination());
            ctx.setOutput(node, "blocked", result.blocked());
            ctx.triggerOutput("flow");
        });
        operations.put("stun_target", (ctx, node) -> {
            LivingEntity target = requireLivingTarget(ctx, node);
            int duration = integer(ctx, node, "duration_ticks", 60);
            requireDuration(duration);
            addEffect(target, "SLOW", duration, 10);
            addEffect(target, "JUMP", duration, 250);
            ctx.triggerOutput("flow");
        });
        operations.put("root_target", (ctx, node) -> {
            LivingEntity target = requireLivingTarget(ctx, node);
            int duration = integer(ctx, node, "duration_ticks", 60);
            requireDuration(duration);
            addEffect(target, "SLOW", duration, 10);
            addEffect(target, "JUMP", duration, 250);
            ctx.triggerOutput("flow");
        });
        operations.put("silence_target", (ctx, node) -> {
            Entity target = requireTarget(ctx, node);
            int duration = integer(ctx, node, "duration_ticks", 100);
            requireDuration(duration);
            marks.put(markKey(target.getUniqueId(), "silenced"), System.currentTimeMillis() + duration * 50L);
            ctx.triggerOutput("flow");
        });
        operations.put("disarm_target", (ctx, node) -> {
            Entity target = requireTarget(ctx, node);
            int duration = integer(ctx, node, "duration_ticks", 60);
            requireDuration(duration);
            if (!(target instanceof Player player)) throw new IllegalArgumentException("Disarm target must be a player");
            disarm(ctx, player, duration);
            ctx.triggerOutput("flow");
        });
        operations.put("pull_entities", (ctx, node) -> moveArea(ctx, node, -1));
        operations.put("push_entities", (ctx, node) -> moveArea(ctx, node, 1));
        operations.put("ignite_target", (ctx, node) -> {
            Entity target = requireTarget(ctx, node);
            target.setFireTicks(requireDuration(integer(ctx, node, "ticks", 100)));
            ctx.triggerOutput("flow");
        });
        operations.put("freeze_target", (ctx, node) -> {
            Entity target = requireTarget(ctx, node);
            target.setFreezeTicks(requireDuration(integer(ctx, node, "ticks", 100)));
            ctx.triggerOutput("flow");
        });
        operations.put("set_velocity", (ctx, node) -> {
            Entity target = requireTarget(ctx, node);
            FlowMutations.applyVelocity(ctx, target, new Vector(number(ctx, node, "x", 0), number(ctx, node, "y", 0), number(ctx, node, "z", 0)));
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
            Location origin = requireLocation(ctx, node);
            Vector direction = direction(ctx, node);
            double range = number(ctx, node, "range", 8.0);
            requireRadius(range);
            if (direction.lengthSquared() <= 0) throw new IllegalArgumentException("Cone direction cannot be zero");
            double angle = number(ctx, node, "angle", 60.0);
            if (!Double.isFinite(angle) || angle < 0 || angle > 180) throw new IllegalArgumentException("Cone angle must be between 0 and 180");
            String targetFilter = string(ctx, node, "target_filter", "living_entity");
            boolean excludeCaster = bool(ctx, node, "exclude_caster", true);
            List<Entity> entities = new ArrayList<>();
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
            ctx.setOutput(node, "entities", entities);
            ctx.triggerOutput("flow");
        });
        operations.put("line_entities", (ctx, node) -> {
            Player player = ctx.getPlayer();
            Location origin = requireLocation(ctx, node);
            Vector direction = normalizedDirection(ctx, node);
            double range = number(ctx, node, "range", 12.0);
            double width = number(ctx, node, "width", 1.0);
            requireRadius(range);
            if (!Double.isFinite(width) || width < 0 || width > 32) throw new IllegalArgumentException("Line width must be between 0 and 32");
            String targetFilter = string(ctx, node, "target_filter", "living_entity");
            boolean excludeCaster = bool(ctx, node, "exclude_caster", true);
            List<Entity> entities = new ArrayList<>();
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
            ctx.setOutput(node, "entities", entities);
            ctx.triggerOutput("flow");
        });
        operations.put("dash", (ctx, node) -> {
            Entity target = requireTarget(ctx, node);
            double strength = number(ctx, node, "strength", 1.4);
            double upwardStrength = number(ctx, node, "upward_strength", 0.15);
            Vector vector = normalizedDirection(ctx, node).multiply(strength);
            vector.setY(Math.max(vector.getY(), upwardStrength));
            FlowMutations.applyVelocity(ctx, target, vector);
            ctx.triggerOutput("flow");
        });
        operations.put("teleport_caster", (ctx, node) -> {
            Player player = requirePlayer(ctx, node, "player");
            Location location = requireLocation(ctx, node);
            boolean keepDirection = bool(ctx, node, "keep_direction", true);
            Location target = location.clone();
            if (keepDirection) {
                target.setYaw(player.getLocation().getYaw());
                target.setPitch(player.getLocation().getPitch());
            }
            player.teleport(target);
            ctx.triggerOutput("flow");
        });
        operations.put("teleport_target", (ctx, node) -> {
            Entity target = requireTarget(ctx, node);
            Location location = requireLocation(ctx, node);
            target.teleport(location);
            ctx.triggerOutput("flow");
        });
        operations.put("beam", (ctx, node) -> {
            Location start = ctx.getInputValue(node, "start", Location.class, null);
            Location end = ctx.getInputValue(node, "end", Location.class, null);
            if (start == null && ctx.getPlayer() != null) {
                start = ctx.getPlayer().getEyeLocation();
            }
            if (end == null) {
                end = requireLocation(ctx, node);
            }
            String particleName = string(ctx, node, "particle", "ELECTRIC_SPARK");
            if (start == null || start.getWorld() == null) throw new IllegalArgumentException("Beam start location is required");
            if (end.getWorld() == null || !start.getWorld().equals(end.getWorld())) throw new IllegalArgumentException("Beam locations must be in the same world");
            int steps = integer(ctx, node, "steps", 24);
            if (steps < 1 || steps > 10000) throw new IllegalArgumentException("Beam steps must be between 1 and 10000");
            Particle particle = parseParticle(particleName);
            Vector delta = end.toVector().subtract(start.toVector()).multiply(1.0 / steps);
            Location point = start.clone();
            for (int i = 0; i <= steps; i++) {
                start.getWorld().spawnParticle(particle, point, 1, 0, 0, 0, 0);
                point.add(delta);
            }
            ctx.triggerOutput("flow");
        });
        operations.put("temporary_block", (ctx, node) -> {
            Location location = requireLocation(ctx, node);
            int duration = requireDuration(integer(ctx, node, "duration_ticks", 60));
            String materialName = string(ctx, node, "material", "ICE");
            Block block = location.getBlock();
            Material previousType = block.getType();
            Material material = parseMaterial(materialName, Material.ICE);
            if (material == null) throw new IllegalArgumentException("Temporary block material cannot be a wildcard");
            block.setType(material, false);
            ctx.runLater(() -> {
                if (block.getType() == material) block.setType(previousType, false);
            }, duration);
            ctx.triggerOutput("flow");
        });
        operations.put("find_blocks", (ctx, node) -> {
            Location center = requireLocation(ctx, node);
            int radius = requireBlockRadius(integer(ctx, node, "radius", 5));
            String materialName = string(ctx, node, "material", "any");
            List<Block> blocks = new ArrayList<>();
            Material material = parseMaterial(materialName, null);
            forEachBlock(center, radius, "cube", block -> {
                if (material == null || block.getType() == material) blocks.add(block);
            });
            ctx.setOutput(node, "blocks", blocks);
            ctx.setOutput(node, "count", blocks.size());
            ctx.triggerOutput("flow");
        });
        operations.put("break_blocks", (ctx, node) -> {
            Location center = requireLocation(ctx, node);
            int radius = requireBlockRadius(integer(ctx, node, "radius", 3));
            String materialName = string(ctx, node, "material", "any");
            boolean drops = bool(ctx, node, "drops", true);
            int maxBlocks = requireMaxBlocks(integer(ctx, node, "max_blocks", 128));
            int[] count = {0};
            Material material = parseMaterial(materialName, null);
            forEachBlock(center, radius, "sphere", block -> {
                if (count[0] >= maxBlocks || material != null && block.getType() != material || block.getType().isAir()) return;
                if (drops) block.breakNaturally();
                else block.setType(Material.AIR, false);
                count[0]++;
            });
            ctx.setOutput(node, "count", count[0]);
            ctx.triggerOutput("flow");
        });
        operations.put("replace_blocks", (ctx, node) -> {
            Location center = requireLocation(ctx, node);
            int radius = requireBlockRadius(integer(ctx, node, "radius", 3));
            Material from = parseMaterial(string(ctx, node, "from", "any"), null);
            Material to = parseMaterial(string(ctx, node, "to", "STONE"), Material.STONE);
            int maxBlocks = requireMaxBlocks(integer(ctx, node, "max_blocks", 128));
            int[] count = {0};
            if (to == null) throw new IllegalArgumentException("Replacement material cannot be a wildcard");
            forEachBlock(center, radius, "sphere", block -> {
                if (count[0] >= maxBlocks || from != null && block.getType() != from) return;
                block.setType(to, false);
                count[0]++;
            });
            ctx.setOutput(node, "count", count[0]);
            ctx.triggerOutput("flow");
        });
        operations.put("place_shape", (ctx, node) -> {
            Location center = requireLocation(ctx, node);
            int radius = requireBlockRadius(integer(ctx, node, "radius", 3));
            String shape = string(ctx, node, "shape", "sphere");
            Material material = parseMaterial(string(ctx, node, "material", "STONE"), Material.STONE);
            int maxBlocks = requireMaxBlocks(integer(ctx, node, "max_blocks", 128));
            boolean replaceAirOnly = bool(ctx, node, "air_only", true);
            int[] count = {0};
            if (material == null) throw new IllegalArgumentException("Shape material cannot be a wildcard");
            validateBlockShape(shape);
            forEachBlock(center, radius, shape, block -> {
                if (count[0] >= maxBlocks || replaceAirOnly && !block.getType().isAir()) return;
                block.setType(material, false);
                count[0]++;
            });
            ctx.setOutput(node, "count", count[0]);
            ctx.triggerOutput("flow");
        });
        operations.put("extinguish_area", (ctx, node) -> {
            Location center = requireLocation(ctx, node);
            int radius = requireBlockRadius(integer(ctx, node, "radius", 5));
            int[] count = {0};
            forEachBlock(center, radius, "sphere", block -> {
                if (block.getType() == Material.FIRE || block.getType() == Material.SOUL_FIRE) {
                    block.setType(Material.AIR, false);
                    count[0]++;
                }
            });
            ctx.setOutput(node, "count", count[0]);
            ctx.triggerOutput("flow");
        });
        operations.put("grow_block", (ctx, node) -> {
            Location location = requireLocation(ctx, node);
            int attempts = integer(ctx, node, "attempts", 1);
            if (attempts < 1 || attempts > 64) throw new IllegalArgumentException("Growth attempts must be between 1 and 64");
            boolean success = false;
            for (int i = 0; i < attempts; i++) {
                success |= location.getBlock().applyBoneMeal(BlockFace.UP);
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
            Location origin = requireLocation(ctx, node);
            String mode = string(ctx, node, "mode", "nearest");
            if (!mode.equalsIgnoreCase("nearest") && !mode.equalsIgnoreCase("farthest")) throw new IllegalArgumentException("Unknown entity sort mode: " + mode);
            entities.sort(Comparator.comparingDouble(entity -> entity.getLocation().distanceSquared(origin)));
            if ("farthest".equalsIgnoreCase(mode)) Collections.reverse(entities);
            ctx.setOutput(node, "entities", entities);
            ctx.triggerOutput("flow");
        });
        operations.put("limit_entities", (ctx, node) -> {
            List<Entity> entities = entityList(ctx, node);
            int limit = integer(ctx, node, "limit", 1);
            if (limit < 0) throw new IllegalArgumentException("Entity limit cannot be negative");
            List<Entity> limited = entities.stream().limit(limit).toList();
            ctx.setOutput(node, "entities", limited);
            ctx.setOutput(node, "count", limited.size());
            ctx.triggerOutput("flow");
        });
        operations.put("is_holding_content", (ctx, node) -> {
            Player player = requirePlayer(ctx, node, "player");
            requireCustomContentService();
            String contentId = string(ctx, node, "content_id", "");
            String hand = string(ctx, node, "hand", "any");
            ItemStack item = matchingHeldItem(player, contentId, hand);
            boolean matches = item != null;
            ctx.setOutput(node, "item", item);
            ctx.setOutput(node, "matches", matches);
            ctx.triggerOutput(matches ? "true" : "false");
        });
        operations.put("is_wearing_content", (ctx, node) -> {
            Player player = requirePlayer(ctx, node, "player");
            requireCustomContentService();
            String contentId = string(ctx, node, "content_id", "");
            String slot = string(ctx, node, "slot", "any");
            ItemStack item = matchingArmorItem(player, contentId, slot);
            boolean matches = item != null;
            ctx.setOutput(node, "item", item);
            ctx.setOutput(node, "matches", matches);
            ctx.triggerOutput(matches ? "true" : "false");
        });
        operations.put("has_content_set", (ctx, node) -> {
            Player player = requirePlayer(ctx, node, "player");
            requireCustomContentService();
            String prefix = string(ctx, node, "content_prefix", "");
            boolean matches = hasContentSet(player, prefix);
            ctx.setOutput(node, "matches", matches);
            ctx.triggerOutput(matches ? "true" : "false");
        });
        operations.put("custom_block_at", (ctx, node) -> {
            Location location = requireLocation(ctx, node);
            CustomContentService service = requireCustomContentService();
            String contentId = service.identifyBlock(location);
            CustomContentDefinition definition = definition(contentId);
            ctx.setOutput(node, "content_id", contentId);
            ctx.setOutput(node, "content_type", definition != null ? definition.getType() : "");
            ctx.setOutput(node, "exists", contentId != null);
            ctx.triggerOutput(contentId != null ? "true" : "false");
        });
        operations.put("trigger_content_ability", (ctx, node) -> {
            CustomContentService service = requireCustomContentService();
            String contentId = string(ctx, node, "content_id", "");
            String trigger = string(ctx, node, "trigger", "item.use");
            Player player = requirePlayer(ctx, node, "player");
            if (contentId.isBlank()) throw new IllegalArgumentException("Custom content ID is required");
            if (trigger.isBlank()) throw new IllegalArgumentException("Custom content trigger is required");
            Map<String, Object> vars = new HashMap<>(ctx.getRuntime().getEventVariables());
            vars.put("event.location", location(ctx, node));
            vars.put("event.target", target(ctx, node));
            service.dispatch(contentId, trigger, player, ctx.getEvent(), vars);
            ctx.setOutput(node, "success", true);
            ctx.triggerOutput("flow");
        });
        operations.put("cooldown_remaining", (ctx, node) -> {
            String key = cooldownKey(ctx, node);
            long remaining = Math.max(0L, cooldowns.getOrDefault(key, 0L) - System.currentTimeMillis());
            int ticks = (int) Math.ceil(remaining / 50.0);
            ctx.setOutput(node, "ticks", ticks);
            ctx.setOutput(node, "is_ready", ticks <= 0);
            ctx.triggerOutput(ticks <= 0 ? "ready" : "cooldown");
        });
        operations.put("set_cooldown", (ctx, node) -> {
            String key = cooldownKey(ctx, node);
            int ticks = integer(ctx, node, "ticks", 100);
            if (ticks < 0 || ticks > 72_000) throw new IllegalArgumentException("Cooldown ticks must be between 0 and 72000");
            cooldowns.put(key, System.currentTimeMillis() + ticks * 50L);
            ctx.triggerOutput("flow");
        });
        operations.put("get_charge", (ctx, node) -> {
            ItemStack item = requireItem(ctx, node);
            String key = string(ctx, node, "key", "charge");
            double value = getCharge(item, key);
            ctx.setOutput(node, "value", value);
            ctx.triggerOutput("flow");
        });
        operations.put("set_charge", (ctx, node) -> {
            ItemStack item = requireItem(ctx, node);
            String key = string(ctx, node, "key", "charge");
            double value = number(ctx, node, "value", 0.0);
            setCharge(item, key, value);
            ctx.setOutput(node, "item", item);
            ctx.setOutput(node, "value", value);
            ctx.triggerOutput("flow");
        });
        operations.put("add_charge", (ctx, node) -> {
            ItemStack item = requireItem(ctx, node);
            String key = string(ctx, node, "key", "charge");
            double value = getCharge(item, key) + number(ctx, node, "amount", 1.0);
            setCharge(item, key, value);
            ctx.setOutput(node, "item", item);
            ctx.setOutput(node, "value", value);
            ctx.triggerOutput("flow");
        });
        operations.put("consume_charge", (ctx, node) -> {
            ItemStack item = requireItem(ctx, node);
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
            requireEntityEffectMode(mode);
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
            Location center = requireAreaCenter(ctx, node);
            String mode = string(ctx, node, "mode", "damage");
            boolean worldEffect = isAreaWorldEffect(mode);
            if (!worldEffect) requireEntityEffectMode(mode);
            List<Entity> entities = worldEffect ? List.of() : areaEntities(ctx, node, center);
            int affected = 0;
            for (Entity entity : entities) {
                if (applyEntityEffect(ctx, node, entity, mode)) {
                    affected++;
                }
            }
            if (worldEffect) applyAreaWorldEffect(ctx, node, center, mode);
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
            Player player = requirePlayer(ctx, node, "player");
            ItemStack heldItem = requireItem(ctx, node);
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
            Entity target = requireTarget(ctx, node);
            String key = string(ctx, node, "key", "mark");
            int duration = integer(ctx, node, "duration_ticks", 100);
            requireDuration(duration);
            marks.put(markKey(target.getUniqueId(), key), System.currentTimeMillis() + duration * 50L);
            ctx.triggerOutput("flow");
        });
        operations.put("has_mark", (ctx, node) -> {
            Entity target = requireTarget(ctx, node);
            String key = string(ctx, node, "key", "mark");
            boolean active = isMarked(target.getUniqueId(), key);
            ctx.setOutput(node, "active", active);
            ctx.triggerOutput(active ? "true" : "false");
        });
        operations.put("remove_mark", (ctx, node) -> {
            Entity target = requireTarget(ctx, node);
            String key = string(ctx, node, "key", "mark");
            marks.remove(markKey(target.getUniqueId(), key));
            ctx.triggerOutput("flow");
        });
    }

    public void registerTo(HandlerRegistry registry) {
        registry.register("AbilityEffectHandler", this);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        restoreDisarmed(event.getPlayer());
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        restoreDisarmed(event.getPlayer());
    }

    @Override
    public void shutdown() {
        HandlerList.unregisterAll(this);
        for (UUID playerId : List.copyOf(disarmedItems.keySet())) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null) restoreDisarmed(player);
        }
        marks.clear();
        cooldowns.clear();
        if (!disarmedItems.isEmpty()) throw new IllegalStateException("Disarmed items could not be restored for players: " + disarmedItems.keySet());
    }

    @Override
    public void execute(FlowContext ctx, FlowNode node) {
        String operation = node.getHandlerConfig().getString("operation");
        BiConsumer<FlowContext, FlowNode> op = operation != null ? operations.get(operation) : null;
        if (op != null) {
            op.accept(ctx, node);
        } else {
            throw new IllegalArgumentException("Unknown ability effect operation: " + operation);
        }
    }

    private void disarm(FlowContext context, Player player, int duration) {
        PlayerInventory inventory = player.getInventory();
        ItemStack item = inventory.getItemInMainHand();
        if (item == null || item.getType().isAir()) throw new IllegalArgumentException("Target player is not holding an item");
        if (disarmedItems.putIfAbsent(player.getUniqueId(), item.clone()) != null) throw new IllegalStateException("Target player is already disarmed");
        inventory.setItemInMainHand(new ItemStack(Material.AIR));
        try {
            context.runLater(() -> restoreDisarmed(player), duration);
        } catch (RuntimeException exception) {
            restoreDisarmed(player);
            throw exception;
        }
    }

    private void restoreDisarmed(Player player) {
        if (player == null) return;
        ItemStack stored = disarmedItems.get(player.getUniqueId());
        if (stored == null) return;
        PlayerInventory inventory = player.getInventory();
        ItemStack current = inventory.getItemInMainHand();
        if (current == null || current.getType().isAir()) {
            inventory.setItemInMainHand(stored);
        } else {
            inventory.addItem(stored).values().forEach(leftover -> player.getWorld().dropItemNaturally(player.getLocation(), leftover));
        }
        disarmedItems.remove(player.getUniqueId(), stored);
    }

    private boolean cooldownReady(FlowContext ctx, FlowNode node, String fallbackKey) {
        int ticks = integer(ctx, node, "cooldown_ticks", 0);
        if (ticks < 0 || ticks > 72_000) throw new IllegalArgumentException("Cooldown ticks must be between 0 and 72000");
        if (ticks == 0) {
            return true;
        }
        String key = familyCooldownKey(ctx, node, fallbackKey);
        long now = System.currentTimeMillis();
        long readyAt = cooldowns.getOrDefault(key, 0L);
        if (readyAt > now) {
            ctx.setOutput(node, "cooldown_ticks_left", (int) Math.ceil((readyAt - now) / 50.0));
            return false;
        }
        cooldowns.put(key, now + ticks * 50L);
        ctx.setOutput(node, "cooldown_ticks_left", 0);
        return true;
    }

    private String familyCooldownKey(FlowContext ctx, FlowNode node, String fallbackKey) {
        String key = string(ctx, node, "cooldown_key", fallbackKey);
        String scope = string(ctx, node, "cooldown_scope", "player").toLowerCase(Locale.ROOT);
        return switch (scope) {
            case "global" -> key + ":global";
            case "content" -> key + ':' + String.valueOf(ctx.getRuntime().getEventVariables().getOrDefault("event.content_id", ""));
            case "target" -> key + ':' + requireTarget(ctx, node).getUniqueId();
            case "item", "item instance" -> key + ':' + String.valueOf(ctx.getRuntime().getEventVariables().getOrDefault("event.instance_id", ""));
            case "player" -> key + ':' + requirePlayer(ctx, node, "player").getUniqueId();
            default -> throw new IllegalArgumentException("Unknown cooldown scope: " + scope);
        };
    }

    private List<Entity> queryEntities(FlowContext ctx, FlowNode node) {
        String mode = string(ctx, node, "mode", "radius").toLowerCase(Locale.ROOT);
        return switch (mode) {
            case "radius", "nearest", "random" -> entitiesAround(ctx, node);
            case "cone" -> coneEntities(ctx, node);
            case "line" -> lineEntities(ctx, node);
            case "box" -> boxEntities(ctx, node);
            case "raycast" -> raycastEntity(ctx, node);
            default -> throw new IllegalArgumentException("Unknown entity query mode: " + mode);
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
        Location center = requireAreaCenter(ctx, node);
        return switch (select) {
            case "nearest" -> entities.stream().min(Comparator.comparingDouble(entity -> entity.getLocation().distanceSquared(center))).orElse(null);
            case "farthest" -> entities.stream().max(Comparator.comparingDouble(entity -> entity.getLocation().distanceSquared(center))).orElse(null);
            case "random" -> entities.get(ThreadLocalRandom.current().nextInt(entities.size()));
            case "lowest_health" -> entities.stream()
                .min(Comparator.comparingDouble(entity -> entity instanceof LivingEntity living ? living.getHealth() : Double.MAX_VALUE))
                .orElse(entities.getFirst());
            case "first" -> entities.getFirst();
            default -> throw new IllegalArgumentException("Unknown entity selection mode: " + select);
        };
    }

    private List<Entity> coneEntities(FlowContext ctx, FlowNode node) {
        Player player = ctx.getPlayer();
        Location origin = requireAreaCenter(ctx, node);
        Vector facing = direction(ctx, node);
        double range = number(ctx, node, "range", number(ctx, node, "radius", 8.0));
        double angle = number(ctx, node, "angle", 60.0);
        requireRadius(range);
        if (!Double.isFinite(angle) || angle < 0 || angle > 180) throw new IllegalArgumentException("Cone angle must be between 0 and 180");
        String targetFilter = string(ctx, node, "target_filter", "living_entity");
        boolean excludeCaster = bool(ctx, node, "exclude_caster", true);
        List<Entity> entities = new ArrayList<>();
        if (facing.lengthSquared() <= 0.0) throw new IllegalArgumentException("Cone direction cannot be zero");
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
        Location origin = requireAreaCenter(ctx, node);
        Vector facing = direction(ctx, node);
        double range = number(ctx, node, "range", number(ctx, node, "radius", 12.0));
        double width = number(ctx, node, "width", 1.0);
        requireRadius(range);
        if (!Double.isFinite(width) || width < 0 || width > 32) throw new IllegalArgumentException("Line width must be between 0 and 32");
        String targetFilter = string(ctx, node, "target_filter", "living_entity");
        boolean excludeCaster = bool(ctx, node, "exclude_caster", true);
        List<Entity> entities = new ArrayList<>();
        if (facing.lengthSquared() <= 0.0) throw new IllegalArgumentException("Line direction cannot be zero");
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
        Location center = requireAreaCenter(ctx, node);
        String targetFilter = string(ctx, node, "target_filter", "living_entity");
        boolean excludeCaster = bool(ctx, node, "exclude_caster", true);
        double width = number(ctx, node, "width", number(ctx, node, "radius", 5.0) * 2.0);
        double height = number(ctx, node, "height", width);
        double depth = number(ctx, node, "depth", width);
        if (!Double.isFinite(width) || !Double.isFinite(height) || !Double.isFinite(depth) || width < 0 || height < 0 || depth < 0 || width > 256 || height > 256 || depth > 256) throw new IllegalArgumentException("Entity box dimensions must be between 0 and 256");
        List<Entity> entities = new ArrayList<>();
        for (Entity entity : center.getWorld().getNearbyEntities(center, width * 0.5, height * 0.5, depth * 0.5)) {
            if (acceptsTarget(entity, targetFilter) && (!excludeCaster || entity != ctx.getPlayer())) {
                entities.add(entity);
            }
        }
        return limitEntities(ctx, node, sortEntities(ctx, node, entities));
    }

    private List<Entity> raycastEntity(FlowContext ctx, FlowNode node) {
        Player player = requirePlayer(ctx, node, "player");
        double range = number(ctx, node, "range", 20.0);
        requireRadius(range);
        String targetFilter = string(ctx, node, "target_filter", "living_entity");
        RayTraceResult result = player.getWorld().rayTraceEntities(player.getEyeLocation(), player.getEyeLocation().getDirection(), range, entity -> entity != player && acceptsTarget(entity, targetFilter));
        return result != null && result.getHitEntity() != null ? List.of(result.getHitEntity()) : List.of();
    }

    private List<Entity> areaEntities(FlowContext ctx, FlowNode node, Location center) {
        String shape = string(ctx, node, "shape", "sphere").toLowerCase(Locale.ROOT);
        Map<String, Object> inputs = new HashMap<>(node.getInputValues() != null ? node.getInputValues() : Map.of());
        inputs.put("mode", switch (shape) {
            case "sphere" -> "radius";
            case "cone" -> "cone";
            case "line" -> "line";
            case "box" -> "box";
            default -> throw new IllegalArgumentException("Unknown area shape: " + shape);
        });
        inputs.put("location", center);
        FlowNode queryNode = new FlowNode("ability.entity_query", node.getX(), node.getY(), inputs);
        return queryEntities(ctx, queryNode);
    }

    private List<Entity> sortEntities(FlowContext ctx, FlowNode node, List<Entity> entities) {
        String sort = string(ctx, node, "sort", "none").toLowerCase(Locale.ROOT);
        Location center = requireAreaCenter(ctx, node);
        List<Entity> sorted = new ArrayList<>(entities);
        if ("nearest".equals(sort) && center != null) {
            sorted.sort(Comparator.comparingDouble(entity -> entity.getLocation().distanceSquared(center)));
        } else if ("farthest".equals(sort) && center != null) {
            sorted.sort(Comparator.comparingDouble((Entity entity) -> entity.getLocation().distanceSquared(center)).reversed());
        } else if ("lowest_health".equals(sort)) {
            sorted.sort(Comparator.comparingDouble(entity -> entity instanceof LivingEntity living ? living.getHealth() : Double.MAX_VALUE));
        } else if ("random".equals(sort)) {
            Collections.shuffle(sorted);
        } else if (!"none".equals(sort)) {
            throw new IllegalArgumentException("Unknown entity sort mode: " + sort);
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
                marks.put(markKey(entity.getUniqueId(), "silenced"), System.currentTimeMillis() + integer(ctx, node, "duration_ticks", 100) * 50L);
                return true;
            }
            case "disarm" -> {
                if (entity instanceof Player player) {
                    disarm(ctx, player, requireDuration(integer(ctx, node, "duration_ticks", 60)));
                    return true;
                }
            }
            case "launch" -> {
                Vector vector = normalizedDirection(ctx, node).multiply(number(ctx, node, "strength", 1.0));
                vector.setY(Math.max(number(ctx, node, "upward_strength", 0.5), vector.getY()));
                FlowMutations.applyVelocity(ctx, entity, vector);
                return true;
            }
            case "pull" -> {
                Location center = requireAreaCenter(ctx, node);
                Vector vector = center.toVector().subtract(entity.getLocation().toVector());
                if (vector.lengthSquared() <= 0.0001) return false;
                FlowMutations.applyVelocity(ctx, entity, vector.normalize().multiply(number(ctx, node, "strength", 1.0)));
                return true;
            }
            case "push", "knockback" -> {
                Location center = requireAreaCenter(ctx, node);
                Vector vector = knockbackDirection(ctx, entity, center).multiply(number(ctx, node, "strength", 1.0));
                vector.setY(Math.max(number(ctx, node, "upward_strength", 0.2), vector.getY()));
                FlowMutations.applyVelocity(ctx, entity, vector);
                return true;
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
                marks.put(markKey(entity.getUniqueId(), string(ctx, node, "key", "mark")), System.currentTimeMillis() + integer(ctx, node, "duration_ticks", 100) * 50L);
                return true;
            }
            case "remove_mark" -> {
                marks.remove(markKey(entity.getUniqueId(), string(ctx, node, "key", "mark")));
                return true;
            }
            case "life_steal" -> {
                Player player = requirePlayer(ctx, node, "player");
                double amount = number(ctx, node, "amount", ctx.getEvent() instanceof EntityDamageEvent event ? event.getDamage() : 1.0);
                FlowMutations.heal(ctx, player, amount);
                return true;
            }
            default -> throw new IllegalArgumentException("Unknown entity effect mode: " + mode);
        }
        return false;
    }

    private void applyAreaWorldEffect(FlowContext ctx, FlowNode node, Location center, String mode) {
        if (center == null || center.getWorld() == null) throw new IllegalArgumentException("Area effect center is required");
        if (mode == null || mode.isBlank()) throw new IllegalArgumentException("Area effect mode is required");
        String normalized = mode.toLowerCase(Locale.ROOT);
        if ("particle".equals(normalized)) {
            int count = integer(ctx, node, "count", 20);
            double radius = number(ctx, node, "radius", 3.0);
            double speed = number(ctx, node, "speed", 0.0);
            if (count < 1 || count > 10_000) throw new IllegalArgumentException("Area particle count must be between 1 and 10000");
            requireRadius(radius);
            if (!Double.isFinite(speed) || speed < 0) throw new IllegalArgumentException("Area particle speed must be a finite non-negative number");
            center.getWorld().spawnParticle(parseParticle(string(ctx, node, "particle", "FLAME")), center, count, radius, radius, radius, speed);
            return;
        }
        if ("sound".equals(normalized)) {
            float volume = (float) number(ctx, node, "volume", 1.0);
            float pitch = (float) number(ctx, node, "pitch", 1.0);
            if (!Float.isFinite(volume) || volume < 0) throw new IllegalArgumentException("Area sound volume must be a finite non-negative number");
            if (!Float.isFinite(pitch) || pitch < 0.5f || pitch > 2.0f) throw new IllegalArgumentException("Area sound pitch must be between 0.5 and 2.0");
            center.getWorld().playSound(center, parseSound(string(ctx, node, "sound", "ENTITY_EXPERIENCE_ORB_PICKUP")), volume, pitch);
            return;
        }
        if ("extinguish".equals(normalized)) {
            int radius = requireBlockRadius(integer(ctx, node, "radius", 5));
            forEachBlock(center, radius, "sphere", block -> {
                if (block.getType() == Material.FIRE || block.getType() == Material.SOUL_FIRE) {
                    block.setType(Material.AIR, false);
                }
            });
            return;
        }
        throw new IllegalArgumentException("Unknown area world effect mode: " + mode);
    }

    private static boolean isAreaWorldEffect(String mode) {
        if (mode == null) return false;
        return switch (mode.toLowerCase(Locale.ROOT)) {
            case "particle", "sound", "extinguish" -> true;
            default -> false;
        };
    }

    private static void requireEntityEffectMode(String mode) {
        if (mode == null || mode.isBlank()) throw new IllegalArgumentException("Entity effect mode is required");
        switch (mode.toLowerCase(Locale.ROOT)) {
            case "damage", "heal", "potion", "ignite", "freeze", "stun", "root", "silence", "disarm", "launch", "pull", "push", "knockback", "set_velocity", "no_damage_ticks", "shield", "mark", "remove_mark", "life_steal" -> {
                return;
            }
            default -> throw new IllegalArgumentException("Unknown entity effect mode: " + mode);
        }
    }

    private boolean applyHoldingEffect(FlowContext ctx, FlowNode node, Player player, ItemStack heldItem, String mode) {
        if (player == null) throw new IllegalArgumentException("Holding effect player is required");
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
            default -> throw new IllegalArgumentException("Unknown holding effect mode: " + mode);
        }
    }

    private void moveArea(FlowContext ctx, FlowNode node, int direction) {
        Location location = requireAreaCenter(ctx, node);
        double radius = number(ctx, node, "radius", 5.0);
        double strength = number(ctx, node, "strength", 1.0);
        String targetFilter = string(ctx, node, "target_filter", "any");
        boolean excludeCaster = bool(ctx, node, "exclude_caster", true);
        requireRadius(radius);
        for (Entity entity : location.getWorld().getNearbyEntities(location, radius, radius, radius)) {
            if (!acceptsTarget(entity, targetFilter) || excludeCaster && entity == ctx.getPlayer()) {
                continue;
            }
            Vector vector = direction > 0 ? knockbackDirection(ctx, entity, location) : location.toVector().subtract(entity.getLocation().toVector());
            if (vector.lengthSquared() > 0.0001) {
                FlowMutations.applyVelocity(ctx, entity, vector.normalize().multiply(strength));
            }
        }
        ctx.triggerOutput("flow");
    }

    private LeapResult leapToLocation(FlowContext ctx, FlowNode node, Entity target, Location requestedLocation) {
        Location start = target.getLocation();
        if (start.getWorld() == null || requestedLocation.getWorld() == null || !start.getWorld().equals(requestedLocation.getWorld())) {
            throw new IllegalArgumentException("Leap target and destination must be in the same world");
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
        Location location = requireAreaCenter(ctx, node);
        double radius = number(ctx, node, "radius", 8.0);
        requireRadius(radius);
        String targetFilter = string(ctx, node, "target_filter", "living_entity");
        boolean excludeCaster = bool(ctx, node, "exclude_caster", true);
        List<Entity> entities = new ArrayList<>();
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

    private Location requireAreaCenter(FlowContext context, FlowNode node) {
        Location center = areaCenter(context, node);
        if (center == null || center.getWorld() == null) throw new IllegalArgumentException("Ability area center is required");
        return center;
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

    private Location requireLocation(FlowContext context, FlowNode node) {
        Location value = location(context, node);
        if (value == null || value.getWorld() == null) throw new IllegalArgumentException("Ability location is required");
        return value;
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

    private Entity requireTarget(FlowContext context, FlowNode node) {
        Entity value = target(context, node);
        if (value == null) throw new IllegalArgumentException("Ability target is required");
        return value;
    }

    private LivingEntity requireLivingTarget(FlowContext context, FlowNode node) {
        Entity value = requireTarget(context, node);
        if (!(value instanceof LivingEntity livingEntity)) throw new IllegalArgumentException("Ability target must be a living entity");
        return livingEntity;
    }

    private Player requirePlayer(FlowContext context, FlowNode node, String input) {
        Player player = context.getInputValue(node, input, Player.class, context.getPlayer());
        if (player == null) throw new IllegalArgumentException("Ability player is required");
        return player;
    }

    private ItemStack requireItem(FlowContext context, FlowNode node) {
        ItemStack value = item(context, node);
        if (value == null || value.getType().isAir()) throw new IllegalArgumentException("Ability item is required");
        return value;
    }

    private CustomContentService requireCustomContentService() {
        CustomContentService service = CustomContentAccess.getService();
        if (service == null) throw new IllegalStateException("Custom content service is unavailable");
        return service;
    }

    private void requireRadius(double radius) {
        if (!Double.isFinite(radius) || radius < 0 || radius > 128) throw new IllegalArgumentException("Ability radius must be between 0 and 128");
    }

    private int requireBlockRadius(int radius) {
        if (radius < 0 || radius > 16) throw new IllegalArgumentException("Block ability radius must be between 0 and 16");
        return radius;
    }

    private int requireMaxBlocks(int maxBlocks) {
        if (maxBlocks < 1 || maxBlocks > 10_000) throw new IllegalArgumentException("Maximum block count must be between 1 and 10000");
        return maxBlocks;
    }

    private int requireDuration(int ticks) {
        if (ticks < 1 || ticks > 72_000) throw new IllegalArgumentException("Ability duration must be between 1 and 72000 ticks");
        return ticks;
    }

    private void requireEffectValues(int duration, int amplifier) {
        requireDuration(duration);
        if (amplifier < 0 || amplifier > 255) throw new IllegalArgumentException("Potion amplifier must be between 0 and 255");
    }

    private void validateBlockShape(String shape) {
        if (!"cube".equalsIgnoreCase(shape) && !"sphere".equalsIgnoreCase(shape) && !"cylinder".equalsIgnoreCase(shape)) throw new IllegalArgumentException("Unknown block shape: " + shape);
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
            case "any" -> true;
            case "player" -> entity instanceof Player;
            case "living_entity", "living entity", "living" -> entity instanceof LivingEntity;
            case "hostile" -> entity instanceof Monster;
            case "passive" -> entity instanceof Animals;
            default -> throw new IllegalArgumentException("Unknown entity target filter: " + filter);
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
        particleHandler.executeInline(ctx, particleNode);
        ctx.triggerOutput("flow");
    }

    private Particle parseParticle(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Particle is required");
        }
        try {
            return Particle.valueOf(name.toUpperCase(Locale.ROOT).replace('.', '_'));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unknown particle: " + name, exception);
        }
    }

    private Sound parseSound(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Sound is required");
        }
        try {
            return Sound.valueOf(name.toUpperCase(Locale.ROOT).replace('.', '_'));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unknown sound: " + name, exception);
        }
    }

    private Material parseMaterial(String name, Material fallback) {
        if (name == null || name.isBlank()) {
            if (fallback == null) {
                throw new IllegalArgumentException("Material is required");
            }
            return fallback;
        }
        if (name.equalsIgnoreCase("any") || name.equals("*")) {
            return null;
        }
        Material material = Material.matchMaterial(name);
        if (material == null) {
            throw new IllegalArgumentException("Unknown material: " + name);
        }
        return material;
    }

    private void addEffect(LivingEntity living, String name, int duration, int amplifier) {
        PotionEffectType type = requirePotionEffect(name);
        requireEffectValues(duration, amplifier);
        living.addPotionEffect(new PotionEffect(type, duration, amplifier, false, false, false));
    }

    private PotionEffectType requirePotionEffect(String name) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Potion effect is required");
        PotionEffectType type = PotionEffectType.getByName(name.toUpperCase(Locale.ROOT));
        if (type == null) throw new IllegalArgumentException("Unknown potion effect: " + name);
        return type;
    }

    private Projectile launchProjectile(Player player, String projectileType) {
        String normalized = projectileType == null ? "ARROW" : projectileType.toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "SNOWBALL" -> player.launchProjectile(Snowball.class);
            case "EGG" -> player.launchProjectile(Egg.class);
            case "TRIDENT" -> player.launchProjectile(Trident.class);
            case "FIREBALL", "SMALL_FIREBALL" -> player.launchProjectile(SmallFireball.class);
            case "ARROW" -> player.launchProjectile(Arrow.class);
            default -> throw new IllegalArgumentException("Unknown projectile type: " + projectileType);
        };
    }

    private AbstractArrow.PickupStatus pickupStatus(String pickupMode) {
        String normalized = pickupMode == null ? "allowed" : pickupMode.toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "creative_only", "creative only" -> AbstractArrow.PickupStatus.CREATIVE_ONLY;
            case "disallowed", "none" -> AbstractArrow.PickupStatus.DISALLOWED;
            case "allowed" -> AbstractArrow.PickupStatus.ALLOWED;
            default -> throw new IllegalArgumentException("Unknown projectile pickup mode: " + pickupMode);
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
        if (world == null) throw new IllegalArgumentException("Block operation center must have a world");
        validateBlockShape(shape);
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
        if (world == null) throw new IllegalArgumentException("Particle shape center must have a world");
        if (count < 1 || count > 10_000) throw new IllegalArgumentException("Particle shape count must be between 1 and 10000");
        requireRadius(radius);
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
        if (!"ring".equals(normalized) && !"orbit".equals(normalized)) throw new IllegalArgumentException("Unknown particle shape mode: " + mode);
        for (int i = 0; i < count; i++) {
            double angle = Math.PI * 2.0 * i / count;
            double y = "orbit".equals(normalized) ? Math.sin(angle * 2.0) * radius * 0.5 : 0.0;
            Location point = center.clone().add(Math.cos(angle) * radius, y, Math.sin(angle) * radius);
            world.spawnParticle(particle, point, 1, 0, 0, 0, speed);
        }
    }

    private List<Entity> entityList(FlowContext ctx, FlowNode node) {
        String source = string(ctx, node, "target_source", "event target").toLowerCase(Locale.ROOT);
        return switch (source) {
            case "entities" -> connectedEntityList(ctx, node);
            case "target" -> List.of(requireTarget(ctx, node));
            case "event target" -> {
                List<Entity> entities = connectedEntityList(ctx, node);
                yield !entities.isEmpty() ? entities : List.of(requireTarget(ctx, node));
            }
            default -> throw new IllegalArgumentException("Unknown entity target source: " + source);
        };
    }

    private List<Entity> connectedEntityList(FlowContext ctx, FlowNode node) {
        Object value = ctx.getInputValue(node, "entities");
        if (value instanceof Collection<?> collection) {
            List<Entity> entities = new ArrayList<>();
            for (Object entry : collection) {
                if (!(entry instanceof Entity entity)) throw new IllegalArgumentException("Entity collection contains a non-entity value");
                entities.add(entity);
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
        if (!"any".equals(normalized) && !"offhand".equals(normalized) && !"main hand".equals(normalized) && !"main_hand".equals(normalized)) throw new IllegalArgumentException("Unknown equipment hand: " + hand);
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
            if (!armor.containsKey(normalized)) throw new IllegalArgumentException("Unknown armor slot: " + slot);
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
            case "player" -> key + ':' + requirePlayer(ctx, node, "player").getUniqueId();
            default -> throw new IllegalArgumentException("Unknown cooldown scope: " + scope);
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
        if (item == null || item.getType().isAir()) throw new IllegalArgumentException("Charge item is required");
        if (!Double.isFinite(value)) throw new IllegalArgumentException("Charge value must be finite");
        ItemMeta meta = item.getItemMeta();
        if (meta == null) throw new IllegalArgumentException("Item does not support charge metadata");
        PersistentDataContainer container = meta.getPersistentDataContainer();
        container.set(chargeKey(key), PersistentDataType.DOUBLE, value);
        item.setItemMeta(meta);
    }

    private NamespacedKey chargeKey(String key) {
        String safeKey = (key == null || key.isBlank() ? "charge" : key).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_./-]", "_");
        return new NamespacedKey(ReSync.getInstance(), "ability_" + safeKey);
    }

    private String markKey(UUID uuid, String key) {
        if (uuid == null) throw new IllegalArgumentException("Mark target identity is required");
        if (key == null || key.isBlank()) throw new IllegalArgumentException("Mark key is required");
        return uuid + ":" + key.toLowerCase(Locale.ROOT);
    }

    private boolean isMarked(UUID uuid, String key) {
        String markKey = markKey(uuid, key);
        Long expiresAt = marks.get(markKey);
        if (expiresAt == null) {
            return false;
        }
        if (expiresAt < System.currentTimeMillis()) {
            marks.remove(markKey);
            return false;
        }
        return true;
    }
}
