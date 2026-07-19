package restudio.flow.data;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class FlowJobReference<T> {
    public enum State {
        PENDING,
        RUNNING,
        SUCCEEDED,
        FAILED,
        CANCELLED
    }

    private final String id;
    private final String kind;
    private final String owner;
    private final Instant createdAt;
    private final CompletableFuture<FlowOperationResult<T>> completion = new CompletableFuture<>();
    private final AtomicReference<State> state = new AtomicReference<>(State.PENDING);
    private final AtomicBoolean cancellationRequested = new AtomicBoolean();
    private volatile double progress;
    private volatile Runnable cancellation;
    private volatile Map<String, Object> metadata = Map.of();

    public FlowJobReference(String id, String kind) {
        this(id, kind, "server");
    }

    public FlowJobReference(String id, String kind, String owner) {
        this(id, kind, owner, Instant.now());
    }

    private FlowJobReference(String id, String kind, String owner, Instant createdAt) {
        this.id = id != null ? id : "";
        this.kind = kind != null ? kind : "";
        this.owner = owner != null && !owner.isBlank() ? owner : "server";
        this.createdAt = createdAt != null ? createdAt : Instant.now();
    }

    public String getId() {
        return id;
    }

    public String getKind() {
        return kind;
    }

    public String getOwner() {
        return owner;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public State getState() {
        return state.get();
    }

    public double getProgress() {
        return progress;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public CompletableFuture<FlowOperationResult<T>> getCompletion() {
        return completion;
    }

    public void start() {
        state.compareAndSet(State.PENDING, State.RUNNING);
    }

    public void updateProgress(double progress, Map<String, Object> metadata) {
        this.progress = Math.clamp(progress, 0.0, 1.0);
        this.metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
    }

    public boolean succeed(T value) {
        if (!state.compareAndSet(State.RUNNING, State.SUCCEEDED) && !state.compareAndSet(State.PENDING, State.SUCCEEDED)) {
            return false;
        }
        progress = 1.0;
        completion.complete(FlowOperationResult.success(value));
        return true;
    }

    public boolean fail(String code, String message, Map<String, Object> details) {
        if (!state.compareAndSet(State.RUNNING, State.FAILED) && !state.compareAndSet(State.PENDING, State.FAILED)) {
            return false;
        }
        completion.complete(FlowOperationResult.failure(code, message, details));
        return true;
    }

    public boolean cancel() {
        cancellationRequested.set(true);
        while (true) {
            State current = state.get();
            if (current == State.SUCCEEDED || current == State.FAILED || current == State.CANCELLED) {
                return false;
            }
            if (state.compareAndSet(current, State.CANCELLED)) {
                break;
            }
        }
        Runnable action = cancellation;
        if (action != null) {
            action.run();
        }
        completion.complete(FlowOperationResult.failure("JOB_CANCELLED", "Job Cancelled", Map.of("jobId", id)));
        return true;
    }

    public boolean isCancellationRequested() {
        return cancellationRequested.get();
    }

    public void setCancellation(Runnable cancellation) {
        this.cancellation = cancellation;
    }

    public Snapshot<T> snapshot() {
        return new Snapshot<>(id, kind, owner, createdAt, state.get(), progress, metadata, cancellationRequested.get(), completion.getNow(null));
    }

    public static <T> FlowJobReference<T> restore(Snapshot<T> snapshot) {
        if (snapshot == null) {
            throw new IllegalArgumentException("Job snapshot is required");
        }
        FlowJobReference<T> reference = new FlowJobReference<>(snapshot.id(), snapshot.kind(), snapshot.owner(), snapshot.createdAt());
        reference.state.set(snapshot.state());
        reference.progress = Math.clamp(snapshot.progress(), 0.0, 1.0);
        reference.metadata = snapshot.metadata();
        reference.cancellationRequested.set(snapshot.cancellationRequested());
        if (snapshot.outcome() != null) {
            reference.completion.complete(snapshot.outcome());
        }
        return reference;
    }

    public record Snapshot<T>(String id, String kind, String owner, Instant createdAt, State state, double progress, Map<String, Object> metadata,
                              boolean cancellationRequested, FlowOperationResult<T> outcome) {
        public Snapshot {
            id = id != null ? id : "";
            kind = kind != null ? kind : "";
            owner = owner != null && !owner.isBlank() ? owner : "server";
            createdAt = createdAt != null ? createdAt : Instant.EPOCH;
            state = state != null ? state : State.PENDING;
            progress = Math.clamp(progress, 0.0, 1.0);
            metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
        }
    }
}
