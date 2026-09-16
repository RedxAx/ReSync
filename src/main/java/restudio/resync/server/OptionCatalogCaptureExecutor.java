package restudio.resync.server;

import restudio.resync.api.OptionCatalogCapture;
import restudio.resync.api.OptionCatalogProvider;
import restudio.resync.api.OptionCatalogQuery;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

@FunctionalInterface
public interface OptionCatalogCaptureExecutor extends AutoCloseable {
    OptionCatalogCapture capture(OptionCatalogProvider provider, OptionCatalogQuery query);

    default CompletionStage<OptionCatalogCapture> captureAsync(OptionCatalogProvider provider, OptionCatalogQuery query) {
        try {
            return CompletableFuture.completedFuture(capture(provider, query));
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    @Override
    default void close() {
    }

    static OptionCatalogCaptureExecutor callerOnly() {
        return (provider, query) -> {
            Objects.requireNonNull(provider, "Option catalog provider is required");
            Objects.requireNonNull(query, "Option catalog query is required");
            if (provider.captureAffinity() != OptionCatalogProvider.CaptureAffinity.CALLER) {
                throw new UnsupportedOperationException("Option provider requires an unavailable capture executor");
            }
            return Objects.requireNonNull(provider.capture(query), "Option catalog capture is required");
        };
    }

    static Bounded bounded(int queueCapacity, Duration timeout, BooleanSupplier serverMainThread,
                           Consumer<Runnable> serverMainScheduler, ThreadFactory ioThreadFactory) {
        return new Bounded(queueCapacity, timeout, serverMainThread, serverMainScheduler, ioThreadFactory);
    }

    final class Bounded implements OptionCatalogCaptureExecutor {
        private final Duration timeout;
        private final BooleanSupplier serverMainThread;
        private final Consumer<Runnable> serverMainScheduler;
        private final ThreadPoolExecutor io;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Bounded(int queueCapacity, Duration timeout, BooleanSupplier serverMainThread,
                        Consumer<Runnable> serverMainScheduler, ThreadFactory ioThreadFactory) {
            if (queueCapacity < 1) {
                throw new IllegalArgumentException("Option catalog capture queue capacity must be positive");
            }
            this.timeout = Objects.requireNonNull(timeout, "Option catalog capture timeout is required");
            if (timeout.isZero() || timeout.isNegative()) {
                throw new IllegalArgumentException("Option catalog capture timeout must be positive");
            }
            this.serverMainThread = Objects.requireNonNull(serverMainThread, "Server main-thread predicate is required");
            this.serverMainScheduler = Objects.requireNonNull(serverMainScheduler, "Server main-thread scheduler is required");
            this.io = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity), Objects.requireNonNull(ioThreadFactory, "Option catalog IO thread factory is required"),
                new ThreadPoolExecutor.AbortPolicy());
        }

        @Override
        public OptionCatalogCapture capture(OptionCatalogProvider provider, OptionCatalogQuery query) {
            require(provider, query);
            return switch (provider.captureAffinity()) {
                case CALLER -> required(provider.capture(query));
                case IO -> {
                    if (serverMainThread.getAsBoolean()) {
                        throw new CaptureUnavailable("Option catalog IO capture cannot block the server main thread");
                    }
                    yield await(submitIo(provider, query));
                }
                case SERVER_MAIN -> serverMainThread.getAsBoolean()
                    ? required(provider.capture(query)) : await(submitMain(provider, query));
                case UNSUPPORTED -> throw new CaptureUnavailable("Option catalog provider does not expose a coherent capture");
            };
        }

        @Override
        public CompletionStage<OptionCatalogCapture> captureAsync(OptionCatalogProvider provider, OptionCatalogQuery query) {
            require(provider, query);
            return switch (provider.captureAffinity()) {
                case CALLER -> completed(provider, query);
                case IO -> submitIo(provider, query).completion();
                case SERVER_MAIN -> serverMainThread.getAsBoolean() ? completed(provider, query) : submitMain(provider, query).completion();
                case UNSUPPORTED -> CompletableFuture.failedFuture(
                    new CaptureUnavailable("Option catalog provider does not expose a coherent capture"));
            };
        }

        public ShutdownResult shutdown(Duration grace) {
            if (closed.compareAndSet(false, true)) {
                io.shutdownNow();
                io.purge();
            }
            boolean terminated;
            try {
                terminated = io.awaitTermination(Math.max(0L, Objects.requireNonNull(grace, "Shutdown grace is required").toMillis()),
                    TimeUnit.MILLISECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                terminated = io.isTerminated();
            }
            return new ShutdownResult(terminated, io.getActiveCount(), io.getQueue().size());
        }

        @Override
        public void close() {
            shutdown(Duration.ofMillis(100));
        }

        private Pending submitIo(OptionCatalogProvider provider, OptionCatalogQuery query) {
            CompletableFuture<OptionCatalogCapture> completion = new CompletableFuture<>();
            Future<?> submitted;
            try {
                submitted = io.submit(() -> complete(completion, provider, query));
            } catch (RejectedExecutionException exception) {
                throw new CaptureUnavailable("Option catalog IO capture queue is full", exception);
            }
            return timeout(new Pending(completion, submitted));
        }

        private Pending submitMain(OptionCatalogProvider provider, OptionCatalogQuery query) {
            CompletableFuture<OptionCatalogCapture> completion = new CompletableFuture<>();
            try {
                serverMainScheduler.accept(() -> complete(completion, provider, query));
            } catch (RuntimeException exception) {
                throw new CaptureUnavailable("Option catalog server main-thread capture could not be scheduled", exception);
            }
            return timeout(new Pending(completion, null));
        }

        private Pending timeout(Pending pending) {
            CompletableFuture.delayedExecutor(timeout.toMillis(), TimeUnit.MILLISECONDS).execute(() -> {
                if (pending.completion().completeExceptionally(new CaptureTimeout("Option catalog capture timed out"))) {
                    if (pending.task() != null) {
                        pending.task().cancel(true);
                        io.purge();
                    }
                }
            });
            return pending;
        }

        private OptionCatalogCapture await(Pending pending) {
            try {
                return pending.completion().get(timeout.toMillis() + 50L, TimeUnit.MILLISECONDS);
            } catch (InterruptedException exception) {
                if (pending.task() != null) {
                    pending.task().cancel(true);
                    io.purge();
                }
                Thread.currentThread().interrupt();
                throw new CaptureUnavailable("Option catalog capture was interrupted", exception);
            } catch (TimeoutException exception) {
                if (pending.task() != null) {
                    pending.task().cancel(true);
                    io.purge();
                }
                throw new CaptureTimeout("Option catalog capture timed out", exception);
            } catch (ExecutionException exception) {
                Throwable cause = exception.getCause();
                if (cause instanceof RuntimeException runtime) {
                    throw runtime;
                }
                throw new CaptureUnavailable("Option catalog capture failed", cause);
            }
        }

        private CompletionStage<OptionCatalogCapture> completed(OptionCatalogProvider provider, OptionCatalogQuery query) {
            try {
                return CompletableFuture.completedFuture(required(provider.capture(query)));
            } catch (RuntimeException exception) {
                return CompletableFuture.failedFuture(exception);
            }
        }

        private void complete(CompletableFuture<OptionCatalogCapture> completion, OptionCatalogProvider provider,
                              OptionCatalogQuery query) {
            try {
                completion.complete(required(provider.capture(query)));
            } catch (Throwable failure) {
                completion.completeExceptionally(failure);
            }
        }

        private void require(OptionCatalogProvider provider, OptionCatalogQuery query) {
            if (closed.get()) {
                throw new CaptureUnavailable("Option catalog capture executor is closed");
            }
            Objects.requireNonNull(provider, "Option catalog provider is required");
            Objects.requireNonNull(query, "Option catalog query is required");
        }

        private OptionCatalogCapture required(OptionCatalogCapture capture) {
            return Objects.requireNonNull(capture, "Option catalog capture is required");
        }

        private record Pending(CompletableFuture<OptionCatalogCapture> completion, Future<?> task) {
        }
    }

    record ShutdownResult(boolean terminated, int activeTasks, int queuedTasks) {
    }

    class CaptureUnavailable extends IllegalStateException {
        public CaptureUnavailable(String message) {
            super(message);
        }

        public CaptureUnavailable(String message, Throwable cause) {
            super(message, cause);
        }
    }

    final class CaptureTimeout extends CaptureUnavailable {
        public CaptureTimeout(String message) {
            super(message);
        }

        public CaptureTimeout(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
