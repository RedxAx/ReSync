package restudio.resync.flow.jobs;

import restudio.flow.data.FlowJobReference;
import restudio.resync.Log;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicBoolean;

public final class FlowJobRegistry {
    private static final int DEFAULT_MAX_RETAINED = 1024;
    private static final Duration DEFAULT_TERMINAL_RETENTION = Duration.ofMinutes(15);
    public static final Duration DEFAULT_DRAIN_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration DEFAULT_CANCELLATION_TIMEOUT = Duration.ofSeconds(1);
    private final Map<String, FlowJobReference<?>> jobs = new ConcurrentHashMap<>();
    private final Map<String, Instant> terminalTimes = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<Consumer<FlowJobReference.Snapshot<?>>> listeners = new CopyOnWriteArrayList<>();
    private final Object lifecycleMonitor = new Object();
    private final Map<String, String> lifecycleFailures = new LinkedHashMap<>();
    private final Map<String, PhysicalTask> physicalTasks = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<Boolean>> pendingCancellations = new ConcurrentHashMap<>();
    private final ExecutorService cancellationExecutor = Executors.newSingleThreadExecutor(
        Thread.ofVirtual().name("resync-flow-job-cancel-", 0).factory());
    private final AtomicBoolean cancellationExecutorClosed = new AtomicBoolean();
    private final int maxRetained;
    private final Duration terminalRetention;
    private final Duration drainTimeout;
    private State state = State.OPEN;
    private long generation;
    private CompletableFuture<Void> idle = CompletableFuture.completedFuture(null);
    private CompletableFuture<Void> lifecycleDrain = CompletableFuture.completedFuture(null);
    private CompletableFuture<Void> cancellationTail = CompletableFuture.completedFuture(null);
    private int pendingCancellationActions;

    public FlowJobRegistry() {
        this(DEFAULT_MAX_RETAINED, DEFAULT_TERMINAL_RETENTION, DEFAULT_DRAIN_TIMEOUT);
    }

    public FlowJobRegistry(int maxRetained, Duration terminalRetention) {
        this(maxRetained, terminalRetention, DEFAULT_DRAIN_TIMEOUT);
    }

    public FlowJobRegistry(int maxRetained, Duration terminalRetention, Duration drainTimeout) {
        this.maxRetained = Math.clamp(maxRetained, 16, 65_536);
        this.terminalRetention = terminalRetention != null && !terminalRetention.isNegative() ? terminalRetention : DEFAULT_TERMINAL_RETENTION;
        this.drainTimeout = drainTimeout != null && !drainTimeout.isNegative() ? drainTimeout : DEFAULT_DRAIN_TIMEOUT;
    }

    public <T> FlowJobReference<T> create(String kind, String owner) {
        FlowJobReference<T> reference;
        synchronized (lifecycleMonitor) {
            requireAdmissionOpen();
            pruneLocked();
            reference = new FlowJobReference<>(UUID.randomUUID().toString(), kind, owner);
            jobs.put(reference.getId(), reference);
            if (idle.isDone()) {
                idle = new CompletableFuture<>();
            }
            reference.getCompletion().whenComplete((outcome, failure) -> complete(reference));
        }
        publish(reference);
        prune();
        return reference;
    }

    public void bind(FlowJobReference<?> reference, Runnable cancellation, CompletionStage<?> termination) {
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(cancellation, "cancellation");
        Objects.requireNonNull(termination, "termination");
        CompletableFuture<?> completion = termination.toCompletableFuture();
        PhysicalTask task = new PhysicalTask(reference, cancellation, completion);
        boolean cancel;
        synchronized (lifecycleMonitor) {
            requireRegisteredLocked(reference);
            FlowJobReference.State referenceState = reference.getState();
            boolean lateCancellation = reference.isCancellationRequested()
                && (referenceState == FlowJobReference.State.CANCELLING || referenceState == FlowJobReference.State.CANCELLED);
            if (state == State.QUIESCED || state == State.CLOSED || (state == State.FAILED && !lateCancellation)) {
                throw new IllegalStateException("Flow Job Physical Binding Is Not Accepted In " + state.name());
            }
            if (terminal(referenceState) && !(referenceState == FlowJobReference.State.CANCELLED && lateCancellation)) {
                throw new IllegalStateException("Flow Job Physical Binding Is Not Accepted After Terminalization: "
                    + reference.getId());
            }
            if (physicalTasks.putIfAbsent(reference.getId(), task) != null) {
                throw new IllegalStateException("Flow Job Physical Task Is Already Bound: " + reference.getId());
            }
            if (!completion.isDone() && idle.isDone()) {
                idle = new CompletableFuture<>();
            }
            cancel = state != State.OPEN || reference.isCancellationRequested()
                || referenceState == FlowJobReference.State.CANCELLING;
        }
        completion.whenComplete((unused, failure) -> physicalTerminated(task, failure));
        if (cancel) {
            requestPhysicalCancellation(task, "late physical task binding");
        }
    }

