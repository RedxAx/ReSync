package restudio.resync.flow.validation;

public record ValidationBudget(int maxValidators, int maxDiagnostics, int maxDiagnosticsPerValidator) {
    public ValidationBudget {
        if (maxValidators < 1) {
            throw new IllegalArgumentException("maxValidators must be positive");
        }
        if (maxDiagnostics < 1) {
            throw new IllegalArgumentException("maxDiagnostics must be positive");
        }
        if (maxDiagnosticsPerValidator < 1 || maxDiagnosticsPerValidator > maxDiagnostics) {
            throw new IllegalArgumentException("maxDiagnosticsPerValidator must be between one and maxDiagnostics");
        }
    }

    public ValidationBudget(int maxValidators, int maxDiagnostics) {
        this(maxValidators, maxDiagnostics, maxDiagnostics);
    }

    public static ValidationBudget defaults() {
        return new ValidationBudget(4096, 8192, 512);
    }
}
