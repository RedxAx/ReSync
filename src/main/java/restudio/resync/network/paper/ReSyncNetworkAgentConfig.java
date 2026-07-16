package restudio.resync.network.paper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Properties;
import java.util.Set;

public record ReSyncNetworkAgentConfig(boolean enabled, String networkId, String nodeId, String displayName, String hubUrl, String enrollmentToken, String credential, int capacity, int maximumFrameBytes, int maximumPayloadBytes, long heartbeatIntervalTicks, long reconnectDelayTicks, Tls tls, Path credentialFile) {
    public ReSyncNetworkAgentConfig {
        networkId = normalize(networkId);
        nodeId = normalize(nodeId);
        displayName = normalize(displayName);
        hubUrl = normalize(hubUrl);
        enrollmentToken = normalize(enrollmentToken);
        credential = normalize(credential);
        tls = tls == null ? Tls.disabled() : tls;
        if (capacity < 0) {
            throw new IllegalArgumentException("ReSync Network Capacity Cannot Be Negative");
        }
        if (maximumFrameBytes < 256 || maximumPayloadBytes < 0 || maximumPayloadBytes > maximumFrameBytes) {
            throw new IllegalArgumentException("ReSync Network Frame Limits Are Invalid");
        }
        if (heartbeatIntervalTicks < 1 || reconnectDelayTicks < 1) {
            throw new IllegalArgumentException("ReSync Network Timing Is Invalid");
        }
        if (enabled) {
            if (networkId.isBlank() || nodeId.isBlank() || hubUrl.isBlank()) {
                throw new IllegalArgumentException("ReSync Network ID, Node ID, And Hub URL Are Required");
            }
            if (credential.isBlank() && enrollmentToken.isBlank()) {
                throw new IllegalArgumentException("ReSync Network Enrollment Is Required");
            }
            URI hub = URI.create(hubUrl);
            if ("ws".equalsIgnoreCase(hub.getScheme()) && !loopbackHub(hub)) {
                throw new IllegalArgumentException("Cross-Host ReSync Network Connections Require WSS");
            }
            if ("wss".equalsIgnoreCase(hub.getScheme()) && !tls.enabled()) {
                throw new IllegalArgumentException("ReSync Network WSS Trust Store Is Required");
            }
            if (!"ws".equalsIgnoreCase(hub.getScheme()) && !"wss".equalsIgnoreCase(hub.getScheme())) {
                throw new IllegalArgumentException("ReSync Network Hub URL Must Use WS Or WSS");
            }
        }
    }

    public static ReSyncNetworkAgentConfig load(Path dataDirectory) throws IOException {
        Path root = dataDirectory.toAbsolutePath().normalize();
        Path propertiesFile = root.resolve("resync.properties");
        Properties properties = new Properties();
        if (Files.exists(propertiesFile)) {
            try (InputStream input = Files.newInputStream(propertiesFile)) {
                properties.load(input);
            }
        }
        Path credentialFile = root.resolve(properties.getProperty("network.credential-file", "network/node.credential")).normalize();
        if (!credentialFile.startsWith(root)) {
            throw new IllegalArgumentException("ReSync Network Credential File Must Stay Inside The Plugin Directory");
        }
        String credential = Files.exists(credentialFile) ? Files.readString(credentialFile).trim() : "";
        String trustStoreValue = properties.getProperty("network.tls.trust-store", "").trim();
        Path trustStore = trustStoreValue.isBlank() ? null : root.resolve(trustStoreValue).normalize();
        if (trustStore != null && !trustStore.startsWith(root)) {
            throw new IllegalArgumentException("ReSync Network Trust Store Must Stay Inside The Plugin Directory");
        }
        String passwordEnvironment = properties.getProperty("network.tls.trust-store-password-env", "RESYNC_NETWORK_TRUSTSTORE_PASSWORD").trim();
        String trustStorePassword = passwordEnvironment.isBlank() ? "" : System.getenv(passwordEnvironment);
        Tls tls = new Tls(trustStore != null, trustStore, trustStorePassword == null ? "" : trustStorePassword);
        return new ReSyncNetworkAgentConfig(Boolean.parseBoolean(properties.getProperty("network.enabled", "false")), properties.getProperty("network.id", ""), properties.getProperty("network.node-id", ""), properties.getProperty("network.display-name", "Backend"), properties.getProperty("network.hub-url", ""), properties.getProperty("network.enrollment-token", ""), credential, integer(properties, "network.capacity", 0), integer(properties, "network.maximum-frame-bytes", 1_048_576), integer(properties, "network.maximum-payload-bytes", 524_288), longValue(properties, "network.heartbeat-interval-ticks", 100), longValue(properties, "network.reconnect-delay-ticks", 100), tls, credentialFile);
    }

    public void saveCredential(String value) throws IOException {
        String credential = normalize(value);
        if (credential.isBlank()) {
            throw new IllegalArgumentException("ReSync Network Credential Is Required");
        }
        Path parent = credentialFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temporary = credentialFile.resolveSibling(credentialFile.getFileName() + ".tmp");
        Files.writeString(temporary, credential);
        try {
            Files.setPosixFilePermissions(temporary, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException ignored) {
        }
        try {
            Files.move(temporary, credentialFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException exception) {
            Files.move(temporary, credentialFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public void clearCredential() throws IOException {
        Files.deleteIfExists(credentialFile);
    }

    public record Tls(boolean enabled, Path trustStore, String trustStorePassword) {
        public Tls {
            trustStorePassword = normalize(trustStorePassword);
            if (enabled && (trustStore == null || trustStorePassword.isBlank())) {
                throw new IllegalArgumentException("ReSync Network Trust Store And Password Are Required");
            }
        }

        public static Tls disabled() {
            return new Tls(false, null, "");
        }
    }

    private static boolean loopbackHub(URI hub) {
        String host = normalize(hub.getHost()).toLowerCase();
        return host.equals("127.0.0.1") || host.equals("localhost") || host.equals("::1") || host.equals("[::1]") || host.equals("0:0:0:0:0:0:0:1") || host.equals("[0:0:0:0:0:0:0:1]");
    }

    private static int integer(Properties properties, String key, int fallback) {
        return Integer.parseInt(properties.getProperty(key, String.valueOf(fallback)).trim());
    }

    private static long longValue(Properties properties, String key, long fallback) {
        return Long.parseLong(properties.getProperty(key, String.valueOf(fallback)).trim());
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
