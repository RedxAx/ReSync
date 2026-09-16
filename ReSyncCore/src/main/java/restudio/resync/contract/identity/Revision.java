package restudio.resync.contract.identity;

import restudio.resync.flow.identity.IdentitySupport;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class Revision implements Comparable<Revision> {
    private final long value;
    private final Map<String, Object> unknown;

    public Revision(long value) {
        this(value, Map.of());
    }

    public Revision(long value, Map<String, ?> unknown) {
        if (value < 0) {
            throw new IllegalArgumentException("Revision must be non-negative");
        }
        this.value = value;
        this.unknown = IdentitySupport.unknown(unknown, "revision unknown data");
    }

    public static Revision of(long value) {
        return new Revision(value);
    }

    public static Revision zero() {
        return new Revision(0L);
    }

    public long value() {
        return value;
    }

    public long revision() {
        return value;
    }

    public Map<String, Object> unknown() {
        return unknown;
    }

    public Revision next() {
        if (value == Long.MAX_VALUE) {
            throw new ArithmeticException("Revision cannot advance beyond Long.MAX_VALUE");
        }
        return new Revision(value + 1L);
    }

    public boolean isInitial() {
        return value == 0L;
    }

    public Map<String, Object> canonicalValue() {
        var known = new LinkedHashMap<String, Object>();
        known.put("revision", value);
        return IdentitySupport.merge(unknown, known);
    }

    @Override
    public int compareTo(Revision other) {
        return Long.compare(value, Objects.requireNonNull(other, "Revision is required").value);
    }

    @Override
    public boolean equals(Object object) {
        return this == object || object instanceof Revision other && value == other.value;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(value);
    }

    @Override
    public String toString() {
        return Long.toString(value);
    }
}
