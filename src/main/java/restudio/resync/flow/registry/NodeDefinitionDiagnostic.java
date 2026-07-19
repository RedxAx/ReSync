package restudio.resync.flow.registry;

import java.util.LinkedHashMap;
import java.util.Map;

public record NodeDefinitionDiagnostic(Severity severity, String code, String source, int index, String nodeId, String message) {
    public NodeDefinitionDiagnostic {
        severity = severity != null ? severity : Severity.ERROR;
        code = code != null ? code : "UNKNOWN";
        source = source != null ? source : "unknown";
        nodeId = nodeId != null ? nodeId : "";
        message = message != null ? message : "";
    }

    public Map<String, Object> toMap() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("severity", severity.name());
        value.put("code", code);
        value.put("source", source);
        value.put("index", index);
        value.put("nodeId", nodeId);
        value.put("message", message);
        return value;
    }

    public enum Severity {
        INFO,
        WARNING,
        ERROR
    }
}
