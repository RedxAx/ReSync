package restudio.resync.upgrade;

import java.nio.file.Path;
import java.util.Objects;

import restudio.resync.migration.PersistenceParticipantRegistry;
import restudio.resync.migration.SnapshotMetadata;

public record UpgradeDryRunRequest(
    Path sourceRoot,
    Path snapshotStagingRoot,
    SnapshotMetadata snapshotMetadata,
    PersistenceParticipantRegistry participants,
    UpgradeSourceWindow sourceWindow,
    UpgradePlanner planner,
    long reservedBytes
) {
    public UpgradeDryRunRequest {
        sourceRoot = Objects.requireNonNull(sourceRoot, "sourceRoot").toAbsolutePath().normalize();
        snapshotStagingRoot = Objects.requireNonNull(snapshotStagingRoot, "snapshotStagingRoot").toAbsolutePath().normalize();
        snapshotMetadata = Objects.requireNonNull(snapshotMetadata, "snapshotMetadata");
        participants = Objects.requireNonNull(participants, "participants");
        sourceWindow = Objects.requireNonNull(sourceWindow, "sourceWindow");
        planner = Objects.requireNonNull(planner, "planner");
        if (reservedBytes < 0) {
            throw new IllegalArgumentException("reservedBytes Must Be Non-Negative");
        }
    }
}
