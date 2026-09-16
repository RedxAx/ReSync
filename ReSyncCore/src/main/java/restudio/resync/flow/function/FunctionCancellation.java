package restudio.resync.flow.function;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record FunctionCancellation(State state, String reason, long deadlineMillis) {
    public static final long NO_DEADLINE = Long.MAX_VALUE;

    public FunctionCancellation {
        state = Objects.requireNonNull(state, "Function Cancellation State Is Required");
        reason = FunctionContractSupport.optionalText(reason, "Function Cancellation Reason", 512);
        if (deadlineMillis < 0) {
            throw new IllegalArgumentException("Function Cancellation Deadline Cannot Be Negative");
        }
        if (state == State.NONE && reason != null) {
            throw new IllegalArgumentException("An Unrequested Function Cancellation Cannot Have A Reason");
        }
    }

    public static FunctionCancellation none() {
        return new FunctionCancellation(State.NONE, null, NO_DEADLINE);
    }

    public static FunctionCancellation requested(String reason) {
        return new FunctionCancellation(State.REQUESTED, reason, NO_DEADLINE);
    }

    public static FunctionCancellation cancelled(String reason) {
        return new FunctionCancellation(State.CANCELLED, reason, NO_DEADLINE);
    }

    public static FunctionCancellation expired(long deadlineMillis) {
        return new FunctionCancellation(State.EXPIRED, "The function deadline expired.", deadlineMillis);
    }

    public boolean requested() {
        return state != State.NONE;
    }

    public boolean terminal() {
        return state == State.CANCELLED || state == State.EXPIRED;
    }

    public boolean deadlineExceeded(long nowMillis) {
        if (nowMillis < 0) {
            throw new IllegalArgumentException("Current Time Cannot Be Negative");
        }
        return deadlineMillis != NO_DEADLINE && nowMillis >= deadlineMillis;
    }

    public Map<String, Object> canonicalValue() {
        LinkedHashMap<String, Object> value = new LinkedHashMap<>();
        value.put("state", state.wireName());
        if (reason != null) {
            value.put("reason", reason);
        }
        if (deadlineMillis != NO_DEADLINE) {
            value.put("deadlineMillis", deadlineMillis);
        }
        return Map.copyOf(value);
    }

    public enum State {
        NONE("none"),
        REQUESTED("requested"),
        CANCELLED("cancelled"),
        EXPIRED("expired");

        private final String wireName;

        State(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }
    }
}
