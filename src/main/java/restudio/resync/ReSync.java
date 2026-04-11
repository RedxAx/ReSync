package restudio.resync;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import restudio.resync.commands.ReSyncCommand;
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
        interactiveSelectionManager = new InteractiveSelectionManager(this);
        interactiveSelectionManager.start();

        wsServer = new WebSocketServer(new InetSocketAddress(config.getPort())) {
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
                getLogger().severe("[ReSync] WebSocket error: " + ex.getMessage());
                ex.printStackTrace();
            }

            @Override
            public void onStart() {
                getLogger().info("[ReSync] Server started on port " + getPort());
            }
        };

        try {
            wsServer.start();
            getLogger().info("[ReSync] Server enabled on port " + config.getPort());
        } catch (Exception e) {
            getLogger().severe("[ReSync] Failed to start WebSocket server: " + e.getMessage());
            e.printStackTrace();
        }

        if (getCommand("resync") != null) {
            ReSyncCommand command = new ReSyncCommand(this);
            getCommand("resync").setExecutor(command);
            getCommand("resync").setTabCompleter(command);
        } else {
            getLogger().warning("[ReSync] Command '/resync' not found in plugin.yml");
        }

        registerPlaceholderExpansion();
    }

    @Override
    public void onDisable() {
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
                getLogger().info("[ReSync] WebSocket server stopped.");
            } catch (Exception e) {
                getLogger().severe("[ReSync] Error stopping WebSocket server: " + e.getMessage());
            }
        }

        getLogger().info("[ReSync] Plugin disabled.");
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
