package restudio.resync.core;

public enum ConnectionState {
    CONNECTING,
    CONNECTED,
    AUTHENTICATED,
    CLOSING,
    CLOSED,
    TIMED_OUT
}
