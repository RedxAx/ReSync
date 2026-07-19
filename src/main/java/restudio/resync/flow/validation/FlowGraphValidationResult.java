package restudio.resync.flow.validation;

import java.util.List;

public record FlowGraphValidationResult(List<FlowGraphDiagnostic> diagnostics) {
    public FlowGraphValidationResult {
        diagnostics = diagnostics != null ? List.copyOf(diagnostics) : List.of();
    }

    public boolean valid() {
        return diagnostics.stream().noneMatch(diagnostic -> diagnostic.severity() == FlowGraphDiagnostic.Severity.ERROR);
    }

    public List<FlowGraphDiagnostic> errors() {
        return diagnostics.stream().filter(diagnostic -> diagnostic.severity() == FlowGraphDiagnostic.Severity.ERROR).toList();
    }

    public String summary() {
        return errors().stream().limit(3).map(diagnostic -> diagnostic.code() + ": " + diagnostic.message()).reduce((left, right) -> left + "; " + right).orElse("Valid");
    }
}
