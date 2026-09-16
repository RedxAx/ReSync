package restudio.resync.server;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;

public final class ReSyncShutdownCoordinator {
    private final Supplier<? extends CompletionStage<Boolean>> initialShutdown;
    private final Supplier<? extends CompletionStage<Void>> retryShutdown;
    private final Runnable finishCore;
    private final Supplier<? extends CompletionStage<Void>> coreCompletion;
    private final Object monitor = new Object();
    private Attempt activeAttempt;

    public ReSyncShutdownCoordinator(Supplier<? extends CompletionStage<Boolean>> initialShutdown,
                                     Supplier<? extends CompletionStage<Void>> retryShutdown,
                                     Runnable finishCore,
                                     Supplier<? extends CompletionStage<Void>> coreCompletion) {
        this.initialShutdown = Objects.requireNonNull(initialShutdown, "initialShutdown");
        this.retryShutdown = Objects.requireNonNull(retryShutdown, "retryShutdown");
        this.finishCore = Objects.requireNonNull(finishCore, "finishCore");
        this.coreCompletion = Objects.requireNonNull(coreCompletion, "coreCompletion");
    }

    public CompletionStage<Void> continueShutdown(boolean initialReady) {
        Attempt attempt;
        synchronized (monitor) {
            if (activeAttempt != null && !activeAttempt.completion.isDone()) {
                activeAttempt.retryRequested = true;
                return activeAttempt.completion;
            }
            attempt = new Attempt();
            activeAttempt = attempt;
        }
        startAttempt(attempt, initialReady);
        return attempt.completion;
    }

    private void startAttempt(Attempt attempt, boolean initialReady) {
        CompletionStage<Void> operation;
        try {
            operation = initialReady ? startInitialShutdown() : requiredStage(retryShutdown.get());
        } catch (RuntimeException exception) {
            operation = CompletableFuture.failedFuture(exception);
        }
        operation.whenComplete((unused, failure) -> completeAttempt(attempt, failure));
    }

    private CompletionStage<Void> startInitialShutdown() {
        CompletionStage<Boolean> network = requiredStage(initialShutdown.get());
        return network.thenCompose(completed -> {
            if (!Boolean.TRUE.equals(completed)) {
                return CompletableFuture.failedFuture(new IllegalStateException(
                    "ReSync Network Shutdown Did Not Complete"));
            }
            finishCore.run();
            return requiredStage(coreCompletion.get());
        });
    }

    private void completeAttempt(Attempt attempt, Throwable failure) {
        boolean retry;
        synchronized (monitor) {
            if (activeAttempt != attempt) {
                return;
            }
            retry = failure != null && attempt.retryRequested;
            if (retry) {
                attempt.retryRequested = false;
            } else {
                activeAttempt = null;
            }
        }
        if (retry) {
            startAttempt(attempt, false);
            return;
        }
        if (failure == null) {
            attempt.completion.complete(null);
        } else {
            attempt.completion.completeExceptionally(rootCause(failure));
        }
    }

    private static <T> CompletionStage<T> requiredStage(CompletionStage<T> stage) {
        return Objects.requireNonNull(stage, "shutdown stage");
    }

    private static Throwable rootCause(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
            && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static final class Attempt {
        private final CompletableFuture<Void> completion = new CompletableFuture<>();
        private boolean retryRequested;
    }
}
