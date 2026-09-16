package restudio.resync.flow.runtime;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class RuntimeCancellationToken {
    private static final Executor CALLBACKS = runnable -> {
        Thread thread = new Thread(runnable, "resync-runtime-cancel-" + UUID.randomUUID());
        thread.setDaemon(true);
        thread.start();
    };
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final CompletableFuture<Void> cancellation = new CompletableFuture<>();
    private final long deadlineMillis;

    public RuntimeCancellationToken() {
        this(RuntimeExecutionContext.NO_DEADLINE);
    }

    public RuntimeCancellationToken(long deadlineMillis) {
        if (deadlineMillis < 0) {
            throw new IllegalArgumentException("Runtime Deadline Cannot Be Negative");
        }
        this.deadlineMillis = deadlineMillis;
        if (deadlineMillis != RuntimeExecutionContext.NO_DEADLINE) {
            long now = System.currentTimeMillis();
            if (deadlineMillis <= now) {
                cancel();
            } else {
                CompletableFuture.delayedExecutor(deadlineMillis - now, TimeUnit.MILLISECONDS, CALLBACKS)
                    .execute(this::cancel);
            }
        }
    }

    public boolean isCancelled() {
        if (!cancelled.get() && deadlineExceeded()) {
            cancel();
        }
        return cancelled.get();
    }

    public long deadlineMillis() {
        return deadlineMillis;
    }

    public boolean deadlineExceeded() {
        return deadlineMillis != RuntimeExecutionContext.NO_DEADLINE
            && System.currentTimeMillis() >= deadlineMillis;
    }

    public boolean cancel() {
        if (!cancelled.compareAndSet(false, true)) {
            return false;
        }
        CompletableFuture.runAsync(() -> cancellation.complete(null), CALLBACKS);
        return true;
    }

    public void throwIfCancelled() {
        if (deadlineExceeded()) {
            cancel();
        }
        if (cancelled.get()) {
            throw new RuntimeOperationCancelledException();
        }
    }

    public CompletionStage<Void> cancelled() {
        isCancelled();
        return cancellation;
    }

    public RuntimeCancellationToken child() {
        return child(deadlineMillis);
    }

    RuntimeCancellationToken child(long childDeadlineMillis) {
        RuntimeCancellationToken child = new RuntimeCancellationToken(Math.min(deadlineMillis, childDeadlineMillis));
        if (isCancelled()) {
            child.cancel();
        } else {
            cancellation.thenRun(child::cancel);
        }
        return child;
    }

    public void cancelIfRequested() {
        throwIfCancelled();
    }
}
