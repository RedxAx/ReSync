package restudio.resync.flow.nodes;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import restudio.flow.data.FlowNode;
import restudio.flow.data.FlowType;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.FlowRegistry;
import restudio.resync.flow.registry.DefineNode;
import restudio.resync.flow.registry.FlowPin;
import restudio.resync.flow.registry.NodeDefinition;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

public class SoundNodes {

    private static final Map<String, BiConsumer<FlowContext, FlowNode>> LEGACY_EXECUTORS = new ConcurrentHashMap<>();
    private static volatile boolean initialized;

    private static void registerLegacyNodes(FlowRegistry registry) {
        registry.register("sound_play", (ctx, node) -> {
            Location location = ctx.getInputValue(node, "location", Location.class, null);
            String soundName = ctx.getInputValue(node, "sound_type", String.class, "BLOCK_AMETHYST_BLOCK_CHIME");
            Float volume = ctx.getInputValue(node, "volume", Float.class, 1.0f);
            Float pitch = ctx.getInputValue(node, "pitch", Float.class, 1.0f);
            if (location != null && location.getWorld() != null) {
                try {
                    Sound sound = Sound.valueOf(soundName.toUpperCase().replace('.', '_'));
                    if (Bukkit.isPrimaryThread()) {
                        location.getWorld().playSound(location, sound, volume, pitch);
                    } else {
                        Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), () -> location.getWorld().playSound(location, sound, volume, pitch));
                    }
                } catch (IllegalArgumentException ignored) {
                }
            }
            ctx.triggerOutput("flow");
        });

        registry.register("sound_play_for_player", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            String soundName = ctx.getInputValue(node, "sound_type", String.class, "BLOCK_AMETHYST_BLOCK_CHIME");
            Float volume = ctx.getInputValue(node, "volume", Float.class, 1.0f);
            Float pitch = ctx.getInputValue(node, "pitch", Float.class, 1.0f);
            if (player != null && player.getWorld() != null) {
                try {
                    Sound sound = Sound.valueOf(soundName.toUpperCase().replace('.', '_'));
                    Location location = player.getLocation();
                    if (Bukkit.isPrimaryThread()) {
                        player.playSound(location, sound, volume, pitch);
                    } else {
                        Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), () -> player.playSound(location, sound, volume, pitch));
                    }
                } catch (IllegalArgumentException ignored) {
                }
            }
            ctx.triggerOutput("flow");
        });

        registry.register("sound_play_for_all", (ctx, node) -> {
            String soundName = ctx.getInputValue(node, "sound_type", String.class, "BLOCK_AMETHYST_BLOCK_CHIME");
            Float volume = ctx.getInputValue(node, "volume", Float.class, 1.0f);
            Float pitch = ctx.getInputValue(node, "pitch", Float.class, 1.0f);
            try {
                Sound sound = Sound.valueOf(soundName.toUpperCase().replace('.', '_'));
                for (Player player : Bukkit.getOnlinePlayers()) {
                    if (player.getWorld() != null) {
                        Location location = player.getLocation();
                        if (Bukkit.isPrimaryThread()) {
                            player.playSound(location, sound, volume, pitch);
                        } else {
                            Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), () -> player.playSound(location, sound, volume, pitch));
                        }
                    }
                }
            } catch (IllegalArgumentException ignored) {
            }
            ctx.triggerOutput("flow");
        });

        registry.register("sound_stop", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            String soundName = ctx.getInputValue(node, "sound", String.class, "");
            if (player != null && !soundName.isEmpty()) {
                try {
                    Sound sound = Sound.valueOf(soundName.toUpperCase().replace('.', '_'));
                    if (Bukkit.isPrimaryThread()) {
                        player.stopSound(sound);
                    } else {
                        Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), () -> player.stopSound(sound));
                    }
                } catch (IllegalArgumentException ignored) {
                }
            }
            ctx.triggerOutput("flow");
        });

        registry.register("sound_stop_all", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            if (player != null) {
                if (Bukkit.isPrimaryThread()) {
                    player.stopAllSounds();
                } else {
                    Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), player::stopAllSounds);
                }
            }
            ctx.triggerOutput("flow");
        });

        registry.register("sound_play_category", (ctx, node) -> {
            Location location = ctx.getInputValue(node, "location", Location.class, null);
            String soundName = ctx.getInputValue(node, "sound_name", String.class, "BLOCK_AMETHYST_BLOCK_CHIME");
            String categoryName = ctx.getInputValue(node, "category", String.class, "MASTER");
            Float volume = ctx.getInputValue(node, "volume", Float.class, 1.0f);
            Float pitch = ctx.getInputValue(node, "pitch", Float.class, 1.0f);
            if (location != null && location.getWorld() != null) {
                try {
                    Sound sound = Sound.valueOf(soundName.toUpperCase().replace('.', '_'));
                    SoundCategory category = SoundCategory.valueOf(categoryName.toUpperCase());
                    if (Bukkit.isPrimaryThread()) {
                        location.getWorld().playSound(location, sound, category, volume, pitch);
                    } else {
                        Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), () -> location.getWorld().playSound(location, sound, category, volume, pitch));
                    }
                } catch (IllegalArgumentException ignored) {
                }
            }
            ctx.triggerOutput("flow");
        });

        registry.register("sound_stop_for_player", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            String soundName = ctx.getInputValue(node, "sound_name", String.class, "");
            if (player != null && !soundName.isEmpty()) {
                try {
                    Sound sound = Sound.valueOf(soundName.toUpperCase().replace('.', '_'));
                    if (Bukkit.isPrimaryThread()) {
                        player.stopSound(sound);
                    } else {
                        Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), () -> player.stopSound(sound));
                    }
                } catch (IllegalArgumentException ignored) {
                }
            }
            ctx.triggerOutput("flow");
        });

        registry.register("sound_stop_category", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            String categoryName = ctx.getInputValue(node, "category", String.class, "MASTER");
            if (player != null) {
                try {
                    SoundCategory category = SoundCategory.valueOf(categoryName.toUpperCase());
                    if (Bukkit.isPrimaryThread()) {
                        player.stopSound(category);
                    } else {
                        Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), () -> player.stopSound(category));
                    }
                } catch (IllegalArgumentException ignored) {
                }
            }
            ctx.triggerOutput("flow");
        });

        registry.register("sound_play_with_distance", (ctx, node) -> {
            Location location = ctx.getInputValue(node, "location", Location.class, null);
            String soundName = ctx.getInputValue(node, "sound_name", String.class, "BLOCK_AMETHYST_BLOCK_CHIME");
            Float volume = ctx.getInputValue(node, "volume", Float.class, 1.0f);
            Float pitch = ctx.getInputValue(node, "pitch", Float.class, 1.0f);
            Float minDistance = ctx.getInputValue(node, "min_distance", Float.class, 0.0f);
            Float maxDistance = ctx.getInputValue(node, "max_distance", Float.class, 16.0f);
            if (location != null && location.getWorld() != null) {
                try {
                    Sound sound = Sound.valueOf(soundName.toUpperCase().replace('.', '_'));
                    if (Bukkit.isPrimaryThread()) {
                        location.getWorld().playSound(location, sound, volume, pitch);
                    } else {
                        Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), () -> location.getWorld().playSound(location, sound, volume, pitch));
                    }
                } catch (IllegalArgumentException ignored) {
                }
            }
            ctx.triggerOutput("flow");
        });

        registry.register("sound_loop_for_player", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            Location location = ctx.getInputValue(node, "location", Location.class, null);
            String soundName = ctx.getInputValue(node, "sound_name", String.class, "BLOCK_AMETHYST_BLOCK_CHIME");
            Float volume = ctx.getInputValue(node, "volume", Float.class, 1.0f);
            Float pitch = ctx.getInputValue(node, "pitch", Float.class, 1.0f);
            Integer durationTicks = ctx.getInputValue(node, "duration_ticks", Integer.class, 60);
            if (player != null && location != null && location.getWorld() != null) {
                try {
                    Sound sound = Sound.valueOf(soundName.toUpperCase().replace('.', '_'));
                    int interval = Math.max(durationTicks / 10, 1);
                    Bukkit.getScheduler().runTaskTimer(restudio.resync.ReSync.getInstance(), task -> {
                        if (player.isOnline()) {
                            player.playSound(location, sound, volume, pitch);
                        } else {
                            task.cancel();
                        }
                    }, 0L, interval);
                } catch (IllegalArgumentException ignored) {
                }
            }
            ctx.triggerOutput("flow");
        });

        registry.register("sound_fade", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            String soundName = ctx.getInputValue(node, "sound_name", String.class, "");
            Float startVolume = ctx.getInputValue(node, "start_volume", Float.class, 1.0f);
            Float endVolume = ctx.getInputValue(node, "end_volume", Float.class, 0.0f);
            Integer durationTicks = ctx.getInputValue(node, "duration_ticks", Integer.class, 20);
            if (player != null && !soundName.isEmpty()) {
                try {
                    Sound sound = Sound.valueOf(soundName.toUpperCase().replace('.', '_'));
                    int steps = Math.min(durationTicks, 20);
                    long delayPerStep = Math.max(durationTicks / steps, 1);
                    float volumeStep = (endVolume - startVolume) / steps;
                    Location location = player.getLocation();
                    Bukkit.getScheduler().runTaskTimer(restudio.resync.ReSync.getInstance(), new Runnable() {
                        private int currentStep;
                        @Override
                        public void run() {
                            if (currentStep >= steps || !player.isOnline()) {
                                ctx.triggerOutput("flow");
                                return;
                            }
                            float volume = startVolume + (volumeStep * currentStep);
                            player.playSound(location, sound, Math.max(0, Math.min(1, volume)), 1.0f);
                            currentStep++;
                        }
                    }, 0L, delayPerStep);
                } catch (IllegalArgumentException ignored) {
                    ctx.triggerOutput("flow");
                }
            } else {
                ctx.triggerOutput("flow");
            }
        });

        registry.register("sound_play_sequence", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            Location location = ctx.getInputValue(node, "location", Location.class, null);
            Object soundsListObj = ctx.getInputValue(node, "sounds_list", Object.class, null);
            Object delaysListObj = ctx.getInputValue(node, "delays_list", Object.class, null);
            if (player != null && location != null && soundsListObj instanceof java.util.List<?> soundsList) {
                java.util.List<?> delaysList = (delaysListObj instanceof java.util.List<?> dl) ? dl : null;
                long totalDelay = 0;
                for (int i = 0; i < soundsList.size(); i++) {
                    final int index = i;
                    final Long delay = (delaysList != null && index < delaysList.size()) ? ((Number) delaysList.get(index)).longValue() : 20L;
                    final String soundName = soundsList.get(index).toString();
                    totalDelay += delay;
                    Bukkit.getScheduler().runTaskLater(restudio.resync.ReSync.getInstance(), () -> {
                        if (player.isOnline()) {
                            try {
                                Sound sound = Sound.valueOf(soundName.toUpperCase().replace('.', '_'));
                                player.playSound(location, sound, 1.0f, 1.0f);
                            } catch (IllegalArgumentException ignored) {
                            }
                        }
                        if (index == soundsList.size() - 1) {
                            ctx.triggerOutput("flow");
                        }
                    }, totalDelay);
                }
            } else {
                ctx.triggerOutput("flow");
            }
        });
    }

    public void registerNodes(FlowRegistry registry) {
        registerLegacyNodes(registry);
    }

    private static void ensureLegacyInitialized() {
        if (initialized) {
            return;
        }
        synchronized (SoundNodes.class) {
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

    @DefineNode(id = "sound_play", displayName = "Play Sound", category = NodeDefinition.NodeCategory.VISUAL,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "location", dataType = FlowType.LOCATION),
                    @FlowPin(name = "sound_type", dataType = FlowType.STRING),
                    @FlowPin(name = "volume", dataType = FlowType.NUMBER),
                    @FlowPin(name = "pitch", dataType = FlowType.NUMBER)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION)})
    public void soundPlay(FlowContext ctx, FlowNode node) { executeLegacy("sound_play", ctx, node); }

    @DefineNode(id = "sound_play_for_player", displayName = "Play Sound for Player", category = NodeDefinition.NodeCategory.VISUAL,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "player", dataType = FlowType.PLAYER),
                    @FlowPin(name = "sound_type", dataType = FlowType.STRING),
                    @FlowPin(name = "volume", dataType = FlowType.NUMBER),
                    @FlowPin(name = "pitch", dataType = FlowType.NUMBER)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION)})
    public void soundPlayForPlayer(FlowContext ctx, FlowNode node) { executeLegacy("sound_play_for_player", ctx, node); }

    @DefineNode(id = "sound_play_for_all", displayName = "Play Sound for All", category = NodeDefinition.NodeCategory.VISUAL,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "sound_type", dataType = FlowType.STRING),
                    @FlowPin(name = "volume", dataType = FlowType.NUMBER),
                    @FlowPin(name = "pitch", dataType = FlowType.NUMBER)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION)})
    public void soundPlayForAll(FlowContext ctx, FlowNode node) { executeLegacy("sound_play_for_all", ctx, node); }

    @DefineNode(id = "sound_stop", displayName = "Stop Sound", category = NodeDefinition.NodeCategory.VISUAL,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "player", dataType = FlowType.PLAYER),
                    @FlowPin(name = "sound", dataType = FlowType.STRING)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION)})
    public void soundStop(FlowContext ctx, FlowNode node) { executeLegacy("sound_stop", ctx, node); }

    @DefineNode(id = "sound_stop_all", displayName = "Stop All Sounds", category = NodeDefinition.NodeCategory.VISUAL,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "player", dataType = FlowType.PLAYER)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION)})
    public void soundStopAll(FlowContext ctx, FlowNode node) { executeLegacy("sound_stop_all", ctx, node); }

    @DefineNode(id = "sound_play_category", displayName = "Sound Play Category", category = NodeDefinition.NodeCategory.VISUAL,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "location", dataType = FlowType.LOCATION),
                    @FlowPin(name = "sound_name", dataType = FlowType.STRING),
                    @FlowPin(name = "category", dataType = FlowType.STRING),
                    @FlowPin(name = "volume", dataType = FlowType.NUMBER),
                    @FlowPin(name = "pitch", dataType = FlowType.NUMBER)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION)})
    public void soundPlayCategory(FlowContext ctx, FlowNode node) { executeLegacy("sound_play_category", ctx, node); }

    @DefineNode(id = "sound_stop_for_player", displayName = "Sound Stop For Player", category = NodeDefinition.NodeCategory.VISUAL,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "player", dataType = FlowType.PLAYER),
                    @FlowPin(name = "sound_name", dataType = FlowType.STRING)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION)})
    public void soundStopForPlayer(FlowContext ctx, FlowNode node) { executeLegacy("sound_stop_for_player", ctx, node); }

    @DefineNode(id = "sound_stop_category", displayName = "Sound Stop Category", category = NodeDefinition.NodeCategory.VISUAL,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "player", dataType = FlowType.PLAYER),
                    @FlowPin(name = "category", dataType = FlowType.STRING)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION)})
    public void soundStopCategory(FlowContext ctx, FlowNode node) { executeLegacy("sound_stop_category", ctx, node); }

    @DefineNode(id = "sound_play_with_distance", displayName = "Sound Play With Distance", category = NodeDefinition.NodeCategory.VISUAL,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "location", dataType = FlowType.LOCATION),
                    @FlowPin(name = "sound_name", dataType = FlowType.STRING),
                    @FlowPin(name = "volume", dataType = FlowType.NUMBER),
                    @FlowPin(name = "pitch", dataType = FlowType.NUMBER),
                    @FlowPin(name = "min_distance", dataType = FlowType.NUMBER),
                    @FlowPin(name = "max_distance", dataType = FlowType.NUMBER)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION)})
    public void soundPlayWithDistance(FlowContext ctx, FlowNode node) { executeLegacy("sound_play_with_distance", ctx, node); }

    @DefineNode(id = "sound_loop_for_player", displayName = "Sound Loop For Player", category = NodeDefinition.NodeCategory.VISUAL,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "player", dataType = FlowType.PLAYER),
                    @FlowPin(name = "location", dataType = FlowType.LOCATION),
                    @FlowPin(name = "sound_name", dataType = FlowType.STRING),
                    @FlowPin(name = "volume", dataType = FlowType.NUMBER),
                    @FlowPin(name = "pitch", dataType = FlowType.NUMBER),
                    @FlowPin(name = "duration_ticks", dataType = FlowType.NUMBER)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION)})
    public void soundLoopForPlayer(FlowContext ctx, FlowNode node) { executeLegacy("sound_loop_for_player", ctx, node); }

    @DefineNode(id = "sound_fade", displayName = "Sound Fade", category = NodeDefinition.NodeCategory.VISUAL,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "player", dataType = FlowType.PLAYER),
                    @FlowPin(name = "sound_name", dataType = FlowType.STRING),
                    @FlowPin(name = "start_volume", dataType = FlowType.NUMBER),
                    @FlowPin(name = "end_volume", dataType = FlowType.NUMBER),
                    @FlowPin(name = "duration_ticks", dataType = FlowType.NUMBER)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION)})
    public void soundFade(FlowContext ctx, FlowNode node) { executeLegacy("sound_fade", ctx, node); }

    @DefineNode(id = "sound_play_sequence", displayName = "Sound Play Sequence", category = NodeDefinition.NodeCategory.VISUAL,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
                    @FlowPin(name = "player", dataType = FlowType.PLAYER),
                    @FlowPin(name = "location", dataType = FlowType.LOCATION),
                    @FlowPin(name = "sounds_list", dataType = FlowType.LIST),
                    @FlowPin(name = "delays_list", dataType = FlowType.LIST)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION)})
    public void soundPlaySequence(FlowContext ctx, FlowNode node) { executeLegacy("sound_play_sequence", ctx, node); }

    private static String findNodeId(FlowContext ctx, FlowNode node) {
        for (var entry : ctx.getRuntime().getGraph().getNodes().entrySet()) {
            if (entry.getValue() == node) {
                return entry.getKey();
            }
        }
        return null;
    }
}
