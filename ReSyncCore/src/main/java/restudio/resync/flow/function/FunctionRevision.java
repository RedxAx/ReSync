package restudio.resync.flow.function;

import java.util.Objects;

public record FunctionRevision(long value) implements Comparable<FunctionRevision> {
    public FunctionRevision {
        if (value < 0) {
            throw new IllegalArgumentException("Function Revision Cannot Be Negative");
        }
    }

    public static FunctionRevision initial() {
        return new FunctionRevision(0);
    }

    public static FunctionRevision of(long value) {
        return new FunctionRevision(value);
    }

    public static FunctionRevision parseCanonicalText(String value) {
        Objects.requireNonNull(value, "Function Revision Text Is Required");
        long parsed;
        try {
            parsed = Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Function Revision Text Is Invalid", exception);
        }
        FunctionRevision revision = new FunctionRevision(parsed);
        if (!revision.canonicalText().equals(value)) {
            throw new IllegalArgumentException("Function Revision Text Is Not Canonical");
        }
        return revision;
    }

    public FunctionRevision next() {
        if (value == Long.MAX_VALUE) {
            throw new IllegalStateException("Function Revision Cannot Advance");
        }
        return new FunctionRevision(value + 1);
    }

    public String canonicalText() {
        return Long.toString(value);
    }

    public Object canonicalValue() {
        return value;
    }

    @Override
    public int compareTo(FunctionRevision other) {
        return Long.compare(value, Objects.requireNonNull(other, "Function Revision Is Required").value);
    }
}
