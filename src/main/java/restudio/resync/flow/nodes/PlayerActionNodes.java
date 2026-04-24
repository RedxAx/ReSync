package restudio.resync.flow.nodes;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.advancement.Advancement;
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
import restudio.resync.flow.registry.VisibleWhen;

import java.util.Map;
import java.util.Locale;
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
                    @FlowPin(name = "property", dataType = FlowType.STRING, widget = NodeDefinition.WidgetType.DROPDOWN,
                            options = {"sprint", "sneak", "fly", "vanish", "glowing", "invulnerable", "gamemode", "food_level", "saturation", "exhaustion", "health", "max_health", "absorption", "walk_speed", "fly_speed", "fire_ticks", "air_ticks", "no_damage_ticks", "freeze_state", "flight_state", "compass_target", "xp", "total_exp"},
                            defaultValue = "sprint"),
                    @FlowPin(name = "action", dataType = FlowType.STRING, widget = NodeDefinition.WidgetType.DROPDOWN,
                            options = {"get", "set"},
                            defaultValue = "get"),
                    @FlowPin(name = "target", dataType = FlowType.PLAYER),
                    @FlowPin(name = "enabled", dataType = FlowType.BOOLEAN, widget = NodeDefinition.WidgetType.TOGGLE, defaultValue = "true",
                            visibleWhen = {
                                    @VisibleWhen(pin = "property", value = "sprint,sneak,fly,vanish,glowing,invulnerable,freeze_state,flight_state"),
                                    @VisibleWhen(pin = "action", value = "set")
                            }),
                    @FlowPin(name = "value", dataType = FlowType.NUMBER, defaultValue = "0",
                            visibleWhen = {
                                    @VisibleWhen(pin = "property", value = "food_level,saturation,exhaustion,health,max_health,absorption,walk_speed,fly_speed,fire_ticks,air_ticks,no_damage_ticks,total_exp"),
                                    @VisibleWhen(pin = "action", value = "set")
                            }),
                    @FlowPin(name = "gamemode", dataType = FlowType.STRING, widget = NodeDefinition.WidgetType.DROPDOWN,
                            optionsSource = "minecraft:gamemode",
                            visibleWhen = {
                                    @VisibleWhen(pin = "property", value = "gamemode"),
                                    @VisibleWhen(pin = "action", value = "set")
                            }),
                    @FlowPin(name = "compass_location", dataType = FlowType.LOCATION,
                            visibleWhen = {
                                    @VisibleWhen(pin = "property", value = "compass_target"),
                                    @VisibleWhen(pin = "action", value = "set")
                            }),
                    @FlowPin(name = "level", dataType = FlowType.NUMBER, defaultValue = "0",
                            visibleWhen = {
                                    @VisibleWhen(pin = "property", value = "xp"),
                                    @VisibleWhen(pin = "action", value = "set")
                            }),
                    @FlowPin(name = "points", dataType = FlowType.NUMBER, defaultValue = "0",
                            visibleWhen = {
                                    @VisibleWhen(pin = "property", value = "xp"),
                                    @VisibleWhen(pin = "action", value = "set")
                            })
            },
            outputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "success", dataType = FlowType.BOOLEAN),
                    @FlowPin(name = "result", dataType = FlowType.ANY,
                            visibleWhen = {@VisibleWhen(pin = "action", value = "get")})
            })
    public void playerState(FlowContext ctx, FlowNode node) {
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
                        runSync(() -> target.setNoDamageTicks(ticks));
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
        ctx.triggerOutput("flow");
    }

    @DefineNode(id = "player_movement", displayName = "Player Movement", category = NodeDefinition.NodeCategory.ACTION,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "mode", dataType = FlowType.STRING, widget = NodeDefinition.WidgetType.DROPDOWN,
                            options = {"teleport", "launch", "push", "spin", "set_rotation"},
                            defaultValue = "teleport"),
                    @FlowPin(name = "target", dataType = FlowType.PLAYER),
                    @FlowPin(name = "location", dataType = FlowType.LOCATION,
                            visibleWhen = {@VisibleWhen(pin = "mode", value = "teleport")}),
                    @FlowPin(name = "x", dataType = FlowType.NUMBER,
                            visibleWhen = {@VisibleWhen(pin = "mode", value = "teleport")}),
                    @FlowPin(name = "y", dataType = FlowType.NUMBER,
                            visibleWhen = {@VisibleWhen(pin = "mode", value = "teleport")}),
                    @FlowPin(name = "z", dataType = FlowType.NUMBER,
                            visibleWhen = {@VisibleWhen(pin = "mode", value = "teleport")}),
                    @FlowPin(name = "yaw", dataType = FlowType.NUMBER,
                            visibleWhen = {@VisibleWhen(pin = "mode", value = "teleport,spin,set_rotation")}),
                    @FlowPin(name = "pitch", dataType = FlowType.NUMBER,
                            visibleWhen = {@VisibleWhen(pin = "mode", value = "teleport,spin,set_rotation")}),
                    @FlowPin(name = "vx", dataType = FlowType.NUMBER,
                            visibleWhen = {@VisibleWhen(pin = "mode", value = "launch")}),
                    @FlowPin(name = "vy", dataType = FlowType.NUMBER,
                            visibleWhen = {@VisibleWhen(pin = "mode", value = "launch")}),
                    @FlowPin(name = "vz", dataType = FlowType.NUMBER,
                            visibleWhen = {@VisibleWhen(pin = "mode", value = "launch")}),
                    @FlowPin(name = "strength", dataType = FlowType.NUMBER,
                            visibleWhen = {@VisibleWhen(pin = "mode", value = "push")}),
                    @FlowPin(name = "direction_vector", dataType = FlowType.LOCATION,
                            visibleWhen = {@VisibleWhen(pin = "mode", value = "push")})
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
                    @FlowPin(name = "mode", dataType = FlowType.STRING, widget = NodeDefinition.WidgetType.DROPDOWN,
                            options = {"add", "clear", "has"},
                            defaultValue = "add"),
                    @FlowPin(name = "target", dataType = FlowType.PLAYER),
                    @FlowPin(name = "effect_type", dataType = FlowType.STRING, widget = NodeDefinition.WidgetType.DROPDOWN,
                            optionsSource = "minecraft:potion_effect",
                            visibleWhen = {@VisibleWhen(pin = "mode", value = "add,has")}),
                    @FlowPin(name = "duration_ticks", dataType = FlowType.NUMBER,
                            visibleWhen = {@VisibleWhen(pin = "mode", value = "add")}),
                    @FlowPin(name = "amplifier", dataType = FlowType.NUMBER,
                            visibleWhen = {@VisibleWhen(pin = "mode", value = "add")})
            },
            outputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "success", dataType = FlowType.BOOLEAN),
                    @FlowPin(name = "has_effect", dataType = FlowType.BOOLEAN,
                            visibleWhen = {@VisibleWhen(pin = "mode", value = "has")}),
                    @FlowPin(name = "effect_amplifier", dataType = FlowType.NUMBER,
                            visibleWhen = {@VisibleWhen(pin = "mode", value = "has")})
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
                    @FlowPin(name = "mode", dataType = FlowType.STRING, widget = NodeDefinition.WidgetType.DROPDOWN,
                            options = {"grant", "revoke", "has"},
                            defaultValue = "grant"),
                    @FlowPin(name = "target", dataType = FlowType.PLAYER),
                    @FlowPin(name = "advancement_key", dataType = FlowType.STRING, widget = NodeDefinition.WidgetType.SEARCHABLE_LIST,
                            optionsSource = "minecraft:advancement")
            },
            outputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "success", dataType = FlowType.BOOLEAN),
                    @FlowPin(name = "has_advancement", dataType = FlowType.BOOLEAN,
                            visibleWhen = {@VisibleWhen(pin = "mode", value = "has")})
            })
    public void playerAdvancement(FlowContext ctx, FlowNode node) {
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
        ctx.triggerOutput("flow");
    }

    @DefineNode(id = "player_cooldown", displayName = "Player Cooldown", category = NodeDefinition.NodeCategory.ACTION,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "mode", dataType = FlowType.STRING, widget = NodeDefinition.WidgetType.DROPDOWN,
                            options = {"set", "has", "get", "clear"},
                            defaultValue = "set"),
                    @FlowPin(name = "target", dataType = FlowType.PLAYER),
                    @FlowPin(name = "material", dataType = FlowType.STRING, widget = NodeDefinition.WidgetType.DROPDOWN,
                            optionsSource = "minecraft:material",
                            visibleWhen = {@VisibleWhen(pin = "mode", value = "set,has,get,clear")}),
                    @FlowPin(name = "ticks", dataType = FlowType.NUMBER,
                            visibleWhen = {@VisibleWhen(pin = "mode", value = "set")})
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
