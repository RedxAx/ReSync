package restudio.resync.flow.nodes;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
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
