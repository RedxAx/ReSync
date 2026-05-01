package restudio.resync.world;

import java.util.LinkedHashMap;
import java.util.Map;

public class WorldOperationAuditRecord {
    private String auditId;
    private String operationId;
    private String action;
    private String actorClientId;
    private String targetWorld;
    private Map<String, Object> parameters = new LinkedHashMap<>();
    private boolean success;
    private String message;
    private String failureReason;
    private String safetyBackupId;
    private boolean backupAvailable;
    private long startedAt;
    private long finishedAt;
    private long durationMillis;

    public String getAuditId() {
        return auditId;
    }

    public void setAuditId(String auditId) {
        this.auditId = auditId;
    }

    public String getOperationId() {
        return operationId;
    }

    public void setOperationId(String operationId) {
        this.operationId = operationId;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getActorClientId() {
        return actorClientId;
    }

    public void setActorClientId(String actorClientId) {
        this.actorClientId = actorClientId;
    }

    public String getTargetWorld() {
        return targetWorld;
    }

    public void setTargetWorld(String targetWorld) {
        this.targetWorld = targetWorld;
    }

    public Map<String, Object> getParameters() {
        return parameters;
    }

    public void setParameters(Map<String, Object> parameters) {
        this.parameters = parameters == null ? new LinkedHashMap<>() : new LinkedHashMap<>(parameters);
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public void setFailureReason(String failureReason) {
        this.failureReason = failureReason;
    }

    public String getSafetyBackupId() {
        return safetyBackupId;
    }

    public void setSafetyBackupId(String safetyBackupId) {
        this.safetyBackupId = safetyBackupId;
    }

    public boolean isBackupAvailable() {
        return backupAvailable;
    }

    public void setBackupAvailable(boolean backupAvailable) {
        this.backupAvailable = backupAvailable;
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

    public long getDurationMillis() {
        return durationMillis;
    }

    public void setDurationMillis(long durationMillis) {
        this.durationMillis = durationMillis;
    }
}
