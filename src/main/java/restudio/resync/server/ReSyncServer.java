package restudio.resync.server;

import org.bukkit.Bukkit;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.bukkit.entity.Player;
import restudio.resync.Log;
import restudio.resync.ReSync;
import restudio.resync.api.OptionCatalogRegistry;
import restudio.resync.api.ReSyncExtensionData;
import restudio.resync.api.ReSyncExtensionManager;
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
import restudio.resync.flow.ScoreboardTemplateManager;
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
import restudio.resync.protocol.FrameSender;
import restudio.resync.protocol.FrameHeader;
import restudio.resync.protocol.MessageType;
import restudio.resync.protocol.messages.ChannelRegistryMessage;
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
import restudio.resync.security.ClientAuthorizer;
import restudio.resync.security.ClientIdentity;
import restudio.resync.server.ReSyncConfig;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private final ClientAuthorizer clientAuthorizer;
    private final ReSyncExtensionManager extensionManager;
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
        this.clientAuthorizer = new ClientAuthorizer(config);
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
        moduleRegistry.addListener(new ModuleRegistry.ModuleChangeListener() {
            @Override
            public void onModuleRegistered(Module module) {
                publishChannelDelta(module.getChannels(), List.of());
            }

            @Override
            public void onModuleUnregistered(String moduleId, java.util.Set<String> channels) {
                publishChannelDelta(List.of(), new ArrayList<>(channels));
            }
        });
        this.extensionManager = new ReSyncExtensionManager(moduleContext, plugin.getDataFolder().toPath().resolve("extensions"));
        moduleContext.registerService(ReSyncExtensionManager.class, extensionManager);
        this.extensionManager.loadInitialExtensions();
        startScheduler();
    }

    private void registerCoreServices() {
        moduleContext.registerService(PlayerTrackingService.class, new PlayerTrackingManager(plugin));
        moduleContext.registerService(PlayerSessionLinkService.class, new DefaultPlayerSessionLinkService());
        moduleContext.registerService(ModuleContext.class, moduleContext);
        moduleContext.registerService(OptionCatalogRegistry.class, new OptionCatalogRegistry());
        moduleContext.registerService(ReSyncExtensionData.class, new ReSyncExtensionData());
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
                extensionManager.tick();
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

    public ConnectionInfo onBridgeOpen(FrameSender sender) {
        return connectionManager.createVirtualConnection(sender);
    }

    public void onBridgeClose(ConnectionInfo info) {
        if (info == null) {
            return;
        }
        Session session = sessionManager.getSession(info);
        if (session != null) {
            moduleRegistry.cleanupSession(session);
        }
        sessionManager.removeSession(info);
        connectionManager.removeVirtualConnection(info);
        info.setState(ConnectionState.CLOSING);
    }

    public Map<String, Integer> getBridgeChannels() {
        return channelMuxer.getNumericChannels();
    }

    public void onBridgeMessage(ConnectionInfo info, Player player, byte[] data) {
        if (info == null || player == null || data == null) {
            return;
        }
        try {
            String clientId = info.getClientId() != null ? info.getClientId() : player.getUniqueId().toString();
            if (!rateLimiter.tryConsume("global", 1, config.getQueue().getMaxGlobalRequests(), config.getQueue().getMaxGlobalRequests(), 1000)) {
                sendError(info, 429, "Global rate limit exceeded");
                return;
            }
            if (!rateLimiter.tryConsume(clientId, 1, config.getQueue().getMaxRequestsPerClient(), config.getQueue().getMaxRequestsPerClient(), 1000)) {
                sendError(info, 429, "Rate limit exceeded");
                return;
            }
            Codec.Frame frame = codec.decodeFrame(data);
            if (frame.header.getMessageType() == MessageType.DATA && !info.acceptInboundDataSequence(frame.header.getSequence())) {
                sendError(info, 409, "Stale data frame");
                return;
            }
            Message payload = codec.decodePayload(frame);
            handleBridgePayload(info, player, payload, frame.header);
        } catch (Exception e) {
            String reason = e.getMessage();
            if (reason == null || reason.isBlank()) {
                reason = e.getClass().getSimpleName();
            }
            Log.warn("Error handling bridge message: " + reason);
        }
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
                sendError(info, 429, "Global rate limit exceeded");
                return;
            }
            if (!rateLimiter.tryConsume(clientId, 1, config.getQueue().getMaxRequestsPerClient(), config.getQueue().getMaxRequestsPerClient(), 1000)) {
                sendError(info, 429, "Rate limit exceeded");
                return;
            }
            Codec.Frame frame = codec.decodeFrame(data);
            if (frame.header.getMessageType() == MessageType.DATA && !info.acceptInboundDataSequence(frame.header.getSequence())) {
                sendError(info, 409, "Stale data frame");
                return;
            }
            Message payload = codec.decodePayload(frame);
            handlePayload(info, payload, frame.header);
        } catch (Exception e) {
            String reason = e.getMessage();
            if (reason == null || reason.isBlank()) {
                reason = e.getClass().getSimpleName();
            }
            Log.warn("Error handling message: " + reason);
        }
    }

    private void handlePayload(ConnectionInfo info, Message payload, FrameHeader header) {
        if (info.getState() != ConnectionState.AUTHENTICATED && payload.getType() != MessageType.HANDSHAKE_REQUEST) {
            sendError(info, 401, "Handshake required");
            info.getFrameSender().close(1008, "Handshake required");
            return;
        }
        switch (payload.getType()) {
            case HANDSHAKE_REQUEST -> handleHandshake(info, (HandshakeRequest) payload);
            case SUBSCRIBE -> handleSubscribe(info, (SubscribeRequest) payload);
            case UNSUBSCRIBE -> handleUnsubscribe(info, (UnsubscribeRequest) payload);
            case DATA -> handleData(info, (DataMessage) payload);
            case HEARTBEAT -> handleHeartbeat(info, (Heartbeat) payload);
            default -> Log.warn("Unhandled message type: " + payload.getType());
        }
    }

    private void handleBridgePayload(ConnectionInfo info, Player player, Message payload, FrameHeader header) {
        if (payload.getType() == MessageType.HANDSHAKE_REQUEST) {
            handleBridgeHandshake(info, player, (HandshakeRequest) payload);
            return;
        }
        handlePayload(info, payload, header);
    }

    private void handleHandshake(ConnectionInfo info, HandshakeRequest req) {
        if (req.getProtocolVersion() != 2) {
            sendError(info, 400, "Unsupported protocol version");
            info.getFrameSender().close(1003, "Unsupported protocol version");
            return;
        }

        ClientIdentity identity;
        try {
            identity = clientAuthorizer.authorize(req.getApiKey(), req.getClientId(), req.getClientVersion());
        } catch (SecurityException exception) {
            sendError(info, 401, exception.getMessage());
            info.getFrameSender().close(1008, exception.getMessage());
            return;
        }
        completeHandshake(info, req, identity);
    }

    private void handleBridgeHandshake(ConnectionInfo info, Player player, HandshakeRequest req) {
        if (req.getProtocolVersion() != 2) {
            sendError(info, 400, "Unsupported protocol version");
            info.getFrameSender().close(1003, "Unsupported protocol version");
            return;
        }
        if (!player.isOp() && !player.hasPermission("resync.api.access")) {
            sendError(info, 401, "No Permission");
            info.getFrameSender().close(1008, "No Permission");
            return;
        }
        String clientId = req.getClientId();
        if (clientId == null || clientId.isBlank()) {
            clientId = "bridge:" + player.getUniqueId();
            req.setClientId(clientId);
        }
        completeHandshake(info, req, new ClientIdentity(clientId, req.getClientVersion()));
        Session session = sessionManager.getSession(info);
        sessionManager.linkPlayerToSession(player.getUniqueId(), session);
        PlayerSessionLinkService linkService = getPlayerSessionLinkService();
        if (linkService != null) {
            linkService.link(player.getUniqueId(), session);
        }
        GuiManager guiManager = getGuiManager();
        if (guiManager != null) {
            guiManager.sendOpenGuiState(player, session);
        }
        ScoreboardTemplateManager.sendActiveState(player, session);
    }

    private void completeHandshake(ConnectionInfo info, HandshakeRequest req, ClientIdentity identity) {
        Session oldSession = sessionManager.getSession(info);
        if (oldSession != null) {
            moduleRegistry.cleanupSession(oldSession);
            sessionManager.removeSession(info);
        }

        Session duplicate = sessionManager.getSessionByClientId(identity.clientId());
        if (duplicate != null && duplicate.getConnection() != info) {
            duplicate.getConnection().getFrameSender().close(1000, "Duplicate client replaced");
        }

        info.setClientId(req.getClientId());
        info.setClientVersion(req.getClientVersion());
        info.setState(ConnectionState.AUTHENTICATED);
        sessionManager.createSession(info, identity);

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

        codec.sendMessage(info.getFrameSender(), response, 0, false);
        publishChannelSnapshot(info);
        Log.fine("Client authenticated: " + req.getClientId());
    }

    private void publishChannelSnapshot(ConnectionInfo info) {
        if (!supportsChannelRegistry(info)) {
            return;
        }
        ChannelRegistryMessage message = new ChannelRegistryMessage();
        message.setSnapshot(true);
        message.setChannels(channelMuxer.getNumericChannels());
        codec.sendMessage(info.getFrameSender(), message, 0, false);
    }

    private void publishChannelDelta(Iterable<String> addedChannels, List<String> removedChannels) {
        Map<String, Integer> added = new LinkedHashMap<>();
        Map<String, Integer> numericChannels = channelMuxer.getNumericChannels();
        if (addedChannels != null) {
            for (String channelId : addedChannels) {
                Integer numericId = numericChannels.get(channelId);
                if (numericId != null) {
                    added.put(channelId, numericId);
                }
            }
        }
        ChannelRegistryMessage message = new ChannelRegistryMessage();
        message.setSnapshot(false);
        message.setChannels(added);
        message.setRemovedChannels(removedChannels);
        for (Session session : sessionManager.getSessions()) {
            if (!supportsChannelRegistry(session.getConnection())) {
                continue;
            }
            codec.sendMessage(session.getConnection().getFrameSender(), message, 0, false);
        }
    }

    private boolean supportsChannelRegistry(ConnectionInfo info) {
        String version = info != null ? info.getClientVersion() : null;
        if (version == null || version.isBlank()) {
            return false;
        }
        String[] parts = version.split("\\.");
        try {
            int major = parts.length > 0 ? Integer.parseInt(parts[0]) : 0;
            int minor = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            return major > 2 || (major == 2 && minor >= 1);
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private void handleSubscribe(ConnectionInfo info, SubscribeRequest req) {
        Session session = sessionManager.getSession(info);
        if (session == null) {
            sendError(info, 401, "Not authenticated");
            return;
        }

        Module module = moduleRegistry.getModuleByChannel(req.getChannelId());
        if (module == null) {
            sendError(info, 404, "Unknown channel: " + req.getChannelId());
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
            sendError(info, 403, "Channel subscription required");
            return;
        }

        session.updateActivity();
        module.onData(session, req);
    }

    private void handleHeartbeat(ConnectionInfo info, Heartbeat req) {
        connectionManager.updateHeartbeat(info);
    }

    private void sendError(WebSocket conn, int code, String message) {
        if (conn == null) {
            return;
        }
        ErrorMessage error = new ErrorMessage();
        error.setErrorCode(code);
        error.setErrorText(message);
        codec.sendMessage(conn, error, 0, false);
    }

    private void sendError(ConnectionInfo info, int code, String message) {
        ErrorMessage error = new ErrorMessage();
        error.setErrorCode(code);
        error.setErrorText(message);
        codec.sendMessage(info.getFrameSender(), error, 0, false);
    }

    public void shutdown() {
        extensionManager.shutdown();
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

    public ReSyncExtensionManager getExtensionManager() {
        return extensionManager;
    }

    public ReSyncConfig getConfig() {
        return config;
    }

    public Map<String, Object> readinessSnapshot() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("apiEnabled", config.isEnabled());
        snapshot.put("bindHost", config.getBindHost());
        snapshot.put("connectedClients", sessionManager.getSessionCount());
        snapshot.put("maxConnections", config.getMaxConnections());
        snapshot.put("queueMaxGlobalRequests", config.getQueue().getMaxGlobalRequests());
        snapshot.put("queueMaxRequestsPerClient", config.getQueue().getMaxRequestsPerClient());
        snapshot.put("sessionMemoryBytes", sessionManager.getTotalSessionMemory());
        snapshot.put("sessionMemoryLimitBytes", memoryMonitor.getMaxMemoryForSessions());
        snapshot.put("authMode", "adminApiKey");
        snapshot.put("openConnections", openConnections.get());
        return snapshot;
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
