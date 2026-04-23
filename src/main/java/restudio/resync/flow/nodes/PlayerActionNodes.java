package restudio.resync.flow.nodes;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;
import restudio.flow.data.FlowNode;
import restudio.flow.data.FlowType;
import restudio.resync.ReSync;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.registry.DefineNode;
import restudio.resync.flow.registry.FlowPin;
import restudio.resync.flow.registry.NodeDefinition;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

public class PlayerActionNodes {

    private static final Map<String, BossBar> BOSS_BARS = new ConcurrentHashMap<>();

    @DefineNode(id = "player_send_resourcepack", displayName = "Send Resource Pack", category = NodeDefinition.NodeCategory.ACTION,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "player", dataType = FlowType.PLAYER),
                    @FlowPin(name = "url", dataType = FlowType.STRING),
                    @FlowPin(name = "force", dataType = FlowType.BOOLEAN)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void playerSendResourcepack(FlowContext ctx, FlowNode node) {
        Player player = ctx.getInputValue(node, "player", Player.class, null);
        String url = ctx.getInputValue(node, "url", String.class, "");
        if (player != null && !url.isEmpty()) {
            runSync(() -> player.setResourcePack(url));
        }
        ctx.triggerOutput("flow");
    }

    @DefineNode(id = "player_get_exp_level", displayName = "Get Xp Level", category = NodeDefinition.NodeCategory.DATA,
            inputs = {@FlowPin(name = "player", dataType = FlowType.PLAYER)},
            outputs = {@FlowPin(name = "level", dataType = FlowType.NUMBER)})
    public void playerGetExpLevel(FlowContext ctx, FlowNode node) {
        Player player = ctx.getInputValue(node, "player", Player.class, null);
        int level = player == null ? 0 : callSync(player::getLevel);
        ctx.setOutput(node, "level", level);
    }

    @DefineNode(id = "player_get_exp_to_level", displayName = "Get Xp To Level", category = NodeDefinition.NodeCategory.DATA,
            inputs = {@FlowPin(name = "player", dataType = FlowType.PLAYER)},
            outputs = {@FlowPin(name = "exp_needed", dataType = FlowType.NUMBER)})
    public void playerGetExpToLevel(FlowContext ctx, FlowNode node) {
        Player player = ctx.getInputValue(node, "player", Player.class, null);
        int expNeeded = player == null ? 0 : callSync(player::getExpToLevel);
        ctx.setOutput(node, "exp_needed", expNeeded);
    }

    @DefineNode(id = "player_get_total_exp", displayName = "Get Total Xp", category = NodeDefinition.NodeCategory.DATA,
            inputs = {@FlowPin(name = "player", dataType = FlowType.PLAYER)},
            outputs = {@FlowPin(name = "total_exp", dataType = FlowType.NUMBER)})
    public void playerGetTotalExp(FlowContext ctx, FlowNode node) {
        Player player = ctx.getInputValue(node, "player", Player.class, null);
        int totalExp = player == null ? 0 : callSync(player::getTotalExperience);
        ctx.setOutput(node, "total_exp", totalExp);
    }

