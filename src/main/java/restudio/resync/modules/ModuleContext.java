package restudio.resync.modules;

import restudio.resync.ReSync;
import restudio.resync.compression.CompressionPool;
import restudio.resync.core.ChannelMuxer;
import restudio.resync.core.ConnectionManager;
import restudio.resync.core.SessionManager;
import restudio.resync.memory.MemoryMonitor;
import restudio.resync.protocol.Codec;
import restudio.resync.queue.RateLimiter;
import restudio.resync.queue.RequestQueue;
import restudio.resync.server.ReSyncConfig;
import restudio.resync.server.ReSyncServer;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;

public class ModuleContext {
    private final ReSync plugin;
    private final ReSyncServer server;
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
    private final Map<Class<?>, Object> services = new ConcurrentHashMap<>();

    public ModuleContext(ReSync plugin, ReSyncServer server, ReSyncConfig config, ConnectionManager connectionManager,
                         SessionManager sessionManager, ChannelMuxer channelMuxer, ModuleRegistry moduleRegistry,
                         RequestQueue requestQueue, RateLimiter rateLimiter, CompressionPool compressionPool,
                         Codec codec, ScheduledExecutorService scheduler, MemoryMonitor memoryMonitor) {
        this.plugin = plugin;
        this.server = server;
        this.config = config;
        this.connectionManager = connectionManager;
        this.sessionManager = sessionManager;
        this.channelMuxer = channelMuxer;
        this.moduleRegistry = moduleRegistry;
        this.requestQueue = requestQueue;
        this.rateLimiter = rateLimiter;
        this.compressionPool = compressionPool;
        this.codec = codec;
        this.scheduler = scheduler;
        this.memoryMonitor = memoryMonitor;
    }

    public ReSync getPlugin() {
        return plugin;
    }

    public ReSyncServer getServer() {
        return server;
    }

    public ReSyncConfig getConfig() {
        return config;
    }

    public ConnectionManager getConnectionManager() {
        return connectionManager;
    }

    public SessionManager getSessionManager() {
        return sessionManager;
    }

    public ChannelMuxer getChannelMuxer() {
        return channelMuxer;
    }

    public ModuleRegistry getModuleRegistry() {
        return moduleRegistry;
    }

    public RequestQueue getRequestQueue() {
        return requestQueue;
    }

    public RateLimiter getRateLimiter() {
        return rateLimiter;
    }

    public CompressionPool getCompressionPool() {
        return compressionPool;
    }

    public Codec getCodec() {
        return codec;
    }

    public ScheduledExecutorService getScheduler() {
        return scheduler;
    }

    public MemoryMonitor getMemoryMonitor() {
        return memoryMonitor;
    }

    public <T> void registerService(Class<T> type, T service) {
        if (type == null || service == null) {
            return;
        }
        services.put(type, service);
    }

    public <T> T getService(Class<T> type) {
        Object service = services.get(type);
        return service == null ? null : type.cast(service);
    }

    public <T> T getRequiredService(Class<T> type) {
        T service = getService(type);
        if (service == null) {
            throw new IllegalStateException("Missing service: " + type.getName());
        }
        return service;
    }
}
