package restudio.resync.jobs;

import restudio.flow.data.FlowJobReference;
import restudio.resync.flow.jobs.FlowJobRegistry;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

public class JobManager {
    private static final int MAX_RETAINED = 1024;
    private static final long TERMINAL_RETENTION_MS = Duration.ofMinutes(15).toMillis();
    private final Map<String, JobRecord<?>> jobs = new ConcurrentHashMap<>();
    private final Map<String, JobRecord<?>> jobsByRequest = new ConcurrentHashMap<>();
    private final Map<JobIdentity, JobRecord<?>> jobsByIdentity = new ConcurrentHashMap<>();
    private final Map<String, JobIdentity> identitiesByJob = new ConcurrentHashMap<>();
    private final Map<String, String> mutationIdsByRequest = new ConcurrentHashMap<>();
    private final Map<String, JobExecutionLease> executions = new ConcurrentHashMap<>();
    private final Set<String> terminalPublications = ConcurrentHashMap.newKeySet();
    private final Set<String> terminalPublicationInProgress = ConcurrentHashMap.newKeySet();
    private final Consumer<JobRecord<?>> listener;
    private final FlowJobRegistry canonicalRegistry;
    private final Consumer<FlowJobReference.Snapshot<?>> canonicalListener;

    public JobManager(FlowJobRegistry canonicalRegistry, Consumer<JobRecord<?>> listener) {
        this.canonicalRegistry = Objects.requireNonNull(canonicalRegistry, "Flow Job Registry Is Required");
        this.listener = listener;
        this.canonicalListener = this::publishCanonicalTerminal;
        this.canonicalRegistry.addListener(canonicalListener);
    }

    public <T> JobRecord<T> create(String action, String actorClientId, String target) {
        return create(action, actorClientId, target, null);
    }

    @SuppressWarnings("unchecked")
    public synchronized <T> JobRecord<T> create(String action, String actorClientId, String target, String requestId) {
        Creation<T> creation = this.<T>createLocked(action, actorClientId, target, requestId, null, null, -1L, null);
        if (creation.disposition() == StartDisposition.IDENTITY_CONFLICT) {
            throw new IllegalArgumentException("Job request identity conflicts with an in-flight operation");
        }
        return creation.job();
    }

    public synchronized <T> StartedJob<T> createStarted(String action, String actorClientId, String target, String requestId) {
        return createStarted(action, actorClientId, target, requestId, null, null, -1L, null);
    }

    public synchronized <T> StartedJob<T> createStarted(String action, String actorClientId, String target,
                                                        String requestId, String mutationId) {
        return createStarted(action, actorClientId, target, requestId, mutationId, null, -1L, null);
    }

    public synchronized <T> StartedJob<T> createStarted(String action, String actorClientId, String target,
                                                        String requestId, String mutationId, long expectedRevision,
                                                        String intentHash) {
        return createStarted(action, actorClientId, target, requestId, mutationId, null, expectedRevision, intentHash);
    }

    public synchronized <T> StartedJob<T> createStarted(String action, String actorClientId, String target,
                                                        String requestId, String mutationId, String operationId,
                                                        long expectedRevision, String intentHash) {
        Creation<T> creation = createLocked(action, actorClientId, target, requestId, mutationId, operationId,
            expectedRevision, intentHash);
        if (!creation.created()) {
            if (creation.disposition() == StartDisposition.IDENTITY_CONFLICT) {
                return new StartedJob<>(creation.job(), null, StartDisposition.IDENTITY_CONFLICT);
            }
            JobStatus status = creation.job().getStatus();
            StartDisposition disposition = status == JobStatus.PENDING || status == JobStatus.RUNNING
                ? StartDisposition.IN_FLIGHT_DUPLICATE : StartDisposition.TERMINAL_REPLAY;
            return new StartedJob<>(creation.job(), null, disposition);
        }
        JobExecutionLease execution = new JobExecutionLease();
        executions.put(creation.job().getJobId(), execution);
        execution.termination().whenComplete((unused, failure) -> releaseExecutionAfterTermination(creation.job(), execution));
        boolean admitted;
        try {
            admitted = canonicalRegistry.bindAndStart(creation.job().canonicalReference(), execution::requestCancellation,
                execution.termination());
        } catch (RuntimeException | Error failure) {
            terminalizeNotStarted(creation.job(), failure);
            executions.remove(creation.job().getJobId(), execution);
            execution.complete();
            return new StartedJob<>(creation.job(), execution, StartDisposition.NOT_STARTED);
        }
        if (!admitted || !creation.job().recordRunningFromCanonical()) {
            terminalizeNotStarted(creation.job(), new IllegalStateException("Job Was Not Started"));
            executions.remove(creation.job().getJobId(), execution);
            execution.complete();
            return new StartedJob<>(creation.job(), execution, StartDisposition.NOT_STARTED);
        }
        publish(creation.job());
        return new StartedJob<>(creation.job(), execution, StartDisposition.ACQUIRED);
    }

