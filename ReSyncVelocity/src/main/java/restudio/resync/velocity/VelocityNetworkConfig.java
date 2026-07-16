package restudio.resync.velocity;

import restudio.resync.network.NetworkRoute;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public record VelocityNetworkConfig(boolean enabled, String networkId, String nodeId, String displayName, String bindHost, int port, Path databasePath, int maximumFrameBytes, int maximumPayloadBytes, long heartbeatTimeoutMillis, long snapshotRetentionMillis, int snapshotRetentionPerPlayerFamily, Tls tls, Map<String, EnrollmentNode> enrollmentNodes, Map<String, NetworkRoute> routes, String maintenanceRoute, Path dataDirectory) {
    public VelocityNetworkConfig {
        networkId = normalize(networkId);
        nodeId = normalize(nodeId);
        displayName = normalize(displayName);
        bindHost = normalize(bindHost);
        databasePath = databasePath == null ? Path.of("network.db") : databasePath.toAbsolutePath().normalize();
        dataDirectory = dataDirectory == null ? databasePath.getParent() : dataDirectory.toAbsolutePath().normalize();
        tls = tls == null ? Tls.disabled() : tls;
        enrollmentNodes = enrollmentNodes == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(enrollmentNodes));
        routes = routes == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(routes));
        maintenanceRoute = normalize(maintenanceRoute).toLowerCase(Locale.ROOT);
        if (enabled) {
            if (networkId.isBlank() || nodeId.isBlank()) {
                throw new IllegalArgumentException("Network ID And Proxy Node ID Are Required");
            }
            if (bindHost.isBlank()) {
                throw new IllegalArgumentException("Network Hub Bind Host Is Required");
            }
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException("Network Hub Port Is Invalid");
            }
            if (maximumFrameBytes < 256 || maximumPayloadBytes < 0 || maximumPayloadBytes > maximumFrameBytes) {
                throw new IllegalArgumentException("Network Hub Frame Limits Are Invalid");
            }
            if (heartbeatTimeoutMillis < 1000) {
                throw new IllegalArgumentException("Network Hub Heartbeat Timeout Is Invalid");
            }
            if (snapshotRetentionMillis < 86400000L || snapshotRetentionPerPlayerFamily < 1 || snapshotRetentionPerPlayerFamily > 1000) {
                throw new IllegalArgumentException("Network Snapshot Retention Is Invalid");
            }
            if (!loopback(bindHost) && !tls.enabled()) {
                throw new IllegalArgumentException("Public Network Hub Binding Requires TLS");
            }
            routes.forEach((name, route) -> {
                if (route == null || !name.equals(route.routeName())) {
                    throw new IllegalArgumentException("Network Hub Route Map Is Invalid");
                }
            });
            if (!maintenanceRoute.isBlank() && !routes.containsKey(maintenanceRoute)) {
                throw new IllegalArgumentException("Network Maintenance Route Is Unknown");
            }
        }
    }

    public record Tls(boolean enabled, Path keyStore, String keyStorePassword, Path trustStore, String trustStorePassword) {
        public Tls {
            keyStorePassword = normalize(keyStorePassword);
            trustStorePassword = normalize(trustStorePassword);
            if (enabled && (keyStore == null || keyStorePassword.isBlank())) {
                throw new IllegalArgumentException("Network Hub TLS Key Store Is Required");
            }
            if (keyStore != null) {
                keyStore = keyStore.toAbsolutePath().normalize();
            }
            if (trustStore != null) {
                trustStore = trustStore.toAbsolutePath().normalize();
            }
        }

        public static Tls disabled() {
            return new Tls(false, null, "", null, "");
        }
    }

    public record EnrollmentNode(String nodeId, String displayName, String role, byte[] tokenHash, long expiresAt, Set<String> capabilities) {
        public EnrollmentNode {
            nodeId = required(nodeId, "Node ID");
            displayName = required(displayName, "Node Name");
            role = required(role, "Node Role");
            if (tokenHash == null || tokenHash.length != 32) {
                throw new IllegalArgumentException("Enrollment Token Hash Must Be SHA-256");
            }
            tokenHash = tokenHash.clone();
            capabilities = capabilities == null ? Set.of() : Set.copyOf(new LinkedHashSet<>(capabilities));
        }

        @Override
        public byte[] tokenHash() {
            return tokenHash.clone();
        }
    }

    private static boolean loopback(String host) {
        return host.isBlank() || host.equals("127.0.0.1") || host.equals("::1") || host.equals("0:0:0:0:0:0:0:1") || host.equalsIgnoreCase("localhost");
    }

    private static String required(String value, String label) {
        String normalized = normalize(value);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(label + " Is Required");
        }
        return normalized;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
