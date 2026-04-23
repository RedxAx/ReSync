package restudio.resync.flow.nodes;

import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.entity.Player;
import restudio.flow.data.FlowNode;
import restudio.flow.data.FlowType;
import restudio.resync.ReSync;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.FlowRegistry;
import restudio.resync.flow.registry.DefineNode;
import restudio.resync.flow.registry.FlowPin;
import restudio.resync.flow.registry.NodeDefinition;
import restudio.resync.world.WorldManagementService;
import restudio.resync.world.WorldOperationResult;
import restudio.resync.world.WorldPortal;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

public class WorldStateNodes {

    private static final Map<String, BiConsumer<FlowContext, FlowNode>> LEGACY_EXECUTORS = new ConcurrentHashMap<>();
    private static volatile boolean initialized;

    private static void registerLegacyNodes(FlowRegistry registry) {
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

    private static void ensureLegacyInitialized() {
        if (initialized) {
            return;
        }
        synchronized (WorldStateNodes.class) {
            if (initialized) {
                return;
            }
            FlowRegistry legacyRegistry = new FlowRegistry();
            registerLegacyNodes(legacyRegistry);
            for (String type : legacyRegistry.getRegisteredTypes()) {
                LEGACY_EXECUTORS.put(type, legacyRegistry.getExecutor(type));
            }
            initialized = true;
        }
    }

    private void executeLegacy(String id, FlowContext ctx, FlowNode node) {
        ensureLegacyInitialized();
        BiConsumer<FlowContext, FlowNode> executor = LEGACY_EXECUTORS.get(id);
        if (executor == null) {
            ctx.triggerOutput("flow");
            return;
        }
        executor.accept(ctx, node);
    }

    @DefineNode(id = "world_set_spawn", displayName = "Set Spawn", category = NodeDefinition.NodeCategory.WORLD,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "world", dataType = FlowType.ANY),
                    @FlowPin(name = "location", dataType = FlowType.LOCATION)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void worldSetSpawn(FlowContext ctx, FlowNode node) {
        executeLegacy("world_set_spawn", ctx, node);
    }

    @DefineNode(id = "world_set_difficulty", displayName = "Set Difficulty", category = NodeDefinition.NodeCategory.WORLD,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "world", dataType = FlowType.ANY),
                    @FlowPin(name = "difficulty", dataType = FlowType.STRING)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void worldSetDifficulty(FlowContext ctx, FlowNode node) {
        executeLegacy("world_set_difficulty", ctx, node);
    }

    @DefineNode(id = "world_set_pvp", displayName = "Set Pvp", category = NodeDefinition.NodeCategory.WORLD,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "world", dataType = FlowType.ANY),
                    @FlowPin(name = "pvp", dataType = FlowType.BOOLEAN)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void worldSetPvp(FlowContext ctx, FlowNode node) {
        executeLegacy("world_set_pvp", ctx, node);
    }

    @DefineNode(id = "world_set_keep_spawn", displayName = "Set Keep Spawn", category = NodeDefinition.NodeCategory.WORLD,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "world", dataType = FlowType.ANY),
                    @FlowPin(name = "keep_spawn_time", dataType = FlowType.BOOLEAN)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void worldSetKeepSpawn(FlowContext ctx, FlowNode node) {
        executeLegacy("world_set_keep_spawn", ctx, node);
    }

    @DefineNode(id = "world_set_auto_save", displayName = "Set Auto Save", category = NodeDefinition.NodeCategory.WORLD,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "world", dataType = FlowType.ANY),
                    @FlowPin(name = "auto_save", dataType = FlowType.BOOLEAN)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void worldSetAutoSave(FlowContext ctx, FlowNode node) {
        executeLegacy("world_set_auto_save", ctx, node);
    }

    @DefineNode(id = "world_spawn_lightning", displayName = "Spawn Lightning", category = NodeDefinition.NodeCategory.WORLD,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "world", dataType = FlowType.ANY),
                    @FlowPin(name = "location", dataType = FlowType.LOCATION),
                    @FlowPin(name = "effect", dataType = FlowType.STRING)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void worldSpawnLightning(FlowContext ctx, FlowNode node) {
        executeLegacy("world_spawn_lightning", ctx, node);
    }

    @DefineNode(id = "world_set_border_size", displayName = "Set Border Size", category = NodeDefinition.NodeCategory.WORLD,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "world", dataType = FlowType.ANY),
                    @FlowPin(name = "size", dataType = FlowType.NUMBER)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void worldSetBorderSize(FlowContext ctx, FlowNode node) {
        executeLegacy("world_set_border_size", ctx, node);
    }

    @DefineNode(id = "world_set_border_damage", displayName = "Set Border Damage", category = NodeDefinition.NodeCategory.WORLD,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "world", dataType = FlowType.ANY),
                    @FlowPin(name = "damage_amount", dataType = FlowType.NUMBER)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void worldSetBorderDamage(FlowContext ctx, FlowNode node) {
        executeLegacy("world_set_border_damage", ctx, node);
    }

    @DefineNode(id = "world_set_border_warning", displayName = "Set Border Warning", category = NodeDefinition.NodeCategory.WORLD,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "world", dataType = FlowType.ANY),
                    @FlowPin(name = "warning_distance", dataType = FlowType.NUMBER)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void worldSetBorderWarning(FlowContext ctx, FlowNode node) {
        executeLegacy("world_set_border_warning", ctx, node);
    }

    @DefineNode(id = "world_management_create_world", displayName = "Create World", category = NodeDefinition.NodeCategory.WORLD,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "world_name", dataType = FlowType.STRING),
                    @FlowPin(name = "seed", dataType = FlowType.STRING),
                    @FlowPin(name = "environment", dataType = FlowType.STRING),
                    @FlowPin(name = "generator", dataType = FlowType.STRING)
            },
            outputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "success", dataType = FlowType.BOOLEAN),
                    @FlowPin(name = "message", dataType = FlowType.STRING)
            })
    public void worldManagementCreateWorld(FlowContext ctx, FlowNode node) {
        executeLegacy("world_management_create_world", ctx, node);
    }

    @DefineNode(id = "world_management_scan_worlds", displayName = "Scan Worlds", category = NodeDefinition.NodeCategory.WORLD,
            inputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)},
            outputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "success", dataType = FlowType.BOOLEAN),
                    @FlowPin(name = "message", dataType = FlowType.STRING),
                    @FlowPin(name = "count", dataType = FlowType.NUMBER)
            })
    public void worldManagementScanWorlds(FlowContext ctx, FlowNode node) {
        executeLegacy("world_management_scan_worlds", ctx, node);
    }

    @DefineNode(id = "world_management_import_worlds", displayName = "Import Worlds", category = NodeDefinition.NodeCategory.WORLD,
            inputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)},
            outputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "success", dataType = FlowType.BOOLEAN),
                    @FlowPin(name = "message", dataType = FlowType.STRING),
                    @FlowPin(name = "count", dataType = FlowType.NUMBER)
            })
    public void worldManagementImportWorlds(FlowContext ctx, FlowNode node) {
        executeLegacy("world_management_import_worlds", ctx, node);
    }

    @DefineNode(id = "world_management_clone_world", displayName = "Clone World", category = NodeDefinition.NodeCategory.WORLD,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "source_world", dataType = FlowType.STRING),
                    @FlowPin(name = "target_world", dataType = FlowType.STRING),
                    @FlowPin(name = "load_after_clone", dataType = FlowType.BOOLEAN)
            },
            outputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "success", dataType = FlowType.BOOLEAN),
                    @FlowPin(name = "message", dataType = FlowType.STRING)
            })
    public void worldManagementCloneWorld(FlowContext ctx, FlowNode node) {
        executeLegacy("world_management_clone_world", ctx, node);
    }

    @DefineNode(id = "world_management_load_world", displayName = "Load World", category = NodeDefinition.NodeCategory.WORLD,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "world_name", dataType = FlowType.STRING)
            },
            outputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "success", dataType = FlowType.BOOLEAN),
                    @FlowPin(name = "message", dataType = FlowType.STRING)
            })
    public void worldManagementLoadWorld(FlowContext ctx, FlowNode node) {
        executeLegacy("world_management_load_world", ctx, node);
    }

    @DefineNode(id = "world_management_unload_world", displayName = "Unload World", category = NodeDefinition.NodeCategory.WORLD,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "world_name", dataType = FlowType.STRING),
                    @FlowPin(name = "fallback_world", dataType = FlowType.STRING)
            },
            outputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "success", dataType = FlowType.BOOLEAN),
                    @FlowPin(name = "message", dataType = FlowType.STRING)
            })
    public void worldManagementUnloadWorld(FlowContext ctx, FlowNode node) {
        executeLegacy("world_management_unload_world", ctx, node);
    }

    @DefineNode(id = "world_management_delete_world", displayName = "Delete World", category = NodeDefinition.NodeCategory.WORLD,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "world_name", dataType = FlowType.STRING),
                    @FlowPin(name = "delete_files", dataType = FlowType.BOOLEAN),
                    @FlowPin(name = "fallback_world", dataType = FlowType.STRING)
            },
            outputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "success", dataType = FlowType.BOOLEAN),
                    @FlowPin(name = "message", dataType = FlowType.STRING)
            })
    public void worldManagementDeleteWorld(FlowContext ctx, FlowNode node) {
        executeLegacy("world_management_delete_world", ctx, node);
    }

    @DefineNode(id = "world_management_set_rule", displayName = "Set World Rule", category = NodeDefinition.NodeCategory.WORLD,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "world_name", dataType = FlowType.STRING),
                    @FlowPin(name = "rule_name", dataType = FlowType.STRING),
                    @FlowPin(name = "value", dataType = FlowType.STRING)
            },
            outputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "success", dataType = FlowType.BOOLEAN),
                    @FlowPin(name = "message", dataType = FlowType.STRING)
            })
    public void worldManagementSetRule(FlowContext ctx, FlowNode node) {
        executeLegacy("world_management_set_rule", ctx, node);
    }

    @DefineNode(id = "world_management_set_difficulty", displayName = "Set World Difficulty", category = NodeDefinition.NodeCategory.WORLD,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "world_name", dataType = FlowType.STRING),
                    @FlowPin(name = "difficulty", dataType = FlowType.STRING)
            },
            outputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "success", dataType = FlowType.BOOLEAN),
                    @FlowPin(name = "message", dataType = FlowType.STRING)
            })
    public void worldManagementSetDifficulty(FlowContext ctx, FlowNode node) {
        executeLegacy("world_management_set_difficulty", ctx, node);
    }

    @DefineNode(id = "world_management_set_time_lock", displayName = "Set Time Lock", category = NodeDefinition.NodeCategory.WORLD,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "world_name", dataType = FlowType.STRING),
                    @FlowPin(name = "enabled", dataType = FlowType.BOOLEAN),
                    @FlowPin(name = "locked_time", dataType = FlowType.NUMBER)
            },
            outputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "success", dataType = FlowType.BOOLEAN),
                    @FlowPin(name = "message", dataType = FlowType.STRING)
            })
    public void worldManagementSetTimeLock(FlowContext ctx, FlowNode node) {
        executeLegacy("world_management_set_time_lock", ctx, node);
    }

    @DefineNode(id = "world_management_set_weather_lock", displayName = "Set Weather Lock", category = NodeDefinition.NodeCategory.WORLD,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "world_name", dataType = FlowType.STRING),
                    @FlowPin(name = "enabled", dataType = FlowType.BOOLEAN),
                    @FlowPin(name = "storm", dataType = FlowType.BOOLEAN),
                    @FlowPin(name = "thundering", dataType = FlowType.BOOLEAN)
            },
            outputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "success", dataType = FlowType.BOOLEAN),
                    @FlowPin(name = "message", dataType = FlowType.STRING)
            })
    public void worldManagementSetWeatherLock(FlowContext ctx, FlowNode node) {
        executeLegacy("world_management_set_weather_lock", ctx, node);
    }

    @DefineNode(id = "world_management_set_isolated_state", displayName = "Set Isolated State", category = NodeDefinition.NodeCategory.WORLD,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "world_name", dataType = FlowType.STRING),
                    @FlowPin(name = "enabled", dataType = FlowType.BOOLEAN)
            },
            outputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "success", dataType = FlowType.BOOLEAN),
                    @FlowPin(name = "message", dataType = FlowType.STRING)
            })
    public void worldManagementSetIsolatedState(FlowContext ctx, FlowNode node) {
        executeLegacy("world_management_set_isolated_state", ctx, node);
    }

    @DefineNode(id = "world_management_create_portal", displayName = "Create Portal", category = NodeDefinition.NodeCategory.WORLD,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "portal_name", dataType = FlowType.STRING),
                    @FlowPin(name = "source_world", dataType = FlowType.STRING),
                    @FlowPin(name = "min_x", dataType = FlowType.NUMBER),
                    @FlowPin(name = "min_y", dataType = FlowType.NUMBER),
                    @FlowPin(name = "min_z", dataType = FlowType.NUMBER),
                    @FlowPin(name = "max_x", dataType = FlowType.NUMBER),
                    @FlowPin(name = "max_y", dataType = FlowType.NUMBER),
                    @FlowPin(name = "max_z", dataType = FlowType.NUMBER),
                    @FlowPin(name = "destination_world", dataType = FlowType.STRING),
                    @FlowPin(name = "destination_x", dataType = FlowType.NUMBER),
                    @FlowPin(name = "destination_y", dataType = FlowType.NUMBER),
                    @FlowPin(name = "destination_z", dataType = FlowType.NUMBER),
                    @FlowPin(name = "destination_yaw", dataType = FlowType.NUMBER),
                    @FlowPin(name = "destination_pitch", dataType = FlowType.NUMBER),
                    @FlowPin(name = "enabled", dataType = FlowType.BOOLEAN)
            },
            outputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "success", dataType = FlowType.BOOLEAN),
                    @FlowPin(name = "message", dataType = FlowType.STRING),
                    @FlowPin(name = "portal_id", dataType = FlowType.STRING)
            })
    public void worldManagementCreatePortal(FlowContext ctx, FlowNode node) {
        executeLegacy("world_management_create_portal", ctx, node);
    }

    @DefineNode(id = "world_management_delete_portal", displayName = "Delete Portal", category = NodeDefinition.NodeCategory.WORLD,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "portal_id", dataType = FlowType.STRING)
            },
            outputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "success", dataType = FlowType.BOOLEAN),
                    @FlowPin(name = "message", dataType = FlowType.STRING)
            })
    public void worldManagementDeletePortal(FlowContext ctx, FlowNode node) {
        executeLegacy("world_management_delete_portal", ctx, node);
    }

    @DefineNode(id = "world_management_set_portal_enabled", displayName = "Set Portal Enabled", category = NodeDefinition.NodeCategory.WORLD,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "portal_id", dataType = FlowType.STRING),
                    @FlowPin(name = "enabled", dataType = FlowType.BOOLEAN)
            },
            outputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "success", dataType = FlowType.BOOLEAN),
                    @FlowPin(name = "message", dataType = FlowType.STRING),
                    @FlowPin(name = "portal_id", dataType = FlowType.STRING)
            })
    public void worldManagementSetPortalEnabled(FlowContext ctx, FlowNode node) {
        executeLegacy("world_management_set_portal_enabled", ctx, node);
    }

    @DefineNode(id = "world_management_set_portal_destination", displayName = "Set Portal Destination", category = NodeDefinition.NodeCategory.WORLD,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "portal_id", dataType = FlowType.STRING),
                    @FlowPin(name = "destination_world", dataType = FlowType.STRING),
                    @FlowPin(name = "destination_x", dataType = FlowType.NUMBER),
                    @FlowPin(name = "destination_y", dataType = FlowType.NUMBER),
                    @FlowPin(name = "destination_z", dataType = FlowType.NUMBER),
                    @FlowPin(name = "destination_yaw", dataType = FlowType.NUMBER),
                    @FlowPin(name = "destination_pitch", dataType = FlowType.NUMBER)
            },
            outputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "success", dataType = FlowType.BOOLEAN),
                    @FlowPin(name = "message", dataType = FlowType.STRING),
                    @FlowPin(name = "portal_id", dataType = FlowType.STRING)
            })
    public void worldManagementSetPortalDestination(FlowContext ctx, FlowNode node) {
        executeLegacy("world_management_set_portal_destination", ctx, node);
    }

    @DefineNode(id = "world_management_set_portal_bounds", displayName = "Set Portal Bounds", category = NodeDefinition.NodeCategory.WORLD,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "portal_id", dataType = FlowType.STRING),
                    @FlowPin(name = "source_world", dataType = FlowType.STRING),
                    @FlowPin(name = "min_x", dataType = FlowType.NUMBER),
                    @FlowPin(name = "min_y", dataType = FlowType.NUMBER),
                    @FlowPin(name = "min_z", dataType = FlowType.NUMBER),
                    @FlowPin(name = "max_x", dataType = FlowType.NUMBER),
                    @FlowPin(name = "max_y", dataType = FlowType.NUMBER),
                    @FlowPin(name = "max_z", dataType = FlowType.NUMBER)
            },
            outputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "success", dataType = FlowType.BOOLEAN),
                    @FlowPin(name = "message", dataType = FlowType.STRING),
                    @FlowPin(name = "portal_id", dataType = FlowType.STRING)
            })
    public void worldManagementSetPortalBounds(FlowContext ctx, FlowNode node) {
        executeLegacy("world_management_set_portal_bounds", ctx, node);
    }

    @DefineNode(id = "world_management_teleport_player_to_world", displayName = "Teleport Player To World", category = NodeDefinition.NodeCategory.WORLD,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "target", dataType = FlowType.PLAYER),
                    @FlowPin(name = "world_name", dataType = FlowType.STRING),
                    @FlowPin(name = "x", dataType = FlowType.NUMBER),
                    @FlowPin(name = "y", dataType = FlowType.NUMBER),
                    @FlowPin(name = "z", dataType = FlowType.NUMBER),
                    @FlowPin(name = "yaw", dataType = FlowType.NUMBER),
                    @FlowPin(name = "pitch", dataType = FlowType.NUMBER)
            },
            outputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "success", dataType = FlowType.BOOLEAN),
                    @FlowPin(name = "message", dataType = FlowType.STRING)
            })
    public void worldManagementTeleportPlayerToWorld(FlowContext ctx, FlowNode node) {
        executeLegacy("world_management_teleport_player_to_world", ctx, node);
    }

    @DefineNode(id = "world_management_teleport_player_to_world_spawn", displayName = "Teleport Player To World Spawn", category = NodeDefinition.NodeCategory.WORLD,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "target", dataType = FlowType.PLAYER),
                    @FlowPin(name = "world_name", dataType = FlowType.STRING)
            },
            outputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "success", dataType = FlowType.BOOLEAN),
                    @FlowPin(name = "message", dataType = FlowType.STRING)
            })
    public void worldManagementTeleportPlayerToWorldSpawn(FlowContext ctx, FlowNode node) {
        executeLegacy("world_management_teleport_player_to_world_spawn", ctx, node);
    }

    @DefineNode(id = "world_management_teleport_player_to_portal", displayName = "Teleport Player To Portal", category = NodeDefinition.NodeCategory.WORLD,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "target", dataType = FlowType.PLAYER),
                    @FlowPin(name = "portal_id", dataType = FlowType.STRING)
            },
            outputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "success", dataType = FlowType.BOOLEAN),
                    @FlowPin(name = "message", dataType = FlowType.STRING)
            })
    public void worldManagementTeleportPlayerToPortal(FlowContext ctx, FlowNode node) {
        executeLegacy("world_management_teleport_player_to_portal", ctx, node);
    }

    @DefineNode(id = "world_properties", displayName = "World Properties", category = NodeDefinition.NodeCategory.WORLD,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "world", dataType = FlowType.ANY),
                    @FlowPin(name = "mode", dataType = FlowType.STRING),
                    @FlowPin(name = "property", dataType = FlowType.STRING),
                    @FlowPin(name = "value", dataType = FlowType.ANY)
            },
            outputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "success", dataType = FlowType.BOOLEAN),
                    @FlowPin(name = "result", dataType = FlowType.ANY)
            })
    public void worldProperties(FlowContext ctx, FlowNode node) {
        World world = ctx.getInputValue(node, "world", World.class, null);
        String mode = ctx.getInputValue(node, "mode", String.class, "");
        String property = ctx.getInputValue(node, "property", String.class, "");
        Object value = ctx.getInputValue(node, "value", Object.class, null);
        boolean success = false;
        Object result = null;
        String nodeId = findNodeId(ctx, node);

        if (world != null && mode != null && property != null) {
            switch (mode.toLowerCase()) {
                case "set_gamerule" -> {
                    try {
                        org.bukkit.GameRule rule = org.bukkit.GameRule.getByName(property);
                        if (rule != null) {
                            Object parsed = parseGameRuleValue(rule, value);
                            if (parsed != null) {
                                world.setGameRule(rule, parsed);
                                success = true;
                            }
                        }
                    } catch (Exception ignored) {
                    }
                }
                case "get_gamerule" -> {
                    try {
                        org.bukkit.GameRule rule = org.bukkit.GameRule.getByName(property);
                        if (rule != null) {
                            result = world.getGameRuleValue(rule);
                            success = true;
                        }
                    } catch (Exception ignored) {
                    }
                }
                case "set_biome" -> {
                    if (value instanceof String biomeName) {
                        try {
                            org.bukkit.block.Biome biome = org.bukkit.block.Biome.valueOf(biomeName.toUpperCase());
                            org.bukkit.Location loc = parseLocationInput(world, value);
                            if (loc != null) {
                                world.setBiome(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(), biome);
                                success = true;
                            }
                        } catch (IllegalArgumentException ignored) {
                        }
                    }
                }
                case "get_biome" -> {
                    org.bukkit.Location loc = parseLocationInput(world, value);
                    if (loc != null) {
                        result = world.getBiome(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ()).name();
                        success = true;
                    }
                }
                case "get_spawn_location" -> {
                    result = world.getSpawnLocation();
                    success = true;
                }
                case "get_time" -> {
                    result = world.getTime();
                    success = true;
                }
                case "get_weather" -> {
                    if (world.isThundering()) {
                        result = "thunder";
                    } else if (world.hasStorm()) {
                        result = "rain";
                    } else {
                        result = "clear";
                    }
                    success = true;
                }
            }
        }

        ctx.setNodeOutput(nodeId, "success", success);
        ctx.setNodeOutput(nodeId, "result", result);
        ctx.triggerOutput("flow");
    }

    private static org.bukkit.Location parseLocationInput(World world, Object value) {
        if (value instanceof org.bukkit.Location loc) {
            return loc;
        }
        if (value instanceof org.bukkit.block.Block block) {
            return block.getLocation();
        }
        return null;
    }

    private static Object parseGameRuleValue(org.bukkit.GameRule rule, Object value) {
        if (value == null || rule == null) {
            return null;
        }
        if (rule.getType() == Boolean.class) {
            return Boolean.valueOf(String.valueOf(value));
        }
        if (rule.getType() == Integer.class) {
            return Integer.valueOf(String.valueOf(value));
        }
        return String.valueOf(value);
    }

    private static WorldManagementService getWorldManagementService() {
        ReSync plugin = ReSync.getInstance();
        if (plugin == null || plugin.getReSyncServer() == null) {
            return null;
        }
        return plugin.getReSyncServer().getWorldManagementService();
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
