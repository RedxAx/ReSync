package restudio.resync.flow.handler.generic;

import org.bukkit.Bukkit;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import restudio.flow.data.FlowNode;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.NodeHandler;
import restudio.resync.flow.util.TextFormatter;

import java.lang.management.ManagementFactory;
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
            if (message.isBlank()) throw new IllegalArgumentException("Broadcast message is required");
            Bukkit.broadcastMessage(TextFormatter.formatLegacy(message));
            ctx.setOutput(node, "sent_count", Bukkit.getOnlinePlayers().size());
        });

        operations.put("system_execute_command", (ctx, node) -> {
            String command = ctx.getInputValue(node, "command", String.class, "").trim();
            if (command.startsWith("/")) command = command.substring(1).trim();
            if (command.isBlank()) throw new IllegalArgumentException("Server command is required");
            ConsoleCommandSender console = Bukkit.getConsoleSender();
            boolean success = Bukkit.dispatchCommand(console, command);
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
            if (tps.length == 0) throw new IllegalStateException("Server TPS is unavailable");
            ctx.setOutput(node, "tps", tps[0]);
        });

        operations.put("system_get_mspt", (ctx, node) -> {
            double mspt = Bukkit.getAverageTickTime();
            ctx.setOutput(node, "mspt", mspt);
        });

        operations.put("system_restart", (ctx, node) -> {
            String reason = ctx.getInputValue(node, "reason", String.class, "Server restart");
            Bukkit.broadcastMessage(TextFormatter.formatLegacy("Server restarting: " + reason));
            ctx.runLater(() -> Bukkit.spigot().restart(), 20L);
        });

        operations.put("system_shutdown", (ctx, node) -> {
            String reason = ctx.getInputValue(node, "reason", String.class, "Server shutdown");
            Bukkit.broadcastMessage(TextFormatter.formatLegacy("Server shutting down: " + reason));
            ctx.runLater(Bukkit::shutdown, 20L);
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
            long uptime = ManagementFactory.getRuntimeMXBean().getUptime();
            ctx.setOutput(node, "uptime_seconds", uptime / 1000L);
        });

        operations.put("server_get_info", (ctx, node) -> {
            ctx.setOutput(node, "motd", Bukkit.getMotd());
            ctx.setOutput(node, "version", Bukkit.getVersion());
            ctx.setOutput(node, "online_players", Bukkit.getOnlinePlayers().size());
            ctx.setOutput(node, "max_players", Bukkit.getMaxPlayers());
        });

    }

    public void registerTo(HandlerRegistry registry) {
        registry.register("ServerHandler", this);
    }

    @Override
    public void execute(FlowContext ctx, FlowNode node) {
        String operation = node.getHandlerConfig().getString("operation");
        BiConsumer<FlowContext, FlowNode> op = operation != null ? operations.get(operation) : null;
        if (op == null) {
            throw new IllegalArgumentException("Unknown server operation: " + operation);
        }
        op.accept(ctx, node);
        ctx.triggerOutput("flow");
    }
}
