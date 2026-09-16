package restudio.resync.structure;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.bukkit.plugin.Plugin;
import restudio.resync.migration.MigrationPaths;
import restudio.resync.storage.StorageSafety;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public class StructureLibrary {
    public static final String ROOT_DIRECTORY = "structures";
    private static final String FILE_SUFFIX = ".resync-structure";
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static volatile StructureLibrary instance;
    private static volatile Plugin instancePlugin;
    private static volatile boolean activeBinding;

    private volatile Path structuresDir;
    private final ConcurrentHashMap<String, StructureSummary> summaries = new ConcurrentHashMap<>();
    private volatile boolean quiesced;

    private StructureLibrary(Plugin plugin) {
        this(plugin.getDataFolder().toPath());
    }

    public StructureLibrary(Path dataRoot) {
        Path scope = MigrationPaths.requirePath(dataRoot, "dataRoot");
        updateRoot(scope.resolve(ROOT_DIRECTORY));
        try {
            Files.createDirectories(structuresDir);
            reload();
        } catch (IOException exception) {
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
            if (current == null || instancePlugin != plugin || current.quiesced
                || !expectedRoot.equals(current.getStructuresDir())) {
                current = new StructureLibrary(activeDataRoot);
                instance = current;
                instancePlugin = plugin;
                activeBinding = true;
            }
            return current;
        }
    }

    public static StructureLibrary active() {
        StructureLibrary current = instance;
        if (current == null) {
            throw new IllegalStateException("Structure library is not initialized");
        }
        return current;
    }

    public Path getStructuresDir() {
        return structuresDir;
    }

    public synchronized void reload() {
        try {
            Map<String, StructureSummary> loaded = readSummaries(structuresDir);
            summaries.clear();
            summaries.putAll(loaded);
        } catch (IOException exception) {
            throw new IllegalStateException("Structure library reload failed", exception);
        }
    }

    public List<StructureSummary> list() {
        return summaries.values().stream().sorted(Comparator.comparing(StructureSummary::id)).toList();
    }

    public boolean exists(String id) {
        return summaries.containsKey(requireId(id));
    }

    public String canonicalId(String id) {
        return requireId(id);
    }

    public synchronized Optional<ReSyncStructure> load(String id) {
        try {
            requireRoot(structuresDir);
            return read(pathFor(requireId(id)));
        } catch (IOException exception) {
            throw new IllegalStateException("Structure load failed", exception);
        }
    }

    public synchronized void save(ReSyncStructure structure) {
        requireWritable();
        if (structure == null) {
            throw new IllegalArgumentException("Structure is required");
        }
        String id = safeId(structure.getId());
        if (id.isBlank()) {
            throw new IllegalArgumentException("Structure Id Missing");
        }
        long now = System.currentTimeMillis();
        structure.setId(id);
        if (structure.getDisplayName() == null || structure.getDisplayName().isBlank()) {
            structure.setDisplayName(id);
        }
        if (structure.getCreatedAt() <= 0L) {
            structure.setCreatedAt(now);
        }
        structure.setUpdatedAt(now);
        try {
            Path root = requireRoot(structuresDir);
            StorageSafety.writeBytesAtomic(pathFor(id), gzip(structure));
            summaries.put(id, summary(structure));
            StorageSafety.forceDirectory(root);
        } catch (IOException exception) {
            throw new IllegalStateException("Structure Save Failed: " + exception.getMessage(), exception);
        }
    }

    public synchronized boolean delete(String id) {
        requireWritable();
        String safe = requireId(id);
        try {
            requireRoot(structuresDir);
            Path path = pathFor(safe);
            boolean deleted = Files.exists(path) && Files.isRegularFile(path);
            StorageSafety.deleteIfExists(path);
            if (deleted) {
                StorageSafety.forceDirectory(structuresDir);
            }
            summaries.remove(safe);
            return deleted;
        } catch (IOException exception) {
            throw new IllegalStateException("Structure delete failed: " + safe, exception);
        }
    }

    public synchronized void flushPersistence() throws IOException {
        StorageSafety.forceDirectory(requireRoot(structuresDir));
    }

    public synchronized void quiescePersistence() throws IOException {
        flushPersistence();
        quiesced = true;
    }

    public synchronized void resumePersistence() throws IOException {
        healthCheckPersistence();
        quiesced = false;
    }

    public synchronized void rebindPersistence(Path candidateRoot) throws IOException {
        if (!quiesced) {
            throw new IOException("Structure persistence must be quiesced before rebind");
        }
        Path nextRoot = requireRoot(candidateRoot);
        Map<String, StructureSummary> loaded = readSummaries(nextRoot);
        updateRoot(nextRoot);
        summaries.clear();
        summaries.putAll(loaded);
    }

    public synchronized void healthCheckPersistence() throws IOException {
        readSummaries(structuresDir);
    }

    private Optional<ReSyncStructure> read(Path path) {
        if (path == null || !Files.exists(path)) {
            return Optional.empty();
        }
        try {
            return Optional.of(readStructure(path));
        } catch (IOException exception) {
            throw new IllegalStateException("Structure load failed: " + path.getFileName(), exception);
        }
    }

    private ReSyncStructure readStructure(Path path) throws IOException {
        try (GZIPInputStream gzip = new GZIPInputStream(Files.newInputStream(path));
             InputStreamReader reader = new InputStreamReader(gzip, StandardCharsets.UTF_8)) {
            ReSyncStructure structure = GSON.fromJson(reader, ReSyncStructure.class);
            if (structure == null || structure.getId() == null || structure.getId().isBlank()) {
                throw new IOException("Structure ID is missing: " + path.getFileName());
            }
            structure.setId(requireId(structure.getId()));
            return structure;
        } catch (RuntimeException exception) {
            throw new IOException("Structure content is invalid: " + path.getFileName(), exception);
        }
    }

    private Map<String, StructureSummary> readSummaries(Path root) throws IOException {
        Path normalized = requireRoot(root);
        Map<String, StructureSummary> loaded = new LinkedHashMap<>();
        try (var stream = Files.list(normalized)) {
            List<Path> files = stream.filter(path -> path.getFileName().toString().endsWith(FILE_SUFFIX))
                .sorted()
                .toList();
            for (Path file : files) {
                ReSyncStructure structure = readStructure(file);
                StructureSummary previous = loaded.putIfAbsent(structure.getId(), summary(structure));
                if (previous != null) {
                    throw new IOException("Duplicate structure ID: " + structure.getId());
                }
            }
        }
        return loaded;
    }

    private byte[] gzip(ReSyncStructure structure) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(output);
             OutputStreamWriter writer = new OutputStreamWriter(gzip, StandardCharsets.UTF_8)) {
            GSON.toJson(structure, writer);
        }
        return output.toByteArray();
    }

    private Path pathFor(String id) {
        Path root = structuresDir;
        Path path = root.resolve(requireId(id) + FILE_SUFFIX).normalize();
        if (!path.startsWith(root) || path.getParent() == null || !path.getParent().equals(root)) {
            throw new IllegalArgumentException("Structure Id Invalid");
        }
        return path;
    }

    private StructureSummary summary(ReSyncStructure structure) {
        return new StructureSummary(structure.getId(), structure.getDisplayName(), structure.getTags(), structure.getSizeX(), structure.getSizeY(), structure.getSizeZ(), structure.getUpdatedAt());
    }

    private void requireWritable() {
        if (quiesced) {
            throw new IllegalStateException("Structure persistence is quiesced");
        }
    }

    private static Path requireRoot(Path root) throws IOException {
        Path normalized = MigrationPaths.requireDirectory(root, "structures root");
        MigrationPaths.requireNoSymlinkTree(normalized);
        return normalized;
    }

    private void updateRoot(Path root) {
        structuresDir = MigrationPaths.requirePath(root, "structuresRoot");
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
        if (id.isBlank()) throw new IllegalArgumentException("Structure ID is required");
        return id;
    }
}
