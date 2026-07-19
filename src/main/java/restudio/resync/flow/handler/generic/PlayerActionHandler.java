package restudio.resync.flow.handler.generic;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.advancement.Advancement;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.block.Sign;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;
import restudio.flow.data.FlowNode;
import restudio.resync.ReSync;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.FlowMutations;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.NodeHandler;
import restudio.resync.flow.util.TextFormatter;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

public class PlayerActionHandler implements NodeHandler, Listener {
    private final Map<String, BiConsumer<FlowContext, FlowNode>> operations = new ConcurrentHashMap<>();
    private final ParticleHandler particleHandler = new ParticleHandler();
    private final Map<String, BossBar> bossBars = new ConcurrentHashMap<>();
    private final Map<UUID, MovementSpeeds> frozenPlayers = new ConcurrentHashMap<>();

    private record MovementSpeeds(float walk, float fly) {
    }

    public PlayerActionHandler() {
        ReSync plugin = ReSync.getInstance();
        if (plugin != null) Bukkit.getPluginManager().registerEvents(this, plugin);
        operations.put("get_player_info", (ctx, node) -> {
            Player target = ctx.getInputValue(node, "target", Player.class, null);
            if (target == null) throw new IllegalArgumentException("Player is required");
            ctx.setOutput(node, "name", target.getName());
            ctx.setOutput(node, "uuid", target.getUniqueId().toString());
            ctx.setOutput(node, "health", target.getHealth());
            ctx.setOutput(node, "location", target.getLocation());
            ctx.setOutput(node, "is_op", target.isOp());
        });

        operations.put("player_message", (ctx, node) -> {
            Player target = requirePlayer(ctx, node, "target");
            String text = ctx.getInputValue(node, "text", String.class, "");
            if (text.isBlank()) throw new IllegalArgumentException("Message text is required");
            runSync(() -> target.sendMessage(TextFormatter.formatLegacy(text)));
        });

        operations.put("player_kick", (ctx, node) -> {
            Player target = ctx.getInputValue(node, "target", Player.class, null);
            String reason = ctx.getInputValue(node, "reason", String.class, "Kicked by Flow");
            if (target == null) {
                throw new IllegalArgumentException("Player is required");
            }
            runSync(() -> target.kick(TextFormatter.parse(reason)));
        });

        operations.put("player_teleport", (ctx, node) -> {
            Player target = requirePlayer(ctx, node, "target");
            Location location = ctx.getInputValue(node, "location", Location.class, null);
            if (location == null) {
                Double x = ctx.getInputValue(node, "x", Double.class, target.getLocation().getX());
                Double y = ctx.getInputValue(node, "y", Double.class, target.getLocation().getY());
                Double z = ctx.getInputValue(node, "z", Double.class, target.getLocation().getZ());
                location = new Location(target.getWorld(), x, y, z);
            }
            requireLocation(location, "Teleport location");
            if (!target.teleport(location)) throw new IllegalStateException("Player teleport was rejected");
        });

        operations.put("give_item", (ctx, node) -> {
            Player target = ctx.getInputValue(node, "target", Player.class, null);
            String materialName = ctx.getInputValue(node, "material", String.class, "STONE");
            Integer amount = ctx.getInputValue(node, "amount", Integer.class, 1);
            if (target == null) throw new IllegalArgumentException("Player is required");
            Material material = requireMaterial(materialName);
            if (amount < 1 || amount > material.getMaxStackSize()) throw new IllegalArgumentException("Item amount must be between 1 and " + material.getMaxStackSize());
            runSync(() -> addItemFully(target, new ItemStack(material, amount)));
        });

        operations.put("player_set_walking_speed", (ctx, node) -> {
            Player target = requirePlayer(ctx, node, "target");
            Double speed = ctx.getInputValue(node, "speed", Double.class, 0.2);
            float value = requireSpeed(speed, "Walking speed");
            runSync(() -> target.setWalkSpeed(value));
        });

        operations.put("player_set_flying_speed", (ctx, node) -> {
            Player target = requirePlayer(ctx, node, "target");
            Double speed = ctx.getInputValue(node, "speed", Double.class, 0.05);
            float value = requireSpeed(speed, "Flying speed");
            runSync(() -> target.setFlySpeed(value));
        });

        operations.put("player_execute_command", (ctx, node) -> {
            Player target = ctx.getInputValue(node, "target", Player.class, null);
            String command = ctx.getInputValue(node, "command", String.class, "");
            Boolean asOp = ctx.getInputValue(node, "as_op", Boolean.class, false);
            if (target == null) throw new IllegalArgumentException("Player is required");
            if (command.isBlank()) throw new IllegalArgumentException("Command is required");
            String normalizedCommand = command.startsWith("/") ? command.substring(1) : command;
            if (normalizedCommand.isBlank()) throw new IllegalArgumentException("Command is required");
            boolean success = callSync(() -> {
                boolean wasOp = target.isOp();
                try {
                    if (asOp && !wasOp) {
                        target.setOp(true);
                    }
                    return Bukkit.dispatchCommand(target, normalizedCommand);
                } finally {
                    if (asOp && !wasOp) {
                        target.setOp(false);
                    }
                }
            });
            ctx.setOutput(node, "success", success);
        });

        operations.put("player_chat", (ctx, node) -> {
            Player target = ctx.getInputValue(node, "target", Player.class, null);
            String message = ctx.getInputValue(node, "message", String.class, "");
            if (target == null || message.isEmpty()) throw new IllegalArgumentException("Player and message are required");
            runSync(() -> target.chat(message));
        });

        operations.put("player_say", (ctx, node) -> {
            Player target = ctx.getInputValue(node, "target", Player.class, null);
            String message = ctx.getInputValue(node, "message", String.class, "");
            if (target == null || message.isEmpty()) throw new IllegalArgumentException("Player and message are required");
            runSync(() -> target.chat("/say " + message));
        });

        operations.put("player_send_resourcepack", (ctx, node) -> {
            Player player = requirePlayer(ctx, node, "player");
            String url = ctx.getInputValue(node, "url", String.class, "");
            requireHttpUrl(url, "Resource pack URL");
            runSync(() -> player.setResourcePack(url));
        });

        operations.put("player_get_exp_level", (ctx, node) -> {
            Player player = requirePlayer(ctx, node, "player");
            int level = callSync(player::getLevel);
            ctx.setOutput(node, "level", level);
        });

        operations.put("player_get_exp_to_level", (ctx, node) -> {
            Player player = requirePlayer(ctx, node, "player");
            int expNeeded = callSync(player::getExpToLevel);
            ctx.setOutput(node, "exp_needed", expNeeded);
        });

        operations.put("player_get_total_exp", (ctx, node) -> {
            Player player = requirePlayer(ctx, node, "player");
            int totalExp = callSync(player::getTotalExperience);
            ctx.setOutput(node, "total_exp", totalExp);
        });

        operations.put("player_show_bossbar", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            String title = ctx.getInputValue(node, "title", String.class, "");
            Double progress = ctx.getInputValue(node, "progress", Double.class, 1.0);
            String colorName = ctx.getInputValue(node, "color", String.class, "WHITE");
            String styleName = ctx.getInputValue(node, "style", String.class, "SOLID");
            if (player == null) {
                throw new IllegalArgumentException("Player is required");
            }
            if (!Double.isFinite(progress) || progress < 0 || progress > 1) throw new IllegalArgumentException("Boss bar progress must be between 0 and 1");
            BarColor color = BarColor.valueOf(colorName.toUpperCase(Locale.ROOT));
            BarStyle style = BarStyle.valueOf(styleName.toUpperCase(Locale.ROOT));
            String bossbarId = UUID.randomUUID().toString();
            runSync(() -> {
                BossBar bossBar = Bukkit.createBossBar(title, color, style);
                bossBar.setProgress(progress);
                bossBar.addPlayer(player);
                bossBars.put(bossbarId, bossBar);
            });
            ctx.setOutput(node, "bossbar_id", bossbarId);
        });

