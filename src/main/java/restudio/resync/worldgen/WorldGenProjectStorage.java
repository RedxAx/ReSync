package restudio.resync.worldgen;

import org.bukkit.plugin.java.JavaPlugin;
import restudio.resync.Log;
import restudio.resync.resources.JsonAssetStore;
import restudio.resync.resources.ReSyncResourceCatalog;
import restudio.resync.storage.StorageSafety;
import restudio.resync.worldgen.data.WorldGenProject;
import restudio.resync.worldgen.data.WorldGenSerializer;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

public class WorldGenProjectStorage {
    private final File projectDir;
    private final Path projectPath;
    private final File assetsDir;
    private final JsonAssetStore<WorldGenProject> assetStore;
    private final Map<String, WorldGenProject> cache = new ConcurrentHashMap<>();

    public WorldGenProjectStorage(JavaPlugin plugin) {
        this.projectDir = new File(plugin.getDataFolder(), "worldgen-projects");
        this.projectPath = projectDir.toPath();
        this.assetsDir = new File(plugin.getDataFolder(), "assets");
        this.assetStore = new JsonAssetStore<>(
            assetsDir.toPath(),
            projectPath,
            ReSyncResourceCatalog.WORLDGEN,
            ReSyncResourceCatalog.defaultFolder(ReSyncResourceCatalog.WORLDGEN),
            WorldGenSerializer::deserializeProject,
            WorldGenSerializer::serializeProject,
            WorldGenProject::getId
        );
        if (!assetsDir.exists()) {
            assetsDir.mkdirs();
        }
        migrateLegacyAssets();
    }

    public WorldGenProject getProject(String id) {
        String safeId = safeId(id, "load");
        if (safeId == null) {
            return null;
        }
        WorldGenProject cached = cache.get(safeId);
        if (cached != null) {
            return cached;
        }
        WorldGenProject project = assetStore.get(safeId);
        if (project != null) {
            if (project.getId() == null || project.getId().isBlank()) {
                project.setId(safeId);
            }
            cache.put(project.getId(), project);
        }
        return project;
    }

    public void saveProject(WorldGenProject project) {
        if (project == null) {
            throw new IllegalArgumentException("Invalid WorldGen project");
        }
        String safeId = safeId(project.getId(), "save");
        if (safeId == null) {
            throw new IllegalArgumentException("Invalid WorldGen project id");
        }
        project.rebuildIndices();
        try {
            assetStore.save(project);
            cache.put(safeId, project);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to save WorldGen project: " + safeId, e);
        }
    }

    public void deleteProject(String id) {
        String safeId = safeId(id, "delete");
        if (safeId == null) {
            throw new IllegalArgumentException("Invalid WorldGen project id");
        }
        try {
            assetStore.delete(safeId);
            cache.remove(safeId);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to delete WorldGen project: " + safeId, e);
        }
    }

    public List<String> listProjectIds() {
        Set<String> ids = new HashSet<>(assetStore.listIds());
        return ids.stream().sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    private String safeId(String id, String action) {
        try {
            return StorageSafety.validateId(id);
        } catch (IllegalArgumentException e) {
            Log.warn("Rejected unsafe WorldGen project id during " + action + ": " + id);
            return null;
        }
    }

    private WorldGenProject loadProject(Path file, String safeId) {
        try {
            WorldGenProject project = WorldGenSerializer.deserializeProject(StorageSafety.readUtf8(file));
            if (project != null) {
                if (project.getId() == null || project.getId().isBlank()) {
                    project.setId(safeId);
                }
                cache.put(project.getId(), project);
            }
            return project;
        } catch (IOException e) {
            Log.warn("Failed to load WorldGen project: " + safeId + " - " + e.getMessage());
            return null;
        }
    }

    private Path findAssetFile(String id) {
        String fileName = "worldgen__" + id + ".json";
        Path root = assetsDir.toPath();
        if (!root.toFile().exists()) {
            return null;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            return paths
                    .filter(path -> Files.isRegularFile(path) && path.getFileName().toString().equals(fileName))
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            Log.warn("Failed to search WorldGen assets: " + id + " - " + e.getMessage());
            return null;
        }
    }

    private Path defaultAssetFile(String id) throws IOException {
        Path target = assetsDir.toPath().resolve("WorldGen").resolve("worldgen__" + id + ".json");
        Files.createDirectories(target.getParent());
        return target;
    }

    private void migrateLegacyAssets() {
        assetStore.migrateLegacyAssets();
    }

    private void migrateLegacyFile(Path file, String id) {
        try {
            Path target = defaultAssetFile(id);
            if (!Files.exists(target)) {
                Files.move(file, target, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.deleteIfExists(file);
            }
        } catch (IOException e) {
            Log.warn("Failed to migrate WorldGen asset: " + id + " - " + e.getMessage());
        }
    }

    private void deleteLegacyDirectory() {
        if (!projectDir.exists()) {
            return;
        }
        try (Stream<Path> paths = Files.walk(projectDir.toPath())) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            Log.warn("Failed to delete legacy WorldGen directory: " + e.getMessage());
        }
    }

    private void deleteAssetFile(Path file) throws IOException {
        Path root = assetsDir.toPath().toAbsolutePath().normalize();
        Path target = file.toAbsolutePath().normalize();
        if (!target.startsWith(root) || target.getParent() == null || !target.getFileName().toString().endsWith(".json")) {
            throw new IOException("Unsafe WorldGen assets delete target: " + file);
        }
        Files.deleteIfExists(target);
    }
}
