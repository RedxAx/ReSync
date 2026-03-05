package restudio.resync.flow;

import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowNode;
import restudio.resync.flow.util.TextFormatter;

public class StandardNodes {
    public static void registerAll(FlowRegistry registry) {
        registerEventNodes(registry);
        registerUtilityNodes(registry);
        restudio.resync.flow.nodes.PlayerNodes.registerAll(registry);
        restudio.resync.flow.nodes.WorldNodes.registerAll(registry);
        new restudio.resync.flow.nodes.LogicNodes().registerNodes(registry);
        new restudio.resync.flow.nodes.MathNodes().registerNodes(registry);
        new restudio.resync.flow.nodes.FlowControlNodes().registerNodes(registry);
        registerVariableNodes(registry);
        registerInventoryNodes(registry);
        new restudio.resync.flow.nodes.StringNodes().registerNodes(registry);
        new restudio.resync.flow.nodes.TextFormattingNodes().registerNodes(registry);
        new restudio.resync.flow.nodes.ListNodes().registerNodes(registry);
        new restudio.resync.flow.nodes.ListTransformNodes().registerNodes(registry);
        new restudio.resync.flow.nodes.PlayerActionNodes().registerNodes(registry);
        new restudio.resync.flow.nodes.PlayerInventoryNodes().registerNodes(registry);
        new restudio.resync.flow.nodes.PlayerMessagingNodes().registerNodes(registry);
        new restudio.resync.flow.nodes.EntitySpawnNodes().registerNodes(registry);
        new restudio.resync.flow.nodes.EntityControlNodes().registerNodes(registry);
        new restudio.resync.flow.nodes.EntityQueryNodes().registerNodes(registry);
        new restudio.resync.flow.nodes.BlockNodes().registerNodes(registry);
        new restudio.resync.flow.nodes.RegionNodes().registerNodes(registry);
        new restudio.resync.flow.nodes.WorldStateNodes().registerNodes(registry);
        new restudio.resync.flow.nodes.InventoryNodes().registerNodes(registry);
        new restudio.resync.flow.nodes.ItemCreationNodes().registerNodes(registry);
        new restudio.resync.flow.nodes.MenuNodes().registerNodes(registry);
        new restudio.resync.flow.nodes.PlayerEventNodes().registerNodes(registry);
        new restudio.resync.flow.nodes.EntityEventNodes().registerNodes(registry);
        new restudio.resync.flow.nodes.WorldEventNodes().registerNodes(registry);
        new restudio.resync.flow.nodes.VariableNodes().registerNodes(registry);
        new restudio.resync.flow.nodes.FileNodes().registerNodes(registry);
        new restudio.resync.flow.nodes.JsonNodes().registerNodes(registry);
        new restudio.resync.flow.nodes.TimeNodes().registerNodes(registry);
        new restudio.resync.flow.nodes.RandomNodes().registerNodes(registry);
        new restudio.resync.flow.nodes.ConversionNodes().registerNodes(registry);
        new restudio.resync.flow.nodes.DebugNodes().registerNodes(registry);
        new restudio.resync.flow.nodes.SystemNodes().registerNodes(registry);
        new restudio.resync.flow.nodes.ScoreboardNodes().registerNodes(registry);
        new restudio.resync.flow.nodes.TeamNodes().registerNodes(registry);
        new restudio.resync.flow.nodes.SoundNodes().registerNodes(registry);
        new restudio.resync.flow.nodes.ParticleNodes().registerNodes(registry);
        new restudio.resync.flow.nodes.TitleNodes().registerNodes(registry);
        new restudio.resync.flow.nodes.SystemEventNodes().registerNodes(registry);
        new restudio.resync.flow.nodes.CustomEventNodes().registerNodes(registry);
        new restudio.resync.flow.nodes.EconomyNodes().registerNodes(registry);
        new restudio.resync.flow.nodes.PermissionNodes().registerNodes(registry);
        new restudio.resync.flow.nodes.LocationNodes().registerNodes(registry);
        new restudio.resync.flow.nodes.EntityAdvancedNodes().registerNodes(registry);
        new restudio.resync.flow.nodes.ItemNodes().registerNodes(registry);
        new restudio.resync.flow.nodes.PlayerQueryNodes().registerNodes(registry);
        new restudio.resync.flow.nodes.MathAdvancedNodes().registerNodes(registry);
        new restudio.resync.flow.nodes.StringAdvancedNodes().registerNodes(registry);
        new restudio.resync.flow.nodes.ListAdvancedNodes().registerNodes(registry);
        new restudio.resync.flow.nodes.UtilityNodes().registerNodes(registry);
        new restudio.resync.flow.nodes.RegionAdvancedNodes().registerNodes(registry);
        new restudio.resync.flow.nodes.DataStructureNodes().registerNodes(registry);
        new restudio.resync.flow.nodes.HttpNodes().registerNodes(registry);
        new restudio.resync.flow.nodes.DiscordNodes().registerNodes(registry);
    }

