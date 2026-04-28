package restudio.resync.flow.handler.generic;

import org.bukkit.Bukkit;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import restudio.flow.data.FlowNode;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.NodeHandler;
import restudio.resync.ReSync;
import restudio.resync.flow.util.TextFormatter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

public class ServerHandler implements NodeHandler {
    private final Map<String, BiConsumer<FlowContext, FlowNode>> operations = new ConcurrentHashMap<>();

    public ServerHandler() {
        operations.put("system_broadcast", (ctx, node) -> {
            String message = ctx.getInputValue(node, "message", String.class, "");
            int sentCount = 0;
            if (!message.isEmpty()) {
                Bukkit.broadcastMessage(TextFormatter.formatLegacy(message));
                sentCount = Bukkit.getOnlinePlayers().size();
            }
            ctx.setOutput(node, "sent_count", sentCount);
        });

        operations.put("system_execute_command", (ctx, node) -> {
            String command = ctx.getInputValue(node, "command", String.class, "");
            boolean success = false;
            if (!command.isEmpty()) {
                ConsoleCommandSender console = Bukkit.getConsoleSender();
                success = Bukkit.dispatchCommand(console, command);
            }
            ctx.setOutput(node, "success", success);
        });

        operations.put("system_get_online_players", (ctx, node) -> {
            List<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());
            ctx.setOutput(node, "players", players);
        });

        operations.put("system_get_max_players", (ctx, node) -> {
            int maxPlayers = Bukkit.getMaxPlayers();
            ctx.setOutput(node, "max", maxPlayers);
        });

        operations.put("system_get_tps", (ctx, node) -> {
            double[] tps = Bukkit.getTPS();
            ctx.setOutput(node, "tps", tps);
        });

        operations.put("system_get_mspt", (ctx, node) -> {
            double mspt = Bukkit.getAverageTickTime();
            ctx.setOutput(node, "mspt", mspt);
        });

        operations.put("system_restart", (ctx, node) -> {
            String reason = ctx.getInputValue(node, "reason", String.class, "Server restart");
            Bukkit.broadcastMessage(TextFormatter.formatLegacy("Server restarting: " + reason));
            Bukkit.getScheduler().runTaskLater(ReSync.getInstance(), () -> Bukkit.spigot().restart(), 20L);
        });

        operations.put("system_shutdown", (ctx, node) -> {
            String reason = ctx.getInputValue(node, "reason", String.class, "Server shutdown");
            Bukkit.broadcastMessage(TextFormatter.formatLegacy("Server shutting down: " + reason));
            Bukkit.getScheduler().runTaskLater(ReSync.getInstance(), Bukkit::shutdown, 20L);
        });

        operations.put("system_get_motd", (ctx, node) -> {
            String motd = Bukkit.getMotd();
            ctx.setOutput(node, "motd", motd);
        });

        operations.put("system_set_motd", (ctx, node) -> {
            String motd = ctx.getInputValue(node, "motd", String.class, "");
            Bukkit.getServer().setMotd(motd);
        });

        operations.put("reload", (ctx, node) -> {
            Bukkit.reload();
        });

        operations.put("server_get_uptime", (ctx, node) -> {
            long uptime = java.lang.management.ManagementFactory.getRuntimeMXBean().getUptime();
            ctx.setOutput(node, "uptime_seconds", (int) (uptime / 1000));
        });

        operations.put("server_get_info", (ctx, node) -> {
            ctx.setOutput(node, "motd", org.bukkit.Bukkit.getMotd());
            ctx.setOutput(node, "version", org.bukkit.Bukkit.getVersion());
            ctx.setOutput(node, "online_players", org.bukkit.Bukkit.getOnlinePlayers().size());
            ctx.setOutput(node, "max_players", org.bukkit.Bukkit.getMaxPlayers());
        });

    }

    public void registerTo(HandlerRegistry registry) {
        registry.register("ServerHandler", this);
    }

    @Override
    public void execute(FlowContext ctx, FlowNode node) {
        String operation = node.getHandlerConfig().getString("operation");
        BiConsumer<FlowContext, FlowNode> op = operation != null ? operations.get(operation) : null;
        if (op != null) {
            op.accept(ctx, node);
        }
        ctx.triggerOutput("flow");
    }
}
