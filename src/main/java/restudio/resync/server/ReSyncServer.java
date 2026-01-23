package restudio.resync.server;

import org.bukkit.Bukkit;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import restudio.resync.compression.CompressionPool;
import restudio.resync.core.*;
import restudio.resync.memory.MemoryMonitor;
import restudio.resync.flow.FlowRegistry;
import restudio.resync.flow.FlowStorage;
import restudio.resync.flow.GuiManager;
import restudio.resync.flow.plugins.FlowNodePluginRegistry;
import restudio.resync.flow.registry.NodeDefinitionRegistry;
import restudio.resync.modules.ChunkModule;
import restudio.resync.modules.FlowModule;
import restudio.resync.modules.Module;
import restudio.resync.modules.ModuleRegistry;
import restudio.resync.protocol.Codec;
import restudio.resync.protocol.FrameHeader;
import restudio.resync.protocol.messages.*;
import restudio.resync.queue.Priority;
import restudio.resync.queue.RateLimiter;
import restudio.resync.queue.RequestQueue;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class ReSyncServer {
    private final ReSyncConfig config;
    private final ConnectionManager connectionManager;
    private final SessionManager sessionManager;
    private final ChannelMuxer channelMuxer;
    private final ModuleRegistry moduleRegistry;
    private final RequestQueue requestQueue;
    private final RateLimiter rateLimiter;
    private final CompressionPool compressionPool;
    private final Codec codec;
    private final ScheduledExecutorService scheduler;
    private final MemoryMonitor memoryMonitor;
    private restudio.resync.flow.GuiManager guiManager;
    private FlowModule flowModule;
    private restudio.resync.flow.SystemEventListener systemEventListener;
    private FlowNodePluginRegistry nodePluginRegistry;
    
    public FlowModule getFlowModule() {
        return flowModule;
    }

    public restudio.resync.flow.GuiManager getGuiManager() {
        return guiManager;
    }

    public ReSyncServer(ReSyncConfig config) {
        this.config = config;
        this.connectionManager = new ConnectionManager(30, 60);
        this.compressionPool = new CompressionPool(
            config.getCompression().getLevel(),
            10
        );
        this.codec = new Codec(compressionPool);
        this.channelMuxer = new ChannelMuxer();
        this.moduleRegistry = new ModuleRegistry();
        this.requestQueue = new RequestQueue(
            config.getQueue().getMaxGlobalRequests(),
            config.getQueue().getMaxRequestsPerClient(),
            4
        );
        this.memoryMonitor = new MemoryMonitor(config.getMemory().getSessionMemoryRatio());
        this.sessionManager = new SessionManager(
            memoryMonitor,
            300,
            config.getMemory().getMaxMemoryPerSession()
        );
        this.rateLimiter = new RateLimiter(
            1000,
            10,
            1000
        );
        this.scheduler = Executors.newScheduledThreadPool(2);

        setupChannels();
        registerModules();
        startScheduler();
    }

    private void registerModules() {
        restudio.resync.core.ChannelMuxer.Channel chunksChannel = channelMuxer.getChannel("chunks");
        int chunksId = (chunksChannel != null) ? chunksChannel.getNumericId() : 1000;

        ChunkModule chunkModule = new ChunkModule(
            codec,
            config.getMemory().getMaxCacheSize(),
            config.getMemory().getCacheTtlMinutes()
        );
        moduleRegistry.registerModule(chunkModule);

        restudio.resync.core.ChannelMuxer.Channel flowChannel = channelMuxer.getChannel("flow");
        int flowId = (flowChannel != null) ? flowChannel.getNumericId() : 1001;

        FlowStorage flowStorage = new FlowStorage(restudio.resync.ReSync.getInstance());
        restudio.resync.flow.TypeAdapterRegistry typeAdapter = new restudio.resync.flow.TypeAdapterRegistry();
        FlowRegistry flowRegistry = new FlowRegistry();
        restudio.resync.flow.StandardNodes.registerAll(flowRegistry);
        NodeDefinitionRegistry nodeDefinitionRegistry = new NodeDefinitionRegistry();
        FlowNodePluginRegistry nodePluginRegistry = new FlowNodePluginRegistry(
            flowRegistry,
            nodeDefinitionRegistry,
            restudio.resync.ReSync.getInstance().getDataFolder().toPath().resolve("flow-plugins")
        );
        nodePluginRegistry.loadInitialPlugins();
        this.nodePluginRegistry = nodePluginRegistry;
        java.util.Map<String, Object> globalVariables = new java.util.HashMap<>();
        restudio.resync.flow.FlowExecutor flowExecutor = new restudio.resync.flow.FlowExecutor(flowRegistry, typeAdapter, globalVariables);
        restudio.resync.flow.triggers.TriggerRegistry triggerRegistry = new restudio.resync.flow.triggers.TriggerRegistry(restudio.resync.ReSync.getInstance());
        restudio.resync.flow.GlobalTriggers globalTriggers = new restudio.resync.flow.GlobalTriggers(flowStorage, flowExecutor, triggerRegistry);
        this.systemEventListener = new restudio.resync.flow.SystemEventListener(flowStorage, flowExecutor, triggerRegistry);
        Bukkit.getPluginManager().registerEvents(globalTriggers, restudio.resync.ReSync.getInstance());
        Bukkit.getPluginManager().registerEvents(systemEventListener, restudio.resync.ReSync.getInstance());

        Bukkit.getScheduler().runTaskTimer(restudio.resync.ReSync.getInstance(), () -> {
            if (this.systemEventListener != null) {
                this.systemEventListener.tick();
            }
            restudio.resync.flow.CustomEventManager.getInstance().tick();
        }, 1L, 1L);
        
        FlowModule flowModule = new FlowModule(flowStorage, codec, flowId, triggerRegistry, globalTriggers, flowRegistry, nodeDefinitionRegistry, nodePluginRegistry);
        moduleRegistry.registerModule(flowModule);

        this.guiManager = new restudio.resync.flow.GuiManager(this, flowStorage, flowExecutor, flowModule);
        Bukkit.getPluginManager().registerEvents(this.guiManager, restudio.resync.ReSync.getInstance());
    }

    private void setupChannels() {
        channelMuxer.createChannel("chunks");
        channelMuxer.createChannel("flow");
    }

    private void startScheduler() {
        scheduler.scheduleWithFixedDelay(() -> {
            moduleRegistry.tickAll();
            if (nodePluginRegistry != null) {
                nodePluginRegistry.tick();
            }
        }, 100, 100, TimeUnit.MILLISECONDS);
    }

    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        ConnectionInfo info = connectionManager.createConnection(conn);
        Bukkit.getLogger().info("[ReSync] Client connected: " + conn.getRemoteSocketAddress());
    }

    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        connectionManager.removeConnection(conn);
        Session session = sessionManager.getSession(conn);
        if (session != null) {
            moduleRegistry.cleanupSession(session);
        }
        sessionManager.removeSession(conn);
        Bukkit.getLogger().info("[ReSync] Client disconnected: " + conn.getRemoteSocketAddress());
    }

    public void onMessage(WebSocket conn, ByteBuffer message) {
        ConnectionInfo info = connectionManager.getConnection(conn);
        if (info == null) {
            conn.close(1002, "Unknown connection");
            return;
        }

        byte[] data = new byte[message.remaining()];
        message.get(data);

        try {
            Codec.Frame frame = codec.decodeFrame(data);
            Message payload = codec.decodePayload(frame);

            handlePayload(conn, info, payload, frame.header);
        } catch (Exception e) {
            Bukkit.getLogger().warning("[ReSync] Error handling message: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void handlePayload(WebSocket conn, ConnectionInfo info, Message payload, FrameHeader header) {
        switch (payload.getType()) {
            case HANDSHAKE_REQUEST:
                handleHandshake(conn, info, (HandshakeRequest) payload);
                break;

            case SUBSCRIBE:
                handleSubscribe(conn, info, (SubscribeRequest) payload);
                break;

            case UNSUBSCRIBE:
                handleUnsubscribe(conn, info, (UnsubscribeRequest) payload);
                break;

            case DATA:
                handleData(conn, info, (DataMessage) payload);
                break;

            case HEARTBEAT:
                handleHeartbeat(info, (Heartbeat) payload);
                break;

            default:
                Bukkit.getLogger().warning("[ReSync] Unhandled message type: " + payload.getType());
                break;
        }
    }

    private void handleHandshake(WebSocket conn, ConnectionInfo info, HandshakeRequest req) {
        if (!req.getApiKey().equals(config.getApiKey())) {
            sendError(conn, 401, "Invalid API key");
            conn.close(1008, "Invalid API key");
            return;
        }

        if (req.getProtocolVersion() != 2) {
            sendError(conn, 400, "Unsupported protocol version");
            conn.close(1003, "Unsupported protocol version");
            return;
        }

        info.setClientId(req.getClientId());
        info.setClientVersion(req.getClientVersion());
        info.setState(ConnectionState.AUTHENTICATED);

        sessionManager.createSession(info, req.getClientId());

        HandshakeResponse response = new HandshakeResponse();
        response.setSuccess(true);
        response.setMessage("Handshake successful");
        response.setServerProtocolVersion(2);
        response.setServerVersion("2.0.0");

        List<String> worlds = new ArrayList<>();
        Bukkit.getWorlds().forEach(w -> worlds.add(w.getName()));
        response.setWorlds(worlds);

        response.setSupportedTileSizes(new int[]{32, 64, 128, 256});

        codec.sendMessage(conn, response, 0, false);
        Bukkit.getLogger().info("[ReSync] Client authenticated: " + req.getClientId());
    }

    private void handleSubscribe(WebSocket conn, ConnectionInfo info, SubscribeRequest req) {
        Session session = sessionManager.getSession(info);
        if (session == null) {
            sendError(conn, 401, "Not authenticated");
            return;
        }

        String channelId = req.getChannelId();
        Module module = moduleRegistry.getModule(channelId);

        if (module == null) {
            sendError(conn, 404, "Unknown channel: " + channelId);
            return;
        }

        module.onSubscribe(session, req);
        channelMuxer.getChannel(channelId).incrementSubscribers();

        Bukkit.getLogger().info("[ReSync] Client " + session.getClientId() + " subscribed to " + channelId);
    }

    private void handleUnsubscribe(WebSocket conn, ConnectionInfo info, UnsubscribeRequest req) {
        Session session = sessionManager.getSession(info);
        if (session == null) {
            return;
        }

        String channelId = req.getChannelId();
        Module module = moduleRegistry.getModule(channelId);

        if (module != null) {
            module.onUnsubscribe(session, req);
            channelMuxer.getChannel(channelId).decrementSubscribers();
        }
    }

    private void handleData(WebSocket conn, ConnectionInfo info, DataMessage req) {
        Session session = sessionManager.getSession(info);
        if (session == null) {
            return;
        }

        int numericChannelId = req.getChannel();
        ChannelMuxer.Channel channel = channelMuxer.getChannelByNumericId(numericChannelId);
        
        if (channel == null) {
            return;
        }
        
        String channelId = channel.getId();
        Module module = moduleRegistry.getModule(channelId);
        if (module == null) {
            return;
        }

        session.updateActivity();
        module.onData(session, req);
    }

    private void handleHeartbeat(ConnectionInfo info, Heartbeat req) {
        connectionManager.updateHeartbeat(info.getWebSocket());
    }

    private void sendError(WebSocket conn, int code, String message) {
        ErrorMessage error = new ErrorMessage();
        error.setErrorCode(code);
        error.setErrorText(message);
        codec.sendMessage(conn, error, 0, false);
    }

    public void shutdown() {
        if (this.systemEventListener != null) {
            this.systemEventListener.onServerStop();
        }
        if (this.nodePluginRegistry != null) {
            this.nodePluginRegistry.shutdown();
        }
        scheduler.shutdown();
        connectionManager.shutdown();
        sessionManager.shutdown();
        requestQueue.shutdown();
        compressionPool.close();
        rateLimiter.resetAll();
    }

    public ConnectionManager getConnectionManager() {
        return connectionManager;
    }

    public SessionManager getSessionManager() {
        return sessionManager;
    }

    public ModuleRegistry getModuleRegistry() {
        return moduleRegistry;
    }

    public RequestQueue getRequestQueue() {
        return requestQueue;
    }
}