        operations.put("player_hide_bossbar", (ctx, node) -> {
            Player player = requirePlayer(ctx, node, "player");
            String bossbarId = ctx.getInputValue(node, "bossbar_id", String.class, "");
            if (bossbarId.isBlank()) throw new IllegalArgumentException("Boss bar ID is required");
            runSync(() -> {
                BossBar bossBar = bossBars.remove(bossbarId);
                if (bossBar == null) throw new IllegalArgumentException("Unknown boss bar: " + bossbarId);
                if (!bossBar.getPlayers().contains(player)) throw new IllegalArgumentException("Boss bar is not shown to the selected player: " + bossbarId);
                bossBar.removeAll();
            });
        });

        operations.put("player_update_bossbar", (ctx, node) -> {
            Player player = requirePlayer(ctx, node, "player");
            String bossbarId = ctx.getInputValue(node, "bossbar_id", String.class, "");
            String newTitle = ctx.getInputValue(node, "new_title", String.class, null);
            Double newProgress = ctx.getInputValue(node, "new_progress", Double.class, null);
            if (bossbarId.isBlank()) throw new IllegalArgumentException("Boss bar ID is required");
            if (newTitle == null && newProgress == null) throw new IllegalArgumentException("A new title or progress value is required");
            if (newProgress != null && (!Double.isFinite(newProgress) || newProgress < 0 || newProgress > 1)) throw new IllegalArgumentException("Boss bar progress must be between 0 and 1");
            runSync(() -> {
                BossBar bossBar = bossBars.get(bossbarId);
                if (bossBar == null) throw new IllegalArgumentException("Unknown boss bar: " + bossbarId);
                if (!bossBar.getPlayers().contains(player)) throw new IllegalArgumentException("Boss bar is not shown to the selected player: " + bossbarId);
                if (newTitle != null) bossBar.setTitle(newTitle);
                if (newProgress != null) bossBar.setProgress(newProgress);
            });
        });

