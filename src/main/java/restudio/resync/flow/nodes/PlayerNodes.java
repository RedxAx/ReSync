package restudio.resync.flow.nodes;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import restudio.flow.data.FlowType;
import restudio.resync.ReSync;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.registry.DefineNode;
import restudio.resync.flow.registry.FlowPin;
import restudio.resync.flow.registry.NodeDefinition;
import restudio.resync.flow.util.TextFormatter;

public class PlayerNodes {

    @DefineNode(id = "get_player_info", displayName = "Get Player Info", category = NodeDefinition.NodeCategory.ENTITY,
            inputs = {@FlowPin(name = "target", dataType = FlowType.PLAYER)},
            outputs = {@FlowPin(name = "name", dataType = FlowType.STRING), @FlowPin(name = "uuid", dataType = FlowType.STRING), @FlowPin(name = "health", dataType = FlowType.NUMBER), @FlowPin(name = "location", dataType = FlowType.LOCATION), @FlowPin(name = "is_op", dataType = FlowType.BOOLEAN)})
    public void getPlayerInfo(FlowContext ctx, restudio.flow.data.FlowNode node) {
        Player target = ctx.getInputValue(node, "target", Player.class);
        if (target == null) return;
        ctx.setOutput(node, "name", target.getName());
        ctx.setOutput(node, "uuid", target.getUniqueId().toString());
        ctx.setOutput(node, "health", target.getHealth());
        ctx.setOutput(node, "location", target.getLocation());
        ctx.setOutput(node, "is_op", target.isOp());
    }

