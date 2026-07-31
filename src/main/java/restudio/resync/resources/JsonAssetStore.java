package restudio.resync.resources;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.stream.Stream;

public class JsonAssetStore<T> {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
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
    private final ConcurrentHashMap<String, Path> fileIndex = new ConcurrentHashMap<>();
    private volatile boolean fileIndexReady;

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
            Path oldTarget = target;
            if (target == null) {
                target = defaultAssetFile(safeId, value);
            } else if (!AssetFileFormat.isIdOnlyFileName(target.getFileName().toString())) {
                target = target.getParent().resolve(AssetFileFormat.idOnlyFileName(safeId));
            }
            if ((oldTarget == null || !oldTarget.toAbsolutePath().normalize().equals(target.toAbsolutePath().normalize()))
                    && Files.exists(target)
                    && !typeId.equals(AssetFileFormat.readResourceType(target))) {
                target = conflictAssetFile(safeId, 1);
            }
            StorageSafety.writeUtf8Atomic(target, AssetFileFormat.withResourceType(writer.write(value), typeId));
            if (oldTarget != null && !oldTarget.toAbsolutePath().normalize().equals(target.toAbsolutePath().normalize())) {
                Files.deleteIfExists(oldTarget);
            }
            fileIndex.put(safeId, target);
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
            deleteMatchingAssetFiles(safeId);
            removeProjectMetadataResource(safeId);
            fileIndex.remove(safeId);
            cache.remove(safeId);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to delete " + typeId + ": " + safeId, exception);
        }
    }

    private void removeProjectMetadataResource(String id) throws IOException {
        Path projectFile = assetsRoot.resolve("project.json");
        if (!Files.exists(projectFile)) {
            return;
        }
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(StorageSafety.readUtf8(projectFile));
        } catch (RuntimeException exception) {
            Log.warn("Failed to update project metadata after deleting " + typeId + ": " + exception.getMessage());
            return;
        }
        if (!parsed.isJsonObject()) {
            return;
        }
        JsonObject project = parsed.getAsJsonObject();
        JsonArray resources = project.has("resources") && project.get("resources").isJsonArray() ? project.getAsJsonArray("resources") : null;
        if (resources == null) {
            return;
        }
        boolean changed = resources.asList().removeIf(element -> element != null && element.isJsonObject()
            && typeId.equals(text(element.getAsJsonObject(), "type")) && id.equals(text(element.getAsJsonObject(), "id")));
        if (changed) {
            StorageSafety.writeUtf8Atomic(projectFile, GSON.toJson(project));
        }
    }

    private String text(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : "";
    }

    public List<String> listIds() {
        Set<String> ids = new HashSet<>(cache.keySet());
        ids.addAll(assetFileIndex().keySet());
        if (legacyDirectory != null && Files.exists(legacyDirectory)) {
            try (Stream<Path> paths = Files.list(legacyDirectory)) {
                paths.filter(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".json"))
                    .map(path -> path.getFileName().toString())
                    .map(fileName -> fileName.substring(0, fileName.length() - 5))
                    .filter(id -> safeId(id, "list") != null)
                    .forEach(ids::add);
            } catch (IOException exception) {
                Log.warn("Failed to list legacy " + typeId + " assets: " + exception.getMessage());
            }
        }
        return ids.stream().sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    public void migrateLegacyAssets() {
        migrateAssetRootFiles();
        if (legacyDirectory == null || !Files.exists(legacyDirectory)) {
            invalidateFileIndex();
            return;
        }
        boolean migrationComplete = !hasUnexpectedLegacyFiles();
        try (Stream<Path> paths = Files.list(legacyDirectory)) {
            for (Path file : paths.filter(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".json")).toList()) {
                String fileName = file.getFileName().toString();
                String id = fileName.substring(0, fileName.length() - 5);
                if (safeId(id, "migrate") == null) {
                    migrationComplete = false;
                    continue;
                }
                try {
                    String sourceJson = StorageSafety.readUtf8(file);
                    T value = reader.read(sourceJson);
                    if (value == null || !id.equals(safeId(idExtractor.id(value), "migrate"))) {
                        migrationComplete = false;
                        Log.warn("Failed to validate legacy " + typeId + " asset: " + id);
                        continue;
                    }
                    backupLegacyAsset(file, sourceJson);
                    Path target = defaultAssetFile(id, value);
                    String json = AssetFileFormat.withResourceType(sourceJson, typeId);
                    if (Files.exists(target)) {
                        String targetType = AssetFileFormat.readResourceType(target);
                        if (!typeId.equals(targetType)) {
                            target = conflictAssetFile(id, 1);
                        }
                    }
                    if (!Files.exists(target) || !typeId.equals(AssetFileFormat.readResourceType(target))) {
                        StorageSafety.writeUtf8Atomic(target, json);
                    }
                    if (!validMigratedAsset(target, id)) {
                        migrationComplete = false;
                        Log.warn("Failed to verify migrated " + typeId + " asset: " + id);
                    }
                } catch (IOException | RuntimeException exception) {
                    migrationComplete = false;
                    Log.warn("Failed to migrate legacy " + typeId + " asset: " + id + " - " + exception.getMessage());
                }
            }
        } catch (IOException exception) {
            migrationComplete = false;
            Log.warn("Failed to migrate " + typeId + " assets: " + exception.getMessage());
        }
        if (migrationComplete) {
            deleteLegacyDirectory();
        }
        invalidateFileIndex();
    }

    private boolean hasUnexpectedLegacyFiles() {
        try (Stream<Path> paths = Files.walk(legacyDirectory)) {
            return paths.anyMatch(path -> Files.isRegularFile(path)
                && (!legacyDirectory.equals(path.getParent()) || !path.getFileName().toString().endsWith(".json")));
        } catch (IOException exception) {
            Log.warn("Failed to inspect legacy " + typeId + " assets: " + exception.getMessage());
            return true;
        }
    }

    private void backupLegacyAsset(Path source, String json) throws IOException {
        Path backup = assetsRoot.resolve("migration-backups").resolve(typeId).resolve("legacy").resolve(source.getFileName().toString());
        if (Files.notExists(backup)) {
            StorageSafety.writeUtf8Atomic(backup, json);
        }
    }

    private boolean validMigratedAsset(Path target, String expectedId) {
        try {
            T value = reader.read(StorageSafety.readUtf8(target));
            return value != null && expectedId.equals(safeId(idExtractor.id(value), "verify migration"))
                && typeId.equals(AssetFileFormat.readResourceType(target));
        } catch (IOException | RuntimeException exception) {
            return false;
        }
    }

    private void migrateAssetRootFiles() {
        if (!Files.exists(assetsRoot)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(assetsRoot)) {
            for (Path file : paths.filter(Files::isRegularFile).toList()) {
                if (isInternalAssetPath(file)) {
                    continue;
                }
                AssetName asset = assetName(file);
                if (!typeId.equals(asset.type()) || safeId(asset.id(), "migrate") == null) {
                    continue;
                }
                Path target = file.getParent().resolve(AssetFileFormat.idOnlyFileName(asset.id()));
                if (file.toAbsolutePath().normalize().equals(target.toAbsolutePath().normalize()) && !AssetFileFormat.needsRewrite(file, typeId)) {
                    continue;
                }
                if (Files.exists(target) && !file.toAbsolutePath().normalize().equals(target.toAbsolutePath().normalize())) {
                    String targetType = AssetFileFormat.readResourceType(target);
                    if (typeId.equals(targetType)) {
                        if (AssetFileFormat.isIdOnlyFileName(file.getFileName().toString())) {
                            quarantineDuplicate(file);
                        }
                        continue;
                    }
                    target = conflictAssetFile(asset.id(), 1);
                    if (Files.exists(target) && typeId.equals(AssetFileFormat.readResourceType(target))) {
                        if (AssetFileFormat.isIdOnlyFileName(file.getFileName().toString())) {
                            quarantineDuplicate(file);
                        }
                        continue;
                    }
                }
                AssetFileFormat.copyTyped(file, target, typeId);
            }
        } catch (IOException exception) {
            Log.warn("Failed to migrate " + typeId + " assets root: " + exception.getMessage());
        }
    }

    public void clearCache() {
        cache.clear();
        invalidateFileIndex();
    }

    public T reload(String id) {
        return reload(id, null);
    }

    public T reload(String id, Consumer<T> validator) {
        String safeId = safeId(id, "reload");
        if (safeId == null) {
            throw new IllegalArgumentException("Invalid " + typeId + " id");
        }
        T previous = cache.get(safeId);
        cache.remove(safeId);
        invalidateFileIndex();
        T value = get(safeId);
        try {
            if (value != null && validator != null) {
                validator.accept(value);
            }
            return value;
        } catch (RuntimeException exception) {
            if (previous != null) {
                cache.put(safeId, previous);
            } else {
                cache.remove(safeId);
            }
            throw exception;
        }
    }

    public Path findAssetFile(String id) {
        String safeId = safeId(id, "search");
        if (safeId == null || !Files.exists(assetsRoot)) {
            return null;
        }
        return assetFileIndex().get(safeId);
    }

    private ConcurrentHashMap<String, Path> assetFileIndex() {
        if (!fileIndexReady) {
            synchronized (this) {
                if (!fileIndexReady) {
                    rebuildFileIndex();
                    fileIndexReady = true;
                }
            }
        }
        return fileIndex;
    }

    private void invalidateFileIndex() {
        fileIndexReady = false;
        fileIndex.clear();
    }

    private void quarantineDuplicate(Path file) throws IOException {
        Path root = assetsRoot.toAbsolutePath().normalize();
        Path source = file.toAbsolutePath().normalize();
        Path quarantineRoot = root.resolve(".quarantine").resolve("duplicates").resolve(UUID.randomUUID().toString());
        Path target = quarantineRoot.resolve(root.relativize(source)).normalize();
        if (!source.startsWith(root) || !target.startsWith(quarantineRoot)) {
            throw new IOException("Unsafe " + typeId + " asset quarantine path: " + file);
        }
        Files.createDirectories(target.getParent());
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException exception) {
            Files.move(source, target);
        }
    }

    private boolean isInternalAssetPath(Path file) {
        Path root = assetsRoot.toAbsolutePath().normalize();
        Path relative = root.relativize(file.toAbsolutePath().normalize());
        if (relative.getNameCount() == 0) {
            return false;
        }
        return Set.of(".transactions", ".snapshots", ".quarantine", ".durability", ".tombstones", ".migrations", "migration-backups")
            .contains(relative.getName(0).toString());
    }

    private void rebuildFileIndex() {
        fileIndex.clear();
        if (!Files.exists(assetsRoot)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(assetsRoot)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                if (isInternalAssetPath(path)) {
                    continue;
                }
                AssetName asset = assetName(path);
                if (!typeId.equals(asset.type())) {
                    continue;
                }
                String safeId = safeId(asset.id(), "index");
                if (safeId == null) {
                    continue;
                }
                Path existing = fileIndex.get(safeId);
                if (AssetFileFormat.isIdOnlyFileName(path.getFileName().toString())) {
                    fileIndex.put(safeId, path);
                } else if (existing == null || !AssetFileFormat.isIdOnlyFileName(existing.getFileName().toString())) {
                    fileIndex.put(safeId, path);
                }
            }
        } catch (IOException exception) {
            Log.warn("Failed to index " + typeId + " assets: " + exception.getMessage());
        }
    }

    private Path defaultAssetFile(String id, T value) throws IOException {
        String folder = value != null && folderResolver != null ? folderResolver.folder(value) : defaultFolder;
        Path target = safeAssetFolder(folder == null ? defaultFolder : folder).resolve(AssetFileFormat.idOnlyFileName(id));
        Files.createDirectories(target.getParent());
        return target;
    }

    private Path conflictAssetFile(String id, int index) throws IOException {
        String suffix = index <= 1 ? typeId : typeId + "_" + index;
        Path target = safeAssetFolder(AssetFileFormat.typedConflictFolder(defaultFolder, suffix)).resolve(AssetFileFormat.idOnlyFileName(id));
        if (Files.exists(target) && !typeId.equals(AssetFileFormat.readResourceType(target))) {
            return conflictAssetFile(id, index + 1);
        }
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

    private void deleteMatchingAssetFiles(String id) throws IOException {
        if (!Files.exists(assetsRoot)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(assetsRoot)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                if (isInternalAssetPath(path)) {
                    continue;
                }
                AssetName asset = assetName(path);
                if (typeId.equals(asset.type()) && id.equals(asset.id())) {
                    deleteAssetFile(path);
                }
            }
        }
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

    private AssetName assetName(Path path) {
        String fileName = path != null && path.getFileName() != null ? path.getFileName().toString() : "";
        if (!fileName.endsWith(".json")) {
            return AssetName.empty();
        }
        int separator = fileName.indexOf("__");
        if (separator > 0) {
            String type = fileName.substring(0, separator);
            if ("chat_channel".equals(type)) {
                type = "chat";
            }
            String id = fileName.substring(separator + 2, fileName.length() - 5);
            return new AssetName(type, id);
        }
        String type = AssetFileFormat.readResourceType(path);
        if ("chat_channel".equals(type)) {
            type = "chat";
        }
        String id = AssetFileFormat.idFromIdOnlyFileName(fileName);
        return new AssetName(type, id);
    }

    private record AssetName(String type, String id) {
        private static AssetName empty() {
            return new AssetName("", "");
        }
    }
}
