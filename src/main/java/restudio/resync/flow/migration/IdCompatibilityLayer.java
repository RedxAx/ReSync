package restudio.resync.flow.migration;

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
        for (Map.Entry<String, String> entry : FlowNodeMigrationMap.load().entrySet()) {
            String oldId = entry.getKey();
            String newId = entry.getValue();
            oldToNew.put(oldId, newId);
            newToOld.putIfAbsent(newId, oldId);
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
