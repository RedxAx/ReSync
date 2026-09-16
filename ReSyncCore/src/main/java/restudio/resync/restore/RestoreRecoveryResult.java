package restudio.resync.restore;

import restudio.resync.migration.JournalRecoveryAction;
import restudio.resync.migration.MigrationJournalState;

import java.nio.file.Path;
import java.util.Objects;

public record RestoreRecoveryResult(Path journalPath, JournalRecoveryAction action, MigrationJournalState finalState) {
    public RestoreRecoveryResult {
        journalPath = Objects.requireNonNull(journalPath, "journalPath").toAbsolutePath().normalize();
        action = Objects.requireNonNull(action, "action");
        finalState = Objects.requireNonNull(finalState, "finalState");
    }
}
