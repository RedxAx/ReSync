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
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;
import restudio.flow.data.FlowNode;
import restudio.resync.Log;
import restudio.resync.ReSync;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.FlowMutations;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.NodeHandler;
import restudio.resync.flow.util.TextFormatter;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

public class PlayerActionHandler implements NodeHandler {
    private final Map<String, BiConsumer<FlowContext, FlowNode>> operations = new ConcurrentHashMap<>();
    private final ParticleHandler particleHandler = new ParticleHandler();
    private static final Map<String, BossBar> BOSS_BARS = new ConcurrentHashMap<>();

    public PlayerActionHandler() {
        operations.put("get_player_info", (ctx, node) -> {
            Player target = ctx.getInputValue(node, "target", Player.class, null);
            if (target == null) return;
            ctx.setOutput(node, "name", target.getName());
            ctx.setOutput(node, "uuid", target.getUniqueId().toString());
            ctx.setOutput(node, "health", target.getHealth());
            ctx.setOutput(node, "location", target.getLocation());
            ctx.setOutput(node, "is_op", target.isOp());
        });

        operations.put("player_message", (ctx, node) -> {
            Player target = ctx.getInputValue(node, "target", Player.class, null);
            String text = ctx.getInputValue(node, "text", String.class, "");
            if (target != null) {
                target.sendMessage(TextFormatter.parse(text));
            }
        });

        operations.put("player_kick", (ctx, node) -> {
            Player target = ctx.getInputValue(node, "target", Player.class, null);
            String reason = ctx.getInputValue(node, "reason", String.class, "Kicked by Flow");
            if (target != null) {
                if (Bukkit.isPrimaryThread()) {
                    target.kick(TextFormatter.parse(reason));
                } else {
                    try {
                        Bukkit.getScheduler().callSyncMethod(ReSync.getInstance(), () -> {
                            target.kick(TextFormatter.parse(reason));
                            return null;
                        }).get();
                    } catch (Exception e) {
                        Log.warn("[Flow] Failed to kick player: " + e.getMessage());
                    }
                }
            }
        });

        operations.put("player_teleport", (ctx, node) -> {
            Player target = ctx.getInputValue(node, "target", Player.class, null);
            if (target == null) return;
            Location location = ctx.getInputValue(node, "location", Location.class, null);
            if (location == null) {
                Double x = ctx.getInputValue(node, "x", Double.class, target.getLocation().getX());
                Double y = ctx.getInputValue(node, "y", Double.class, target.getLocation().getY());
                Double z = ctx.getInputValue(node, "z", Double.class, target.getLocation().getZ());
                location = new Location(target.getWorld(), x, y, z);
            }
            target.teleport(location);
        });

        operations.put("give_item", (ctx, node) -> {
            Player target = ctx.getInputValue(node, "target", Player.class, null);
            String materialName = ctx.getInputValue(node, "material", String.class, "STONE");
            Integer amount = ctx.getInputValue(node, "amount", Integer.class, 1);
            if (target == null) return;
            Material material = Material.getMaterial(materialName.toUpperCase());
            if (material != null) {
                target.getInventory().addItem(new ItemStack(material, Math.max(1, amount)));
            }
        });

        operations.put("player_set_walking_speed", (ctx, node) -> {
            Player target = ctx.getInputValue(node, "target", Player.class, null);
            Double speed = ctx.getInputValue(node, "speed", Double.class, 0.2);
            if (target != null) {
                target.setWalkSpeed(speed.floatValue());
            }
        });

        operations.put("player_set_flying_speed", (ctx, node) -> {
            Player target = ctx.getInputValue(node, "target", Player.class, null);
            Double speed = ctx.getInputValue(node, "speed", Double.class, 0.05);
            if (target != null) {
                target.setFlySpeed(speed.floatValue());
            }
        });

        operations.put("player_execute_command", (ctx, node) -> {
            Player target = ctx.getInputValue(node, "target", Player.class, null);
            String command = ctx.getInputValue(node, "command", String.class, "");
            Boolean asOp = ctx.getInputValue(node, "as_op", Boolean.class, false);
            if (target == null || command.isEmpty()) {
                ctx.setOutput(node, "success", false);
                return;
            }
            boolean success;
            if (Bukkit.isPrimaryThread()) {
                boolean wasOp = target.isOp();
                if (asOp) target.setOp(true);
                success = Bukkit.dispatchCommand(target, command);
                if (asOp && !wasOp) target.setOp(false);
            } else {
                Bukkit.getScheduler().runTask(ReSync.getInstance(), () -> {
                    boolean wasOp = target.isOp();
                    if (asOp) target.setOp(true);
                    Bukkit.dispatchCommand(target, command);
                    if (asOp && !wasOp) target.setOp(false);
                });
                success = true;
            }
            ctx.setOutput(node, "success", success);
        });

        operations.put("player_chat", (ctx, node) -> {
            Player target = ctx.getInputValue(node, "target", Player.class, null);
            String message = ctx.getInputValue(node, "message", String.class, "");
            if (target == null || message.isEmpty()) return;
            if (Bukkit.isPrimaryThread()) {
                target.chat(message);
            } else {
                Bukkit.getScheduler().runTask(ReSync.getInstance(), () -> target.chat(message));
            }
        });

        operations.put("player_say", (ctx, node) -> {
            Player target = ctx.getInputValue(node, "target", Player.class, null);
            String message = ctx.getInputValue(node, "message", String.class, "");
            if (target == null || message.isEmpty()) return;
            if (Bukkit.isPrimaryThread()) {
                target.chat("/say " + message);
            } else {
                Bukkit.getScheduler().runTask(ReSync.getInstance(), () -> target.chat("/say " + message));
            }
        });

        operations.put("player_send_resourcepack", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            String url = ctx.getInputValue(node, "url", String.class, "");
            if (player != null && !url.isEmpty()) {
                runSync(() -> player.setResourcePack(url));
            }
        });

