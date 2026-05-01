package restudio.resync.server;

public class ReSyncConfig {
    private boolean enabled;
    private int port;
    private String apiKey;
    private int maxConnections;
    private String bindHost;
    private int maxEncodedFrameBytes;
    private int maxDecompressedPayloadBytes;
    private String logLevel;

    private CompressionConfig compression;
    private BatchingConfig batching;
    private QueueConfig queue;
    private MemoryConfig memory;
    private PlayerTrackingConfig playerTracking;

    public static class CompressionConfig {
        private boolean enabled;
        private int level;
        private int threshold;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getLevel() {
            return level;
        }

        public void setLevel(int level) {
            this.level = level;
        }

        public int getThreshold() {
            return threshold;
        }

        public void setThreshold(int threshold) {
            this.threshold = threshold;
        }
    }

    public static class BatchingConfig {
        private boolean enabled;
        private int maxBatchSize;
        private int maxBatchDelay;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getMaxBatchSize() {
            return maxBatchSize;
        }

        public void setMaxBatchSize(int maxBatchSize) {
            this.maxBatchSize = maxBatchSize;
        }

        public int getMaxBatchDelay() {
            return maxBatchDelay;
        }

        public void setMaxBatchDelay(int maxBatchDelay) {
            this.maxBatchDelay = maxBatchDelay;
        }
    }

    public static class QueueConfig {
        private int maxRequestsPerClient;
        private int maxGlobalRequests;
        private double tpsThreshold;

        public int getMaxRequestsPerClient() {
            return maxRequestsPerClient;
        }

        public void setMaxRequestsPerClient(int maxRequestsPerClient) {
            this.maxRequestsPerClient = maxRequestsPerClient;
        }

        public int getMaxGlobalRequests() {
            return maxGlobalRequests;
        }

        public void setMaxGlobalRequests(int maxGlobalRequests) {
            this.maxGlobalRequests = maxGlobalRequests;
        }

        public double getTpsThreshold() {
            return tpsThreshold;
        }

        public void setTpsThreshold(double tpsThreshold) {
            this.tpsThreshold = tpsThreshold;
        }
    }

    public static class MemoryConfig {
        private int maxCacheSize;
        private long maxMemoryPerSession;
        private long cacheTtlMinutes;
        private double sessionMemoryRatio;

        public int getMaxCacheSize() {
            return maxCacheSize;
        }

        public void setMaxCacheSize(int maxCacheSize) {
            this.maxCacheSize = maxCacheSize;
        }

        public long getMaxMemoryPerSession() {
            return maxMemoryPerSession;
        }

        public void setMaxMemoryPerSession(long maxMemoryPerSession) {
            this.maxMemoryPerSession = maxMemoryPerSession;
        }

        public long getCacheTtlMinutes() {
            return cacheTtlMinutes;
        }

        public void setCacheTtlMinutes(long cacheTtlMinutes) {
            this.cacheTtlMinutes = cacheTtlMinutes;
        }

        public double getSessionMemoryRatio() {
            return sessionMemoryRatio;
        }

        public void setSessionMemoryRatio(double sessionMemoryRatio) {
            this.sessionMemoryRatio = sessionMemoryRatio;
        }
    }

    public static class PlayerTrackingConfig {
        private boolean captureChatText;
        private boolean captureCommandArguments;

        public boolean isCaptureChatText() {
            return captureChatText;
        }

        public void setCaptureChatText(boolean captureChatText) {
            this.captureChatText = captureChatText;
        }

        public boolean isCaptureCommandArguments() {
            return captureCommandArguments;
        }

        public void setCaptureCommandArguments(boolean captureCommandArguments) {
            this.captureCommandArguments = captureCommandArguments;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public int getMaxConnections() {
        return maxConnections;
    }

    public void setMaxConnections(int maxConnections) {
        this.maxConnections = maxConnections;
    }

    public String getBindHost() {
        return bindHost;
    }

    public void setBindHost(String bindHost) {
        this.bindHost = bindHost;
    }

    public int getMaxEncodedFrameBytes() {
        return maxEncodedFrameBytes;
    }

    public void setMaxEncodedFrameBytes(int maxEncodedFrameBytes) {
        this.maxEncodedFrameBytes = maxEncodedFrameBytes;
    }

    public int getMaxDecompressedPayloadBytes() {
        return maxDecompressedPayloadBytes;
    }

    public void setMaxDecompressedPayloadBytes(int maxDecompressedPayloadBytes) {
        this.maxDecompressedPayloadBytes = maxDecompressedPayloadBytes;
    }

    public String getLogLevel() {
        return logLevel;
    }

    public void setLogLevel(String logLevel) {
        this.logLevel = logLevel;
    }

    public CompressionConfig getCompression() {
        return compression;
    }

    public void setCompression(CompressionConfig compression) {
        this.compression = compression;
    }

    public BatchingConfig getBatching() {
        return batching;
    }

    public void setBatching(BatchingConfig batching) {
        this.batching = batching;
    }

    public QueueConfig getQueue() {
        return queue;
    }

    public void setQueue(QueueConfig queue) {
        this.queue = queue;
    }

    public MemoryConfig getMemory() {
        return memory;
    }

    public void setMemory(MemoryConfig memory) {
        this.memory = memory;
    }

    public PlayerTrackingConfig getPlayerTracking() {
        return playerTracking;
    }

    public void setPlayerTracking(PlayerTrackingConfig playerTracking) {
        this.playerTracking = playerTracking;
    }
}
