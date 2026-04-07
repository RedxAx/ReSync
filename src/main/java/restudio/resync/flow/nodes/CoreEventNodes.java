package restudio.resync.flow.nodes;

import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import restudio.flow.data.FlowNode;
import restudio.flow.data.FlowType;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.registry.DefineNode;
import restudio.resync.flow.registry.FlowPin;
import restudio.resync.flow.registry.NodeDefinition;

public class CoreEventNodes {

    @DefineNode(id = "event:click", displayName = "On Click", category = NodeDefinition.NodeCategory.EVENT,
            inputs = {},
            outputs = {
                    @FlowPin(name = "player", type = NodeDefinition.PinType.DATA, dataType = FlowType.PLAYER),
                    @FlowPin(name = "slot", type = NodeDefinition.PinType.DATA, dataType = FlowType.NUMBER),
                    @FlowPin(name = "raw_slot", type = NodeDefinition.PinType.DATA, dataType = FlowType.NUMBER),
                    @FlowPin(name = "button", type = NodeDefinition.PinType.DATA, dataType = FlowType.NUMBER),
                    @FlowPin(name = "action", type = NodeDefinition.PinType.DATA, dataType = FlowType.STRING),
                    @FlowPin(name = "item", type = NodeDefinition.PinType.DATA, dataType = FlowType.ITEMSTACK),
                    @FlowPin(name = "cursor_item", type = NodeDefinition.PinType.DATA, dataType = FlowType.ITEMSTACK),
                    @FlowPin(name = "next", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "left", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "right", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "shift_left", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "shift_right", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "middle", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "double_click", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "drop", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "control_drop", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "number_key", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "creative", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "swap_offhand", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "window_border_left", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "window_border_right", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "unknown", type = NodeDefinition.PinType.FLOW),
            })
    public void eventClick(FlowContext ctx, FlowNode node) {
        Player player = ctx.getPlayer();
        ctx.setOutput(node, "player", player);
        if (ctx.getEvent() instanceof InventoryClickEvent clickEvent) {
            ctx.setOutput(node, "slot", clickEvent.getSlot());
            ctx.setOutput(node, "raw_slot", clickEvent.getRawSlot());
            ctx.setOutput(node, "button", clickEvent.getHotbarButton());
            ctx.setOutput(node, "action", clickEvent.getAction().name());
            ctx.setOutput(node, "item", clickEvent.getCurrentItem());
            ctx.setOutput(node, "cursor_item", clickEvent.getCursor());
            String outputPin = switch (clickEvent.getClick()) {
                case LEFT -> "left";
                case RIGHT -> "right";
                case SHIFT_LEFT -> "shift_left";
                case SHIFT_RIGHT -> "shift_right";
                case MIDDLE -> "middle";
                case DOUBLE_CLICK -> "double_click";
                case DROP -> "drop";
                case CONTROL_DROP -> "control_drop";
                case NUMBER_KEY -> "number_key";
                case CREATIVE -> "creative";
                case SWAP_OFFHAND -> "swap_offhand";
                case WINDOW_BORDER_LEFT -> "window_border_left";
                case WINDOW_BORDER_RIGHT -> "window_border_right";
                case UNKNOWN -> "unknown";
            };
            ctx.triggerOutput(outputPin);
            return;
        }
        ctx.triggerOutput("next");
    }

    @DefineNode(id = "event:chat", displayName = "On Chat", category = NodeDefinition.NodeCategory.EVENT,
            outputs = {
                    @FlowPin(name = "message", type = NodeDefinition.PinType.DATA, dataType = FlowType.STRING),
                    @FlowPin(name = "player", type = NodeDefinition.PinType.DATA, dataType = FlowType.PLAYER),
                    @FlowPin(name = "next", type = NodeDefinition.PinType.FLOW)
            })
    public void eventChat(FlowContext ctx, FlowNode node) {
        String message = (String) ctx.getVariable("event.message");
        ctx.setOutput(node, "message", message);
        ctx.setOutput(node, "player", ctx.getPlayer());
        ctx.triggerOutput("next");
    }

    @DefineNode(id = "event:join", displayName = "On Join", category = NodeDefinition.NodeCategory.EVENT,
            outputs = {
                    @FlowPin(name = "player", type = NodeDefinition.PinType.DATA, dataType = FlowType.PLAYER),
                    @FlowPin(name = "message", type = NodeDefinition.PinType.DATA, dataType = FlowType.STRING),
                    @FlowPin(name = "next", type = NodeDefinition.PinType.FLOW)
            })
    public void eventJoin(FlowContext ctx, FlowNode node) {
        ctx.setOutput(node, "player", ctx.getPlayer());
        ctx.setOutput(node, "message", ctx.getVariable("event.join_message"));
        ctx.triggerOutput("next");
    }

