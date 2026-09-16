package restudio.resync.restore;

import restudio.resync.migration.MigrationJournalState;
import restudio.resync.migration.StagedMigration;
import restudio.resync.migration.Snapshot;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

public record RestoreResult(Optional<Snapshot> currentSnapshot, StagedMigration staged, Path journalPath, MigrationJournalState finalState) {
    public RestoreResult {
        currentSnapshot = currentSnapshot == null ? Optional.empty() : currentSnapshot;
        staged = Objects.requireNonNull(staged, "staged");
        journalPath = Objects.requireNonNull(journalPath, "journalPath").toAbsolutePath().normalize();
        finalState = Objects.requireNonNull(finalState, "finalState");
        if (finalState != MigrationJournalState.COMMITTED) {
            throw new IllegalArgumentException("Successful Restore Must Be Committed");
        }
    }
}
