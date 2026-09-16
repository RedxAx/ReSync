package restudio.resync.flow.identity;

import java.util.UUID;

public record TraceId(UUID value) implements UuidIdentity, Comparable<TraceId> {
    public TraceId {
        value = IdentityValidation.uuid(value, "Trace ID");
    }

    public static TraceId of(UUID value) {
        return new TraceId(value);
    }

    public static TraceId random() {
        return new TraceId(UuidIdentity.interactive());
    }

    public static TraceId interactive() {
        return random();
    }

    public static TraceId deterministic(String name) {
        return deterministic(UuidIdentity.MIGRATION_NAMESPACE, name);
    }

    public static TraceId deterministic(UUID namespace, String name) {
        return new TraceId(UuidIdentity.deterministic(namespace, "trace", name));
    }

    public static TraceId deterministic(UUID namespace, String domain, String name) {
        return new TraceId(UuidIdentity.deterministic(namespace, domain, name));
    }

    public static TraceId parseCanonicalText(String value) {
        return new TraceId(IdentityValidation.uuid(value, "Trace ID"));
    }

    @Override
    public int compareTo(TraceId other) {
        return CanonicalText.compare(canonicalText(), other.canonicalText());
    }

    @Override
    public String toString() {
        return canonicalText();
    }
}
