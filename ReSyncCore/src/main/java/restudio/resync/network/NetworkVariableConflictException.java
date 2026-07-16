package restudio.resync.network;

public class NetworkVariableConflictException extends NetworkStoreException {
    private final long expectedRevision;
    private final long currentRevision;

    public NetworkVariableConflictException(long expectedRevision, long currentRevision) {
        super("Network Variable Revision Changed From " + expectedRevision + " To " + currentRevision);
        this.expectedRevision = expectedRevision;
        this.currentRevision = currentRevision;
    }

    public long expectedRevision() {
        return expectedRevision;
    }

    public long currentRevision() {
        return currentRevision;
    }
}
