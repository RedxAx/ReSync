package restudio.resync.flow.identity;

import java.util.UUID;

public record LeaseId(UUID value) implements UuidIdentity, Comparable<LeaseId> {
    public LeaseId {
        value = IdentityValidation.uuid(value, "Lease ID");
    }

    public static LeaseId of(UUID value) {
        return new LeaseId(value);
    }

    public static LeaseId random() {
        return new LeaseId(UuidIdentity.interactive());
    }

    public static LeaseId interactive() {
        return random();
    }

    public static LeaseId deterministic(String name) {
        return deterministic(UuidIdentity.MIGRATION_NAMESPACE, name);
    }

    public static LeaseId deterministic(UUID namespace, String name) {
        return new LeaseId(UuidIdentity.deterministic(namespace, "lease", name));
    }

    public static LeaseId deterministic(UUID namespace, String domain, String name) {
        return new LeaseId(UuidIdentity.deterministic(namespace, domain, name));
    }

    public static LeaseId parseCanonicalText(String value) {
        return new LeaseId(IdentityValidation.uuid(value, "Lease ID"));
    }

    @Override
    public int compareTo(LeaseId other) {
        return CanonicalText.compare(canonicalText(), other.canonicalText());
    }

    @Override
    public String toString() {
        return canonicalText();
    }
}
