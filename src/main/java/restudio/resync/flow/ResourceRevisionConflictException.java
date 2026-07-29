package restudio.resync.flow;

public final class ResourceRevisionConflictException extends IllegalStateException {
    private final String resourceId;
    private final long expectedRevision;
    private final long currentRevision;

    public ResourceRevisionConflictException(String resourceId, long expectedRevision, long currentRevision) {
        super("Resource changed since it was opened: " + resourceId + " (expected " + expectedRevision + ", current " + currentRevision + ")");
        this.resourceId = resourceId;
        this.expectedRevision = expectedRevision;
        this.currentRevision = currentRevision;
    }

    public String getResourceId() {
        return resourceId;
    }

    public long getExpectedRevision() {
        return expectedRevision;
    }

    public long getCurrentRevision() {
        return currentRevision;
    }
}
