package restudio.resync.network.paper;

import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

public final class NetworkSettings {
    public static final String FILE_NAME = ".resync-network.properties";
    public static final String LEGACY_FILE_NAME = ".resync-network-legacy.properties";
    private static final int MAXIMUM_BYTES = 1_048_576;

    private NetworkSettings() {
    }

    public static Properties load(Path operatorDataDirectory) throws IOException {
        Path root = operatorDataDirectory.toAbsolutePath().normalize();
        Path managed = root.resolveSibling(FILE_NAME);
        boolean external = Files.exists(managed, LinkOption.NOFOLLOW_LINKS);
        Path file = external ? managed : root.resolve("resync.properties");
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return new Properties();
        Properties properties = read(file);
        if (external && (!"1".equals(properties.getProperty("network.config-version", "1"))
                || properties.stringPropertyNames().stream().anyMatch(key -> !key.startsWith("network.")))) {
            throw new IOException("Managed ReSync Network Settings Are Invalid Or Unsupported");
        }
        return properties;
    }

    public static void prepareBootstrap(Path operatorDataDirectory, Path coordinationDirectory) throws IOException {
        Path root = operatorDataDirectory.toAbsolutePath().normalize();
        if (Files.exists(coordinationDirectory, LinkOption.NOFOLLOW_LINKS) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(root) || !Files.exists(root.resolveSibling(FILE_NAME), LinkOption.NOFOLLOW_LINKS)) return;
        Path legacy = root.resolve("resync.properties");
        List<Path> entries;
        try (var files = Files.list(root)) {
            entries = files.limit(2).toList();
        }
        if (entries.size() != 1 || !entries.getFirst().equals(legacy)) return;
        Properties previous = read(legacy);
        if (previous.isEmpty() || previous.stringPropertyNames().stream().anyMatch(key -> !key.startsWith("network."))) return;
        Properties managed = load(root);
        for (String key : List.of("network.id", "network.node-id")) {
            if (previous.getProperty(key, "").isBlank() || !previous.getProperty(key).equals(managed.getProperty(key))) return;
        }
        Path backup = root.resolveSibling(LEGACY_FILE_NAME);
        if (Files.exists(backup, LinkOption.NOFOLLOW_LINKS)) throw new IOException("ReSync Network Settings Backup Already Exists");
        Files.move(legacy, backup);
        Files.delete(root);
    }

    private static Properties read(Path file) throws IOException {
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > MAXIMUM_BYTES) {
            throw new IOException("ReSync Network Settings Must Be A Bounded Regular File");
        }
        byte[] content;
        try (InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            content = input.readNBytes(MAXIMUM_BYTES + 1);
        }
        if (content.length > MAXIMUM_BYTES) throw new IOException("ReSync Network Settings Are Too Large");
        Properties properties = new Properties();
        properties.load(new ByteArrayInputStream(content));
        return properties;
    }
}