    public boolean bindAndStart(FlowJobReference<?> reference, Runnable cancellation, CompletionStage<?> termination) {
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(cancellation, "cancellation");
        Objects.requireNonNull(termination, "termination");
        CompletableFuture<?> completion = termination.toCompletableFuture();
        PhysicalTask task = new PhysicalTask(reference, cancellation, completion);
        synchronized (lifecycleMonitor) {
            requireRegisteredLocked(reference);
            if (!admissionOpenLocked() || reference.isCancellationRequested()
                || reference.getState() != FlowJobReference.State.PENDING) {
                return false;
            }
            if (physicalTasks.putIfAbsent(reference.getId(), task) != null) {
                throw new IllegalStateException("Flow Job Physical Task Is Already Bound: " + reference.getId());
            }
            if (!completion.isDone() && idle.isDone()) {
                idle = new CompletableFuture<>();
            }
            reference.start();
        }
        completion.whenComplete((unused, failure) -> physicalTerminated(task, failure));
        return true;
    }

    public boolean launch(FlowJobReference<?> reference, Runnable starter) {
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(starter, "starter");
        synchronized (lifecycleMonitor) {
            if (jobs.get(reference.getId()) != reference) {
                return false;
            }
            if (!admissionOpenLocked() || reference.isCancellationRequested()) {
                PhysicalTask physical = physicalTasks.get(reference.getId());
                if (physical != null) {
                    requestPhysicalCancellation(physical, "launch rejected");
                }
                return false;
            }
            starter.run();
            return true;
        }
    }

    public FlowJobReference<?> get(String jobId) {
        return jobId == null ? null : jobs.get(jobId);
    }

    public List<FlowJobReference.Snapshot<?>> snapshots(String owner) {
        prune();
        List<FlowJobReference.Snapshot<?>> snapshots = new ArrayList<>();
        for (FlowJobReference<?> reference : jobs.values()) {
            if (owner == null || owner.isBlank() || owner.equals(reference.getOwner())) {
                snapshots.add(reference.snapshot());
            }
        }
        snapshots.sort(Comparator.comparing(FlowJobReference.Snapshot<?>::createdAt).reversed());
        return List.copyOf(snapshots);
    }

    public void start(FlowJobReference<?> reference) {
        if (reference == null) {
            return;
        }
        synchronized (lifecycleMonitor) {
            if (!admissionOpenLocked()) {
                requireAdmissionOpen();
            }
            if (reference.getState() != FlowJobReference.State.PENDING) {
                return;
            }
            reference.start();
        }
        publish(reference);
    }

    public void update(FlowJobReference<?> reference, double progress, Map<String, Object> metadata) {
        if (reference == null) {
            return;
        }
        synchronized (lifecycleMonitor) {
            if (!admissionOpenLocked()) {
                return;
            }
            reference.updateProgress(progress, metadata);
        }
        publish(reference);
    }

    public <T> boolean succeed(FlowJobReference<T> reference, T value) {
        if (reference == null) {
            return false;
        }
        synchronized (lifecycleMonitor) {
            if (!admissionOpenLocked()) {
                return false;
            }
            return reference.succeed(value);
        }
    }

    public boolean fail(FlowJobReference<?> reference, String code, String message, Map<String, Object> details) {
        if (reference == null) {
            return false;
        }
        synchronized (lifecycleMonitor) {
            if (!admissionOpenLocked()) {
                return false;
            }
            return reference.fail(code, message, details);
        }
    }

    public boolean retireNotStarted(FlowJobReference<?> reference, String code, String message,
                                    Map<String, Object> details) {
        if (reference == null) {
            return false;
        }
        synchronized (lifecycleMonitor) {
            if (jobs.get(reference.getId()) != reference || reference.getState() != FlowJobReference.State.PENDING
                || physicalTasks.containsKey(reference.getId())) {
                return false;
            }
            if (!reference.fail(code, message, details)) {
                return false;
            }
            jobs.remove(reference.getId(), reference);
            terminalTimes.remove(reference.getId());
        }
        completeLifecycleIfIdle();
        return true;
    }