    @DefineNode(id = "player_show_bossbar", displayName = "Show Boss Bar", category = NodeDefinition.NodeCategory.ACTION,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "player", dataType = FlowType.PLAYER),
                    @FlowPin(name = "title", dataType = FlowType.STRING),
                    @FlowPin(name = "progress", dataType = FlowType.NUMBER),
                    @FlowPin(name = "color", dataType = FlowType.STRING),
                    @FlowPin(name = "style", dataType = FlowType.STRING)
            },
            outputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "bossbar_id", dataType = FlowType.STRING)
            })
    public void playerShowBossbar(FlowContext ctx, FlowNode node) {
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
        ctx.triggerOutput("flow");
    }

    @DefineNode(id = "player_hide_bossbar", displayName = "Hide Boss Bar", category = NodeDefinition.NodeCategory.ACTION,
            inputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW), @FlowPin(name = "player", dataType = FlowType.PLAYER), @FlowPin(name = "bossbar_id", dataType = FlowType.STRING)},
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void playerHideBossbar(FlowContext ctx, FlowNode node) {
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
        ctx.triggerOutput("flow");
    }

    @DefineNode(id = "player_update_bossbar", displayName = "Update Boss Bar", category = NodeDefinition.NodeCategory.ACTION,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "player", dataType = FlowType.PLAYER),
                    @FlowPin(name = "bossbar_id", dataType = FlowType.STRING),
                    @FlowPin(name = "new_title", dataType = FlowType.STRING),
                    @FlowPin(name = "new_progress", dataType = FlowType.NUMBER)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void playerUpdateBossbar(FlowContext ctx, FlowNode node) {
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
        ctx.triggerOutput("flow");
    }

    @DefineNode(id = "player_state", displayName = "Player State", category = NodeDefinition.NodeCategory.ACTION,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "mode", dataType = FlowType.STRING),
                    @FlowPin(name = "target", dataType = FlowType.PLAYER),
                    @FlowPin(name = "enabled", dataType = FlowType.BOOLEAN),
                    @FlowPin(name = "value", dataType = FlowType.NUMBER),
                    @FlowPin(name = "string_value", dataType = FlowType.STRING),
                    @FlowPin(name = "ticks", dataType = FlowType.NUMBER),
                    @FlowPin(name = "level", dataType = FlowType.NUMBER),
                    @FlowPin(name = "points", dataType = FlowType.NUMBER)
            },
            outputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "success", dataType = FlowType.BOOLEAN)
            })
    public void playerState(FlowContext ctx, FlowNode node) {
        String mode = ctx.getInputValue(node, "mode", String.class, "");
        Player target = ctx.getInputValue(node, "target", Player.class, null);
        boolean success = false;
        if (target != null) {
            switch (mode.toLowerCase()) {
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
                    String modeName = ctx.getInputValue(node, "string_value", String.class, "SURVIVAL");
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
                    runSync(() -> target.setHealth(Math.max(0, Math.min(target.getMaxHealth(), health))));
                    success = true;
                }
                case "max_health" -> {
                    Double maxHealth = ctx.getInputValue(node, "value", Double.class, 20.0);
                    runSync(() -> target.setMaxHealth(Math.max(1, maxHealth)));
                    success = true;
                }
                case "absorption" -> {
                    Double absorption = ctx.getInputValue(node, "value", Double.class, 0.0);
                    runSync(() -> target.setAbsorptionAmount(Math.max(0, absorption)));
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
                    Integer ticks = ctx.getInputValue(node, "ticks", Integer.class, 0);
                    runSync(() -> target.setFireTicks(ticks));
                    success = true;
                }
                case "air_ticks" -> {
                    Integer ticks = ctx.getInputValue(node, "ticks", Integer.class, 300);
                    runSync(() -> target.setRemainingAir(Math.max(-20, ticks)));
                    success = true;
                }
                case "no_damage_ticks" -> {
                    Integer ticks = ctx.getInputValue(node, "ticks", Integer.class, 0);
                    runSync(() -> target.setNoDamageTicks(ticks));
                    success = true;
                }
                case "freeze" -> {
                    runSync(() -> {
                        target.setWalkSpeed(0);
                        target.setFlySpeed(0);
                    });
                    success = true;
                }
                case "unfreeze" -> {
                    runSync(() -> {
                        target.setWalkSpeed(0.2f);
                        target.setFlySpeed(0.1f);
                    });
                    success = true;
                }
                case "allow_flight" -> {
                    Boolean allowed = ctx.getInputValue(node, "enabled", Boolean.class, true);
                    runSync(() -> target.setAllowFlight(allowed));
                    success = true;
                }
                case "deny_flight" -> {
                    runSync(() -> {
                        target.setAllowFlight(false);
                        target.setFlying(false);
                    });
                    success = true;
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
                case "give_exp" -> {
                    Integer exp = ctx.getInputValue(node, "value", Integer.class, 0);
                    runSync(() -> target.giveExp(Math.max(0, exp)));
                    success = true;
                }
                case "compass_target" -> {
                    Location location = ctx.getInputValue(node, "string_value", Location.class, null);
                    if (location != null) {
                        runSync(() -> target.setCompassTarget(location));
                        success = true;
                    }
                }
                case "reset_compass" -> {
                    runSync(() -> target.setCompassTarget(target.getWorld().getSpawnLocation()));
                    success = true;
                }
                default -> {
                }
            }
        }
        ctx.setOutput(node, "success", success);
        ctx.triggerOutput("flow");
    }

    @DefineNode(id = "player_movement", displayName = "Player Movement", category = NodeDefinition.NodeCategory.ACTION,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "mode", dataType = FlowType.STRING),
                    @FlowPin(name = "target", dataType = FlowType.PLAYER),
                    @FlowPin(name = "location", dataType = FlowType.LOCATION),
                    @FlowPin(name = "x", dataType = FlowType.NUMBER),
                    @FlowPin(name = "y", dataType = FlowType.NUMBER),
                    @FlowPin(name = "z", dataType = FlowType.NUMBER),
                    @FlowPin(name = "yaw", dataType = FlowType.NUMBER),
                    @FlowPin(name = "pitch", dataType = FlowType.NUMBER),
                    @FlowPin(name = "vx", dataType = FlowType.NUMBER),
                    @FlowPin(name = "vy", dataType = FlowType.NUMBER),
                    @FlowPin(name = "vz", dataType = FlowType.NUMBER),
                    @FlowPin(name = "strength", dataType = FlowType.NUMBER),
                    @FlowPin(name = "direction_vector", dataType = FlowType.LOCATION)
            },
            outputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "success", dataType = FlowType.BOOLEAN)
            })
    public void playerMovement(FlowContext ctx, FlowNode node) {
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
                    runSync(() -> target.setVelocity(new Vector(vx, vy, vz)));
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
                        target.setVelocity(direction.multiply(strength));
                    });
                    success = true;
                }
                case "spin" -> {
                    Float yaw = ctx.getInputValue(node, "yaw", Float.class, 0.0f);
                    Float pitch = ctx.getInputValue(node, "pitch", Float.class, 0.0f);
                    runSync(() -> {
                        Location loc = target.getLocation();
                        loc.setYaw(loc.getYaw() + yaw);
                        loc.setPitch(loc.getPitch() + pitch);
                        target.teleport(loc);
                    });
                    success = true;
                }
                case "set_rotation" -> {
                    Location base = callSync(target::getLocation);
                    Float yaw = ctx.getInputValue(node, "yaw", Float.class, base.getYaw());
                    Float pitch = ctx.getInputValue(node, "pitch", Float.class, base.getPitch());
                    runSync(() -> {
                        Location loc = target.getLocation();
                        loc.setYaw(yaw);
                        loc.setPitch(pitch);
                        target.teleport(loc);
                    });
                    success = true;
                }
                default -> {
                }
            }
        }
        ctx.setOutput(node, "success", success);
        ctx.triggerOutput("flow");
    }

    @DefineNode(id = "player_potion", displayName = "Player Potion", category = NodeDefinition.NodeCategory.ACTION,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "mode", dataType = FlowType.STRING),
                    @FlowPin(name = "target", dataType = FlowType.PLAYER),
                    @FlowPin(name = "effect_type", dataType = FlowType.STRING),
                    @FlowPin(name = "duration_ticks", dataType = FlowType.NUMBER),
                    @FlowPin(name = "amplifier", dataType = FlowType.NUMBER)
            },
            outputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "success", dataType = FlowType.BOOLEAN),
                    @FlowPin(name = "has_effect", dataType = FlowType.BOOLEAN),
                    @FlowPin(name = "effect_amplifier", dataType = FlowType.NUMBER)
            })
    public void playerPotion(FlowContext ctx, FlowNode node) {
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
        ctx.triggerOutput("flow");
    }

    @DefineNode(id = "player_advancement", displayName = "Player Advancement", category = NodeDefinition.NodeCategory.ACTION,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "mode", dataType = FlowType.STRING),
                    @FlowPin(name = "target", dataType = FlowType.PLAYER),
                    @FlowPin(name = "advancement_key", dataType = FlowType.STRING)
            },
            outputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "success", dataType = FlowType.BOOLEAN),
                    @FlowPin(name = "has_advancement", dataType = FlowType.BOOLEAN)
            })
    public void playerAdvancement(FlowContext ctx, FlowNode node) {
        String mode = ctx.getInputValue(node, "mode", String.class, "");
        Player target = ctx.getInputValue(node, "target", Player.class, null);
        String key = ctx.getInputValue(node, "advancement_key", String.class, "");
        boolean success = false;
        boolean hasAdvancement = false;
        if (target != null && !key.isEmpty()) {
            org.bukkit.NamespacedKey namespacedKey = org.bukkit.NamespacedKey.fromString(key.toLowerCase());
            if (namespacedKey != null) {
                org.bukkit.advancement.Advancement advancement = Bukkit.getAdvancement(namespacedKey);
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
        ctx.triggerOutput("flow");
    }

    @DefineNode(id = "player_cooldown", displayName = "Player Cooldown", category = NodeDefinition.NodeCategory.ACTION,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "mode", dataType = FlowType.STRING),
                    @FlowPin(name = "target", dataType = FlowType.PLAYER),
                    @FlowPin(name = "material", dataType = FlowType.STRING),
                    @FlowPin(name = "ticks", dataType = FlowType.NUMBER)
            },
            outputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "success", dataType = FlowType.BOOLEAN),
                    @FlowPin(name = "has_cooldown", dataType = FlowType.BOOLEAN),
                    @FlowPin(name = "remaining_ticks", dataType = FlowType.NUMBER)
            })
    public void playerCooldown(FlowContext ctx, FlowNode node) {
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
                    default -> {
                    }
                }
            }
        }
        ctx.setOutput(node, "success", success);
        ctx.setOutput(node, "has_cooldown", hasCooldown);
        ctx.setOutput(node, "remaining_ticks", remainingTicks);
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
