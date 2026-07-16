package restudio.resync.network;

public enum NetworkPlayerRouteStatus {
    SUCCESS,
    ALREADY_CONNECTED,
    CONNECTION_IN_PROGRESS,
    CONNECTION_CANCELLED,
    SERVER_DISCONNECTED;

    public boolean successful() {
        return this == SUCCESS || this == ALREADY_CONNECTED;
    }
}
