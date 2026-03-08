package restudio.resync.world;

import java.util.LinkedHashMap;
import java.util.Map;

public class WorldMapActionResult {
    private boolean success;
    private String extensionId;
    private String actionId;
    private String message;
    private Map<String, Object> data = new LinkedHashMap<>();

    public static WorldMapActionResult success(String extensionId, String actionId, String message) {
        WorldMapActionResult result = new WorldMapActionResult();
        result.success = true;
        result.extensionId = extensionId;
        result.actionId = actionId;
        result.message = message;
        return result;
    }

    public static WorldMapActionResult failure(String extensionId, String actionId, String message) {
        WorldMapActionResult result = new WorldMapActionResult();
        result.success = false;
        result.extensionId = extensionId;
        result.actionId = actionId;
        result.message = message;
        return result;
    }

    public WorldMapActionResult withData(String key, Object value) {
        data.put(key, value);
        return this;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
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

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public Map<String, Object> getData() {
        return data;
    }

    public void setData(Map<String, Object> data) {
        this.data = data == null ? new LinkedHashMap<>() : new LinkedHashMap<>(data);
    }
}
