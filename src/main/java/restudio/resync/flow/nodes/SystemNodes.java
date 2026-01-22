package restudio.resync.flow.nodes;

import org.bukkit.Bukkit;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import restudio.flow.data.FlowNode;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.FlowRegistry;
import restudio.resync.flow.NodeCategory;
import restudio.resync.flow.util.TextFormatter;
import restudio.resync.ReSync;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SystemNodes implements NodeCategory {
    
    @Override
    public void registerNodes(FlowRegistry registry) {
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
            Bukkit.getScheduler().runTaskLater(ReSync.getInstance(), () -> {
                Bukkit.shutdown();
            }, 20L);
            
            ctx.triggerOutput("flow");
        });
        
        registry.register("server_restart", (ctx, node) -> {
            String reason = ctx.getInputValue(node, "reason", String.class, "Server restart");
            
            Bukkit.broadcastMessage(TextFormatter.formatLegacy("Server restarting: " + reason));
            Bukkit.getScheduler().runTaskLater(ReSync.getInstance(), () -> {
                Bukkit.spigot().restart();
            }, 20L);
            
            ctx.triggerOutput("flow");
        });
        
        registry.register("server_reload", (ctx, node) -> {
            boolean success = false;
            try {
                Bukkit.reload();
                success = true;
            } catch (Exception e) {
                Bukkit.getLogger().severe("Error during reload: " + e.getMessage());
                success = false;
            }
            
            String nodeId = findNodeId(ctx, node);
            ctx.setNodeOutput(nodeId, "success", success);
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
