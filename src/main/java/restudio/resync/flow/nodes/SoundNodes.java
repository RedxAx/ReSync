package restudio.resync.flow.nodes;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import restudio.flow.data.FlowNode;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.FlowRegistry;
import restudio.resync.flow.NodeCategory;

public class SoundNodes implements NodeCategory {

    @Override
    public void registerNodes(FlowRegistry registry) {
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
                    Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), () -> player.stopAllSounds());
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
                    Bukkit.getScheduler().runTaskTimer(restudio.resync.ReSync.getInstance(), (task) -> {
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
                        private int currentStep = 0;

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

    private static String findNodeId(FlowContext ctx, FlowNode node) {
        for (var entry : ctx.getRuntime().getGraph().getNodes().entrySet()) {
            if (entry.getValue() == node) {
                return entry.getKey();
            }
        }
        return null;
    }
}
