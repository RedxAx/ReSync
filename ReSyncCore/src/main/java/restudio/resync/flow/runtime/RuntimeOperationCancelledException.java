package restudio.resync.flow.runtime;

public final class RuntimeOperationCancelledException extends RuntimeException {
    public RuntimeOperationCancelledException() {
        super("Runtime Operation Cancelled");
    }

    public RuntimeOperationCancelledException(String message) {
        super(message == null || message.isBlank() ? "Runtime Operation Cancelled" : message);
    }

    public RuntimeOperationCancelledException(String message, Throwable cause) {
        super(message == null || message.isBlank() ? "Runtime Operation Cancelled" : message, cause);
    }
}
