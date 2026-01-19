package restudio.resync;

import org.bukkit.plugin.java.JavaPlugin;
import org.java_websocket.server.WebSocketServer;
import restudio.resync.server.ReSyncServer;
import restudio.resync.server.ConfigLoader;
import restudio.resync.server.ReSyncConfig;

import java.net.InetSocketAddress;

public class ReSync extends JavaPlugin {
    private static ReSync instance;
    private WebSocketServer wsServer;
    private ReSyncServer v2Server;

    @Override
    public void onEnable() {
        instance = this;

        ReSyncConfig config = ConfigLoader.load(getDataFolder().toPath().resolve("config.properties").toString());

        if (!config.isEnabled()) {
            getLogger().info("ReSync v2 is disabled in config.");
            return;
        }

        v2Server = new ReSyncServer(config);

        wsServer = new WebSocketServer(new InetSocketAddress(config.getPort())) {
            @Override
            public void onOpen(org.java_websocket.WebSocket conn, org.java_websocket.handshake.ClientHandshake handshake) {
                v2Server.onOpen(conn, handshake);
            }

            @Override
            public void onClose(org.java_websocket.WebSocket conn, int code, String reason, boolean remote) {
                v2Server.onClose(conn, code, reason, remote);
            }

            @Override
            public void onMessage(org.java_websocket.WebSocket conn, String message) {
                v2Server.onMessage(conn, java.nio.ByteBuffer.wrap(message.getBytes()));
            }

            @Override
            public void onMessage(org.java_websocket.WebSocket conn, java.nio.ByteBuffer message) {
                v2Server.onMessage(conn, message);
            }

            @Override
            public void onError(org.java_websocket.WebSocket conn, Exception ex) {
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
    }

    @Override
    public void onDisable() {
        if (v2Server != null) {
            v2Server.shutdown();
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
}
