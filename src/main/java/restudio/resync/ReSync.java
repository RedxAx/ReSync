package restudio.resync;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import restudio.resync.commands.ReSyncCommand;
import restudio.resync.placeholder.ReSyncPlaceholderExpansion;
import restudio.resync.server.ReSyncServer;
import restudio.resync.server.ConfigLoader;
import restudio.resync.server.ReSyncConfig;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;

public class ReSync extends JavaPlugin {
    private static ReSync instance;
    private WebSocketServer wsServer;
    private ReSyncServer v2Server;
    private ReSyncPlaceholderExpansion placeholderExpansion;

    @Override
    public void onEnable() {
        instance = this;

        ReSyncConfig config = ConfigLoader.load(getDataFolder().toPath().resolve("config.properties").toString());

        if (!config.isEnabled()) {
            getLogger().info("ReSync v2 is disabled in config.");
            return;
        }

        v2Server = new ReSyncServer(this, config);

        wsServer = new WebSocketServer(new InetSocketAddress(config.getPort())) {
            @Override
            public void onOpen(WebSocket conn, ClientHandshake handshake) {
                v2Server.onOpen(conn, handshake);
            }

            @Override
            public void onClose(WebSocket conn, int code, String reason, boolean remote) {
                v2Server.onClose(conn, code, reason, remote);
            }

            @Override
            public void onMessage(WebSocket conn, String message) {
                v2Server.onMessage(conn, ByteBuffer.wrap(message.getBytes()));
            }

            @Override
            public void onMessage(WebSocket conn, ByteBuffer message) {
                v2Server.onMessage(conn, message);
            }

            @Override
            public void onError(WebSocket conn, Exception ex) {
                getLogger().severe("[ReSync] WebSocket error: " + ex.getMessage());
                ex.printStackTrace();
            }

            @Override
            public void onStart() {
                getLogger().info("[ReSync v2] Server started on port " + getPort());
            }
        };

        try {
            wsServer.start();
            getLogger().info("[ReSync v2] Server enabled on port " + config.getPort());
        } catch (Exception e) {
            getLogger().severe("[ReSync v2] Failed to start WebSocket server: " + e.getMessage());
            e.printStackTrace();
        }

        if (getCommand("resync") != null) {
            ReSyncCommand command = new ReSyncCommand(this);
            getCommand("resync").setExecutor(command);
            getCommand("resync").setTabCompleter(command);
        } else {
            getLogger().warning("[ReSync v2] Command '/resync' not found in plugin.yml");
        }

        registerPlaceholderExpansion();
    }

    @Override
    public void onDisable() {
        if (v2Server != null) {
            v2Server.shutdown();
        }
        if (placeholderExpansion != null) {
            placeholderExpansion.unregister();
            placeholderExpansion = null;
        }

        if (wsServer != null) {
            try {
                wsServer.stop();
                getLogger().info("[ReSync v2] WebSocket server stopped.");
            } catch (Exception e) {
                getLogger().severe("[ReSync v2] Error stopping WebSocket server: " + e.getMessage());
            }
        }

        getLogger().info("[ReSync v2] Plugin disabled.");
    }

    public static ReSync getInstance() {
        return instance;
    }

    public ReSyncServer getV2Server() {
        return v2Server;
    }

    private void registerPlaceholderExpansion() {
        if (!Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            getLogger().info("[ReSync v2] PlaceholderAPI not found, skipping PAPI hook registration.");
            return;
        }
        placeholderExpansion = new ReSyncPlaceholderExpansion(this);
        if (placeholderExpansion.register()) {
            getLogger().info("[ReSync v2] Registered PlaceholderAPI hook: %resync_*%");
            return;
        }
        getLogger().warning("[ReSync v2] Failed to register PlaceholderAPI hook.");
    }
}
