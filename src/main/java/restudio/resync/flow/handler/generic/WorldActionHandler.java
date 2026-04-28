package restudio.resync.flow.handler.generic;

import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.block.Biome;
import org.bukkit.entity.Player;
import restudio.flow.data.FlowNode;
import restudio.resync.ReSync;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.NodeHandler;
import restudio.resync.world.WorldManagementService;
import restudio.resync.world.WorldMapQuery;
import restudio.resync.world.WorldOperationResult;
import restudio.resync.world.WorldPortal;
import restudio.resync.world.WorldRegistryEntry;
import restudio.resync.world.WorldSnapshot;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

public class WorldActionHandler implements NodeHandler {
    private final Map<String, BiConsumer<FlowContext, FlowNode>> operations = new ConcurrentHashMap<>();

    public WorldActionHandler() {
        operations.put("get_location", (ctx, node) -> {
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
        });

        operations.put("world_get_by_name", (ctx, node) -> {
            String worldName = ctx.getInputValue(node, "world_name", String.class, "");
            ctx.setOutput(node, "world", Bukkit.getWorld(worldName));
        });

        operations.put("world_get_all", (ctx, node) -> {
            ctx.setOutput(node, "worlds_list", Bukkit.getWorlds());
        });

        operations.put("world_set_time", (ctx, node) -> {
            World world = ctx.getInputValue(node, "world", World.class, null);
            Long timeTicks = ctx.getInputValue(node, "time_ticks", Long.class, 0L);
            if (world != null) world.setTime(timeTicks);
        });

        operations.put("world_get_time", (ctx, node) -> {
            World world = ctx.getInputValue(node, "world", World.class, null);
            if (world != null) ctx.setOutput(node, "time_ticks", world.getTime());
        });

        operations.put("world_set_full_time", (ctx, node) -> {
            World world = ctx.getInputValue(node, "world", World.class, null);
            Long fullTimeTicks = ctx.getInputValue(node, "full_time_ticks", Long.class, 0L);
            if (world != null) world.setFullTime(fullTimeTicks);
        });

        operations.put("world_get_full_time", (ctx, node) -> {
            World world = ctx.getInputValue(node, "world", World.class, null);
            if (world != null) ctx.setOutput(node, "full_time_ticks", world.getFullTime());
        });

        operations.put("world_set_day_time", (ctx, node) -> {
            World world = ctx.getInputValue(node, "world", World.class, null);
            Long time = ctx.getInputValue(node, "time", Long.class, 0L);
            if (world != null) world.setTime(time);
        });

        operations.put("world_set_weather", (ctx, node) -> {
            World world = ctx.getInputValue(node, "world", World.class, null);
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
        });

        operations.put("world_get_weather", (ctx, node) -> {
            World world = ctx.getInputValue(node, "world", World.class, null);
            if (world != null) {
                ctx.setOutput(node, "weather_type", world.isThundering() ? "thunder" : world.hasStorm() ? "rain" : "clear");
                ctx.setOutput(node, "thundering", world.isThundering());
                ctx.setOutput(node, "has_storm", world.hasStorm());
            }
        });

        operations.put("world_spawn_set", (ctx, node) -> {
            World world = ctx.getInputValue(node, "world", World.class, null);
            Location location = ctx.getInputValue(node, "location", Location.class, null);
            if (world != null && location != null) world.setSpawnLocation(location);
        });

        operations.put("world_spawn_get", (ctx, node) -> {
            World world = ctx.getInputValue(node, "world", World.class, null);
            if (world != null) ctx.setOutput(node, "spawn_location", world.getSpawnLocation());
        });

        operations.put("world_set_difficulty", (ctx, node) -> {
            World world = ctx.getInputValue(node, "world", World.class, null);
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
        });

        operations.put("world_get_difficulty", (ctx, node) -> {
            World world = ctx.getInputValue(node, "world", World.class, null);
            if (world != null) ctx.setOutput(node, "difficulty", world.getDifficulty().name().toLowerCase());
        });

        operations.put("world_set_pvp", (ctx, node) -> {
            World world = ctx.getInputValue(node, "world", World.class, null);
            Boolean pvpEnabled = ctx.getInputValue(node, "pvp_enabled", Boolean.class, false);
            if (world != null) world.setPVP(pvpEnabled);
        });

        operations.put("world_get_pvp", (ctx, node) -> {
            World world = ctx.getInputValue(node, "world", World.class, null);
            if (world != null) ctx.setOutput(node, "pvp_enabled", world.getPVP());
        });

        operations.put("world_save", (ctx, node) -> {
            World world = ctx.getInputValue(node, "world", World.class, null);
            if (world != null) world.save();
        });

        operations.put("world_auto_save_set", (ctx, node) -> {
            World world = ctx.getInputValue(node, "world", World.class, null);
            Long intervalTicks = ctx.getInputValue(node, "interval_ticks", Long.class, 0L);
            if (world != null) world.setTicksPerAnimalSpawns(intervalTicks.intValue());
        });

        operations.put("world_set_spawn_limits", (ctx, node) -> {
            World world = ctx.getInputValue(node, "world", World.class, null);
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
        });

        operations.put("world_management_get_snapshot", (ctx, node) -> {
            WorldManagementService service = getWorldManagementService();
            if (service != null) ctx.setOutput(node, "snapshot", service.createSnapshot());
        });

        operations.put("world_management_get_worlds", (ctx, node) -> {
            WorldManagementService service = getWorldManagementService();
            WorldSnapshot snapshot = service != null ? service.createSnapshot() : null;
            ctx.setOutput(node, "worlds", snapshot != null ? snapshot.getWorlds() : Collections.emptyList());
        });

        operations.put("world_management_get_world", (ctx, node) -> {
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
        });

        operations.put("world_management_get_portals", (ctx, node) -> {
            String worldName = ctx.getInputValue(node, "world_name", String.class, "");
            WorldManagementService service = getWorldManagementService();
            if (service == null) {
                ctx.setOutput(node, "portals", Collections.emptyList());
                return;
            }
            ctx.setOutput(node, "portals", worldName == null || worldName.isBlank() ? service.getPortals() : service.getPortalsByWorld(worldName));
        });

        operations.put("world_management_get_portal", (ctx, node) -> {
            WorldManagementService service = getWorldManagementService();
            ctx.setOutput(node, "portal", service == null ? null : service.getPortal(ctx.getInputValue(node, "portal_id", String.class, "")));
        });

        operations.put("world_management_get_game_rules", (ctx, node) -> {
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
        });

        operations.put("world_management_get_game_rule_descriptors", (ctx, node) -> {
            WorldManagementService service = getWorldManagementService();
            ctx.setOutput(node, "descriptors", service == null ? Collections.emptyList() : service.getGameRuleDescriptors());
        });

        operations.put("world_management_get_map_snapshot", (ctx, node) -> {
            WorldManagementService service = getWorldManagementService();
            if (service == null) return;
            WorldMapQuery query = new WorldMapQuery();
            query.setWorldName(ctx.getInputValue(node, "world_name", String.class, ""));
            query.setCenterX(ctx.getInputValue(node, "center_x", Double.class, 0.0));
            query.setCenterZ(ctx.getInputValue(node, "center_z", Double.class, 0.0));
            query.setZoom(ctx.getInputValue(node, "zoom", Integer.class, 1));
            ctx.setOutput(node, "snapshot", service.getMapService().createSnapshot(query));
        });

        operations.put("world_management", (ctx, node) -> {
            String action = ctx.getInputValue(node, "action", String.class, "");
            if (action == null || action.isBlank()) {
                ctx.triggerOutput("flow");
                return;
            }
            String operationId = action.startsWith("world_management_") ? action : "world_management_" + action;
            BiConsumer<FlowContext, FlowNode> operation = operations.get(operationId);
            if (operation == null) {
                String normalized = action.trim().toLowerCase()
                    .replaceAll("[^a-z0-9]+", "_")
                    .replaceAll("_+", "_")
                    .replaceAll("^_|_$", "");
                operationId = normalized.startsWith("world_management_") ? normalized : "world_management_" + normalized;
                operation = operations.get(operationId);
            }
            if (operation == null) {
                ctx.triggerOutput("flow");
                return;
            }
            operation.accept(ctx, node);
            if (action.startsWith("get_") || operationId.contains("_get_")) {
                ctx.triggerOutput("flow");
            }
        });

        operations.put("world_set_spawn", (ctx, node) -> {
            World world = ctx.getInputValue(node, "world", World.class, null);
            Location location = ctx.getInputValue(node, "location", Location.class, null);
            if (world == null || location == null) return;
            Runnable spawnTask = () -> world.setSpawnLocation(location);
            if (Bukkit.isPrimaryThread()) {
                spawnTask.run();
            } else {
                Bukkit.getScheduler().runTask(ReSync.getInstance(), spawnTask);
            }
        });

        operations.put("world_set_keep_spawn", (ctx, node) -> {
            World world = ctx.getInputValue(node, "world", World.class, null);
            Boolean keepSpawn = ctx.getInputValue(node, "keep_spawn_time", Boolean.class, true);
            if (world == null) return;
            Runnable keepSpawnTask = () -> world.setKeepSpawnInMemory(keepSpawn);
            if (Bukkit.isPrimaryThread()) {
                keepSpawnTask.run();
            } else {
                Bukkit.getScheduler().runTask(ReSync.getInstance(), keepSpawnTask);
            }
        });

        operations.put("world_set_auto_save", (ctx, node) -> {
            World world = ctx.getInputValue(node, "world", World.class, null);
            Boolean autoSave = ctx.getInputValue(node, "auto_save", Boolean.class, true);
            if (world == null) return;
            Runnable autoSaveTask = () -> world.setAutoSave(autoSave);
            if (Bukkit.isPrimaryThread()) {
                autoSaveTask.run();
            } else {
                Bukkit.getScheduler().runTask(ReSync.getInstance(), autoSaveTask);
            }
        });

        operations.put("world_spawn_lightning", (ctx, node) -> {
            World world = ctx.getInputValue(node, "world", World.class, null);
            Location location = ctx.getInputValue(node, "location", Location.class, null);
            String effectName = ctx.getInputValue(node, "effect", String.class, "strike");
            if (world == null || location == null) return;
            Runnable lightningTask = () -> {
                boolean effect = effectName.equalsIgnoreCase("effect") || effectName.equalsIgnoreCase("visual");
                world.strikeLightningEffect(location);
                if (!effect) {
                    world.strikeLightning(location);
                }
            };
            if (Bukkit.isPrimaryThread()) {
                lightningTask.run();
            } else {
                Bukkit.getScheduler().runTask(ReSync.getInstance(), lightningTask);
            }
        });

        operations.put("world_set_border_size", (ctx, node) -> {
            World world = ctx.getInputValue(node, "world", World.class, null);
            Double size = ctx.getInputValue(node, "size", Double.class, 500.0);
            if (world == null) return;
            Runnable borderTask = () -> {
                WorldBorder border = world.getWorldBorder();
                border.setSize(size);
            };
            if (Bukkit.isPrimaryThread()) {
                borderTask.run();
            } else {
                Bukkit.getScheduler().runTask(ReSync.getInstance(), borderTask);
            }
        });

        operations.put("world_set_border_damage", (ctx, node) -> {
            World world = ctx.getInputValue(node, "world", World.class, null);
            Double damage = ctx.getInputValue(node, "damage_amount", Double.class, 1.0);
            if (world == null) return;
            Runnable borderDamageTask = () -> {
                WorldBorder border = world.getWorldBorder();
                border.setDamageAmount(damage);
            };
            if (Bukkit.isPrimaryThread()) {
                borderDamageTask.run();
            } else {
                Bukkit.getScheduler().runTask(ReSync.getInstance(), borderDamageTask);
            }
        });

        operations.put("world_set_border_warning", (ctx, node) -> {
            World world = ctx.getInputValue(node, "world", World.class, null);
            Integer warningDistance = ctx.getInputValue(node, "warning_distance", Integer.class, 5);
            if (world == null) return;
            Runnable borderWarningTask = () -> {
                WorldBorder border = world.getWorldBorder();
                border.setWarningDistance(warningDistance);
            };
            if (Bukkit.isPrimaryThread()) {
                borderWarningTask.run();
            } else {
                Bukkit.getScheduler().runTask(ReSync.getInstance(), borderWarningTask);
            }
        });

        operations.put("world_management_create_world", (ctx, node) -> {
            WorldManagementService service = getWorldManagementService();
            WorldOperationResult result = service != null
                ? service.createWorld(
                ctx.getInputValue(node, "world_name", String.class, ""),
                ctx.getInputValue(node, "seed", String.class, ""),
                ctx.getInputValue(node, "environment", String.class, ""),
                ctx.getInputValue(node, "generator", String.class, "")
            )
                : WorldOperationResult.failure("createWorld", null, "WorldManagementUnavailable");
            applyResult(ctx, node, result, null);
        });

        operations.put("world_management_scan_worlds", (ctx, node) -> {
            WorldManagementService service = getWorldManagementService();
            WorldOperationResult result = service != null
                ? service.scanUnregisteredWorlds()
                : WorldOperationResult.failure("scanWorlds", null, "WorldManagementUnavailable");
            applyResult(ctx, node, result, null);
        });

        operations.put("world_management_import_worlds", (ctx, node) -> {
            WorldManagementService service = getWorldManagementService();
            WorldOperationResult result = service != null
                ? service.importUnregisteredWorlds()
                : WorldOperationResult.failure("importWorlds", null, "WorldManagementUnavailable");
            applyResult(ctx, node, result, null);
        });

        operations.put("world_management_clone_world", (ctx, node) -> {
            WorldManagementService service = getWorldManagementService();
            WorldOperationResult result = service != null
                ? service.cloneWorldAsync(
                ctx.getInputValue(node, "source_world", String.class, ""),
                ctx.getInputValue(node, "target_world", String.class, ""),
                ctx.getInputValue(node, "load_after_clone", Boolean.class, false)
            )
                : WorldOperationResult.failure("cloneWorld", null, "WorldManagementUnavailable");
            applyResult(ctx, node, result, null);
        });

        operations.put("world_management_load_world", (ctx, node) -> {
            WorldManagementService service = getWorldManagementService();
            WorldOperationResult result = service != null
                ? service.loadWorld(ctx.getInputValue(node, "world_name", String.class, ""))
                : WorldOperationResult.failure("loadWorld", null, "WorldManagementUnavailable");
            applyResult(ctx, node, result, null);
        });

        operations.put("world_management_unload_world", (ctx, node) -> {
            WorldManagementService service = getWorldManagementService();
            WorldOperationResult result = service != null
                ? service.unloadWorld(
                ctx.getInputValue(node, "world_name", String.class, ""),
                ctx.getInputValue(node, "fallback_world", String.class, "")
            )
                : WorldOperationResult.failure("unloadWorld", null, "WorldManagementUnavailable");
            applyResult(ctx, node, result, null);
        });

        operations.put("world_management_delete_world", (ctx, node) -> {
            WorldManagementService service = getWorldManagementService();
            WorldOperationResult result = service != null
                ? service.deleteWorld(
                ctx.getInputValue(node, "world_name", String.class, ""),
                ctx.getInputValue(node, "delete_files", Boolean.class, false),
                ctx.getInputValue(node, "fallback_world", String.class, "")
            )
                : WorldOperationResult.failure("deleteWorld", null, "WorldManagementUnavailable");
            applyResult(ctx, node, result, null);
        });

        operations.put("world_management_set_rule", (ctx, node) -> {
            WorldManagementService service = getWorldManagementService();
            WorldOperationResult result = service != null
                ? service.setGameRule(
                ctx.getInputValue(node, "world_name", String.class, ""),
                ctx.getInputValue(node, "rule_name", String.class, ""),
                ctx.getInputValue(node, "value", String.class, "")
            )
                : WorldOperationResult.failure("setWorldRule", null, "WorldManagementUnavailable");
            applyResult(ctx, node, result, null);
        });

        operations.put("world_management_set_difficulty", (ctx, node) -> {
            WorldManagementService service = getWorldManagementService();
            WorldOperationResult result = service != null
                ? service.setDifficulty(
                ctx.getInputValue(node, "world_name", String.class, ""),
                ctx.getInputValue(node, "difficulty", String.class, "")
            )
                : WorldOperationResult.failure("setWorldDifficulty", null, "WorldManagementUnavailable");
            applyResult(ctx, node, result, null);
        });

        operations.put("world_management_set_time_lock", (ctx, node) -> {
            WorldManagementService service = getWorldManagementService();
            Double lockedTime = ctx.getInputValue(node, "locked_time", Double.class, 0.0);
            WorldOperationResult result = service != null
                ? service.setTimeLock(
                ctx.getInputValue(node, "world_name", String.class, ""),
                ctx.getInputValue(node, "enabled", Boolean.class, false),
                lockedTime.longValue()
            )
                : WorldOperationResult.failure("setTimeLock", null, "WorldManagementUnavailable");
            applyResult(ctx, node, result, null);
        });

        operations.put("world_management_set_weather_lock", (ctx, node) -> {
            WorldManagementService service = getWorldManagementService();
            WorldOperationResult result = service != null
                ? service.setWeatherLock(
                ctx.getInputValue(node, "world_name", String.class, ""),
                ctx.getInputValue(node, "enabled", Boolean.class, false),
                ctx.getInputValue(node, "storm", Boolean.class, false),
                ctx.getInputValue(node, "thundering", Boolean.class, false)
            )
                : WorldOperationResult.failure("setWeatherLock", null, "WorldManagementUnavailable");
            applyResult(ctx, node, result, null);
        });

        operations.put("world_management_set_isolated_state", (ctx, node) -> {
            WorldManagementService service = getWorldManagementService();
            WorldOperationResult result = service != null
                ? service.setIsolatedPlayerState(
                ctx.getInputValue(node, "world_name", String.class, ""),
                ctx.getInputValue(node, "enabled", Boolean.class, false)
            )
                : WorldOperationResult.failure("setIsolatedState", null, "WorldManagementUnavailable");
            applyResult(ctx, node, result, null);
        });

        operations.put("world_management_create_portal", (ctx, node) -> {
            WorldManagementService service = getWorldManagementService();
            WorldPortal portal = new WorldPortal();
            portal.setPortalName(ctx.getInputValue(node, "portal_name", String.class, ""));
            portal.setSourceWorld(ctx.getInputValue(node, "source_world", String.class, ""));
            portal.setMinX(ctx.getInputValue(node, "min_x", Double.class, 0.0));
            portal.setMinY(ctx.getInputValue(node, "min_y", Double.class, 0.0));
            portal.setMinZ(ctx.getInputValue(node, "min_z", Double.class, 0.0));
            portal.setMaxX(ctx.getInputValue(node, "max_x", Double.class, 0.0));
            portal.setMaxY(ctx.getInputValue(node, "max_y", Double.class, 0.0));
            portal.setMaxZ(ctx.getInputValue(node, "max_z", Double.class, 0.0));
            portal.setDestinationWorld(ctx.getInputValue(node, "destination_world", String.class, ""));
            portal.setDestinationX(ctx.getInputValue(node, "destination_x", Double.class, 0.0));
            portal.setDestinationY(ctx.getInputValue(node, "destination_y", Double.class, 80.0));
            portal.setDestinationZ(ctx.getInputValue(node, "destination_z", Double.class, 0.0));
            portal.setDestinationYaw(ctx.getInputValue(node, "destination_yaw", Float.class, 0f));
            portal.setDestinationPitch(ctx.getInputValue(node, "destination_pitch", Float.class, 0f));
            portal.setEnabled(ctx.getInputValue(node, "enabled", Boolean.class, true));
            WorldOperationResult result = service != null
                ? service.createPortal(portal)
                : WorldOperationResult.failure("createPortal", null, "WorldManagementUnavailable");
            applyResult(ctx, node, result, result != null ? extractPortalId(result) : null);
        });

        operations.put("world_management_delete_portal", (ctx, node) -> {
            WorldManagementService service = getWorldManagementService();
            WorldOperationResult result = service != null
                ? service.deletePortal(ctx.getInputValue(node, "portal_id", String.class, ""))
                : WorldOperationResult.failure("deletePortal", null, "WorldManagementUnavailable");
            applyResult(ctx, node, result, null);
        });

        operations.put("world_management_set_portal_enabled", (ctx, node) -> {
            WorldManagementService service = getWorldManagementService();
            WorldOperationResult result = service != null
                ? service.setPortalEnabled(
                ctx.getInputValue(node, "portal_id", String.class, ""),
                ctx.getInputValue(node, "enabled", Boolean.class, true)
            )
                : WorldOperationResult.failure("setPortalEnabled", null, "WorldManagementUnavailable");
            applyResult(ctx, node, result, extractPortalId(result));
        });

        operations.put("world_management_set_portal_destination", (ctx, node) -> {
            WorldManagementService service = getWorldManagementService();
            WorldOperationResult result = service != null
                ? service.setPortalDestination(
                ctx.getInputValue(node, "portal_id", String.class, ""),
                ctx.getInputValue(node, "destination_world", String.class, ""),
                ctx.getInputValue(node, "destination_x", Double.class, 0.0),
                ctx.getInputValue(node, "destination_y", Double.class, 80.0),
                ctx.getInputValue(node, "destination_z", Double.class, 0.0),
                ctx.getInputValue(node, "destination_yaw", Float.class, 0f),
                ctx.getInputValue(node, "destination_pitch", Float.class, 0f)
            )
                : WorldOperationResult.failure("setPortalDestination", null, "WorldManagementUnavailable");
            applyResult(ctx, node, result, extractPortalId(result));
        });

        operations.put("world_management_set_portal_bounds", (ctx, node) -> {
            WorldManagementService service = getWorldManagementService();
            WorldOperationResult result = service != null
                ? service.setPortalBounds(
                ctx.getInputValue(node, "portal_id", String.class, ""),
                ctx.getInputValue(node, "source_world", String.class, ""),
                ctx.getInputValue(node, "min_x", Double.class, 0.0),
                ctx.getInputValue(node, "min_y", Double.class, 0.0),
                ctx.getInputValue(node, "min_z", Double.class, 0.0),
                ctx.getInputValue(node, "max_x", Double.class, 0.0),
                ctx.getInputValue(node, "max_y", Double.class, 0.0),
                ctx.getInputValue(node, "max_z", Double.class, 0.0)
            )
                : WorldOperationResult.failure("setPortalBounds", null, "WorldManagementUnavailable");
            applyResult(ctx, node, result, extractPortalId(result));
        });

        operations.put("world_management_teleport_player_to_world", (ctx, node) -> {
            WorldManagementService service = getWorldManagementService();
            Player player = ctx.getInputValue(node, "target", Player.class, null);
            WorldOperationResult result = service == null
                ? WorldOperationResult.failure("teleportPlayerToWorld", null, "WorldManagementUnavailable")
                : player == null
                ? WorldOperationResult.failure("teleportPlayerToWorld", null, "PlayerUnavailable")
                : service.teleportPlayerToWorld(
                player.getName(),
                ctx.getInputValue(node, "world_name", String.class, ""),
                ctx.getInputValue(node, "x", Double.class, 0.0),
                ctx.getInputValue(node, "y", Double.class, 80.0),
                ctx.getInputValue(node, "z", Double.class, 0.0),
                ctx.getInputValue(node, "yaw", Float.class, 0f),
                ctx.getInputValue(node, "pitch", Float.class, 0f)
            );
            applyResult(ctx, node, result, null);
        });

        operations.put("world_management_teleport_player_to_world_spawn", (ctx, node) -> {
            WorldManagementService service = getWorldManagementService();
            Player player = ctx.getInputValue(node, "target", Player.class, null);
            WorldOperationResult result = service == null
                ? WorldOperationResult.failure("teleportPlayerToWorldSpawn", null, "WorldManagementUnavailable")
                : player == null
                ? WorldOperationResult.failure("teleportPlayerToWorldSpawn", null, "PlayerUnavailable")
                : service.teleportPlayerToWorldSpawn(
                player.getName(),
                ctx.getInputValue(node, "world_name", String.class, "")
            );
            applyResult(ctx, node, result, null);
        });

        operations.put("world_management_teleport_player_to_portal", (ctx, node) -> {
            WorldManagementService service = getWorldManagementService();
            Player player = ctx.getInputValue(node, "target", Player.class, null);
            WorldOperationResult result = service == null
                ? WorldOperationResult.failure("teleportPlayerToPortal", null, "WorldManagementUnavailable")
                : player == null
                ? WorldOperationResult.failure("teleportPlayerToPortal", null, "PlayerUnavailable")
                : service.teleportPlayerToPortal(
                player.getName(),
                ctx.getInputValue(node, "portal_id", String.class, "")
            );
            applyResult(ctx, node, result, null);
        });

        operations.put("world_properties", (ctx, node) -> {
            World world = ctx.getInputValue(node, "world", World.class, null);
            String property = ctx.getInputValue(node, "property", String.class, "");
            String action = ctx.getInputValue(node, "action", String.class, "get");
            String gamerule = ctx.getInputValue(node, "gamerule", String.class, "");
            String gameruleValue = ctx.getInputValue(node, "gamerule_value", String.class, "");
            Long time = ctx.getInputValue(node, "time", Long.class, 6000L);
            Long fullTime = ctx.getInputValue(node, "full_time", Long.class, 6000L);
            String weather = ctx.getInputValue(node, "weather", String.class, "clear");
            String difficulty = ctx.getInputValue(node, "difficulty", String.class, "normal");
            Location location = ctx.getInputValue(node, "location", Location.class, null);
            String biome = ctx.getInputValue(node, "biome", String.class, "");
            Boolean pvp = ctx.getInputValue(node, "pvp", Boolean.class, false);
            Boolean autoSave = ctx.getInputValue(node, "auto_save", Boolean.class, true);
            Boolean keepSpawn = ctx.getInputValue(node, "keep_spawn", Boolean.class, true);
            boolean success = false;
            Object result = null;

            if (world != null && property != null && action != null) {
                if ("set".equalsIgnoreCase(action)) {
                    switch (property.toLowerCase()) {
                        case "gamerule" -> {
                            if (!gamerule.isBlank()) {
                                try {
                                    GameRule rule = GameRule.getByName(gamerule);
                                    if (rule != null) {
                                        Object parsed = parseGameRuleValue(rule, gameruleValue);
                                        if (parsed != null) {
                                            world.setGameRule(rule, parsed);
                                            success = true;
                                        }
                                    }
                                } catch (Exception ignored) {
                                }
                            }
                        }
                        case "time" -> {
                            world.setTime(Math.max(0L, time));
                            success = true;
                        }
                        case "full_time" -> {
                            world.setFullTime(Math.max(0L, fullTime));
                            success = true;
                        }
                        case "weather" -> {
                            String normalized = weather.toLowerCase();
                            switch (normalized) {
                                case "rain", "storm" -> {
                                    world.setStorm(true);
                                    world.setThundering(false);
                                    success = true;
                                }
                                case "thunder" -> {
                                    world.setStorm(true);
                                    world.setThundering(true);
                                    success = true;
                                }
                                case "clear" -> {
                                    world.setStorm(false);
                                    world.setThundering(false);
                                    success = true;
                                }
                                default -> {
                                }
                            }
                        }
                        case "difficulty" -> {
                            try {
                                Difficulty worldDifficulty = Difficulty.valueOf(difficulty.toUpperCase());
                                world.setDifficulty(worldDifficulty);
                                success = true;
                            } catch (IllegalArgumentException ignored) {
                            }
                        }
                        case "spawn" -> {
                            if (location != null) {
                                world.setSpawnLocation(location);
                                success = true;
                            }
                        }
                        case "biome" -> {
                            if (location != null && !biome.isBlank()) {
                                try {
                                    Biome worldBiome = Biome.valueOf(biome.toUpperCase());
                                    world.setBiome(location.getBlockX(), location.getBlockY(), location.getBlockZ(), worldBiome);
                                    success = true;
                                } catch (IllegalArgumentException ignored) {
                                }
                            }
                        }
                        case "pvp" -> {
                            world.setPVP(Boolean.TRUE.equals(pvp));
                            success = true;
                        }
                        case "auto_save" -> {
                            world.setAutoSave(Boolean.TRUE.equals(autoSave));
                            success = true;
                        }
                        case "keep_spawn" -> {
                            world.setKeepSpawnInMemory(Boolean.TRUE.equals(keepSpawn));
                            success = true;
                        }
                    }
                } else {
                    switch (property.toLowerCase()) {
                        case "gamerule" -> {
                            if (!gamerule.isBlank()) {
                                try {
                                    GameRule rule = GameRule.getByName(gamerule);
                                    if (rule != null) {
                                        result = world.getGameRuleValue(rule);
                                        success = true;
                                    }
                                } catch (Exception ignored) {
                                }
                            }
                        }
                        case "time" -> {
                            result = world.getTime();
                            success = true;
                        }
                        case "full_time" -> {
                            result = world.getFullTime();
                            success = true;
                        }
                        case "weather" -> {
                            if (world.isThundering()) {
                                result = "thunder";
                            } else if (world.hasStorm()) {
                                result = "rain";
                            } else {
                                result = "clear";
                            }
                            success = true;
                        }
                        case "difficulty" -> {
                            result = world.getDifficulty().name().toLowerCase();
                            success = true;
                        }
                        case "spawn" -> {
                            result = world.getSpawnLocation();
                            success = true;
                        }
                        case "biome" -> {
                            if (location != null) {
                                result = world.getBiome(location.getBlockX(), location.getBlockY(), location.getBlockZ()).name();
                                success = true;
                            }
                        }
                        case "seed" -> {
                            result = world.getSeed();
                            success = true;
                        }
                        case "name" -> {
                            result = world.getName();
                            success = true;
                        }
                        case "environment" -> {
                            result = world.getEnvironment().name().toLowerCase();
                            success = true;
                        }
                        case "entities" -> {
                            result = world.getEntities();
                            success = true;
                        }
                        case "players" -> {
                            result = world.getPlayers();
                            success = true;
                        }
                        case "has_storm" -> {
                            result = world.hasStorm();
                            success = true;
                        }
                        case "thundering" -> {
                            result = world.isThundering();
                            success = true;
                        }
                        case "weather_type" -> {
                            if (world.isThundering()) {
                                result = "thunder";
                            } else if (world.hasStorm()) {
                                result = "rain";
                            } else {
                                result = "clear";
                            }
                            success = true;
                        }
                        case "pvp" -> {
                            result = world.getPVP();
                            success = true;
                        }
                        case "auto_save" -> {
                            result = world.isAutoSave();
                            success = true;
                        }
                        case "keep_spawn" -> {
                            result = world.getKeepSpawnInMemory();
                            success = true;
                        }
                    }
                }
            }

            ctx.setOutput(node, "success", success);
            ctx.setOutput(node, "result", result);
            if (!"set".equalsIgnoreCase(action) && result != null && property != null && !property.isBlank()) {
                ctx.setOutput(node, property, result);
            }
        });
    }

