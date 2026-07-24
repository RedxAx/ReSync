package restudio.resync.network.paper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

public record ReSyncNetworkAgentConfig(boolean enabled, ChatPolicy chat, ResourcePolicy resources, String networkId, String nodeId, String displayName, String hubUrl, String enrollmentToken, String credential, int capacity, int maximumFrameBytes, int maximumPayloadBytes, long heartbeatIntervalTicks, long reconnectDelayTicks, Tls tls, Path credentialFile) {
    public ReSyncNetworkAgentConfig {
        chat = chat == null ? ChatPolicy.disabled() : chat;
        resources = resources == null ? ResourcePolicy.disabled() : resources;
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
        ChatPolicy chat = new ChatPolicy(
            Boolean.parseBoolean(properties.getProperty("network.chat.enabled", "false")),
            enumValue(properties, "network.chat.channel-mode", SelectionMode.class, SelectionMode.ALL),
            values(properties, "network.chat.channels"),
            longValue(properties, "network.chat.retention-millis", 120_000)
        );
        ResourcePolicy resources = new ResourcePolicy(
            Boolean.parseBoolean(properties.getProperty("network.resources.enabled", "false")),
            enumValue(properties, "network.resources.type-mode", SelectionMode.class, SelectionMode.ALL),
            values(properties, "network.resources.types"),
            enumValue(properties, "network.resources.conflict-policy", ResourceConflictPolicy.class, ResourceConflictPolicy.NETWORK_WINS)
        );
        return new ReSyncNetworkAgentConfig(Boolean.parseBoolean(properties.getProperty("network.enabled", "false")), chat, resources, properties.getProperty("network.id", ""), properties.getProperty("network.node-id", ""), properties.getProperty("network.display-name", "Backend"), properties.getProperty("network.hub-url", ""), properties.getProperty("network.enrollment-token", ""), credential, integer(properties, "network.capacity", 0), integer(properties, "network.maximum-frame-bytes", 1_048_576), integer(properties, "network.maximum-payload-bytes", 524_288), longValue(properties, "network.heartbeat-interval-ticks", 100), longValue(properties, "network.reconnect-delay-ticks", 100), tls, credentialFile);
    }

    public boolean chatEnabled() {
        return chat.enabled();
    }

    public boolean resourcesEnabled() {
        return resources.enabled();
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

    public record ChatPolicy(boolean enabled, SelectionMode channelMode, Set<String> channels, long retentionMillis) {
        private static final long MINIMUM_RETENTION_MILLIS = 1_000;
        private static final long MAXIMUM_RETENTION_MILLIS = 31_536_000_000L;

        public ChatPolicy {
            channelMode = channelMode == null ? SelectionMode.ALL : channelMode;
            channels = normalizedValues(channels);
            if (retentionMillis < MINIMUM_RETENTION_MILLIS || retentionMillis > MAXIMUM_RETENTION_MILLIS) {
                throw new IllegalArgumentException("ReSync Network Chat Retention Must Be Between 1,000 And 31,536,000,000 Milliseconds");
            }
        }

        public boolean includes(String channelId) {
            return channelMode.includes(channels, channelId);
        }

        public static ChatPolicy disabled() {
            return new ChatPolicy(false, SelectionMode.ALL, Set.of(), 120_000);
        }
    }

    public record ResourcePolicy(boolean enabled, SelectionMode typeMode, Set<String> types, ResourceConflictPolicy conflictPolicy) {
        public ResourcePolicy {
            typeMode = typeMode == null ? SelectionMode.ALL : typeMode;
            types = normalizedValues(types);
            conflictPolicy = conflictPolicy == null ? ResourceConflictPolicy.NETWORK_WINS : conflictPolicy;
        }

        public boolean includes(String type) {
            return typeMode.includes(types, type);
        }

        public ResourcePolicy withIncluded(String type) {
            String normalized = normalize(type).toLowerCase(Locale.ROOT);
            if (normalized.isBlank() || enabled && includes(normalized)) {
                return this;
            }
            if (!enabled) {
                return new ResourcePolicy(true, SelectionMode.ALLOW_LIST, Set.of(normalized), conflictPolicy);
            }
            Set<String> selected = new LinkedHashSet<>(types);
            if (typeMode == SelectionMode.ALLOW_LIST) {
                selected.add(normalized);
            } else if (typeMode == SelectionMode.DENY_LIST) {
                selected.remove(normalized);
            }
            return new ResourcePolicy(true, typeMode, selected, conflictPolicy);
        }

        public static ResourcePolicy disabled() {
            return new ResourcePolicy(false, SelectionMode.ALL, Set.of(), ResourceConflictPolicy.NETWORK_WINS);
        }
    }

    public enum SelectionMode {
        ALL,
        ALLOW_LIST,
        DENY_LIST;

        private boolean includes(Set<String> values, String value) {
            boolean selected = values.contains(normalize(value).toLowerCase(Locale.ROOT));
            return switch (this) {
                case ALL -> true;
                case ALLOW_LIST -> selected;
                case DENY_LIST -> !selected;
            };
        }
    }

    public enum ResourceConflictPolicy {
        NETWORK_WINS,
        LOCAL_WINS
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

    private static Set<String> values(Properties properties, String key) {
        return normalizedValues(new LinkedHashSet<>(Arrays.asList(properties.getProperty(key, "").split(","))));
    }

    private static Set<String> normalizedValues(Set<String> values) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            String candidate = normalize(value).toLowerCase(Locale.ROOT);
            if (!candidate.isBlank()) {
                normalized.add(candidate);
            }
        }
        return Set.copyOf(normalized);
    }

    private static <E extends Enum<E>> E enumValue(Properties properties, String key, Class<E> type, E fallback) {
        String value = normalize(properties.getProperty(key, fallback.name())).toUpperCase(Locale.ROOT).replace('-', '_');
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("ReSync Network Property Is Invalid: " + key, exception);
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