        operations.put("player_state", (ctx, node) -> {
            String property = ctx.getInputValue(node, "property", String.class, "");
            String action = ctx.getInputValue(node, "action", String.class, "get");
            Player target = requirePlayer(ctx, node, "target");
            if (property.isBlank()) throw new IllegalArgumentException("Player state property is required");
            if (!action.equalsIgnoreCase("get") && !action.equalsIgnoreCase("set")) throw new IllegalArgumentException("Unknown player state action: " + action);
            boolean success = false;
            Object result = null;
            if ("set".equalsIgnoreCase(action)) {
                    switch (property.toLowerCase()) {
                        case "sprint" -> {
                            Boolean enabled = ctx.getInputValue(node, "enabled", Boolean.class, true);
                            runSync(() -> target.setSprinting(enabled));
                            success = true;
                        }
                        case "sneak" -> {
                            Boolean enabled = ctx.getInputValue(node, "enabled", Boolean.class, true);
                            runSync(() -> target.setSneaking(enabled));
                            success = true;
                        }
                        case "fly" -> {
                            Boolean enabled = ctx.getInputValue(node, "enabled", Boolean.class, true);
                            runSync(() -> {
                                target.setAllowFlight(enabled);
                                target.setFlying(enabled);
                            });
                            success = true;
                        }
                        case "vanish" -> {
                            Boolean enabled = ctx.getInputValue(node, "enabled", Boolean.class, true);
                            runSync(() -> target.setInvisible(enabled));
                            success = true;
                        }
                        case "glowing" -> {
                            Boolean enabled = ctx.getInputValue(node, "enabled", Boolean.class, true);
                            runSync(() -> target.setGlowing(enabled));
                            success = true;
                        }
                        case "invulnerable" -> {
                            Boolean enabled = ctx.getInputValue(node, "enabled", Boolean.class, true);
                            runSync(() -> target.setInvulnerable(enabled));
                            success = true;
                        }
                        case "gamemode" -> {
                            String modeName = ctx.getInputValue(node, "gamemode", String.class, "SURVIVAL");
                            GameMode gameMode = GameMode.valueOf(modeName.toUpperCase(Locale.ROOT));
                            runSync(() -> target.setGameMode(gameMode));
                            success = true;
                        }
                        case "food_level" -> {
                            Integer level = ctx.getInputValue(node, "value", Integer.class, 20);
                            requireRange(level, 0, 20, "Food level");
                            runSync(() -> target.setFoodLevel(level));
                            success = true;
                        }
                        case "saturation" -> {
                            Float saturation = ctx.getInputValue(node, "value", Float.class, 20.0f);
                            requireFiniteRange(saturation, 0.0, 20.0, "Saturation");
                            runSync(() -> target.setSaturation(saturation));
                            success = true;
                        }
                        case "exhaustion" -> {
                            Float exhaustion = ctx.getInputValue(node, "value", Float.class, 0.0f);
                            requireFiniteRange(exhaustion, 0.0, 40.0, "Exhaustion");
                            runSync(() -> target.setExhaustion(exhaustion));
                            success = true;
                        }
                        case "health" -> {
                            Double health = ctx.getInputValue(node, "value", Double.class, 20.0);
                            FlowMutations.setHealth(ctx, target, health);
                            success = true;
                        }
                        case "max_health" -> {
                            Double maxHealth = ctx.getInputValue(node, "value", Double.class, 20.0);
                            requireFiniteRange(maxHealth, 1.0, 2048.0, "Maximum health");
                            runSync(() -> {
                                target.setMaxHealth(maxHealth);
                                if (target.getHealth() > maxHealth) target.setHealth(maxHealth);
                            });
                            success = true;
                        }
                        case "absorption" -> {
                            Double absorption = ctx.getInputValue(node, "value", Double.class, 0.0);
                            FlowMutations.setAbsorption(ctx, target, absorption);
                            success = true;
                        }
                        case "walk_speed" -> {
                            Float speed = ctx.getInputValue(node, "value", Float.class, 0.2f);
                            float validated = requireSpeed(speed, "Walk speed");
                            runSync(() -> target.setWalkSpeed(validated));
                            success = true;
                        }
                        case "fly_speed" -> {
                            Float speed = ctx.getInputValue(node, "value", Float.class, 0.1f);
                            float validated = requireSpeed(speed, "Fly speed");
                            runSync(() -> target.setFlySpeed(validated));
                            success = true;
                        }
                        case "fire_ticks" -> {
                            Integer ticks = ctx.getInputValue(node, "value", Integer.class, 0);
                            requireRange(ticks, 0, 72_000, "Fire ticks");
                            runSync(() -> target.setFireTicks(ticks));
                            success = true;
                        }
                        case "air_ticks" -> {
                            Integer ticks = ctx.getInputValue(node, "value", Integer.class, 300);
                            requireRange(ticks, -20, target.getMaximumAir(), "Air ticks");
                            runSync(() -> target.setRemainingAir(ticks));
                            success = true;
                        }
                        case "no_damage_ticks" -> {
                            Integer ticks = ctx.getInputValue(node, "value", Integer.class, 0);
                            FlowMutations.noDamageTicks(ctx, target, ticks);
                            success = true;
                        }
                        case "freeze_state" -> {
                            Boolean enabled = ctx.getInputValue(node, "enabled", Boolean.class, true);
                            runSync(() -> setFrozen(target, enabled));
                            success = true;
                        }
                        case "flight_state" -> {
                            Boolean enabled = ctx.getInputValue(node, "enabled", Boolean.class, true);
                            runSync(() -> {
                                target.setAllowFlight(enabled);
                                if (!enabled) {
                                    target.setFlying(false);
                                }
                            });
                            success = true;
                        }
                        case "compass_target" -> {
                            Location location = ctx.getInputValue(node, "compass_location", Location.class, null);
                            if (location == null || location.getWorld() == null) throw new IllegalArgumentException("Compass world location is required");
                            runSync(() -> target.setCompassTarget(location));
                            success = true;
                        }
                        case "xp" -> {
                            Integer level = ctx.getInputValue(node, "level", Integer.class, 0);
                            Float points = ctx.getInputValue(node, "points", Float.class, 0.0f);
                            requireRange(level, 0, 21_863, "Experience level");
                            requireFiniteRange(points, 0.0, 1.0, "Experience progress");
                            runSync(() -> {
                                target.setLevel(level);
                                target.setExp(points);
                            });
                            success = true;
                        }
                        case "total_exp" -> {
                            Integer exp = ctx.getInputValue(node, "value", Integer.class, 0);
                            if (exp < 0) throw new IllegalArgumentException("Total experience must be non-negative");
                            runSync(() -> target.setTotalExperience(exp));
                            success = true;
                        }
                        default -> throw new IllegalArgumentException("Unknown writable player state property: " + property);
                    }
            } else {
                    switch (property.toLowerCase()) {
                        case "sprint" -> result = callSync(target::isSprinting);
                        case "sneak" -> result = callSync(target::isSneaking);
                        case "fly" -> result = callSync(target::isFlying);
                        case "vanish" -> result = callSync(target::isInvisible);
                        case "glowing" -> result = callSync(target::isGlowing);
                        case "invulnerable" -> result = callSync(target::isInvulnerable);
                        case "gamemode" -> result = callSync(() -> target.getGameMode().name().toLowerCase(Locale.ROOT));
                        case "food_level" -> result = callSync(target::getFoodLevel);
                        case "saturation" -> result = callSync(target::getSaturation);
                        case "exhaustion" -> result = callSync(target::getExhaustion);
                        case "health" -> result = callSync(target::getHealth);
                        case "max_health" -> result = callSync(target::getMaxHealth);
                        case "absorption" -> result = callSync(target::getAbsorptionAmount);
                        case "walk_speed" -> result = callSync(target::getWalkSpeed);
                        case "fly_speed" -> result = callSync(target::getFlySpeed);
                        case "fire_ticks" -> result = callSync(target::getFireTicks);
                        case "air_ticks" -> result = callSync(target::getRemainingAir);
                        case "no_damage_ticks" -> result = callSync(target::getNoDamageTicks);
                        case "freeze_state" -> result = frozenPlayers.containsKey(target.getUniqueId());
                        case "flight_state" -> result = callSync(target::getAllowFlight);
                        case "compass_target" -> result = callSync(target::getCompassTarget);
                        case "xp" -> result = callSync(target::getLevel);
                        case "total_exp" -> result = callSync(target::getTotalExperience);
                        default -> throw new IllegalArgumentException("Unknown readable player state property: " + property);
                    }
                    success = true;
            }
            ctx.setOutput(node, "success", success);
            ctx.setOutput(node, "result", result);
            if (!"set".equalsIgnoreCase(action) && result != null) {
                ctx.setOutput(node, property, result);
            }
        });

