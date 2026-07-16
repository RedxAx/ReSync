package restudio.resync.network;

public enum NetworkTransferStatus {
    INTENT,
    SOURCE_LEASED,
    SNAPSHOT_COMMITTED,
    TARGET_READY,
    CONNECTED,
    APPLIED,
    COMMITTED,
    ABORTED,
    TIMED_OUT
}
