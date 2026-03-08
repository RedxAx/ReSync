package restudio.resync.world;

import java.util.LinkedHashMap;
import java.util.Map;

public class WorldOperationResult {
    private boolean success;
    private String action;
    private String message;
    private String worldName;
    private Map<String, Object> data = new LinkedHashMap<>();

    public static WorldOperationResult success(String action, String worldName, String message) {
        WorldOperationResult result = new WorldOperationResult();
        result.success = true;
        result.action = action;
        result.worldName = worldName;
        result.message = message;
        return result;
    }

    public static WorldOperationResult failure(String action, String worldName, String message) {
        WorldOperationResult result = new WorldOperationResult();
        result.success = false;
        result.action = action;
        result.worldName = worldName;
        result.message = message;
        return result;
    }

    public WorldOperationResult withData(String key, Object value) {
        data.put(key, value);
        return this;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
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
