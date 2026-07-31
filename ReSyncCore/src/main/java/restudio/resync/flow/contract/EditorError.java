package restudio.resync.flow.contract;

import java.util.List;

public record EditorError(String code, String resourceType, String resourceId, String title, String message, List<EditorDiagnostic> diagnostics) {
    public static final String PREFIX = "EDITOR_ERROR:";

    public EditorError {
        code = safe(code);
        resourceType = safe(resourceType);
        resourceId = safe(resourceId);
        title = safe(title);
        message = safe(message);
        diagnostics = diagnostics != null ? List.copyOf(diagnostics) : List.of();
    }

    private static String safe(String value) {
        return value != null ? value : "";
    }
}
