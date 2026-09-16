package restudio.resync.flow.runtime;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class RuntimeExecutionBarrier {
    private final Object monitor = new Object();
    private final CompletableFuture<Void> drainSignal = new CompletableFuture<>();
    private final CompletionStage<Void> drainView = drainSignal.minimalCompletionStage();
    private int activeCount;
    private boolean fenced;

    public Optional<Admission> tryAcquire() {
        synchronized (monitor) {
            if (fenced) {
                return Optional.empty();
            }
            activeCount++;
            return Optional.of(new Admission());
        }
    }

    public Admission acquire() {
        return tryAcquire().orElseThrow(() -> new IllegalStateException("Runtime Execution Admissions Are Fenced"));
    }

    public boolean fence() {
        synchronized (monitor) {
            if (fenced) {
                return false;
            }
            fenced = true;
            completeDrainIfReady();
            return true;
        }
    }

    public boolean isFenced() {
        synchronized (monitor) {
            return fenced;
        }
    }

    public int activeCount() {
        synchronized (monitor) {
            return activeCount;
        }
    }

    public CompletionStage<Void> drainSignal() {
        synchronized (monitor) {
            if (!fenced) {
                throw new IllegalStateException("Runtime Execution Barrier Must Be Fenced Before Drain");
            }
            return drainView;
        }
    }

    public DrainResult drain(Duration timeout) {
        Objects.requireNonNull(timeout, "Drain Timeout Is Required");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("Drain Timeout Cannot Be Negative");
        }
        long started = System.nanoTime();
        fence();
        try {
            drainSignal().toCompletableFuture().get(timeoutNanos(timeout), TimeUnit.NANOSECONDS);
            return result(DrainStatus.DRAINED, started);
        } catch (TimeoutException exception) {
            return result(DrainStatus.TIMED_OUT, started);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return result(DrainStatus.INTERRUPTED, started);
        } catch (ExecutionException exception) {
            throw new IllegalStateException("Runtime Execution Drain Signal Failed", exception);
        }
    }

    private DrainResult result(DrainStatus status, long started) {
        return new DrainResult(status, activeCount(), elapsedMillis(started));
    }

    private void release() {
        synchronized (monitor) {
            if (activeCount <= 0) {
                throw new IllegalStateException("Runtime Execution Admission Was Already Released");
            }
            activeCount--;
            completeDrainIfReady();
        }
    }

    private void completeDrainIfReady() {
        if (fenced && activeCount == 0) {
            drainSignal.complete(null);
        }
    }

    private static long timeoutNanos(Duration timeout) {
        try {
            return timeout.toNanos();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Drain Timeout Exceeds Nanosecond Range", exception);
        }
    }

    private static long elapsedMillis(long started) {
        return Math.max(0L, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
    }

    public enum DrainStatus {
        DRAINED,
        TIMED_OUT,
        INTERRUPTED
    }

    public record DrainResult(DrainStatus status, int activeCount, long elapsedMillis) {
        public DrainResult {
            status = Objects.requireNonNull(status, "Drain Status Is Required");
            if (activeCount < 0 || elapsedMillis < 0) {
                throw new IllegalArgumentException("Drain Measurements Cannot Be Negative");
            }
        }

        public boolean drained() {
            return status == DrainStatus.DRAINED;
        }
    }

    public final class Admission implements AutoCloseable {
        private boolean closed;

        private Admission() {
        }

        public boolean isClosed() {
            synchronized (monitor) {
                return closed;
            }
        }

        @Override
        public void close() {
            synchronized (monitor) {
                if (closed) {
                    return;
                }
                closed = true;
            }
            release();
        }
    }
}
