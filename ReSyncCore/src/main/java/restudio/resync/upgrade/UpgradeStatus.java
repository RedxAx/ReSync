package restudio.resync.upgrade;

public enum UpgradeStatus {
    READY,
    AWAITING_QUARANTINE_ACCEPTANCE,
    FAILED,
    APPLIED,
    ALREADY_COMMITTED
}
