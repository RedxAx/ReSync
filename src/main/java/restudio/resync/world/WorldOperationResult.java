package restudio.resync.world;

import java.util.LinkedHashMap;
import java.util.Map;

public class WorldOperationResult {
    private boolean success;
    private String action;
    private String message;
    private String worldName;
    private String operationId;
    private String actorClientId;
    private long startedAt;
    private long finishedAt;
    private String safetyBackupId;
    private String auditId;
    private String status;
    private boolean requiresConfirmation;
    private Map<String, Object> data = new LinkedHashMap<>();

    public static WorldOperationResult success(String action, String worldName, String message) {
        WorldOperationResult result = new WorldOperationResult();
        result.success = true;
        result.status = "succeeded";
        result.action = action;
        result.worldName = worldName;
        result.message = message;
        return result;
    }

    public static WorldOperationResult failure(String action, String worldName, String message) {
        WorldOperationResult result = new WorldOperationResult();
        result.success = false;
        result.status = "failed";
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

    public String getOperationId() {
        return operationId;
    }

    public void setOperationId(String operationId) {
        this.operationId = operationId;
    }

    public String getActorClientId() {
        return actorClientId;
    }

    public void setActorClientId(String actorClientId) {
        this.actorClientId = actorClientId;
    }

    public long getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(long startedAt) {
        this.startedAt = startedAt;
    }

    public long getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(long finishedAt) {
        this.finishedAt = finishedAt;
    }

    public String getSafetyBackupId() {
        return safetyBackupId;
    }

    public void setSafetyBackupId(String safetyBackupId) {
        this.safetyBackupId = safetyBackupId;
    }

    public String getAuditId() {
        return auditId;
    }

    public void setAuditId(String auditId) {
        this.auditId = auditId;
    }

    public boolean isRequiresConfirmation() {
        return requiresConfirmation;
    }

    public void setRequiresConfirmation(boolean requiresConfirmation) {
        this.requiresConfirmation = requiresConfirmation;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