    @DefineNode(id = "event:quit", displayName = "On Quit", category = NodeDefinition.NodeCategory.EVENT,
            outputs = {
                    @FlowPin(name = "player", type = NodeDefinition.PinType.DATA, dataType = FlowType.PLAYER),
                    @FlowPin(name = "message", type = NodeDefinition.PinType.DATA, dataType = FlowType.STRING),
                    @FlowPin(name = "next", type = NodeDefinition.PinType.FLOW)
            })
    public void eventQuit(FlowContext ctx, FlowNode node) {
        ctx.setOutput(node, "player", ctx.getPlayer());
        ctx.setOutput(node, "message", ctx.getVariable("event.quit_message"));
        ctx.triggerOutput("next");
    }

    @DefineNode(id = "event:sneak", displayName = "On Sneak", category = NodeDefinition.NodeCategory.EVENT,
            outputs = {
                    @FlowPin(name = "player", type = NodeDefinition.PinType.DATA, dataType = FlowType.PLAYER),
                    @FlowPin(name = "is_sneaking", type = NodeDefinition.PinType.DATA, dataType = FlowType.BOOLEAN),
                    @FlowPin(name = "next", type = NodeDefinition.PinType.FLOW)
            })
    public void eventSneak(FlowContext ctx, FlowNode node) {
        ctx.setOutput(node, "player", ctx.getPlayer());
        ctx.setOutput(node, "is_sneaking", ctx.getVariable("event.is_sneaking"));
        ctx.triggerOutput("next");
    }

    @DefineNode(id = "event:death", displayName = "On Death", category = NodeDefinition.NodeCategory.EVENT,
            outputs = {
                    @FlowPin(name = "player", type = NodeDefinition.PinType.DATA, dataType = FlowType.PLAYER),
                    @FlowPin(name = "message", type = NodeDefinition.PinType.DATA, dataType = FlowType.STRING),
                    @FlowPin(name = "next", type = NodeDefinition.PinType.FLOW)
            })
    public void eventDeath(FlowContext ctx, FlowNode node) {
        ctx.setOutput(node, "player", ctx.getPlayer());
        ctx.setOutput(node, "message", ctx.getVariable("event.death_message"));
        ctx.triggerOutput("next");
    }

    @DefineNode(id = "event:block_break", displayName = "On Block Break", category = NodeDefinition.NodeCategory.EVENT,
            outputs = {
                    @FlowPin(name = "player", type = NodeDefinition.PinType.DATA, dataType = FlowType.PLAYER),
                    @FlowPin(name = "block_type", type = NodeDefinition.PinType.DATA, dataType = FlowType.STRING),
                    @FlowPin(name = "location", type = NodeDefinition.PinType.DATA, dataType = FlowType.LOCATION),
                    @FlowPin(name = "is_cancelled", type = NodeDefinition.PinType.DATA, dataType = FlowType.BOOLEAN),
                    @FlowPin(name = "next", type = NodeDefinition.PinType.FLOW)
            })
    public void eventBlockBreak(FlowContext ctx, FlowNode node) {
        Player player = ctx.getPlayer();
        Block block = (Block) ctx.getVariable("event.block");
        Boolean cancelled = (Boolean) ctx.getVariable("event.is_cancelled");
        ctx.setOutput(node, "player", player);
        if (block != null) {
            ctx.setOutput(node, "block_type", block.getType().name());
            ctx.setOutput(node, "location", block.getLocation());
        }
        ctx.setOutput(node, "is_cancelled", cancelled != null && cancelled);
        ctx.triggerOutput("next");
    }

    @DefineNode(id = "event:block_place", displayName = "On Block Place", category = NodeDefinition.NodeCategory.EVENT,
            outputs = {
                    @FlowPin(name = "player", type = NodeDefinition.PinType.DATA, dataType = FlowType.PLAYER),
                    @FlowPin(name = "block_type", type = NodeDefinition.PinType.DATA, dataType = FlowType.STRING),
                    @FlowPin(name = "location", type = NodeDefinition.PinType.DATA, dataType = FlowType.LOCATION),
                    @FlowPin(name = "against_type", type = NodeDefinition.PinType.DATA, dataType = FlowType.STRING),
                    @FlowPin(name = "is_cancelled", type = NodeDefinition.PinType.DATA, dataType = FlowType.BOOLEAN),
                    @FlowPin(name = "next", type = NodeDefinition.PinType.FLOW)
            })
    public void eventBlockPlace(FlowContext ctx, FlowNode node) {
        Player player = ctx.getPlayer();
        Block block = (Block) ctx.getVariable("event.block");
        Block against = (Block) ctx.getVariable("event.placed_against");
        Boolean cancelled = (Boolean) ctx.getVariable("event.is_cancelled");
        ctx.setOutput(node, "player", player);
        if (block != null) {
            ctx.setOutput(node, "block_type", block.getType().name());
            ctx.setOutput(node, "location", block.getLocation());
        }
        if (against != null) {
            ctx.setOutput(node, "against_type", against.getType().name());
        }
        ctx.setOutput(node, "is_cancelled", cancelled != null && cancelled);
        ctx.triggerOutput("next");
    }
}
