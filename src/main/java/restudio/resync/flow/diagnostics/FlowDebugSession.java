package restudio.resync.flow.diagnostics;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;

public class FlowDebugSession {
    private final String sessionId;
    private final String graphId;
    private volatile String currentGraphId;
    private volatile String currentNodeId;
    private volatile String currentNodeType;
    private volatile int currentDepth;
    private volatile String status = "running";
    private volatile String reason = "";
    private volatile boolean stopRequested;
    private volatile String stepMode = "";
    private volatile int stepDepth;
    private volatile CompletableFuture<Void> pauseFuture;
    private volatile ScheduledFuture<?> autoResumeTask;
    private volatile long updatedAt = System.currentTimeMillis();

    public FlowDebugSession(String sessionId, String graphId) {
        this.sessionId = sessionId;
        this.graphId = graphId;
        this.currentGraphId = graphId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getGraphId() {
        return graphId;
    }

    public String getCurrentGraphId() {
        return currentGraphId;
    }

    public void setCurrentGraphId(String currentGraphId) {
        this.currentGraphId = currentGraphId;
        touch();
    }

    public String getCurrentNodeId() {
        return currentNodeId;
    }

    public void setCurrentNodeId(String currentNodeId) {
        this.currentNodeId = currentNodeId;
        touch();
    }

    public String getCurrentNodeType() {
        return currentNodeType;
    }

    public void setCurrentNodeType(String currentNodeType) {
        this.currentNodeType = currentNodeType;
        touch();
    }

    public int getCurrentDepth() {
        return currentDepth;
    }

    public void setCurrentDepth(int currentDepth) {
        this.currentDepth = currentDepth;
        touch();
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
        touch();
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
        touch();
    }

    public boolean isStopRequested() {
        return stopRequested;
    }

    public void setStopRequested(boolean stopRequested) {
        this.stopRequested = stopRequested;
        touch();
    }

    public String getStepMode() {
        return stepMode;
    }

    public void setStepMode(String stepMode) {
        this.stepMode = stepMode != null ? stepMode : "";
        touch();
    }

    public int getStepDepth() {
        return stepDepth;
    }

    public void setStepDepth(int stepDepth) {
        this.stepDepth = stepDepth;
        touch();
    }

    public CompletableFuture<Void> getPauseFuture() {
        return pauseFuture;
    }

    public void setPauseFuture(CompletableFuture<Void> pauseFuture) {
        this.pauseFuture = pauseFuture;
        touch();
    }

    public ScheduledFuture<?> getAutoResumeTask() {
        return autoResumeTask;
    }

    public void setAutoResumeTask(ScheduledFuture<?> autoResumeTask) {
        this.autoResumeTask = autoResumeTask;
        touch();
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public void touch() {
        updatedAt = System.currentTimeMillis();
    }
}
