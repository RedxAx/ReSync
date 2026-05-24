package restudio.resync;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import restudio.resync.commands.ReSyncCommand;
import restudio.resync.bridge.ReSyncPluginMessageBridge;
import restudio.resync.selection.InteractiveSelectionManager;
import restudio.resync.server.ReSyncServer;
import restudio.resync.server.ConfigLoader;
import restudio.resync.server.ReSyncConfig;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;

public class ReSync extends JavaPlugin {
    private static ReSync instance;
    private WebSocketServer wsServer;
    private ReSyncServer server;
    private ReSyncPluginMessageBridge pluginMessageBridge;
    private Object placeholderExpansion;
    private InteractiveSelectionManager interactiveSelectionManager;

    @Override
    public void onEnable() {
        instance = this;
        Log.init(getLogger());

        ReSyncConfig config = ConfigLoader.load(getDataFolder().toPath().resolve("config.properties").toString());
        Log.setLevel(config.getLogLevel());

        if (!config.isEnabled()) {
            Log.info("ReSync is disabled in config");
            return;
        }

        server = new ReSyncServer(this, config);
        pluginMessageBridge = new ReSyncPluginMessageBridge(this);
        pluginMessageBridge.register();
        interactiveSelectionManager = new InteractiveSelectionManager(this);
        interactiveSelectionManager.start();

        wsServer = new WebSocketServer(new InetSocketAddress(config.getBindHost(), config.getPort())) {
            @Override
            public void onOpen(WebSocket conn, ClientHandshake handshake) {
                server.onOpen(conn, handshake);
            }

            @Override
            public void onClose(WebSocket conn, int code, String reason, boolean remote) {
                server.onClose(conn, code, reason, remote);
            }

            @Override
            public void onMessage(WebSocket conn, String message) {
                server.onMessage(conn, ByteBuffer.wrap(message.getBytes()));
            }

            @Override
            public void onMessage(WebSocket conn, ByteBuffer message) {
                server.onMessage(conn, message);
            }

            @Override
            public void onError(WebSocket conn, Exception ex) {
                Log.error("[ReSync] WebSocket error: " + ex.getMessage(), ex);
            }

            @Override
            public void onStart() {
                Log.info("Server started on " + config.getBindHost() + ":" + getPort());
            }
        };

        try {
            wsServer.start();
            Log.info("[ReSync] Server enabled on " + config.getBindHost() + ":" + config.getPort());
        } catch (Exception e) {
            Log.error("[ReSync] Failed to start WebSocket server: " + e.getMessage(), e);
        }

        if (getCommand("resync") != null) {
            ReSyncCommand command = new ReSyncCommand(this);
            getCommand("resync").setExecutor(command);
            getCommand("resync").setTabCompleter(command);
        } else {
            Log.warn("Command '/resync' not found in plugin.yml");
        }

        registerPlaceholderExpansion();
    }

    @Override
    public void onDisable() {
        if (pluginMessageBridge != null) {
            pluginMessageBridge.unregister();
            pluginMessageBridge = null;
        }
        if (server != null) {
            server.shutdown();
        }
        if (interactiveSelectionManager != null) {
            interactiveSelectionManager.shutdown();
            interactiveSelectionManager = null;
        }
        if (placeholderExpansion != null) {
            try {
                placeholderExpansion.getClass().getMethod("unregister").invoke(placeholderExpansion);
            } catch (Exception ignored) {
            }
            placeholderExpansion = null;
        }

        if (wsServer != null) {
            try {
                wsServer.stop();
                Log.info("WebSocket server stopped.");
            } catch (Exception e) {
                Log.error("Error stopping WebSocket server: " + e.getMessage());
            }
        }

        Log.info("Plugin disabled.");
    }

    public static ReSync getInstance() {
        return instance;
    }

    public ReSyncServer getReSyncServer() {
        return server;
    }

    public InteractiveSelectionManager getInteractiveSelectionManager() {
        return interactiveSelectionManager;
    }

    private void registerPlaceholderExpansion() {
        if (!Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            Log.fine("PlaceholderAPI not found, skipping PAPI hook");
            return;
        }
        try {
            Class<?> expansionClass = Class.forName("restudio.resync.placeholder.ReSyncPlaceholderExpansion");
            placeholderExpansion = expansionClass.getConstructor(ReSync.class).newInstance(this);
            boolean registered = (boolean) expansionClass.getMethod("register").invoke(placeholderExpansion);
            if (registered) {
                Log.info("PlaceholderAPI hook registered");
            } else {
                Log.warn("Failed to register PlaceholderAPI hook");
            }
        } catch (NoClassDefFoundError e) {
            Log.fine("PlaceholderAPI classes not available, skipping PAPI hook");
        } catch (Exception e) {
            Log.warn("Failed to register PlaceholderAPI hook: " + e.getMessage());
        }
    }
}
