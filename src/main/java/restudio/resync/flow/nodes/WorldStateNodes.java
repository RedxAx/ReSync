package restudio.resync.flow.nodes;

import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.entity.Player;
import restudio.flow.data.FlowNode;
import restudio.resync.ReSync;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.FlowRegistry;
import restudio.resync.flow.NodeCategory;
import restudio.resync.world.WorldManagementService;
import restudio.resync.world.WorldOperationResult;
import restudio.resync.world.WorldPortal;

public class WorldStateNodes implements NodeCategory {

    @Override
    public void registerNodes(FlowRegistry registry) {
        registry.register("world_set_time", (ctx, node) -> {
            World world = ctx.getInputValue(node, "world", World.class, null);
            Long time = ctx.getInputValue(node, "time", Long.class, 6000L);

            if (world == null) {
                ctx.triggerOutput("flow");
                return;
            }

            Runnable timeTask = () -> {
                world.setTime(time);
            };

            if (Bukkit.isPrimaryThread()) {
                timeTask.run();
            } else {
                Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), timeTask);
            }
            ctx.triggerOutput("flow");
        });

        registry.register("world_set_weather", (ctx, node) -> {
            World world = ctx.getInputValue(node, "world", World.class, null);
            String weatherName = ctx.getInputValue(node, "weather", String.class, "clear");

            if (world == null) {
                ctx.triggerOutput("flow");
                return;
            }

            Runnable weatherTask = () -> {
                boolean storm = false;
                boolean thunder = false;

                switch (weatherName.toLowerCase()) {
                    case "rain":
                    case "storm":
                        storm = true;
                        break;
                    case "thunder":
                        storm = true;
                        thunder = true;
                        break;
                    case "clear":
                    default:
                        storm = false;
                        thunder = false;
                        break;
                }

                world.setStorm(storm);
                world.setThundering(thunder);
            };

            if (Bukkit.isPrimaryThread()) {
                weatherTask.run();
            } else {
                Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), weatherTask);
            }
            ctx.triggerOutput("flow");
        });

        registry.register("world_set_thunder", (ctx, node) -> {
            World world = ctx.getInputValue(node, "world", World.class, null);
            Boolean thundering = ctx.getInputValue(node, "thundering", Boolean.class, false);

            if (world == null) {
                ctx.triggerOutput("flow");
                return;
            }

            Runnable thunderTask = () -> {
                world.setThundering(thundering);
            };

            if (Bukkit.isPrimaryThread()) {
                thunderTask.run();
            } else {
                Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), thunderTask);
            }
            ctx.triggerOutput("flow");
        });

        registry.register("world_set_spawn", (ctx, node) -> {
            World world = ctx.getInputValue(node, "world", World.class, null);
            Location location = ctx.getInputValue(node, "location", Location.class, null);

            if (world == null || location == null) {
                ctx.triggerOutput("flow");
                return;
            }

            Runnable spawnTask = () -> {
                world.setSpawnLocation(location);
            };

            if (Bukkit.isPrimaryThread()) {
                spawnTask.run();
            } else {
                Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), spawnTask);
            }
            ctx.triggerOutput("flow");
        });

        registry.register("world_set_difficulty", (ctx, node) -> {
            World world = ctx.getInputValue(node, "world", World.class, null);
            String difficultyName = ctx.getInputValue(node, "difficulty", String.class, "NORMAL");

            if (world == null) {
                ctx.triggerOutput("flow");
                return;
            }

            try {
                Difficulty difficulty = Difficulty.valueOf(difficultyName.toUpperCase());
                Runnable difficultyTask = () -> {
                    world.setDifficulty(difficulty);
                };

                if (Bukkit.isPrimaryThread()) {
                    difficultyTask.run();
                } else {
                    Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), difficultyTask);
                }
            } catch (IllegalArgumentException e) {
            }
            ctx.triggerOutput("flow");
        });

        registry.register("world_set_pvp", (ctx, node) -> {
            World world = ctx.getInputValue(node, "world", World.class, null);
            Boolean pvp = ctx.getInputValue(node, "pvp", Boolean.class, false);

            if (world == null) {
                ctx.triggerOutput("flow");
                return;
            }

            Runnable pvpTask = () -> {
                world.setPVP(pvp);
            };

            if (Bukkit.isPrimaryThread()) {
                pvpTask.run();
            } else {
                Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), pvpTask);
            }
            ctx.triggerOutput("flow");
        });

        registry.register("world_set_keep_spawn", (ctx, node) -> {
            World world = ctx.getInputValue(node, "world", World.class, null);
            Boolean keepSpawn = ctx.getInputValue(node, "keep_spawn_time", Boolean.class, true);

            if (world == null) {
                ctx.triggerOutput("flow");
                return;
            }

            Runnable keepSpawnTask = () -> {
                world.setKeepSpawnInMemory(keepSpawn);
            };

            if (Bukkit.isPrimaryThread()) {
                keepSpawnTask.run();
            } else {
                Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), keepSpawnTask);
            }
            ctx.triggerOutput("flow");
        });

        registry.register("world_set_auto_save", (ctx, node) -> {
            World world = ctx.getInputValue(node, "world", World.class, null);
            Boolean autoSave = ctx.getInputValue(node, "auto_save", Boolean.class, true);

            if (world == null) {
                ctx.triggerOutput("flow");
                return;
            }

            Runnable autoSaveTask = () -> {
                world.setAutoSave(autoSave);
            };

            if (Bukkit.isPrimaryThread()) {
                autoSaveTask.run();
            } else {
                Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), autoSaveTask);
            }
            ctx.triggerOutput("flow");
        });

        registry.register("world_spawn_lightning", (ctx, node) -> {
            World world = ctx.getInputValue(node, "world", World.class, null);
            Location location = ctx.getInputValue(node, "location", Location.class, null);
            String effectName = ctx.getInputValue(node, "effect", String.class, "strike");

            if (world == null || location == null) {
                ctx.triggerOutput("flow");
                return;
            }

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
                Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), lightningTask);
            }
            ctx.triggerOutput("flow");
        });

        registry.register("world_set_border_size", (ctx, node) -> {
            World world = ctx.getInputValue(node, "world", World.class, null);
            Double size = ctx.getInputValue(node, "size", Double.class, 500.0);

            if (world == null) {
                ctx.triggerOutput("flow");
                return;
            }

            Runnable borderTask = () -> {
                WorldBorder border = world.getWorldBorder();
                border.setSize(size);
            };

            if (Bukkit.isPrimaryThread()) {
                borderTask.run();
            } else {
                Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), borderTask);
            }
            ctx.triggerOutput("flow");
        });

        registry.register("world_set_border_damage", (ctx, node) -> {
            World world = ctx.getInputValue(node, "world", World.class, null);
            Double damage = ctx.getInputValue(node, "damage_amount", Double.class, 1.0);

            if (world == null) {
                ctx.triggerOutput("flow");
                return;
            }

            Runnable borderDamageTask = () -> {
                WorldBorder border = world.getWorldBorder();
                border.setDamageAmount(damage);
            };

            if (Bukkit.isPrimaryThread()) {
                borderDamageTask.run();
            } else {
                Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), borderDamageTask);
            }
            ctx.triggerOutput("flow");
        });

        registry.register("world_set_border_warning", (ctx, node) -> {
            World world = ctx.getInputValue(node, "world", World.class, null);
            Integer warningDistance = ctx.getInputValue(node, "warning_distance", Integer.class, 5);

            if (world == null) {
                ctx.triggerOutput("flow");
                return;
            }

            Runnable borderWarningTask = () -> {
                WorldBorder border = world.getWorldBorder();
                border.setWarningDistance(warningDistance);
            };

            if (Bukkit.isPrimaryThread()) {
                borderWarningTask.run();
            } else {
                Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), borderWarningTask);
            }
            ctx.triggerOutput("flow");
        });

        registry.register("world_management_create_world", (ctx, node) -> {
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

        registry.register("world_management_scan_worlds", (ctx, node) -> {
            WorldManagementService service = getWorldManagementService();
            WorldOperationResult result = service != null
                ? service.scanUnregisteredWorlds()
                : WorldOperationResult.failure("scanWorlds", null, "WorldManagementUnavailable");
            applyResult(ctx, node, result, null);
        });

        registry.register("world_management_import_worlds", (ctx, node) -> {
            WorldManagementService service = getWorldManagementService();
            WorldOperationResult result = service != null
                ? service.importUnregisteredWorlds()
                : WorldOperationResult.failure("importWorlds", null, "WorldManagementUnavailable");
            applyResult(ctx, node, result, null);
        });

        registry.register("world_management_clone_world", (ctx, node) -> {
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

        registry.register("world_management_load_world", (ctx, node) -> {
            WorldManagementService service = getWorldManagementService();
            WorldOperationResult result = service != null
                ? service.loadWorld(ctx.getInputValue(node, "world_name", String.class, ""))
                : WorldOperationResult.failure("loadWorld", null, "WorldManagementUnavailable");
            applyResult(ctx, node, result, null);
        });

        registry.register("world_management_unload_world", (ctx, node) -> {
            WorldManagementService service = getWorldManagementService();
            WorldOperationResult result = service != null
                ? service.unloadWorld(
                ctx.getInputValue(node, "world_name", String.class, ""),
                ctx.getInputValue(node, "fallback_world", String.class, "")
            )
                : WorldOperationResult.failure("unloadWorld", null, "WorldManagementUnavailable");
            applyResult(ctx, node, result, null);
        });

        registry.register("world_management_delete_world", (ctx, node) -> {
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

        registry.register("world_management_set_rule", (ctx, node) -> {
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

        registry.register("world_management_set_difficulty", (ctx, node) -> {
            WorldManagementService service = getWorldManagementService();
            WorldOperationResult result = service != null
                ? service.setDifficulty(
                ctx.getInputValue(node, "world_name", String.class, ""),
                ctx.getInputValue(node, "difficulty", String.class, "")
            )
                : WorldOperationResult.failure("setWorldDifficulty", null, "WorldManagementUnavailable");
            applyResult(ctx, node, result, null);
        });

        registry.register("world_management_set_time_lock", (ctx, node) -> {
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

        registry.register("world_management_set_weather_lock", (ctx, node) -> {
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

        registry.register("world_management_set_isolated_state", (ctx, node) -> {
            WorldManagementService service = getWorldManagementService();
            WorldOperationResult result = service != null
                ? service.setIsolatedPlayerState(
                ctx.getInputValue(node, "world_name", String.class, ""),
                ctx.getInputValue(node, "enabled", Boolean.class, false)
            )
                : WorldOperationResult.failure("setIsolatedState", null, "WorldManagementUnavailable");
            applyResult(ctx, node, result, null);
        });

        registry.register("world_management_create_portal", (ctx, node) -> {
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

        registry.register("world_management_delete_portal", (ctx, node) -> {
            WorldManagementService service = getWorldManagementService();
            WorldOperationResult result = service != null
                ? service.deletePortal(ctx.getInputValue(node, "portal_id", String.class, ""))
                : WorldOperationResult.failure("deletePortal", null, "WorldManagementUnavailable");
            applyResult(ctx, node, result, null);
        });

        registry.register("world_management_set_portal_enabled", (ctx, node) -> {
            WorldManagementService service = getWorldManagementService();
            WorldOperationResult result = service != null
                ? service.setPortalEnabled(
                ctx.getInputValue(node, "portal_id", String.class, ""),
                ctx.getInputValue(node, "enabled", Boolean.class, true)
            )
                : WorldOperationResult.failure("setPortalEnabled", null, "WorldManagementUnavailable");
            applyResult(ctx, node, result, extractPortalId(result));
        });

        registry.register("world_management_set_portal_destination", (ctx, node) -> {
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

        registry.register("world_management_set_portal_bounds", (ctx, node) -> {
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

        registry.register("world_management_teleport_player_to_world", (ctx, node) -> {
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

        registry.register("world_management_teleport_player_to_world_spawn", (ctx, node) -> {
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

        registry.register("world_management_teleport_player_to_portal", (ctx, node) -> {
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
    }

    private static WorldManagementService getWorldManagementService() {
        ReSync plugin = ReSync.getInstance();
        if (plugin == null || plugin.getV2Server() == null) {
            return null;
        }
        return plugin.getV2Server().getWorldManagementService();
    }

    private static void applyResult(FlowContext ctx, FlowNode node, WorldOperationResult result, String portalId) {
        String nodeId = findNodeId(ctx, node);
        if (nodeId != null) {
            boolean success = result != null && result.isSuccess();
            ctx.setNodeOutput(nodeId, "success", success);
            ctx.setNodeOutput(nodeId, "message", result != null ? result.getMessage() : "WorldManagementUnavailable");
            if (result != null && result.getData().containsKey("count")) {
                ctx.setNodeOutput(nodeId, "count", result.getData().get("count"));
            }
            if (portalId != null) {
                ctx.setNodeOutput(nodeId, "portal_id", portalId);
            }
        }
        ctx.triggerOutput("flow");
    }

    private static String extractPortalId(WorldOperationResult result) {
        if (result == null) {
            return null;
        }
        Object portal = result.getData().get("portal");
        if (portal instanceof WorldPortal worldPortal) {
            return worldPortal.getPortalId();
        }
        Object portalId = result.getData().get("portalId");
        return portalId == null ? null : String.valueOf(portalId);
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
