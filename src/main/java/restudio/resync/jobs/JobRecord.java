package restudio.resync.jobs;

import restudio.flow.data.FlowJobReference;
import restudio.flow.data.FlowOperationResult;
import restudio.resync.flow.jobs.FlowJobRegistry;

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
    private final FlowJobReference<T> canonicalReference;
    private final FlowJobRegistry canonicalRegistry;
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
        this(jobId, requestId, action, actorClientId, target, null, null);
    }

    JobRecord(String requestId, String action, String actorClientId, String target, FlowJobReference<T> canonicalReference, FlowJobRegistry canonicalRegistry) {
        this(canonicalReference.getId(), requestId, action, actorClientId, target, canonicalReference, canonicalRegistry);
    }

    private JobRecord(String jobId, String requestId, String action, String actorClientId, String target, FlowJobReference<T> canonicalReference,
                      FlowJobRegistry canonicalRegistry) {
        this.jobId = jobId;
        this.requestId = requestId;
        this.action = action;
        this.actorClientId = actorClientId;
        this.target = target;
        this.canonicalReference = canonicalReference;
        this.canonicalRegistry = canonicalRegistry;
        this.submittedAt = canonicalReference != null ? canonicalReference.getCreatedAt().toEpochMilli() : System.currentTimeMillis();
        this.message = "Pending";
        if (canonicalReference != null) {
            canonicalReference.getCompletion().whenComplete(this::completeFromCanonical);
        }
    }

    public boolean markRunning() {
        if (canonicalReference != null) {
            if (canonicalReference.getState() != FlowJobReference.State.PENDING) {
                return false;
            }
            canonicalRegistry.start(canonicalReference);
            if (canonicalReference.getState() != FlowJobReference.State.RUNNING) {
                return false;
            }
            status.set(JobStatus.RUNNING);
            startedAt = System.currentTimeMillis();
            message = "Running";
            return true;
        }
        if (!status.compareAndSet(JobStatus.PENDING, JobStatus.RUNNING)) {
            return false;
        }
        startedAt = System.currentTimeMillis();
        message = "Running";
        return true;
    }

    public boolean markSucceeded(T value, String message) {
        if (canonicalReference != null) {
            this.message = message == null || message.isBlank() ? JobStatus.SUCCEEDED.wireName() : message;
            this.result = value;
            return canonicalRegistry.succeed(canonicalReference, value);
        }
        if (!markTerminal(JobStatus.SUCCEEDED, message, null, value)) {
            return false;
        }
        future.complete(value);
        return true;
    }

    public boolean markFailed(String message, Throwable error) {
        if (canonicalReference != null) {
            this.message = message == null || message.isBlank() ? JobStatus.FAILED.wireName() : message;
            this.errorText = error != null && error.getMessage() != null ? error.getMessage() : this.message;
            return canonicalRegistry.fail(canonicalReference, "JOB_FAILED", this.message, Map.of("error", this.errorText));
        }
        if (!markTerminal(JobStatus.FAILED, message, error != null ? error.getMessage() : message, null)) {
            return false;
        }
        future.completeExceptionally(error != null ? error : new IllegalStateException(message));
        return true;
    }

    public boolean cancel(String message) {
        if (canonicalReference != null) {
            this.message = message == null || message.isBlank() ? JobStatus.CANCELLED.wireName() : message;
            this.errorText = this.message;
            return canonicalRegistry.cancel(canonicalReference);
        }
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

    private void completeFromCanonical(FlowOperationResult<T> outcome, Throwable failure) {
        JobStatus terminalStatus = switch (canonicalReference.getState()) {
            case SUCCEEDED -> JobStatus.SUCCEEDED;
            case FAILED -> JobStatus.FAILED;
            case CANCELLED -> JobStatus.CANCELLED;
            case RUNNING -> JobStatus.RUNNING;
            case PENDING -> JobStatus.PENDING;
        };
        status.set(terminalStatus);
        if (!terminalStatus.terminal()) {
            return;
        }
        finishedAt = System.currentTimeMillis();
        if (terminalStatus == JobStatus.CANCELLED) {
            future.cancel(false);
            return;
        }
        if (failure != null || outcome == null || !outcome.success()) {
            if ((message == null || message.isBlank()) && outcome != null) {
                message = outcome.message();
            }
            if (errorText == null || errorText.isBlank()) {
                errorText = failure != null ? failure.getMessage() : outcome != null ? outcome.message() : "Job Failed";
            }
            future.completeExceptionally(failure != null ? failure : new IllegalStateException(errorText));
            return;
        }
        result = outcome.value();
        future.complete(outcome.value());
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("jobId", jobId);
        data.put("operationId", requestId == null || requestId.isBlank() ? jobId : requestId);
        data.put("requestId", requestId);
        data.put("action", action);
        data.put("actorClientId", actorClientId);
        data.put("target", target);
        data.put("status", getStatus().wireName());
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
        if (canonicalReference == null) {
            return status.get();
        }
        return switch (canonicalReference.getState()) {
            case PENDING -> JobStatus.PENDING;
            case RUNNING -> JobStatus.RUNNING;
            case SUCCEEDED -> JobStatus.SUCCEEDED;
            case FAILED -> JobStatus.FAILED;
            case CANCELLED -> JobStatus.CANCELLED;
        };
    }

    public CompletableFuture<T> getFuture() {
        return future;
    }
}
