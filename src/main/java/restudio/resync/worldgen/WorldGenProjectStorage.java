package restudio.resync.worldgen;

import org.bukkit.plugin.java.JavaPlugin;
import restudio.resync.Log;
import restudio.resync.storage.StorageSafety;
import restudio.resync.worldgen.data.WorldGenProject;
import restudio.resync.worldgen.data.WorldGenSerializer;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class WorldGenProjectStorage {
    private final File projectDir;
    private final Path projectPath;
    private final Map<String, WorldGenProject> cache = new ConcurrentHashMap<>();

    public WorldGenProjectStorage(JavaPlugin plugin) {
        this.projectDir = new File(plugin.getDataFolder(), "worldgen-projects");
        this.projectPath = projectDir.toPath();
        if (!projectDir.exists()) {
            projectDir.mkdirs();
        }
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
        Path file;
        try {
            file = StorageSafety.jsonFile(projectPath, safeId);
        } catch (IOException | IllegalArgumentException e) {
            Log.warn("Failed to resolve WorldGen project: " + safeId + " - " + e.getMessage());
            return null;
        }
        if (!file.toFile().exists()) {
            return null;
        }
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
            StorageSafety.writeUtf8Atomic(StorageSafety.jsonFile(projectPath, safeId), WorldGenSerializer.serializeProject(project));
            cache.put(safeId, project);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to save WorldGen project: " + safeId, e);
        }
    }

    public void deleteProject(String id) {
        String safeId = safeId(id, "delete");
        if (safeId == null) {
            throw new IllegalArgumentException("Invalid WorldGen project id");
        }
        try {
            StorageSafety.deleteIfExists(StorageSafety.jsonFile(projectPath, safeId));
            cache.remove(safeId);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to delete WorldGen project: " + safeId, e);
        }
    }

    public List<String> listProjectIds() {
        List<String> ids = new ArrayList<>();
        File[] files = projectDir.listFiles((dir, name) -> name.endsWith(".json"));
        if (files == null) {
            return ids;
        }
        for (File file : files) {
            String name = file.getName();
            String id = name.substring(0, name.length() - 5);
            if (safeId(id, "list") != null) {
                ids.add(id);
            }
        }
        ids.sort(String.CASE_INSENSITIVE_ORDER);
        return ids;
    }

    private String safeId(String id, String action) {
        try {
            return StorageSafety.validateId(id);
        } catch (IllegalArgumentException e) {
            Log.warn("Rejected unsafe WorldGen project id during " + action + ": " + id);
            return null;
        }
    }
}