    public boolean cancel(FlowJobReference<?> reference) {
        if (reference == null) {
            return false;
        }
        synchronized (lifecycleMonitor) {
            if (state == State.CLOSED) {
                return false;
            }
        }
        return cancelReference(reference, "job cancellation");
    }

    public boolean cancel(String jobId) {
        return cancel(get(jobId));
    }

    public boolean completeCancellationAfterPhysical(FlowJobReference<?> reference) {
        if (reference == null) {
            return false;
        }
        synchronized (lifecycleMonitor) {
            if (jobs.get(reference.getId()) != reference || physicalTasks.containsKey(reference.getId())
                || reference.getState() != FlowJobReference.State.CANCELLING) {
                return false;
            }
            return reference.completeCancellation();
        }
    }

    public void addListener(Consumer<FlowJobReference.Snapshot<?>> listener) {
        if (listener != null) {
            listeners.addIfAbsent(listener);
        }
    }

    public void removeListener(Consumer<FlowJobReference.Snapshot<?>> listener) {
        listeners.remove(listener);
    }

    public void closeAdmission() {
        synchronized (lifecycleMonitor) {
            if (state == State.CLOSED || state == State.ADMISSION_CLOSED || state == State.QUIESCED || state == State.QUIESCING) {
                return;
            }
            if (state == State.FAILED) {
                throw new IllegalStateException("Flow Job Lifecycle Is Failed");
            }
            state = State.ADMISSION_CLOSED;
            generation++;
        }
    }

    public void resumeAdmission() {
        synchronized (lifecycleMonitor) {
            if (state == State.OPEN) {
                return;
            }
            if (state == State.QUIESCED) {
                if (!lifecycleFailures.isEmpty() || activeJobCountLocked() > 0 || lifecycleWorkCountLocked() > 0) {
                    throw new IllegalStateException("Flow Job Lifecycle Is Not Safe To Resume");
                }
                state = State.OPEN;
                generation++;
                return;
            }
            if (state != State.ADMISSION_CLOSED) {
                throw new IllegalStateException("Flow Job Admission Cannot Resume From " + state.name());
            }
            state = State.OPEN;
            generation++;
        }
    }

    public CompletableFuture<Void> quiesce() {
        return quiesce(drainTimeout);
    }

    public CompletableFuture<Void> quiesce(Duration timeout) {
        Duration wait = requireTimeout(timeout);
        List<FlowJobReference<?>> active;
        List<PhysicalTask> physical;
        CompletableFuture<Void> drain;
        synchronized (lifecycleMonitor) {
            if (state == State.CLOSED) {
                return CompletableFuture.completedFuture(null);
            }
            if (state == State.FAILED) {
                return failedDrain();
            }
            if (state == State.QUIESCED || state == State.QUIESCING) {
                return lifecycleDrain;
            }
            state = State.QUIESCING;
            generation++;
            lifecycleDrain = new CompletableFuture<>();
            drain = lifecycleDrain;
            active = activeJobsLocked();
            physical = physicalTasks.values().stream()
                .sorted(Comparator.comparing((PhysicalTask task) -> task.reference().getCreatedAt()).thenComparing(task -> task.reference().getId()))
                .toList();
        }
        for (FlowJobReference<?> reference : active) {
            cancelReference(reference, "lifecycle quiesce");
        }
        for (PhysicalTask task : physical) {
            requestPhysicalCancellation(task, "lifecycle quiesce");
        }
        scheduleDrainTimeout(drain, timeout, "lifecycle quiesce");
        completeLifecycleIfIdle();
        return drain;
    }

    public CompletableFuture<Void> drainAndCancel() {
        return quiesce();
    }

    public CompletableFuture<Void> drainAndCancel(Duration timeout) {
        return quiesce(timeout);
    }

    public CompletableFuture<Void> prepareSnapshot() {
        return quiesce();
    }

    public CompletableFuture<Void> prepareRestore() {
        return quiesce();
    }

