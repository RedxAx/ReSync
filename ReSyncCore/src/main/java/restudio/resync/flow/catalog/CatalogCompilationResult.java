package restudio.resync.flow.catalog;

import restudio.resync.flow.diagnostic.Diagnostic;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class CatalogCompilationResult {
    private final CatalogSnapshot snapshot;
    private final List<Diagnostic> diagnostics;

    private CatalogCompilationResult(CatalogSnapshot snapshot, List<Diagnostic> diagnostics) {
        this.snapshot = snapshot;
        this.diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }

    public static CatalogCompilationResult accepted(CatalogSnapshot snapshot, List<Diagnostic> diagnostics) {
        return new CatalogCompilationResult(Objects.requireNonNull(snapshot, "snapshot"), diagnostics);
    }

    public static CatalogCompilationResult rejected(List<Diagnostic> diagnostics) {
        return new CatalogCompilationResult(null, diagnostics);
    }

    public boolean accepted() {
        return snapshot != null;
    }

    public boolean isAccepted() {
        return accepted();
    }

    public Optional<CatalogSnapshot> snapshot() {
        return Optional.ofNullable(snapshot);
    }

    public List<Diagnostic> diagnostics() {
        return diagnostics;
    }
}
