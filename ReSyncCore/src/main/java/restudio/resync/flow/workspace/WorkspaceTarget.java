package restudio.resync.flow.workspace;

public record WorkspaceTarget(String resourceType, String resourceId) {
    public WorkspaceTarget {
        resourceType = safe(resourceType);
        resourceId = safe(resourceId);
    }

    public String key() {
        return resourceType + '\u0000' + resourceId;
    }

    private static String safe(String value) {
        return value != null ? value.trim() : "";
    }
}
