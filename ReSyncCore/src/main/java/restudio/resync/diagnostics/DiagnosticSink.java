package restudio.resync.diagnostics;

import java.util.Objects;

public interface DiagnosticSink extends AutoCloseable {
    Status status();

    default boolean enabled() {
        return status().enabled();
    }

    Offer offer(DiagnosticEvent event);

    Status pause();

    Status resume();

    Flush flush();

    @Override
    void close();

    enum Mode {
        OFF("off", -1),
        NORMAL("normal", 2),
        RECOVERY("recovery", 1),
        VERBOSE("verbose", 0);

        private final String wireName;
        private final int minimumPriority;

        Mode(String wireName, int minimumPriority) {
            this.wireName = wireName;
            this.minimumPriority = minimumPriority;
        }

        public String wireName() {
            return wireName;
        }

        public boolean enabled() {
            return this != OFF;
        }

        public boolean accepts(Priority priority) {
            Objects.requireNonNull(priority, "Diagnostic priority is required");
            return enabled() && priority.rank() >= minimumPriority;
        }
    }

    enum Priority {
        TRACE("trace", 0),
        NORMAL("normal", 1),
        IMPORTANT("important", 2),
        TERMINAL("terminal", 3);

        private final String wireName;
        private final int rank;

        Priority(String wireName, int rank) {
            this.wireName = wireName;
            this.rank = rank;
        }

        public String wireName() {
            return wireName;
        }

        public int rank() {
            return rank;
        }

        public boolean atLeast(Priority other) {
            return rank >= Objects.requireNonNull(other, "Diagnostic priority is required").rank;
        }
    }

    enum State {
        READY,
        PAUSED,
        FAILED,
        CLOSED
    }

    enum Offer {
        ACCEPTED,
        DISABLED,
        PAUSED,
        DROPPED,
        REJECTED,
        FAILED,
        CLOSED
    }

    enum Flush {
        FLUSHED,
        EMPTY,
        DISABLED,
        FAILED,
        CLOSED
    }

    record Status(Mode mode, State state, long offered, long accepted, long dropped, long failures, String reason) {
        public Status {
            mode = Objects.requireNonNull(mode, "Diagnostic mode is required");
            state = Objects.requireNonNull(state, "Diagnostic sink state is required");
            if (offered < 0L || accepted < 0L || dropped < 0L || failures < 0L) {
                throw new IllegalArgumentException("Diagnostic sink counters cannot be negative");
            }
            reason = reason == null ? "" : reason;
        }

        public static Status disabled() {
            return new Status(Mode.OFF, State.READY, 0L, 0L, 0L, 0L, "");
        }

        public static Status ready(Mode mode) {
            return new Status(Objects.requireNonNull(mode, "Diagnostic mode is required"), State.READY,
                0L, 0L, 0L, 0L, "");
        }

        public boolean enabled() {
            return mode.enabled() && state == State.READY;
        }

        public boolean paused() {
            return state == State.PAUSED;
        }

        public boolean failed() {
            return state == State.FAILED;
        }

        public boolean closed() {
            return state == State.CLOSED;
        }
    }
}
