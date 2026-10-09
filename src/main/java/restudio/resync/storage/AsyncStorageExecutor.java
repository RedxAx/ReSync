package restudio.resync.storage;

import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class AsyncStorageExecutor {
    private static final int MAX_QUEUED = 64;
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(MAX_QUEUED), r -> {
            Thread thread = new Thread(r, "ReSync-Storage-Writer");
            thread.setDaemon(true);
            return thread;
        });
    private final Map<String, Write> queued = new LinkedHashMap<>();
    private final Set<Write> pending = new LinkedHashSet<>();
    private final long timeoutNanos;
    private Throwable firstFailure;
    private long failures;

    public AsyncStorageExecutor() {
        this(Duration.ofSeconds(5));
    }

    AsyncStorageExecutor(Duration timeout) {
        timeoutNanos = timeout.toNanos();
        if (timeoutNanos <= 0L) {
            throw new IllegalArgumentException("Storage timeout must be positive");
        }
    }

    public void submit(Runnable task) {
        submitTracked(task);
    }

    public CompletableFuture<Void> submitTracked(Runnable task) {
        return submitTracked(task, () -> {});
    }

    public CompletableFuture<Void> submitTracked(Runnable task, Runnable cleanup) {
        return admit(null, task, cleanup).future.copy();
    }

    public void submitLatest(String key, Runnable task, Runnable cleanup) {
        admit(Objects.requireNonNull(key, "key"), task, cleanup);
    }

    private Write admit(String key, Runnable task, Runnable cleanup) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(cleanup, "cleanup");
        synchronized (pending) {
            if (executor.isShutdown()) {
                throw new RejectedExecutionException("Storage writer is closed");
            }
            Write previous = key == null ? null : queued.get(key);
            if (previous != null) {
                Runnable retired = previous.cleanup;
                previous.task = task;
                previous.cleanup = cleanup;
                try {
                    retired.run();
                } catch (Throwable failure) {
                    recordFailure(failure);
                }
                return previous;
            }
            Write write = new Write(key, task, cleanup);
            pending.add(write);
            if (key != null) {
                queued.put(key, write);
            }
            try {
                executor.execute(write);
            } catch (RuntimeException | Error failure) {
                pending.remove(write);
                if (key != null) {
                    queued.remove(key, write);
                }
                throw failure;
            }
            return write;
        }
    }

    private void recordFailure(Throwable failure) {
        if (firstFailure == null) {
            firstFailure = failure;
        }
        if (failures < Long.MAX_VALUE) {
            failures++;
        }
    }

    public void flush() throws IOException {
        flush(System.nanoTime() + timeoutNanos);
    }

    private void flush(long deadline) throws IOException {
        CompletableFuture<?>[] futures;
        synchronized (pending) {
            futures = pending.stream().map(write -> write.future).toArray(CompletableFuture<?>[]::new);
        }
        try {
            CompletableFuture.allOf(futures).get(Math.max(0L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while flushing storage writes", failure);
        } catch (TimeoutException failure) {
            throw new IOException("Storage writes did not finish before the flush deadline", failure);
        } catch (ExecutionException failure) {
            throw writeFailure(failure.getCause());
        }
        synchronized (pending) {
            if (firstFailure != null) {
                throw writeFailure(firstFailure);
            }
        }
    }

    private IOException writeFailure(Throwable fallback) {
        synchronized (pending) {
            return new IOException("Async storage writes failed: " + failures,
                firstFailure == null ? fallback : firstFailure);
        }
    }

    public void shutdown() throws IOException {
        long deadline = System.nanoTime() + timeoutNanos;
        synchronized (pending) {
            executor.shutdown();
        }
        IOException failure = null;
        try {
            flush(deadline);
        } catch (IOException exception) {
            failure = exception;
        } finally {
            boolean terminated = executor.isTerminated();
            try {
                if (!terminated) {
                    terminated = executor.awaitTermination(Math.max(0L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                if (failure == null) {
                    failure = new IOException("Interrupted while stopping storage writes", exception);
                }
            } finally {
                if (!terminated) {
                    List<Runnable> discarded = executor.shutdownNow();
                    for (Runnable task : discarded) {
                        ((Write) task).finish(new IOException("Storage write cancelled during shutdown"));
                    }
                    if (failure == null) {
                        failure = new IOException("Storage writer did not stop before the shutdown deadline");
                    }
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private final class Write implements Runnable {
        private final String key;
        private Runnable task;
        private Runnable cleanup;
        private final CompletableFuture<Void> future = new CompletableFuture<>();

        private Write(String key, Runnable task, Runnable cleanup) {
            this.key = key;
            this.task = task;
            this.cleanup = cleanup;
        }

        @Override
        public void run() {
            Runnable operation;
            synchronized (pending) {
                if (key != null) {
                    queued.remove(key, this);
                }
                operation = task;
                task = null;
            }
            Throwable failure = null;
            try {
                operation.run();
            } catch (Throwable exception) {
                failure = exception;
            }
            finish(failure);
        }

        private void finish(Throwable failure) {
            try {
                cleanup.run();
            } catch (Throwable exception) {
                if (failure == null) {
                    failure = exception;
                } else if (failure != exception) {
                    failure.addSuppressed(exception);
                }
            }
            synchronized (pending) {
                if (failure != null) {
                    recordFailure(failure);
                }
                if (key != null) {
                    queued.remove(key, this);
                }
                task = null;
                cleanup = null;
                pending.remove(this);
            }
            if (failure == null) {
                future.complete(null);
            } else {
                future.completeExceptionally(failure);
            }
        }
    }
}
