package restudio.resync.resources;

import restudio.resync.Log;
import restudio.resync.storage.StorageSafety;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

public class JsonAssetStore<T> {
    public interface JsonReader<T> {
        T read(String json);
    }

    public interface JsonWriter<T> {
        String write(T value);
    }

    public interface IdExtractor<T> {
        String id(T value);
    }

    public interface FolderResolver<T> {
        String folder(T value);
    }

    private final Path assetsRoot;
    private final Path legacyDirectory;
    private final String typeId;
    private final String defaultFolder;
    private final JsonReader<T> reader;
    private final JsonWriter<T> writer;
    private final IdExtractor<T> idExtractor;
    private final FolderResolver<T> folderResolver;
    private final ConcurrentHashMap<String, T> cache = new ConcurrentHashMap<>();

    public JsonAssetStore(Path assetsRoot, Path legacyDirectory, String typeId, String defaultFolder, JsonReader<T> reader, JsonWriter<T> writer, IdExtractor<T> idExtractor) {
        this(assetsRoot, legacyDirectory, typeId, defaultFolder, reader, writer, idExtractor, null);
    }

    public JsonAssetStore(Path assetsRoot, Path legacyDirectory, String typeId, String defaultFolder, JsonReader<T> reader, JsonWriter<T> writer, IdExtractor<T> idExtractor, FolderResolver<T> folderResolver) {
        this.assetsRoot = assetsRoot;
        this.legacyDirectory = legacyDirectory;
        this.typeId = typeId;
        this.defaultFolder = defaultFolder == null ? "" : defaultFolder;
        this.reader = reader;
        this.writer = writer;
        this.idExtractor = idExtractor;
        this.folderResolver = folderResolver;
    }

    public T get(String id) {
        String safeId = safeId(id, "load");
        if (safeId == null) {
            return null;
        }
        T cached = cache.get(safeId);
        if (cached != null) {
            return cached;
        }
        Path file = findAssetFile(safeId);
        if (file == null && legacyDirectory != null && Files.exists(legacyDirectory)) {
            file = jsonFile(legacyDirectory, safeId, "load");
        }
        if (file == null || !Files.exists(file)) {
            return null;
        }
        try {
            T value = reader.read(StorageSafety.readUtf8(file));
            if (value != null) {
                String valueId = safeId(idExtractor.id(value), "load");
                cache.put(valueId != null ? valueId : safeId, value);
            }
            return value;
        } catch (IOException exception) {
            Log.warn("Failed to load " + typeId + ": " + safeId + " - " + exception.getMessage());
            return null;
        }
    }

    public void save(T value) {
        if (value == null) {
            throw new IllegalArgumentException("Invalid " + typeId);
        }
        String safeId = safeId(idExtractor.id(value), "save");
        if (safeId == null) {
            throw new IllegalArgumentException("Invalid " + typeId + " id");
        }
        try {
            Path target = findAssetFile(safeId);
            if (target == null) {
                target = defaultAssetFile(safeId, value);
            }
            StorageSafety.writeUtf8Atomic(target, writer.write(value));
            cache.put(safeId, value);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to save " + typeId + ": " + safeId, exception);
        }
    }

    public void delete(String id) {
        String safeId = safeId(id, "delete");
        if (safeId == null) {
            throw new IllegalArgumentException("Invalid " + typeId + " id");
        }
        try {
            if (legacyDirectory != null && Files.exists(legacyDirectory)) {
                Path legacyFile = jsonFile(legacyDirectory, safeId, "delete");
                if (legacyFile != null) {
                    StorageSafety.deleteIfExists(legacyFile);
                }
            }
            Path assetFile = findAssetFile(safeId);
            if (assetFile != null) {
                deleteAssetFile(assetFile);
            }
            cache.remove(safeId);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to delete " + typeId + ": " + safeId, exception);
        }
    }

