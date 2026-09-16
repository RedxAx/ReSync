package restudio.resync.restore;

import restudio.resync.migration.Snapshot;
import restudio.resync.migration.SnapshotMetadata;

import java.nio.file.Path;
import java.util.Objects;

public record RestoreRequest(Snapshot snapshot, RestoreCompatibilityPolicy compatibility, Path currentSnapshotStagingRoot, SnapshotMetadata currentSnapshotMetadata, Path restoreStagingRoot, Path journalPath) {
    public RestoreRequest {
        snapshot = Objects.requireNonNull(snapshot, "snapshot");
        compatibility = Objects.requireNonNull(compatibility, "compatibility");
        currentSnapshotStagingRoot = normalize(currentSnapshotStagingRoot, "currentSnapshotStagingRoot");
        currentSnapshotMetadata = Objects.requireNonNull(currentSnapshotMetadata, "currentSnapshotMetadata");
        restoreStagingRoot = normalize(restoreStagingRoot, "restoreStagingRoot");
        journalPath = normalize(journalPath, "journalPath");
    }

    private static Path normalize(Path path, String field) {
        return Objects.requireNonNull(path, field).toAbsolutePath().normalize();
    }
}
