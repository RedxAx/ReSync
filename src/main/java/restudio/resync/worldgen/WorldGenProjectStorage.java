package restudio.resync.worldgen;

import org.bukkit.plugin.java.JavaPlugin;
import restudio.resync.Log;
import restudio.resync.worldgen.data.WorldGenProject;
import restudio.resync.worldgen.data.WorldGenSerializer;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class WorldGenProjectStorage {
    private final File projectDir;
    private final Map<String, WorldGenProject> cache = new ConcurrentHashMap<>();

    public WorldGenProjectStorage(JavaPlugin plugin) {
        this.projectDir = new File(plugin.getDataFolder(), "worldgen-projects");
        if (!projectDir.exists()) {
            projectDir.mkdirs();
        }
    }

    public WorldGenProject getProject(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        WorldGenProject cached = cache.get(id);
        if (cached != null) {
            return cached;
        }
        File file = new File(projectDir, id + ".json");
        if (!file.exists()) {
            return null;
        }
        try {
            WorldGenProject project = WorldGenSerializer.deserializeProject(Files.readString(file.toPath(), StandardCharsets.UTF_8));
            if (project != null) {
                if (project.getId() == null || project.getId().isBlank()) {
                    project.setId(id);
                }
                cache.put(project.getId(), project);
            }
            return project;
        } catch (IOException e) {
            Log.warn("Failed to load WorldGen project: " + id + " - " + e.getMessage());
            return null;
        }
    }

    public void saveProject(WorldGenProject project) {
        if (project == null || project.getId() == null || project.getId().isBlank()) {
            return;
        }
        project.rebuildIndices();
        cache.put(project.getId(), project);
        File file = new File(projectDir, project.getId() + ".json");
        try {
            Files.writeString(file.toPath(), WorldGenSerializer.serializeProject(project), StandardCharsets.UTF_8);
        } catch (IOException e) {
            Log.warn("Failed to save WorldGen project: " + project.getId() + " - " + e.getMessage());
        }
    }

    public void deleteProject(String id) {
        if (id == null || id.isBlank()) {
            return;
        }
        cache.remove(id);
        File file = new File(projectDir, id + ".json");
        try {
            Files.deleteIfExists(file.toPath());
        } catch (IOException e) {
            Log.warn("Failed to delete WorldGen project: " + id + " - " + e.getMessage());
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
            ids.add(name.substring(0, name.length() - 5));
        }
        ids.sort(String.CASE_INSENSITIVE_ORDER);
        return ids;
    }
}
