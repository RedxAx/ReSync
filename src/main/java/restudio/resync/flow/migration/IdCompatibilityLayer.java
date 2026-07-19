package restudio.resync.flow.migration;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class IdCompatibilityLayer {

    private final Map<String, String> oldToNew = new HashMap<>();
    private final Map<String, String> newToOld = new HashMap<>();

    public IdCompatibilityLayer() {
        loadMigrationMap();
    }

    private void loadMigrationMap() {
        try (InputStream is = getClass().getResourceAsStream("/nodes/migrated/_id_migration_map.json")) {
            if (is == null) {
                throw new IllegalStateException("Node migration map is missing");
            }
            Map<String, String> map = new Gson().fromJson(
                new InputStreamReader(is, StandardCharsets.UTF_8),
                new TypeToken<Map<String, String>>() {}.getType()
            );
            if (map != null) {
                for (Map.Entry<String, String> entry : map.entrySet()) {
                    String oldId = entry.getKey();
                    String newId = entry.getValue();
                    if (!newId.contains("DRAFT")) {
                        oldToNew.put(oldId, newId);
                        newToOld.put(newId, oldId);
                    }
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load node migration map", e);
        }
    }

    public String mapToNew(String oldId) {
        return oldToNew.getOrDefault(oldId, oldId);
    }

    public String mapToOld(String newId) {
        return newToOld.getOrDefault(newId, newId);
    }

    public boolean hasMapping(String id) {
        return oldToNew.containsKey(id) || newToOld.containsKey(id);
    }

    public Map<String, String> getAllMappings() {
        return Collections.unmodifiableMap(oldToNew);
    }
}