    public void resume() {
        synchronized (lifecycleMonitor) {
            if (state == State.OPEN) {
                return;
            }
            if (state != State.QUIESCED) {
                throw new IllegalStateException("Flow Job Lifecycle Cannot Resume From " + state.name());
            }
            if (!lifecycleFailures.isEmpty() || activeJobCountLocked() > 0 || lifecycleWorkCountLocked() > 0) {
                throw new IllegalStateException("Flow Job Lifecycle Is Not Safe To Resume");
            }
            state = State.OPEN;
            generation++;
        }
    }

    public void resumeAfterSnapshot() {
        resume();
    }

    public void resumeAfterRestore() {
        resume();
    }

    public CompletableFuture<Void> awaitIdle() {
        synchronized (lifecycleMonitor) {
            return idle;
        }
    }

    public boolean awaitIdle(Duration timeout) {
        if (timeout == null || timeout.isNegative()) {
            throw new IllegalArgumentException("Flow Job Idle Timeout Must Be Non-Negative");
        }
        try {
            awaitIdle().get(timeout.toNanos(), TimeUnit.NANOSECONDS);
            return true;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        } catch (TimeoutException exception) {
            return false;
        } catch (ExecutionException exception) {
            return false;
        }
    }

    public State state() {
        synchronized (lifecycleMonitor) {
            return state;
        }
    }

    public boolean admissionsOpen() {
        synchronized (lifecycleMonitor) {
            return admissionOpenLocked();
        }
    }

    public boolean admissionOpen() {
        return admissionsOpen();
    }

    public int activeJobCount() {
        synchronized (lifecycleMonitor) {
            return activeJobCountLocked();
        }
    }

    public int physicalTaskCount() {
        synchronized (lifecycleMonitor) {
            return physicalTaskCountLocked();
        }
    }

    public long generation() {
        synchronized (lifecycleMonitor) {
            return generation;
        }
    }

    public Health health() {
        synchronized (lifecycleMonitor) {
            State current = state;
            int active = activeJobCountLocked();
            int physical = physicalTaskCountLocked();
            Map<String, String> failures = Map.copyOf(lifecycleFailures);
            boolean available = (current == State.OPEN || current == State.QUIESCED) && failures.isEmpty()
                && (current == State.OPEN || active == 0 && physical == 0);
            return new Health(available, current, active, physical, generation, failures);
        }
    }

    public void healthCheck() {
        Health current = health();
        if (!current.failures().isEmpty()) {
            throw new IllegalStateException("Flow Job Health Check Failed: " + current.failures());
        }
        if (current.state() == State.FAILED) {
            throw new IllegalStateException("Flow Job Lifecycle Is Failed");
        }
        if ((current.state() == State.QUIESCING || current.state() == State.QUIESCED)
            && (current.activeJobs() > 0 || current.physicalTasks() > 0 || pendingCancellationCount() > 0)) {
            throw new IllegalStateException("Flow Job Lifecycle Still Has Active Work");
        }
    }

    public void shutdown() {
        try {
            shutdownAsync().join();
        } catch (RuntimeException ignored) {
        }
    }

    public CompletableFuture<Void> shutdownAsync() {
        List<FlowJobReference<?>> active;
        List<PhysicalTask> physical;
        CompletableFuture<Void> drain;
        synchronized (lifecycleMonitor) {
            if (state == State.CLOSED) {
                return lifecycleDrain;
            }
            if (state == State.QUIESCING) {
                state = State.CLOSED;
                generation++;
                drain = lifecycleDrain;
            } else {
                state = State.CLOSED;
                generation++;
                lifecycleDrain = new CompletableFuture<>();
                drain = lifecycleDrain;
            }
            active = activeJobsLocked();
            physical = physicalTasks.values().stream()
                .sorted(Comparator.comparing((PhysicalTask task) -> task.reference().getCreatedAt()).thenComparing(task -> task.reference().getId()))
                .toList();
        }
        for (FlowJobReference<?> reference : active) {
            cancelReference(reference, "shutdown");
        }
        for (PhysicalTask task : physical) {
            requestPhysicalCancellation(task, "shutdown");
        }
        scheduleDrainTimeout(drain, drainTimeout, "shutdown");
        completeLifecycleIfIdle();
        drain.whenComplete((unused, failure) -> listeners.clear());
        return drain;
    }

    private void publish(FlowJobReference<?> reference) {
        if (reference == null) {
            return;
        }
        FlowJobReference.Snapshot<?> snapshot = reference.snapshot();
        for (Consumer<FlowJobReference.Snapshot<?>> listener : listeners) {
            try {
                listener.accept(snapshot);
            } catch (RuntimeException failure) {
                Log.warn("Flow job listener failed: " + failure.getMessage());
            }
        }
    }

