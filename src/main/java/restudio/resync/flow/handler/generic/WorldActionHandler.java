package restudio.resync.flow.handler.generic;

import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.entity.Player;
import restudio.flow.data.FlowNode;
import restudio.flow.data.FlowResourceReference;
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

import java.util.LinkedHashMap;
import java.util.Locale;
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
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) throw new IllegalArgumentException("Location coordinates must be finite");
            World world = null;
            Player player = ctx.getPlayer();
            if (player != null) {
                world = player.getWorld();
            } else if (!Bukkit.getWorlds().isEmpty()) {
                world = Bukkit.getWorlds().getFirst();
            }
            if (world == null) throw new IllegalStateException("No world is available");
            ctx.setOutput(node, "location", new Location(world, x, y, z));
        });

        operations.put("world_get_by_name", (ctx, node) -> {
            String worldName = ctx.getInputValue(node, "world_name", String.class, "");
            if (worldName == null || worldName.isBlank()) throw new IllegalArgumentException("World name is required");
            ctx.setOutput(node, "world", Bukkit.getWorld(worldName));
        });

        operations.put("world_get_all", (ctx, node) -> {
            ctx.setOutput(node, "worlds_list", Bukkit.getWorlds());
        });

        operations.put("world_set_time", (ctx, node) -> {
            World world = requireWorld(ctx, node);
            Long timeTicks = ctx.getInputValue(node, "time_ticks", Long.class, 0L);
            world.setTime(timeTicks);
        });

        operations.put("world_get_time", (ctx, node) -> {
            ctx.setOutput(node, "time_ticks", requireWorld(ctx, node).getTime());
        });

        operations.put("world_set_full_time", (ctx, node) -> {
            World world = requireWorld(ctx, node);
            Long fullTimeTicks = ctx.getInputValue(node, "full_time_ticks", Long.class, 0L);
            if (fullTimeTicks < 0) throw new IllegalArgumentException("World full time cannot be negative");
            world.setFullTime(fullTimeTicks);
        });

        operations.put("world_get_full_time", (ctx, node) -> {
            ctx.setOutput(node, "full_time_ticks", requireWorld(ctx, node).getFullTime());
        });

        operations.put("world_set_day_time", (ctx, node) -> {
            World world = requireWorld(ctx, node);
            Long time = ctx.getInputValue(node, "time", Long.class, 0L);
            world.setTime(time);
        });

        operations.put("world_set_weather", (ctx, node) -> {
            World world = requireWorld(ctx, node);
            String weatherType = ctx.getInputValue(node, "weather_type", String.class, "clear");
            Integer durationTicks = ctx.getInputValue(node, "duration_ticks", Integer.class, 0);
            if (weatherType == null) throw new IllegalArgumentException("Weather type is required");
            if (durationTicks < 0) throw new IllegalArgumentException("Weather duration cannot be negative");
            switch (weatherType.toLowerCase(Locale.ROOT)) {
                case "clear", "clear_all" -> { world.setStorm(false); world.setThundering(false); }
                case "rain" -> { world.setStorm(true); world.setThundering(false); }
                case "thunder", "downfall" -> { world.setStorm(true); world.setThundering(true); }
                default -> throw new IllegalArgumentException("Unknown weather type: " + weatherType);
            }
            if (durationTicks > 0) {
                world.setWeatherDuration(durationTicks);
                world.setThunderDuration(durationTicks);
            }
        });

        operations.put("world_get_weather", (ctx, node) -> {
            World world = requireWorld(ctx, node);
            ctx.setOutput(node, "weather_type", world.isThundering() ? "thunder" : world.hasStorm() ? "rain" : "clear");
            ctx.setOutput(node, "thundering", world.isThundering());
            ctx.setOutput(node, "has_storm", world.hasStorm());
        });

        operations.put("world_spawn_set", (ctx, node) -> {
            World world = requireWorld(ctx, node);
            Location location = requireWorldLocation(ctx, node, "location", world);
            world.setSpawnLocation(location);
        });

        operations.put("world_spawn_get", (ctx, node) -> {
            ctx.setOutput(node, "spawn_location", requireWorld(ctx, node).getSpawnLocation());
        });

        operations.put("world_set_difficulty", (ctx, node) -> {
            World world = requireWorld(ctx, node);
            String difficultyStr = ctx.getInputValue(node, "difficulty", String.class, "normal");
            if (difficultyStr == null) throw new IllegalArgumentException("World difficulty is required");
            try {
                world.setDifficulty(Difficulty.valueOf(difficultyStr.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("Unknown world difficulty: " + difficultyStr, exception);
            }
        });

        operations.put("world_get_difficulty", (ctx, node) -> {
            ctx.setOutput(node, "difficulty", requireWorld(ctx, node).getDifficulty().name().toLowerCase(Locale.ROOT));
        });

        operations.put("world_set_pvp", (ctx, node) -> {
            World world = requireWorld(ctx, node);
            Boolean pvpEnabled = ctx.getInputValue(node, "pvp_enabled", Boolean.class, false);
            world.setPVP(Boolean.TRUE.equals(pvpEnabled));
        });

        operations.put("world_get_pvp", (ctx, node) -> {
            ctx.setOutput(node, "pvp_enabled", requireWorld(ctx, node).getPVP());
        });

        operations.put("world_save", (ctx, node) -> {
            requireWorld(ctx, node).save();
        });

        operations.put("world_auto_save_set", (ctx, node) -> {
            World world = requireWorld(ctx, node);
            Long intervalTicks = ctx.getInputValue(node, "interval_ticks", Long.class, 0L);
            world.setAutoSave(intervalTicks > 0);
        });

        operations.put("world_set_spawn_limits", (ctx, node) -> {
            World world = requireWorld(ctx, node);
            Integer monsters = ctx.getInputValue(node, "monsters", Integer.class, 0);
            Integer animals = ctx.getInputValue(node, "animals", Integer.class, 0);
            Integer waterAmbient = ctx.getInputValue(node, "water_ambient", Integer.class, 0);
            Integer waterAnimals = ctx.getInputValue(node, "water_animals", Integer.class, 0);
            Integer waterUnderground = ctx.getInputValue(node, "water_underground", Integer.class, 0);
            if (monsters < 0 || animals < 0 || waterAmbient < 0 || waterAnimals < 0 || waterUnderground < 0) throw new IllegalArgumentException("World spawn limits cannot be negative");
            world.setMonsterSpawnLimit(monsters);
            world.setAnimalSpawnLimit(animals);
            world.setWaterAmbientSpawnLimit(waterAmbient);
            world.setWaterAnimalSpawnLimit(waterAnimals);
            world.setWaterUndergroundCreatureSpawnLimit(waterUnderground);
        });

        operations.put("world_management_get_snapshot", (ctx, node) -> {
            ctx.setOutput(node, "snapshot", requireWorldManagementService().createSnapshot());
        });

        operations.put("world_management_get_worlds", (ctx, node) -> {
            WorldSnapshot snapshot = requireWorldManagementService().createSnapshot();
            ctx.setOutput(node, "worlds", snapshot.getWorlds());
        });

        operations.put("world_management_get_world", (ctx, node) -> {
            String worldName = ctx.getInputValue(node, "world_name", String.class, "");
            if (worldName == null || worldName.isBlank()) throw new IllegalArgumentException("World name is required");
            WorldManagementService service = requireWorldManagementService();
            WorldRegistryEntry match = null;
            for (WorldRegistryEntry entry : service.createSnapshot().getWorlds()) {
                if (entry != null && entry.getWorldName() != null && entry.getWorldName().equalsIgnoreCase(worldName)) {
                    match = entry;
                    break;
                }
            }
            ctx.setOutput(node, "world", match);
        });

        operations.put("world_management_get_portals", (ctx, node) -> {
            String worldName = ctx.getInputValue(node, "world_name", String.class, "");
            WorldManagementService service = getWorldManagementService();
            if (service == null) {
                throw new IllegalStateException("World management service is unavailable");
            }
            ctx.setOutput(node, "portals", worldName == null || worldName.isBlank() ? service.getPortals() : service.getPortalsByWorld(worldName));
        });

        operations.put("world_management_get_portal", (ctx, node) -> {
            String portalId = ctx.getInputValue(node, "portal_id", String.class, "");
            if (portalId == null || portalId.isBlank()) throw new IllegalArgumentException("Portal ID is required");
            ctx.setOutput(node, "portal", requireWorldManagementService().getPortal(portalId));
        });

        operations.put("world_management_get_game_rules", (ctx, node) -> {
            String worldName = ctx.getInputValue(node, "world_name", String.class, "");
            if (worldName == null || worldName.isBlank()) throw new IllegalArgumentException("World name is required");
            WorldManagementService service = requireWorldManagementService();
            Map<String, String> gameRules = new LinkedHashMap<>();
            for (WorldRegistryEntry entry : service.createSnapshot().getWorlds()) {
                if (entry != null && entry.getWorldName() != null && entry.getWorldName().equalsIgnoreCase(worldName)) {
                    gameRules.putAll(entry.getGameRules());
                    break;
                }
            }
            ctx.setOutput(node, "game_rules", gameRules);
        });

        operations.put("world_management_get_game_rule_descriptors", (ctx, node) -> {
            ctx.setOutput(node, "descriptors", requireWorldManagementService().getGameRuleDescriptors());
        });

        operations.put("world_management_get_map_snapshot", (ctx, node) -> {
            WorldManagementService service = getWorldManagementService();
            if (service == null) throw new IllegalStateException("World management service is unavailable");
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
                throw new IllegalArgumentException("World management action is required");
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
                throw new IllegalArgumentException("Unknown world management action: " + action);
            }
            operation.accept(ctx, node);
        });

        operations.put("world_set_spawn", (ctx, node) -> {
            World world = requireWorld(ctx, node);
            world.setSpawnLocation(requireWorldLocation(ctx, node, "location", world));
        });

        operations.put("world_set_keep_spawn", (ctx, node) -> {
            World world = requireWorld(ctx, node);
            Boolean keepSpawn = ctx.getInputValue(node, "keep_spawn_time", Boolean.class, true);
            world.setKeepSpawnInMemory(Boolean.TRUE.equals(keepSpawn));
        });

        operations.put("world_set_auto_save", (ctx, node) -> {
            World world = requireWorld(ctx, node);
            Boolean autoSave = ctx.getInputValue(node, "auto_save", Boolean.class, true);
            world.setAutoSave(Boolean.TRUE.equals(autoSave));
        });

        operations.put("world_spawn_lightning", (ctx, node) -> {
            World world = requireWorld(ctx, node);
            Location location = requireWorldLocation(ctx, node, "location", world);
            String effectName = ctx.getInputValue(node, "effect", String.class, "strike");
            if (effectName == null) throw new IllegalArgumentException("Lightning effect is required");
            switch (effectName.toLowerCase(Locale.ROOT)) {
                case "effect", "visual" -> world.strikeLightningEffect(location);
                case "strike", "lightning" -> world.strikeLightning(location);
                default -> throw new IllegalArgumentException("Unknown lightning effect: " + effectName);
            }
        });

        operations.put("world_set_border_size", (ctx, node) -> {
            World world = requireWorld(ctx, node);
            Double size = ctx.getInputValue(node, "size", Double.class, 500.0);
            if (!Double.isFinite(size) || size < 1 || size > 59_999_968) throw new IllegalArgumentException("World border size must be between 1 and 59999968");
            world.getWorldBorder().setSize(size);
        });

        operations.put("world_set_border_damage", (ctx, node) -> {
            World world = requireWorld(ctx, node);
            Double damage = ctx.getInputValue(node, "damage_amount", Double.class, 1.0);
            if (!Double.isFinite(damage) || damage < 0) throw new IllegalArgumentException("World border damage must be a finite non-negative number");
            world.getWorldBorder().setDamageAmount(damage);
        });

        operations.put("world_set_border_warning", (ctx, node) -> {
            World world = requireWorld(ctx, node);
            Integer warningDistance = ctx.getInputValue(node, "warning_distance", Integer.class, 5);
            if (warningDistance < 0) throw new IllegalArgumentException("World border warning distance cannot be negative");
            world.getWorldBorder().setWarningDistance(warningDistance);
        });

        operations.put("world_management_create_world", (ctx, node) -> {
            WorldManagementService service = getWorldManagementService();
            Object generatorConfigValue = ctx.getInputValue(node, "generator_config");
            String generatorConfig = generatorConfigValue instanceof FlowResourceReference reference
                ? reference.id()
                : generatorConfigValue == null ? "" : String.valueOf(generatorConfigValue);
            WorldOperationResult result = service != null
                ? service.createWorld(
                ctx.getInputValue(node, "world_name", String.class, ""),
                ctx.getInputValue(node, "seed", String.class, ""),
                ctx.getInputValue(node, "environment", String.class, ""),
                ctx.getInputValue(node, "generator", String.class, ""),
                generatorConfig
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
            World world = requireWorld(ctx, node);
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

            if (property == null || property.isBlank()) throw new IllegalArgumentException("World property is required");
            if (action == null || action.isBlank()) throw new IllegalArgumentException("World property action is required");
            if ("set".equalsIgnoreCase(action)) {
                    switch (property.toLowerCase()) {
                        case "gamerule" -> {
                            if (gamerule.isBlank()) {
                                throw new IllegalArgumentException("Game rule is required");
                            }
                            GameRule rule = GameRule.getByName(gamerule);
                            if (rule == null) {
                                throw new IllegalArgumentException("Unknown game rule: " + gamerule);
                            }
                            Object parsed = parseGameRuleValue(rule, gameruleValue);
                            world.setGameRule(rule, parsed);
                            success = true;
                        }
                        case "time" -> {
                            if (time < 0) throw new IllegalArgumentException("World day time cannot be negative");
                            world.setTime(time);
                            success = true;
                        }
                        case "full_time" -> {
                            if (fullTime < 0) throw new IllegalArgumentException("World full time cannot be negative");
                            world.setFullTime(fullTime);
                            success = true;
                        }
                        case "weather" -> {
                            String normalized = weather.toLowerCase(Locale.ROOT);
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
                                default -> throw new IllegalArgumentException("Unknown weather mode: " + weather);
                            }
                        }
                        case "difficulty" -> {
                            try {
                                Difficulty worldDifficulty = Difficulty.valueOf(difficulty.toUpperCase(Locale.ROOT));
                                world.setDifficulty(worldDifficulty);
                                success = true;
                            } catch (IllegalArgumentException exception) {
                                throw new IllegalArgumentException("Unknown world difficulty: " + difficulty, exception);
                            }
                        }
                        case "spawn" -> {
                            if (location == null) throw new IllegalArgumentException("World spawn location is required");
                            if (location.getWorld() == null || !location.getWorld().equals(world)) throw new IllegalArgumentException("Spawn location must be in the selected world");
                            world.setSpawnLocation(location);
                            success = true;
                        }
                        case "biome" -> {
                            if (location == null || biome.isBlank()) {
                                throw new IllegalArgumentException("Biome world location and value are required");
                            }
                            if (location.getWorld() == null || !location.getWorld().equals(world)) {
                                throw new IllegalArgumentException("Biome location must be in the selected world");
                            }
                            Biome worldBiome;
                            try {
                                worldBiome = Biome.valueOf(biome.toUpperCase(Locale.ROOT));
                            } catch (IllegalArgumentException exception) {
                                throw new IllegalArgumentException("Unknown biome: " + biome, exception);
                            }
                            world.setBiome(location.getBlockX(), location.getBlockY(), location.getBlockZ(), worldBiome);
                            success = true;
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
                        default -> throw new IllegalArgumentException("Unknown writable world property: " + property);
                    }
            } else if ("get".equalsIgnoreCase(action)) {
                    switch (property.toLowerCase()) {
                        case "gamerule" -> {
                            if (gamerule.isBlank()) {
                                throw new IllegalArgumentException("Game rule is required");
                            }
                            GameRule rule = GameRule.getByName(gamerule);
                            if (rule == null) {
                                throw new IllegalArgumentException("Unknown game rule: " + gamerule);
                            }
                            result = world.getGameRuleValue(rule);
                            success = true;
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
                            if (location == null) throw new IllegalArgumentException("Biome location is required");
                            if (location.getWorld() == null || !location.getWorld().equals(world)) throw new IllegalArgumentException("Biome location must be in the selected world");
                            result = world.getBiome(location.getBlockX(), location.getBlockY(), location.getBlockZ()).name();
                            success = true;
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
                        default -> throw new IllegalArgumentException("Unknown readable world property: " + property);
                    }
            } else {
                throw new IllegalArgumentException("Unknown world property action: " + action);
            }

            if (!success) throw new IllegalArgumentException("World property operation is unsupported: " + property + "." + action);
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
        if (op == null) {
            throw new IllegalArgumentException("Unknown world action operation: " + operation);
        }
        op.accept(ctx, node);
        ctx.triggerOutput(Boolean.FALSE.equals(ctx.getOutput(node, "success")) ? "failed" : "flow");
    }

    private static WorldManagementService getWorldManagementService() {
        ReSync plugin = ReSync.getInstance();
        if (plugin == null || plugin.getReSyncServer() == null) return null;
        return plugin.getReSyncServer().getWorldManagementService();
    }

    private static WorldManagementService requireWorldManagementService() {
        WorldManagementService service = getWorldManagementService();
        if (service == null) throw new IllegalStateException("World management service is unavailable");
        return service;
    }

    private static World requireWorld(FlowContext context, FlowNode node) {
        World world = context.getInputValue(node, "world", World.class, null);
        if (world == null) throw new IllegalArgumentException("World is required");
        return world;
    }

    private static Location requireWorldLocation(FlowContext context, FlowNode node, String inputName, World world) {
        Location location = context.getInputValue(node, inputName, Location.class, null);
        if (location == null) throw new IllegalArgumentException("Location input is required: " + inputName);
        if (location.getWorld() == null || !location.getWorld().equals(world)) throw new IllegalArgumentException("Location must be in the selected world: " + inputName);
        return location;
    }

    private static void applyResult(FlowContext ctx, FlowNode node, WorldOperationResult result, String portalId) {
        boolean success = result != null && result.isSuccess();
        ctx.setOutput(node, "success", success);
        ctx.setOutput(node, "message", result != null ? result.getMessage() : "WorldManagementUnavailable");
        ctx.setOutput(node, "error_code", success ? "" : result != null && result.getData().get("errorCode") != null
            ? String.valueOf(result.getData().get("errorCode"))
            : result != null ? result.getMessage() : "WORLD_MANAGEMENT_UNAVAILABLE");
        if (success && result != null && result.getWorldName() != null) {
            ctx.setOutput(node, "world", Bukkit.getWorld(result.getWorldName()));
        }
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
        if (value == null || rule == null) {
            throw new IllegalArgumentException("Game rule and value are required");
        }
        if (rule.getType() == Boolean.class) {
            String normalized = String.valueOf(value).toLowerCase(Locale.ROOT);
            if (!normalized.equals("true") && !normalized.equals("false")) {
                throw new IllegalArgumentException("Boolean game rule value must be true or false");
            }
            return Boolean.valueOf(normalized);
        }
        if (rule.getType() == Integer.class) {
            return Integer.valueOf(String.valueOf(value));
        }
        throw new IllegalArgumentException("Unsupported game rule type: " + rule.getType().getName());
    }
}
