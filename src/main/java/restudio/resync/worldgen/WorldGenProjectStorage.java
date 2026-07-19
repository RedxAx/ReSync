package restudio.resync.worldgen;

import org.bukkit.plugin.java.JavaPlugin;
import restudio.resync.Log;
import restudio.resync.resources.JsonAssetStore;
import restudio.resync.resources.ReSyncResourceCatalog;
import restudio.resync.storage.StorageSafety;
import restudio.resync.worldgen.data.WorldGenProject;
import restudio.resync.worldgen.data.WorldGenSerializer;

import java.io.File;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public class WorldGenProjectStorage {
    private final File projectDir;
    private final Path projectPath;
    private final File assetsDir;
    private final JsonAssetStore<WorldGenProject> assetStore;
    private final Map<String, WorldGenProject> cache = new ConcurrentHashMap<>();
    private Runnable changeListener = () -> {
    };

    public WorldGenProjectStorage(JavaPlugin plugin) {
        this(plugin.getDataFolder());
    }

    public WorldGenProjectStorage(File dataFolder) {
        this.projectDir = new File(dataFolder, "worldgen-projects");
        this.projectPath = projectDir.toPath();
        this.assetsDir = new File(dataFolder, "assets");
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
            notifyChange();
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
            notifyChange();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to delete WorldGen project: " + safeId, e);
        }
    }

    public List<String> listProjectIds() {
        Set<String> ids = new HashSet<>(assetStore.listIds());
        return ids.stream().sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    public WorldGenProject reloadProject(String id, Consumer<WorldGenProject> validator) {
        String safeId = safeId(id, "reload");
        if (safeId == null) {
            throw new IllegalArgumentException("Invalid WorldGen project id");
        }
        WorldGenProject project = assetStore.reload(safeId, validator);
        if (project != null) {
            cache.put(safeId, project);
        } else {
            cache.remove(safeId);
        }
        notifyChange();
        return project;
    }

    public void setChangeListener(Runnable changeListener) {
        this.changeListener = changeListener != null ? changeListener : () -> {
        };
    }

    private void notifyChange() {
        try {
            changeListener.run();
        } catch (RuntimeException exception) {
            Log.warn("Failed to publish WorldGen project catalog change: " + exception.getMessage());
        }
    }

    private String safeId(String id, String action) {
        try {
            return StorageSafety.validateId(id);
        } catch (IllegalArgumentException e) {
            Log.warn("Rejected unsafe WorldGen project id during " + action + ": " + id);
            return null;
        }
    }

    private void migrateLegacyAssets() {
        assetStore.migrateLegacyAssets();
    }
}