    public void registerTo(HandlerRegistry registry) {
        registry.register("WorldActionHandler", this);
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

    private static WorldManagementService getWorldManagementService() {
        ReSync plugin = ReSync.getInstance();
        if (plugin == null || plugin.getReSyncServer() == null) return null;
        return plugin.getReSyncServer().getWorldManagementService();
    }

    private static void applyResult(FlowContext ctx, FlowNode node, WorldOperationResult result, String portalId) {
        boolean success = result != null && result.isSuccess();
        ctx.setOutput(node, "success", success);
        ctx.setOutput(node, "message", result != null ? result.getMessage() : "WorldManagementUnavailable");
        if (result != null && result.getData().containsKey("count")) {
            ctx.setOutput(node, "count", result.getData().get("count"));
        }
        if (portalId != null) {
            ctx.setOutput(node, "portal_id", portalId);
        }
    }

    private static String extractPortalId(WorldOperationResult result) {
        if (result == null) return null;
        Object portal = result.getData().get("portal");
        if (portal instanceof WorldPortal worldPortal) {
            return worldPortal.getPortalId();
        }
        Object portalId = result.getData().get("portalId");
        return portalId == null ? null : String.valueOf(portalId);
    }

    private static Object parseGameRuleValue(GameRule rule, Object value) {
        if (value == null || rule == null) return null;
        if (rule.getType() == Boolean.class) return Boolean.valueOf(String.valueOf(value));
        if (rule.getType() == Integer.class) return Integer.valueOf(String.valueOf(value));
        return String.valueOf(value);
    }
}
