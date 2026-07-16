package restudio.resync.velocity;

import restudio.resync.network.NetworkRoute;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

public final class VelocityNetworkConfigLoader {
    private VelocityNetworkConfigLoader() {
    }

    public static VelocityNetworkConfig load(Path dataDirectory) throws IOException {
        Path root = dataDirectory.toAbsolutePath().normalize();
        Files.createDirectories(root);
        Path file = root.resolve("network.properties");
        Properties properties = new Properties();
        if (Files.exists(file)) {
            try (InputStream input = Files.newInputStream(file)) {
                properties.load(input);
            }
        } else {
            properties.setProperty("network.enabled", "false");
            properties.setProperty("hub.bind-host", "127.0.0.1");
            properties.setProperty("hub.port", "12442");
            properties.setProperty("hub.maximum-frame-bytes", "1048576");
            properties.setProperty("hub.maximum-payload-bytes", "524288");
            properties.setProperty("hub.heartbeat-timeout-millis", "15000");
            properties.setProperty("snapshot.retention-millis", "2592000000");
            properties.setProperty("snapshot.retention-per-player-family", "20");
            try (OutputStream output = Files.newOutputStream(file)) {
                properties.store(output, "ReSync Velocity Network");
            }
        }
        boolean tlsEnabled = booleanValue(properties, "hub.tls.enabled", false);
        Path keyStore = path(root, properties.getProperty("hub.tls.key-store", ""));
        Path trustStore = path(root, properties.getProperty("hub.tls.trust-store", ""));
        String keyPassword = environment(properties.getProperty("hub.tls.key-store-password-env", "RESYNC_HUB_KEYSTORE_PASSWORD"));
        String trustPassword = environment(properties.getProperty("hub.tls.trust-store-password-env", "RESYNC_HUB_TRUSTSTORE_PASSWORD"));
        VelocityNetworkConfig.Tls tls = new VelocityNetworkConfig.Tls(tlsEnabled, keyStore, keyPassword, trustStore, trustPassword);
        Map<String, VelocityNetworkConfig.EnrollmentNode> nodes = new LinkedHashMap<>();
        for (String nodeId : values(properties.getProperty("nodes", ""))) {
            String prefix = "node." + nodeId + ".";
            byte[] tokenHash = Base64.getUrlDecoder().decode(properties.getProperty(prefix + "enrollment-token-hash", ""));
            Set<String> capabilities = new LinkedHashSet<>(values(properties.getProperty(prefix + "capabilities", "presence")));
            VelocityNetworkConfig.EnrollmentNode node = new VelocityNetworkConfig.EnrollmentNode(nodeId, properties.getProperty(prefix + "display-name", nodeId), properties.getProperty(prefix + "role", "BACKEND"), tokenHash, longValue(properties, prefix + "enrollment-expires-at", 0), capabilities);
            nodes.put(nodeId, node);
        }
        Map<String, NetworkRoute> routes = new LinkedHashMap<>();
        for (String routeName : values(properties.getProperty("routes", ""))) {
            String prefix = "route." + routeName + ".";
            NetworkRoute route = new NetworkRoute(properties.getProperty(prefix + "node-id", ""), routeName, properties.getProperty(prefix + "address", ""), integer(properties, prefix + "port", 0));
            routes.put(route.routeName(), route);
        }
        String databaseValue = properties.getProperty("hub.database", "network/network.db").trim();
        Path database = path(root, databaseValue.isBlank() ? "network/network.db" : databaseValue);
        return new VelocityNetworkConfig(booleanValue(properties, "network.enabled", false), properties.getProperty("network.id", ""), properties.getProperty("network.node-id", ""), properties.getProperty("network.display-name", "Proxy"), properties.getProperty("hub.bind-host", "127.0.0.1"), integer(properties, "hub.port", 12442), database, integer(properties, "hub.maximum-frame-bytes", 1_048_576), integer(properties, "hub.maximum-payload-bytes", 524_288), longValue(properties, "hub.heartbeat-timeout-millis", 15_000), longValue(properties, "snapshot.retention-millis", 2_592_000_000L), integer(properties, "snapshot.retention-per-player-family", 20), tls, nodes, routes, properties.getProperty("maintenance-route", ""), root);
    }

    private static Set<String> values(String value) {
        Set<String> values = new LinkedHashSet<>();
        if (value != null) {
            for (String item : value.split(",")) {
                if (!item.isBlank()) {
                    values.add(item.trim());
                }
            }
        }
        return values;
    }

    private static Path path(Path dataDirectory, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        Path path = Path.of(value.trim());
        Path resolved = path.isAbsolute() ? path.normalize() : dataDirectory.resolve(path).normalize();
        if (!resolved.startsWith(dataDirectory)) {
            throw new IllegalArgumentException("ReSync Velocity Network Files Must Stay Inside The Plugin Directory");
        }
        return resolved;
    }

    private static String environment(String name) {
        String value = name == null || name.isBlank() ? "" : System.getenv(name.trim());
        return value == null ? "" : value.trim();
    }

    private static boolean booleanValue(Properties properties, String key, boolean fallback) {
        return Boolean.parseBoolean(properties.getProperty(key, String.valueOf(fallback)));
    }

    private static int integer(Properties properties, String key, int fallback) {
        return Integer.parseInt(properties.getProperty(key, String.valueOf(fallback)).trim());
    }

    private static long longValue(Properties properties, String key, long fallback) {
        return Long.parseLong(properties.getProperty(key, String.valueOf(fallback)).trim());
    }
}