    public JobExecutionLease execution(JobRecord<?> job) {
        return job == null ? null : executions.get(job.getJobId());
    }

    public void setExecutionCancellation(JobRecord<?> job, Runnable cancellation) {
        JobExecutionLease execution = requireExecution(job);
        execution.setCancellation(cancellation);
    }

    public void completeExecution(JobRecord<?> job) {
        if (job == null) {
            return;
        }
        JobExecutionLease execution = executions.get(job.getJobId());
        if (execution != null) {
            execution.complete();
            if (job.canonicalReference().getState() != FlowJobReference.State.CANCELLING) {
                executions.remove(job.getJobId(), execution);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private <T> Creation<T> createLocked(String action, String actorClientId, String target, String requestId,
                                          String mutationId, String operationId, long expectedRevision, String intentHash) {
        requireAdmissionOpen();
        prune();
        String normalizedRequestId = requestId == null || requestId.isBlank() ? null : requestId;
        String normalizedMutationId = mutationId == null || mutationId.isBlank() ? null : mutationId;
        String normalizedOperationId = operationId == null || operationId.isBlank() ? null : operationId;
        String normalizedAction = action == null ? "" : action;
        String normalizedTarget = target == null ? "" : target;
        String normalizedIntentHash = intentHash == null || intentHash.isBlank() ? null : intentHash;
        if (expectedRevision < -1L) {
            throw new IllegalArgumentException("Expected job revision cannot be negative");
        }
        if (normalizedMutationId != null) {
            if (normalizedRequestId == null) {
                throw new IllegalArgumentException("Mutation requests require a request identity");
            }
            if (actorClientId == null || actorClientId.isBlank()) {
                throw new IllegalArgumentException("Mutation requests require an authenticated client identity");
            }
        }
        String requestKey = requestKey(actorClientId, normalizedRequestId);
        if (requestKey != null) {
            JobIdentity identity = new JobIdentity(actorClientId, normalizedRequestId, normalizedMutationId,
                normalizedOperationId, normalizedAction, normalizedTarget, expectedRevision, normalizedIntentHash);
            JobRecord<?> exact = jobsByIdentity.get(identity);
            if (exact != null) {
                return new Creation<>((JobRecord<T>) exact, false, null);
            }
            JobRecord<?> existing = jobsByRequest.get(requestKey);
            if (existing != null) {
                JobIdentity existingIdentity = identitiesByJob.get(existing.getJobId());
                if (identity.equals(existingIdentity)) {
                    return new Creation<>((JobRecord<T>) existing, false, null);
                }
                return new Creation<>((JobRecord<T>) existing, false, StartDisposition.IDENTITY_CONFLICT);
            }
        }
        String effectiveRequestId = normalizedRequestId != null ? normalizedRequestId : null;
        FlowJobReference<T> reference = canonicalRegistry.create(action, actorClientId);
        String effectiveOperationId = normalizedOperationId != null ? normalizedOperationId : reference.getId();
        JobRecord<T> job = new JobRecord<>(effectiveRequestId, effectiveOperationId, action, actorClientId, target,
            reference, canonicalRegistry, normalizedMutationId, expectedRevision, normalizedIntentHash);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("action", action != null ? action : "");
        metadata.put("target", target != null ? target : "");
        metadata.put("actorClientId", actorClientId != null ? actorClientId : "unknown");
        if (effectiveRequestId != null) {
            metadata.put("requestId", effectiveRequestId);
        }
        if (normalizedMutationId != null) {
            metadata.put("mutationId", normalizedMutationId);
        }
        metadata.put("operationId", effectiveOperationId);
        metadata.put("expectedRevision", expectedRevision);
        if (normalizedIntentHash != null) {
            metadata.put("intentHash", normalizedIntentHash);
        }
        canonicalRegistry.update(reference, 0.0, metadata);
        jobs.put(job.getJobId(), job);
        if (requestKey != null) {
            JobIdentity identity = new JobIdentity(actorClientId, normalizedRequestId, normalizedMutationId,
                normalizedOperationId, normalizedAction, normalizedTarget, expectedRevision, normalizedIntentHash);
            jobsByRequest.put(requestKey, job);
            jobsByIdentity.put(identity, job);
            identitiesByJob.put(job.getJobId(), identity);
            if (normalizedMutationId != null) {
                mutationIdsByRequest.put(requestKey, normalizedMutationId);
            }
        }
        publish(job);
        return new Creation<>(job, true, null);
    }

    public JobRecord<?> get(String jobId) {
        return jobs.get(jobId);
    }

    public List<Map<String, Object>> snapshot(String actorClientId) {
        prune();
        return jobs.values().stream()
            .filter(job -> actorClientId == null || actorClientId.isBlank() || actorClientId.equals(job.getActorClientId()))
            .sorted((left, right) -> Long.compare(right.getSubmittedAt(), left.getSubmittedAt()))
            .map(JobRecord::snapshot)
            .toList();
    }

    public List<Map<String, Object>> activeOrRecentSnapshot(String actorClientId, long recentWindowMs) {
        prune();
        long cutoff = System.currentTimeMillis() - Math.max(0, recentWindowMs);
        return jobs.values().stream()
            .filter(job -> actorClientId == null || actorClientId.isBlank() || actorClientId.equals(job.getActorClientId()))
            .filter(job -> !job.getStatus().terminal() || job.getFinishedAt() >= cutoff)
            .sorted((left, right) -> Long.compare(right.getSubmittedAt(), left.getSubmittedAt()))
            .map(JobRecord::snapshot)
            .toList();
    }

    public void publish(JobRecord<?> job) {
        if (job == null) {
            return;
        }
        if (!job.canonicalTerminal()) {
            notifyListener(job);
            return;
        }
        String jobId = job.getJobId();
        synchronized (job) {
            if (!terminalPublicationInProgress.add(jobId)) {
                return;
            }
            if (terminalPublications.contains(jobId)) {
                terminalPublicationInProgress.remove(jobId);
                return;
            }
        }
        boolean finalized = false;
        try {
            job.synchronizeCanonical();
            notifyListener(job);
            finalized = true;
        } finally {
            synchronized (job) {
                if (finalized) {
                    terminalPublications.add(jobId);
                }
                terminalPublicationInProgress.remove(jobId);
            }
        }
    }

    private void notifyListener(JobRecord<?> job) {
        if (listener != null) {
            try {
                listener.accept(job);
            } catch (RuntimeException ignored) {
            }
        }
    }

    public String operationId(JobRecord<?> job) {
        return job == null ? null : job.getOperationId();
    }

    public void bind(FlowJobReference<?> reference, Runnable cancellation, CompletionStage<?> termination) {
        canonicalRegistry.bind(reference, cancellation, termination);
    }

    public void bind(JobRecord<?> job, Runnable cancellation, CompletionStage<?> termination) {
        Objects.requireNonNull(job, "job");
        bind(job.canonicalReference(), cancellation, termination);
    }

    public boolean launch(FlowJobReference<?> reference, Runnable starter) {
        return canonicalRegistry.launch(reference, starter);
    }

    public boolean launch(JobRecord<?> job, Runnable starter) {
        Objects.requireNonNull(job, "job");
        return launch(job.canonicalReference(), starter);
    }

    public void closeAdmission() {
        canonicalRegistry.closeAdmission();
    }

    public void resumeAdmission() {
        canonicalRegistry.resumeAdmission();
    }

    public CompletableFuture<Void> quiesce() {
        return canonicalRegistry.quiesce();
    }

    public CompletableFuture<Void> quiesce(Duration timeout) {
        return canonicalRegistry.quiesce(timeout);
    }

    public CompletableFuture<Void> drainAndCancel() {
        return canonicalRegistry.drainAndCancel();
    }

    public CompletableFuture<Void> drainAndCancel(Duration timeout) {
        return canonicalRegistry.drainAndCancel(timeout);
    }

    public CompletableFuture<Void> prepareSnapshot() {
        return canonicalRegistry.prepareSnapshot();
    }

    public CompletableFuture<Void> prepareRestore() {
        return canonicalRegistry.prepareRestore();
    }

    public void resume() {
        canonicalRegistry.resume();
    }

    public void resumeAfterSnapshot() {
        canonicalRegistry.resumeAfterSnapshot();
    }

    public void resumeAfterRestore() {
        canonicalRegistry.resumeAfterRestore();
    }

    public CompletableFuture<Void> awaitIdle() {
        return canonicalRegistry.awaitIdle();
    }

    public boolean awaitIdle(Duration timeout) {
        if (timeout == null || timeout.isNegative()) {
            throw new IllegalArgumentException("Job Idle Timeout Must Be Non-Negative");
        }
        try {
            awaitIdle().get(timeout.toNanos(), TimeUnit.NANOSECONDS);
            return true;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        } catch (TimeoutException | ExecutionException exception) {
            return false;
        }
    }

    public FlowJobRegistry.State state() {
        return canonicalRegistry.state();
    }

    public boolean admissionsOpen() {
        return canonicalRegistry.admissionsOpen();
    }

    public int activeJobCount() {
        return canonicalRegistry.activeJobCount();
    }

    public int physicalTaskCount() {
        return canonicalRegistry.physicalTaskCount();
    }

    public FlowJobRegistry.Health health() {
        return canonicalRegistry.health();
    }

    public void healthCheck() {
        canonicalRegistry.healthCheck();
    }

    public void shutdown() {
        try {
            shutdownAsync().join();
        } catch (RuntimeException ignored) {
        }
    }

    public CompletableFuture<Void> shutdownAsync() {
        CompletableFuture<Void> shutdown = canonicalRegistry.shutdownAsync();
        shutdown.whenComplete((unused, failure) -> {
            if (failure != null) {
                return;
            }
            canonicalRegistry.removeListener(canonicalListener);
            jobs.clear();
            jobsByRequest.clear();
            jobsByIdentity.clear();
            identitiesByJob.clear();
            mutationIdsByRequest.clear();
            terminalPublications.clear();
            terminalPublicationInProgress.clear();
            executions.clear();
        });
        return shutdown;
    }

    private JobExecutionLease requireExecution(JobRecord<?> job) {
        Objects.requireNonNull(job, "Job Is Required");
        JobExecutionLease execution = executions.get(job.getJobId());
        if (execution == null) {
            throw new IllegalStateException("Job Execution Lease Is Missing: " + job.getJobId());
        }
        return execution;
    }

    private void releaseExecutionAfterTermination(JobRecord<?> job, JobExecutionLease execution) {
        if (job.canonicalReference().getState() == FlowJobReference.State.CANCELLING) {
            job.canonicalReference().getCompletion().whenComplete((unused, failure) ->
                executions.remove(job.getJobId(), execution));
            return;
        }
        executions.remove(job.getJobId(), execution);
    }

    private String requestKey(String actorClientId, String requestId) {
        if (requestId == null || requestId.isBlank()) {
            return null;
        }
        String actor = actorClientId == null || actorClientId.isBlank() ? "unknown" : actorClientId;
        return actor + '\n' + requestId;
    }

    private synchronized void prune() {
        long cutoff = System.currentTimeMillis() - TERMINAL_RETENTION_MS;
        for (JobRecord<?> job : jobs.values()) {
            synchronized (job) {
                if (!terminalPruneReadyLocked(job) || job.getFinishedAt() >= cutoff) {
                    continue;
                }
                if (jobs.remove(job.getJobId(), job)) {
                    removeIndexes(job);
                    identitiesByJob.remove(job.getJobId());
                }
            }
        }
        int overflow = jobs.size() - MAX_RETAINED;
        if (overflow <= 0) {
            return;
        }
        List<JobRecord<?>> removable = jobs.values().stream()
            .filter(this::terminalPruneReady)
            .sorted((left, right) -> Long.compare(left.getSubmittedAt(), right.getSubmittedAt()))
            .limit(overflow)
            .toList();
        for (JobRecord<?> job : removable) {
            synchronized (job) {
                if (!terminalPruneReadyLocked(job)) {
                    continue;
                }
                if (jobs.remove(job.getJobId(), job)) {
                    removeIndexes(job);
                    identitiesByJob.remove(job.getJobId());
                }
            }
        }
    }

    private boolean terminalPruneReady(JobRecord<?> job) {
        synchronized (job) {
            return terminalPruneReadyLocked(job);
        }
    }

    private boolean terminalPruneReadyLocked(JobRecord<?> job) {
        return job.terminalPruneReady(terminalPublications.contains(job.getJobId()),
            terminalPublicationInProgress.contains(job.getJobId()));
    }

    private void removeIndexes(JobRecord<?> job) {
        synchronized (job) {
            String requestKey = requestKey(job.getActorClientId(), job.getRequestId());
            if (requestKey != null) {
                jobsByRequest.remove(requestKey, job);
                if (job.getMutationId() != null) {
                    mutationIdsByRequest.remove(requestKey, job.getMutationId());
                }
            }
            JobIdentity identity = identitiesByJob.remove(job.getJobId());
            if (identity != null) {
                jobsByIdentity.remove(identity, job);
            }
            terminalPublicationInProgress.remove(job.getJobId());
            terminalPublications.remove(job.getJobId());
        }
    }

    private void requireAdmissionOpen() {
        if (!canonicalRegistry.admissionsOpen()) {
            throw new IllegalStateException("Job Admission Is " + canonicalRegistry.state().name());
        }
    }

    private void terminalizeNotStarted(JobRecord<?> job, Throwable failure) {
        String message = failure == null || failure.getMessage() == null || failure.getMessage().isBlank()
            ? "Job Was Not Started" : failure.getMessage();
        Throwable terminalFailure = failure instanceof Exception ? failure : new IllegalStateException(message, failure);
        boolean markedFailed = false;
        try {
            markedFailed = job.markFailed(message, terminalFailure);
        } catch (RuntimeException ignored) {
        }
        if (markedFailed) {
            publish(job);
            return;
        }
        boolean retired = false;
        try {
            retired = canonicalRegistry.retireNotStarted(job.canonicalReference(), "JOB_NOT_STARTED", message,
                Map.of("error", message));
        } catch (RuntimeException ignored) {
        }
        if (retired) {
            publish(job);
            jobs.remove(job.getJobId(), job);
            removeIndexes(job);
            return;
        }
        if (!job.getStatus().terminal() && canonicalRegistry.get(job.getJobId()) == null) {
            jobs.remove(job.getJobId(), job);
            removeIndexes(job);
        }
    }

    private void publishCanonicalTerminal(FlowJobReference.Snapshot<?> snapshot) {
        if (snapshot == null || !terminal(snapshot.state())) {
            return;
        }
        JobRecord<?> job = jobs.get(snapshot.id());
        if (job != null) {
            job.synchronizeCanonical();
            publish(job);
        }
    }

    private boolean terminal(FlowJobReference.State state) {
        return state == FlowJobReference.State.SUCCEEDED || state == FlowJobReference.State.FAILED
            || state == FlowJobReference.State.CANCELLED;
    }

    private record Creation<T>(JobRecord<T> job, boolean created, StartDisposition disposition) {
    }

    public record JobIdentity(String actorClientId, String requestId, String mutationId, String operationId,
                              String action, String target, long expectedRevision, String intentHash) {
        public JobIdentity {
            actorClientId = actorClientId == null || actorClientId.isBlank() ? "unknown" : actorClientId;
            requestId = requestId == null || requestId.isBlank() ? null : requestId;
            mutationId = mutationId == null || mutationId.isBlank() ? null : mutationId;
            operationId = operationId == null || operationId.isBlank() ? null : operationId;
            action = action == null ? "" : action;
            target = target == null ? "" : target;
            intentHash = intentHash == null || intentHash.isBlank() ? null : intentHash;
        }
    }

    public record StartedJob<T>(JobRecord<T> job, JobExecutionLease execution, StartDisposition disposition) {
        public boolean started() {
            return disposition == StartDisposition.ACQUIRED;
        }

        public boolean inFlightDuplicate() {
            return disposition == StartDisposition.IN_FLIGHT_DUPLICATE;
        }

        public boolean terminalReplay() {
            return disposition == StartDisposition.TERMINAL_REPLAY;
        }

        public boolean identityConflict() {
            return disposition == StartDisposition.IDENTITY_CONFLICT;
        }
    }

    public enum StartDisposition {
        ACQUIRED,
        IN_FLIGHT_DUPLICATE,
        TERMINAL_REPLAY,
        IDENTITY_CONFLICT,
        NOT_STARTED
    }
}
