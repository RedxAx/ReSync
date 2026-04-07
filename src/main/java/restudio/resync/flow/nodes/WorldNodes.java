package restudio.resync.flow.nodes;

import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import restudio.flow.data.FlowType;
import restudio.resync.ReSync;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.registry.DefineNode;
import restudio.resync.flow.registry.FlowPin;
import restudio.resync.flow.registry.NodeDefinition;
import restudio.resync.world.WorldManagementService;
import restudio.resync.world.WorldMapQuery;
import restudio.resync.world.WorldRegistryEntry;
import restudio.resync.world.WorldSnapshot;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class WorldNodes {

    private static WorldManagementService getWorldManagementService() {
        ReSync plugin = ReSync.getInstance();
        if (plugin == null || plugin.getReSyncServer() == null) return null;
        return plugin.getReSyncServer().getWorldManagementService();
    }

    @DefineNode(id = "get_location", displayName = "Get Location", category = NodeDefinition.NodeCategory.WORLD,
            inputs = {@FlowPin(name = "x", dataType = FlowType.NUMBER), @FlowPin(name = "y", dataType = FlowType.NUMBER), @FlowPin(name = "z", dataType = FlowType.NUMBER)},
            outputs = {@FlowPin(name = "location", dataType = FlowType.LOCATION)})
    public void getLocation(FlowContext ctx, restudio.flow.data.FlowNode node) {
        Double x = ctx.getInputValue(node, "x", Double.class, 0.0);
        Double y = ctx.getInputValue(node, "y", Double.class, 0.0);
        Double z = ctx.getInputValue(node, "z", Double.class, 0.0);
        World world = null;
        Player player = ctx.getPlayer();
        if (player != null) {
            world = player.getWorld();
        } else if (!Bukkit.getWorlds().isEmpty()) {
            world = Bukkit.getWorlds().getFirst();
        }
        if (world == null) return;
        ctx.setOutput(node, "location", new Location(world, x, y, z));
    }

    @DefineNode(id = "world_get_by_name", displayName = "Get World", category = NodeDefinition.NodeCategory.WORLD,
            inputs = {@FlowPin(name = "world_name", dataType = FlowType.STRING)},
            outputs = {@FlowPin(name = "world", dataType = FlowType.ANY)})
    public void getWorldByName(FlowContext ctx, restudio.flow.data.FlowNode node) {
        String worldName = ctx.getInputValue(node, "world_name", String.class, "");
        ctx.setOutput(node, "world", Bukkit.getWorld(worldName));
    }

    @DefineNode(id = "world_get_all", displayName = "Get All Worlds", category = NodeDefinition.NodeCategory.WORLD,
            outputs = {@FlowPin(name = "worlds_list", dataType = FlowType.LIST)})
    public void getAllWorlds(FlowContext ctx, restudio.flow.data.FlowNode node) {
        ctx.setOutput(node, "worlds_list", Bukkit.getWorlds());
    }

    @DefineNode(id = "world_set_time", displayName = "Set Time", category = NodeDefinition.NodeCategory.WORLD,
            inputs = {@FlowPin(name = "world", dataType = FlowType.ANY), @FlowPin(name = "time_ticks", dataType = FlowType.NUMBER)},
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void setTime(FlowContext ctx, restudio.flow.data.FlowNode node) {
        World world = ctx.getInputValue(node, "world", World.class);
        Long timeTicks = ctx.getInputValue(node, "time_ticks", Long.class, 0L);
        if (world != null) world.setTime(timeTicks);
        ctx.setOutput(node, "flow", true);
    }

    @DefineNode(id = "world_get_time", displayName = "Get Time", category = NodeDefinition.NodeCategory.WORLD,
            inputs = {@FlowPin(name = "world", dataType = FlowType.ANY)},
            outputs = {@FlowPin(name = "time_ticks", dataType = FlowType.NUMBER)})
    public void getTime(FlowContext ctx, restudio.flow.data.FlowNode node) {
        World world = ctx.getInputValue(node, "world", World.class);
        if (world != null) ctx.setOutput(node, "time_ticks", world.getTime());
    }

    @DefineNode(id = "world_set_full_time", displayName = "Set Full Time", category = NodeDefinition.NodeCategory.WORLD,
            inputs = {@FlowPin(name = "world", dataType = FlowType.ANY), @FlowPin(name = "full_time_ticks", dataType = FlowType.NUMBER)},
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void setFullTime(FlowContext ctx, restudio.flow.data.FlowNode node) {
        World world = ctx.getInputValue(node, "world", World.class);
        Long fullTimeTicks = ctx.getInputValue(node, "full_time_ticks", Long.class, 0L);
        if (world != null) world.setFullTime(fullTimeTicks);
        ctx.setOutput(node, "flow", true);
    }

    @DefineNode(id = "world_get_full_time", displayName = "Get Full Time", category = NodeDefinition.NodeCategory.WORLD,
            inputs = {@FlowPin(name = "world", dataType = FlowType.ANY)},
            outputs = {@FlowPin(name = "full_time_ticks", dataType = FlowType.NUMBER)})
    public void getFullTime(FlowContext ctx, restudio.flow.data.FlowNode node) {
        World world = ctx.getInputValue(node, "world", World.class);
        if (world != null) ctx.setOutput(node, "full_time_ticks", world.getFullTime());
    }

    @DefineNode(id = "world_set_day_time", displayName = "Set Day Time", category = NodeDefinition.NodeCategory.WORLD,
            inputs = {@FlowPin(name = "world", dataType = FlowType.ANY), @FlowPin(name = "time", dataType = FlowType.NUMBER)},
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void setDayTime(FlowContext ctx, restudio.flow.data.FlowNode node) {
        World world = ctx.getInputValue(node, "world", World.class);
        Long time = ctx.getInputValue(node, "time", Long.class, 0L);
        if (world != null) world.setTime(time);
        ctx.setOutput(node, "flow", true);
    }

    @DefineNode(id = "world_set_weather", displayName = "Set Weather", category = NodeDefinition.NodeCategory.WORLD,
            inputs = {@FlowPin(name = "world", dataType = FlowType.ANY), @FlowPin(name = "weather_type", dataType = FlowType.STRING), @FlowPin(name = "duration_ticks", dataType = FlowType.NUMBER)},
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void setWeather(FlowContext ctx, restudio.flow.data.FlowNode node) {
        World world = ctx.getInputValue(node, "world", World.class);
        String weatherType = ctx.getInputValue(node, "weather_type", String.class, "clear");
        Integer durationTicks = ctx.getInputValue(node, "duration_ticks", Integer.class, 0);
        if (world != null) {
            switch (weatherType) {
                case "clear", "clear_all" -> { world.setStorm(false); world.setThundering(false); }
                case "rain" -> { world.setStorm(true); world.setThundering(false); }
                case "thunder", "downfall" -> { world.setStorm(true); world.setThundering(true); }
            }
            if (durationTicks > 0) {
                world.setWeatherDuration(durationTicks);
                world.setThunderDuration(durationTicks);
            }
        }
        ctx.setOutput(node, "flow", true);
    }

    @DefineNode(id = "world_get_weather", displayName = "Get Weather", category = NodeDefinition.NodeCategory.WORLD,
            inputs = {@FlowPin(name = "world", dataType = FlowType.ANY)},
            outputs = {@FlowPin(name = "weather_type", dataType = FlowType.STRING), @FlowPin(name = "thundering", dataType = FlowType.BOOLEAN), @FlowPin(name = "has_storm", dataType = FlowType.BOOLEAN)})
    public void getWeather(FlowContext ctx, restudio.flow.data.FlowNode node) {
        World world = ctx.getInputValue(node, "world", World.class);
        if (world != null) {
            ctx.setOutput(node, "weather_type", world.isThundering() ? "thunder" : world.hasStorm() ? "rain" : "clear");
            ctx.setOutput(node, "thundering", world.isThundering());
            ctx.setOutput(node, "has_storm", world.hasStorm());
        }
    }

    @DefineNode(id = "world_spawn_set", displayName = "Set Spawn", category = NodeDefinition.NodeCategory.WORLD,
            inputs = {@FlowPin(name = "world", dataType = FlowType.ANY), @FlowPin(name = "location", dataType = FlowType.LOCATION)},
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void setSpawn(FlowContext ctx, restudio.flow.data.FlowNode node) {
        World world = ctx.getInputValue(node, "world", World.class);
        Location location = ctx.getInputValue(node, "location", Location.class);
        if (world != null && location != null) world.setSpawnLocation(location);
        ctx.setOutput(node, "flow", true);
    }

    @DefineNode(id = "world_spawn_get", displayName = "Get Spawn", category = NodeDefinition.NodeCategory.WORLD,
            inputs = {@FlowPin(name = "world", dataType = FlowType.ANY)},
            outputs = {@FlowPin(name = "spawn_location", dataType = FlowType.LOCATION)})
    public void getSpawn(FlowContext ctx, restudio.flow.data.FlowNode node) {
        World world = ctx.getInputValue(node, "world", World.class);
        if (world != null) ctx.setOutput(node, "spawn_location", world.getSpawnLocation());
    }

    @DefineNode(id = "world_set_difficulty", displayName = "Set Difficulty", category = NodeDefinition.NodeCategory.WORLD,
            inputs = {@FlowPin(name = "world", dataType = FlowType.ANY), @FlowPin(name = "difficulty", dataType = FlowType.STRING)},
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void setDifficulty(FlowContext ctx, restudio.flow.data.FlowNode node) {
        World world = ctx.getInputValue(node, "world", World.class);
        String difficultyStr = ctx.getInputValue(node, "difficulty", String.class, "normal");
        if (world != null) {
            Difficulty difficulty = switch (difficultyStr.toLowerCase()) {
                case "peaceful" -> Difficulty.PEACEFUL;
                case "easy" -> Difficulty.EASY;
                case "hard" -> Difficulty.HARD;
                default -> Difficulty.NORMAL;
            };
            world.setDifficulty(difficulty);
        }
        ctx.setOutput(node, "flow", true);
    }

    @DefineNode(id = "world_get_difficulty", displayName = "Get Difficulty", category = NodeDefinition.NodeCategory.WORLD,
            inputs = {@FlowPin(name = "world", dataType = FlowType.ANY)},
            outputs = {@FlowPin(name = "difficulty", dataType = FlowType.STRING)})
    public void getDifficulty(FlowContext ctx, restudio.flow.data.FlowNode node) {
        World world = ctx.getInputValue(node, "world", World.class);
        if (world != null) ctx.setOutput(node, "difficulty", world.getDifficulty().name().toLowerCase());
    }

    @DefineNode(id = "world_set_pvp", displayName = "Set PVP", category = NodeDefinition.NodeCategory.WORLD,
            inputs = {@FlowPin(name = "world", dataType = FlowType.ANY), @FlowPin(name = "pvp_enabled", dataType = FlowType.BOOLEAN)},
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void setPvp(FlowContext ctx, restudio.flow.data.FlowNode node) {
        World world = ctx.getInputValue(node, "world", World.class);
        Boolean pvpEnabled = ctx.getInputValue(node, "pvp_enabled", Boolean.class, false);
        if (world != null) world.setPVP(pvpEnabled);
        ctx.setOutput(node, "flow", true);
    }

    @DefineNode(id = "world_get_pvp", displayName = "Get PVP", category = NodeDefinition.NodeCategory.WORLD,
            inputs = {@FlowPin(name = "world", dataType = FlowType.ANY)},
            outputs = {@FlowPin(name = "pvp_enabled", dataType = FlowType.BOOLEAN)})
    public void getPvp(FlowContext ctx, restudio.flow.data.FlowNode node) {
        World world = ctx.getInputValue(node, "world", World.class);
        if (world != null) ctx.setOutput(node, "pvp_enabled", world.getPVP());
    }

    @DefineNode(id = "world_save", displayName = "Save", category = NodeDefinition.NodeCategory.WORLD,
            inputs = {@FlowPin(name = "world", dataType = FlowType.ANY)},
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void save(FlowContext ctx, restudio.flow.data.FlowNode node) {
        World world = ctx.getInputValue(node, "world", World.class);
        if (world != null) world.save();
        ctx.setOutput(node, "flow", true);
    }

    @DefineNode(id = "world_auto_save_set", displayName = "Set Auto Save", category = NodeDefinition.NodeCategory.WORLD,
            inputs = {@FlowPin(name = "world", dataType = FlowType.ANY), @FlowPin(name = "interval_ticks", dataType = FlowType.NUMBER)},
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void setAutoSave(FlowContext ctx, restudio.flow.data.FlowNode node) {
        World world = ctx.getInputValue(node, "world", World.class);
        Long intervalTicks = ctx.getInputValue(node, "interval_ticks", Long.class, 0L);
        if (world != null) world.setTicksPerAnimalSpawns(intervalTicks.intValue());
        ctx.setOutput(node, "flow", true);
    }

    @DefineNode(id = "world_set_spawn_limits", displayName = "Set Spawn Limits", category = NodeDefinition.NodeCategory.WORLD,
            inputs = {@FlowPin(name = "world", dataType = FlowType.ANY), @FlowPin(name = "monsters", dataType = FlowType.NUMBER), @FlowPin(name = "animals", dataType = FlowType.NUMBER), @FlowPin(name = "water_ambient", dataType = FlowType.NUMBER), @FlowPin(name = "water_animals", dataType = FlowType.NUMBER), @FlowPin(name = "water_underground", dataType = FlowType.NUMBER)},
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void setSpawnLimits(FlowContext ctx, restudio.flow.data.FlowNode node) {
        World world = ctx.getInputValue(node, "world", World.class);
        Integer monsters = ctx.getInputValue(node, "monsters", Integer.class, 0);
        Integer animals = ctx.getInputValue(node, "animals", Integer.class, 0);
        Integer waterAmbient = ctx.getInputValue(node, "water_ambient", Integer.class, 0);
        Integer waterAnimals = ctx.getInputValue(node, "water_animals", Integer.class, 0);
        Integer waterUnderground = ctx.getInputValue(node, "water_underground", Integer.class, 0);
        if (world != null) {
            world.setMonsterSpawnLimit(monsters);
            world.setAnimalSpawnLimit(animals);
            world.setWaterAmbientSpawnLimit(waterAmbient);
            world.setWaterAnimalSpawnLimit(waterAnimals);
            world.setWaterUndergroundCreatureSpawnLimit(waterUnderground);
        }
        ctx.setOutput(node, "flow", true);
    }

    @DefineNode(id = "world_management_get_snapshot", displayName = "Get Snapshot", category = NodeDefinition.NodeCategory.WORLD,
            outputs = {@FlowPin(name = "snapshot", dataType = FlowType.ANY)})
    public void getSnapshot(FlowContext ctx, restudio.flow.data.FlowNode node) {
        WorldManagementService service = getWorldManagementService();
        if (service != null) ctx.setOutput(node, "snapshot", service.createSnapshot());
    }

    @DefineNode(id = "world_management_get_worlds", displayName = "Get Worlds", category = NodeDefinition.NodeCategory.WORLD,
            outputs = {@FlowPin(name = "worlds", dataType = FlowType.LIST)})
    public void getWorlds(FlowContext ctx, restudio.flow.data.FlowNode node) {
        WorldManagementService service = getWorldManagementService();
        WorldSnapshot snapshot = service != null ? service.createSnapshot() : null;
        ctx.setOutput(node, "worlds", snapshot != null ? snapshot.getWorlds() : Collections.emptyList());
    }

    @DefineNode(id = "world_management_get_world", displayName = "Get World Entry", category = NodeDefinition.NodeCategory.WORLD,
            inputs = {@FlowPin(name = "world_name", dataType = FlowType.STRING)},
            outputs = {@FlowPin(name = "world", dataType = FlowType.ANY)})
    public void getWorld(FlowContext ctx, restudio.flow.data.FlowNode node) {
        String worldName = ctx.getInputValue(node, "world_name", String.class, "");
        WorldManagementService service = getWorldManagementService();
        WorldRegistryEntry match = null;
        if (service != null) {
            for (WorldRegistryEntry entry : service.createSnapshot().getWorlds()) {
                if (entry != null && entry.getWorldName() != null && entry.getWorldName().equalsIgnoreCase(worldName)) {
                    match = entry;
                    break;
                }
            }
        }
        ctx.setOutput(node, "world", match);
    }

    @DefineNode(id = "world_management_get_portals", displayName = "Get Portals", category = NodeDefinition.NodeCategory.WORLD,
            inputs = {@FlowPin(name = "world_name", dataType = FlowType.STRING)},
            outputs = {@FlowPin(name = "portals", dataType = FlowType.LIST)})
    public void getPortals(FlowContext ctx, restudio.flow.data.FlowNode node) {
        String worldName = ctx.getInputValue(node, "world_name", String.class, "");
        WorldManagementService service = getWorldManagementService();
        if (service == null) {
            ctx.setOutput(node, "portals", Collections.emptyList());
            return;
        }
        ctx.setOutput(node, "portals", worldName == null || worldName.isBlank() ? service.getPortals() : service.getPortalsByWorld(worldName));
    }

    @DefineNode(id = "world_management_get_portal", displayName = "Get Portal", category = NodeDefinition.NodeCategory.WORLD,
            inputs = {@FlowPin(name = "portal_id", dataType = FlowType.STRING)},
            outputs = {@FlowPin(name = "portal", dataType = FlowType.ANY)})
    public void getPortal(FlowContext ctx, restudio.flow.data.FlowNode node) {
        WorldManagementService service = getWorldManagementService();
        ctx.setOutput(node, "portal", service == null ? null : service.getPortal(ctx.getInputValue(node, "portal_id", String.class, "")));
    }

    @DefineNode(id = "world_management_get_game_rules", displayName = "Get Game Rules", category = NodeDefinition.NodeCategory.WORLD,
            inputs = {@FlowPin(name = "world_name", dataType = FlowType.STRING)},
            outputs = {@FlowPin(name = "game_rules", dataType = FlowType.MAP)})
    public void getGameRules(FlowContext ctx, restudio.flow.data.FlowNode node) {
        String worldName = ctx.getInputValue(node, "world_name", String.class, "");
        WorldManagementService service = getWorldManagementService();
        Map<String, String> gameRules = new LinkedHashMap<>();
        if (service != null) {
            for (WorldRegistryEntry entry : service.createSnapshot().getWorlds()) {
                if (entry != null && entry.getWorldName() != null && entry.getWorldName().equalsIgnoreCase(worldName)) {
                    gameRules.putAll(entry.getGameRules());
                    break;
                }
            }
        }
        ctx.setOutput(node, "game_rules", gameRules);
    }

    @DefineNode(id = "world_management_get_game_rule_descriptors", displayName = "Get Game Rule Descriptors", category = NodeDefinition.NodeCategory.WORLD,
            outputs = {@FlowPin(name = "descriptors", dataType = FlowType.LIST)})
    public void getGameRuleDescriptors(FlowContext ctx, restudio.flow.data.FlowNode node) {
        WorldManagementService service = getWorldManagementService();
        ctx.setOutput(node, "descriptors", service == null ? Collections.emptyList() : service.getGameRuleDescriptors());
    }

    @DefineNode(id = "world_management_get_map_snapshot", displayName = "Get Map Snapshot", category = NodeDefinition.NodeCategory.WORLD,
            inputs = {@FlowPin(name = "world_name", dataType = FlowType.STRING), @FlowPin(name = "center_x", dataType = FlowType.NUMBER), @FlowPin(name = "center_z", dataType = FlowType.NUMBER), @FlowPin(name = "zoom", dataType = FlowType.NUMBER)},
            outputs = {@FlowPin(name = "snapshot", dataType = FlowType.ANY)})
    public void getMapSnapshot(FlowContext ctx, restudio.flow.data.FlowNode node) {
        WorldManagementService service = getWorldManagementService();
        if (service == null) return;
        WorldMapQuery query = new WorldMapQuery();
        query.setWorldName(ctx.getInputValue(node, "world_name", String.class, ""));
        query.setCenterX(ctx.getInputValue(node, "center_x", Double.class, 0.0));
        query.setCenterZ(ctx.getInputValue(node, "center_z", Double.class, 0.0));
        query.setZoom(ctx.getInputValue(node, "zoom", Integer.class, 1));
        ctx.setOutput(node, "snapshot", service.getMapService().createSnapshot(query));
    }
}
