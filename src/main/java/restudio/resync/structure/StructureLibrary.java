package restudio.resync.structure;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.bukkit.plugin.Plugin;
import restudio.resync.migration.MigrationPaths;
import restudio.resync.storage.AssetTransactionCoordinator;
import restudio.resync.storage.AssetTransactionCoordinator.AssetDelta;
import restudio.resync.storage.AssetTransactionCoordinator.AssetKey;
import restudio.resync.storage.AssetTransactionCoordinator.CommittedAsset;
import restudio.resync.storage.AssetTransactionCoordinator.Deleted;
import restudio.resync.storage.AssetTransactionCoordinator.ExpectedState;
import restudio.resync.storage.AssetTransactionCoordinator.Live;
import restudio.resync.storage.AssetTransactionCoordinator.Missing;
import restudio.resync.storage.AssetTransactionCoordinator.Snapshot;
import restudio.resync.storage.AssetTransactionCoordinator.TransactionRequest;
import restudio.resync.storage.StorageSafety;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public class StructureLibrary implements AutoCloseable {
    public static final String ROOT_DIRECTORY = "structures";
    private static final String TYPE = "structure";
    private static final String FILE_SUFFIX = ".resync-structure";
    private static final int FILE_LIMIT = 64 * 1024 * 1024;
    private static final int JSON_LIMIT = 256 * 1024 * 1024;
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static volatile StructureLibrary instance;
    private static volatile Plugin instancePlugin;
    private static volatile boolean activeBinding;

    private volatile Path structuresDir;
    private final ConcurrentHashMap<AssetKey, Resident> residents = new ConcurrentHashMap<>();
    private AssetTransactionCoordinator coordinator;
    private AssetTransactionCoordinator.ListenerRegistration listener;
    private long generation;
    private boolean changeTime;
    private volatile boolean quiesced;
    private volatile boolean closed;
    private volatile boolean closing;
    private IOException closeFailure;

    private StructureLibrary(Plugin plugin) {
        this(plugin.getDataFolder().toPath());
    }

    public StructureLibrary(Path dataRoot) {
        Path scope = MigrationPaths.requirePath(dataRoot, "dataRoot");
        structuresDir = scope.resolve(ROOT_DIRECTORY);
        try {
            Files.createDirectories(structuresDir);
            coordinator = open(structuresDir);
            listener = listen(coordinator);
            generation++;
            changeTime = structuresDir.getFileSystem().supportedFileAttributeViews().contains("unix");
            reload();
        } catch (IOException | RuntimeException exception) {
            try {
                close();
            } catch (IOException closeFailure) {
                exception.addSuppressed(closeFailure);
            }
            throw new IllegalStateException("Structure library initialization failed", exception);
        }
    }

    public static StructureLibrary get(Plugin plugin) {
        if (plugin == null) {
            throw new IllegalArgumentException("Plugin is required");
        }
        synchronized (StructureLibrary.class) {
            Path expectedRoot = plugin.getDataFolder().toPath().resolve(ROOT_DIRECTORY).toAbsolutePath().normalize();
            StructureLibrary current = instance;
            if (current == null || instancePlugin != plugin
                || (!activeBinding && !expectedRoot.equals(current.getStructuresDir()))) {
                current = new StructureLibrary(plugin);
                instance = current;
                instancePlugin = plugin;
                activeBinding = false;
            }
            current.requireOpen();
            return current;
        }
    }

    public static StructureLibrary get(Plugin plugin, Path activeDataRoot) {
        if (plugin == null) {
            throw new IllegalArgumentException("Plugin is required");
        }
        Path expectedRoot = MigrationPaths.requirePath(activeDataRoot, "activeDataRoot")
            .resolve(ROOT_DIRECTORY).toAbsolutePath().normalize();
        synchronized (StructureLibrary.class) {
            StructureLibrary current = instance;
            if (current == null || instancePlugin != plugin || current.closed
                || !expectedRoot.equals(current.getStructuresDir())) {
                current = new StructureLibrary(activeDataRoot);
                instance = current;
                instancePlugin = plugin;
                activeBinding = true;
            }
            current.requireOpen();
            return current;
        }
    }

    public static StructureLibrary active() {
        StructureLibrary current = instance;
        if (current == null) {
            throw new IllegalStateException("Structure library is not initialized");
        }
        current.requireOpen();
        return current;
    }

    public Path getStructuresDir() {
        return structuresDir;
    }

    public synchronized void reload() {
        requireOpen();
        residents.clear();
        try {
            coordinator.healthCheck();
            verifyAll();
        } catch (IOException exception) {
            residents.clear();
            throw new IllegalStateException("Structure library reload failed", exception);
        }
    }

    public synchronized List<StructureSummary> list() {
        requireOpen();
        try {
            return read(snapshot -> snapshot.states().keySet().stream()
                .filter(key -> TYPE.equals(key.type()))
                .map(key -> resident(snapshot, key, false))
                .filter(Objects::nonNull)
                .map(Resident::summary)
                .sorted(Comparator.comparing(StructureSummary::id))
                .toList());
        } catch (IOException exception) {
            throw new IllegalStateException("Structure list failed", exception);
        }
    }

    public synchronized boolean exists(String id) {
        requireOpen();
        AssetKey key = key(id);
        try {
            return read(snapshot -> resident(snapshot, key, false) != null);
        } catch (IOException exception) {
            throw new IllegalStateException("Structure lookup failed", exception);
        }
    }

    public String canonicalId(String id) {
        return requireId(id);
    }

    public synchronized Optional<ReSyncStructure> load(String id) {
        requireOpen();
        AssetKey key = key(id);
        try {
            return read(snapshot -> {
                Resident resident = resident(snapshot, key);
                return resident == null ? Optional.empty() : Optional.of(copy(resident.structure()));
            });
        } catch (IOException exception) {
            throw new IllegalStateException("Structure load failed", exception);
        }
    }

    public synchronized void save(ReSyncStructure structure) {
        requireWritable();
        if (structure == null) {
            throw new IllegalArgumentException("Structure is required");
        }
        ReSyncStructure value = copy(structure);
        value.validateGeometry();
        String id = requireId(value.getId());
        long now = System.currentTimeMillis();
        value.setId(id);
        if (value.getDisplayName() == null || value.getDisplayName().isBlank()) {
            value.setDisplayName(id);
        }
        if (value.getCreatedAt() <= 0L) {
            value.setCreatedAt(now);
        }
        value.setUpdatedAt(now);
        try {
            AssetKey key = key(id);
            Snapshot snapshot = read(current -> {
                resident(current, key);
                return current;
            });
            byte[] bytes = gzip(value);
            coordinator.transact(new TransactionRequest(UUID.randomUUID(), snapshot.project(),
                List.of(AssetDelta.write(key, pathFor(id), snapshot.state(key).orElse(Missing.INSTANCE), bytes)), List.of()));
            read(current -> resident(current, key));
            structure.setId(value.getId());
            structure.setDisplayName(value.getDisplayName());
            structure.setCreatedAt(value.getCreatedAt());
            structure.setUpdatedAt(value.getUpdatedAt());
        } catch (IOException exception) {
            throw new IllegalStateException("Structure Save Failed: " + exception.getMessage(), exception);
        }
    }

    public synchronized boolean delete(String id) {
        requireWritable();
        AssetKey key = key(id);
        try {
            Snapshot snapshot = read(current -> {
                resident(current, key);
                return current;
            });
            ExpectedState state = snapshot.state(key).orElse(Missing.INSTANCE);
            if (!(state instanceof Live)) {
                return false;
            }
            coordinator.transact(new TransactionRequest(UUID.randomUUID(), snapshot.project(),
                List.of(AssetDelta.delete(key, pathFor(key.id()), state)), List.of()));
            return true;
        } catch (IOException exception) {
            throw new IllegalStateException("Structure delete failed: " + key.id(), exception);
        }
    }

    public synchronized void flushPersistence() throws IOException {
        requireOpen();
        coordinator.flush();
        StorageSafety.forceDirectory(MigrationPaths.requireDirectory(structuresDir, "structures root"));
    }

    public synchronized void quiescePersistence() throws IOException {
        requireOpen();
        quiesced = true;
        flushPersistence();
    }

    public synchronized void resumePersistence() throws IOException {
        healthCheckPersistence();
        quiesced = false;
    }

    public synchronized void rebindPersistence(Path candidateRoot) throws IOException {
        requireOpen();
        if (!quiesced) {
            throw new IOException("Structure persistence must be quiesced before rebind");
        }
        Path nextRoot = MigrationPaths.requireDirectory(candidateRoot, "structures root");
        if (structuresDir.equals(nextRoot)) {
            healthCheckPersistence();
            return;
        }
        AssetTransactionCoordinator next = open(nextRoot);
        AssetTransactionCoordinator previous = coordinator;
        AssetTransactionCoordinator.ListenerRegistration previousListener = listener;
        Path previousRoot = structuresDir;
        boolean previousChangeTime = changeTime;
        try {
            structuresDir = nextRoot;
            coordinator = next;
            changeTime = nextRoot.getFileSystem().supportedFileAttributeViews().contains("unix");
            generation++;
            residents.clear();
            verifyAll();
            listener = listen(next);
        } catch (IOException | RuntimeException exception) {
            structuresDir = previousRoot;
            coordinator = previous;
            changeTime = previousChangeTime;
            generation++;
            residents.clear();
            try {
                next.close();
            } catch (IOException closeFailure) {
                exception.addSuppressed(closeFailure);
            }
            throw exception;
        }
        previousListener.close();
        previous.close();
    }

    public synchronized void healthCheckPersistence() throws IOException {
        requireOpen();
        residents.clear();
        try {
            coordinator.healthCheck();
            verifyAll();
        } catch (IOException | RuntimeException exception) {
            residents.clear();
            throw exception;
        }
    }

    @Override
    public synchronized void close() throws IOException {
        if (closeFailure != null) {
            throw closeFailure;
        }
        if (closed) {
            return;
        }
        closing = true;
        quiesced = true;
        generation++;
        residents.clear();
        if (listener != null) {
            listener.close();
            listener = null;
        }
        try {
            if (coordinator != null) {
                coordinator.close();
                coordinator = null;
            }
            closed = true;
        } catch (IOException exception) {
            closeFailure = exception;
            throw exception;
        }
    }

    private AssetTransactionCoordinator open(Path root) throws IOException {
        Path normalized = MigrationPaths.requireDirectory(root, "structures root");
        MigrationPaths.requireNoSymlinkTree(normalized);
        if (!Files.exists(normalized.resolve(".asset-coordinator/genesis.json"), LinkOption.NOFOLLOW_LINKS)) {
            try (var files = Files.walk(normalized)) {
                if (files.anyMatch(path -> !path.equals(normalized))) {
                    throw new IOException("Structure root has uncoordinated data; preserve it and start with a fresh root: " + normalized);
                }
            }
        }
        return new AssetTransactionCoordinator(normalized, GSON);
    }

    private AssetTransactionCoordinator.ListenerRegistration listen(AssetTransactionCoordinator owner) {
        return owner.addListener(result -> result.states().keySet().stream()
            .filter(key -> TYPE.equals(key.type())).forEach(residents::remove));
    }

    private <T> T read(Function<Snapshot, T> reader) throws IOException {
        try {
            return coordinator.read(reader);
        } catch (UncheckedIOException exception) {
            throw exception.getCause();
        }
    }

    private Resident resident(Snapshot snapshot, AssetKey key) {
        return resident(snapshot, key, true);
    }

    private Resident resident(Snapshot snapshot, AssetKey key, boolean inspect) {
        try {
            if (!key.id().equals(requireId(key.id()))) {
                throw new IOException("Structure coordinator ID is not canonical: " + key.id());
            }
            CommittedAsset stamp = coordinator.committedAsset(key).orElse(null);
            Path file = pathFor(key.id());
            if (stamp == null || stamp.state() instanceof Missing || stamp.state() instanceof Deleted) {
                residents.remove(key);
                if (!inspect) {
                    return null;
                }
                MigrationPaths.requireNoSymlinkTraversal(structuresDir, file);
                if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Structure file has no live committed identity: " + key.id());
                }
                return null;
            }
            if (!(stamp.state() instanceof Live live) || !stamp.path().equals(file)
                || !snapshot.state(key).orElse(Missing.INSTANCE).equals(stamp.state())) {
                throw new IOException("Structure committed path or state is invalid: " + key.id());
            }
            Resident cached = residents.get(key);
            boolean matches = cached != null && cached.coordinator() == coordinator && cached.generation() == generation
                && cached.stamp().equals(stamp);
            if (matches && !inspect) {
                return cached;
            }
            Physical physical = physical(file);
            if (matches && cached.physical().equals(physical)) {
                return cached;
            }
            residents.remove(key);
            byte[] bytes;
            try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                bytes = input.readNBytes(FILE_LIMIT + 1);
            }
            if (bytes.length > FILE_LIMIT || !physical.equals(physical(file))) {
                throw new IOException("Structure file is too large or changed during verification: " + key.id());
            }
            if (!live.hash().equals(StorageSafety.sha256(bytes))) {
                throw new IOException("Structure bytes do not match committed identity: " + key.id());
            }
            ReSyncStructure structure = cached != null && cached.coordinator() == coordinator
                && cached.generation() == generation && cached.stamp().equals(stamp) && Arrays.equals(bytes, cached.bytes())
                ? cached.structure() : decode(bytes, key.id());
            Resident admitted = new Resident(coordinator, generation, stamp, physical, bytes, structure, summary(structure));
            residents.put(key, admitted);
            return admitted;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private Physical physical(Path file) throws IOException {
        MigrationPaths.requireNoSymlinkTraversal(structuresDir, file);
        BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (attributes.isSymbolicLink() || !attributes.isRegularFile() || attributes.size() > FILE_LIMIT) {
            throw new IOException("Structure file is not a bounded regular file: " + file.getFileName());
        }
        FileTime changed = changeTime ? (FileTime) Files.getAttribute(file, "unix:ctime", LinkOption.NOFOLLOW_LINKS) : null;
        return new Physical(attributes.fileKey(), attributes.size(), attributes.lastModifiedTime(), attributes.creationTime(), changed);
    }

    private void verifyAll() throws IOException {
        MigrationPaths.requireNoSymlinkTree(structuresDir);
        read(snapshot -> {
            Map<Path, AssetKey> indexed = new LinkedHashMap<>();
            for (AssetKey key : snapshot.states().keySet()) {
                if (TYPE.equals(key.type())) {
                    Resident value = resident(snapshot, key);
                    if (value != null) {
                        indexed.put(pathFor(key.id()), key);
                    }
                }
            }
            try (var files = Files.list(structuresDir)) {
                for (Path file : files.filter(path -> path.getFileName().toString().endsWith(FILE_SUFFIX)).toList()) {
                    if (!indexed.containsKey(file)) {
                        throw new IOException("Uncoordinated structure file: " + file.getFileName());
                    }
                }
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
            return null;
        });
    }

    private ReSyncStructure decode(byte[] bytes, String id) throws IOException {
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
            byte[] json = gzip.readNBytes(JSON_LIMIT + 1);
            if (json.length > JSON_LIMIT) {
                throw new IOException("Structure content is too large: " + id);
            }
            ReSyncStructure structure = GSON.fromJson(new String(json, StandardCharsets.UTF_8), ReSyncStructure.class);
            if (structure == null || !id.equals(structure.getId()) || structure.getFormatVersion() != 1) {
                throw new IOException("Structure content identity or format is invalid: " + id);
            }
            structure.validateGeometry();
            structure.setTags(structure.getTags());
            return structure;
        } catch (RuntimeException exception) {
            throw new IOException("Structure content is invalid: " + id, exception);
        }
    }

    private byte[] gzip(ReSyncStructure structure) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(output);
             OutputStreamWriter writer = new OutputStreamWriter(gzip, StandardCharsets.UTF_8)) {
            GSON.toJson(structure, writer);
        }
        byte[] bytes = output.toByteArray();
        if (bytes.length > FILE_LIMIT) {
            throw new IOException("Structure file is too large");
        }
        decode(bytes, structure.getId());
        return bytes;
    }

    private ReSyncStructure copy(ReSyncStructure source) {
        ReSyncStructure result = new ReSyncStructure();
        result.setFormatVersion(source.getFormatVersion());
        result.setId(source.getId());
        result.setDisplayName(source.getDisplayName());
        result.setTags(source.getTags());
        result.setOriginX(source.getOriginX());
        result.setOriginY(source.getOriginY());
        result.setOriginZ(source.getOriginZ());
        result.setSizeX(source.getSizeX());
        result.setSizeY(source.getSizeY());
        result.setSizeZ(source.getSizeZ());
        result.setBlockTypes(copy(source.getBlockTypes()));
        result.setBlockDataStrings(copy(source.getBlockDataStrings()));
        result.setCreatedAt(source.getCreatedAt());
        result.setUpdatedAt(source.getUpdatedAt());
        return result;
    }

    private String[][][] copy(String[][][] source) {
        if (source == null) {
            return null;
        }
        String[][][] result = new String[source.length][][];
        for (int y = 0; y < source.length; y++) {
            if (source[y] != null) {
                result[y] = new String[source[y].length][];
                for (int x = 0; x < source[y].length; x++) {
                    result[y][x] = source[y][x] == null ? null : source[y][x].clone();
                }
            }
        }
        return result;
    }

    private Path pathFor(String id) {
        Path root = structuresDir;
        Path path = root.resolve(requireId(id) + FILE_SUFFIX).normalize();
        if (!root.equals(path.getParent())) {
            throw new IllegalArgumentException("Structure Id Invalid");
        }
        return path;
    }

    private AssetKey key(String id) {
        return new AssetKey(TYPE, requireId(id));
    }

    private StructureSummary summary(ReSyncStructure structure) {
        return new StructureSummary(structure.getId(), structure.getDisplayName(), structure.getTags(),
            structure.getSizeX(), structure.getSizeY(), structure.getSizeZ(), structure.getUpdatedAt());
    }

    private void requireOpen() {
        if (closed || closing) {
            throw new IllegalStateException("Structure library is closed");
        }
    }

    private void requireWritable() {
        requireOpen();
        if (quiesced) {
            throw new IllegalStateException("Structure persistence is quiesced");
        }
    }

    private String safeId(String value) {
        String source = value == null ? "" : value.toLowerCase(Locale.ROOT).trim();
        StringBuilder builder = new StringBuilder();
        for (char c : source.toCharArray()) {
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-') {
                builder.append(c);
            } else if (c == ' ' || c == ':' || c == '.' || c == '/' || c == '\\') {
                builder.append('_');
            }
        }
        return builder.toString();
    }

    private String requireId(String value) {
        String id = safeId(value);
        if (id.isBlank() || id.length() > 255 - FILE_SUFFIX.length()) {
            throw new IllegalArgumentException("Structure ID is required and must fit the file name");
        }
        return id;
    }

    private record Physical(Object key, long size, FileTime modified, FileTime created, FileTime changed) {
    }

    private record Resident(AssetTransactionCoordinator coordinator, long generation, CommittedAsset stamp,
                            Physical physical, byte[] bytes, ReSyncStructure structure, StructureSummary summary) {
    }
}
