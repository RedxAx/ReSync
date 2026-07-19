package restudio.resync.flow.validation;

public record FlowGraphDiagnostic(Severity severity, String code, String graphId, String nodeId, String pin, String message, String remediation) {
    public FlowGraphDiagnostic {
        severity = severity != null ? severity : Severity.ERROR;
        code = code != null ? code : "UNKNOWN";
        graphId = graphId != null ? graphId : "";
        nodeId = nodeId != null ? nodeId : "";
        pin = pin != null ? pin : "";
        message = message != null ? message : "";
        remediation = remediation != null ? remediation : "";
    }

    public enum Severity {
        INFO,
        WARNING,
        ERROR
    }
}
