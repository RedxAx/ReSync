package restudio.resync.flow.nodes;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import restudio.flow.data.FlowNode;
import restudio.resync.ReSync;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.FlowRegistry;
import restudio.resync.flow.util.TextFormatter;

public class PlayerNodes {
    public static void registerAll(FlowRegistry registry) {
        registry.register("get_player_info", (ctx, node) -> {
            Player target = ctx.getInputValue(node, "target", Player.class);
            if (target == null) {
                return;
            }

            String nodeId = findNodeId(ctx, node);
            if (nodeId == null) {
                return;
            }

            ctx.setNodeOutput(nodeId, "name", target.getName());
            ctx.setNodeOutput(nodeId, "uuid", target.getUniqueId().toString());
            ctx.setNodeOutput(nodeId, "health", target.getHealth());
            ctx.setNodeOutput(nodeId, "location", target.getLocation());
            ctx.setNodeOutput(nodeId, "is_op", target.isOp());
        });

        registry.register("player_message", (ctx, node) -> {
            Player target = ctx.getInputValue(node, "target", Player.class);
            String text = ctx.getInputValue(node, "text", String.class, "");
            if (target != null) {
                target.sendMessage(TextFormatter.parse(text));
            }
            ctx.triggerOutput("flow");
        });

        registry.register("player_kick", (ctx, node) -> {
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
        });

        registry.register("player_teleport", (ctx, node) -> {
            Player target = ctx.getInputValue(node, "target", Player.class);
            if (target == null) {
                ctx.triggerOutput("flow");
                return;
            }

            var location = ctx.getInputValue(node, "location", org.bukkit.Location.class, null);
            if (location == null) {
                Double x = ctx.getInputValue(node, "x", Double.class, target.getLocation().getX());
                Double y = ctx.getInputValue(node, "y", Double.class, target.getLocation().getY());
                Double z = ctx.getInputValue(node, "z", Double.class, target.getLocation().getZ());
                location = new org.bukkit.Location(target.getWorld(), x, y, z);
            }
            target.teleport(location);
            ctx.triggerOutput("flow");
        });

        registry.register("give_item", (ctx, node) -> {
            Player target = ctx.getInputValue(node, "target", Player.class);
            String materialName = ctx.getInputValue(node, "material", String.class, "STONE");
            Integer amount = ctx.getInputValue(node, "amount", Integer.class,1);
            if (target == null) {
                ctx.triggerOutput("flow");
                return;
            }

            Material material = Material.getMaterial(materialName.toUpperCase());
            if (material != null) {
                target.getInventory().addItem(new ItemStack(material, Math.max(1, amount)));
            }
            ctx.triggerOutput("flow");
        });

        registry.register("player_set_walking_speed", (ctx, node) -> {
            Player target = ctx.getInputValue(node, "target", Player.class, null);
            Double speed = ctx.getInputValue(node, "speed", Double.class, 0.2);
            if (target != null) {
                target.setWalkSpeed(speed.floatValue());
            }
            ctx.triggerOutput("flow");
        });

        registry.register("player_set_flying_speed", (ctx, node) -> {
            Player target = ctx.getInputValue(node, "target", Player.class, null);
            Double speed = ctx.getInputValue(node, "speed", Double.class, 0.05);
            if (target != null) {
                target.setFlySpeed(speed.floatValue());
            }
            ctx.triggerOutput("flow");
        });

        registry.register("player_execute_command", (ctx, node) -> {
            Player target = ctx.getInputValue(node, "target", Player.class);
            String command = ctx.getInputValue(node, "command", String.class, "");
            Boolean asOp = ctx.getInputValue(node, "as_op", Boolean.class, false);
            if (target == null || command.isEmpty()) {
                String nodeId = findNodeId(ctx, node);
                ctx.setNodeOutput(nodeId, "success", false);
                ctx.triggerOutput("flow");
                return;
            }

            boolean success;
            if (Bukkit.isPrimaryThread()) {
                boolean wasOp = target.isOp();
                if (asOp) {
                    target.setOp(true);
                }
                success = Bukkit.dispatchCommand(target, command);
                if (asOp && !wasOp) {
                    target.setOp(false);
                }
            } else {
                Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), () -> {
                    boolean wasOp = target.isOp();
                    if (asOp) {
                        target.setOp(true);
                    }
                    Bukkit.dispatchCommand(target, command);
                    if (asOp && !wasOp) {
                        target.setOp(false);
                    }
                });
                success = true;
            }

            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "success", success);
            ctx.triggerOutput("flow");
        });

        registry.register("player_chat", (ctx, node) -> {
            Player target = ctx.getInputValue(node, "target", Player.class);
            String message = ctx.getInputValue(node, "message", String.class, "");
            if (target == null || message.isEmpty()) {
                ctx.triggerOutput("flow");
                return;
            }

            if (Bukkit.isPrimaryThread()) {
                target.chat(message);
            } else {
                Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), () -> target.chat(message));
            }
            ctx.triggerOutput("flow");
        });

        registry.register("player_say", (ctx, node) -> {
            Player target = ctx.getInputValue(node, "target", Player.class);
            String message = ctx.getInputValue(node, "message", String.class, "");
            if (target == null || message.isEmpty()) {
                ctx.triggerOutput("flow");
                return;
            }

            if (Bukkit.isPrimaryThread()) {
                target.chat("/say " + message);
            } else {
                Bukkit.getScheduler().runTask(restudio.resync.ReSync.getInstance(), () -> target.chat("/say " + message));
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
