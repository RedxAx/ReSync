package restudio.resync.flow.contract;

public record EditorDiagnostic(Severity severity, String code, String nodeId, String field, String path, String message, String remediation) {
    public EditorDiagnostic {
        severity = severity != null ? severity : Severity.ERROR;
        code = safe(code);
        nodeId = safe(nodeId);
        field = safe(field);
        path = safe(path);
        message = safe(message);
        remediation = safe(remediation);
    }

    private static String safe(String value) {
        return value != null ? value : "";
    }

    public enum Severity {
        INFO,
        WARNING,
        ERROR
    }
}
