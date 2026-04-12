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

    @DefineNode(id = "player_sprint", displayName = "Sprint", category = NodeDefinition.NodeCategory.ACTION,
            inputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW), @FlowPin(name = "target", dataType = FlowType.PLAYER), @FlowPin(name = "enabled", dataType = FlowType.BOOLEAN)},
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void playerSprint(FlowContext ctx, FlowNode node) {
        Player target = ctx.getInputValue(node, "target", Player.class, null);
        Boolean enabled = ctx.getInputValue(node, "enabled", Boolean.class, true);
        if (target != null) {
            runSync(() -> target.setSprinting(enabled));
        }
        ctx.triggerOutput("flow");
    }

    @DefineNode(id = "player_sneak", displayName = "Sneak", category = NodeDefinition.NodeCategory.ACTION,
            inputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW), @FlowPin(name = "target", dataType = FlowType.PLAYER), @FlowPin(name = "enabled", dataType = FlowType.BOOLEAN)},
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void playerSneak(FlowContext ctx, FlowNode node) {
        Player target = ctx.getInputValue(node, "target", Player.class, null);
        Boolean enabled = ctx.getInputValue(node, "enabled", Boolean.class, true);
        if (target != null) {
            runSync(() -> target.setSneaking(enabled));
        }
        ctx.triggerOutput("flow");
    }

    @DefineNode(id = "player_fly", displayName = "Fly", category = NodeDefinition.NodeCategory.ACTION,
            inputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW), @FlowPin(name = "target", dataType = FlowType.PLAYER), @FlowPin(name = "enabled", dataType = FlowType.BOOLEAN)},
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void playerFly(FlowContext ctx, FlowNode node) {
        Player target = ctx.getInputValue(node, "target", Player.class, null);
        Boolean enabled = ctx.getInputValue(node, "enabled", Boolean.class, true);
        if (target != null) {
            runSync(() -> {
                target.setAllowFlight(enabled);
                target.setFlying(enabled);
            });
        }
        ctx.triggerOutput("flow");
    }

    @DefineNode(id = "player_gamemode", displayName = "Gamemode", category = NodeDefinition.NodeCategory.ACTION,
            inputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW), @FlowPin(name = "target", dataType = FlowType.PLAYER), @FlowPin(name = "mode", dataType = FlowType.STRING)},
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void playerGamemode(FlowContext ctx, FlowNode node) {
        Player target = ctx.getInputValue(node, "target", Player.class, null);
        String modeName = ctx.getInputValue(node, "mode", String.class, "SURVIVAL");
        if (target != null) {
            try {
                GameMode mode = GameMode.valueOf(modeName.toUpperCase());
                runSync(() -> target.setGameMode(mode));
            } catch (IllegalArgumentException ignored) {
            }
        }
        ctx.triggerOutput("flow");
    }

    @DefineNode(id = "player_vanish", displayName = "Vanish", category = NodeDefinition.NodeCategory.ACTION,
            inputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW), @FlowPin(name = "target", dataType = FlowType.PLAYER), @FlowPin(name = "enabled", dataType = FlowType.BOOLEAN)},
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void playerVanish(FlowContext ctx, FlowNode node) {
        Player target = ctx.getInputValue(node, "target", Player.class, null);
        Boolean enabled = ctx.getInputValue(node, "enabled", Boolean.class, true);
        if (target != null) {
            runSync(() -> target.setInvisible(enabled));
        }
        ctx.triggerOutput("flow");
    }

    @DefineNode(id = "player_glowing", displayName = "Glowing", category = NodeDefinition.NodeCategory.ACTION,
            inputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW), @FlowPin(name = "target", dataType = FlowType.PLAYER), @FlowPin(name = "enabled", dataType = FlowType.BOOLEAN)},
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void playerGlowing(FlowContext ctx, FlowNode node) {
        Player target = ctx.getInputValue(node, "target", Player.class, null);
        Boolean enabled = ctx.getInputValue(node, "enabled", Boolean.class, true);
        if (target != null) {
            runSync(() -> target.setGlowing(enabled));
        }
        ctx.triggerOutput("flow");
    }

    @DefineNode(id = "player_invulnerable", displayName = "Invulnerable", category = NodeDefinition.NodeCategory.ACTION,
            inputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW), @FlowPin(name = "target", dataType = FlowType.PLAYER), @FlowPin(name = "enabled", dataType = FlowType.BOOLEAN)},
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void playerInvulnerable(FlowContext ctx, FlowNode node) {
        Player target = ctx.getInputValue(node, "target", Player.class, null);
        Boolean enabled = ctx.getInputValue(node, "enabled", Boolean.class, true);
        if (target != null) {
            runSync(() -> target.setInvulnerable(enabled));
        }
        ctx.triggerOutput("flow");
    }

    @DefineNode(id = "player_food_level", displayName = "Food Level", category = NodeDefinition.NodeCategory.ACTION,
            inputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW), @FlowPin(name = "target", dataType = FlowType.PLAYER), @FlowPin(name = "level", dataType = FlowType.NUMBER)},
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void playerFoodLevel(FlowContext ctx, FlowNode node) {
        Player target = ctx.getInputValue(node, "target", Player.class, null);
        Integer level = ctx.getInputValue(node, "level", Integer.class, 20);
        if (target != null) {
            runSync(() -> target.setFoodLevel(Math.max(0, Math.min(20, level))));
        }
        ctx.triggerOutput("flow");
    }

    @DefineNode(id = "player_saturation", displayName = "Saturation", category = NodeDefinition.NodeCategory.ACTION,
            inputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW), @FlowPin(name = "target", dataType = FlowType.PLAYER), @FlowPin(name = "saturation", dataType = FlowType.NUMBER)},
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void playerSaturation(FlowContext ctx, FlowNode node) {
        Player target = ctx.getInputValue(node, "target", Player.class, null);
        Float saturation = ctx.getInputValue(node, "saturation", Float.class, 20.0f);
        if (target != null) {
            runSync(() -> target.setSaturation(Math.max(0, Math.min(20, saturation))));
        }
        ctx.triggerOutput("flow");
    }

    @DefineNode(id = "player_exhaustion", displayName = "Exhaustion", category = NodeDefinition.NodeCategory.ACTION,
            inputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW), @FlowPin(name = "target", dataType = FlowType.PLAYER), @FlowPin(name = "exhaustion", dataType = FlowType.NUMBER)},
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void playerExhaustion(FlowContext ctx, FlowNode node) {
        Player target = ctx.getInputValue(node, "target", Player.class, null);
        Float exhaustion = ctx.getInputValue(node, "exhaustion", Float.class, 0.0f);
        if (target != null) {
            runSync(() -> target.setExhaustion(Math.max(0, exhaustion)));
        }
        ctx.triggerOutput("flow");
    }

    @DefineNode(id = "player_health", displayName = "Health", category = NodeDefinition.NodeCategory.ACTION,
            inputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW), @FlowPin(name = "target", dataType = FlowType.PLAYER), @FlowPin(name = "health", dataType = FlowType.NUMBER)},
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void playerHealth(FlowContext ctx, FlowNode node) {
        Player target = ctx.getInputValue(node, "target", Player.class, null);
        Double health = ctx.getInputValue(node, "health", Double.class, 20.0);
        if (target != null) {
            runSync(() -> target.setHealth(Math.max(0, Math.min(target.getMaxHealth(), health))));
        }
        ctx.triggerOutput("flow");
    }

    @DefineNode(id = "player_max_health", displayName = "Max Health", category = NodeDefinition.NodeCategory.ACTION,
            inputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW), @FlowPin(name = "target", dataType = FlowType.PLAYER), @FlowPin(name = "max_health", dataType = FlowType.NUMBER)},
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void playerMaxHealth(FlowContext ctx, FlowNode node) {
        Player target = ctx.getInputValue(node, "target", Player.class, null);
        Double maxHealth = ctx.getInputValue(node, "max_health", Double.class, 20.0);
        if (target != null) {
            runSync(() -> target.setMaxHealth(Math.max(1, maxHealth)));
        }
        ctx.triggerOutput("flow");
    }

    @DefineNode(id = "player_absorption", displayName = "Absorption", category = NodeDefinition.NodeCategory.ACTION,
            inputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW), @FlowPin(name = "target", dataType = FlowType.PLAYER), @FlowPin(name = "absorption", dataType = FlowType.NUMBER)},
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void playerAbsorption(FlowContext ctx, FlowNode node) {
        Player target = ctx.getInputValue(node, "target", Player.class, null);
        Double absorption = ctx.getInputValue(node, "absorption", Double.class, 0.0);
        if (target != null) {
            runSync(() -> target.setAbsorptionAmount(Math.max(0, absorption)));
        }
        ctx.triggerOutput("flow");
    }

    @DefineNode(id = "player_xp", displayName = "Xp", category = NodeDefinition.NodeCategory.ACTION,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "target", dataType = FlowType.PLAYER),
                    @FlowPin(name = "level", dataType = FlowType.NUMBER),
                    @FlowPin(name = "points", dataType = FlowType.NUMBER)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void playerXp(FlowContext ctx, FlowNode node) {
        Player target = ctx.getInputValue(node, "target", Player.class, null);
        Integer level = ctx.getInputValue(node, "level", Integer.class, 0);
        Float points = ctx.getInputValue(node, "points", Float.class, 0.0f);
        if (target != null) {
            runSync(() -> {
                target.setLevel(Math.max(0, level));
                target.setExp(Math.max(0, Math.min(1, points)));
            });
        }
        ctx.triggerOutput("flow");
    }

    @DefineNode(id = "player_tp", displayName = "Teleport", category = NodeDefinition.NodeCategory.ACTION,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "target", dataType = FlowType.PLAYER),
                    @FlowPin(name = "location", dataType = FlowType.LOCATION),
                    @FlowPin(name = "x", dataType = FlowType.NUMBER),
                    @FlowPin(name = "y", dataType = FlowType.NUMBER),
                    @FlowPin(name = "z", dataType = FlowType.NUMBER),
                    @FlowPin(name = "yaw", dataType = FlowType.NUMBER),
                    @FlowPin(name = "pitch", dataType = FlowType.NUMBER)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void playerTp(FlowContext ctx, FlowNode node) {
        Player target = ctx.getInputValue(node, "target", Player.class, null);
        if (target != null) {
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
        }
        ctx.triggerOutput("flow");
    }

    @DefineNode(id = "player_launch", displayName = "Launch", category = NodeDefinition.NodeCategory.ACTION,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "target", dataType = FlowType.PLAYER),
                    @FlowPin(name = "vx", dataType = FlowType.NUMBER),
                    @FlowPin(name = "vy", dataType = FlowType.NUMBER),
                    @FlowPin(name = "vz", dataType = FlowType.NUMBER)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void playerLaunch(FlowContext ctx, FlowNode node) {
        Player target = ctx.getInputValue(node, "target", Player.class, null);
        Double vx = ctx.getInputValue(node, "vx", Double.class, 0.0);
        Double vy = ctx.getInputValue(node, "vy", Double.class, 0.0);
        Double vz = ctx.getInputValue(node, "vz", Double.class, 0.0);
        if (target != null) {
            runSync(() -> target.setVelocity(new Vector(vx, vy, vz)));
        }
        ctx.triggerOutput("flow");
    }

    @DefineNode(id = "player_push", displayName = "Push", category = NodeDefinition.NodeCategory.ACTION,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "target", dataType = FlowType.PLAYER),
                    @FlowPin(name = "strength", dataType = FlowType.NUMBER),
                    @FlowPin(name = "direction_vector", dataType = FlowType.LOCATION)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void playerPush(FlowContext ctx, FlowNode node) {
        Player target = ctx.getInputValue(node, "target", Player.class, null);
        Double strength = ctx.getInputValue(node, "strength", Double.class, 1.0);
        Vector inputDirection = ctx.getInputValue(node, "direction_vector", Vector.class, null);
        if (target != null) {
            runSync(() -> {
                Vector direction = inputDirection != null ? inputDirection.clone() : target.getLocation().getDirection();
                if (direction.lengthSquared() > 0) {
                    direction.normalize();
                }
                target.setVelocity(direction.multiply(strength));
            });
        }
        ctx.triggerOutput("flow");
    }

    @DefineNode(id = "player_spin", displayName = "Spin", category = NodeDefinition.NodeCategory.ACTION,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "target", dataType = FlowType.PLAYER),
                    @FlowPin(name = "yaw", dataType = FlowType.NUMBER),
                    @FlowPin(name = "pitch", dataType = FlowType.NUMBER)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void playerSpin(FlowContext ctx, FlowNode node) {
        Player target = ctx.getInputValue(node, "target", Player.class, null);
        Float yaw = ctx.getInputValue(node, "yaw", Float.class, 0.0f);
        Float pitch = ctx.getInputValue(node, "pitch", Float.class, 0.0f);
        if (target != null) {
            runSync(() -> {
                Location loc = target.getLocation();
                loc.setYaw(loc.getYaw() + yaw);
                loc.setPitch(loc.getPitch() + pitch);
                target.teleport(loc);
            });
        }
        ctx.triggerOutput("flow");
    }

    @DefineNode(id = "player_set_rotation", displayName = "Set Rotation", category = NodeDefinition.NodeCategory.ACTION,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "target", dataType = FlowType.PLAYER),
                    @FlowPin(name = "yaw", dataType = FlowType.NUMBER),
                    @FlowPin(name = "pitch", dataType = FlowType.NUMBER)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void playerSetRotation(FlowContext ctx, FlowNode node) {
        Player target = ctx.getInputValue(node, "target", Player.class, null);
        if (target != null) {
            Location base = callSync(target::getLocation);
            Float yaw = ctx.getInputValue(node, "yaw", Float.class, base.getYaw());
            Float pitch = ctx.getInputValue(node, "pitch", Float.class, base.getPitch());
            runSync(() -> {
                Location loc = target.getLocation();
                loc.setYaw(yaw);
                loc.setPitch(pitch);
                target.teleport(loc);
            });
        }
        ctx.triggerOutput("flow");
    }

    @DefineNode(id = "player_allow_flight", displayName = "Allow Flight", category = NodeDefinition.NodeCategory.ACTION,
            inputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW), @FlowPin(name = "target", dataType = FlowType.PLAYER), @FlowPin(name = "allowed", dataType = FlowType.BOOLEAN)},
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void playerAllowFlight(FlowContext ctx, FlowNode node) {
        Player target = ctx.getInputValue(node, "target", Player.class, null);
        Boolean allowed = ctx.getInputValue(node, "allowed", Boolean.class, true);
        if (target != null) {
            runSync(() -> target.setAllowFlight(allowed));
        }
        ctx.triggerOutput("flow");
    }

    @DefineNode(id = "player_deny_flight", displayName = "Deny Flight", category = NodeDefinition.NodeCategory.ACTION,
            inputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW), @FlowPin(name = "target", dataType = FlowType.PLAYER)},
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void playerDenyFlight(FlowContext ctx, FlowNode node) {
        Player target = ctx.getInputValue(node, "target", Player.class, null);
        if (target != null) {
            runSync(() -> {
                target.setAllowFlight(false);
                target.setFlying(false);
            });
        }
        ctx.triggerOutput("flow");
    }

    @DefineNode(id = "player_set_walk_speed", displayName = "Walk Speed", category = NodeDefinition.NodeCategory.ACTION,
            inputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW), @FlowPin(name = "target", dataType = FlowType.PLAYER), @FlowPin(name = "speed", dataType = FlowType.NUMBER)},
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void playerSetWalkSpeed(FlowContext ctx, FlowNode node) {
        Player target = ctx.getInputValue(node, "target", Player.class, null);
        Float speed = ctx.getInputValue(node, "speed", Float.class, 0.2f);
        if (target != null) {
            runSync(() -> target.setWalkSpeed(Math.max(-1, Math.min(1, speed))));
        }
        ctx.triggerOutput("flow");
    }

    @DefineNode(id = "player_set_fly_speed", displayName = "Fly Speed", category = NodeDefinition.NodeCategory.ACTION,
            inputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW), @FlowPin(name = "target", dataType = FlowType.PLAYER), @FlowPin(name = "speed", dataType = FlowType.NUMBER)},
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void playerSetFlySpeed(FlowContext ctx, FlowNode node) {
        Player target = ctx.getInputValue(node, "target", Player.class, null);
        Float speed = ctx.getInputValue(node, "speed", Float.class, 0.1f);
        if (target != null) {
            runSync(() -> target.setFlySpeed(Math.max(-1, Math.min(1, speed))));
        }
        ctx.triggerOutput("flow");
    }

    @DefineNode(id = "player_freeze", displayName = "Freeze", category = NodeDefinition.NodeCategory.ACTION,
            inputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW), @FlowPin(name = "target", dataType = FlowType.PLAYER)},
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void playerFreeze(FlowContext ctx, FlowNode node) {
        Player target = ctx.getInputValue(node, "target", Player.class, null);
        if (target != null) {
            runSync(() -> {
                target.setWalkSpeed(0);
                target.setFlySpeed(0);
            });
        }
        ctx.triggerOutput("flow");
    }

    @DefineNode(id = "player_unfreeze", displayName = "Unfreeze", category = NodeDefinition.NodeCategory.ACTION,
            inputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW), @FlowPin(name = "target", dataType = FlowType.PLAYER)},
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void playerUnfreeze(FlowContext ctx, FlowNode node) {
        Player target = ctx.getInputValue(node, "target", Player.class, null);
        if (target != null) {
            runSync(() -> {
                target.setWalkSpeed(0.2f);
                target.setFlySpeed(0.1f);
            });
        }
        ctx.triggerOutput("flow");
    }

    @DefineNode(id = "player_set_fire_ticks", displayName = "Fire Ticks", category = NodeDefinition.NodeCategory.ACTION,
            inputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW), @FlowPin(name = "target", dataType = FlowType.PLAYER), @FlowPin(name = "ticks", dataType = FlowType.NUMBER)},
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void playerSetFireTicks(FlowContext ctx, FlowNode node) {
        Player target = ctx.getInputValue(node, "target", Player.class, null);
        Integer ticks = ctx.getInputValue(node, "ticks", Integer.class, 0);
        if (target != null) {
            runSync(() -> target.setFireTicks(ticks));
        }
        ctx.triggerOutput("flow");
    }

    @DefineNode(id = "player_set_air_ticks", displayName = "Air Ticks", category = NodeDefinition.NodeCategory.ACTION,
            inputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW), @FlowPin(name = "target", dataType = FlowType.PLAYER), @FlowPin(name = "ticks", dataType = FlowType.NUMBER)},
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void playerSetAirTicks(FlowContext ctx, FlowNode node) {
        Player target = ctx.getInputValue(node, "target", Player.class, null);
        Integer ticks = ctx.getInputValue(node, "ticks", Integer.class, 300);
        if (target != null) {
            runSync(() -> target.setRemainingAir(Math.max(-20, ticks)));
        }
        ctx.triggerOutput("flow");
    }

    @DefineNode(id = "player_set_no_damage_ticks", displayName = "No Damage Ticks", category = NodeDefinition.NodeCategory.ACTION,
            inputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW), @FlowPin(name = "target", dataType = FlowType.PLAYER), @FlowPin(name = "ticks", dataType = FlowType.NUMBER)},
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void playerSetNoDamageTicks(FlowContext ctx, FlowNode node) {
        Player target = ctx.getInputValue(node, "target", Player.class, null);
        Integer ticks = ctx.getInputValue(node, "ticks", Integer.class, 0);
        if (target != null) {
            runSync(() -> target.setNoDamageTicks(ticks));
        }
        ctx.triggerOutput("flow");
    }

    @DefineNode(id = "player_add_potion", displayName = "Add Potion", category = NodeDefinition.NodeCategory.ACTION,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "player", dataType = FlowType.PLAYER),
                    @FlowPin(name = "effect_type", dataType = FlowType.STRING),
                    @FlowPin(name = "duration_ticks", dataType = FlowType.NUMBER),
                    @FlowPin(name = "amplifier", dataType = FlowType.NUMBER)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void playerAddPotion(FlowContext ctx, FlowNode node) {
        Player player = ctx.getInputValue(node, "player", Player.class, null);
        String effectType = ctx.getInputValue(node, "effect_type", String.class, "SPEED");
        Integer duration = ctx.getInputValue(node, "duration_ticks", Integer.class, 600);
        Integer amplifier = ctx.getInputValue(node, "amplifier", Integer.class, 0);
        if (player != null) {
            PotionEffectType type = PotionEffectType.getByName(effectType.toUpperCase());
            if (type != null) {
                PotionEffect effect = new PotionEffect(type, Math.max(0, duration), Math.max(0, amplifier));
                runSync(() -> player.addPotionEffect(effect));
            }
        }
        ctx.triggerOutput("flow");
    }

    @DefineNode(id = "player_clear_potions", displayName = "Clear Potions", category = NodeDefinition.NodeCategory.ACTION,
            inputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW), @FlowPin(name = "player", dataType = FlowType.PLAYER)},
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void playerClearPotions(FlowContext ctx, FlowNode node) {
        Player player = ctx.getInputValue(node, "player", Player.class, null);
        if (player != null) {
            runSync(() -> player.getActivePotionEffects().forEach(effect -> player.removePotionEffect(effect.getType())));
        }
        ctx.triggerOutput("flow");
    }

    @DefineNode(id = "player_has_potion", displayName = "Has Potion", category = NodeDefinition.NodeCategory.LOGIC,
            inputs = {@FlowPin(name = "player", dataType = FlowType.PLAYER), @FlowPin(name = "effect_type", dataType = FlowType.STRING)},
            outputs = {@FlowPin(name = "has_effect", dataType = FlowType.BOOLEAN), @FlowPin(name = "amplifier", dataType = FlowType.NUMBER)})
    public void playerHasPotion(FlowContext ctx, FlowNode node) {
        Player player = ctx.getInputValue(node, "player", Player.class, null);
        String effectType = ctx.getInputValue(node, "effect_type", String.class, "SPEED");
        boolean hasEffect = false;
        int amplifier = 0;
        if (player != null) {
            PotionEffectType type = PotionEffectType.getByName(effectType.toUpperCase());
            if (type != null) {
                hasEffect = callSync(() -> player.hasPotionEffect(type));
                if (hasEffect) {
                    PotionEffect potionEffect = callSync(() -> player.getPotionEffect(type));
                    amplifier = potionEffect != null ? potionEffect.getAmplifier() : 0;
                }
            }
        }
        ctx.setOutput(node, "has_effect", hasEffect);
        ctx.setOutput(node, "amplifier", amplifier);
    }

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

    @DefineNode(id = "player_set_compass_target", displayName = "Set Compass Target", category = NodeDefinition.NodeCategory.ACTION,
            inputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW), @FlowPin(name = "player", dataType = FlowType.PLAYER), @FlowPin(name = "location", dataType = FlowType.LOCATION)},
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void playerSetCompassTarget(FlowContext ctx, FlowNode node) {
        Player player = ctx.getInputValue(node, "player", Player.class, null);
        Location location = ctx.getInputValue(node, "location", Location.class, null);
        if (player != null && location != null) {
            runSync(() -> player.setCompassTarget(location));
        }
        ctx.triggerOutput("flow");
    }

    @DefineNode(id = "player_reset_compass", displayName = "Reset Compass", category = NodeDefinition.NodeCategory.ACTION,
            inputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW), @FlowPin(name = "player", dataType = FlowType.PLAYER)},
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void playerResetCompass(FlowContext ctx, FlowNode node) {
        Player player = ctx.getInputValue(node, "player", Player.class, null);
        if (player != null) {
            runSync(() -> player.setCompassTarget(player.getWorld().getSpawnLocation()));
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

    @DefineNode(id = "player_set_exp", displayName = "Set Xp", category = NodeDefinition.NodeCategory.ACTION,
            inputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW), @FlowPin(name = "player", dataType = FlowType.PLAYER), @FlowPin(name = "exp", dataType = FlowType.NUMBER)},
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void playerSetExp(FlowContext ctx, FlowNode node) {
        Player player = ctx.getInputValue(node, "player", Player.class, null);
        Integer exp = ctx.getInputValue(node, "exp", Integer.class, 0);
        if (player != null) {
            runSync(() -> player.setTotalExperience(Math.max(0, exp)));
        }
        ctx.triggerOutput("flow");
    }

    @DefineNode(id = "player_give_exp", displayName = "Give Xp", category = NodeDefinition.NodeCategory.ACTION,
            inputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW), @FlowPin(name = "player", dataType = FlowType.PLAYER), @FlowPin(name = "exp", dataType = FlowType.NUMBER)},
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void playerGiveExp(FlowContext ctx, FlowNode node) {
        Player player = ctx.getInputValue(node, "player", Player.class, null);
        Integer exp = ctx.getInputValue(node, "exp", Integer.class, 0);
        if (player != null) {
            runSync(() -> player.giveExp(Math.max(0, exp)));
        }
        ctx.triggerOutput("flow");
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

    @DefineNode(id = "player_set_walking_speed", displayName = "Set Walking Speed", category = NodeDefinition.NodeCategory.ACTION,
            inputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW), @FlowPin(name = "player", dataType = FlowType.PLAYER), @FlowPin(name = "speed", dataType = FlowType.NUMBER)},
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void playerSetWalkingSpeed(FlowContext ctx, FlowNode node) {
        Player player = ctx.getInputValue(node, "player", Player.class, null);
        Float speed = ctx.getInputValue(node, "speed", Float.class, 0.2f);
        if (player != null) {
            runSync(() -> player.setWalkSpeed(Math.max(-1, Math.min(1, speed))));
        }
        ctx.triggerOutput("flow");
    }

    @DefineNode(id = "player_set_flying_speed", displayName = "Set Flying Speed", category = NodeDefinition.NodeCategory.ACTION,
            inputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW), @FlowPin(name = "player", dataType = FlowType.PLAYER), @FlowPin(name = "speed", dataType = FlowType.NUMBER)},
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void playerSetFlyingSpeed(FlowContext ctx, FlowNode node) {
        Player player = ctx.getInputValue(node, "player", Player.class, null);
        Float speed = ctx.getInputValue(node, "speed", Float.class, 0.05f);
        if (player != null) {
            runSync(() -> player.setFlySpeed(Math.max(-1, Math.min(1, speed))));
        }
        ctx.triggerOutput("flow");
    }

    @DefineNode(id = "player_set_saturation", displayName = "Set Saturation", category = NodeDefinition.NodeCategory.ACTION,
            inputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW), @FlowPin(name = "player", dataType = FlowType.PLAYER), @FlowPin(name = "saturation", dataType = FlowType.NUMBER)},
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void playerSetSaturation(FlowContext ctx, FlowNode node) {
        Player player = ctx.getInputValue(node, "player", Player.class, null);
        Float saturation = ctx.getInputValue(node, "saturation", Float.class, 20.0f);
        if (player != null) {
            runSync(() -> player.setSaturation(Math.max(0, Math.min(20, saturation))));
        }
        ctx.triggerOutput("flow");
    }

    @DefineNode(id = "player_set_food_level", displayName = "Set Food Level", category = NodeDefinition.NodeCategory.ACTION,
            inputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW), @FlowPin(name = "player", dataType = FlowType.PLAYER), @FlowPin(name = "level", dataType = FlowType.NUMBER)},
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void playerSetFoodLevel(FlowContext ctx, FlowNode node) {
        Player player = ctx.getInputValue(node, "player", Player.class, null);
        Integer level = ctx.getInputValue(node, "level", Integer.class, 20);
        if (player != null) {
            runSync(() -> player.setFoodLevel(Math.max(0, Math.min(20, level))));
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
