package restudio.resync.server;

import restudio.resync.Log;
import restudio.resync.protocol.Codec;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

public class ConfigLoader {
    public static ReSyncConfig load(Path dataRoot) {
        Path root = dataRoot.toAbsolutePath().normalize();
        return loadConfig(root.resolve(ConfigurationPersistenceParticipant.FILE_NAME));
    }

    public static ReSyncConfig load(String configPath) {
        return loadConfig(Path.of(configPath));
    }

    private static ReSyncConfig loadConfig(Path configPath) {
        ReSyncConfig config = new ReSyncConfig();
        Path absoluteConfigFile = configPath.toAbsolutePath().normalize();

        Properties props = new Properties();
        ConfigurationPersistenceParticipant persistence = null;

        try {
            persistence = new ConfigurationPersistenceParticipant(absoluteConfigFile.getParent(), absoluteConfigFile, false);
            props.putAll(persistence.properties());
        } catch (Exception exception) {
            throw new IllegalStateException("ReSync Configuration Could Not Be Loaded", exception);
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

        ensureDefault(props, "tls.enabled", "false");
        ensureDefault(props, "tls.spki-fingerprint", "");
        ensureDefault(props, "tls.runtime-metadata-file", "tls/resync-server.runtime.json");
        ensureDefault(props, "tls.subject-alternative-names", "");
        ReSyncConfig.TlsConfig tls = new ReSyncConfig.TlsConfig();
        tls.setEnabled(Boolean.parseBoolean(props.getProperty("tls.enabled", "false")));
        tls.setSpkiFingerprint(props.getProperty("tls.spki-fingerprint", "").strip());
        tls.setRuntimeMetadataFile(props.getProperty("tls.runtime-metadata-file", "tls/resync-server.runtime.json").strip());
        tls.setSubjectAlternativeNames(parseNames(props.getProperty("tls.subject-alternative-names", "")));
        config.setTls(tls);

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

        persistence.replaceProperties(props);
        config.setPersistenceParticipant(persistence);

        return config;
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
        if (config.getTls().isEnabled() && config.getTls().getRuntimeMetadataFile().isBlank()) {
            Log.error("ReSync TLS runtime metadata path is empty. WebSocket API disabled.");
            config.setEnabled(false);
        }
        if (config.getTls().isEnabled() && config.getTls().getSubjectAlternativeNames().isEmpty()) {
            Log.error("ReSync TLS subject alternative names are empty. WebSocket API disabled.");
            config.setEnabled(false);
        }
    }

    private static List<String> parseNames(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split("[,;\\r\\n]+"))
                .map(String::strip)
                .filter(name -> !name.isBlank())
                .distinct()
                .toList();
    }

    private static boolean isLoopbackBind(String bindHost) {
        return bindHost.isBlank() || "127.0.0.1".equals(bindHost) || "localhost".equalsIgnoreCase(bindHost) || "::1".equals(bindHost);
    }
}