    private static void registerEventNodes(FlowRegistry registry) {
        registry.register("event:click", (ctx, node) -> {
            String nodeId = findNodeId(ctx, node);
            Player player = ctx.getPlayer();

            ctx.setNodeOutput(nodeId, "player", player);
            if (ctx.getEvent() instanceof InventoryClickEvent clickEvent) {
                ctx.setNodeOutput(nodeId, "slot", clickEvent.getSlot());
                ctx.setNodeOutput(nodeId, "raw_slot", clickEvent.getRawSlot());
                ctx.setNodeOutput(nodeId, "button", clickEvent.getHotbarButton());
                ctx.setNodeOutput(nodeId, "action", clickEvent.getAction().name());
                ctx.setNodeOutput(nodeId, "item", clickEvent.getCurrentItem());
                ctx.setNodeOutput(nodeId, "cursor_item", clickEvent.getCursor());

                ClickType clickType = clickEvent.getClick();
                String outputPin = switch (clickType) {
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
        });

        registry.register("event:chat", (ctx, node) -> {
            String nodeId = findNodeId(ctx, node);

            String message = (String)ctx.getVariable("event.message");
            Player player = ctx.getPlayer();

            ctx.setNodeOutput(nodeId, "message", message);
            ctx.setNodeOutput(nodeId, "player", player);
            ctx.triggerOutput("next");
        });

        registry.register("event:join", (ctx, node) -> {
            String nodeId = findNodeId(ctx, node);

            Player player = ctx.getPlayer();
            String joinMessage = (String)ctx.getVariable("event.join_message");

            ctx.setNodeOutput(nodeId, "player", player);
            ctx.setNodeOutput(nodeId, "message", joinMessage);
            ctx.triggerOutput("next");
        });

        registry.register("event:quit", (ctx, node) -> {
            String nodeId = findNodeId(ctx, node);

            Player player = ctx.getPlayer();
            String quitMessage = (String)ctx.getVariable("event.quit_message");

            ctx.setNodeOutput(nodeId, "player", player);
            ctx.setNodeOutput(nodeId, "message", quitMessage);
            ctx.triggerOutput("next");
        });

        registry.register("event:sneak", (ctx, node) -> {
            String nodeId = findNodeId(ctx, node);

            Player player = ctx.getPlayer();
            Boolean isSneaking = (Boolean)ctx.getVariable("event.is_sneaking");

            ctx.setNodeOutput(nodeId, "player", player);
            ctx.setNodeOutput(nodeId, "is_sneaking", isSneaking);
            ctx.triggerOutput("next");
        });

        registry.register("event:death", (ctx, node) -> {
            String nodeId = findNodeId(ctx, node);

            Player player = ctx.getPlayer();
            String deathMessage = (String)ctx.getVariable("event.death_message");

            ctx.setNodeOutput(nodeId, "player", player);
            ctx.setNodeOutput(nodeId, "message", deathMessage);
            ctx.triggerOutput("next");
        });

        registry.register("event:block_break", (ctx, node) -> {
            String nodeId = findNodeId(ctx, node);
            Player player = ctx.getPlayer();
            Block block = (Block) ctx.getVariable("event.block");
            Boolean cancelled = (Boolean) ctx.getVariable("event.is_cancelled");

            ctx.setNodeOutput(nodeId, "player", player);
            if (block != null) {
                ctx.setNodeOutput(nodeId, "block_type", block.getType().name());
                ctx.setNodeOutput(nodeId, "location", block.getLocation());
            }
            ctx.setNodeOutput(nodeId, "is_cancelled", cancelled != null && cancelled);
            ctx.triggerOutput("next");
        });

        registry.register("event:block_place", (ctx, node) -> {
            String nodeId = findNodeId(ctx, node);
            Player player = ctx.getPlayer();
            Block block = (Block) ctx.getVariable("event.block");
            Block against = (Block) ctx.getVariable("event.placed_against");
            Boolean cancelled = (Boolean) ctx.getVariable("event.is_cancelled");

            ctx.setNodeOutput(nodeId, "player", player);
            if (block != null) {
                ctx.setNodeOutput(nodeId, "block_type", block.getType().name());
                ctx.setNodeOutput(nodeId, "location", block.getLocation());
            }
            if (against != null) {
                ctx.setNodeOutput(nodeId, "against_type", against.getType().name());
            }
            ctx.setNodeOutput(nodeId, "is_cancelled", cancelled != null && cancelled);
            ctx.triggerOutput("next");
        });
    }

    private static void registerUtilityNodes(FlowRegistry registry) {
        registry.register("log", (ctx, node) -> {
            Object text = ctx.getInputValue(node, "text", String.class, "");
            Bukkit.getLogger().info("[Flow] " + text);
            ctx.triggerOutput("flow");
        });

        registry.register("cancel_event", (ctx, node) -> {
            Boolean cancel = ctx.getInputValue(node, "cancel", Boolean.class, true);
            if (Boolean.TRUE.equals(cancel) && ctx.getEvent() instanceof Cancellable cancellable) {
                cancellable.setCancelled(true);
            }
            ctx.triggerOutput("flow");
        });

        registry.register("delay", (ctx, node) -> {
            Object ticksObj = ctx.getInputValue(node, "ticks", Integer.class, 20);
            int ticks = (int)ticksObj;
            
            String nodeId = findNodeId(ctx, node);
            if (nodeId != null) {
                ctx.setNodeOutput(nodeId, "done", true);
            }
            
            ctx.runLater(() -> ctx.triggerOutput("flow"), ticks);
        });
    }

    private static void registerPlayerNodes(FlowRegistry registry) {
        registry.register("player_message", (ctx, node) -> {
            Object text = ctx.getInputValue(node, "text", String.class, "");
            if (ctx.getPlayer() != null) {
                ctx.getPlayer().sendMessage(TextFormatter.parse(String.valueOf(text)));
            }
            ctx.triggerOutput("flow");
        });

        registry.register("player_teleport", (ctx, node) -> {
            if (ctx.getPlayer() == null) {
                ctx.triggerOutput("flow");
                return;
            }

            Object xObj = ctx.getInputValue(node, "x", Double.class, 0.0);
            Object yObj = ctx.getInputValue(node, "y", Double.class, 0.0);
            Object zObj = ctx.getInputValue(node, "z", Double.class, 0.0);
            Object yawObj = ctx.getInputValue(node, "yaw", Float.class, 0.0f);
            Object pitchObj = ctx.getInputValue(node, "pitch", Float.class, 0.0f);

            Location loc = ctx.getPlayer().getLocation();
            loc.setX((double)xObj);
            loc.setY((double)yObj);
            loc.setZ((double)zObj);
            loc.setYaw((float)yawObj);
            loc.setPitch((float)pitchObj);

            ctx.getPlayer().teleport(loc);
            ctx.triggerOutput("flow");
        });

        registry.register("player_health", (ctx, node) -> {
            if (ctx.getPlayer() == null) return;

            Object healthObj = ctx.getInputValue(node, "health", Double.class, null);
            if (healthObj != null) {
                ctx.getPlayer().setHealth((double)healthObj);
            }

            ctx.setNodeOutput(findNodeId(ctx, node), "health", ctx.getPlayer().getHealth());
            ctx.triggerOutput("flow");
        });

        registry.register("player_gamemode", (ctx, node) -> {
            if (ctx.getPlayer() == null) return;

            Object modeObj = ctx.getInputValue(node, "mode", String.class, "SURVIVAL");
            GameMode mode = GameMode.valueOf(((String)modeObj).toUpperCase());

            ctx.getPlayer().setGameMode(mode);
            ctx.triggerOutput("flow");
        });
    }

    private static void registerWorldNodes(FlowRegistry registry) {
        registry.register("give_item", (ctx, node) -> {
            Object matObj = ctx.getInputValue(node, "material", String.class, "STONE");
            Object amtObj = ctx.getInputValue(node, "amount", Integer.class, 1);

            Material mat = Material.getMaterial(((String)matObj).toUpperCase());
            int amount = (int)amtObj;

            if (mat != null && ctx.getPlayer() != null) {
                ctx.getPlayer().getInventory().addItem(new ItemStack(mat, amount));
            }
            ctx.triggerOutput("flow");
        });

        registry.register("set_block", (ctx, node) -> {
            if (ctx.getPlayer() == null) return;

            Object matObj = ctx.getInputValue(node, "material", String.class, "STONE");
            Object relativeObj = ctx.getInputValue(node, "relative", Boolean.class, true);
            Object xOffObj = ctx.getInputValue(node, "x_offset", Integer.class, 0);
            Object yOffObj = ctx.getInputValue(node, "y_offset", Integer.class, 0);
            Object zOffObj = ctx.getInputValue(node, "z_offset", Integer.class, 0);

            Material mat = Material.getMaterial(((String)matObj).toUpperCase());
            if (mat == null) return;

            Location loc = ctx.getPlayer().getLocation();
            if ((boolean)relativeObj) {
                loc.add((int)xOffObj, (int)yOffObj, (int)zOffObj);
            } else {
                loc.setX((int)xOffObj);
                loc.setY((int)yOffObj);
                loc.setZ((int)zOffObj);
            }

            Block block = loc.getBlock();
            block.setType(mat);

            ctx.triggerOutput("flow");
        });

        registry.register("get_block", (ctx, node) -> {
            if (ctx.getPlayer() == null) return;

            Object relativeObj = ctx.getInputValue(node, "relative", Boolean.class, true);
            Object xOffObj = ctx.getInputValue(node, "x_offset", Integer.class, 0);
            Object yOffObj = ctx.getInputValue(node, "y_offset", Integer.class, 0);
            Object zOffObj = ctx.getInputValue(node, "z_offset", Integer.class, 0);

            Location loc = ctx.getPlayer().getLocation();
            if ((boolean)relativeObj) {
                loc.add((int)xOffObj, (int)yOffObj, (int)zOffObj);
            } else {
                loc.setX((int)xOffObj);
                loc.setY((int)yOffObj);
                loc.setZ((int)zOffObj);
            }

            Block block = loc.getBlock();
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "type", block.getType().name());
            ctx.setNodeOutput(nodeId, "location", loc.toString());
            ctx.triggerOutput("flow");
        });

        registry.register("explosion", (ctx, node) -> {
            Object powerObj = ctx.getInputValue(node, "power", Float.class, 4.0f);
            Object setFireObj = ctx.getInputValue(node, "set_fire", Boolean.class, false);
            Object breakBlocksObj = ctx.getInputValue(node, "break_blocks", Boolean.class, true);
            Object relativeObj = ctx.getInputValue(node, "relative", Boolean.class, true);
            Object xOffObj = ctx.getInputValue(node, "x_offset", Integer.class, 0);
            Object yOffObj = ctx.getInputValue(node, "y_offset", Integer.class, 0);
            Object zOffObj = ctx.getInputValue(node, "z_offset", Integer.class, 0);

            Location loc = ctx.getPlayer() != null ? ctx.getPlayer().getLocation() : new Location(Bukkit.getWorlds().get(0), 0, 0, 0);
            if ((boolean)relativeObj) {
                loc.add((int)xOffObj, (int)yOffObj, (int)zOffObj);
            } else {
                loc.setX((int)xOffObj);
                loc.setY((int)yOffObj);
                loc.setZ((int)zOffObj);
            }

            loc.getWorld().createExplosion(
                loc.getX() + 0.5,
                loc.getY() + 0.5,
                loc.getZ() + 0.5,
                (float)powerObj,
                (boolean)setFireObj,
                (boolean)breakBlocksObj
            );

            ctx.triggerOutput("flow");
        });
    }

    private static void registerMathNodes(FlowRegistry registry) {
        registry.register("number", (ctx, node) -> {
            Object value = ctx.getInputValue(node, "value", Double.class, 0.0);
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "value", value);
            ctx.triggerOutput("flow");
        });

        registry.register("string", (ctx, node) -> {
            Object value = ctx.getInputValue(node, "value", String.class, "");
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "value", value);
            ctx.triggerOutput("flow");
        });

