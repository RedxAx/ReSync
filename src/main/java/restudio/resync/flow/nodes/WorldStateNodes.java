package restudio.resync.flow.nodes;

import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import restudio.flow.data.FlowNode;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.FlowRegistry;
import restudio.resync.flow.NodeCategory;

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
