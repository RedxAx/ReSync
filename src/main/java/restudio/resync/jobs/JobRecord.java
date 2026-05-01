package restudio.resync.jobs;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

public class JobRecord<T> {
    private final String jobId;
    private final String requestId;
    private final String action;
    private final String actorClientId;
    private final String target;
    private final long submittedAt;
    private final AtomicReference<JobStatus> status = new AtomicReference<>(JobStatus.PENDING);
    private final CompletableFuture<T> future = new CompletableFuture<>();
    private volatile long startedAt;
    private volatile long finishedAt;
    private volatile String message;
    private volatile String errorText;
    private volatile Object result;

    public JobRecord(String jobId, String action, String actorClientId, String target) {
        this(jobId, jobId, action, actorClientId, target);
    }

    public JobRecord(String jobId, String requestId, String action, String actorClientId, String target) {
        this.jobId = jobId;
        this.requestId = requestId;
        this.action = action;
        this.actorClientId = actorClientId;
        this.target = target;
        this.submittedAt = System.currentTimeMillis();
        this.message = "Pending";
    }

    public boolean markRunning() {
        if (!status.compareAndSet(JobStatus.PENDING, JobStatus.RUNNING)) {
            return false;
        }
        startedAt = System.currentTimeMillis();
        message = "Running";
        return true;
    }

    public boolean markSucceeded(T value, String message) {
        if (!markTerminal(JobStatus.SUCCEEDED, message, null, value)) {
            return false;
        }
        future.complete(value);
        return true;
    }

    public boolean markFailed(String message, Throwable error) {
        if (!markTerminal(JobStatus.FAILED, message, error != null ? error.getMessage() : message, null)) {
            return false;
        }
        future.completeExceptionally(error != null ? error : new IllegalStateException(message));
        return true;
    }

    public boolean cancel(String message) {
        if (!markTerminal(JobStatus.CANCELLED, message, message, null)) {
            return false;
        }
        future.cancel(false);
        return true;
    }

    private boolean markTerminal(JobStatus terminalStatus, String message, String errorText, Object result) {
        JobStatus current = status.get();
        while (!current.terminal()) {
            if (status.compareAndSet(current, terminalStatus)) {
                this.finishedAt = System.currentTimeMillis();
                this.message = message == null || message.isBlank() ? terminalStatus.wireName() : message;
                this.errorText = errorText;
                this.result = result;
                return true;
            }
            current = status.get();
        }
        return false;
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("jobId", jobId);
        data.put("operationId", requestId == null || requestId.isBlank() ? jobId : requestId);
        data.put("requestId", requestId);
        data.put("action", action);
        data.put("actorClientId", actorClientId);
        data.put("target", target);
        data.put("status", status.get().wireName());
        data.put("message", message);
        data.put("errorText", errorText);
        data.put("submittedAt", submittedAt);
        data.put("startedAt", startedAt);
        data.put("finishedAt", finishedAt);
        if (result != null) {
            data.put("result", result);
        }
        return data;
    }

    public String getJobId() {
        return jobId;
    }

    public String getRequestId() {
        return requestId;
    }

    public String getActorClientId() {
        return actorClientId;
    }

    public long getSubmittedAt() {
        return submittedAt;
    }

    public long getFinishedAt() {
        return finishedAt;
    }

    public JobStatus getStatus() {
        return status.get();
    }

    public CompletableFuture<T> getFuture() {
        return future;
    }
}
