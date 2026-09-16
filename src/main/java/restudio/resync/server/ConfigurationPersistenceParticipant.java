package restudio.resync.server;

import restudio.resync.migration.MigrationPaths;
import restudio.resync.migration.PersistenceOwnershipContext;
import restudio.resync.migration.PersistenceOwnershipIndex;
import restudio.resync.migration.PersistenceOwnershipProvider;
import restudio.resync.migration.RebindablePersistenceParticipant;
import restudio.resync.storage.StorageSafety;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.function.Consumer;

public final class ConfigurationPersistenceParticipant
    implements RebindablePersistenceParticipant, PersistenceOwnershipProvider {
    public static final String OWNER = "resync.configuration";
    public static final String FILE_NAME = "config.properties";

    private final Path scopeRoot;
    private final String fileName;
    private volatile Path file;
    private final Properties properties = new Properties();
    private final Properties durableProperties = new Properties();
    private boolean durabilityDeferred;
    private PersistenceState persistenceState = PersistenceState.OPEN;

    public ConfigurationPersistenceParticipant(Path scopeRoot) throws IOException {
        this(scopeRoot, requireConfigPath(scopeRoot), true);
    }

    public ConfigurationPersistenceParticipant(Path scopeRoot, Path configFile) throws IOException {
        this(scopeRoot, configFile, true);
    }

    ConfigurationPersistenceParticipant(Path scopeRoot, Path configFile, boolean durabilityEnabled) throws IOException {
        this.scopeRoot = prepareScope(scopeRoot);
        this.file = requireConfigPath(this.scopeRoot, configFile);
        this.fileName = this.file.getFileName().toString();
        this.properties.putAll(readProperties(this.file, false));
        this.durableProperties.putAll(this.properties);
        this.durabilityDeferred = !durabilityEnabled;
    }

    @Override
    public String owner() {
        return OWNER;
    }

    @Override
    public synchronized boolean rootMayBeAbsent() {
        return durabilityDeferred;
    }

    @Override
    public synchronized Path root() {
        return file;
    }

    @Override
    public PersistenceOwnershipIndex ownershipIndex(PersistenceOwnershipContext context) {
        return PersistenceOwnershipIndex.builder(context).exactRoot().build();
    }

    @Override
    public Path rebindScope() {
        return scopeRoot;
    }

    public synchronized Properties loadProperties() throws IOException {
        return copy(readProperties(file, false));
    }

    public synchronized Properties properties() {
        return copy(properties);
    }

    public synchronized void replaceProperties(Properties replacement) {
        requireWritable();
        Properties validated = requireProperties(replacement);
        properties.clear();
        properties.putAll(validated);
    }

    public synchronized void replacePropertiesAndFlush(Properties replacement) throws IOException {
        requireWritable();
        Properties previous = copy(properties);
        Properties validated = requireProperties(replacement);
        properties.clear();
        properties.putAll(validated);
        try {
            flush();
        } catch (IOException | RuntimeException exception) {
            properties.clear();
            properties.putAll(previous);
            throw exception;
        }
    }

    public synchronized void updateProperties(Consumer<Properties> updater) throws IOException {
        requireWritable();
        Objects.requireNonNull(updater, "updater");
        Properties previous = copy(properties);
        Properties updated = copy(properties);
        updater.accept(updated);
        Properties validated = requireProperties(updated);
        properties.clear();
        properties.putAll(validated);
        try {
            flush();
        } catch (IOException | RuntimeException exception) {
            properties.clear();
            properties.putAll(previous);
            throw exception;
        }
    }

    @Override
    public synchronized void flush() throws IOException {
        if (durabilityDeferred) {
            return;
        }
        Path activeFile = requireActiveFile();
        StorageSafety.writeBytesAtomic(activeFile, encode(properties));
        durableProperties.clear();
        durableProperties.putAll(properties);
    }

    synchronized void activateAndFlush() throws IOException {
        requireWritable();
        if (!durabilityDeferred) {
            flush();
            return;
        }
        Path activeFile = requireActiveFile();
        if (!properties.equals(durableProperties) || !Files.exists(activeFile, LinkOption.NOFOLLOW_LINKS)) {
            StorageSafety.writeBytesAtomic(activeFile, encode(properties));
        }
        durableProperties.clear();
        durableProperties.putAll(properties);
        durabilityDeferred = false;
    }

    @Override
    public synchronized void quiesce() throws IOException {
        if (persistenceState == PersistenceState.QUIESCED) {
            return;
        }
        flush();
        persistenceState = PersistenceState.QUIESCED;
    }

    @Override
    public synchronized void resume() throws IOException {
        healthCheck();
        persistenceState = PersistenceState.OPEN;
    }

    @Override
    public synchronized void rebind(Path activeRoot) throws IOException {
        if (persistenceState != PersistenceState.QUIESCED) {
            throw new IOException("Configuration persistence must be quiesced before rebind");
        }
        Path scope = MigrationPaths.requireDirectory(activeRoot, "activeRoot");
        Path candidate = requireConfigPath(scope, scope.resolve(fileName));
        boolean candidateExists = Files.exists(candidate, LinkOption.NOFOLLOW_LINKS);
        Properties candidateProperties = readProperties(candidate, !durabilityDeferred);
        file = candidate;
        if (candidateExists) {
            durableProperties.clear();
            durableProperties.putAll(candidateProperties);
            properties.clear();
            properties.putAll(candidateProperties);
        }
    }

    @Override
    public synchronized void healthCheck() throws IOException {
        Path activeFile = requireActiveFile();
        if (!Files.exists(activeFile, LinkOption.NOFOLLOW_LINKS)) {
            if (!durabilityDeferred && !durableProperties.isEmpty()) {
                throw new IOException("Configuration file is missing while properties are dirty");
            }
            StorageSafety.forceDirectory(activeFile.getParent());
            return;
        }
        Properties persisted = readProperties(activeFile, true);
        if (!persisted.equals(durableProperties)) {
            throw new IOException("Configuration properties are out of sync with config.properties");
        }
        StorageSafety.forceDirectory(activeFile.getParent());
    }

    public synchronized boolean isQuiesced() {
        return persistenceState == PersistenceState.QUIESCED;
    }

    public synchronized boolean durabilityDeferred() {
        return durabilityDeferred;
    }

    private Path requireActiveFile() throws IOException {
        Path activeFile = MigrationPaths.requirePath(file, "configuration file");
        Path parent = activeFile.getParent();
        if (parent == null) {
            throw new IOException("Configuration file has no parent");
        }
        if (!Files.exists(parent, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectories(parent);
        }
        MigrationPaths.requireDirectory(parent, "configuration persistence root");
        MigrationPaths.requireNoSymlinkTraversal(parent, activeFile);
        if (Files.exists(activeFile, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(activeFile, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Configuration file must be a regular non-symbolic-link file: " + activeFile);
        }
        return activeFile;
    }

    private void requireWritable() {
        if (persistenceState != PersistenceState.OPEN) {
            throw new IllegalStateException("Configuration persistence is quiesced; mutation rejected");
        }
    }

    private static Properties readProperties(Path file, boolean required) throws IOException {
        Path normalized = MigrationPaths.requirePath(file, "configuration file");
        Path parent = normalized.getParent();
        if (parent == null) {
            throw new IOException("Configuration file has no parent");
        }
        if (!Files.exists(parent, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectories(parent);
        }
        MigrationPaths.requireDirectory(parent, "configuration persistence root");
        MigrationPaths.requireNoSymlinkTraversal(parent, normalized);
        if (!Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
            if (required) {
                throw new IOException("Configuration file does not exist: " + normalized);
            }
            return new Properties();
        }
        if (!Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(normalized)) {
            throw new IOException("Configuration file must be a regular non-symbolic-link file: " + normalized);
        }
        Properties loaded = new Properties();
        try (InputStream input = Files.newInputStream(normalized)) {
            loaded.load(input);
        } catch (IllegalArgumentException exception) {
            throw new IOException("Configuration file contains invalid properties: " + normalized, exception);
        }
        return loaded;
    }

    private static byte[] encode(Properties values) throws IOException {
        StringWriter writer = new StringWriter();
        values.store(writer, "ReSync Configuration");
        return writer.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    private static Properties requireProperties(Properties replacement) {
        Objects.requireNonNull(replacement, "replacement");
        for (Map.Entry<Object, Object> entry : replacement.entrySet()) {
            if (!(entry.getKey() instanceof String) || !(entry.getValue() instanceof String)) {
                throw new IllegalArgumentException("Configuration properties must contain only string keys and values");
            }
        }
        return replacement;
    }

    private static Properties copy(Properties source) {
        Properties copy = new Properties();
        copy.putAll(source);
        return copy;
    }

    private static Path prepareScope(Path scopeRoot) throws IOException {
        Path normalized = MigrationPaths.requirePath(scopeRoot, "scopeRoot");
        if (!Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectories(normalized);
        }
        return MigrationPaths.requireDirectory(normalized, "scopeRoot");
    }

    private static Path requireConfigPath(Path scopeRoot) {
        Path normalizedScope = MigrationPaths.requirePath(scopeRoot, "scopeRoot");
        return normalizedScope.resolve(FILE_NAME).toAbsolutePath().normalize();
    }

    private static Path requireConfigPath(Path scopeRoot, Path configFile) {
        Path normalizedScope = MigrationPaths.requirePath(scopeRoot, "scopeRoot");
        Path normalizedFile = MigrationPaths.requirePath(configFile, "configFile");
        if (normalizedFile.getParent() == null || !normalizedFile.getParent().equals(normalizedScope)
            || normalizedFile.getNameCount() != normalizedScope.getNameCount() + 1 || normalizedFile.equals(normalizedScope)) {
            throw new IllegalArgumentException("Configuration File Must Be A Direct Child Of Scope Root");
        }
        return normalizedFile;
    }

    private enum PersistenceState {
        OPEN,
        QUIESCED
    }
}
