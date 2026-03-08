package restudio.resync.flow.nodes;

import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import restudio.flow.data.FlowNode;
import restudio.resync.ReSync;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.FlowRegistry;
import restudio.resync.world.WorldManagementService;
import restudio.resync.world.WorldMapQuery;
import restudio.resync.world.WorldRegistryEntry;
import restudio.resync.world.WorldSnapshot;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class WorldNodes {
    public static void registerAll(FlowRegistry registry) {
        registry.register("get_location", (ctx, node) -> {
            Double x = ctx.getInputValue(node, "x", Double.class, 0.0);
            Double y = ctx.getInputValue(node, "y", Double.class, 0.0);
            Double z = ctx.getInputValue(node, "z", Double.class, 0.0);

            World world = null;
            Player player = ctx.getPlayer();
            if (player != null) {
                world = player.getWorld();
            } else if (!Bukkit.getWorlds().isEmpty()) {
                world = Bukkit.getWorlds().get(0);
            }

            if (world == null) {
                return;
            }

            String nodeId = findNodeId(ctx, node);
            if (nodeId == null) {
                return;
            }

            Location location = new Location(world, x, y, z);
            ctx.setNodeOutput(nodeId, "location", location);
        });

        registry.register("world_get_by_name", (ctx, node) -> {
            String nodeId = findNodeId(ctx, node);
            if (nodeId == null) {
                return;
            }
            String worldName = ctx.getInputValue(node, "world_name", String.class, "");
            World world = Bukkit.getWorld(worldName);
            ctx.setNodeOutput(nodeId, "world", world);
        });

        registry.register("world_get_all", (ctx, node) -> {
            String nodeId = findNodeId(ctx, node);
            if (nodeId == null) {
                return;
            }
            List<World> worlds = Bukkit.getWorlds();
            ctx.setNodeOutput(nodeId, "worlds_list", worlds);
        });

        registry.register("world_set_time", (ctx, node) -> {
            String nodeId = findNodeId(ctx, node);
            if (nodeId == null) {
                return;
            }
            World world = ctx.getInputValue(node, "world", World.class);
            Long timeTicks = ctx.getInputValue(node, "time_ticks", Long.class, 0L);
            if (world != null) {
                world.setTime(timeTicks);
            }
            ctx.setNodeOutput(nodeId, "flow", true);
        });

        registry.register("world_get_time", (ctx, node) -> {
            String nodeId = findNodeId(ctx, node);
            if (nodeId == null) {
                return;
            }
            World world = ctx.getInputValue(node, "world", World.class);
            if (world != null) {
                ctx.setNodeOutput(nodeId, "time_ticks", world.getTime());
            }
        });

        registry.register("world_set_full_time", (ctx, node) -> {
            String nodeId = findNodeId(ctx, node);
            if (nodeId == null) {
                return;
            }
            World world = ctx.getInputValue(node, "world", World.class);
            Long fullTimeTicks = ctx.getInputValue(node, "full_time_ticks", Long.class, 0L);
            if (world != null) {
                world.setFullTime(fullTimeTicks);
            }
            ctx.setNodeOutput(nodeId, "flow", true);
        });

        registry.register("world_get_full_time", (ctx, node) -> {
            String nodeId = findNodeId(ctx, node);
            if (nodeId == null) {
                return;
            }
            World world = ctx.getInputValue(node, "world", World.class);
            if (world != null) {
                ctx.setNodeOutput(nodeId, "full_time_ticks", world.getFullTime());
            }
        });

        registry.register("world_set_day_time", (ctx, node) -> {
            String nodeId = findNodeId(ctx, node);
            if (nodeId == null) {
                return;
            }
            World world = ctx.getInputValue(node, "world", World.class);
            Long time = ctx.getInputValue(node, "time", Long.class, 0L);
            if (world != null) {
                world.setTime(time);
            }
            ctx.setNodeOutput(nodeId, "flow", true);
        });

        registry.register("world_set_weather", (ctx, node) -> {
            String nodeId = findNodeId(ctx, node);
            if (nodeId == null) {
                return;
            }
            World world = ctx.getInputValue(node, "world", World.class);
            String weatherType = ctx.getInputValue(node, "weather_type", String.class, "clear");
            Integer durationTicks = ctx.getInputValue(node, "duration_ticks", Integer.class, 0);
            if (world != null) {
                switch (weatherType) {
                    case "clear", "clear_all" -> {
                        world.setStorm(false);
                        world.setThundering(false);
                    }
                    case "rain" -> {
                        world.setStorm(true);
                        world.setThundering(false);
                    }
                    case "thunder", "downfall" -> {
                        world.setStorm(true);
                        world.setThundering(true);
                    }
                }
                if (durationTicks > 0) {
                    world.setWeatherDuration(durationTicks);
                    world.setThunderDuration(durationTicks);
                }
            }
            ctx.setNodeOutput(nodeId, "flow", true);
        });

        registry.register("world_get_weather", (ctx, node) -> {
            String nodeId = findNodeId(ctx, node);
            if (nodeId == null) {
                return;
            }
            World world = ctx.getInputValue(node, "world", World.class);
            if (world != null) {
                String weatherType = world.isThundering() ? "thunder" : world.hasStorm() ? "rain" : "clear";
                ctx.setNodeOutput(nodeId, "weather_type", weatherType);
                ctx.setNodeOutput(nodeId, "thundering", world.isThundering());
                ctx.setNodeOutput(nodeId, "has_storm", world.hasStorm());
            }
        });

        registry.register("world_spawn_set", (ctx, node) -> {
            String nodeId = findNodeId(ctx, node);
            if (nodeId == null) {
                return;
            }
            World world = ctx.getInputValue(node, "world", World.class);
            Location location = ctx.getInputValue(node, "location", Location.class);
            if (world != null && location != null) {
                world.setSpawnLocation(location);
            }
            ctx.setNodeOutput(nodeId, "flow", true);
        });

        registry.register("world_spawn_get", (ctx, node) -> {
            String nodeId = findNodeId(ctx, node);
            if (nodeId == null) {
                return;
            }
            World world = ctx.getInputValue(node, "world", World.class);
            if (world != null) {
                Location spawnLocation = world.getSpawnLocation();
                ctx.setNodeOutput(nodeId, "spawn_location", spawnLocation);
            }
        });

        registry.register("world_set_difficulty", (ctx, node) -> {
            String nodeId = findNodeId(ctx, node);
            if (nodeId == null) {
                return;
            }
            World world = ctx.getInputValue(node, "world", World.class);
            String difficultyStr = ctx.getInputValue(node, "difficulty", String.class, "normal");
            if (world != null) {
                Difficulty difficulty = switch (difficultyStr.toLowerCase()) {
                    case "peaceful" -> Difficulty.PEACEFUL;
                    case "easy" -> Difficulty.EASY;
                    case "normal" -> Difficulty.NORMAL;
                    case "hard" -> Difficulty.HARD;
                    default -> Difficulty.NORMAL;
                };
                world.setDifficulty(difficulty);
            }
            ctx.setNodeOutput(nodeId, "flow", true);
        });

        registry.register("world_get_difficulty", (ctx, node) -> {
            String nodeId = findNodeId(ctx, node);
            if (nodeId == null) {
                return;
            }
            World world = ctx.getInputValue(node, "world", World.class);
            if (world != null) {
                Difficulty difficulty = world.getDifficulty();
                ctx.setNodeOutput(nodeId, "difficulty", difficulty.name().toLowerCase());
            }
        });

        registry.register("world_set_pvp", (ctx, node) -> {
            String nodeId = findNodeId(ctx, node);
            if (nodeId == null) {
                return;
            }
            World world = ctx.getInputValue(node, "world", World.class);
            Boolean pvpEnabled = ctx.getInputValue(node, "pvp_enabled", Boolean.class, false);
            if (world != null) {
                world.setPVP(pvpEnabled);
            }
            ctx.setNodeOutput(nodeId, "flow", true);
        });

        registry.register("world_get_pvp", (ctx, node) -> {
            String nodeId = findNodeId(ctx, node);
            if (nodeId == null) {
                return;
            }
            World world = ctx.getInputValue(node, "world", World.class);
            if (world != null) {
                ctx.setNodeOutput(nodeId, "pvp_enabled", world.getPVP());
            }
        });

        registry.register("world_save", (ctx, node) -> {
            String nodeId = findNodeId(ctx, node);
            if (nodeId == null) {
                return;
            }
            World world = ctx.getInputValue(node, "world", World.class);
            if (world != null) {
                world.save();
            }
            ctx.setNodeOutput(nodeId, "flow", true);
        });

        registry.register("world_auto_save_set", (ctx, node) -> {
            String nodeId = findNodeId(ctx, node);
            if (nodeId == null) {
                return;
            }
            World world = ctx.getInputValue(node, "world", World.class);
            Long intervalTicks = ctx.getInputValue(node, "interval_ticks", Long.class, 0L);
            if (world != null) {
                world.setTicksPerAnimalSpawns(intervalTicks.intValue());
            }
            ctx.setNodeOutput(nodeId, "flow", true);
        });

        registry.register("world_set_spawn_limits", (ctx, node) -> {
            String nodeId = findNodeId(ctx, node);
            if (nodeId == null) {
                return;
            }
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
            ctx.setNodeOutput(nodeId, "flow", true);
        });

        registry.register("world_management_get_snapshot", (ctx, node) -> {
            String nodeId = findNodeId(ctx, node);
            if (nodeId == null) {
                return;
            }
            WorldManagementService service = getWorldManagementService();
            if (service != null) {
                ctx.setNodeOutput(nodeId, "snapshot", service.createSnapshot());
            }
        });

        registry.register("world_management_get_worlds", (ctx, node) -> {
            String nodeId = findNodeId(ctx, node);
            if (nodeId == null) {
                return;
            }
            WorldManagementService service = getWorldManagementService();
            WorldSnapshot snapshot = service != null ? service.createSnapshot() : null;
            ctx.setNodeOutput(nodeId, "worlds", snapshot != null ? snapshot.getWorlds() : Collections.emptyList());
        });

        registry.register("world_management_get_world", (ctx, node) -> {
            String nodeId = findNodeId(ctx, node);
            if (nodeId == null) {
                return;
            }
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
            ctx.setNodeOutput(nodeId, "world", match);
        });

        registry.register("world_management_get_portals", (ctx, node) -> {
            String nodeId = findNodeId(ctx, node);
            if (nodeId == null) {
                return;
            }
            String worldName = ctx.getInputValue(node, "world_name", String.class, "");
            WorldManagementService service = getWorldManagementService();
            if (service == null) {
                ctx.setNodeOutput(nodeId, "portals", Collections.emptyList());
                return;
            }
            ctx.setNodeOutput(nodeId, "portals", worldName == null || worldName.isBlank() ? service.getPortals() : service.getPortalsByWorld(worldName));
        });

        registry.register("world_management_get_portal", (ctx, node) -> {
            String nodeId = findNodeId(ctx, node);
            if (nodeId == null) {
                return;
            }
            WorldManagementService service = getWorldManagementService();
            ctx.setNodeOutput(nodeId, "portal", service == null ? null : service.getPortal(ctx.getInputValue(node, "portal_id", String.class, "")));
        });

        registry.register("world_management_get_game_rules", (ctx, node) -> {
            String nodeId = findNodeId(ctx, node);
            if (nodeId == null) {
                return;
            }
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
            ctx.setNodeOutput(nodeId, "game_rules", gameRules);
        });

        registry.register("world_management_get_game_rule_descriptors", (ctx, node) -> {
            String nodeId = findNodeId(ctx, node);
            if (nodeId == null) {
                return;
            }
            WorldManagementService service = getWorldManagementService();
            ctx.setNodeOutput(nodeId, "descriptors", service == null ? Collections.emptyList() : service.getGameRuleDescriptors());
        });

        registry.register("world_management_get_map_snapshot", (ctx, node) -> {
            String nodeId = findNodeId(ctx, node);
            if (nodeId == null) {
                return;
            }
            WorldManagementService service = getWorldManagementService();
            if (service == null) {
                return;
            }
            WorldMapQuery query = new WorldMapQuery();
            query.setWorldName(ctx.getInputValue(node, "world_name", String.class, ""));
            query.setCenterX(ctx.getInputValue(node, "center_x", Double.class, 0.0));
            query.setCenterZ(ctx.getInputValue(node, "center_z", Double.class, 0.0));
            query.setZoom(ctx.getInputValue(node, "zoom", Integer.class, 1));
            ctx.setNodeOutput(nodeId, "snapshot", service.getMapService().createSnapshot(query));
        });
    }

    private static WorldManagementService getWorldManagementService() {
        ReSync plugin = ReSync.getInstance();
        if (plugin == null || plugin.getV2Server() == null) {
            return null;
        }
        return plugin.getV2Server().getWorldManagementService();
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