    private void complete(FlowJobReference<?> reference) {
        synchronized (lifecycleMonitor) {
            terminalTimes.putIfAbsent(reference.getId(), Instant.now());
        }
        publish(reference);
        completeLifecycleIfIdle();
    }

    private boolean cancelReference(FlowJobReference<?> reference, String phase) {
        PhysicalTask physical;
        CompletableFuture<Boolean> cancellation;
        boolean schedule;
        boolean accepted;
        synchronized (lifecycleMonitor) {
            if (jobs.get(reference.getId()) != reference) {
                return false;
            }
            physical = physicalTasks.get(reference.getId());
            if (!active(reference) && physical == null) {
                return false;
            }
            cancellation = pendingCancellations.get(reference.getId());
            schedule = cancellation == null && active(reference);
            if (schedule) {
                if (reference.getState() != FlowJobReference.State.CANCELLING && !reference.requestCancellation()) {
                    return false;
                }
                cancellation = new CompletableFuture<>();
                pendingCancellations.put(reference.getId(), cancellation);
            }
            accepted = schedule || reference.isCancellationRequested() || physical != null;
        }
        if (schedule) {
            CompletableFuture<Boolean> requested = cancellation;
            submitCancellation(reference::runCancellationAction).whenComplete((unused, failure) -> {
                try {
                    if (failure != null) {
                        recordFailure(reference.getId(), phase + ": " + reason(failure));
                    }
                    synchronized (lifecycleMonitor) {
                        if (!physicalTasks.containsKey(reference.getId())) {
                            completeCancellationIfReadyLocked(reference, phase, failure);
                        }
                    }
                    if (failure != null) {
                        requested.completeExceptionally(failure);
                    } else {
                        requested.complete(true);
                    }
                    pendingCancellations.remove(reference.getId(), requested);
                } finally {
                    completeCancellationAction();
                }
            });
            scheduleCancellationTimeout(reference.getId(), requested, phase);
        }
        if (physical != null) {
            requestPhysicalCancellation(physical, phase);
        }
        return accepted;
    }

    private void completeLifecycleIfIdle() {
        CompletableFuture<Void> idleFuture = null;
        CompletableFuture<Void> lifecycleFuture = null;
        Throwable lifecycleFailure = null;
        boolean closeExecutor = false;
        synchronized (lifecycleMonitor) {
            if (lifecycleWorkCountLocked() > 0) {
                return;
            }
            if (!idle.isDone()) {
                idleFuture = idle;
            }
            if (state == State.QUIESCING || state == State.FAILED) {
                if (lifecycleFailures.isEmpty()) {
                    state = State.QUIESCED;
                } else {
                    state = State.FAILED;
                    lifecycleFailure = new IllegalStateException("Flow Job Lifecycle Cancellation Failed: " + lifecycleFailures);
                    closeExecutor = true;
                }
                lifecycleFuture = lifecycleDrain;
            } else if (state == State.CLOSED) {
                lifecycleFuture = lifecycleDrain;
                jobs.clear();
                terminalTimes.clear();
                closeExecutor = true;
            }
        }
        if (closeExecutor) {
            closeCancellationExecutor(false);
        }
        if (idleFuture != null) {
            idleFuture.complete(null);
        }
        if (lifecycleFuture != null) {
            if (lifecycleFailure == null) {
                lifecycleFuture.complete(null);
            } else {
                lifecycleFuture.completeExceptionally(lifecycleFailure);
            }
        }
    }

    private void physicalTerminated(PhysicalTask task, Throwable failure) {
        synchronized (lifecycleMonitor) {
            if (!physicalTasks.remove(task.reference().getId(), task)) {
                return;
            }
            if (failure != null) {
                String diagnostic = "physical termination: " + reason(failure);
                lifecycleFailures.put(task.reference().getId(), diagnostic);
                if (state != State.CLOSED) {
                    state = State.FAILED;
                }
                terminalizePhysicalFailureLocked(task.reference(), failure);
            }
            if (failure == null && task.reference().getState() == FlowJobReference.State.CANCELLING
                && !physicalTasks.containsKey(task.reference().getId())) {
                completeCancellationIfReadyLocked(task.reference(), "physical termination", null);
            }
        }
        completeLifecycleIfIdle();
    }

