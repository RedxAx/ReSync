package restudio.resync.flow.graph;

import java.util.List;

public final class GraphCompilationException extends IllegalArgumentException {
    private final ValidationResult validation;

    public GraphCompilationException(ValidationResult validation) {
        super("Graph compilation failed with " + (validation != null ? validation.diagnostics().size() : 0) + " diagnostic(s)");
        this.validation = validation != null ? validation : ValidationResult.of(List.of());
    }

    public ValidationResult validation() {
        return validation;
    }
}
