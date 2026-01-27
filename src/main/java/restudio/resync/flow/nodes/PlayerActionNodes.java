package restudio.resync.flow.nodes;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;
import restudio.flow.data.FlowNode;
import restudio.resync.ReSync;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.FlowRegistry;
import restudio.resync.flow.NodeCategory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PlayerActionNodes implements NodeCategory {

    private static final Map<String, BossBar> bossBars = new ConcurrentHashMap<>();

    @Override
    public void registerNodes(FlowRegistry registry) {
        registry.register("player_sprint", (ctx, node) -> {
            Player target = ctx.getInputValue(node, "target", Player.class, null);
            if (target == null) {
                ctx.triggerOutput("flow");
                return;
            }
            Boolean enabled = ctx.getInputValue(node, "enabled", Boolean.class, true);
            if (Bukkit.isPrimaryThread()) {
                target.setSprinting(enabled);
            } else {
                Bukkit.getScheduler().runTask(ReSync.getInstance(), () -> target.setSprinting(enabled));
            }
            ctx.triggerOutput("flow");
        });

        registry.register("player_sneak", (ctx, node) -> {
            Player target = ctx.getInputValue(node, "target", Player.class, null);
            if (target == null) {
                ctx.triggerOutput("flow");
                return;
            }
            Boolean enabled = ctx.getInputValue(node, "enabled", Boolean.class, true);
            if (Bukkit.isPrimaryThread()) {
                target.setSneaking(enabled);
            } else {
                Bukkit.getScheduler().runTask(ReSync.getInstance(), () -> target.setSneaking(enabled));
            }
            ctx.triggerOutput("flow");
        });

        registry.register("player_fly", (ctx, node) -> {
            Player target = ctx.getInputValue(node, "target", Player.class, null);
            if (target == null) {
                ctx.triggerOutput("flow");
                return;
            }
            Boolean enabled = ctx.getInputValue(node, "enabled", Boolean.class, true);
            if (Bukkit.isPrimaryThread()) {
                target.setAllowFlight(enabled);
                target.setFlying(enabled);
            } else {
                Bukkit.getScheduler().runTask(ReSync.getInstance(), () -> {
                    target.setAllowFlight(enabled);
                    target.setFlying(enabled);
                });
            }
            ctx.triggerOutput("flow");
        });

        registry.register("player_gamemode", (ctx, node) -> {
            Player target = ctx.getInputValue(node, "target", Player.class, null);
            if (target == null) {
                ctx.triggerOutput("flow");
                return;
            }
            String modeName = ctx.getInputValue(node, "mode", String.class, "SURVIVAL");
            try {
                GameMode mode = GameMode.valueOf(modeName.toUpperCase());
                if (Bukkit.isPrimaryThread()) {
                    target.setGameMode(mode);
                } else {
                    Bukkit.getScheduler().runTask(ReSync.getInstance(), () -> target.setGameMode(mode));
                }
            } catch (IllegalArgumentException e) {
            }
            ctx.triggerOutput("flow");
        });

        registry.register("player_vanish", (ctx, node) -> {
            Player target = ctx.getInputValue(node, "target", Player.class, null);
            if (target == null) {
                ctx.triggerOutput("flow");
                return;
            }
            Boolean enabled = ctx.getInputValue(node, "enabled", Boolean.class, true);
            if (Bukkit.isPrimaryThread()) {
                target.setInvisible(enabled);
            } else {
                Bukkit.getScheduler().runTask(ReSync.getInstance(), () -> target.setInvisible(enabled));
            }
            ctx.triggerOutput("flow");
        });

        registry.register("player_glowing", (ctx, node) -> {
            Player target = ctx.getInputValue(node, "target", Player.class, null);
            if (target == null) {
                ctx.triggerOutput("flow");
                return;
            }
            Boolean enabled = ctx.getInputValue(node, "enabled", Boolean.class, true);
            if (Bukkit.isPrimaryThread()) {
                target.setGlowing(enabled);
            } else {
                Bukkit.getScheduler().runTask(ReSync.getInstance(), () -> target.setGlowing(enabled));
            }
            ctx.triggerOutput("flow");
        });

        registry.register("player_invulnerable", (ctx, node) -> {
            Player target = ctx.getInputValue(node, "target", Player.class, null);
            if (target == null) {
                ctx.triggerOutput("flow");
                return;
            }
            Boolean enabled = ctx.getInputValue(node, "enabled", Boolean.class, true);
            if (Bukkit.isPrimaryThread()) {
                target.setInvulnerable(enabled);
            } else {
                Bukkit.getScheduler().runTask(ReSync.getInstance(), () -> target.setInvulnerable(enabled));
            }
            ctx.triggerOutput("flow");
        });

        registry.register("player_food_level", (ctx, node) -> {
            Player target = ctx.getInputValue(node, "target", Player.class, null);
            if (target == null) {
                ctx.triggerOutput("flow");
                return;
            }
            Integer level = ctx.getInputValue(node, "level", Integer.class, 20);
            if (Bukkit.isPrimaryThread()) {
                target.setFoodLevel(Math.max(0, Math.min(20, level)));
            } else {
                Bukkit.getScheduler().runTask(ReSync.getInstance(), () -> target.setFoodLevel(Math.max(0, Math.min(20, level))));
            }
            ctx.triggerOutput("flow");
        });

        registry.register("player_saturation", (ctx, node) -> {
            Player target = ctx.getInputValue(node, "target", Player.class, null);
            if (target == null) {
                ctx.triggerOutput("flow");
                return;
            }
            Float saturation = ctx.getInputValue(node, "saturation", Float.class, 20.0f);
            if (Bukkit.isPrimaryThread()) {
                target.setSaturation(Math.max(0, Math.min(20, saturation)));
            } else {
                Bukkit.getScheduler().runTask(ReSync.getInstance(), () -> target.setSaturation(Math.max(0, Math.min(20, saturation))));
            }
            ctx.triggerOutput("flow");
        });

        registry.register("player_exhaustion", (ctx, node) -> {
            Player target = ctx.getInputValue(node, "target", Player.class, null);
            if (target == null) {
                ctx.triggerOutput("flow");
                return;
            }
            Float exhaustion = ctx.getInputValue(node, "exhaustion", Float.class, 0.0f);
            if (Bukkit.isPrimaryThread()) {
                target.setExhaustion(Math.max(0, exhaustion));
            } else {
                Bukkit.getScheduler().runTask(ReSync.getInstance(), () -> target.setExhaustion(Math.max(0, exhaustion)));
            }
            ctx.triggerOutput("flow");
        });

        registry.register("player_health", (ctx, node) -> {
            Player target = ctx.getInputValue(node, "target", Player.class, null);
            if (target == null) {
                ctx.triggerOutput("flow");
                return;
            }
            Double health = ctx.getInputValue(node, "health", Double.class, 20.0);
            if (Bukkit.isPrimaryThread()) {
                target.setHealth(Math.max(0, Math.min(target.getMaxHealth(), health)));
            } else {
                Bukkit.getScheduler().runTask(ReSync.getInstance(), () -> target.setHealth(Math.max(0, Math.min(target.getMaxHealth(), health))));
            }
            ctx.triggerOutput("flow");
        });

        registry.register("player_max_health", (ctx, node) -> {
            Player target = ctx.getInputValue(node, "target", Player.class, null);
            if (target == null) {
                ctx.triggerOutput("flow");
                return;
            }
            Double maxHealth = ctx.getInputValue(node, "max_health", Double.class, 20.0);
            if (Bukkit.isPrimaryThread()) {
                target.setMaxHealth(Math.max(1, maxHealth));
            } else {
                Bukkit.getScheduler().runTask(ReSync.getInstance(), () -> target.setMaxHealth(Math.max(1, maxHealth)));
            }
            ctx.triggerOutput("flow");
        });

        registry.register("player_absorption", (ctx, node) -> {
            Player target = ctx.getInputValue(node, "target", Player.class, null);
            if (target == null) {
                ctx.triggerOutput("flow");
                return;
            }
            Double absorption = ctx.getInputValue(node, "absorption", Double.class, 0.0);
            if (Bukkit.isPrimaryThread()) {
                target.setAbsorptionAmount(Math.max(0, absorption));
            } else {
                Bukkit.getScheduler().runTask(ReSync.getInstance(), () -> target.setAbsorptionAmount(Math.max(0, absorption)));
            }
            ctx.triggerOutput("flow");
        });

        registry.register("player_xp", (ctx, node) -> {
            Player target = ctx.getInputValue(node, "target", Player.class, null);
            if (target == null) {
                ctx.triggerOutput("flow");
                return;
            }
            Integer level = ctx.getInputValue(node, "level", Integer.class, 0);
            Float points = ctx.getInputValue(node, "points", Float.class, 0.0f);
            if (Bukkit.isPrimaryThread()) {
                target.setLevel(Math.max(0, level));
                target.setExp(Math.max(0, Math.min(1, points)));
            } else {
                Bukkit.getScheduler().runTask(ReSync.getInstance(), () -> {
                    target.setLevel(Math.max(0, level));
                    target.setExp(Math.max(0, Math.min(1, points)));
                });
            }
            ctx.triggerOutput("flow");
        });

        registry.register("player_tp", (ctx, node) -> {
            Player target = ctx.getInputValue(node, "target", Player.class, null);
            if (target == null) {
                ctx.triggerOutput("flow");
                return;
            }
            final Location location;
            Location loc = ctx.getInputValue(node, "location", Location.class, null);
            if (loc == null) {
                Double x = ctx.getInputValue(node, "x", Double.class, target.getLocation().getX());
                Double y = ctx.getInputValue(node, "y", Double.class, target.getLocation().getY());
                Double z = ctx.getInputValue(node, "z", Double.class, target.getLocation().getZ());
                Float yaw = ctx.getInputValue(node, "yaw", Float.class, target.getLocation().getYaw());
                Float pitch = ctx.getInputValue(node, "pitch", Float.class, target.getLocation().getPitch());
                location = new Location(target.getWorld(), x, y, z, yaw, pitch);
            } else {
                location = loc;
            }
            if (Bukkit.isPrimaryThread()) {
                target.teleport(location);
            } else {
                Bukkit.getScheduler().runTask(ReSync.getInstance(), () -> target.teleport(location));
            }
            ctx.triggerOutput("flow");
        });

        registry.register("player_launch", (ctx, node) -> {
            Player target = ctx.getInputValue(node, "target", Player.class, null);
            if (target == null) {
                ctx.triggerOutput("flow");
                return;
            }
            Double vx = ctx.getInputValue(node, "vx", Double.class, 0.0);
            Double vy = ctx.getInputValue(node, "vy", Double.class, 0.0);
            Double vz = ctx.getInputValue(node, "vz", Double.class, 0.0);
            if (Bukkit.isPrimaryThread()) {
                target.setVelocity(new Vector(vx, vy, vz));
            } else {
                Bukkit.getScheduler().runTask(ReSync.getInstance(), () -> target.setVelocity(new Vector(vx, vy, vz)));
            }
            ctx.triggerOutput("flow");
        });

        registry.register("player_push", (ctx, node) -> {
            Player target = ctx.getInputValue(node, "target", Player.class, null);
            if (target == null) {
                ctx.triggerOutput("flow");
                return;
            }
            Double strength = ctx.getInputValue(node, "strength", Double.class, 1.0);
            if (Bukkit.isPrimaryThread()) {
                Vector direction = target.getLocation().getDirection();
                target.setVelocity(direction.multiply(strength));
            } else {
                Bukkit.getScheduler().runTask(ReSync.getInstance(), () -> {
                    Vector direction = target.getLocation().getDirection();
                    target.setVelocity(direction.multiply(strength));
                });
            }
            ctx.triggerOutput("flow");
        });

        registry.register("player_spin", (ctx, node) -> {
            Player target = ctx.getInputValue(node, "target", Player.class, null);
            if (target == null) {
                ctx.triggerOutput("flow");
                return;
            }
            Float yaw = ctx.getInputValue(node, "yaw", Float.class, 0.0f);
            Float pitch = ctx.getInputValue(node, "pitch", Float.class, 0.0f);
            if (Bukkit.isPrimaryThread()) {
                Location loc = target.getLocation();
                loc.setYaw(loc.getYaw() + yaw);
                loc.setPitch(loc.getPitch() + pitch);
                target.teleport(loc);
            } else {
                Bukkit.getScheduler().runTask(ReSync.getInstance(), () -> {
                    Location loc = target.getLocation();
                    loc.setYaw(loc.getYaw() + yaw);
                    loc.setPitch(loc.getPitch() + pitch);
                    target.teleport(loc);
                });
            }
            ctx.triggerOutput("flow");
        });

        registry.register("player_set_rotation", (ctx, node) -> {
            Player target = ctx.getInputValue(node, "target", Player.class, null);
            if (target == null) {
                ctx.triggerOutput("flow");
                return;
            }
            Float yaw = ctx.getInputValue(node, "yaw", Float.class, target.getLocation().getYaw());
            Float pitch = ctx.getInputValue(node, "pitch", Float.class, target.getLocation().getPitch());
            if (Bukkit.isPrimaryThread()) {
                Location loc = target.getLocation();
                loc.setYaw(yaw);
                loc.setPitch(pitch);
                target.teleport(loc);
            } else {
                Bukkit.getScheduler().runTask(ReSync.getInstance(), () -> {
                    Location loc = target.getLocation();
                    loc.setYaw(yaw);
                    loc.setPitch(pitch);
                    target.teleport(loc);
                });
            }
            ctx.triggerOutput("flow");
        });

        registry.register("player_allow_flight", (ctx, node) -> {
            Player target = ctx.getInputValue(node, "target", Player.class, null);
            if (target == null) {
                ctx.triggerOutput("flow");
                return;
            }
            Boolean allowed = ctx.getInputValue(node, "allowed", Boolean.class, true);
            if (Bukkit.isPrimaryThread()) {
                target.setAllowFlight(allowed);
            } else {
                Bukkit.getScheduler().runTask(ReSync.getInstance(), () -> target.setAllowFlight(allowed));
            }
            ctx.triggerOutput("flow");
        });

        registry.register("player_deny_flight", (ctx, node) -> {
            Player target = ctx.getInputValue(node, "target", Player.class, null);
            if (target == null) {
                ctx.triggerOutput("flow");
                return;
            }
            if (Bukkit.isPrimaryThread()) {
                target.setAllowFlight(false);
                target.setFlying(false);
            } else {
                Bukkit.getScheduler().runTask(ReSync.getInstance(), () -> {
                    target.setAllowFlight(false);
                    target.setFlying(false);
                });
            }
            ctx.triggerOutput("flow");
        });

        registry.register("player_set_walk_speed", (ctx, node) -> {
            Player target = ctx.getInputValue(node, "target", Player.class, null);
            if (target == null) {
                ctx.triggerOutput("flow");
                return;
            }
            Float speed = ctx.getInputValue(node, "speed", Float.class, 0.2f);
            if (Bukkit.isPrimaryThread()) {
                target.setWalkSpeed(Math.max(-1, Math.min(1, speed)));
            } else {
                Bukkit.getScheduler().runTask(ReSync.getInstance(), () -> target.setWalkSpeed(Math.max(-1, Math.min(1, speed))));
            }
            ctx.triggerOutput("flow");
        });

        registry.register("player_set_fly_speed", (ctx, node) -> {
            Player target = ctx.getInputValue(node, "target", Player.class, null);
            if (target == null) {
                ctx.triggerOutput("flow");
                return;
            }
            Float speed = ctx.getInputValue(node, "speed", Float.class, 0.1f);
            if (Bukkit.isPrimaryThread()) {
                target.setFlySpeed(Math.max(-1, Math.min(1, speed)));
            } else {
                Bukkit.getScheduler().runTask(ReSync.getInstance(), () -> target.setFlySpeed(Math.max(-1, Math.min(1, speed))));
            }
            ctx.triggerOutput("flow");
        });

        registry.register("player_freeze", (ctx, node) -> {
            Player target = ctx.getInputValue(node, "target", Player.class, null);
            if (target == null) {
                ctx.triggerOutput("flow");
                return;
            }
            if (Bukkit.isPrimaryThread()) {
                target.setWalkSpeed(0);
                target.setFlySpeed(0);
            } else {
                Bukkit.getScheduler().runTask(ReSync.getInstance(), () -> {
                    target.setWalkSpeed(0);
                    target.setFlySpeed(0);
                });
            }
            ctx.triggerOutput("flow");
        });

        registry.register("player_unfreeze", (ctx, node) -> {
            Player target = ctx.getInputValue(node, "target", Player.class, null);
            if (target == null) {
                ctx.triggerOutput("flow");
                return;
            }
            if (Bukkit.isPrimaryThread()) {
                target.setWalkSpeed(0.2f);
                target.setFlySpeed(0.1f);
            } else {
                Bukkit.getScheduler().runTask(ReSync.getInstance(), () -> {
                    target.setWalkSpeed(0.2f);
                    target.setFlySpeed(0.1f);
                });
            }
            ctx.triggerOutput("flow");
        });

        registry.register("player_set_fire_ticks", (ctx, node) -> {
            Player target = ctx.getInputValue(node, "target", Player.class, null);
            if (target == null) {
                ctx.triggerOutput("flow");
                return;
            }
            Integer ticks = ctx.getInputValue(node, "ticks", Integer.class, 0);
            if (Bukkit.isPrimaryThread()) {
                target.setFireTicks(ticks);
            } else {
                Bukkit.getScheduler().runTask(ReSync.getInstance(), () -> target.setFireTicks(ticks));
            }
            ctx.triggerOutput("flow");
        });

        registry.register("player_set_air_ticks", (ctx, node) -> {
            Player target = ctx.getInputValue(node, "target", Player.class, null);
            if (target == null) {
                ctx.triggerOutput("flow");
                return;
            }
            Integer ticks = ctx.getInputValue(node, "ticks", Integer.class, 300);
            if (Bukkit.isPrimaryThread()) {
                target.setRemainingAir(Math.max(-20, ticks));
            } else {
                Bukkit.getScheduler().runTask(ReSync.getInstance(), () -> target.setRemainingAir(Math.max(-20, ticks)));
            }
            ctx.triggerOutput("flow");
        });

        registry.register("player_set_no_damage_ticks", (ctx, node) -> {
            Player target = ctx.getInputValue(node, "target", Player.class, null);
            if (target == null) {
                ctx.triggerOutput("flow");
                return;
            }
            Integer ticks = ctx.getInputValue(node, "ticks", Integer.class, 0);
            if (Bukkit.isPrimaryThread()) {
                target.setNoDamageTicks(ticks);
            } else {
                Bukkit.getScheduler().runTask(ReSync.getInstance(), () -> target.setNoDamageTicks(ticks));
            }
            ctx.triggerOutput("flow");
        });

        registry.register("player_add_potion", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            if (player == null) {
                ctx.triggerOutput("flow");
                return;
            }
            String effectType = ctx.getInputValue(node, "effect_type", String.class, "SPEED");
            Integer duration = ctx.getInputValue(node, "duration_ticks", Integer.class, 600);
            Integer amplifier = ctx.getInputValue(node, "amplifier", Integer.class, 0);
            PotionEffectType type = PotionEffectType.getByName(effectType.toUpperCase());
            if (type != null) {
                PotionEffect effect = new PotionEffect(type, Math.max(0, duration), Math.max(0, amplifier));
                if (Bukkit.isPrimaryThread()) {
                    player.addPotionEffect(effect);
                } else {
                    Bukkit.getScheduler().runTask(ReSync.getInstance(), () -> player.addPotionEffect(effect));
                }
            }
            ctx.triggerOutput("flow");
        });

        registry.register("player_clear_potions", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            if (player == null) {
                ctx.triggerOutput("flow");
                return;
            }
            if (Bukkit.isPrimaryThread()) {
                player.getActivePotionEffects().forEach(effect -> player.removePotionEffect(effect.getType()));
            } else {
                Bukkit.getScheduler().runTask(ReSync.getInstance(), () -> player.getActivePotionEffects().forEach(effect -> player.removePotionEffect(effect.getType())));
            }
            ctx.triggerOutput("flow");
        });

        registry.register("player_has_potion", (ctx, node) -> {
            String nodeId = findNodeId(ctx, node);
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            if (player == null) {
                ctx.setNodeOutput(nodeId, "has_effect", false);
                ctx.setNodeOutput(nodeId, "amplifier", 0);
                ctx.triggerOutput("flow");
                return;
            }
            String effectType = ctx.getInputValue(node, "effect_type", String.class, "SPEED");
            PotionEffectType type = PotionEffectType.getByName(effectType.toUpperCase());
            if (type == null) {
                ctx.setNodeOutput(nodeId, "has_effect", false);
                ctx.setNodeOutput(nodeId, "amplifier", 0);
                ctx.triggerOutput("flow");
                return;
            }
            if (Bukkit.isPrimaryThread()) {
                boolean hasEffect = player.hasPotionEffect(type);
                ctx.setNodeOutput(nodeId, "has_effect", hasEffect);
                ctx.setNodeOutput(nodeId, "amplifier", hasEffect ? player.getPotionEffect(type).getAmplifier() : 0);
            } else {
                Bukkit.getScheduler().runTask(ReSync.getInstance(), () -> {
                    boolean hasEffect = player.hasPotionEffect(type);
                    ctx.setNodeOutput(nodeId, "has_effect", hasEffect);
                    ctx.setNodeOutput(nodeId, "amplifier", hasEffect ? player.getPotionEffect(type).getAmplifier() : 0);
                });
            }
            ctx.triggerOutput("flow");
        });

        registry.register("player_send_resourcepack", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            if (player == null) {
                ctx.triggerOutput("flow");
                return;
            }
            String url = ctx.getInputValue(node, "url", String.class, "");
            Boolean force = ctx.getInputValue(node, "force", Boolean.class, false);
            if (Bukkit.isPrimaryThread()) {
                player.setResourcePack(url);
            } else {
                Bukkit.getScheduler().runTask(ReSync.getInstance(), () -> player.setResourcePack(url));
            }
            ctx.triggerOutput("flow");
        });

        registry.register("player_set_compass_target", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            if (player == null) {
                ctx.triggerOutput("flow");
                return;
            }
            Location location = ctx.getInputValue(node, "location", Location.class, null);
            if (location == null) {
                ctx.triggerOutput("flow");
                return;
            }
            if (Bukkit.isPrimaryThread()) {
                player.setCompassTarget(location);
            } else {
                Bukkit.getScheduler().runTask(ReSync.getInstance(), () -> player.setCompassTarget(location));
            }
            ctx.triggerOutput("flow");
        });

        registry.register("player_reset_compass", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            if (player == null) {
                ctx.triggerOutput("flow");
                return;
            }
            if (Bukkit.isPrimaryThread()) {
                player.setCompassTarget(player.getWorld().getSpawnLocation());
            } else {
                Bukkit.getScheduler().runTask(ReSync.getInstance(), () -> player.setCompassTarget(player.getWorld().getSpawnLocation()));
            }
            ctx.triggerOutput("flow");
        });

        registry.register("player_get_exp_level", (ctx, node) -> {
            String nodeId = findNodeId(ctx, node);
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            if (player == null) {
                ctx.setNodeOutput(nodeId, "level", 0);
                ctx.triggerOutput("flow");
                return;
            }
            if (Bukkit.isPrimaryThread()) {
                ctx.setNodeOutput(nodeId, "level", player.getLevel());
            } else {
                Bukkit.getScheduler().runTask(ReSync.getInstance(), () -> ctx.setNodeOutput(nodeId, "level", player.getLevel()));
            }
            ctx.triggerOutput("flow");
        });

        registry.register("player_get_exp_to_level", (ctx, node) -> {
            String nodeId = findNodeId(ctx, node);
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            if (player == null) {
                ctx.setNodeOutput(nodeId, "exp_needed", 0);
                ctx.triggerOutput("flow");
                return;
            }
            if (Bukkit.isPrimaryThread()) {
                ctx.setNodeOutput(nodeId, "exp_needed", player.getExpToLevel());
            } else {
                Bukkit.getScheduler().runTask(ReSync.getInstance(), () -> ctx.setNodeOutput(nodeId, "exp_needed", player.getExpToLevel()));
            }
            ctx.triggerOutput("flow");
        });

        registry.register("player_get_total_exp", (ctx, node) -> {
            String nodeId = findNodeId(ctx, node);
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            if (player == null) {
                ctx.setNodeOutput(nodeId, "total_exp", 0);
                ctx.triggerOutput("flow");
                return;
            }
            if (Bukkit.isPrimaryThread()) {
                ctx.setNodeOutput(nodeId, "total_exp", player.getTotalExperience());
            } else {
                Bukkit.getScheduler().runTask(ReSync.getInstance(), () -> ctx.setNodeOutput(nodeId, "total_exp", player.getTotalExperience()));
            }
            ctx.triggerOutput("flow");
        });

        registry.register("player_set_exp", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            if (player == null) {
                ctx.triggerOutput("flow");
                return;
            }
            Integer exp = ctx.getInputValue(node, "exp", Integer.class, 0);
            if (Bukkit.isPrimaryThread()) {
                player.setTotalExperience(Math.max(0, exp));
            } else {
                Bukkit.getScheduler().runTask(ReSync.getInstance(), () -> player.setTotalExperience(Math.max(0, exp)));
            }
            ctx.triggerOutput("flow");
        });

        registry.register("player_give_exp", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            if (player == null) {
                ctx.triggerOutput("flow");
                return;
            }
            Integer exp = ctx.getInputValue(node, "exp", Integer.class, 0);
            if (Bukkit.isPrimaryThread()) {
                player.giveExp(Math.max(0, exp));
            } else {
                Bukkit.getScheduler().runTask(ReSync.getInstance(), () -> player.giveExp(Math.max(0, exp)));
            }
            ctx.triggerOutput("flow");
        });

        registry.register("player_show_bossbar", (ctx, node) -> {
            String nodeId = findNodeId(ctx, node);
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            if (player == null) {
                ctx.triggerOutput("flow");
                return;
            }
            String title = ctx.getInputValue(node, "title", String.class, "");
            Double progress = ctx.getInputValue(node, "progress", Double.class, 1.0);
            String colorName = ctx.getInputValue(node, "color", String.class, "WHITE");
            String styleName = ctx.getInputValue(node, "style", String.class, "SOLID");
            BarColor color = BarColor.valueOf(colorName.toUpperCase());
            BarStyle style = BarStyle.valueOf(styleName.toUpperCase());
            String bossbarId = UUID.randomUUID().toString();
            BossBar bossBar = Bukkit.createBossBar(title, color, style);
            bossBar.setProgress(Math.max(0, Math.min(1, progress)));
            bossBar.addPlayer(player);
            bossBars.put(bossbarId, bossBar);
            ctx.setNodeOutput(nodeId, "bossbar_id", bossbarId);
            ctx.triggerOutput("flow");
        });

        registry.register("player_hide_bossbar", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            if (player == null) {
                ctx.triggerOutput("flow");
                return;
            }
            String bossbarId = ctx.getInputValue(node, "bossbar_id", String.class, "");
            if (Bukkit.isPrimaryThread()) {
                BossBar bossBar = bossBars.remove(bossbarId);
                if (bossBar != null) {
                    bossBar.removePlayer(player);
                    bossBar.removeAll();
                }
            } else {
                Bukkit.getScheduler().runTask(ReSync.getInstance(), () -> {
                    BossBar bossBar = bossBars.remove(bossbarId);
                    if (bossBar != null) {
                        bossBar.removePlayer(player);
                        bossBar.removeAll();
                    }
                });
            }
            ctx.triggerOutput("flow");
        });

        registry.register("player_update_bossbar", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            if (player == null) {
                ctx.triggerOutput("flow");
                return;
            }
            String bossbarId = ctx.getInputValue(node, "bossbar_id", String.class, "");
            String newTitle = ctx.getInputValue(node, "new_title", String.class, null);
            Double newProgress = ctx.getInputValue(node, "new_progress", Double.class, null);
            if (Bukkit.isPrimaryThread()) {
                BossBar bossBar = bossBars.get(bossbarId);
                if (bossBar != null) {
                    if (newTitle != null) {
                        bossBar.setTitle(newTitle);
                    }
                    if (newProgress != null) {
                        bossBar.setProgress(Math.max(0, Math.min(1, newProgress)));
                    }
                }
            } else {
                Bukkit.getScheduler().runTask(ReSync.getInstance(), () -> {
                    BossBar bossBar = bossBars.get(bossbarId);
                    if (bossBar != null) {
                        if (newTitle != null) {
                            bossBar.setTitle(newTitle);
                        }
                        if (newProgress != null) {
                            bossBar.setProgress(Math.max(0, Math.min(1, newProgress)));
                        }
                    }
                });
            }
            ctx.triggerOutput("flow");
        });

        registry.register("player_set_walking_speed", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            if (player == null) {
                ctx.triggerOutput("flow");
                return;
            }
            Float speed = ctx.getInputValue(node, "speed", Float.class, 0.2f);
            if (Bukkit.isPrimaryThread()) {
                player.setWalkSpeed(Math.max(-1, Math.min(1, speed)));
            } else {
                Bukkit.getScheduler().runTask(ReSync.getInstance(), () -> player.setWalkSpeed(Math.max(-1, Math.min(1, speed))));
            }
            ctx.triggerOutput("flow");
        });

        registry.register("player_set_flying_speed", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            if (player == null) {
                ctx.triggerOutput("flow");
                return;
            }
            Float speed = ctx.getInputValue(node, "speed", Float.class, 0.05f);
            if (Bukkit.isPrimaryThread()) {
                player.setFlySpeed(Math.max(-1, Math.min(1, speed)));
            } else {
                Bukkit.getScheduler().runTask(ReSync.getInstance(), () -> player.setFlySpeed(Math.max(-1, Math.min(1, speed))));
            }
            ctx.triggerOutput("flow");
        });

        registry.register("player_set_saturation", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            if (player == null) {
                ctx.triggerOutput("flow");
                return;
            }
            Float saturation = ctx.getInputValue(node, "saturation", Float.class, 20.0f);
            if (Bukkit.isPrimaryThread()) {
                player.setSaturation(Math.max(0, Math.min(20, saturation)));
            } else {
                Bukkit.getScheduler().runTask(ReSync.getInstance(), () -> player.setSaturation(Math.max(0, Math.min(20, saturation))));
            }
            ctx.triggerOutput("flow");
        });

        registry.register("player_set_food_level", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            if (player == null) {
                ctx.triggerOutput("flow");
                return;
            }
            Integer level = ctx.getInputValue(node, "level", Integer.class, 20);
            if (Bukkit.isPrimaryThread()) {
                player.setFoodLevel(Math.max(0, Math.min(20, level)));
            } else {
                Bukkit.getScheduler().runTask(ReSync.getInstance(), () -> player.setFoodLevel(Math.max(0, Math.min(20, level))));
            }
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
