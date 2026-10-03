package restudio.resync.flow.runtime;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class RuntimeCancellationToken {
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final CompletableFuture<Void> cancellation = new CompletableFuture<>();
    private final CompletableFuture<Void> deadline = new CompletableFuture<>();
    private final Set<Registration> callbacks = ConcurrentHashMap.newKeySet();
    private final long deadlineMillis;
    private volatile Registration parentLink;

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
                deadline.orTimeout(deadlineMillis - now, TimeUnit.MILLISECONDS).whenComplete((ignored, failure) -> {
                    if (failure != null) {
                        cancel();
                    }
                });
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
        deadline.complete(null);
        CompletableFuture.runAsync(() -> {
            callbacks.forEach(registration -> CompletableFuture.runAsync(registration::fire));
            cancellation.complete(null);
        });
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
        child.parentLink = onCancel(child::cancel);
        return child;
    }

    public Registration onCancel(Runnable action) {
        Registration registration = new Registration(Objects.requireNonNull(action, "Cancellation Action Is Required"));
        callbacks.add(registration);
        if (isCancelled()) {
            CompletableFuture.runAsync(registration::fire);
        }
        return registration;
    }

    void finish() {
        deadline.complete(null);
        Registration link = parentLink;
        if (link != null) {
            link.close();
            parentLink = null;
        }
    }

    public final class Registration implements AutoCloseable {
        private final AtomicReference<Runnable> action;

        private Registration(Runnable action) {
            this.action = new AtomicReference<>(action);
        }

        private void fire() {
            Runnable callback = action.getAndSet(null);
            callbacks.remove(this);
            if (callback != null) {
                callback.run();
            }
        }

        @Override
        public void close() {
            action.set(null);
            callbacks.remove(this);
        }
    }

    public void cancelIfRequested() {
        throwIfCancelled();
    }
}
