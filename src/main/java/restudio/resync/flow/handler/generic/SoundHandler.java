package restudio.resync.flow.handler.generic;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import restudio.flow.data.FlowNode;
import restudio.resync.ReSync;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.NodeHandler;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

public class SoundHandler implements NodeHandler {
    private final ConcurrentHashMap<String, BiConsumer<FlowContext, FlowNode>> operations = new ConcurrentHashMap<>();

    public SoundHandler() {
        operations.put("sound_play", (ctx, node) -> {
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
                        Bukkit.getScheduler().runTask(ReSync.getInstance(), () -> location.getWorld().playSound(location, sound, volume, pitch));
                    }
                } catch (IllegalArgumentException ignored) {
                }
            }
        });

        operations.put("sound_stop", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            String soundName = ctx.getInputValue(node, "sound", String.class, "");
            if (player != null && !soundName.isEmpty()) {
                try {
                    Sound sound = Sound.valueOf(soundName.toUpperCase().replace('.', '_'));
                    if (Bukkit.isPrimaryThread()) {
                        player.stopSound(sound);
                    } else {
                        Bukkit.getScheduler().runTask(ReSync.getInstance(), () -> player.stopSound(sound));
                    }
                } catch (IllegalArgumentException ignored) {
                }
            }
        });

        operations.put("sound_play_ambient", (ctx, node) -> {
            Location location = ctx.getInputValue(node, "location", Location.class, null);
            String soundName = ctx.getInputValue(node, "sound_type", String.class, "BLOCK_AMETHYST_BLOCK_CHIME");
            Float volume = ctx.getInputValue(node, "volume", Float.class, 1.0f);
            Float pitch = ctx.getInputValue(node, "pitch", Float.class, 1.0f);
            if (location != null && location.getWorld() != null) {
                try {
                    Sound sound = Sound.valueOf(soundName.toUpperCase().replace('.', '_'));
                    if (Bukkit.isPrimaryThread()) {
                        location.getWorld().playSound(location, sound, SoundCategory.AMBIENT, volume, pitch);
                    } else {
                        Bukkit.getScheduler().runTask(ReSync.getInstance(), () -> location.getWorld().playSound(location, sound, SoundCategory.AMBIENT, volume, pitch));
                    }
                } catch (IllegalArgumentException ignored) {
                }
            }
        });

        operations.put("fade", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            String sound = ctx.getInputValue(node, "sound", String.class, "");
            Float fromVolume = ctx.getInputValue(node, "from_volume", Float.class, 1.0f);
            Float toVolume = ctx.getInputValue(node, "to_volume", Float.class, 0.0f);
            Integer durationTicks = ctx.getInputValue(node, "duration_ticks", Integer.class, 20);
            if (player != null && !sound.isEmpty() && durationTicks > 0) {
                float step = (toVolume - fromVolume) / durationTicks;
                new BukkitRunnable() {
                    int tick = 0;

                    @Override
                    public void run() {
                        if (tick >= durationTicks) {
                            player.stopSound(sound);
                            cancel();
                            return;
                        }
                        float volume = fromVolume + step * tick;
                        player.playSound(player.getLocation(), sound, volume, 1.0f);
                        tick++;
                    }
                }.runTaskTimer(ReSync.getInstance(), 0L, 1L);
            }
        });

        operations.put("sound_play_for_player", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            String soundName = ctx.getInputValue(node, "sound_type", String.class, "BLOCK_AMETHYST_BLOCK_CHIME");
            Float volume = ctx.getInputValue(node, "volume", Float.class, 1.0f);
            Float pitch = ctx.getInputValue(node, "pitch", Float.class, 1.0f);
            if (player != null) {
                try {
                    Sound sound = Sound.valueOf(soundName.toUpperCase().replace('.', '_'));
                    if (Bukkit.isPrimaryThread()) {
                        player.playSound(player.getLocation(), sound, volume, pitch);
                    } else {
                        Bukkit.getScheduler().runTask(ReSync.getInstance(), () -> player.playSound(player.getLocation(), sound, volume, pitch));
                    }
                } catch (IllegalArgumentException ignored) {
                }
            }
        });

        operations.put("sound_play_for_all", (ctx, node) -> {
            String soundName = ctx.getInputValue(node, "sound_type", String.class, "BLOCK_AMETHYST_BLOCK_CHIME");
            Float volume = ctx.getInputValue(node, "volume", Float.class, 1.0f);
            Float pitch = ctx.getInputValue(node, "pitch", Float.class, 1.0f);
            try {
                Sound sound = Sound.valueOf(soundName.toUpperCase().replace('.', '_'));
                for (Player player : Bukkit.getOnlinePlayers()) {
                    if (Bukkit.isPrimaryThread()) {
                        player.playSound(player.getLocation(), sound, volume, pitch);
                    } else {
                        Bukkit.getScheduler().runTask(ReSync.getInstance(), () -> player.playSound(player.getLocation(), sound, volume, pitch));
                    }
                }
            } catch (IllegalArgumentException ignored) {
            }
        });

        operations.put("sound_stop_for_player", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            String soundName = ctx.getInputValue(node, "sound", String.class, "");
            if (player != null && !soundName.isEmpty()) {
                try {
                    Sound sound = Sound.valueOf(soundName.toUpperCase().replace('.', '_'));
                    if (Bukkit.isPrimaryThread()) {
                        player.stopSound(sound);
                    } else {
                        Bukkit.getScheduler().runTask(ReSync.getInstance(), () -> player.stopSound(sound));
                    }
                } catch (IllegalArgumentException ignored) {
                }
            }
        });

        operations.put("sound_stop_all", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            if (player != null) {
                if (Bukkit.isPrimaryThread()) {
                    player.stopAllSounds();
                } else {
                    Bukkit.getScheduler().runTask(ReSync.getInstance(), player::stopAllSounds);
                }
            }
        });

        operations.put("sound_play_category", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            String soundName = ctx.getInputValue(node, "sound_type", String.class, "BLOCK_AMETHYST_BLOCK_CHIME");
            String categoryName = ctx.getInputValue(node, "category", String.class, "MASTER");
            Float volume = ctx.getInputValue(node, "volume", Float.class, 1.0f);
            Float pitch = ctx.getInputValue(node, "pitch", Float.class, 1.0f);
            if (player != null) {
                try {
                    Sound sound = Sound.valueOf(soundName.toUpperCase().replace('.', '_'));
                    SoundCategory category = SoundCategory.valueOf(categoryName.toUpperCase());
                    if (Bukkit.isPrimaryThread()) {
                        player.playSound(player.getLocation(), sound, category, volume, pitch);
                    } else {
                        Bukkit.getScheduler().runTask(ReSync.getInstance(), () -> player.playSound(player.getLocation(), sound, category, volume, pitch));
                    }
                } catch (IllegalArgumentException ignored) {
                }
            }
        });

        operations.put("sound_stop_category", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            String categoryName = ctx.getInputValue(node, "category", String.class, "MASTER");
            if (player != null) {
                try {
                    SoundCategory category = SoundCategory.valueOf(categoryName.toUpperCase());
                    if (Bukkit.isPrimaryThread()) {
                        player.stopSound(category);
                    } else {
                        Bukkit.getScheduler().runTask(ReSync.getInstance(), () -> player.stopSound(category));
                    }
                } catch (IllegalArgumentException ignored) {
                }
            }
        });

        operations.put("sound_loop_for_player", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            String soundName = ctx.getInputValue(node, "sound_type", String.class, "BLOCK_AMETHYST_BLOCK_CHIME");
            Float volume = ctx.getInputValue(node, "volume", Float.class, 1.0f);
            Float pitch = ctx.getInputValue(node, "pitch", Float.class, 1.0f);
            Integer interval = ctx.getInputValue(node, "interval_ticks", Integer.class, 20);
            if (player != null && interval > 0) {
                try {
                    Sound sound = Sound.valueOf(soundName.toUpperCase().replace('.', '_'));
                    Bukkit.getScheduler().runTaskTimer(ReSync.getInstance(), () -> {
                        if (player.isOnline()) {
                            player.playSound(player.getLocation(), sound, volume, pitch);
                        }
                    }, 0L, interval.longValue());
                } catch (IllegalArgumentException ignored) {
                }
            }
        });

        operations.put("sound_play_sequence", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            Object soundsObj = ctx.getInputValue(node, "sounds", Object.class, null);
            Integer interval = ctx.getInputValue(node, "interval_ticks", Integer.class, 20);
            if (player != null && soundsObj instanceof java.util.List<?> soundList && !soundList.isEmpty() && interval > 0) {
                new BukkitRunnable() {
                    int index = 0;
                    @Override
                    public void run() {
                        if (index >= soundList.size() || !player.isOnline()) {
                            cancel();
                            return;
                        }
                        String soundName = String.valueOf(soundList.get(index));
                        try {
                            Sound sound = Sound.valueOf(soundName.toUpperCase().replace('.', '_'));
                            player.playSound(player.getLocation(), sound, 1.0f, 1.0f);
                        } catch (IllegalArgumentException ignored) {
                        }
                        index++;
                    }
                }.runTaskTimer(ReSync.getInstance(), 0L, interval.longValue());
            }
        });

        operations.put("sound_play_with_distance", (ctx, node) -> {
            Location location = ctx.getInputValue(node, "location", Location.class, null);
            String soundName = ctx.getInputValue(node, "sound_type", String.class, "BLOCK_AMETHYST_BLOCK_CHIME");
            Float volume = ctx.getInputValue(node, "volume", Float.class, 1.0f);
            Float pitch = ctx.getInputValue(node, "pitch", Float.class, 1.0f);
            Double maxDistance = ctx.getInputValue(node, "max_distance", Double.class, 16.0);
            if (location != null && location.getWorld() != null) {
                try {
                    Sound sound = Sound.valueOf(soundName.toUpperCase().replace('.', '_'));
                    for (Player player : location.getWorld().getPlayers()) {
                        if (player.getLocation().distance(location) <= maxDistance) {
                            player.playSound(location, sound, volume, pitch);
                        }
                    }
                } catch (IllegalArgumentException ignored) {
                }
            }
        });

    }

    public void registerTo(HandlerRegistry registry) {
        registry.register("SoundHandler", this);
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
}
