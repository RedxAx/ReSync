package restudio.resync.network;

public class NetworkStoreException extends RuntimeException {
    public NetworkStoreException(String message, Throwable cause) {
        super(message, cause);
    }

    public NetworkStoreException(String message) {
        super(message);
    }
}
