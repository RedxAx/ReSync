package restudio.resync.server;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public class ConfigLoader {
    public static ReSyncConfig load(String configPath) {
        ReSyncConfig config = new ReSyncConfig();
        Path configFile = Path.of(configPath);

        Properties props = new Properties();

        if (Files.exists(configFile)) {
            try (FileInputStream fis = new FileInputStream(configFile.toFile())) {
                props.load(fis);
            } catch (Exception e) {
                System.err.println("Failed to load config: " + e.getMessage());
            }
        }

        config.setEnabled(Boolean.parseBoolean(props.getProperty("enabled", "true")));
        config.setPort(Integer.parseInt(props.getProperty("port", "8080")));

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

        saveConfig(configFile, props);

        return config;
    }

    private static void saveConfig(Path configFile, Properties props) {
        try (FileOutputStream fos = new FileOutputStream(configFile.toFile())) {
            props.store(fos, "ReSync v2 Configuration");
        } catch (Exception e) {
            System.err.println("Failed to save config: " + e.getMessage());
        }
    }

    private static String generateApiKey() {
        return java.util.UUID.randomUUID().toString().replace("-", "");
    }
}