        operations.put("player_get_exp_level", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            int level = player == null ? 0 : callSync(player::getLevel);
            ctx.setOutput(node, "level", level);
        });

        operations.put("player_get_exp_to_level", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            int expNeeded = player == null ? 0 : callSync(player::getExpToLevel);
            ctx.setOutput(node, "exp_needed", expNeeded);
        });

        operations.put("player_get_total_exp", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            int totalExp = player == null ? 0 : callSync(player::getTotalExperience);
            ctx.setOutput(node, "total_exp", totalExp);
        });

        operations.put("player_show_bossbar", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            String title = ctx.getInputValue(node, "title", String.class, "");
            Double progress = ctx.getInputValue(node, "progress", Double.class, 1.0);
            String colorName = ctx.getInputValue(node, "color", String.class, "WHITE");
            String styleName = ctx.getInputValue(node, "style", String.class, "SOLID");
            if (player != null) {
                try {
                    BarColor color = BarColor.valueOf(colorName.toUpperCase());
                    BarStyle style = BarStyle.valueOf(styleName.toUpperCase());
                    String bossbarId = UUID.randomUUID().toString();
                    runSync(() -> {
                        BossBar bossBar = Bukkit.createBossBar(title, color, style);
                        bossBar.setProgress(Math.max(0, Math.min(1, progress)));
                        bossBar.addPlayer(player);
                        BOSS_BARS.put(bossbarId, bossBar);
                    });
                    ctx.setOutput(node, "bossbar_id", bossbarId);
                } catch (IllegalArgumentException ignored) {
                }
            }
        });

        operations.put("player_hide_bossbar", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            String bossbarId = ctx.getInputValue(node, "bossbar_id", String.class, "");
            if (player != null && !bossbarId.isEmpty()) {
                runSync(() -> {
                    BossBar bossBar = BOSS_BARS.remove(bossbarId);
                    if (bossBar != null) {
                        bossBar.removePlayer(player);
                        bossBar.removeAll();
                    }
                });
            }
        });

        operations.put("player_update_bossbar", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            String bossbarId = ctx.getInputValue(node, "bossbar_id", String.class, "");
            String newTitle = ctx.getInputValue(node, "new_title", String.class, null);
            Double newProgress = ctx.getInputValue(node, "new_progress", Double.class, null);
            if (player != null && !bossbarId.isEmpty()) {
                runSync(() -> {
                    BossBar bossBar = BOSS_BARS.get(bossbarId);
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
        });

        operations.put("player_state", (ctx, node) -> {
            String property = ctx.getInputValue(node, "property", String.class, "");
            String action = ctx.getInputValue(node, "action", String.class, "get");
            Player target = ctx.getInputValue(node, "target", Player.class, null);
            boolean success = false;
            Object result = null;
            if (target != null && property != null && action != null) {
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
                            try {
                                GameMode gm = GameMode.valueOf(modeName.toUpperCase());
                                runSync(() -> target.setGameMode(gm));
                                success = true;
                            } catch (IllegalArgumentException ignored) {
                            }
                        }
                        case "food_level" -> {
                            Integer level = ctx.getInputValue(node, "value", Integer.class, 20);
                            runSync(() -> target.setFoodLevel(Math.max(0, Math.min(20, level))));
                            success = true;
                        }
                        case "saturation" -> {
                            Float saturation = ctx.getInputValue(node, "value", Float.class, 20.0f);
                            runSync(() -> target.setSaturation(Math.max(0, Math.min(20, saturation))));
                            success = true;
                        }
                        case "exhaustion" -> {
                            Float exhaustion = ctx.getInputValue(node, "value", Float.class, 0.0f);
                            runSync(() -> target.setExhaustion(Math.max(0, exhaustion)));
                            success = true;
                        }
                        case "health" -> {
                            Double health = ctx.getInputValue(node, "value", Double.class, 20.0);
                            FlowMutations.setHealth(ctx, target, health);
                            success = true;
                        }
                        case "max_health" -> {
                            Double maxHealth = ctx.getInputValue(node, "value", Double.class, 20.0);
                            runSync(() -> target.setMaxHealth(Math.max(1, maxHealth)));
                            success = true;
                        }
                        case "absorption" -> {
                            Double absorption = ctx.getInputValue(node, "value", Double.class, 0.0);
                            FlowMutations.setAbsorption(ctx, target, absorption);
                            success = true;
                        }
                        case "walk_speed" -> {
                            Float speed = ctx.getInputValue(node, "value", Float.class, 0.2f);
                            runSync(() -> target.setWalkSpeed(Math.max(-1, Math.min(1, speed))));
                            success = true;
                        }
                        case "fly_speed" -> {
                            Float speed = ctx.getInputValue(node, "value", Float.class, 0.1f);
                            runSync(() -> target.setFlySpeed(Math.max(-1, Math.min(1, speed))));
                            success = true;
                        }
                        case "fire_ticks" -> {
                            Integer ticks = ctx.getInputValue(node, "value", Integer.class, 0);
                            runSync(() -> target.setFireTicks(ticks));
                            success = true;
                        }
                        case "air_ticks" -> {
                            Integer ticks = ctx.getInputValue(node, "value", Integer.class, 300);
                            runSync(() -> target.setRemainingAir(Math.max(-20, ticks)));
                            success = true;
                        }
                        case "no_damage_ticks" -> {
                            Integer ticks = ctx.getInputValue(node, "value", Integer.class, 0);
                            FlowMutations.noDamageTicks(ctx, target, ticks);
                            success = true;
                        }
                        case "freeze_state" -> {
                            Boolean enabled = ctx.getInputValue(node, "enabled", Boolean.class, true);
                            runSync(() -> {
                                if (enabled) {
                                    target.setWalkSpeed(0);
                                    target.setFlySpeed(0);
                                } else {
                                    target.setWalkSpeed(0.2f);
                                    target.setFlySpeed(0.1f);
                                }
                            });
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
                            if (location != null) {
                                runSync(() -> target.setCompassTarget(location));
                                success = true;
                            }
                        }
                        case "xp" -> {
                            Integer level = ctx.getInputValue(node, "level", Integer.class, 0);
                            Float points = ctx.getInputValue(node, "points", Float.class, 0.0f);
                            runSync(() -> {
                                target.setLevel(Math.max(0, level));
                                target.setExp(Math.max(0, Math.min(1, points)));
                            });
                            success = true;
                        }
                        case "total_exp" -> {
                            Integer exp = ctx.getInputValue(node, "value", Integer.class, 0);
                            runSync(() -> target.setTotalExperience(Math.max(0, exp)));
                            success = true;
                        }
                        default -> {
                        }
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
                        case "freeze_state" -> result = callSync(() -> target.getWalkSpeed() == 0 && target.getFlySpeed() == 0);
                        case "flight_state" -> result = callSync(target::getAllowFlight);
                        case "compass_target" -> result = callSync(target::getCompassTarget);
                        case "xp" -> result = callSync(target::getLevel);
                        case "total_exp" -> result = callSync(target::getTotalExperience);
                        default -> {
                        }
                    }
                    success = result != null || !property.isBlank();
                }
            }
            ctx.setOutput(node, "success", success);
            ctx.setOutput(node, "result", result);
            if (!"set".equalsIgnoreCase(action) && result != null && property != null && !property.isBlank()) {
                ctx.setOutput(node, property, result);
            }
        });

        operations.put("player_movement", (ctx, node) -> {
            String mode = ctx.getInputValue(node, "mode", String.class, "");
            Player target = ctx.getInputValue(node, "target", Player.class, null);
            boolean success = false;
            if (target != null) {
                switch (mode.toLowerCase()) {
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
                        Location finalLocation = location;
                        runSync(() -> target.teleport(finalLocation));
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
                        runSync(() -> {
                            Vector direction = inputDirection != null ? inputDirection.clone() : target.getLocation().getDirection();
                            if (direction.lengthSquared() > 0) {
                                direction.normalize();
                            }
                            FlowMutations.applyVelocity(ctx, target, direction.multiply(strength));
                        });
                        success = true;
                    }
                    case "spin" -> {
                        Float yaw = ctx.getInputValue(node, "yaw", Float.class, 0.0f);
                        Float pitch = ctx.getInputValue(node, "pitch", Float.class, 0.0f);
                        Boolean resetVelocity = ctx.getInputValue(node, "reset_velocity", Boolean.class, false);
                        runSync(() -> {
                            Vector velocity = resetVelocity ? null : target.getVelocity().clone();
                            Location loc = target.getLocation();
                            loc.setYaw(loc.getYaw() + yaw);
                            loc.setPitch(loc.getPitch() + pitch);
                            target.teleport(loc);
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
                        Boolean resetVelocity = ctx.getInputValue(node, "reset_velocity", Boolean.class, false);
                        runSync(() -> {
                            Vector velocity = resetVelocity ? null : target.getVelocity().clone();
                            Location loc = target.getLocation();
                            loc.setYaw(yaw);
                            loc.setPitch(pitch);
                            target.teleport(loc);
                            if (velocity != null) {
                                FlowMutations.applyVelocity(ctx, target, velocity);
                            }
                        });
                        success = true;
                    }
                    default -> {
                    }
                }
            }
            ctx.setOutput(node, "success", success);
        });

        operations.put("player_potion", (ctx, node) -> {
            String mode = ctx.getInputValue(node, "mode", String.class, "");
            Player target = ctx.getInputValue(node, "target", Player.class, null);
            boolean success = false;
            boolean hasEffect = false;
            int amplifier = 0;
            if (target != null) {
                switch (mode.toLowerCase()) {
                    case "add" -> {
                        String effectType = ctx.getInputValue(node, "effect_type", String.class, "SPEED");
                        Integer duration = ctx.getInputValue(node, "duration_ticks", Integer.class, 600);
                        Integer amp = ctx.getInputValue(node, "amplifier", Integer.class, 0);
                        PotionEffectType type = PotionEffectType.getByName(effectType.toUpperCase());
                        if (type != null) {
                            PotionEffect effect = new PotionEffect(type, Math.max(0, duration), Math.max(0, amp));
                            runSync(() -> target.addPotionEffect(effect));
                            success = true;
                        }
                    }
                    case "clear" -> {
                        runSync(() -> target.getActivePotionEffects().forEach(effect -> target.removePotionEffect(effect.getType())));
                        success = true;
                    }
                    case "has" -> {
                        String effectType = ctx.getInputValue(node, "effect_type", String.class, "SPEED");
                        PotionEffectType type = PotionEffectType.getByName(effectType.toUpperCase());
                        if (type != null) {
                            hasEffect = callSync(() -> target.hasPotionEffect(type));
                            if (hasEffect) {
                                PotionEffect potionEffect = callSync(() -> target.getPotionEffect(type));
                                amplifier = potionEffect != null ? potionEffect.getAmplifier() : 0;
                            }
                            success = true;
                        }
                    }
                    default -> {
                    }
                }
            }
            ctx.setOutput(node, "success", success);
            ctx.setOutput(node, "has_effect", hasEffect);
            ctx.setOutput(node, "effect_amplifier", amplifier);
        });

        operations.put("player_advancement", (ctx, node) -> {
            String mode = ctx.getInputValue(node, "mode", String.class, "");
            Player target = ctx.getInputValue(node, "target", Player.class, null);
            String key = ctx.getInputValue(node, "advancement_key", String.class, "");
            boolean success = false;
            boolean hasAdvancement = false;
            if (target != null && !key.isEmpty()) {
                NamespacedKey namespacedKey = NamespacedKey.fromString(key.toLowerCase());
                if (namespacedKey != null) {
                    Advancement advancement = Bukkit.getAdvancement(namespacedKey);
                    if (advancement != null) {
                        switch (mode.toLowerCase()) {
                            case "grant" -> {
                                runSync(() -> target.getAdvancementProgress(advancement).awardCriteria("impossible"));
                                success = true;
                            }
                            case "revoke" -> {
                                runSync(() -> target.getAdvancementProgress(advancement).revokeCriteria("impossible"));
                                success = true;
                            }
                            case "has" -> {
                                hasAdvancement = callSync(() -> target.getAdvancementProgress(advancement).isDone());
                                success = true;
                            }
                            default -> {
                            }
                        }
                    }
                }
            }
            ctx.setOutput(node, "success", success);
            ctx.setOutput(node, "has_advancement", hasAdvancement);
        });

        operations.put("player_cooldown", (ctx, node) -> {
            String mode = ctx.getInputValue(node, "mode", String.class, "");
            Player target = ctx.getInputValue(node, "target", Player.class, null);
            String materialName = ctx.getInputValue(node, "material", String.class, "");
            boolean success = false;
            boolean hasCooldown = false;
            int remainingTicks = 0;
            if (target != null && !materialName.isEmpty()) {
                Material material = Material.matchMaterial(materialName.toUpperCase());
                if (material != null) {
                    Material finalMaterial = material;
                    switch (mode.toLowerCase()) {
                        case "set" -> {
                            Integer ticks = ctx.getInputValue(node, "ticks", Integer.class, 0);
                            runSync(() -> target.setCooldown(finalMaterial, Math.max(0, ticks)));
                            success = true;
                        }
                        case "has" -> {
                            hasCooldown = callSync(() -> target.hasCooldown(finalMaterial));
                            success = true;
                        }
                        case "get" -> {
                            remainingTicks = callSync(() -> target.getCooldown(finalMaterial));
                            success = true;
                        }
                        case "clear" -> {
                            runSync(() -> target.setCooldown(finalMaterial, 0));
                            success = true;
                        }
                        default -> {
                        }
                    }
                }
            }
            ctx.setOutput(node, "success", success);
            ctx.setOutput(node, "has_cooldown", hasCooldown);
            ctx.setOutput(node, "remaining_ticks", remainingTicks);
        });

        operations.put("player_send_message", (ctx, node) -> {
            Player target = ctx.getInputValue(node, "target", Player.class, null);
            String text = ctx.getInputValue(node, "text", String.class, "");
            if (target != null && !text.isEmpty()) {
                Component component = TextFormatter.parse(text);
                if (Bukkit.isPrimaryThread()) {
                    target.sendMessage(component);
                } else {
                    Bukkit.getScheduler().runTask(ReSync.getInstance(), () -> target.sendMessage(component));
                }
            }
        });

        operations.put("player_send_action_bar", (ctx, node) -> {
            Player target = ctx.getInputValue(node, "target", Player.class, null);
            String text = ctx.getInputValue(node, "text", String.class, "");
            if (target != null && !text.isEmpty()) {
                Component component = TextFormatter.parse(text);
                if (Bukkit.isPrimaryThread()) {
                    target.sendActionBar(component);
                } else {
                    Bukkit.getScheduler().runTask(ReSync.getInstance(), () -> target.sendActionBar(component));
                }
            }
        });

        operations.put("player_send_title", (ctx, node) -> {
            Player target = ctx.getInputValue(node, "target", Player.class, null);
            String title = ctx.getInputValue(node, "title", String.class, "");
            String subtitle = ctx.getInputValue(node, "subtitle", String.class, "");
            if (target != null) {
                target.showTitle(Title.title(TextFormatter.parse(title), TextFormatter.parse(subtitle)));
            }
        });

        operations.put("player_send_sound", (ctx, node) -> {
            Player target = ctx.getInputValue(node, "target", Player.class, null);
            if (target == null) return;
            String soundName = ctx.getInputValue(node, "sound", String.class, "block.amethyst_block.chime");
            Float volume = ctx.getInputValue(node, "volume", Float.class, 1.0f);
            Float pitch = ctx.getInputValue(node, "pitch", Float.class, 1.0f);
            try {
                Sound sound = Sound.valueOf(soundName.toUpperCase().replace('.', '_'));
                Location loc = target.getLocation();
                if (Bukkit.isPrimaryThread()) {
                    target.playSound(loc, sound, volume, pitch);
                } else {
                    Bukkit.getScheduler().runTask(ReSync.getInstance(), () -> target.playSound(loc, sound, volume, pitch));
                }
            } catch (IllegalArgumentException ignored) {
            }
        });

        operations.put("player_send_particle", (ctx, node) -> {
            Player target = ctx.getInputValue(node, "target", Player.class, null);
            if (target == null) return;
            Map<String, Object> inputs = new HashMap<>(node.getInputValues() != null ? node.getInputValues() : Map.of());
            inputs.put("mode", "player");
            inputs.put("player", target);
            inputs.put("location", target.getLocation().clone().add(0, 1, 0));
            FlowNode particleNode = new FlowNode("particle.apply", node.getX(), node.getY(), inputs);
            particleNode.setHandlerConfig(Map.of("operation", "particle_apply"));
            particleHandler.execute(ctx, particleNode);
        });

        operations.put("player_send_book", (ctx, node) -> {
            Player target = ctx.getInputValue(node, "target", Player.class, null);
            ItemStack book = ctx.getInputValue(node, "book", ItemStack.class, null);
            if (target != null && book != null && book.getType() == Material.WRITTEN_BOOK) {
                if (Bukkit.isPrimaryThread()) {
                    target.openBook(book);
                } else {
                    Bukkit.getScheduler().runTask(ReSync.getInstance(), () -> target.openBook(book));
                }
            }
        });

        operations.put("player_send_sign", (ctx, node) -> {
            Player target = ctx.getInputValue(node, "target", Player.class, null);
            if (target == null) return;
            Runnable action = () -> {
                try {
                    Sign sign = (Sign) target.getLocation().getBlock().getState();
                    target.openSign(sign);
                } catch (Exception ignored) {
                }
            };
            if (Bukkit.isPrimaryThread()) {
                action.run();
            } else {
                Bukkit.getScheduler().runTask(ReSync.getInstance(), action);
            }
        });

        operations.put("player_send_raw_json", (ctx, node) -> {
            Player target = ctx.getInputValue(node, "target", Player.class, null);
            String json = ctx.getInputValue(node, "json", String.class, "");
            if (target != null && !json.isEmpty()) {
                try {
                    Component component = GsonComponentSerializer.gson().deserialize(json);
                    if (Bukkit.isPrimaryThread()) {
                        target.sendMessage(component);
                    } else {
                        Bukkit.getScheduler().runTask(ReSync.getInstance(), () -> target.sendMessage(component));
                    }
                } catch (Exception ignored) {
                }
            }
        });

        operations.put("player_give_item", (ctx, node) -> {
            Player target = ctx.getInputValue(node, "target", Player.class, null);
            ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
            if (target != null && item != null && item.getType() != Material.AIR) {
                runSync(() -> target.getInventory().addItem(item.clone()));
            }
        });

        operations.put("player_take_item", (ctx, node) -> {
            Player target = ctx.getInputValue(node, "target", Player.class, null);
            ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
            Integer amount = ctx.getInputValue(node, "amount", Integer.class, 1);
            if (target != null && item != null && amount > 0) {
                ItemStack toRemove = item.clone();
                toRemove.setAmount(amount);
                runSync(() -> target.getInventory().removeItem(toRemove));
            }
        });

        operations.put("player_set_item", (ctx, node) -> {
            Player target = ctx.getInputValue(node, "target", Player.class, null);
            Integer slot = ctx.getInputValue(node, "slot", Integer.class, 0);
            ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
            if (target != null && slot >= 0 && slot < 36) {
                runSync(() -> target.getInventory().setItem(slot, item));
            }
        });

        operations.put("player_clear_slot", (ctx, node) -> {
            Player target = ctx.getInputValue(node, "target", Player.class, null);
            Integer slot = ctx.getInputValue(node, "slot", Integer.class, 0);
            if (target != null && slot >= 0 && slot < 36) {
                runSync(() -> target.getInventory().setItem(slot, null));
            }
        });

        operations.put("player_swap_items", (ctx, node) -> {
            Player target = ctx.getInputValue(node, "target", Player.class, null);
            Integer slot1 = ctx.getInputValue(node, "slot1", Integer.class, 0);
            Integer slot2 = ctx.getInputValue(node, "slot2", Integer.class, 1);
            if (target != null && slot1 >= 0 && slot1 < 36 && slot2 >= 0 && slot2 < 36) {
                runSync(() -> {
                    ItemStack item1 = target.getInventory().getItem(slot1);
                    ItemStack item2 = target.getInventory().getItem(slot2);
                    target.getInventory().setItem(slot1, item2);
                    target.getInventory().setItem(slot2, item1);
                });
            }
        });

        operations.put("player_set_helmet", (ctx, node) -> {
            Player target = ctx.getInputValue(node, "target", Player.class, null);
            ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
            if (target != null) {
                runSync(() -> target.getInventory().setItem(EquipmentSlot.HEAD, item));
            }
        });

        operations.put("player_set_chestplate", (ctx, node) -> {
            Player target = ctx.getInputValue(node, "target", Player.class, null);
            ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
            if (target != null) {
                runSync(() -> target.getInventory().setItem(EquipmentSlot.CHEST, item));
            }
        });

        operations.put("player_set_leggings", (ctx, node) -> {
            Player target = ctx.getInputValue(node, "target", Player.class, null);
            ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
            if (target != null) {
                runSync(() -> target.getInventory().setItem(EquipmentSlot.LEGS, item));
            }
        });

        operations.put("player_set_boots", (ctx, node) -> {
            Player target = ctx.getInputValue(node, "target", Player.class, null);
            ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
            if (target != null) {
                runSync(() -> target.getInventory().setItem(EquipmentSlot.FEET, item));
            }
        });

        operations.put("player_set_mainhand", (ctx, node) -> {
            Player target = ctx.getInputValue(node, "target", Player.class, null);
            ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
            if (target != null) {
                runSync(() -> target.getInventory().setItemInMainHand(item));
            }
        });

        operations.put("player_set_offhand", (ctx, node) -> {
            Player target = ctx.getInputValue(node, "target", Player.class, null);
            ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
            if (target != null) {
                runSync(() -> target.getInventory().setItemInOffHand(item));
            }
        });

        operations.put("player_set_inventory_title", (ctx, node) -> {
            Player target = ctx.getInputValue(node, "target", Player.class, null);
            ctx.getInputValue(node, "title", String.class, "Inventory");
            if (target != null) {
                runSync(() -> target.openInventory(target.getInventory()));
            }
        });

        operations.put("player_set_armor_color", (ctx, node) -> {
            Player target = ctx.getInputValue(node, "target", Player.class, null);
            EquipmentSlot slot = ctx.getInputValue(node, "slot", EquipmentSlot.class, EquipmentSlot.CHEST);
            Integer red = ctx.getInputValue(node, "red", Integer.class, 255);
            Integer green = ctx.getInputValue(node, "green", Integer.class, 255);
            Integer blue = ctx.getInputValue(node, "blue", Integer.class, 255);
            if (target != null) {
                runSync(() -> {
                    ItemStack item = target.getInventory().getItem(slot);
                    if (item != null && item.getItemMeta() instanceof LeatherArmorMeta meta) {
                        meta.setColor(Color.fromRGB(red, green, blue));
                        item.setItemMeta(meta);
                    }
                });
            }
        });

        operations.put("player_repair_item", (ctx, node) -> {
            ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
            if (item != null) {
                runSync(() -> item.setDurability((short) 0));
            }
            ctx.setOutput(node, "item", item);
        });

        operations.put("player_enchant_item", (ctx, node) -> {
            ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
            String enchantName = ctx.getInputValue(node, "enchantment", String.class, "");
            Integer level = ctx.getInputValue(node, "level", Integer.class, 1);
            if (item != null && item.hasItemMeta()) {
                Enchantment enchant = Enchantment.getByKey(NamespacedKey.minecraft(enchantName.toLowerCase()));
                if (enchant != null) {
                    runSync(() -> {
                        ItemMeta meta = item.getItemMeta();
                        if (meta != null) {
                            meta.addEnchant(enchant, level, true);
                            item.setItemMeta(meta);
                        }
                    });
                }
            }
            ctx.setOutput(node, "item", item);
        });

        operations.put("player_unenchant_item", (ctx, node) -> {
            ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
            String enchantName = ctx.getInputValue(node, "enchantment", String.class, "");
            if (item != null && item.hasItemMeta()) {
                Enchantment enchant = Enchantment.getByKey(NamespacedKey.minecraft(enchantName.toLowerCase()));
                if (enchant != null) {
                    runSync(() -> {
                        ItemMeta meta = item.getItemMeta();
                        if (meta != null) {
                            meta.removeEnchant(enchant);
                            item.setItemMeta(meta);
                        }
                    });
                }
            }
            ctx.setOutput(node, "item", item);
        });

        operations.put("player_clear_enchants", (ctx, node) -> {
            ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
            if (item != null && item.hasItemMeta()) {
                runSync(() -> {
                    ItemMeta meta = item.getItemMeta();
                    if (meta != null) {
                        meta.getEnchants().keySet().forEach(meta::removeEnchant);
                        item.setItemMeta(meta);
                    }
                });
            }
            ctx.setOutput(node, "item", item);
        });

        operations.put("player_set_item_name", (ctx, node) -> {
            ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
            String name = ctx.getInputValue(node, "name", String.class, "");
            if (item != null) {
                runSync(() -> {
                    ItemMeta meta = item.getItemMeta();
                    if (meta != null) {
                        meta.displayName(TextFormatter.parse(name));
                        item.setItemMeta(meta);
                    }
                });
            }
            ctx.setOutput(node, "item", item);
        });

        operations.put("player_set_item_lore", (ctx, node) -> {
            ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
            String lore = ctx.getInputValue(node, "lore", String.class, "");
            if (item != null) {
                runSync(() -> {
                    ItemMeta meta = item.getItemMeta();
                    if (meta != null) {
                        meta.lore(TextFormatter.parseLines(lore));
                        item.setItemMeta(meta);
                    }
                });
            }
            ctx.setOutput(node, "item", item);
        });

        operations.put("player_set_item_flags", (ctx, node) -> {
            ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
            ctx.getInputValue(node, "flags", String.class, "");
            if (item != null) {
                runSync(() -> {
                    ItemMeta meta = item.getItemMeta();
                    if (meta != null) {
                        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
                        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
                        meta.addItemFlags(ItemFlag.HIDE_UNBREAKABLE);
                        item.setItemMeta(meta);
                    }
                });
            }
            ctx.setOutput(node, "item", item);
        });

        operations.put("player_set_item_custom_model", (ctx, node) -> {
            ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
            Integer modelData = ctx.getInputValue(node, "model_data", Integer.class, 0);
            if (item != null) {
                runSync(() -> {
                    ItemMeta meta = item.getItemMeta();
                    if (meta != null) {
                        meta.setCustomModelData(modelData);
                        item.setItemMeta(meta);
                    }
                });
            }
            ctx.setOutput(node, "item", item);
        });

        operations.put("player_set_item_unbreakable", (ctx, node) -> {
            ItemStack item = ctx.getInputValue(node, "item", ItemStack.class, null);
            Boolean unbreakable = ctx.getInputValue(node, "unbreakable", Boolean.class, true);
            if (item != null) {
                runSync(() -> {
                    ItemMeta meta = item.getItemMeta();
                    if (meta != null) {
                        meta.setUnbreakable(unbreakable);
                        item.setItemMeta(meta);
                    }
                });
            }
            ctx.setOutput(node, "item", item);
        });
    }

    public void registerTo(HandlerRegistry registry) {
        registry.register("PlayerActionHandler", this);
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

    private void runSync(Runnable action) {
        if (Bukkit.isPrimaryThread()) {
            action.run();
            return;
        }
        try {
            Bukkit.getScheduler().callSyncMethod(ReSync.getInstance(), () -> {
                action.run();
                return null;
            }).get();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private <T> T callSync(Supplier<T> supplier) {
        if (Bukkit.isPrimaryThread()) {
            return supplier.get();
        }
        try {
            return Bukkit.getScheduler().callSyncMethod(ReSync.getInstance(), supplier::get).get();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
