package restudio.resync.flow.identity;

import java.util.UUID;

public record SnapshotId(UUID value) implements UuidIdentity, Comparable<SnapshotId> {
    public SnapshotId {
        value = IdentityValidation.uuid(value, "Snapshot ID");
    }

    public static SnapshotId of(UUID value) {
        return new SnapshotId(value);
    }

    public static SnapshotId random() {
        return new SnapshotId(UuidIdentity.interactive());
    }

    public static SnapshotId interactive() {
        return random();
    }

    public static SnapshotId deterministic(String name) {
        return deterministic(UuidIdentity.MIGRATION_NAMESPACE, name);
    }

    public static SnapshotId deterministic(UUID namespace, String name) {
        return new SnapshotId(UuidIdentity.deterministic(namespace, "snapshot", name));
    }

    public static SnapshotId deterministic(UUID namespace, String domain, String name) {
        return new SnapshotId(UuidIdentity.deterministic(namespace, domain, name));
    }

    public static SnapshotId parseCanonicalText(String value) {
        return new SnapshotId(IdentityValidation.uuid(value, "Snapshot ID"));
    }

    @Override
    public int compareTo(SnapshotId other) {
        return CanonicalText.compare(canonicalText(), other.canonicalText());
    }

    @Override
    public String toString() {
        return canonicalText();
    }
}
