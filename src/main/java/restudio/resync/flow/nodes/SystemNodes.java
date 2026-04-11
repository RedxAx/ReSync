package restudio.resync.flow.nodes;

import org.bukkit.Bukkit;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import restudio.flow.data.FlowNode;
import restudio.flow.data.FlowType;
import restudio.resync.Log;
import restudio.resync.ReSync;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.FlowRegistry;
import restudio.resync.flow.registry.DefineNode;
import restudio.resync.flow.registry.FlowPin;
import restudio.resync.flow.registry.NodeDefinition;
import restudio.resync.flow.util.TextFormatter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

public class SystemNodes {

    private static final Map<String, BiConsumer<FlowContext, FlowNode>> LEGACY_EXECUTORS = new ConcurrentHashMap<>();
    private static volatile boolean initialized;

    private static void registerLegacyNodes(FlowRegistry registry) {
        registry.register("server_get_info", (ctx, node) -> {
            Map<String, Object> info = new HashMap<>();
            info.put("name", Bukkit.getServer().getName());
            info.put("version", Bukkit.getVersion());
            info.put("bukkit_version", Bukkit.getBukkitVersion());
            info.put("minecraft_version", Bukkit.getMinecraftVersion());
            info.put("online_mode", Bukkit.getOnlineMode());
            info.put("max_players", Bukkit.getMaxPlayers());
            info.put("online_count", Bukkit.getOnlinePlayers().size());
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "info", info);
            ctx.triggerOutput("flow");
        });

        registry.register("server_get_online_players", (ctx, node) -> {
            List<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "players", players);
            ctx.triggerOutput("flow");
        });

        registry.register("server_get_max_players", (ctx, node) -> {
            int maxPlayers = Bukkit.getMaxPlayers();
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "max", maxPlayers);
            ctx.triggerOutput("flow");
        });

        registry.register("server_execute_command", (ctx, node) -> {
            String command = ctx.getInputValue(node, "command", String.class, "");
            boolean success = false;
            if (!command.isEmpty()) {
                ConsoleCommandSender console = Bukkit.getConsoleSender();
                success = Bukkit.dispatchCommand(console, command);
            }
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "success", success);
            ctx.triggerOutput("flow");
        });

        registry.register("server_broadcast", (ctx, node) -> {
            String message = ctx.getInputValue(node, "message", String.class, "");
            int sentCount = 0;
            if (!message.isEmpty()) {
                Bukkit.broadcastMessage(TextFormatter.formatLegacy(message));
                sentCount = Bukkit.getOnlinePlayers().size();
            }
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "sent_count", sentCount);
            ctx.triggerOutput("flow");
        });

        registry.register("server_shutdown", (ctx, node) -> {
            String reason = ctx.getInputValue(node, "reason", String.class, "Server shutdown");
            Bukkit.broadcastMessage(TextFormatter.formatLegacy("Server shutting down: " + reason));
            Bukkit.getScheduler().runTaskLater(ReSync.getInstance(), Bukkit::shutdown, 20L);
            ctx.triggerOutput("flow");
        });

        registry.register("server_restart", (ctx, node) -> {
            String reason = ctx.getInputValue(node, "reason", String.class, "Server restart");
            Bukkit.broadcastMessage(TextFormatter.formatLegacy("Server restarting: " + reason));
            Bukkit.getScheduler().runTaskLater(ReSync.getInstance(), () -> Bukkit.spigot().restart(), 20L);
            ctx.triggerOutput("flow");
        });

        registry.register("server_reload", (ctx, node) -> {
            boolean success;
            try {
                Bukkit.reload();
                success = true;
            } catch (Exception e) {
                Log.error("Error during reload: " + e.getMessage());
                success = false;
            }
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "success", success);
            ctx.triggerOutput("flow");
        });
    }

    public void registerNodes(FlowRegistry registry) {
        registerLegacyNodes(registry);
    }

    private static void ensureLegacyInitialized() {
        if (initialized) {
            return;
        }
        synchronized (SystemNodes.class) {
            if (initialized) {
                return;
            }
            FlowRegistry legacyRegistry = new FlowRegistry();
            registerLegacyNodes(legacyRegistry);
            for (String type : legacyRegistry.getRegisteredTypes()) {
                LEGACY_EXECUTORS.put(type, legacyRegistry.getExecutor(type));
            }
            initialized = true;
        }
    }

    private void executeLegacy(String id, FlowContext ctx, FlowNode node) {
        ensureLegacyInitialized();
        BiConsumer<FlowContext, FlowNode> executor = LEGACY_EXECUTORS.get(id);
        if (executor == null) {
            ctx.triggerOutput("flow");
            return;
        }
        executor.accept(ctx, node);
    }

    @DefineNode(id = "server_get_info", displayName = "Server Info", category = NodeDefinition.NodeCategory.UTILITY,
            inputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)},
            outputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "info", dataType = FlowType.JSON_OBJECT)
            })
    public void serverGetInfo(FlowContext ctx, FlowNode node) {
        executeLegacy("server_get_info", ctx, node);
    }

    @DefineNode(id = "server_get_online_players", displayName = "Get Online Players", category = NodeDefinition.NodeCategory.UTILITY,
            inputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)},
            outputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "players", dataType = FlowType.LIST)
            })
    public void serverGetOnlinePlayers(FlowContext ctx, FlowNode node) {
        executeLegacy("server_get_online_players", ctx, node);
    }

    @DefineNode(id = "server_get_max_players", displayName = "Get Max Players", category = NodeDefinition.NodeCategory.UTILITY,
            inputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)},
            outputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "max", dataType = FlowType.NUMBER)
            })
    public void serverGetMaxPlayers(FlowContext ctx, FlowNode node) {
        executeLegacy("server_get_max_players", ctx, node);
    }

    @DefineNode(id = "server_execute_command", displayName = "Execute Console Command", category = NodeDefinition.NodeCategory.UTILITY,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "command", dataType = FlowType.STRING)
            },
            outputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "success", dataType = FlowType.BOOLEAN)
            })
    public void serverExecuteCommand(FlowContext ctx, FlowNode node) {
        executeLegacy("server_execute_command", ctx, node);
    }

    @DefineNode(id = "server_broadcast", displayName = "Broadcast Message", category = NodeDefinition.NodeCategory.UTILITY,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "message", dataType = FlowType.STRING)
            },
            outputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "sent_count", dataType = FlowType.NUMBER)
            })
    public void serverBroadcast(FlowContext ctx, FlowNode node) {
        executeLegacy("server_broadcast", ctx, node);
    }

    @DefineNode(id = "server_shutdown", displayName = "Server Shutdown", category = NodeDefinition.NodeCategory.UTILITY,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "reason", dataType = FlowType.STRING)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void serverShutdown(FlowContext ctx, FlowNode node) {
        executeLegacy("server_shutdown", ctx, node);
    }

    @DefineNode(id = "server_restart", displayName = "Server Restart", category = NodeDefinition.NodeCategory.UTILITY,
            inputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "reason", dataType = FlowType.STRING)
            },
            outputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)})
    public void serverRestart(FlowContext ctx, FlowNode node) {
        executeLegacy("server_restart", ctx, node);
    }

    @DefineNode(id = "server_reload", displayName = "Server Reload", category = NodeDefinition.NodeCategory.UTILITY,
            inputs = {@FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW)},
            outputs = {
                    @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW),
                    @FlowPin(name = "success", dataType = FlowType.BOOLEAN)
            })
    public void serverReload(FlowContext ctx, FlowNode node) {
        executeLegacy("server_reload", ctx, node);
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