    private void completeCancellationIfReadyLocked(FlowJobReference<?> reference, String phase, Throwable failure) {
        if (physicalTasks.containsKey(reference.getId()) || reference.getState() != FlowJobReference.State.CANCELLING) {
            return;
        }
        if (failure == null) {
            reference.completeCancellation();
            return;
        }
        String diagnostic = reason(failure);
        reference.completeCancellation("Job Cancelled: " + phase + " failed", Map.of("error", diagnostic));
    }

    private void terminalizePhysicalFailureLocked(FlowJobReference<?> reference, Throwable failure) {
        String diagnostic = reason(failure);
        if (reference.getState() == FlowJobReference.State.CANCELLING) {
            completeCancellationIfReadyLocked(reference, "physical termination", failure);
            return;
        }
        if (active(reference)) {
            reference.fail("JOB_FAILED", "Physical Job Termination Failed",
                Map.of("error", diagnostic, "jobId", reference.getId()));
        }
    }

    private void requestPhysicalCancellation(PhysicalTask task, String phase) {
        synchronized (lifecycleMonitor) {
            if (physicalTasks.get(task.reference().getId()) != task
                || !task.cancellationRequested().compareAndSet(false, true)) {
                return;
            }
        }
        submitCancellation(task.cancellation()).whenComplete((unused, failure) -> {
            try {
                if (failure != null) {
                    recordFailure(task.reference().getId(), phase + ": " + reason(failure));
                    task.cancellationCompletion().completeExceptionally(failure);
                } else {
                    task.cancellationCompletion().complete(null);
                }
            } finally {
                completeCancellationAction();
            }
        });
        scheduleCancellationTimeout(task.reference().getId(), task.cancellationCompletion(), phase);
    }

    private void recordFailure(String id, String failure) {
        synchronized (lifecycleMonitor) {
            lifecycleFailures.put(id, failure);
            if (state != State.CLOSED) {
                state = State.FAILED;
            }
        }
    }

    private CompletableFuture<Void> submitCancellation(Runnable action) {
        synchronized (lifecycleMonitor) {
            pendingCancellationActions++;
            if (cancellationExecutorClosed.get()) {
                CompletableFuture<Void> failed = new CompletableFuture<>();
                failed.completeExceptionally(new IllegalStateException("Flow Job Cancellation Executor Is Closed"));
                return failed;
            }
            CompletableFuture<Void> previous = cancellationTail;
            try {
                CompletableFuture<Void> submitted = previous.handle((unused, failure) -> null)
                    .thenRunAsync(action, cancellationExecutor);
                cancellationTail = submitted.handle((unused, failure) -> null);
                return submitted;
            } catch (RuntimeException failure) {
                return CompletableFuture.failedFuture(failure);
            }
        }
    }

    private void completeCancellationAction() {
        synchronized (lifecycleMonitor) {
            pendingCancellationActions--;
        }
        completeLifecycleIfIdle();
    }

    private void closeCancellationExecutor(boolean interrupt) {
        if (!cancellationExecutorClosed.compareAndSet(false, true)) {
            return;
        }
        if (interrupt) {
            cancellationExecutor.shutdownNow();
        } else {
            cancellationExecutor.shutdown();
        }
    }

    private void scheduleCancellationTimeout(String id, CompletableFuture<?> cancellation, String phase) {
        CompletableFuture.delayedExecutor(DEFAULT_CANCELLATION_TIMEOUT.toNanos(), TimeUnit.NANOSECONDS).execute(() -> {
            if (!cancellation.isDone()) {
                recordFailure(id, phase + " cancellation timed out");
                completeLifecycleIfIdle();
            }
        });
    }

    private void scheduleDrainTimeout(CompletableFuture<Void> drain, Duration timeout, String phase) {
        CompletableFuture.delayedExecutor(timeout.toNanos(), TimeUnit.NANOSECONDS).execute(() -> {
            if (drain.isDone()) {
                return;
            }
            IllegalStateException failure;
            synchronized (lifecycleMonitor) {
                String reason = phase + " timed out with " + physicalTaskCountLocked() + " physical tasks, "
                    + pendingCancellations.size() + " cancellation callbacks, " + pendingCancellationActions
                    + " cancellation actions, and " + activeJobCountLocked() + " logical jobs";
                lifecycleFailures.put("lifecycle", reason);
                if (state != State.CLOSED) {
                    state = State.FAILED;
                }
                failure = new IllegalStateException(reason);
            }
            closeCancellationExecutor(true);
            drain.completeExceptionally(failure);
        });
    }