        operations.put("player_movement", (ctx, node) -> {
            String mode = ctx.getInputValue(node, "mode", String.class, "");
            Player target = requirePlayer(ctx, node, "target");
            if (mode.isBlank()) throw new IllegalArgumentException("Player movement mode is required");
            boolean success = false;
            switch (mode.toLowerCase(Locale.ROOT)) {
                    case "teleport" -> {
                        Location location = ctx.getInputValue(node, "location", Location.class, null);
                        if (location == null) {
                            Location base = callSync(target::getLocation);
                            Double x = ctx.getInputValue(node, "x", Double.class, base.getX());
                            Double y = ctx.getInputValue(node, "y", Double.class, base.getY());
                            Double z = ctx.getInputValue(node, "z", Double.class, base.getZ());
                            Float yaw = ctx.getInputValue(node, "yaw", Float.class, base.getYaw());
                            Float pitch = ctx.getInputValue(node, "pitch", Float.class, base.getPitch());
                            location = new Location(base.getWorld(), x, y, z, yaw, pitch);
                        }
                        requireLocation(location, "Teleport location");
                        Location finalLocation = location;
                        runSync(() -> {
                            if (!target.teleport(finalLocation)) throw new IllegalStateException("Player teleport was rejected");
                        });
                        success = true;
                    }
                    case "launch" -> {
                        Double vx = ctx.getInputValue(node, "vx", Double.class, 0.0);
                        Double vy = ctx.getInputValue(node, "vy", Double.class, 0.0);
                        Double vz = ctx.getInputValue(node, "vz", Double.class, 0.0);
                        FlowMutations.applyVelocity(ctx, target, new Vector(vx, vy, vz));
                        success = true;
                    }
                    case "push" -> {
                        Double strength = ctx.getInputValue(node, "strength", Double.class, 1.0);
                        Vector inputDirection = ctx.getInputValue(node, "direction_vector", Vector.class, null);
                        requireFiniteRange(strength, -100, 100, "Push strength");
                        if (inputDirection != null) {
                            FlowMutations.finiteVelocity(inputDirection);
                            if (inputDirection.lengthSquared() == 0) throw new IllegalArgumentException("Push direction cannot be zero");
                        }
                        runSync(() -> {
                            Vector direction = inputDirection != null ? inputDirection.clone() : target.getLocation().getDirection();
                            if (direction.lengthSquared() == 0) throw new IllegalArgumentException("Push direction cannot be zero");
                            direction.normalize();
                            FlowMutations.applyVelocity(ctx, target, direction.multiply(strength));
                        });
                        success = true;
                    }
                    case "spin" -> {
                        Float yaw = ctx.getInputValue(node, "yaw", Float.class, 0.0f);
                        Float pitch = ctx.getInputValue(node, "pitch", Float.class, 0.0f);
                        requireFiniteRange(yaw, -360000, 360000, "Spin yaw");
                        requireFiniteRange(pitch, -180, 180, "Spin pitch");
                        Boolean resetVelocity = ctx.getInputValue(node, "reset_velocity", Boolean.class, false);
                        runSync(() -> {
                            Vector velocity = resetVelocity ? null : target.getVelocity().clone();
                            Location loc = target.getLocation();
                            double finalPitch = loc.getPitch() + pitch;
                            if (finalPitch < -90 || finalPitch > 90) throw new IllegalArgumentException("Resulting rotation pitch must be between -90 and 90");
                            loc.setYaw(loc.getYaw() + yaw);
                            loc.setPitch((float) finalPitch);
                            if (!target.teleport(loc)) throw new IllegalStateException("Player rotation teleport was rejected");
                            if (velocity != null) {
                                FlowMutations.applyVelocity(ctx, target, velocity);
                            }
                        });
                        success = true;
                    }
                    case "set_rotation" -> {
                        Location base = callSync(target::getLocation);
                        Float yaw = ctx.getInputValue(node, "yaw", Float.class, base.getYaw());
                        Float pitch = ctx.getInputValue(node, "pitch", Float.class, base.getPitch());
                        requireFiniteRange(yaw, -360000, 360000, "Rotation yaw");
                        requireFiniteRange(pitch, -90, 90, "Rotation pitch");
                        Boolean resetVelocity = ctx.getInputValue(node, "reset_velocity", Boolean.class, false);
                        runSync(() -> {
                            Vector velocity = resetVelocity ? null : target.getVelocity().clone();
                            Location loc = target.getLocation();
                            loc.setYaw(yaw);
                            loc.setPitch(pitch);
                            if (!target.teleport(loc)) throw new IllegalStateException("Player rotation teleport was rejected");
                            if (velocity != null) {
                                FlowMutations.applyVelocity(ctx, target, velocity);
                            }
                        });
                        success = true;
                    }
                    default -> throw new IllegalArgumentException("Unknown player movement mode: " + mode);
            }
            ctx.setOutput(node, "success", success);
        });

        operations.put("player_potion", (ctx, node) -> {
            String mode = ctx.getInputValue(node, "mode", String.class, "");
            Player target = requirePlayer(ctx, node, "target");
            if (mode.isBlank()) throw new IllegalArgumentException("Player potion mode is required");
            boolean success = false;
            boolean hasEffect = false;
            int amplifier = 0;
            switch (mode.toLowerCase(Locale.ROOT)) {
                    case "add" -> {
                        String effectType = ctx.getInputValue(node, "effect_type", String.class, "SPEED");
                        Integer duration = ctx.getInputValue(node, "duration_ticks", Integer.class, 600);
                        Integer amp = ctx.getInputValue(node, "amplifier", Integer.class, 0);
                        PotionEffectType type = requirePotionEffect(effectType);
                        if (duration < 1) throw new IllegalArgumentException("Potion duration must be positive");
                        if (amp < 0 || amp > 255) throw new IllegalArgumentException("Potion amplifier must be between 0 and 255");
                        PotionEffect effect = new PotionEffect(type, duration, amp);
                        runSync(() -> target.addPotionEffect(effect));
                        success = true;
                    }
                    case "clear" -> {
                        runSync(() -> target.getActivePotionEffects().forEach(effect -> target.removePotionEffect(effect.getType())));
                        success = true;
                    }
                    case "has" -> {
                        String effectType = ctx.getInputValue(node, "effect_type", String.class, "SPEED");
                        PotionEffectType type = requirePotionEffect(effectType);
                        hasEffect = callSync(() -> target.hasPotionEffect(type));
                        if (hasEffect) {
                            PotionEffect potionEffect = callSync(() -> target.getPotionEffect(type));
                            amplifier = potionEffect != null ? potionEffect.getAmplifier() : 0;
                        }
                        success = true;
                    }
                    default -> throw new IllegalArgumentException("Unknown player potion mode: " + mode);
            }
            ctx.setOutput(node, "success", success);
            ctx.setOutput(node, "has_effect", hasEffect);
            ctx.setOutput(node, "effect_amplifier", amplifier);
        });

