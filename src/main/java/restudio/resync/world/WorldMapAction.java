package restudio.resync.world;

import java.util.LinkedHashMap;
import java.util.Map;

public class WorldMapAction {
    private String extensionId;
    private String actionId;
    private String worldName;
    private Map<String, Object> data = new LinkedHashMap<>();

    public WorldMapAction copy() {
        WorldMapAction copy = new WorldMapAction();
        copy.extensionId = extensionId;
        copy.actionId = actionId;
        copy.worldName = worldName;
        copy.data = data == null ? new LinkedHashMap<>() : new LinkedHashMap<>(data);
        return copy;
    }

    public String getExtensionId() {
        return extensionId;
    }

    public void setExtensionId(String extensionId) {
        this.extensionId = extensionId;
    }

    public String getActionId() {
        return actionId;
    }

    public void setActionId(String actionId) {
        this.actionId = actionId;
    }

    public String getWorldName() {
        return worldName;
    }

    public void setWorldName(String worldName) {
        this.worldName = worldName;
    }

    public Map<String, Object> getData() {
        return data;
    }

    public void setData(Map<String, Object> data) {
        this.data = data == null ? new LinkedHashMap<>() : new LinkedHashMap<>(data);
    }
}
