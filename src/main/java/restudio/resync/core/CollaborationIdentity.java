package restudio.resync.core;

public record CollaborationIdentity(String subjectId, String displayName, String avatar, String source) {
    public CollaborationIdentity {
        subjectId = safe(subjectId);
        displayName = safe(displayName);
        avatar = safe(avatar);
        source = safe(source);
    }

    public static CollaborationIdentity client(String clientId) {
        String value = safe(clientId);
        return new CollaborationIdentity(value, value.isBlank() ? "Collaborator" : value, "", "client");
    }

    private static String safe(String value) {
        return value != null ? value.trim() : "";
    }
}
