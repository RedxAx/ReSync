package restudio.resync.flow.inspector;

import restudio.resync.flow.diagnostic.Diagnostic;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public final class InspectorContractException extends IllegalArgumentException {
    private final List<Diagnostic> diagnostics;

    public InspectorContractException(List<Diagnostic> diagnostics) {
        super(message(diagnostics));
        this.diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
    }

    public List<Diagnostic> diagnostics() {
        return diagnostics;
    }

    private static String message(List<Diagnostic> diagnostics) {
        return Objects.requireNonNull(diagnostics, "diagnostics").stream()
            .map(diagnostic -> diagnostic.code() + ": " + diagnostic.message())
            .collect(Collectors.joining("; "));
    }
}
