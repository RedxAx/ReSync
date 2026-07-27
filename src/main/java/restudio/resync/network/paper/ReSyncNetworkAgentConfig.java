package restudio.resync.network.paper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.stream.IntStream;

public record ReSyncNetworkAgentConfig(boolean enabled, ChatPolicy chat, ResourcePolicy resources, List<PathPolicy> pathSyncs, String networkId, String nodeId, String displayName, String hubUrl, String enrollmentToken, String credential, int capacity, int maximumFrameBytes, int maximumPayloadBytes, long heartbeatIntervalTicks, long reconnectDelayTicks, Tls tls, Path credentialFile) {
    public ReSyncNetworkAgentConfig {
        chat = chat == null ? ChatPolicy.disabled() : chat;
        resources = resources == null ? ResourcePolicy.disabled() : resources;
        pathSyncs = normalizedPathPolicies(pathSyncs);
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
        return new ReSyncNetworkAgentConfig(Boolean.parseBoolean(properties.getProperty("network.enabled", "false")), chat, resources, pathPolicies(properties), properties.getProperty("network.id", ""), properties.getProperty("network.node-id", ""), properties.getProperty("network.display-name", "Backend"), properties.getProperty("network.hub-url", ""), properties.getProperty("network.enrollment-token", ""), credential, integer(properties, "network.capacity", 0), integer(properties, "network.maximum-frame-bytes", 1_048_576), integer(properties, "network.maximum-payload-bytes", 500_000), longValue(properties, "network.heartbeat-interval-ticks", 100), longValue(properties, "network.reconnect-delay-ticks", 100), tls, credentialFile);
    }

    public boolean chatEnabled() {
        return chat.enabled();
    }

    public boolean resourcesEnabled() {
        return resources.enabled();
    }

    public boolean pathsEnabled() {
        return pathSyncs.stream().anyMatch(PathPolicy::enabled);
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

    public record PathPolicy(String id, String name, boolean enabled, Set<String> entries, ResourceConflictPolicy conflictPolicy, List<String> commands) {
        public PathPolicy {
            id = normalize(id).toLowerCase(Locale.ROOT);
            name = normalize(name);
            entries = normalizedPaths(entries);
            conflictPolicy = conflictPolicy == null ? ResourceConflictPolicy.NETWORK_WINS : conflictPolicy;
            commands = normalizedCommands(commands);
            if (!id.matches("[a-z0-9][a-z0-9_-]{0,47}")) {
                throw new IllegalArgumentException("ReSync Path Sync ID Must Use Lowercase Letters, Numbers, Dashes, Or Underscores");
            }
            if (name.isBlank() || name.length() > 64) {
                throw new IllegalArgumentException("ReSync Path Sync Name Must Be Between 1 And 64 Characters");
            }
            if (enabled && entries.isEmpty()) {
                throw new IllegalArgumentException("ReSync Path Sync Requires At Least One File Or Folder");
            }
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

    private static Set<String> pathValues(Properties properties, String key) {
        return normalizedPaths(new LinkedHashSet<>(Arrays.asList(properties.getProperty(key, "").split(","))));
    }

    private static List<PathPolicy> pathPolicies(Properties properties) {
        Set<String> ids = values(properties, "network.path-sync.ids");
        if (ids.isEmpty() && properties.containsKey("network.paths.enabled")) {
            return List.of(new PathPolicy("default", "Path Sync", Boolean.parseBoolean(properties.getProperty("network.paths.enabled", "false")), pathValues(properties, "network.paths.entries"), enumValue(properties, "network.paths.conflict-policy", ResourceConflictPolicy.class, ResourceConflictPolicy.NETWORK_WINS), List.of()));
        }
        return ids.stream().map(id -> {
            String prefix = "network.path-sync." + id + ".";
            int commandCount = integer(properties, prefix + "command-count", 0);
            if (commandCount < 0 || commandCount > 32) {
                throw new IllegalArgumentException("ReSync Path Sync Commands Must Be Between 0 And 32");
            }
            List<String> commands = IntStream.range(0, commandCount).mapToObj(index -> properties.getProperty(prefix + "command." + index, "")).toList();
            return new PathPolicy(id, properties.getProperty(prefix + "name", id), Boolean.parseBoolean(properties.getProperty(prefix + "enabled", "false")), pathValues(properties, prefix + "paths"), enumValue(properties, prefix + "conflict-policy", ResourceConflictPolicy.class, ResourceConflictPolicy.NETWORK_WINS), commands);
        }).toList();
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
        return Collections.unmodifiableSet(normalized);
    }

    private static Set<String> normalizedPaths(Set<String> values) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            String candidate = normalize(value).replace('\\', '/');
            while (candidate.endsWith("/")) {
                candidate = candidate.substring(0, candidate.length() - 1);
            }
            if (candidate.isBlank()) {
                continue;
            }
            Path path = Path.of(candidate).normalize();
            if (path.isAbsolute() || path.startsWith("..") || path.getNameCount() < 1) {
                throw new IllegalArgumentException("ReSync Path Sync Entries Must Stay Inside The Server Directory");
            }
            candidate = path.toString().replace('\\', '/');
            if (candidate.isBlank()) {
                candidate = ".";
            }
            normalized.add(candidate);
        }
        return Collections.unmodifiableSet(normalized);
    }

    private static List<PathPolicy> normalizedPathPolicies(List<PathPolicy> policies) {
        if (policies == null || policies.isEmpty()) {
            return List.of();
        }
        List<PathPolicy> normalized = List.copyOf(policies);
        Set<String> ids = new LinkedHashSet<>();
        for (PathPolicy policy : normalized) {
            if (policy == null) {
                throw new IllegalArgumentException("ReSync Path Sync Entry Is Required");
            }
            if (!ids.add(policy.id())) {
                throw new IllegalArgumentException("ReSync Path Sync IDs Must Be Unique");
            }
        }
        for (int first = 0; first < normalized.size(); first++) {
            PathPolicy left = normalized.get(first);
            if (!left.enabled()) {
                continue;
            }
            for (int second = first + 1; second < normalized.size(); second++) {
                PathPolicy right = normalized.get(second);
                if (right.enabled() && overlaps(left.entries(), right.entries())) {
                    throw new IllegalArgumentException("Enabled ReSync Path Sync Entries Cannot Overlap");
                }
            }
        }
        return normalized;
    }

    private static boolean overlaps(Set<String> first, Set<String> second) {
        if (first.contains(".") || second.contains(".")) {
            return true;
        }
        return first.stream().map(Path::of).anyMatch(left -> second.stream().map(Path::of).anyMatch(right -> left.equals(right) || left.startsWith(right) || right.startsWith(left)));
    }

    private static List<String> normalizedCommands(List<String> commands) {
        if (commands == null || commands.isEmpty()) {
            return List.of();
        }
        List<String> normalized = commands.stream().map(ReSyncNetworkAgentConfig::normalize).map(command -> command.startsWith("/") ? command.substring(1).trim() : command).filter(command -> !command.isBlank()).toList();
        if (normalized.size() > 32 || normalized.stream().anyMatch(command -> command.length() > 2_048 || command.indexOf('\u0000') >= 0)) {
            throw new IllegalArgumentException("ReSync Path Sync Commands Are Invalid");
        }
        return normalized;
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
