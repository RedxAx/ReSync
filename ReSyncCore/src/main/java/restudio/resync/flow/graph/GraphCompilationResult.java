package restudio.resync.flow.graph;

import java.util.Objects;

public record GraphCompilationResult(CompiledExecutionPlan plan, ValidationResult validation) {
    public GraphCompilationResult {
        validation = Objects.requireNonNull(validation, "validation");
        if (!validation.valid() && plan != null) {
            throw new IllegalArgumentException("Invalid compilation result cannot contain a plan");
        }
    }

    public boolean compiled() {
        return plan != null && validation.valid();
    }
}
