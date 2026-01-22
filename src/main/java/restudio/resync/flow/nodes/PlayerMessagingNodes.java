package restudio.resync.flow.nodes;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import restudio.flow.data.FlowNode;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.FlowRegistry;
import restudio.resync.flow.NodeCategory;
import restudio.resync.flow.util.TextFormatter;

import java.util.Optional;

public class PlayerMessagingNodes implements NodeCategory {

    @Override
    public void registerNodes(FlowRegistry registry) {
        registry.register("player_send_message", (ctx, node) -> {
            Player target = ctx.getInputValue(node, "target", Player.class, null);
            String text = ctx.getInputValue(node, "text", String.class, "");
            if (target != null && !text.isEmpty()) {
                Component component = TextFormatter.parse(text);
                if (Bukkit.isPrimaryThread()) {
                    target.sendMessage(component);
                } else {
                    Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), () -> target.sendMessage(component));
                }
            }
            ctx.triggerOutput("flow");
        });

        registry.register("player_send_action_bar", (ctx, node) -> {
            Player target = ctx.getInputValue(node, "target", Player.class, null);
            String text = ctx.getInputValue(node, "text", String.class, "");
            if (target != null && !text.isEmpty()) {
                Component component = TextFormatter.parse(text);
                if (Bukkit.isPrimaryThread()) {
                    target.sendActionBar(component);
                } else {
                    Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), () -> target.sendActionBar(component));
                }
            }
            ctx.triggerOutput("flow");
        });

        registry.register("player_send_title", (ctx, node) -> {
            Player target = ctx.getInputValue(node, "target", Player.class, null);
            String title = ctx.getInputValue(node, "title", String.class, "");
            String subtitle = ctx.getInputValue(node, "subtitle", String.class, "");
            Integer fadeIn = ctx.getInputValue(node, "fade_in", Integer.class, 10);
            Integer stay = ctx.getInputValue(node, "stay", Integer.class, 70);
            Integer fadeOut = ctx.getInputValue(node, "fade_out", Integer.class, 20);
            if (target != null) {
                Component titleComponent = TextFormatter.parse(title);
                Component subtitleComponent = TextFormatter.parse(subtitle);
                Title titleObj = Title.title(titleComponent, subtitleComponent);
                target.showTitle(titleObj);
            }
            ctx.triggerOutput("flow");
        });

        registry.register("player_send_sound", (ctx, node) -> {
            Player target = ctx.getInputValue(node, "target", Player.class, null);
            if (target == null) {
                ctx.triggerOutput("flow");
                return;
            }
            String soundName = ctx.getInputValue(node, "sound", String.class, "block.amethyst_block.chime");
            Float volume = ctx.getInputValue(node, "volume", Float.class, 1.0f);
            Float pitch = ctx.getInputValue(node, "pitch", Float.class, 1.0f);
            try {
                org.bukkit.Sound sound = org.bukkit.Sound.valueOf(soundName.toUpperCase().replace('.', '_'));
                Location loc = target.getLocation();
                if (Bukkit.isPrimaryThread()) {
                    target.playSound(loc, sound, volume, pitch);
                } else {
                    Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), () -> target.playSound(loc, sound, volume, pitch));
                }
            } catch (IllegalArgumentException ignored) {}
            ctx.triggerOutput("flow");
        });

        registry.register("player_send_particle", (ctx, node) -> {
            Player target = ctx.getInputValue(node, "target", Player.class, null);
            if (target == null) {
                ctx.triggerOutput("flow");
                return;
            }
            String particleName = ctx.getInputValue(node, "particle", String.class, "FLAME");
            Integer count = ctx.getInputValue(node, "count", Integer.class, 10);
            Double offsetX = ctx.getInputValue(node, "offset_x", Double.class, 0.0);
            Double offsetY = ctx.getInputValue(node, "offset_y", Double.class, 0.0);
            Double offsetZ = ctx.getInputValue(node, "offset_z", Double.class, 0.0);
            Double speed = ctx.getInputValue(node, "speed", Double.class, 0.0);

            try {
                Particle particle = Particle.valueOf(particleName.toUpperCase());
                Location loc = target.getLocation();
                loc.add(0, 1, 0);

                double finalX = offsetX;
                double finalY = offsetY;
                double finalZ = offsetZ;
                double finalSpeed = speed;

                if (Bukkit.isPrimaryThread()) {
                    target.getWorld().spawnParticle(particle, loc, count, finalX, finalY, finalZ, finalSpeed, null);
                } else {
                    Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), () -> {
                        Location spawnLoc = target.getLocation().clone().add(0, 1, 0);
                        target.getWorld().spawnParticle(particle, spawnLoc, count, finalX, finalY, finalZ, finalSpeed, null);
                    });
                }
            } catch (IllegalArgumentException ignored) {}
            ctx.triggerOutput("flow");
        });

        registry.register("player_send_book", (ctx, node) -> {
            Player target = ctx.getInputValue(node, "target", Player.class, null);
            if (target == null) {
                ctx.triggerOutput("flow");
                return;
            }
            ItemStack book = ctx.getInputValue(node, "book", ItemStack.class, null);
            if (book != null && book.getType() == org.bukkit.Material.WRITTEN_BOOK) {
                if (Bukkit.isPrimaryThread()) {
                    target.openBook(book);
                } else {
                    Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), () -> target.openBook(book));
                }
            }
            ctx.triggerOutput("flow");
        });

        registry.register("player_send_sign", (ctx, node) -> {
            Player target = ctx.getInputValue(node, "target", Player.class, null);
            if (target == null) {
                ctx.triggerOutput("flow");
                return;
            }
            if (Bukkit.isPrimaryThread()) {
                Location loc = target.getLocation().clone();
                org.bukkit.block.Sign sign = (org.bukkit.block.Sign) loc.getBlock().getState();
                target.openSign(sign);
            } else {
                Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), () -> {
                    Location loc = target.getLocation().clone();
                    try {
                        org.bukkit.block.Sign sign = (org.bukkit.block.Sign) loc.getBlock().getState();
                        target.openSign(sign);
                    } catch (Exception ignored) {}
                });
            }
            ctx.triggerOutput("flow");
        });

        registry.register("player_send_raw_json", (ctx, node) -> {
            Player target = ctx.getInputValue(node, "target", Player.class, null);
            String json = ctx.getInputValue(node, "json", String.class, "");
            if (target != null && !json.isEmpty()) {
                try {
                    Component component = net.kyori.adventure.text.serializer.gson.GsonComponentSerializer.gson().deserialize(json);
                    if (Bukkit.isPrimaryThread()) {
                        target.sendMessage(component);
                    } else {
                        Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), () -> target.sendMessage(component));
                    }
                } catch (Exception ignored) {}
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
