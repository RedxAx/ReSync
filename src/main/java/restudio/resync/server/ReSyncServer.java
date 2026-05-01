package restudio.resync.server;

import org.bukkit.Bukkit;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import restudio.resync.Log;
import restudio.resync.ReSync;
import restudio.resync.compression.CompressionPool;
import restudio.resync.core.ChannelMuxer;
import restudio.resync.core.ConnectionInfo;
import restudio.resync.core.ConnectionManager;
import restudio.resync.core.ConnectionState;
import restudio.resync.core.Session;
import restudio.resync.core.SessionManager;
import restudio.resync.flow.FlowExecutor;
import restudio.resync.flow.FlowStorage;
import restudio.resync.flow.GuiManager;
import restudio.resync.memory.MemoryMonitor;
import restudio.resync.modules.ChunkTransportModule;
import restudio.resync.modules.FlowModule;
import restudio.resync.modules.FlowRuntimeModule;
import restudio.resync.modules.Module;
import restudio.resync.modules.ModuleContext;
import restudio.resync.modules.ModuleRegistry;
import restudio.resync.modules.PlayerTrackingModule;
import restudio.resync.modules.WorldManagementModule;
import restudio.resync.modules.WorldGenModule;
import restudio.resync.player.DefaultPlayerSessionLinkService;
import restudio.resync.player.PlayerSessionLinkService;
import restudio.resync.player.PlayerTrackingManager;
import restudio.resync.player.PlayerTrackingService;
import restudio.resync.world.WorldManagementService;
import restudio.resync.protocol.Codec;
import restudio.resync.protocol.FrameHeader;
import restudio.resync.protocol.MessageType;
import restudio.resync.protocol.messages.DataMessage;
import restudio.resync.protocol.messages.ErrorMessage;
import restudio.resync.protocol.messages.HandshakeRequest;
import restudio.resync.protocol.messages.HandshakeResponse;
import restudio.resync.protocol.messages.Heartbeat;
import restudio.resync.protocol.messages.Message;
import restudio.resync.protocol.messages.SubscribeRequest;
import restudio.resync.protocol.messages.UnsubscribeRequest;
import restudio.resync.queue.RateLimiter;
import restudio.resync.queue.RequestQueue;
import restudio.resync.server.ReSyncConfig;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class ReSyncServer {
    private final ReSync plugin;
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
    private final ModuleContext moduleContext;
    private final AtomicInteger openConnections = new AtomicInteger();

    public ReSyncServer(ReSync plugin, ReSyncConfig config) {
        this.plugin = plugin;
        this.config = config;
        this.connectionManager = new ConnectionManager(30, 60);
        this.compressionPool = new CompressionPool(config.getCompression().getLevel(), 10);
        this.codec = new Codec(compressionPool, config.getMaxEncodedFrameBytes(), config.getMaxDecompressedPayloadBytes());
        this.channelMuxer = new ChannelMuxer();
        this.moduleRegistry = new ModuleRegistry();
        this.requestQueue = new RequestQueue(
            config.getQueue().getMaxGlobalRequests(),
            config.getQueue().getMaxRequestsPerClient(),
            4
        );
        this.memoryMonitor = new MemoryMonitor(config.getMemory().getSessionMemoryRatio());
        this.sessionManager = new SessionManager(memoryMonitor, 300, config.getMemory().getMaxMemoryPerSession());
        this.rateLimiter = new RateLimiter(1000, 10, 1000);
        this.scheduler = Executors.newScheduledThreadPool(2);
        this.moduleContext = new ModuleContext(
            plugin,
            this,
            config,
            connectionManager,
            sessionManager,
            channelMuxer,
            moduleRegistry,
            requestQueue,
            rateLimiter,
            compressionPool,
            codec,
            scheduler,
            memoryMonitor
        );

        registerCoreServices();
        registerModules();
        moduleRegistry.initializeModules(moduleContext);
        startScheduler();
    }

    private void registerCoreServices() {
        moduleContext.registerService(PlayerTrackingService.class, new PlayerTrackingManager(plugin));
        moduleContext.registerService(PlayerSessionLinkService.class, new DefaultPlayerSessionLinkService());
        moduleContext.registerService(ModuleContext.class, moduleContext);
    }

    private void registerModules() {
        moduleRegistry.registerModule(new ChunkTransportModule());
        moduleRegistry.registerModule(new FlowRuntimeModule());
        moduleRegistry.registerModule(new PlayerTrackingModule());
        moduleRegistry.registerModule(new WorldManagementModule());
        moduleRegistry.registerModule(new WorldGenModule());
    }

    private void startScheduler() {
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                moduleRegistry.tickAll();
            } catch (Exception e) {
                Log.warn("Module tick failed: " + e.getMessage());
            }
        }, 100, 100, TimeUnit.MILLISECONDS);
    }

    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        int current = openConnections.incrementAndGet();
        if (config.getMaxConnections() > 0 && current > config.getMaxConnections()) {
            openConnections.decrementAndGet();
            conn.close(1013, "Max connections reached");
            return;
        }
        connectionManager.createConnection(conn);
        Log.fine("Client connected: " + conn.getRemoteSocketAddress());
    }

    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        openConnections.updateAndGet(value -> Math.max(0, value - 1));
        Session session = sessionManager.getSession(conn);
        if (session != null) {
            moduleRegistry.cleanupSession(session);
        }
        sessionManager.removeSession(conn);
        connectionManager.removeConnection(conn);
        Log.fine("Client disconnected: " + conn.getRemoteSocketAddress());
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
            String clientId = info.getClientId() != null ? info.getClientId() : conn.getRemoteSocketAddress().toString();
            if (!rateLimiter.tryConsume("global", 1, config.getQueue().getMaxGlobalRequests(), config.getQueue().getMaxGlobalRequests(), 1000)) {
                sendError(conn, 429, "Global rate limit exceeded");
                return;
            }
            if (!rateLimiter.tryConsume(clientId, 1, config.getQueue().getMaxRequestsPerClient(), config.getQueue().getMaxRequestsPerClient(), 1000)) {
                sendError(conn, 429, "Rate limit exceeded");
                return;
            }
            Codec.Frame frame = codec.decodeFrame(data);
            if (frame.header.getMessageType() == MessageType.DATA && !info.acceptInboundDataSequence(frame.header.getSequence())) {
                sendError(conn, 409, "Stale data frame");
                return;
            }
            Message payload = codec.decodePayload(frame);
            handlePayload(conn, info, payload, frame.header);
        } catch (Exception e) {
            Log.warn("Error handling message: " + e.getMessage());
        }
    }

    private void handlePayload(WebSocket conn, ConnectionInfo info, Message payload, FrameHeader header) {
        if (info.getState() != ConnectionState.AUTHENTICATED && payload.getType() != MessageType.HANDSHAKE_REQUEST) {
            sendError(conn, 401, "Handshake required");
            conn.close(1008, "Handshake required");
            return;
        }
        switch (payload.getType()) {
            case HANDSHAKE_REQUEST -> handleHandshake(conn, info, (HandshakeRequest) payload);
            case SUBSCRIBE -> handleSubscribe(conn, info, (SubscribeRequest) payload);
            case UNSUBSCRIBE -> handleUnsubscribe(info, (UnsubscribeRequest) payload);
            case DATA -> handleData(info, (DataMessage) payload);
            case HEARTBEAT -> handleHeartbeat(info, (Heartbeat) payload);
            default -> Log.warn("Unhandled message type: " + payload.getType());
        }
    }

    private void handleHandshake(WebSocket conn, ConnectionInfo info, HandshakeRequest req) {
        if (!config.getApiKey().equals(req.getApiKey())) {
            sendError(conn, 401, "Invalid API key");
            conn.close(1008, "Invalid API key");
            return;
        }

        if (req.getProtocolVersion() != 2) {
            sendError(conn, 400, "Unsupported protocol version");
            conn.close(1003, "Unsupported protocol version");
            return;
        }

        Session oldSession = sessionManager.getSession(info);
        if (oldSession != null) {
            moduleRegistry.cleanupSession(oldSession);
            sessionManager.removeSession(conn);
        }

        info.setClientId(req.getClientId());
        info.setClientVersion(req.getClientVersion());
        info.setState(ConnectionState.AUTHENTICATED);
        sessionManager.createSession(info, req.getClientId());

        HandshakeResponse response = new HandshakeResponse();
        response.setSuccess(true);
        response.setMessage("Handshake successful");
        response.setServerProtocolVersion(2);
        response.setServerVersion("3.0.0");

        List<String> worlds = new ArrayList<>();
        Bukkit.getWorlds().forEach(world -> worlds.add(world.getName()));
        response.setWorlds(worlds);
        response.setSupportedTileSizes(new int[]{32, 64, 128, 256});
        response.setChannels(channelMuxer.getNumericChannels());

        codec.sendMessage(conn, response, 0, false);
        Log.info("Client authenticated: " + req.getClientId());
    }

    private void handleSubscribe(WebSocket conn, ConnectionInfo info, SubscribeRequest req) {
        Session session = sessionManager.getSession(info);
        if (session == null) {
            sendError(conn, 401, "Not authenticated");
            return;
        }

        Module module = moduleRegistry.getModuleByChannel(req.getChannelId());
        if (module == null) {
            sendError(conn, 404, "Unknown channel: " + req.getChannelId());
            return;
        }

        session.subscribeChannel(req.getChannelId());
        session.addModule(module);
        module.onSubscribe(session, req);

        ChannelMuxer.Channel channel = channelMuxer.getChannel(req.getChannelId());
        if (channel != null) {
            channel.incrementSubscribers();
        }
        Log.fine(session.getClientId() + " subscribed to " + req.getChannelId());
    }

    private void handleUnsubscribe(ConnectionInfo info, UnsubscribeRequest req) {
        Session session = sessionManager.getSession(info);
        if (session == null) {
            return;
        }

        Module module = moduleRegistry.getModuleByChannel(req.getChannelId());
        if (module == null) {
            return;
        }

        module.onUnsubscribe(session, req);
        session.unsubscribeChannel(req.getChannelId());
        session.removeModule(req.getChannelId());

        ChannelMuxer.Channel channel = channelMuxer.getChannel(req.getChannelId());
        if (channel != null) {
            channel.decrementSubscribers();
        }
    }

    private void handleData(ConnectionInfo info, DataMessage req) {
        Session session = sessionManager.getSession(info);
        if (session == null) {
            return;
        }

        ChannelMuxer.Channel channel = channelMuxer.getChannelByNumericId(req.getChannel());
        if (channel == null) {
            return;
        }

        Module module = moduleRegistry.getModuleByChannel(channel.getId());
        if (module == null) {
            return;
        }
        if (!session.getSubscribedChannels().contains(channel.getId())) {
            sendError(info.getWebSocket(), 403, "Channel subscription required");
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
        moduleRegistry.shutdownModules(moduleContext);
        scheduler.shutdown();
        connectionManager.shutdown();
        sessionManager.shutdown();
        requestQueue.shutdown();
        compressionPool.close();
        rateLimiter.resetAll();
        memoryMonitor.shutdown();
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

    public ModuleContext getModuleContext() {
        return moduleContext;
    }

    public FlowModule getFlowModule() {
        return moduleContext.getService(FlowModule.class);
    }

    public FlowStorage getFlowStorage() {
        return moduleContext.getService(FlowStorage.class);
    }

    public GuiManager getGuiManager() {
        return moduleContext.getService(GuiManager.class);
    }

    public FlowExecutor getFlowExecutor() {
        return moduleContext.getService(FlowExecutor.class);
    }

    public PlayerTrackingService getPlayerTrackingService() {
        return moduleContext.getService(PlayerTrackingService.class);
    }

    public PlayerSessionLinkService getPlayerSessionLinkService() {
        return moduleContext.getService(PlayerSessionLinkService.class);
    }

    public WorldManagementService getWorldManagementService() {
        return moduleContext.getService(WorldManagementService.class);
    }
}
