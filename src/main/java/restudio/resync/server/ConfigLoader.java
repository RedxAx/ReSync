package restudio.resync.server;

import restudio.resync.Log;
import restudio.resync.protocol.Codec;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.UUID;

public class ConfigLoader {
    public static ReSyncConfig load(String configPath) {
        ReSyncConfig config = new ReSyncConfig();
        Path configFile = Path.of(configPath);

        Properties props = new Properties();

        if (Files.exists(configFile)) {
            try (FileInputStream fis = new FileInputStream(configFile.toFile())) {
                props.load(fis);
            } catch (Exception e) {
                Log.error("Failed to load config: " + e.getMessage(), e);
            }
        }

        ensureDefault(props, "enabled", "true");
        ensureDefault(props, "port", "12441");
        ensureDefault(props, "bind-host", "127.0.0.1");
        ensureDefault(props, "public-bind-enabled", "false");
        config.setEnabled(Boolean.parseBoolean(props.getProperty("enabled", "true")));
        config.setPort(Integer.parseInt(props.getProperty("port", "12441")));

        String apiKey = System.getenv("RESYNC_API_KEY");
        if (apiKey == null || apiKey.isEmpty()) {
            apiKey = props.getProperty("api-key", "");
        }
        if (apiKey.isEmpty()) {
            apiKey = generateApiKey();
            props.setProperty("api-key", apiKey);
        }
        config.setApiKey(apiKey);

        config.setMaxConnections(Integer.parseInt(props.getProperty("maxConnections", "10")));
        config.setBindHost(props.getProperty("bind-host", "127.0.0.1"));
        config.setMaxEncodedFrameBytes(Integer.parseInt(props.getProperty("protocol.maxEncodedFrameBytes", String.valueOf(Codec.DEFAULT_MAX_ENCODED_FRAME_BYTES))));
        config.setMaxDecompressedPayloadBytes(Integer.parseInt(props.getProperty("protocol.maxDecompressedPayloadBytes", String.valueOf(Codec.DEFAULT_MAX_DECOMPRESSED_PAYLOAD_BYTES))));
        config.setLogLevel(props.getProperty("log-level", "info"));

        ReSyncConfig.CompressionConfig compression = new ReSyncConfig.CompressionConfig();
        compression.setEnabled(Boolean.parseBoolean(props.getProperty("compression.enabled", "true")));
        compression.setLevel(Integer.parseInt(props.getProperty("compression.level", "6")));
        compression.setThreshold(Integer.parseInt(props.getProperty("compression.threshold", "1024")));
        config.setCompression(compression);

        ReSyncConfig.BatchingConfig batching = new ReSyncConfig.BatchingConfig();
        batching.setEnabled(Boolean.parseBoolean(props.getProperty("batching.enabled", "true")));
        batching.setMaxBatchSize(Integer.parseInt(props.getProperty("batching.maxBatchSize", "50")));
        batching.setMaxBatchDelay(Integer.parseInt(props.getProperty("batching.maxBatchDelay", "100")));
        config.setBatching(batching);

        ReSyncConfig.QueueConfig queue = new ReSyncConfig.QueueConfig();
        queue.setMaxRequestsPerClient(Integer.parseInt(props.getProperty("queue.maxRequestsPerClient", "500")));
        queue.setMaxGlobalRequests(Integer.parseInt(props.getProperty("queue.maxGlobalRequests", "2000")));
        queue.setTpsThreshold(Double.parseDouble(props.getProperty("queue.tpsThreshold", "50.0")));
        config.setQueue(queue);

        ReSyncConfig.MemoryConfig memory = new ReSyncConfig.MemoryConfig();
        memory.setMaxCacheSize(Integer.parseInt(props.getProperty("cache.max-size", "4096")));
        memory.setMaxMemoryPerSession(Long.parseLong(props.getProperty("memory.maxMemoryPerSession", "52428800")));
        memory.setCacheTtlMinutes(Long.parseLong(props.getProperty("cache.ttl-minutes", "10")));
        memory.setSessionMemoryRatio(Double.parseDouble(props.getProperty("memory.sessionMemoryRatio", "0.3")));
        config.setMemory(memory);

        ReSyncConfig.PlayerTrackingConfig playerTracking = new ReSyncConfig.PlayerTrackingConfig();
        playerTracking.setCaptureChatText(Boolean.parseBoolean(props.getProperty("playerTracking.captureChatText", "false")));
        playerTracking.setCaptureCommandArguments(Boolean.parseBoolean(props.getProperty("playerTracking.captureCommandArguments", "false")));
        config.setPlayerTracking(playerTracking);
        validateProductionConfig(config, props);

        saveConfig(configFile, props);

        return config;
    }

    private static void saveConfig(Path configFile, Properties props) {
        try {
            Path parent = configFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (Exception e) {
            Log.error("Failed to create config directory: " + e.getMessage(), e);
            return;
        }

        try (FileOutputStream fos = new FileOutputStream(configFile.toFile())) {
            props.store(fos, "ReSync Configuration");
        } catch (Exception e) {
            Log.error("Failed to save config: " + e.getMessage(), e);
        }
    }

    private static String generateApiKey() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static void ensureDefault(Properties props, String key, String value) {
        if (!props.containsKey(key)) {
            props.setProperty(key, value);
        }
    }

    private static void validateProductionConfig(ReSyncConfig config, Properties props) {
        if (config.getApiKey() == null || config.getApiKey().isBlank()) {
            Log.error("ReSync API key is empty. WebSocket API disabled.");
            config.setEnabled(false);
        }
        String bindHost = config.getBindHost() == null ? "" : config.getBindHost().trim();
        boolean publicBindEnabled = Boolean.parseBoolean(props.getProperty("public-bind-enabled", "false"));
        if (!isLoopbackBind(bindHost) && !publicBindEnabled) {
            Log.error("ReSync public bind requires public-bind-enabled=true. WebSocket API disabled.");
            config.setEnabled(false);
        }
        if (config.getMaxEncodedFrameBytes() <= 0 || config.getMaxDecompressedPayloadBytes() <= 0 || config.getMaxDecompressedPayloadBytes() < config.getMaxEncodedFrameBytes()) {
            Log.error("ReSync protocol frame limits are invalid. WebSocket API disabled.");
            config.setEnabled(false);
        }
        if (config.getQueue().getMaxGlobalRequests() <= 0 || config.getQueue().getMaxRequestsPerClient() <= 0) {
            Log.error("ReSync queue limits are invalid. WebSocket API disabled.");
            config.setEnabled(false);
        }
    }

    private static boolean isLoopbackBind(String bindHost) {
        return bindHost.isBlank() || "127.0.0.1".equals(bindHost) || "localhost".equalsIgnoreCase(bindHost) || "::1".equals(bindHost);
    }
}
