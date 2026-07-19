package restudio.resync.flow.handler;

import java.util.Map;

public final class FlowHandlerException extends RuntimeException {
    private final String code;
    private final String remediation;
    private final Map<String, Object> details;

    public FlowHandlerException(String code, String message, String remediation) {
        this(code, message, remediation, Map.of());
    }

    public FlowHandlerException(String code, String message, String remediation, Map<String, Object> details) {
        this(code, message, remediation, details, null);
    }

    public FlowHandlerException(String code, String message, String remediation, Map<String, Object> details, Throwable cause) {
        super(message, cause);
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("Flow handler error code is required");
        }
        this.code = code;
        this.remediation = remediation != null ? remediation : "";
        this.details = details != null ? Map.copyOf(details) : Map.of();
    }

    public String getCode() {
        return code;
    }

    public String getRemediation() {
        return remediation;
    }

    public Map<String, Object> getDetails() {
        return details;
    }
}
