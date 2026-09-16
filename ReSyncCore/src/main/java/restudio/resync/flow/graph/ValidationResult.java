package restudio.resync.flow.graph;

import restudio.resync.flow.diagnostic.Diagnostic;
import restudio.resync.flow.diagnostic.DiagnosticSeverity;

import java.util.List;
import java.util.ArrayList;

public final class ValidationResult {
    private final List<Diagnostic> diagnostics;

    private ValidationResult(List<Diagnostic> diagnostics) {
        this.diagnostics = List.copyOf(diagnostics);
    }

    public static ValidationResult success() {
        return new ValidationResult(List.of());
    }

    public static ValidationResult of(List<Diagnostic> diagnostics) {
        return new ValidationResult(diagnostics != null ? diagnostics : List.of());
    }

    public boolean valid() {
        return diagnostics.stream().noneMatch(diagnostic -> diagnostic.severity() == DiagnosticSeverity.ERROR);
    }

    public boolean isValid() {
        return valid();
    }

    public List<Diagnostic> diagnostics() {
        return diagnostics;
    }

    public long errorCount() {
        return diagnostics.stream().filter(diagnostic -> diagnostic.severity() == DiagnosticSeverity.ERROR).count();
    }

    public ValidationResult plus(ValidationResult other) {
        ArrayList<Diagnostic> merged = new ArrayList<>(diagnostics);
        if (other != null) {
            merged.addAll(other.diagnostics);
        }
        return new ValidationResult(merged);
    }
}