    public List<String> listIds() {
        Set<String> ids = new HashSet<>(cache.keySet());
        if (Files.exists(assetsRoot)) {
            try (Stream<Path> paths = Files.walk(assetsRoot)) {
                paths.filter(path -> Files.isRegularFile(path) && path.getFileName().toString().startsWith(typeId + "__") && path.getFileName().toString().endsWith(".json"))
                    .map(path -> path.getFileName().toString())
                    .map(name -> name.substring((typeId + "__").length(), name.length() - 5))
                    .filter(id -> safeId(id, "list") != null)
                    .forEach(ids::add);
            } catch (IOException exception) {
                Log.warn("Failed to list " + typeId + " assets: " + exception.getMessage());
            }
        }
        return ids.stream().sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    public void migrateLegacyAssets() {
        if (legacyDirectory == null || !Files.exists(legacyDirectory)) {
            return;
        }
        try (Stream<Path> paths = Files.list(legacyDirectory)) {
            for (Path file : paths.filter(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".json")).toList()) {
                String fileName = file.getFileName().toString();
                String id = fileName.substring(0, fileName.length() - 5);
                if (safeId(id, "migrate") == null) {
                    continue;
                }
                T value = null;
                try {
                    value = reader.read(StorageSafety.readUtf8(file));
                } catch (IOException exception) {
                    Log.warn("Failed to read legacy " + typeId + " asset: " + id + " - " + exception.getMessage());
                }
                Path target = defaultAssetFile(id, value);
                if (!Files.exists(target)) {
                    Files.move(file, target, StandardCopyOption.REPLACE_EXISTING);
                } else {
                    Files.deleteIfExists(file);
                }
            }
        } catch (IOException exception) {
            Log.warn("Failed to migrate " + typeId + " assets: " + exception.getMessage());
        }
        deleteLegacyDirectory();
    }

    public void clearCache() {
        cache.clear();
    }

    public Path findAssetFile(String id) {
        String safeId = safeId(id, "search");
        if (safeId == null || !Files.exists(assetsRoot)) {
            return null;
        }
        String fileName = typeId + "__" + safeId + ".json";
        try (Stream<Path> paths = Files.walk(assetsRoot)) {
            return paths.filter(path -> Files.isRegularFile(path) && path.getFileName().toString().equals(fileName)).findFirst().orElse(null);
        } catch (IOException exception) {
            Log.warn("Failed to search " + typeId + " assets: " + safeId + " - " + exception.getMessage());
            return null;
        }
    }

    private Path defaultAssetFile(String id, T value) throws IOException {
        String folder = value != null && folderResolver != null ? folderResolver.folder(value) : defaultFolder;
        Path target = safeAssetFolder(folder == null ? defaultFolder : folder).resolve(typeId + "__" + id + ".json");
        Files.createDirectories(target.getParent());
        return target;
    }

    private Path safeAssetFolder(String folder) {
        Path root = assetsRoot.toAbsolutePath().normalize();
        Path result = root;
        String normalized = normalizeAssetPath(folder);
        if (!normalized.isBlank()) {
            for (String part : normalized.split("/")) {
                result = result.resolve(part);
            }
        }
        Path target = result.normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("Unsafe assets folder: " + folder);
        }
        return target;
    }

    private String normalizeAssetPath(String path) {
        String normalized = path != null ? path.replace('\\', '/').replaceAll("/+", "/").trim() : "";
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.contains("..")) {
            throw new IllegalArgumentException("Unsafe assets path: " + path);
        }
        return normalized;
    }

    private void deleteLegacyDirectory() {
        if (legacyDirectory == null || !Files.exists(legacyDirectory)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(legacyDirectory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException exception) {
            Log.warn("Failed to delete legacy " + typeId + " directory: " + exception.getMessage());
        }
    }

    private void deleteAssetFile(Path file) throws IOException {
        Path root = assetsRoot.toAbsolutePath().normalize();
        Path target = file.toAbsolutePath().normalize();
        if (!target.startsWith(root) || target.getParent() == null || !target.getFileName().toString().endsWith(".json")) {
            throw new IOException("Unsafe " + typeId + " assets delete target: " + file);
        }
        Files.deleteIfExists(target);
    }

    private Path jsonFile(Path directory, String id, String action) {
        try {
            return StorageSafety.jsonFile(directory, id);
        } catch (IOException | IllegalArgumentException exception) {
            Log.warn("Failed to resolve " + action + " " + typeId + ": " + id + " - " + exception.getMessage());
            return null;
        }
    }

    private String safeId(String id, String action) {
        try {
            return StorageSafety.validateId(id);
        } catch (IllegalArgumentException exception) {
            Log.warn("Rejected unsafe " + typeId + " id during " + action + ": " + id);
            return null;
        }
    }
}