        operations.put("player_advancement", (ctx, node) -> {
            String mode = ctx.getInputValue(node, "mode", String.class, "");
            Player target = requirePlayer(ctx, node, "target");
            String key = ctx.getInputValue(node, "advancement_key", String.class, "");
            String criterion = ctx.getInputValue(node, "criterion", String.class, "impossible");
            if (mode.isBlank()) throw new IllegalArgumentException("Player advancement mode is required");
            if (key.isBlank()) throw new IllegalArgumentException("Advancement key is required");
            if (criterion.isBlank()) throw new IllegalArgumentException("Advancement criterion is required");
            boolean success = false;
            boolean hasAdvancement = false;
            NamespacedKey namespacedKey = NamespacedKey.fromString(key.toLowerCase(Locale.ROOT));
            if (namespacedKey == null) throw new IllegalArgumentException("Invalid advancement key: " + key);
            Advancement advancement = Bukkit.getAdvancement(namespacedKey);
            if (advancement == null) throw new IllegalArgumentException("Unknown advancement: " + key);
            switch (mode.toLowerCase(Locale.ROOT)) {
                            case "grant" -> {
                                runSync(() -> target.getAdvancementProgress(advancement).awardCriteria(criterion));
                                success = true;
                            }
                            case "revoke" -> {
                                runSync(() -> target.getAdvancementProgress(advancement).revokeCriteria(criterion));
                                success = true;
                            }
                            case "has" -> {
                                hasAdvancement = callSync(() -> target.getAdvancementProgress(advancement).getAwardedCriteria().contains(criterion));
                                success = true;
                            }
                            default -> throw new IllegalArgumentException("Unknown player advancement mode: " + mode);
            }
            ctx.setOutput(node, "success", success);
            ctx.setOutput(node, "has_advancement", hasAdvancement);
        });

        operations.put("player_cooldown", (ctx, node) -> {
            String mode = ctx.getInputValue(node, "mode", String.class, "");
            Player target = requirePlayer(ctx, node, "target");
            String materialName = ctx.getInputValue(node, "material", String.class, "");
            if (mode.isBlank()) throw new IllegalArgumentException("Player cooldown mode is required");
            if (materialName.isBlank()) throw new IllegalArgumentException("Cooldown material is required");
            boolean success = false;
            boolean hasCooldown = false;
            int remainingTicks = 0;
            Material material = requireMaterial(materialName);
            switch (mode.toLowerCase(Locale.ROOT)) {
                        case "set" -> {
                            Integer ticks = ctx.getInputValue(node, "ticks", Integer.class, 0);
                            if (ticks < 0) throw new IllegalArgumentException("Cooldown ticks cannot be negative");
                            runSync(() -> target.setCooldown(material, ticks));
                            success = true;
                        }
                        case "has" -> {
                            hasCooldown = callSync(() -> target.hasCooldown(material));
                            success = true;
                        }
                        case "get" -> {
                            remainingTicks = callSync(() -> target.getCooldown(material));
                            success = true;
                        }
                        case "clear" -> {
                            runSync(() -> target.setCooldown(material, 0));
                            success = true;
                        }
                        default -> throw new IllegalArgumentException("Unknown player cooldown mode: " + mode);
            }
            ctx.setOutput(node, "success", success);
            ctx.setOutput(node, "has_cooldown", hasCooldown);
            ctx.setOutput(node, "remaining_ticks", remainingTicks);
        });

        operations.put("player_send_message", (ctx, node) -> {
            Player target = requirePlayer(ctx, node, "target");
            String text = ctx.getInputValue(node, "text", String.class, "");
            if (text.isBlank()) throw new IllegalArgumentException("Message text is required");
            String message = TextFormatter.formatLegacy(text);
            runSync(() -> target.sendMessage(message));
        });

        operations.put("player_send_action_bar", (ctx, node) -> {
            Player target = requirePlayer(ctx, node, "target");
            String text = ctx.getInputValue(node, "text", String.class, "");
            if (text.isBlank()) throw new IllegalArgumentException("Action bar text is required");
            Component component = TextFormatter.parse(text);
            runSync(() -> target.sendActionBar(component));
        });

        operations.put("player_send_title", (ctx, node) -> {
            Player target = requirePlayer(ctx, node, "target");
            String title = ctx.getInputValue(node, "title", String.class, "");
            String subtitle = ctx.getInputValue(node, "subtitle", String.class, "");
            if (title.isBlank() && subtitle.isBlank()) throw new IllegalArgumentException("Title or subtitle text is required");
            runSync(() -> target.showTitle(Title.title(TextFormatter.parse(title), TextFormatter.parse(subtitle))));
        });

        operations.put("player_send_sound", (ctx, node) -> {
            Player target = ctx.getInputValue(node, "target", Player.class, null);
            if (target == null) throw new IllegalArgumentException("Player is required");
            String soundName = ctx.getInputValue(node, "sound", String.class, "block.amethyst_block.chime");
            Float volume = ctx.getInputValue(node, "volume", Float.class, 1.0f);
            Float pitch = ctx.getInputValue(node, "pitch", Float.class, 1.0f);
            requireFiniteRange(volume, 0, 16, "Sound volume");
            requireFiniteRange(pitch, 0, 2, "Sound pitch");
            Sound sound;
            try {
                sound = Sound.valueOf(soundName.toUpperCase(Locale.ROOT).replace('.', '_'));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("Unknown sound: " + soundName, exception);
            }
            runSync(() -> target.playSound(target.getLocation(), sound, volume, pitch));
        });

