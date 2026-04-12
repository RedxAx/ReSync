package restudio.resync.flow.nodes;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import restudio.flow.data.FlowNode;
import restudio.flow.data.FlowType;
import restudio.resync.ReSync;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.registry.DefineNode;
import restudio.resync.flow.registry.FlowPin;
import restudio.resync.flow.registry.NodeDefinition;
import restudio.resync.flow.util.TextFormatter;

public class PlayerMessagingNodes {

    @DefineNode(id = "player_send_message", displayName = "Send Message", category = NodeDefinition.NodeCategory.PLAYER,
            inputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW), @FlowPin(name = "target", dataType = FlowType.PLAYER), @FlowPin(name = "text", dataType = FlowType.STRING)},
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void sendMessage(FlowContext ctx, FlowNode node) {
        Player target = ctx.getInputValue(node, "target", Player.class, null);
        String text = ctx.getInputValue(node, "text", String.class, "");
        if (target != null && !text.isEmpty()) {
            Component component = TextFormatter.parse(text);
            if (Bukkit.isPrimaryThread()) {
                target.sendMessage(component);
            } else {
                Bukkit.getScheduler().runTask(ReSync.getInstance(), () -> target.sendMessage(component));
            }
        }
        ctx.triggerOutput("flow");
    }

    @DefineNode(id = "player_send_action_bar", displayName = "Send ActionBar", category = NodeDefinition.NodeCategory.PLAYER,
            inputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW), @FlowPin(name = "target", dataType = FlowType.PLAYER), @FlowPin(name = "text", dataType = FlowType.STRING)},
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void sendActionBar(FlowContext ctx, FlowNode node) {
        Player target = ctx.getInputValue(node, "target", Player.class, null);
        String text = ctx.getInputValue(node, "text", String.class, "");
        if (target != null && !text.isEmpty()) {
            Component component = TextFormatter.parse(text);
            if (Bukkit.isPrimaryThread()) {
                target.sendActionBar(component);
            } else {
                Bukkit.getScheduler().runTask(ReSync.getInstance(), () -> target.sendActionBar(component));
            }
        }
        ctx.triggerOutput("flow");
    }

    @DefineNode(id = "player_send_title", displayName = "Send Title", category = NodeDefinition.NodeCategory.PLAYER,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "target", dataType = FlowType.PLAYER),
                    @FlowPin(name = "title", dataType = FlowType.STRING),
                    @FlowPin(name = "subtitle", dataType = FlowType.STRING),
                    @FlowPin(name = "fade_in", dataType = FlowType.NUMBER),
                    @FlowPin(name = "stay", dataType = FlowType.NUMBER),
                    @FlowPin(name = "fade_out", dataType = FlowType.NUMBER)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void sendTitle(FlowContext ctx, FlowNode node) {
        Player target = ctx.getInputValue(node, "target", Player.class, null);
        String title = ctx.getInputValue(node, "title", String.class, "");
        String subtitle = ctx.getInputValue(node, "subtitle", String.class, "");
        if (target != null) {
            target.showTitle(Title.title(TextFormatter.parse(title), TextFormatter.parse(subtitle)));
        }
        ctx.triggerOutput("flow");
    }

    @DefineNode(id = "player_send_sound", displayName = "Send Sound", category = NodeDefinition.NodeCategory.PLAYER,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "target", dataType = FlowType.PLAYER),
                    @FlowPin(name = "sound", dataType = FlowType.STRING),
                    @FlowPin(name = "volume", dataType = FlowType.NUMBER),
                    @FlowPin(name = "pitch", dataType = FlowType.NUMBER)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void sendSound(FlowContext ctx, FlowNode node) {
        Player target = ctx.getInputValue(node, "target", Player.class, null);
        if (target == null) {
            ctx.triggerOutput("flow");
            return;
        }
        String soundName = ctx.getInputValue(node, "sound", String.class, "block.amethyst_block.chime");
        Float volume = ctx.getInputValue(node, "volume", Float.class, 1.0f);
        Float pitch = ctx.getInputValue(node, "pitch", Float.class, 1.0f);
        try {
            Sound sound = Sound.valueOf(soundName.toUpperCase().replace('.', '_'));
            Location loc = target.getLocation();
            if (Bukkit.isPrimaryThread()) {
                target.playSound(loc, sound, volume, pitch);
            } else {
                Bukkit.getScheduler().runTask(ReSync.getInstance(), () -> target.playSound(loc, sound, volume, pitch));
            }
        } catch (IllegalArgumentException ignored) {
        }
        ctx.triggerOutput("flow");
    }

    @DefineNode(id = "player_send_particle", displayName = "Send Particle", category = NodeDefinition.NodeCategory.PLAYER,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "target", dataType = FlowType.PLAYER),
                    @FlowPin(name = "particle", dataType = FlowType.STRING),
                    @FlowPin(name = "count", dataType = FlowType.NUMBER),
                    @FlowPin(name = "offset_x", dataType = FlowType.NUMBER),
                    @FlowPin(name = "offset_y", dataType = FlowType.NUMBER),
                    @FlowPin(name = "offset_z", dataType = FlowType.NUMBER),
                    @FlowPin(name = "speed", dataType = FlowType.NUMBER)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void sendParticle(FlowContext ctx, FlowNode node) {
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
            if (Bukkit.isPrimaryThread()) {
                target.getWorld().spawnParticle(particle, target.getLocation().clone().add(0, 1, 0), count, offsetX, offsetY, offsetZ, speed, null);
            } else {
                Bukkit.getScheduler().runTask(ReSync.getInstance(), () ->
                        target.getWorld().spawnParticle(particle, target.getLocation().clone().add(0, 1, 0), count, offsetX, offsetY, offsetZ, speed, null));
            }
        } catch (IllegalArgumentException ignored) {
        }
        ctx.triggerOutput("flow");
    }

    @DefineNode(id = "player_send_book", displayName = "Send Book", category = NodeDefinition.NodeCategory.PLAYER,
            inputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW), @FlowPin(name = "target", dataType = FlowType.PLAYER), @FlowPin(name = "book", dataType = FlowType.ITEMSTACK)},
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void sendBook(FlowContext ctx, FlowNode node) {
        Player target = ctx.getInputValue(node, "target", Player.class, null);
        ItemStack book = ctx.getInputValue(node, "book", ItemStack.class, null);
        if (target != null && book != null && book.getType() == Material.WRITTEN_BOOK) {
            if (Bukkit.isPrimaryThread()) {
                target.openBook(book);
            } else {
                Bukkit.getScheduler().runTask(ReSync.getInstance(), () -> target.openBook(book));
            }
        }
        ctx.triggerOutput("flow");
    }

    @DefineNode(id = "player_send_sign", displayName = "Open Sign", category = NodeDefinition.NodeCategory.PLAYER,
            inputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW), @FlowPin(name = "target", dataType = FlowType.PLAYER)},
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void sendSign(FlowContext ctx, FlowNode node) {
        Player target = ctx.getInputValue(node, "target", Player.class, null);
        if (target == null) {
            ctx.triggerOutput("flow");
            return;
        }
        Runnable action = () -> {
            try {
                Sign sign = (Sign) target.getLocation().getBlock().getState();
                target.openSign(sign);
            } catch (Exception ignored) {
            }
        };
        if (Bukkit.isPrimaryThread()) {
            action.run();
        } else {
            Bukkit.getScheduler().runTask(ReSync.getInstance(), action);
        }
        ctx.triggerOutput("flow");
    }

    @DefineNode(id = "player_send_raw_json", displayName = "Send Raw Json", category = NodeDefinition.NodeCategory.PLAYER,
            inputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW), @FlowPin(name = "target", dataType = FlowType.PLAYER), @FlowPin(name = "json", dataType = FlowType.STRING)},
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void sendRawJson(FlowContext ctx, FlowNode node) {
        Player target = ctx.getInputValue(node, "target", Player.class, null);
        String json = ctx.getInputValue(node, "json", String.class, "");
        if (target != null && !json.isEmpty()) {
            try {
                Component component = net.kyori.adventure.text.serializer.gson.GsonComponentSerializer.gson().deserialize(json);
                if (Bukkit.isPrimaryThread()) {
                    target.sendMessage(component);
                } else {
                    Bukkit.getScheduler().runTask(ReSync.getInstance(), () -> target.sendMessage(component));
                }
            } catch (Exception ignored) {
            }
        }
        ctx.triggerOutput("flow");
    }
}
