package restudio.resync.migration;

public enum MigrationJournalState {
    PREPARED,
    TRANSFORMING,
    VALIDATING,
    STAGED,
    ACTIVATED,
    FAILED,
    ROLLED_BACK,
    COMMITTED
}