        operations.put("player_send_particle", (ctx, node) -> {
            Player target = ctx.getInputValue(node, "target", Player.class, null);
            if (target == null) throw new IllegalArgumentException("Player is required");
            Map<String, Object> inputs = new HashMap<>(node.getInputValues() != null ? node.getInputValues() : Map.of());
            inputs.put("mode", "player");
            inputs.put("player", target);
            inputs.put("location", target.getLocation().clone().add(0, 1, 0));
            FlowNode particleNode = new FlowNode("particle.apply", node.getX(), node.getY(), inputs);
            particleNode.setHandlerConfig(Map.of("operation", "particle_apply"));
            particleHandler.executeInline(ctx, particleNode);
        });

        operations.put("player_send_book", (ctx, node) -> {
            Player target = requirePlayer(ctx, node, "target");
            ItemStack book = ctx.getInputValue(node, "book", ItemStack.class, null);
            if (book == null || book.getType() != Material.WRITTEN_BOOK) throw new IllegalArgumentException("A written book is required");
            runSync(() -> target.openBook(book));
        });

        operations.put("player_send_sign", (ctx, node) -> {
            Player target = ctx.getInputValue(node, "target", Player.class, null);
            if (target == null) throw new IllegalArgumentException("Player is required");
            Runnable action = () -> {
                if (!(target.getLocation().getBlock().getState() instanceof Sign sign)) {
                    throw new IllegalArgumentException("Player must be standing on a sign block");
                }
                target.openSign(sign);
            };
            runSync(action);
        });

        operations.put("player_send_raw_json", (ctx, node) -> {
            Player target = ctx.getInputValue(node, "target", Player.class, null);
            String json = ctx.getInputValue(node, "json", String.class, "");
            if (target == null) {
                throw new IllegalArgumentException("Player is required");
            }
            if (json == null || json.isBlank()) {
                throw new IllegalArgumentException("Raw component JSON is required");
            }
            Component component;
            try {
                component = GsonComponentSerializer.gson().deserialize(json);
            } catch (RuntimeException exception) {
                throw new IllegalArgumentException("Raw component JSON is invalid", exception);
            }
            runSync(() -> target.sendMessage(component));
        });

        operations.put("player_give_item", (ctx, node) -> {
            Player target = requirePlayer(ctx, node, "target");
            ItemStack item = requireItem(ctx, node, "item");
            runSync(() -> addItemFully(target, item.clone()));
        });

        operations.put("player_take_item", (ctx, node) -> {
            Player target = requirePlayer(ctx, node, "target");
            ItemStack item = requireItem(ctx, node, "item");
            Integer amount = ctx.getInputValue(node, "amount", Integer.class, 1);
            if (amount < 1) throw new IllegalArgumentException("Item amount must be positive");
            ItemStack toRemove = item.clone();
            toRemove.setAmount(amount);
            runSync(() -> {
                if (!target.getInventory().containsAtLeast(toRemove, amount)) throw new IllegalArgumentException("Player does not have the requested item amount");
                target.getInventory().removeItem(toRemove);
            });
        });

        operations.put("player_set_item", (ctx, node) -> {
            Player target = requirePlayer(ctx, node, "target");
            int slot = requireInventorySlot(ctx.getInputValue(node, "slot", Integer.class, 0));
            ItemStack item = requireItem(ctx, node, "item");
            runSync(() -> target.getInventory().setItem(slot, item));
        });

        operations.put("player_clear_slot", (ctx, node) -> {
            Player target = requirePlayer(ctx, node, "target");
            int slot = requireInventorySlot(ctx.getInputValue(node, "slot", Integer.class, 0));
            runSync(() -> target.getInventory().setItem(slot, null));
        });

        operations.put("player_swap_items", (ctx, node) -> {
            Player target = requirePlayer(ctx, node, "target");
            int slot1 = requireInventorySlot(ctx.getInputValue(node, "slot1", Integer.class, 0));
            int slot2 = requireInventorySlot(ctx.getInputValue(node, "slot2", Integer.class, 1));
            if (slot1 == slot2) throw new IllegalArgumentException("Inventory swap slots must be different");
            runSync(() -> {
                ItemStack item1 = target.getInventory().getItem(slot1);
                ItemStack item2 = target.getInventory().getItem(slot2);
                target.getInventory().setItem(slot1, item2);
                target.getInventory().setItem(slot2, item1);
            });
        });

        operations.put("player_set_helmet", (ctx, node) -> {
            Player target = requirePlayer(ctx, node, "target");
            ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
            runSync(() -> target.getInventory().setItem(EquipmentSlot.HEAD, item));
        });

        operations.put("player_set_chestplate", (ctx, node) -> {
            Player target = requirePlayer(ctx, node, "target");
            ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
            runSync(() -> target.getInventory().setItem(EquipmentSlot.CHEST, item));
        });

        operations.put("player_set_leggings", (ctx, node) -> {
            Player target = requirePlayer(ctx, node, "target");
            ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
            runSync(() -> target.getInventory().setItem(EquipmentSlot.LEGS, item));
        });

        operations.put("player_set_boots", (ctx, node) -> {
            Player target = requirePlayer(ctx, node, "target");
            ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
            runSync(() -> target.getInventory().setItem(EquipmentSlot.FEET, item));
        });

        operations.put("player_set_mainhand", (ctx, node) -> {
            Player target = requirePlayer(ctx, node, "target");
            ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
            runSync(() -> target.getInventory().setItemInMainHand(item));
        });

        operations.put("player_set_offhand", (ctx, node) -> {
            Player target = requirePlayer(ctx, node, "target");
            ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
            runSync(() -> target.getInventory().setItemInOffHand(item));
        });

        operations.put("player_set_inventory_title", (ctx, node) -> {
            Player target = requirePlayer(ctx, node, "target");
            String title = ctx.getInputValue(node, "title", String.class, "Inventory");
            if (title.isBlank()) throw new IllegalArgumentException("Inventory title is required");
            runSync(() -> target.getOpenInventory().setTitle(title));
        });

