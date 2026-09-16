package restudio.resync.restore;

import restudio.resync.flow.diagnostic.DiagnosticSet;
import restudio.resync.migration.MigrationException;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

public record RestorePreflight(boolean passed, List<Check> checks, DiagnosticSet diagnostics) {
    public record Check(String name, boolean passed, String detail) {
        public Check {
            name = requireText(name, "check name");
            detail = requireText(detail, "check detail");
        }

        private static String requireText(String value, String field) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(field + " Is Required");
            }
            return value;
        }
    }

    public RestorePreflight {
        checks = checks == null ? List.of() : List.copyOf(checks);
        diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        if (passed != checks.stream().allMatch(Check::passed)) {
            throw new IllegalArgumentException("Restore Preflight Result Does Not Match Its Checks");
        }
    }

    public void requirePassed() throws IOException {
        if (!passed) {
            String detail = checks.stream().filter(check -> !check.passed()).map(check -> check.name() + ": " + check.detail()).reduce((left, right) -> left + "; " + right).orElse("Unknown Restore Preflight Failure");
            throw new MigrationException("Restore Preflight Failed: " + detail);
        }
    }
}
