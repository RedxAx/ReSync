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
    private final String operationId;
    private final String action;
    private final String actorClientId;
    private final String target;
    private final String mutationId;
    private final long expectedRevision;
    private final String intentHash;
    private final long submittedAt;
    private final FlowJobReference<T> canonicalReference;
    private final FlowJobRegistry canonicalRegistry;
    private final AtomicReference<JobStatus> status = new AtomicReference<>(JobStatus.PENDING);
    private final CompletableFuture<T> future = new CompletableFuture<>();
    private final Object terminalMonitor = new Object();
    private volatile long startedAt;
    private volatile long finishedAt;
    private volatile String message;
    private volatile String errorText;
    private volatile Object result;
    private JobStatus canonicalPayloadStatus;
    private CanonicalTransition canonicalTransition;

    public JobRecord(String jobId, String action, String actorClientId, String target) {
        this(jobId, jobId, jobId, action, actorClientId, target, null, null, null, -1L, null);
    }

    public JobRecord(String jobId, String requestId, String action, String actorClientId, String target) {
        this(jobId, requestId, jobId, action, actorClientId, target, null, null, null, -1L, null);
    }

    public JobRecord(String jobId, String requestId, String action, String actorClientId, String target,
                     String operationId) {
        this(jobId, requestId, operationId, action, actorClientId, target, null, null, null, -1L, null);
    }

    JobRecord(String requestId, String action, String actorClientId, String target, FlowJobReference<T> canonicalReference, FlowJobRegistry canonicalRegistry) {
        this(canonicalReference.getId(), requestId, canonicalReference.getId(), action, actorClientId, target,
            canonicalReference, canonicalRegistry, null, -1L, null);
    }

    JobRecord(String requestId, String action, String actorClientId, String target, FlowJobReference<T> canonicalReference,
              FlowJobRegistry canonicalRegistry, String mutationId, long expectedRevision, String intentHash) {
        this(canonicalReference.getId(), requestId, canonicalReference.getId(), action, actorClientId, target,
            canonicalReference, canonicalRegistry, mutationId, expectedRevision, intentHash);
    }

    JobRecord(String requestId, String operationId, String action, String actorClientId, String target,
              FlowJobReference<T> canonicalReference, FlowJobRegistry canonicalRegistry, String mutationId,
              long expectedRevision, String intentHash) {
        this(canonicalReference.getId(), requestId, operationId, action, actorClientId, target, canonicalReference,
            canonicalRegistry, mutationId, expectedRevision, intentHash);
    }

    private JobRecord(String jobId, String requestId, String operationId, String action, String actorClientId, String target,
                      FlowJobReference<T> canonicalReference, FlowJobRegistry canonicalRegistry, String mutationId,
                      long expectedRevision, String intentHash) {
        this.jobId = jobId;
        this.requestId = requestId;
        this.operationId = operationId == null || operationId.isBlank() ? jobId : operationId;
        this.action = action;
        this.actorClientId = actorClientId;
        this.target = target;
        this.mutationId = mutationId;
        this.expectedRevision = expectedRevision;
        this.intentHash = intentHash;
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
            if (canonicalReference.getState() == FlowJobReference.State.RUNNING) {
                return recordRunningFromCanonical();
            }
            if (canonicalReference.getState() != FlowJobReference.State.PENDING) {
                return false;
            }
            canonicalRegistry.start(canonicalReference);
            return recordRunningFromCanonical();
        }
        synchronized (terminalMonitor) {
            if (status.get() != JobStatus.PENDING) {
                return false;
            }
            startedAt = System.currentTimeMillis();
            message = "Running";
            status.set(JobStatus.RUNNING);
            return true;
        }
    }

    boolean recordRunningFromCanonical() {
        synchronized (terminalMonitor) {
            if (canonicalReference == null || canonicalReference.getState() != FlowJobReference.State.RUNNING) {
                return false;
            }
            if (canonicalPayloadStatus != null) {
                return false;
            }
            status.set(JobStatus.RUNNING);
            startedAt = System.currentTimeMillis();
            message = "Running";
            return true;
        }
    }

    public boolean markSucceeded(T value, String message) {
        if (canonicalReference != null) {
            String terminalMessage = message == null || message.isBlank() ? JobStatus.SUCCEEDED.wireName() : message;
            CanonicalTransition transition = beginCanonicalTransition(JobStatus.SUCCEEDED, terminalMessage, null, value, null);
            if (transition == null) {
                return false;
            }
            try {
                boolean accepted = canonicalRegistry.succeed(canonicalReference, value);
                finishCanonicalTransition(transition, accepted, null);
                return accepted;
            } catch (RuntimeException | Error failure) {
                finishCanonicalTransition(transition, false, failure);
                throw failure;
            }
        }
        if (!markTerminal(JobStatus.SUCCEEDED, message, null, value)) {
            return false;
        }
        future.complete(value);
        return true;
    }

    public boolean markFailed(String message, Throwable error) {
        if (canonicalReference != null) {
            String terminalMessage = message == null || message.isBlank() ? JobStatus.FAILED.wireName() : message;
            String terminalError = error != null && error.getMessage() != null && !error.getMessage().isBlank()
                ? error.getMessage() : terminalMessage;
            CanonicalTransition transition = beginCanonicalTransition(JobStatus.FAILED, terminalMessage, terminalError,
                null, error);
            if (transition == null) {
                return false;
            }
            try {
                boolean accepted = canonicalRegistry.fail(canonicalReference, "JOB_FAILED", terminalMessage,
                    Map.of("error", terminalError));
                finishCanonicalTransition(transition, accepted, null);
                return accepted;
            } catch (RuntimeException | Error failure) {
                finishCanonicalTransition(transition, false, failure);
                throw failure;
            }
        }
        if (!markTerminal(JobStatus.FAILED, message, error != null ? error.getMessage() : message, null)) {
            return false;
        }
        future.completeExceptionally(error != null ? error : new IllegalStateException(message));
        return true;
    }

    public boolean cancel(String message) {
        if (canonicalReference != null) {
            boolean accepted = canonicalRegistry.cancel(canonicalReference);
            if (!accepted) {
                return false;
            }
            synchronized (terminalMonitor) {
                if (canonicalReference.getState() != FlowJobReference.State.CANCELLING) {
                    return true;
                }
                if (canonicalTransition != null) {
                    return true;
                }
                canonicalPayloadStatus = null;
                this.message = "Cancellation Requested";
                this.errorText = null;
                this.result = null;
                return true;
            }
        }
        if (!markTerminal(JobStatus.CANCELLED, message, message, null)) {
            return false;
        }
        future.cancel(false);
        return true;
    }

    public boolean markCancelledAfterPhysical(String message) {
        if (canonicalReference != null) {
            String terminalMessage = message == null || message.isBlank() ? JobStatus.CANCELLED.wireName() : message;
            CanonicalTransition transition = beginCancellationTransition(terminalMessage);
            if (transition == null) {
                return false;
            }
            try {
                boolean accepted = canonicalRegistry.completeCancellationAfterPhysical(canonicalReference);
                finishCanonicalTransition(transition, accepted, null);
                return accepted;
            } catch (RuntimeException | Error failure) {
                finishCanonicalTransition(transition, false, failure);
                throw failure;
            }
        }
        if (!markTerminal(JobStatus.CANCELLED, message, message, null)) {
            return false;
        }
        future.cancel(false);
        return true;
    }

    private CanonicalTransition beginCancellationTransition(String message) {
        synchronized (terminalMonitor) {
            if (canonicalTransition != null) {
                if (statusFor(canonicalReference.getState()).terminal()) {
                    completeFromCanonicalLocked(canonicalReference.getCompletion().getNow(null), null);
                }
                return null;
            }
            FlowJobReference.State canonicalState = canonicalReference.getState();
            if (statusFor(canonicalState).terminal()) {
                completeCanonicalPayloadLocked(canonicalReference.getCompletion().getNow(null), null);
                return null;
            }
            if (!canonicalReference.isCancellationRequested() && !canonicalReference.requestCancellation()) {
                return null;
            }
            if (canonicalReference.getState() != FlowJobReference.State.CANCELLING) {
                synchronizeCanonicalStateLocked(null, null);
                return null;
            }
            CanonicalTransition transition = new CanonicalTransition(JobStatus.CANCELLED, message, message, null, null,
                captureCanonicalPayload(), Thread.currentThread());
            canonicalTransition = transition;
            stageCanonicalPayload(JobStatus.CANCELLED, message, message, null);
            return transition;
        }
    }

    private boolean markTerminal(JobStatus terminalStatus, String message, String errorText, Object result) {
        synchronized (terminalMonitor) {
            if (status.get().terminal()) {
                return false;
            }
            this.finishedAt = System.currentTimeMillis();
            this.message = message == null || message.isBlank() ? terminalStatus.wireName() : message;
            this.errorText = errorText;
            this.result = result;
            status.set(terminalStatus);
            return true;
        }
    }

    private CanonicalPayload captureCanonicalPayload() {
        return new CanonicalPayload(canonicalPayloadStatus, message, errorText, result, finishedAt);
    }

    private CanonicalTransition beginCanonicalTransition(JobStatus terminalStatus, String message, String errorText,
                                                         T result, Throwable failure) {
        synchronized (terminalMonitor) {
            if (canonicalTransition != null) {
                if (statusFor(canonicalReference.getState()).terminal()) {
                    completeFromCanonicalLocked(canonicalReference.getCompletion().getNow(null), null);
                }
                return null;
            }
            FlowJobReference.State canonicalState = canonicalReference.getState();
            JobStatus canonicalStatus = statusFor(canonicalState);
            if (canonicalStatus.terminal()) {
                completeCanonicalPayloadLocked(canonicalReference.getCompletion().getNow(null), null);
                return null;
            }
            if (terminalStatus != JobStatus.CANCELLED && canonicalState == FlowJobReference.State.CANCELLING) {
                synchronizeCanonicalStateLocked(null, null);
                return null;
            }
            if (terminalStatus == JobStatus.CANCELLED && canonicalState != FlowJobReference.State.CANCELLING) {
                synchronizeCanonicalStateLocked(null, null);
                return null;
            }
            CanonicalTransition transition = new CanonicalTransition(terminalStatus, message, errorText, result,
                failure, captureCanonicalPayload(), Thread.currentThread());
            canonicalTransition = transition;
            stageCanonicalPayload(terminalStatus, message, errorText, result);
            return transition;
        }
    }

    private void finishCanonicalTransition(CanonicalTransition transition, boolean accepted, Throwable failure) {
        synchronized (terminalMonitor) {
            if (canonicalTransition != transition) {
                return;
            }
            FlowJobReference.State canonicalState = canonicalReference.getState();
            JobStatus canonicalStatus = statusFor(canonicalState);
            boolean ownerWon = accepted || failure != null && transition.ownerCompletionObserved();
            canonicalTransition = null;
            if (ownerWon && canonicalStatus == transition.status()) {
                applyStagedCanonicalPayloadLocked(transition);
            } else if (canonicalStatus.terminal()) {
                canonicalPayloadStatus = null;
                FlowOperationResult<T> outcome = transition.externalOutcome() != null
                    ? transition.externalOutcome() : canonicalReference.getCompletion().getNow(null);
                completeCanonicalPayloadLocked(outcome, transition.externalFailure());
            } else {
                restoreCanonicalPayload(transition.previous());
            }
            terminalMonitor.notifyAll();
        }
    }

    private void stageCanonicalPayload(JobStatus terminalStatus, String message, String errorText, Object result) {
        this.message = message;
        this.errorText = errorText;
        this.result = result;
        this.canonicalPayloadStatus = terminalStatus;
    }

    private void restoreCanonicalPayload(CanonicalPayload previous) {
        FlowJobReference.State canonicalState = canonicalReference.getState();
        JobStatus canonicalStatus = statusFor(canonicalState);
        if (canonicalStatus.terminal()) {
            canonicalPayloadStatus = null;
            FlowOperationResult<T> outcome = canonicalReference.getCompletion().getNow(null);
            completeCanonicalPayloadLocked(outcome, null);
            return;
        }
        canonicalPayloadStatus = null;
        status.set(canonicalStatus);
        message = switch (canonicalState) {
            case PENDING -> "Pending";
            case CANCELLING -> "Cancellation Requested";
            case RUNNING -> "Running";
            case SUCCEEDED, FAILED, CANCELLED -> canonicalStatus.wireName();
        };
        errorText = null;
        result = null;
    }

    private void applyStagedCanonicalPayloadLocked(CanonicalTransition transition) {
        status.set(transition.status());
        if (finishedAt == 0L) {
            finishedAt = System.currentTimeMillis();
        }
        message = transition.message();
        errorText = transition.errorText();
        result = transition.result();
        canonicalPayloadStatus = transition.status();
        if (transition.status() == JobStatus.SUCCEEDED) {
            future.complete(transition.result());
        } else if (transition.status() == JobStatus.FAILED) {
            future.completeExceptionally(transition.failure() != null ? transition.failure()
                : new IllegalStateException(transition.errorText()));
        } else {
            future.cancel(false);
        }
    }

    private JobStatus statusFor(FlowJobReference.State state) {
        return switch (state) {
            case PENDING -> JobStatus.PENDING;
            case RUNNING, CANCELLING -> JobStatus.RUNNING;
            case SUCCEEDED -> JobStatus.SUCCEEDED;
            case FAILED -> JobStatus.FAILED;
            case CANCELLED -> JobStatus.CANCELLED;
        };
    }

    private void completeFromCanonical(FlowOperationResult<T> outcome, Throwable failure) {
        synchronized (terminalMonitor) {
            completeFromCanonicalLocked(outcome, failure);
        }
    }

    private void completeFromCanonicalLocked(FlowOperationResult<T> outcome, Throwable failure) {
        CanonicalTransition transition = canonicalTransition;
        if (transition != null && transition.owner() != Thread.currentThread()) {
            transition.externalOutcome = outcome;
            transition.externalFailure = failure;
            JobStatus terminalStatus = statusFor(canonicalReference.getState());
            status.set(terminalStatus);
            if (!terminalStatus.terminal()) {
                return;
            }
            if (finishedAt == 0L) {
                finishedAt = System.currentTimeMillis();
            }
            completeExternalFutureLocked(terminalStatus, outcome, failure);
            return;
        }
        JobStatus terminalStatus = statusFor(canonicalReference.getState());
        if (transition != null && terminalStatus.terminal()) {
            transition.ownerCompletionObserved = true;
        }
        completeCanonicalPayloadLocked(outcome, failure);
    }

    private void completeCanonicalPayloadLocked(FlowOperationResult<T> outcome, Throwable failure) {
        JobStatus terminalStatus = statusFor(canonicalReference.getState());
        status.set(terminalStatus);
        if (!terminalStatus.terminal()) {
            return;
        }
        if (finishedAt == 0L) {
            finishedAt = System.currentTimeMillis();
        }
        JobStatus stagedStatus = canonicalPayloadStatus;
        if (terminalStatus == JobStatus.CANCELLED) {
            if (stagedStatus != JobStatus.CANCELLED) {
                message = outcome != null && outcome.message() != null && !outcome.message().isBlank()
                    ? outcome.message() : JobStatus.CANCELLED.wireName();
                errorText = message;
            }
            result = null;
            canonicalPayloadStatus = JobStatus.CANCELLED;
            future.cancel(false);
            return;
        }
        if (failure != null || outcome == null || !outcome.success()) {
            if (stagedStatus != JobStatus.FAILED) {
                message = outcome != null && outcome.message() != null && !outcome.message().isBlank()
                    ? outcome.message() : JobStatus.FAILED.wireName();
                String canonicalError = failure != null ? failure.getMessage() : outcome != null ? outcome.message() : null;
                errorText = canonicalError == null || canonicalError.isBlank() ? "Job Failed" : canonicalError;
            }
            result = null;
            canonicalPayloadStatus = JobStatus.FAILED;
            future.completeExceptionally(failure != null ? failure : new IllegalStateException(errorText));
            return;
        }
        if (stagedStatus != JobStatus.SUCCEEDED) {
            message = JobStatus.SUCCEEDED.wireName();
            errorText = null;
            result = outcome.value();
        }
        canonicalPayloadStatus = JobStatus.SUCCEEDED;
        future.complete(outcome.value());
    }

    private void completeExternalFutureLocked(JobStatus terminalStatus, FlowOperationResult<T> outcome, Throwable failure) {
        if (terminalStatus == JobStatus.CANCELLED) {
            future.cancel(false);
            return;
        }
        if (failure != null || outcome == null || !outcome.success()) {
            String error = failure != null ? failure.getMessage() : outcome != null ? outcome.message() : null;
            future.completeExceptionally(failure != null ? failure : new IllegalStateException(
                error == null || error.isBlank() ? "Job Failed" : error));
            return;
        }
        future.complete(outcome.value());
    }

    private void synchronizeCanonicalStateLocked(FlowOperationResult<T> outcome, Throwable failure) {
        if (statusFor(canonicalReference.getState()).terminal()) {
            completeCanonicalPayloadLocked(outcome, failure);
            return;
        }
        canonicalPayloadStatus = null;
        status.set(statusFor(canonicalReference.getState()));
        message = switch (canonicalReference.getState()) {
            case PENDING -> "Pending";
            case CANCELLING -> "Cancellation Requested";
            case RUNNING -> "Running";
            case SUCCEEDED, FAILED, CANCELLED -> statusFor(canonicalReference.getState()).wireName();
        };
        errorText = null;
        result = null;
    }

    void synchronizeCanonical() {
        if (canonicalReference == null) {
            return;
        }
        if (canonicalReference.getState() == FlowJobReference.State.CANCELLING) {
            canonicalRegistry.completeCancellationAfterPhysical(canonicalReference);
        }
        synchronized (terminalMonitor) {
            FlowOperationResult<T> outcome = canonicalReference.getCompletion().getNow(null);
            if (outcome != null || canonicalTerminal()) {
                completeFromCanonicalLocked(outcome, null);
            }
        }
    }

    boolean canonicalTerminal() {
        if (canonicalReference == null) {
            return false;
        }
        return switch (canonicalReference.getState()) {
            case SUCCEEDED, FAILED, CANCELLED -> true;
            case PENDING, RUNNING, CANCELLING -> false;
        };
    }

    boolean terminalPruneReady(boolean publicationComplete, boolean publicationInProgress) {
        synchronized (terminalMonitor) {
            JobStatus currentStatus = canonicalReference == null ? status.get() : statusFor(canonicalReference.getState());
            if (!currentStatus.terminal() || finishedAt <= 0L) {
                return false;
            }
            return canonicalReference == null || canonicalReference.getCompletion().isDone()
                && publicationComplete && !publicationInProgress;
        }
    }

    public Map<String, Object> snapshot() {
        synchronized (terminalMonitor) {
            JobStatus snapshotStatus = getStatus();
            String snapshotMessage = message;
            String snapshotErrorText = errorText;
            Object snapshotResult = result;
            if (canonicalReference != null) {
                FlowOperationResult<T> outcome = canonicalReference.getCompletion().getNow(null);
                boolean stagedPayloadVisible = canonicalTransition == null
                    || canonicalTransition.owner() == Thread.currentThread();
                JobStatus visiblePayloadStatus = stagedPayloadVisible ? canonicalPayloadStatus : null;
                if (snapshotStatus.terminal() && finishedAt == 0L) {
                    finishedAt = System.currentTimeMillis();
                }
                switch (snapshotStatus) {
                    case CANCELLED -> {
                        if (visiblePayloadStatus != JobStatus.CANCELLED) {
                            snapshotMessage = outcome != null && outcome.message() != null && !outcome.message().isBlank()
                                ? outcome.message() : JobStatus.CANCELLED.wireName();
                            snapshotErrorText = snapshotMessage;
                        }
                        snapshotResult = null;
                    }
                    case FAILED -> {
                        if (visiblePayloadStatus != JobStatus.FAILED) {
                            snapshotMessage = outcome != null && outcome.message() != null && !outcome.message().isBlank()
                                ? outcome.message() : JobStatus.FAILED.wireName();
                            snapshotErrorText = canonicalErrorText(outcome);
                        }
                        snapshotResult = null;
                    }
                    case SUCCEEDED -> {
                        if (visiblePayloadStatus != JobStatus.SUCCEEDED) {
                            snapshotResult = outcome != null && outcome.success() ? outcome.value() : result;
                        }
                    }
                    case PENDING -> {
                        snapshotMessage = "Pending";
                        snapshotErrorText = null;
                        snapshotResult = null;
                    }
                    case RUNNING -> {
                        snapshotMessage = canonicalReference.getState() == FlowJobReference.State.CANCELLING
                            ? "Cancellation Requested" : "Running";
                        snapshotErrorText = null;
                        snapshotResult = null;
                    }
                }
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("jobId", jobId);
            data.put("operationId", operationId);
            data.put("requestId", requestId);
            data.put("action", action);
            data.put("actorClientId", actorClientId);
            data.put("target", target);
            data.put("mutationId", mutationId);
            data.put("expectedRevision", expectedRevision);
            data.put("intentHash", intentHash);
            data.put("status", snapshotStatus.wireName());
            data.put("message", snapshotMessage);
            data.put("errorText", snapshotErrorText);
            data.put("submittedAt", submittedAt);
            data.put("startedAt", startedAt);
            data.put("finishedAt", finishedAt);
            if (snapshotResult != null && snapshotStatus == JobStatus.SUCCEEDED) {
                data.put("result", snapshotResult);
            }
            return data;
        }
    }

    private String canonicalErrorText(FlowOperationResult<T> outcome) {
        if (outcome != null) {
            Object detail = outcome.details().get("error");
            if (detail != null) {
                String text = String.valueOf(detail);
                if (!text.isBlank()) {
                    return text;
                }
            }
            if (outcome.message() != null && !outcome.message().isBlank()) {
                return outcome.message();
            }
        }
        return "Job Failed";
    }

    public String getJobId() {
        return jobId;
    }

    public String getRequestId() {
        return requestId;
    }

    public String getOperationId() {
        return operationId;
    }

    public String getActorClientId() {
        return actorClientId;
    }

    public String getAction() {
        return action;
    }

    public String getTarget() {
        return target;
    }

    public String getMutationId() {
        return mutationId;
    }

    public long getExpectedRevision() {
        return expectedRevision;
    }

    public String getIntentHash() {
        return intentHash;
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
            case CANCELLING -> JobStatus.RUNNING;
            case SUCCEEDED -> JobStatus.SUCCEEDED;
            case FAILED -> JobStatus.FAILED;
            case CANCELLED -> JobStatus.CANCELLED;
        };
    }

    public CompletableFuture<T> getFuture() {
        return future;
    }

    FlowJobReference<T> canonicalReference() {
        if (canonicalReference == null) {
            throw new IllegalStateException("Canonical Flow Job Reference Is Required");
        }
        return canonicalReference;
    }

    private final class CanonicalTransition {
        private final JobStatus status;
        private final String message;
        private final String errorText;
        private final T result;
        private final Throwable failure;
        private final CanonicalPayload previous;
        private final Thread owner;
        private FlowOperationResult<T> externalOutcome;
        private Throwable externalFailure;
        private boolean ownerCompletionObserved;

        private CanonicalTransition(JobStatus status, String message, String errorText, T result, Throwable failure,
                                    CanonicalPayload previous, Thread owner) {
            this.status = status;
            this.message = message;
            this.errorText = errorText;
            this.result = result;
            this.failure = failure;
            this.previous = previous;
            this.owner = owner;
        }

        private JobStatus status() {
            return status;
        }

        private String message() {
            return message;
        }

        private String errorText() {
            return errorText;
        }

        private T result() {
            return result;
        }

        private Throwable failure() {
            return failure;
        }

        private CanonicalPayload previous() {
            return previous;
        }

        private Thread owner() {
            return owner;
        }

        private FlowOperationResult<T> externalOutcome() {
            return externalOutcome;
        }

        private Throwable externalFailure() {
            return externalFailure;
        }

        private boolean ownerCompletionObserved() {
            return ownerCompletionObserved;
        }
    }

    private record CanonicalPayload(JobStatus status, String message, String errorText, Object result, long finishedAt) {
    }
}
