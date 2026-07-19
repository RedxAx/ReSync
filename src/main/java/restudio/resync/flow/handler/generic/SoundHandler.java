package restudio.resync.flow.handler.generic;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import restudio.flow.data.FlowNode;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.NodeHandler;

import java.util.List;
import java.util.Locale;
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
            requireWorldLocation(location);
            Sound sound = sound(soundName);
            location.getWorld().playSound(location, sound, volume, pitch);
        });

        operations.put("sound_stop", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            String soundName = ctx.getInputValue(node, "sound", String.class, "");
            requirePlayer(player);
            Sound sound = sound(soundName);
            player.stopSound(sound);
        });

        operations.put("sound_play_ambient", (ctx, node) -> {
            Location location = ctx.getInputValue(node, "location", Location.class, null);
            String soundName = ctx.getInputValue(node, "sound_type", String.class, "BLOCK_AMETHYST_BLOCK_CHIME");
            Float volume = ctx.getInputValue(node, "volume", Float.class, 1.0f);
            Float pitch = ctx.getInputValue(node, "pitch", Float.class, 1.0f);
            requireWorldLocation(location);
            Sound sound = sound(soundName);
            location.getWorld().playSound(location, sound, SoundCategory.AMBIENT, volume, pitch);
        });

        operations.put("fade", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            String sound = ctx.getInputValue(node, "sound", String.class, "");
            Float fromVolume = ctx.getInputValue(node, "from_volume", Float.class, 1.0f);
            Float toVolume = ctx.getInputValue(node, "to_volume", Float.class, 0.0f);
            Integer durationTicks = ctx.getInputValue(node, "duration_ticks", Integer.class, 20);
            requirePlayer(player);
            sound(sound);
            if (durationTicks == null || durationTicks <= 0) {
                throw new IllegalArgumentException("Sound fade duration must be greater than zero");
            }
            if (durationTicks > 200) {
                throw new IllegalArgumentException("Sound fade duration cannot exceed 200 ticks");
            }
            float step = (toVolume - fromVolume) / durationTicks;
            for (int tick = 0; tick < durationTicks; tick++) {
                float volume = fromVolume + step * tick;
                ctx.runLaterBeforeContinuation(() -> player.playSound(player.getLocation(), sound, volume, 1.0f), tick);
            }
            ctx.runLaterBeforeContinuation(() -> player.stopSound(sound), durationTicks);
        });

        operations.put("sound_play_for_player", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            String soundName = ctx.getInputValue(node, "sound_type", String.class, "BLOCK_AMETHYST_BLOCK_CHIME");
            Float volume = ctx.getInputValue(node, "volume", Float.class, 1.0f);
            Float pitch = ctx.getInputValue(node, "pitch", Float.class, 1.0f);
            requirePlayer(player);
            Sound sound = sound(soundName);
            player.playSound(player.getLocation(), sound, volume, pitch);
        });

        operations.put("sound_play_for_all", (ctx, node) -> {
            String soundName = ctx.getInputValue(node, "sound_type", String.class, "BLOCK_AMETHYST_BLOCK_CHIME");
            Float volume = ctx.getInputValue(node, "volume", Float.class, 1.0f);
            Float pitch = ctx.getInputValue(node, "pitch", Float.class, 1.0f);
            Sound sound = sound(soundName);
            for (Player player : Bukkit.getOnlinePlayers()) {
                player.playSound(player.getLocation(), sound, volume, pitch);
            }
        });

        operations.put("sound_stop_for_player", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            String soundName = ctx.getInputValue(node, "sound", String.class, "");
            requirePlayer(player);
            Sound sound = sound(soundName);
            player.stopSound(sound);
        });

        operations.put("sound_stop_all", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            requirePlayer(player);
            player.stopAllSounds();
        });

        operations.put("sound_play_category", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            String soundName = ctx.getInputValue(node, "sound_type", String.class, "BLOCK_AMETHYST_BLOCK_CHIME");
            String categoryName = ctx.getInputValue(node, "category", String.class, "MASTER");
            Float volume = ctx.getInputValue(node, "volume", Float.class, 1.0f);
            Float pitch = ctx.getInputValue(node, "pitch", Float.class, 1.0f);
            requirePlayer(player);
            Sound sound = sound(soundName);
            SoundCategory category = soundCategory(categoryName);
            player.playSound(player.getLocation(), sound, category, volume, pitch);
        });

        operations.put("sound_stop_category", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            String categoryName = ctx.getInputValue(node, "category", String.class, "MASTER");
            requirePlayer(player);
            SoundCategory category = soundCategory(categoryName);
            player.stopSound(category);
        });

        operations.put("sound_loop_for_player", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            String soundName = ctx.getInputValue(node, "sound_type", String.class, "BLOCK_AMETHYST_BLOCK_CHIME");
            Float volume = ctx.getInputValue(node, "volume", Float.class, 1.0f);
            Float pitch = ctx.getInputValue(node, "pitch", Float.class, 1.0f);
            Integer interval = ctx.getInputValue(node, "interval_ticks", Integer.class, 20);
            Integer repeatCount = ctx.getInputValue(node, "repeat_count", Integer.class, 10);
            requirePlayer(player);
            if (interval == null || interval <= 0) {
                throw new IllegalArgumentException("Sound loop interval must be greater than zero");
            }
            if (repeatCount == null || repeatCount <= 0 || repeatCount > 256) {
                throw new IllegalArgumentException("Sound loop repeat count must be between 1 and 256");
            }
            long lastDelay = Math.multiplyExact(repeatCount - 1L, interval.longValue());
            if (lastDelay > 72_000L) {
                throw new IllegalArgumentException("Sound loop duration cannot exceed 72000 ticks");
            }
            Sound sound = sound(soundName);
            for (int index = 0; index < repeatCount; index++) {
                long delay = Math.multiplyExact(index, interval.longValue());
                ctx.runLaterBeforeContinuation(() -> {
                    if (player.isOnline()) {
                        player.playSound(player.getLocation(), sound, volume, pitch);
                    }
                }, delay);
            }
        });

        operations.put("sound_play_sequence", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            Object soundsObj = ctx.getInputValue(node, "sounds", Object.class, null);
            Integer interval = ctx.getInputValue(node, "interval_ticks", Integer.class, 20);
            requirePlayer(player);
            if (!(soundsObj instanceof List<?> soundList) || soundList.isEmpty()) {
                throw new IllegalArgumentException("Sound sequence requires at least one sound");
            }
            if (interval == null || interval <= 0) {
                throw new IllegalArgumentException("Sound sequence interval must be greater than zero");
            }
            if (soundList.size() > 100) {
                throw new IllegalArgumentException("Sound sequence cannot contain more than 100 sounds");
            }
            long lastDelay = Math.multiplyExact(soundList.size() - 1L, interval.longValue());
            if (lastDelay > 72_000L) {
                throw new IllegalArgumentException("Sound sequence duration cannot exceed 72000 ticks");
            }
            List<Sound> sounds = soundList.stream().map(value -> sound(String.valueOf(value))).toList();
            for (int index = 0; index < sounds.size(); index++) {
                Sound scheduledSound = sounds.get(index);
                long delay = Math.multiplyExact(index, interval.longValue());
                ctx.runLaterBeforeContinuation(() -> {
                    if (player.isOnline()) {
                        player.playSound(player.getLocation(), scheduledSound, 1.0f, 1.0f);
                    }
                }, delay);
            }
        });

        operations.put("sound_play_with_distance", (ctx, node) -> {
            Location location = ctx.getInputValue(node, "location", Location.class, null);
            String soundName = ctx.getInputValue(node, "sound_type", String.class, "BLOCK_AMETHYST_BLOCK_CHIME");
            Float volume = ctx.getInputValue(node, "volume", Float.class, 1.0f);
            Float pitch = ctx.getInputValue(node, "pitch", Float.class, 1.0f);
            Double maxDistance = ctx.getInputValue(node, "max_distance", Double.class, 16.0);
            requireWorldLocation(location);
            if (maxDistance == null || !Double.isFinite(maxDistance) || maxDistance < 0.0 || maxDistance > 1024.0) {
                throw new IllegalArgumentException("Sound distance must be between 0 and 1024");
            }
            Sound sound = sound(soundName);
            for (Player player : location.getWorld().getPlayers()) {
                if (player.getLocation().distance(location) <= maxDistance) {
                    player.playSound(location, sound, volume, pitch);
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
        if (op == null) {
            throw new IllegalArgumentException("Unknown sound operation: " + operation);
        }
        validateLevel(ctx, node, "volume", 0.0f, 16.0f);
        validateLevel(ctx, node, "from_volume", 0.0f, 16.0f);
        validateLevel(ctx, node, "to_volume", 0.0f, 16.0f);
        validateLevel(ctx, node, "pitch", 0.0f, 2.0f);
        op.accept(ctx, node);
        ctx.triggerOutput("flow");
    }

    private static Sound sound(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Sound is required");
        }
        try {
            return Sound.valueOf(value.toUpperCase(Locale.ROOT).replace('.', '_'));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unknown sound: " + value, exception);
        }
    }

    private static SoundCategory soundCategory(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Sound category is required");
        }
        try {
            return SoundCategory.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unknown sound category: " + value, exception);
        }
    }

    private static void requirePlayer(Player player) {
        if (player == null) {
            throw new IllegalArgumentException("Player is required");
        }
    }

    private static void requireWorldLocation(Location location) {
        if (location == null || location.getWorld() == null) {
            throw new IllegalArgumentException("World location is required");
        }
    }

    private static void validateLevel(FlowContext ctx, FlowNode node, String pin, float minimum, float maximum) {
        Object raw = ctx.getInputValue(node, pin);
        if (raw == null) return;
        if (!(raw instanceof Number number)) throw new IllegalArgumentException("Sound " + pin + " must be a number");
        float value = number.floatValue();
        if (!Float.isFinite(value) || value < minimum || value > maximum) {
            throw new IllegalArgumentException("Sound " + pin + " must be between " + minimum + " and " + maximum);
        }
    }
}
