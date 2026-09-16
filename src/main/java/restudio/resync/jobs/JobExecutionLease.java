package restudio.resync.jobs;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class JobExecutionLease implements AutoCloseable {
    private final AtomicReference<Runnable> cancellation = new AtomicReference<>();
    private final AtomicBoolean cancellationRequested = new AtomicBoolean();
    private final AtomicBoolean cancellationDelivered = new AtomicBoolean();
    private final CompletableFuture<Void> termination = new CompletableFuture<>();

    public void setCancellation(Runnable action) {
        cancellation.set(Objects.requireNonNull(action, "Job Cancellation Is Required"));
        deliverCancellation();
    }

    public void requestCancellation() {
        cancellationRequested.set(true);
        deliverCancellation();
    }

    public CompletionStage<Void> termination() {
        return termination;
    }

    public CompletableFuture<Void> terminationFuture() {
        return termination;
    }

    public void complete() {
        termination.complete(null);
    }

    public void fail(Throwable failure) {
        termination.completeExceptionally(Objects.requireNonNull(failure, "Job Termination Failure Is Required"));
    }

    @Override
    public void close() {
        complete();
    }

    private void deliverCancellation() {
        Runnable action = cancellation.get();
        if (action != null && cancellationRequested.get() && cancellationDelivered.compareAndSet(false, true)) {
            action.run();
        }
    }
}
