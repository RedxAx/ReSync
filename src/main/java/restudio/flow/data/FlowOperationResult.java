package restudio.flow.data;

import java.util.Map;

public record FlowOperationResult<T>(boolean success, T value, String errorCode, String message, Map<String, Object> details) {
    public FlowOperationResult {
        errorCode = errorCode != null ? errorCode : "";
        message = message != null ? message : "";
        details = details != null ? Map.copyOf(details) : Map.of();
    }

    public static <T> FlowOperationResult<T> success(T value) {
        return new FlowOperationResult<>(true, value, "", "", Map.of());
    }

    public static <T> FlowOperationResult<T> failure(String errorCode, String message, Map<String, Object> details) {
        return new FlowOperationResult<>(false, null, errorCode, message, details);
    }
}