    private CompletableFuture<Void> failedDrain() {
        CompletableFuture<Void> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IllegalStateException("Flow Job Lifecycle Is Failed: " + lifecycleFailures));
        return failed;
    }

    private List<FlowJobReference<?>> activeJobsLocked() {
        return jobs.values().stream()
            .filter(this::active)
            .sorted(Comparator.comparing(FlowJobReference<?>::getCreatedAt).thenComparing(FlowJobReference::getId))
            .toList();
    }

    private int activeJobCountLocked() {
        return (int) jobs.values().stream().filter(this::active).count();
    }

    private int physicalTaskCountLocked() {
        return physicalTasks.size();
    }

    private int lifecycleWorkCountLocked() {
        return activeJobCountLocked() + physicalTasks.size() + pendingCancellations.size() + pendingCancellationActions;
    }

    private int pendingCancellationCount() {
        synchronized (lifecycleMonitor) {
            return pendingCancellations.size();
        }
    }

    private boolean admissionOpenLocked() {
        return state == State.OPEN;
    }

    private void requireAdmissionOpen() {
        if (!admissionOpenLocked()) {
            throw new IllegalStateException("Flow Job Admission Is " + state.name());
        }
    }

    private void requireRegisteredLocked(FlowJobReference<?> reference) {
        if (jobs.get(reference.getId()) != reference) {
            throw new IllegalArgumentException("Flow Job Reference Is Not Registered: " + reference.getId());
        }
    }

    private void prune() {
        synchronized (lifecycleMonitor) {
            pruneLocked();
        }
    }

    private void pruneLocked() {
        Instant cutoff = Instant.now().minus(terminalRetention);
        jobs.values().removeIf(reference -> {
            Instant terminalTime = terminalTimes.get(reference.getId());
            boolean expired = terminal(reference.getState()) && terminalTime != null && !terminalTime.isAfter(cutoff);
            if (expired) {
                terminalTimes.remove(reference.getId());
            }
            return expired;
        });
        int overflow = jobs.size() - maxRetained;
        if (overflow <= 0) {
            return;
        }
        List<FlowJobReference<?>> removable = jobs.values().stream()
            .filter(reference -> terminal(reference.getState()))
            .sorted(Comparator.comparing((FlowJobReference<?> reference) -> reference.getCreatedAt()))
            .limit(overflow)
            .toList();
        for (FlowJobReference<?> reference : removable) {
            jobs.remove(reference.getId(), reference);
            terminalTimes.remove(reference.getId());
        }
    }

    private boolean terminal(FlowJobReference.State state) {
        return state == FlowJobReference.State.SUCCEEDED || state == FlowJobReference.State.FAILED || state == FlowJobReference.State.CANCELLED;
    }

    private boolean active(FlowJobReference<?> reference) {
        FlowJobReference.State current = reference.getState();
        return current == FlowJobReference.State.PENDING || current == FlowJobReference.State.RUNNING
            || current == FlowJobReference.State.CANCELLING;
    }

    private String reason(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }

    private Duration requireTimeout(Duration timeout) {
        if (timeout == null || timeout.isNegative()) {
            throw new IllegalArgumentException("Flow Job Drain Timeout Must Be Non-Negative");
        }
        return timeout;
    }

    private record PhysicalTask(FlowJobReference<?> reference, Runnable cancellation, CompletableFuture<?> termination,
                                AtomicBoolean cancellationRequested, CompletableFuture<Void> cancellationCompletion) {
        private PhysicalTask(FlowJobReference<?> reference, Runnable cancellation, CompletableFuture<?> termination) {
            this(reference, cancellation, termination, new AtomicBoolean(), new CompletableFuture<>());
        }
    }

    public enum State {
        OPEN,
        ADMISSION_CLOSED,
        QUIESCING,
        QUIESCED,
        FAILED,
        CLOSED
    }

    public record Health(boolean available, State state, int activeJobs, int physicalTasks, long generation, Map<String, String> failures) {
        public Health(boolean available, State state, int activeJobs, long generation, Map<String, String> failures) {
            this(available, state, activeJobs, 0, generation, failures);
        }

        public Health {
            failures = Map.copyOf(failures == null ? Map.of() : failures);
        }
    }
}
