package restudio.resync.contract.diagnostic;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

public final class DiagnosticCodeCatalogFiles {
    private DiagnosticCodeCatalogFiles() {
    }

    public static DiagnosticCodeCatalog fromPath(Path path) throws IOException {
        Objects.requireNonNull(path, "Diagnostic catalog path is required");
        return DiagnosticCodeCatalog.fromBytes(Files.readAllBytes(path));
    }

    public static DiagnosticCodeCatalog installConfiguredDefault() {
        String configured = System.getProperty("resync.diagnostic.catalog");
        if (configured == null || configured.isBlank()) {
            return DiagnosticCodeCatalog.defaultCatalog();
        }
        Path path = Path.of(configured);
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException("Configured diagnostic catalog is not a regular file: " + path);
        }
        try {
            DiagnosticCodeCatalog catalog = fromPath(path);
            DiagnosticCodeCatalog.installDefault(catalog);
            return catalog;
        } catch (IOException exception) {
            throw new IllegalStateException("Configured diagnostic catalog cannot be read: " + path, exception);
        }
    }
}