        operations.put("player_set_armor_color", (ctx, node) -> {
            Player target = requirePlayer(ctx, node, "target");
            Object slotValue = ctx.getInputValue(node, "slot");
            EquipmentSlot slot = requireArmorSlot(slotValue != null ? slotValue : "CHEST");
            Integer red = ctx.getInputValue(node, "red", Integer.class, 255);
            Integer green = ctx.getInputValue(node, "green", Integer.class, 255);
            Integer blue = ctx.getInputValue(node, "blue", Integer.class, 255);
            requireRgb(red, green, blue);
            runSync(() -> {
                ItemStack item = target.getInventory().getItem(slot);
                if (item == null || !(item.getItemMeta() instanceof LeatherArmorMeta meta)) throw new IllegalArgumentException("Selected equipment slot does not contain leather armor");
                meta.setColor(Color.fromRGB(red, green, blue));
                item.setItemMeta(meta);
            });
        });

        operations.put("player_repair_item", (ctx, node) -> {
            ItemStack item = requireItem(ctx, node, "item");
            runSync(() -> {
                ItemMeta itemMeta = item.getItemMeta();
                if (!(itemMeta instanceof Damageable damageable)) throw new IllegalArgumentException("Item cannot take damage");
                damageable.setDamage(0);
                item.setItemMeta(itemMeta);
            });
            ctx.setOutput(node, "item", item);
        });

        operations.put("player_enchant_item", (ctx, node) -> {
            ItemStack item = requireItem(ctx, node, "item");
            String enchantName = ctx.getInputValue(node, "enchantment", String.class, "");
            Integer level = ctx.getInputValue(node, "level", Integer.class, 1);
            Enchantment enchant = requireEnchantment(enchantName);
            if (level < 1 || level > 255) throw new IllegalArgumentException("Enchantment level must be between 1 and 255");
            runSync(() -> {
                ItemMeta meta = requireItemMeta(item);
                meta.addEnchant(enchant, level, true);
                item.setItemMeta(meta);
            });
            ctx.setOutput(node, "item", item);
        });

        operations.put("player_unenchant_item", (ctx, node) -> {
            ItemStack item = requireItem(ctx, node, "item");
            String enchantName = ctx.getInputValue(node, "enchantment", String.class, "");
            Enchantment enchant = requireEnchantment(enchantName);
            runSync(() -> {
                ItemMeta meta = requireItemMeta(item);
                if (!meta.hasEnchant(enchant)) throw new IllegalArgumentException("Item does not contain enchantment: " + enchantName);
                meta.removeEnchant(enchant);
                item.setItemMeta(meta);
            });
            ctx.setOutput(node, "item", item);
        });

        operations.put("player_clear_enchants", (ctx, node) -> {
            ItemStack item = requireItem(ctx, node, "item");
            runSync(() -> {
                ItemMeta meta = requireItemMeta(item);
                meta.getEnchants().keySet().forEach(meta::removeEnchant);
                item.setItemMeta(meta);
            });
            ctx.setOutput(node, "item", item);
        });

        operations.put("player_set_item_name", (ctx, node) -> {
            ItemStack item = requireItem(ctx, node, "item");
            String name = ctx.getInputValue(node, "name", String.class, "");
            runSync(() -> {
                ItemMeta meta = requireItemMeta(item);
                meta.displayName(TextFormatter.parseItemName(name));
                item.setItemMeta(meta);
            });
            ctx.setOutput(node, "item", item);
        });

        operations.put("player_set_item_lore", (ctx, node) -> {
            ItemStack item = requireItem(ctx, node, "item");
            String lore = ctx.getInputValue(node, "lore", String.class, "");
            runSync(() -> {
                ItemMeta meta = requireItemMeta(item);
                meta.lore(TextFormatter.parseItemLoreLines(lore));
                item.setItemMeta(meta);
            });
            ctx.setOutput(node, "item", item);
        });

        operations.put("player_set_item_flags", (ctx, node) -> {
            ItemStack item = requireItem(ctx, node, "item");
            String flags = ctx.getInputValue(node, "flags", String.class, "");
            ItemFlag[] parsedFlags = parseItemFlags(flags);
            runSync(() -> {
                ItemMeta meta = requireItemMeta(item);
                meta.removeItemFlags(meta.getItemFlags().toArray(ItemFlag[]::new));
                if (parsedFlags.length > 0) meta.addItemFlags(parsedFlags);
                item.setItemMeta(meta);
            });
            ctx.setOutput(node, "item", item);
        });

        operations.put("player_set_item_custom_model", (ctx, node) -> {
            ItemStack item = requireItem(ctx, node, "item");
            Integer modelData = ctx.getInputValue(node, "model_data", Integer.class, 0);
            if (modelData < 0) throw new IllegalArgumentException("Custom model data cannot be negative");
            runSync(() -> {
                ItemMeta meta = requireItemMeta(item);
                meta.setCustomModelData(modelData);
                item.setItemMeta(meta);
            });
            ctx.setOutput(node, "item", item);
        });

