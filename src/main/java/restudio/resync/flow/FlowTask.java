package restudio.resync.flow;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

final class FlowTask {
    private final CompletableFuture<Void> completion;
    private boolean started;
    private boolean cancelled;
    private boolean finished;

    FlowTask() {
        this(new CompletableFuture<>());
    }

    FlowTask(CompletableFuture<Void> completion) {
        this.completion = Objects.requireNonNull(completion, "Flow Task Completion Is Required");
    }

    CompletableFuture<Void> completion() {
        return completion;
    }

    synchronized boolean start() {
        if (started || finished || cancelled || completion.isDone()) {
            return false;
        }
        started = true;
        return true;
    }

    void cancel() {
        synchronized (this) {
            if (finished) {
                return;
            }
            cancelled = true;
            if (started) {
                return;
            }
            finished = true;
        }
        completion.cancel(false);
    }

    synchronized boolean isCancelled() {
        return cancelled;
    }

    void finish(Throwable failure) {
        boolean cancel;
        synchronized (this) {
            if (finished) {
                return;
            }
            finished = true;
            cancel = cancelled;
        }
        if (cancel) {
            completion.cancel(false);
        } else if (failure != null) {
            completion.completeExceptionally(failure);
        } else {
            completion.complete(null);
        }
    }
}
