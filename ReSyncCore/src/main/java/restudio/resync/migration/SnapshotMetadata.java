package restudio.resync.migration;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

public record SnapshotMetadata(int formatVersion, String snapshotId, Instant createdAt, String build, String catalogChecksum, Map<String, String> extensionVersions) {
    public SnapshotMetadata {
        if (formatVersion < 1) {
            throw new IllegalArgumentException("Snapshot Format Version Must Be Positive");
        }
        snapshotId = MigrationCanonical.requireText(snapshotId, "snapshotId");
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
        build = MigrationCanonical.requireText(build, "build");
        catalogChecksum = MigrationCanonical.requireDigest(catalogChecksum, "catalogChecksum");
        Map<String, String> sorted = new TreeMap<>();
        if (extensionVersions != null) {
            extensionVersions.forEach((key, value) -> sorted.put(MigrationCanonical.requireText(key, "extension owner"), MigrationCanonical.requireText(value, "extension version")));
        }
        extensionVersions = Map.copyOf(new LinkedHashMap<>(sorted));
    }

    public static SnapshotMetadata preflight() {
        return new SnapshotMetadata(1, "preflight", Instant.EPOCH, "preflight", "0000000000000000000000000000000000000000000000000000000000000000", Map.of());
    }
}