        operations.put("player_set_item_unbreakable", (ctx, node) -> {
            ItemStack item = requireItem(ctx, node, "item");
            Boolean unbreakable = ctx.getInputValue(node, "unbreakable", Boolean.class, true);
            runSync(() -> {
                ItemMeta meta = requireItemMeta(item);
                meta.setUnbreakable(unbreakable);
                item.setItemMeta(meta);
            });
            ctx.setOutput(node, "item", item);
        });
    }

    public void registerTo(HandlerRegistry registry) {
        registry.register("PlayerActionHandler", this);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        restoreFrozen(event.getPlayer(), false);
        bossBars.values().forEach(bossBar -> bossBar.removePlayer(event.getPlayer()));
    }

    @Override
    public void shutdown() {
        HandlerList.unregisterAll(this);
        for (UUID playerId : Map.copyOf(frozenPlayers).keySet()) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null) restoreFrozen(player, false);
        }
        bossBars.values().forEach(BossBar::removeAll);
        bossBars.clear();
        if (!frozenPlayers.isEmpty()) throw new IllegalStateException("Frozen player speeds could not be restored for players: " + frozenPlayers.keySet());
    }

    @Override
    public void execute(FlowContext ctx, FlowNode node) {
        String operation = node.getHandlerConfig().getString("operation");
        BiConsumer<FlowContext, FlowNode> op = operation != null ? operations.get(operation) : null;
        if (op == null) {
            throw new IllegalArgumentException("Unknown player action operation: " + operation);
        }
        op.accept(ctx, node);
        ctx.triggerOutput("flow");
    }

    private Player requirePlayer(FlowContext context, FlowNode node, String input) {
        Player player = context.getInputValue(node, input, Player.class, null);
        if (player == null) throw new IllegalArgumentException("Player input is required: " + input);
        return player;
    }

    private ItemStack requireItem(FlowContext context, FlowNode node, String input) {
        ItemStack item = context.getInputValue(node, input, ItemStack.class, null);
        if (item == null || item.getType().isAir() || item.getAmount() < 1) throw new IllegalArgumentException("Item input is required: " + input);
        return item;
    }

    private Material requireMaterial(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Material is required");
        Material material = Material.matchMaterial(value);
        if (material == null || material.isAir()) throw new IllegalArgumentException("Unknown material: " + value);
        return material;
    }

    private PotionEffectType requirePotionEffect(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Potion effect is required");
        PotionEffectType type = PotionEffectType.getByName(value.toUpperCase(Locale.ROOT));
        if (type == null) throw new IllegalArgumentException("Unknown potion effect: " + value);
        return type;
    }

    private Enchantment requireEnchantment(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Enchantment is required");
        NamespacedKey key = NamespacedKey.fromString(value.toLowerCase(Locale.ROOT));
        if (key == null) throw new IllegalArgumentException("Invalid enchantment key: " + value);
        Enchantment enchantment = Enchantment.getByKey(key);
        if (enchantment == null) throw new IllegalArgumentException("Unknown enchantment: " + value);
        return enchantment;
    }

    private ItemMeta requireItemMeta(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) throw new IllegalArgumentException("Item does not support metadata: " + item.getType());
        return meta;
    }

    private ItemFlag[] parseItemFlags(String value) {
        if (value == null || value.isBlank()) return new ItemFlag[0];
        return Arrays.stream(value.split("[,\\s]+"))
            .filter(token -> !token.isBlank())
            .map(token -> {
                try {
                    return ItemFlag.valueOf(token.toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException exception) {
                    throw new IllegalArgumentException("Unknown item flag: " + token, exception);
                }
            })
            .distinct()
            .toArray(ItemFlag[]::new);
    }

    private int requireInventorySlot(int slot) {
        if (slot < 0 || slot >= 36) throw new IllegalArgumentException("Inventory slot must be between 0 and 35");
        return slot;
    }

    private EquipmentSlot requireArmorSlot(Object value) {
        try {
            EquipmentSlot slot = value instanceof EquipmentSlot equipmentSlot ? equipmentSlot
                : EquipmentSlot.valueOf(value != null ? value.toString().toUpperCase(Locale.ROOT) : "");
            if (slot != EquipmentSlot.HEAD && slot != EquipmentSlot.CHEST && slot != EquipmentSlot.LEGS && slot != EquipmentSlot.FEET) {
                throw new IllegalArgumentException("Armor slot must be head, chest, legs, or feet");
            }
            return slot;
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unknown armor slot: " + value, exception);
        }
    }

    private float requireSpeed(double speed, String label) {
        if (!Double.isFinite(speed) || speed < -1 || speed > 1) throw new IllegalArgumentException(label + " must be between -1 and 1");
        return (float) speed;
    }

    private void requireRange(int value, int minimum, int maximum, String label) {
        if (value < minimum || value > maximum) throw new IllegalArgumentException(label + " must be between " + minimum + " and " + maximum);
    }

    private void requireFiniteRange(double value, double minimum, double maximum, String label) {
        if (!Double.isFinite(value) || value < minimum || value > maximum) {
            throw new IllegalArgumentException(label + " must be between " + minimum + " and " + maximum);
        }
    }

    private void requireLocation(Location location, String label) {
        if (location == null || location.getWorld() == null) throw new IllegalArgumentException(label + " must belong to a loaded world");
        if (!Double.isFinite(location.getX()) || !Double.isFinite(location.getY()) || !Double.isFinite(location.getZ())
            || !Float.isFinite(location.getYaw()) || !Float.isFinite(location.getPitch())) {
            throw new IllegalArgumentException(label + " must contain finite coordinates and rotation");
        }
        if (location.getPitch() < -90 || location.getPitch() > 90) throw new IllegalArgumentException(label + " pitch must be between -90 and 90");
    }

    private void requireHttpUrl(String value, String label) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(label + " is required");
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme();
            if (uri.getHost() == null || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
                throw new IllegalArgumentException(label + " must be an HTTP or HTTPS URL");
            }
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException(label + " is invalid", exception);
        }
    }

    private void setFrozen(Player player, boolean frozen) {
        UUID playerId = player.getUniqueId();
        if (frozen) {
            frozenPlayers.putIfAbsent(playerId, new MovementSpeeds(player.getWalkSpeed(), player.getFlySpeed()));
            player.setWalkSpeed(0.0f);
            player.setFlySpeed(0.0f);
            return;
        }
        restoreFrozen(player, true);
    }

    private void restoreFrozen(Player player, boolean required) {
        MovementSpeeds speeds = frozenPlayers.remove(player.getUniqueId());
        if (speeds == null) {
            if (required) throw new IllegalStateException("Player is not frozen by ReSync");
            return;
        }
        player.setWalkSpeed(speeds.walk());
        player.setFlySpeed(speeds.fly());
    }

    private void requireRgb(int red, int green, int blue) {
        if (red < 0 || red > 255 || green < 0 || green > 255 || blue < 0 || blue > 255) throw new IllegalArgumentException("RGB values must be between 0 and 255");
    }

    private void addItemFully(Player player, ItemStack item) {
        int capacity = 0;
        int stackLimit = Math.min(item.getMaxStackSize(), player.getInventory().getMaxStackSize());
        for (ItemStack existing : player.getInventory().getStorageContents()) {
            if (existing == null || existing.getType().isAir()) {
                capacity += stackLimit;
            } else if (existing.isSimilar(item)) {
                capacity += Math.max(0, stackLimit - existing.getAmount());
            }
            if (capacity >= item.getAmount()) break;
        }
        if (capacity < item.getAmount()) throw new IllegalArgumentException("Player inventory does not have enough space");
        if (!player.getInventory().addItem(item).isEmpty()) throw new IllegalStateException("Player inventory changed while adding the item");
    }

    private void runSync(Runnable action) {
        action.run();
    }

    private <T> T callSync(Supplier<T> supplier) {
        return supplier.get();
    }
}
