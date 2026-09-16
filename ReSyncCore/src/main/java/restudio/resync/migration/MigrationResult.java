package restudio.resync.migration;

public record MigrationResult(Snapshot snapshot, StagedMigration staged, MigrationJournalState finalState) {
    public MigrationResult {
        java.util.Objects.requireNonNull(snapshot, "snapshot");
        java.util.Objects.requireNonNull(staged, "staged");
        java.util.Objects.requireNonNull(finalState, "finalState");
    }
}
