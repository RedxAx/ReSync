package restudio.resync.structure;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public class StructureLibrary {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static volatile StructureLibrary instance;

    private final Path structuresDir;
    private final ConcurrentHashMap<String, StructureSummary> summaries = new ConcurrentHashMap<>();

    private StructureLibrary(Plugin plugin) {
        this.structuresDir = plugin.getDataFolder().toPath().resolve("structures").toAbsolutePath().normalize();
        reload();
    }

    public static StructureLibrary get(Plugin plugin) {
        StructureLibrary current = instance;
        if (current == null) {
            synchronized (StructureLibrary.class) {
                current = instance;
                if (current == null) {
                    current = new StructureLibrary(plugin);
                    instance = current;
                }
            }
        }
        return current;
    }

    public Path getStructuresDir() {
        return structuresDir;
    }

    public void reload() {
        summaries.clear();
        try {
            Files.createDirectories(structuresDir);
            try (var stream = Files.list(structuresDir)) {
                stream.filter(path -> path.getFileName().toString().endsWith(".resync-structure"))
                        .map(this::read)
                        .filter(Optional::isPresent)
                        .map(Optional::get)
                        .forEach(structure -> summaries.put(structure.getId(), summary(structure)));
            }
        } catch (IOException ignored) {
        }
    }

    public List<StructureSummary> list() {
        return summaries.values().stream().sorted(Comparator.comparing(StructureSummary::id)).toList();
    }

    public boolean exists(String id) {
        return summaries.containsKey(safeId(id));
    }

    public Optional<ReSyncStructure> load(String id) {
        return read(pathFor(id));
    }

    public void save(ReSyncStructure structure) {
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
            Files.createDirectories(structuresDir);
            try (GZIPOutputStream gzip = new GZIPOutputStream(Files.newOutputStream(pathFor(id)));
                 OutputStreamWriter writer = new OutputStreamWriter(gzip, StandardCharsets.UTF_8)) {
                GSON.toJson(structure, writer);
            }
            summaries.put(id, summary(structure));
        } catch (IOException exception) {
            throw new IllegalStateException("Structure Save Failed: " + exception.getMessage(), exception);
        }
    }

    public boolean delete(String id) {
        String safe = safeId(id);
        summaries.remove(safe);
        try {
            return Files.deleteIfExists(pathFor(safe));
        } catch (IOException ignored) {
            return false;
        }
    }

    private Optional<ReSyncStructure> read(Path path) {
        if (path == null || !Files.exists(path)) {
            return Optional.empty();
        }
        try (GZIPInputStream gzip = new GZIPInputStream(Files.newInputStream(path));
             InputStreamReader reader = new InputStreamReader(gzip, StandardCharsets.UTF_8)) {
            ReSyncStructure structure = GSON.fromJson(reader, ReSyncStructure.class);
            if (structure == null || structure.getId() == null || structure.getId().isBlank()) {
                return Optional.empty();
            }
            structure.setId(safeId(structure.getId()));
            return Optional.of(structure);
        } catch (IOException ignored) {
            return Optional.empty();
        }
    }

    private Path pathFor(String id) {
        Path path = structuresDir.resolve(safeId(id) + ".resync-structure").normalize();
        if (!path.startsWith(structuresDir)) {
            throw new IllegalArgumentException("Structure Id Invalid");
        }
        return path;
    }

    private StructureSummary summary(ReSyncStructure structure) {
        return new StructureSummary(structure.getId(), structure.getDisplayName(), structure.getTags(), structure.getSizeX(), structure.getSizeY(), structure.getSizeZ(), structure.getUpdatedAt());
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
}