        registry.register("boolean", (ctx, node) -> {
            Object value = ctx.getInputValue(node, "value", Boolean.class, false);
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "value", value);
            ctx.triggerOutput("flow");
        });

        registry.register("add", (ctx, node) -> {
            Object a = ctx.getInputValue(node, "a", Double.class, 0.0);
            Object b = ctx.getInputValue(node, "b", Double.class, 0.0);
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "result", (double)a + (double)b);
            ctx.triggerOutput("flow");
        });

        registry.register("subtract", (ctx, node) -> {
            Object a = ctx.getInputValue(node, "a", Double.class, 0.0);
            Object b = ctx.getInputValue(node, "b", Double.class, 0.0);
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "result", (double)a - (double)b);
            ctx.triggerOutput("flow");
        });

        registry.register("multiply", (ctx, node) -> {
            Object a = ctx.getInputValue(node, "a", Double.class, 0.0);
            Object b = ctx.getInputValue(node, "b", Double.class, 0.0);
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "result", (double)a * (double)b);
            ctx.triggerOutput("flow");
        });

        registry.register("divide", (ctx, node) -> {
            Object a = ctx.getInputValue(node, "a", Double.class, 0.0);
            Object b = ctx.getInputValue(node, "b", Double.class, 1.0);
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "result", (double)a / (double)b);
            ctx.triggerOutput("flow");
        });

        registry.register("if", (ctx, node) -> {
            Object condition = ctx.getInputValue(node, "condition", Boolean.class, false);
            
            if ((boolean)condition) {
                ctx.triggerOutput("true");
            } else {
                ctx.triggerOutput("false");
            }
        });

        registry.register("equals", (ctx, node) -> {
            Object a = ctx.getInputValue(node, "a", null);
            Object b = ctx.getInputValue(node, "b", null);

            boolean equal = (a != null && a.equals(b)) || (a == null && b == null);
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "result", equal);
            ctx.triggerOutput("flow");
        });

        registry.register("not_equals", (ctx, node) -> {
            Object a = ctx.getInputValue(node, "a", null);
            Object b = ctx.getInputValue(node, "b", null);

            boolean notEqual = !((a != null && a.equals(b)) || (a == null && b == null));
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "result", notEqual);
            ctx.triggerOutput("flow");
        });

        registry.register("contains", (ctx, node) -> {
            Object stringObj = ctx.getInputValue(node, "string", String.class, "");
            Object substringObj = ctx.getInputValue(node, "substring", String.class, "");

            String string = (String)stringObj;
            String substring = (String)substringObj;

            boolean contains = string != null && substring != null && string.contains(substring);
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "result", contains);
            ctx.triggerOutput("flow");
        });
    }

    private static void registerVariableNodes(FlowRegistry registry) {
        registry.register("get_variable", (ctx, node) -> {
            Object nameObj = ctx.getInputValue(node, "name", String.class, "");
            String name = (String)nameObj;
            
            Object value = ctx.getVariable(name);
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "value", value);
            ctx.triggerOutput("flow");
        });

        registry.register("set_variable", (ctx, node) -> {
            Object nameObj = ctx.getInputValue(node, "name", String.class, "");
            Object value = ctx.getInputValue(node, "value", null);
            
            ctx.setVariable((String)nameObj, value);
            ctx.triggerOutput("flow");
        });

        registry.register("get_server_var", (ctx, node) -> {
            Object nameObj = ctx.getInputValue(node, "name", String.class, "");
            String name = (String)nameObj;
            
            Object value = ctx.getGlobalVariables().get("server." + name);
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "value", value);
            ctx.triggerOutput("flow");
        });

        registry.register("call_function", (ctx, node) -> {
            Object functionNameObj = ctx.getInputValue(node, "function", String.class, "");
            String functionName = (String)functionNameObj;
            
            FlowStorage storage = new FlowStorage(restudio.resync.ReSync.getInstance());
            FlowGraph functionGraph = storage.getGraph(functionName);
            
            if (functionGraph != null) {
                String returnNodeId = findNodeId(ctx, node);
                ctx.getRuntime().callFunction(functionGraph, returnNodeId);
            } else {
                System.err.println("[Flow] Function not found: " + functionName);
                ctx.triggerOutput("flow");
            }
        });

        registry.register("return", (ctx, node) -> {
            Object returnValue = ctx.getInputValue(node, "value", null);
            
            if (ctx.getRuntime().returnFromFunction(returnValue)) {
                ctx.triggerOutput("flow");
            } else {
                System.err.println("[Flow] return called outside function");
                ctx.triggerOutput("flow");
            }
        });
    }

    private static void registerInventoryNodes(FlowRegistry registry) {
        registry.register("player_has_item", (ctx, node) -> {
            if (ctx.getPlayer() == null) return;

            Object matObj = ctx.getInputValue(node, "material", String.class, "STONE");
            Material mat = Material.getMaterial(((String)matObj).toUpperCase());
            if (mat == null) return;

            PlayerInventory inv = ctx.getPlayer().getInventory();
            boolean hasItem = false;
            int count = 0;
            
            for (ItemStack item : inv.getContents()) {
                if (item != null && item.getType() == mat) {
                    hasItem = true;
                    count += item.getAmount();
                }
            }
            
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "has", hasItem);
            ctx.setNodeOutput(nodeId, "count", count);
            ctx.triggerOutput("flow");
        });

        registry.register("player_remove_item", (ctx, node) -> {
            if (ctx.getPlayer() == null) return;

            Object matObj = ctx.getInputValue(node, "material", String.class, "STONE");
            Object amtObj = ctx.getInputValue(node, "amount", Integer.class, 1);

            Material mat = Material.getMaterial(((String)matObj).toUpperCase());
            if (mat == null) return;

            ItemStack toRemove = new ItemStack(mat, (int)amtObj);
            ctx.getPlayer().getInventory().removeItem(toRemove);
            
            ctx.triggerOutput("flow");
        });

        registry.register("player_clear_inv", (ctx, node) -> {
            if (ctx.getPlayer() == null) return;

            ctx.getPlayer().getInventory().clear();
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
