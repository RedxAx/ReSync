package restudio.resync.network;

public class NetworkResourceConflictException extends NetworkStoreException {
    public NetworkResourceConflictException(long expectedRevision, long actualRevision) {
        super("Network Resource Revision Conflict: Expected " + expectedRevision + " But Found " + actualRevision);
    }
}
