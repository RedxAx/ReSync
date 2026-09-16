package restudio.resync.flow.identity;

import java.util.UUID;

public record CorrelationId(UUID value) implements UuidIdentity, Comparable<CorrelationId> {
    public CorrelationId {
        value = IdentityValidation.uuid(value, "Correlation ID");
    }

    public static CorrelationId of(UUID value) {
        return new CorrelationId(value);
    }

    public static CorrelationId random() {
        return new CorrelationId(UuidIdentity.interactive());
    }

    public static CorrelationId interactive() {
        return random();
    }

    public static CorrelationId deterministic(String name) {
        return deterministic(UuidIdentity.MIGRATION_NAMESPACE, name);
    }

    public static CorrelationId deterministic(UUID namespace, String name) {
        return new CorrelationId(UuidIdentity.deterministic(namespace, "correlation", name));
    }

    public static CorrelationId deterministic(UUID namespace, String domain, String name) {
        return new CorrelationId(UuidIdentity.deterministic(namespace, domain, name));
    }

    public static CorrelationId parseCanonicalText(String value) {
        return new CorrelationId(IdentityValidation.uuid(value, "Correlation ID"));
    }

    @Override
    public int compareTo(CorrelationId other) {
        return CanonicalText.compare(canonicalText(), other.canonicalText());
    }

    @Override
    public String toString() {
        return canonicalText();
    }
}
