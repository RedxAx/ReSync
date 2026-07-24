package restudio.resync;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import restudio.resync.commands.ReSyncCommand;
import restudio.resync.bridge.ReSyncPluginMessageBridge;
import restudio.resync.flow.network.NetworkFlowBridge;
import restudio.resync.modules.ChatModule;
import restudio.resync.modules.FlowModule;
import restudio.resync.modules.flow.FlowResourceRegistry;
import restudio.resync.network.paper.ReSyncNetworkAgent;
import restudio.resync.network.paper.ReSyncNetworkAgentConfig;
import restudio.resync.network.paper.NetworkResourceSynchronizer;
import restudio.resync.network.paper.state.NetworkPlayerStateConfig;
import restudio.resync.network.paper.state.NetworkPlayerStateCoordinator;
import restudio.resync.resources.ReSyncResourceCatalog;
import restudio.resync.selection.InteractiveSelectionManager;
import restudio.resync.server.ReSyncServer;
import restudio.resync.server.ConfigLoader;
import restudio.resync.server.ReSyncConfig;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;

public class ReSync extends JavaPlugin {
    private static ReSync instance;
    private WebSocketServer wsServer;
    private ReSyncServer server;
    private ReSyncPluginMessageBridge pluginMessageBridge;
    private ReSyncNetworkAgent networkAgent;
    private NetworkResourceSynchronizer networkResourceSynchronizer;
    private NetworkPlayerStateCoordinator networkPlayerStateCoordinator;
    private boolean networkStateReloadScheduled;
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
        try {
            ReSyncNetworkAgentConfig networkConfig = ReSyncNetworkAgentConfig.load(getDataFolder().toPath());
            if (networkConfig.enabled()) {
                networkAgent = new ReSyncNetworkAgent(this, networkConfig);
                NetworkFlowBridge networkFlowBridge = server.getModuleContext().getService(NetworkFlowBridge.class);
                if (networkFlowBridge != null) {
                    networkFlowBridge.connect(networkAgent);
                }
                ChatModule chatModule = server.getModuleContext().getService(ChatModule.class);
                if (chatModule != null) {
                    chatModule.connectNetwork(networkAgent, networkConfig.chat());
                }
                FlowResourceRegistry resourceRegistry = server.getModuleContext().getService(FlowResourceRegistry.class);
                ReSyncNetworkAgentConfig.ResourcePolicy resourcePolicy = networkConfig.chatEnabled() ? networkConfig.resources().withIncluded(ReSyncResourceCatalog.CHAT) : networkConfig.resources();
                resourcePolicy = resourcePolicy.enabled() ? resourcePolicy.withIncluded(ReSyncResourceCatalog.PROJECT_METADATA) : resourcePolicy;
                if (resourcePolicy.enabled() && resourceRegistry != null) {
                    FlowModule flowModule = server.getModuleContext().getService(FlowModule.class);
                    networkResourceSynchronizer = new NetworkResourceSynchronizer(this, networkAgent, resourceRegistry, resourcePolicy, getDataFolder().toPath(), resource -> {
                        if (flowModule != null) {
                            flowModule.refreshSharedResource(resource.type(), resource.resourceId(), resource.deleted());
                        }
                    });
                    networkResourceSynchronizer.start();
                }
                NetworkPlayerStateConfig playerStateConfig = NetworkPlayerStateConfig.load(getDataFolder().toPath());
                if (playerStateConfig.enabled()) {
                    networkPlayerStateCoordinator = new NetworkPlayerStateCoordinator(this, playerStateConfig);
                    networkAgent.setTransferHandler(networkPlayerStateCoordinator);
                }
                networkAgent.start();
            }
        } catch (Exception exception) {
            Log.error("ReSync network agent failed to start: " + exception.getMessage(), exception);
        }

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
                Log.error("WebSocket error: " + ex.getMessage(), ex);
            }

            @Override
            public void onStart() {
                Log.info("WebSocket server ready on " + config.getBindHost() + ":" + getPort());
            }
        };

        try {
            wsServer.start();
            Log.info("WebSocket server starting on " + config.getBindHost() + ":" + config.getPort());
        } catch (Exception e) {
            Log.error("Failed to start WebSocket server: " + e.getMessage(), e);
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
        if (networkResourceSynchronizer != null) {
            networkResourceSynchronizer.shutdown();
            networkResourceSynchronizer = null;
        }
        if (networkPlayerStateCoordinator != null) {
            if (networkAgent != null) {
                networkAgent.setTransferHandler(null);
            }
            networkPlayerStateCoordinator.shutdown();
            networkPlayerStateCoordinator = null;
        }
        if (networkAgent != null) {
            networkAgent.shutdown();
            networkAgent = null;
        }
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

    public ReSyncNetworkAgent getNetworkAgent() {
        return networkAgent;
    }

    public synchronized NetworkPlayerStateConfig reloadNetworkState() throws IOException {
        if (networkAgent == null) throw new IllegalStateException("ReSync Network Agent Is Not Running");
        NetworkPlayerStateConfig config = NetworkPlayerStateConfig.load(getDataFolder().toPath());
        if (networkAgent.hasActiveTransfers()) {
            if (!networkStateReloadScheduled) {
                networkStateReloadScheduled = true;
                Bukkit.getScheduler().runTaskLater(this, () -> {
                    synchronized (this) {
                        networkStateReloadScheduled = false;
                    }
                    try {
                        reloadNetworkState();
                    } catch (IOException | RuntimeException exception) {
                        Log.error("ReSync network state reload failed: " + exception.getMessage(), exception);
                    }
                }, 1);
            }
            return config;
        }
        networkAgent.setTransferHandler(null);
        if (networkPlayerStateCoordinator != null) networkPlayerStateCoordinator.shutdown();
        networkPlayerStateCoordinator = config.enabled() ? new NetworkPlayerStateCoordinator(this, config) : null;
        if (networkPlayerStateCoordinator != null) networkAgent.setTransferHandler(networkPlayerStateCoordinator);
        networkAgent.reconnect();
        return config;
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
