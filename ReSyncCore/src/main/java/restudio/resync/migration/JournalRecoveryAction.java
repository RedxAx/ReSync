package restudio.resync.migration;

public enum JournalRecoveryAction {
    START,
    RESUME,
    VERIFY_OR_ROLLBACK,
    ROLLBACK,
    COMPLETE
}