    @DefineNode(id = "player_message", displayName = "Message", category = NodeDefinition.NodeCategory.ACTION,
            inputs = {@FlowPin(name = "target", dataType = FlowType.PLAYER), @FlowPin(name = "text", dataType = FlowType.STRING)},
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void message(FlowContext ctx, restudio.flow.data.FlowNode node) {
        Player target = ctx.getInputValue(node, "target", Player.class);
        String text = ctx.getInputValue(node, "text", String.class, "");
        if (target != null) {
            target.sendMessage(TextFormatter.parse(text));
        }
        ctx.triggerOutput("flow");
    }

    @DefineNode(id = "player_kick", displayName = "Kick", category = NodeDefinition.NodeCategory.ACTION,
            inputs = {@FlowPin(name = "target", dataType = FlowType.PLAYER), @FlowPin(name = "reason", dataType = FlowType.STRING)},
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void kick(FlowContext ctx, restudio.flow.data.FlowNode node) {
        Player target = ctx.getInputValue(node, "target", Player.class);
        String reason = ctx.getInputValue(node, "reason", String.class, "Kicked by Flow");
        if (target != null) {
            if (Bukkit.isPrimaryThread()) {
                target.kick(TextFormatter.parse(reason));
            } else {
                try {
                    Bukkit.getScheduler().callSyncMethod(ReSync.getInstance(), () -> {
                        target.kick(TextFormatter.parse(reason));
                        return null;
                    }).get();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
        ctx.triggerOutput("flow");
    }

    @DefineNode(id = "player_teleport", displayName = "Teleport", category = NodeDefinition.NodeCategory.ACTION,
            inputs = {@FlowPin(name = "target", dataType = FlowType.PLAYER), @FlowPin(name = "location", dataType = FlowType.LOCATION), @FlowPin(name = "x", dataType = FlowType.NUMBER), @FlowPin(name = "y", dataType = FlowType.NUMBER), @FlowPin(name = "z", dataType = FlowType.NUMBER)},
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void teleport(FlowContext ctx, restudio.flow.data.FlowNode node) {
        Player target = ctx.getInputValue(node, "target", Player.class);
        if (target == null) {
            ctx.triggerOutput("flow");
            return;
        }
        Location location = ctx.getInputValue(node, "location", Location.class, null);
        if (location == null) {
            Double x = ctx.getInputValue(node, "x", Double.class, target.getLocation().getX());
            Double y = ctx.getInputValue(node, "y", Double.class, target.getLocation().getY());
            Double z = ctx.getInputValue(node, "z", Double.class, target.getLocation().getZ());
            location = new Location(target.getWorld(), x, y, z);
        }
        target.teleport(location);
        ctx.triggerOutput("flow");
    }

    @DefineNode(id = "give_item", displayName = "Give Item", category = NodeDefinition.NodeCategory.INVENTORY,
            inputs = {@FlowPin(name = "target", dataType = FlowType.PLAYER), @FlowPin(name = "material", dataType = FlowType.STRING), @FlowPin(name = "amount", dataType = FlowType.NUMBER)},
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void giveItem(FlowContext ctx, restudio.flow.data.FlowNode node) {
        Player target = ctx.getInputValue(node, "target", Player.class);
        String materialName = ctx.getInputValue(node, "material", String.class, "STONE");
        Integer amount = ctx.getInputValue(node, "amount", Integer.class, 1);
        if (target == null) {
            ctx.triggerOutput("flow");
            return;
        }
        Material material = Material.getMaterial(materialName.toUpperCase());
        if (material != null) {
            target.getInventory().addItem(new ItemStack(material, Math.max(1, amount)));
        }
        ctx.triggerOutput("flow");
    }

    @DefineNode(id = "player_set_walking_speed", displayName = "Set Walking Speed", category = NodeDefinition.NodeCategory.ACTION,
            inputs = {@FlowPin(name = "target", dataType = FlowType.PLAYER), @FlowPin(name = "speed", dataType = FlowType.NUMBER)},
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void setWalkingSpeed(FlowContext ctx, restudio.flow.data.FlowNode node) {
        Player target = ctx.getInputValue(node, "target", Player.class, null);
        Double speed = ctx.getInputValue(node, "speed", Double.class, 0.2);
        if (target != null) {
            target.setWalkSpeed(speed.floatValue());
        }
        ctx.triggerOutput("flow");
    }

    @DefineNode(id = "player_set_flying_speed", displayName = "Set Flying Speed", category = NodeDefinition.NodeCategory.ACTION,
            inputs = {@FlowPin(name = "target", dataType = FlowType.PLAYER), @FlowPin(name = "speed", dataType = FlowType.NUMBER)},
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void setFlyingSpeed(FlowContext ctx, restudio.flow.data.FlowNode node) {
        Player target = ctx.getInputValue(node, "target", Player.class, null);
        Double speed = ctx.getInputValue(node, "speed", Double.class, 0.05);
        if (target != null) {
            target.setFlySpeed(speed.floatValue());
        }
        ctx.triggerOutput("flow");
    }

    @DefineNode(id = "player_execute_command", displayName = "Execute Command", category = NodeDefinition.NodeCategory.ACTION,
            inputs = {@FlowPin(name = "target", dataType = FlowType.PLAYER), @FlowPin(name = "command", dataType = FlowType.STRING), @FlowPin(name = "as_op", dataType = FlowType.BOOLEAN)},
            outputs = {@FlowPin(name = "success", dataType = FlowType.BOOLEAN), @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void executeCommand(FlowContext ctx, restudio.flow.data.FlowNode node) {
        Player target = ctx.getInputValue(node, "target", Player.class);
        String command = ctx.getInputValue(node, "command", String.class, "");
        Boolean asOp = ctx.getInputValue(node, "as_op", Boolean.class, false);
        if (target == null || command.isEmpty()) {
            ctx.setOutput(node, "success", false);
            ctx.triggerOutput("flow");
            return;
        }
        boolean success;
        if (Bukkit.isPrimaryThread()) {
            boolean wasOp = target.isOp();
            if (asOp) target.setOp(true);
            success = Bukkit.dispatchCommand(target, command);
            if (asOp && !wasOp) target.setOp(false);
        } else {
            Bukkit.getScheduler().runTask(ReSync.getInstance(), () -> {
                boolean wasOp = target.isOp();
                if (asOp) target.setOp(true);
                Bukkit.dispatchCommand(target, command);
                if (asOp && !wasOp) target.setOp(false);
            });
            success = true;
        }
        ctx.setOutput(node, "success", success);
        ctx.triggerOutput("flow");
    }

    @DefineNode(id = "player_chat", displayName = "Chat", category = NodeDefinition.NodeCategory.ACTION,
            inputs = {@FlowPin(name = "target", dataType = FlowType.PLAYER), @FlowPin(name = "message", dataType = FlowType.STRING)},
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void chat(FlowContext ctx, restudio.flow.data.FlowNode node) {
        Player target = ctx.getInputValue(node, "target", Player.class);
        String message = ctx.getInputValue(node, "message", String.class, "");
        if (target == null || message.isEmpty()) {
            ctx.triggerOutput("flow");
            return;
        }
        if (Bukkit.isPrimaryThread()) {
            target.chat(message);
        } else {
            Bukkit.getScheduler().runTask(ReSync.getInstance(), () -> target.chat(message));
        }
        ctx.triggerOutput("flow");
    }

    @DefineNode(id = "player_say", displayName = "Say", category = NodeDefinition.NodeCategory.ACTION,
            inputs = {@FlowPin(name = "target", dataType = FlowType.PLAYER), @FlowPin(name = "message", dataType = FlowType.STRING)},
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void say(FlowContext ctx, restudio.flow.data.FlowNode node) {
        Player target = ctx.getInputValue(node, "target", Player.class);
        String message = ctx.getInputValue(node, "message", String.class, "");
        if (target == null || message.isEmpty()) {
            ctx.triggerOutput("flow");
            return;
        }
        if (Bukkit.isPrimaryThread()) {
            target.chat("/say " + message);
        } else {
            Bukkit.getScheduler().runTask(ReSync.getInstance(), () -> target.chat("/say " + message));
        }
        ctx.triggerOutput("flow");
    }
}
