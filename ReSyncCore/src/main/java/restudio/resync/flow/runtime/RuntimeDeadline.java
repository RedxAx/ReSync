package restudio.resync.flow.runtime;

public final class RuntimeDeadline {
    private static final long NANOS_PER_MILLI = 1_000_000L;

    private RuntimeDeadline() {
    }

    public static long millisToNanosExact(long millis) {
        if (millis < 0) {
            throw new IllegalArgumentException("Runtime Duration Cannot Be Negative");
        }
        try {
            return Math.multiplyExact(millis, NANOS_PER_MILLI);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Runtime Duration Exceeds Nanosecond Range", exception);
        }
    }

    public static long deadlineMillisExact(long startedMillis, long timeoutMillis) {
        if (startedMillis < 0) {
            throw new IllegalArgumentException("Runtime Clock Value Cannot Be Negative");
        }
        if (timeoutMillis < 0) {
            throw new IllegalArgumentException("Runtime Timeout Cannot Be Negative");
        }
        try {
            return Math.addExact(startedMillis, timeoutMillis);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Runtime Deadline Exceeds Millisecond Range", exception);
        }
    }

    public static boolean elapsedNanos(long startedNanos, long timeoutMillis) {
        if (timeoutMillis < 0) {
            throw new IllegalArgumentException("Runtime Timeout Cannot Be Negative");
        }
        if (timeoutMillis == Long.MAX_VALUE) {
            return false;
        }
        if (timeoutMillis > Long.MAX_VALUE / NANOS_PER_MILLI) {
            return false;
        }
        return System.nanoTime() - startedNanos >= timeoutMillis * NANOS_PER_MILLI;
    }
}
