package restudio.resync.migration;

import java.io.IOException;
import java.util.List;

public record PreflightResult(boolean passed, List<Check> checks) {
    public PreflightResult {
        checks = List.copyOf(checks);
    }

    public record Check(String name, boolean passed, String detail) {
        public Check {
            name = MigrationCanonical.requireText(name, "check name");
            detail = MigrationCanonical.requireText(detail, "check detail");
        }
    }

    public void requirePassed() throws IOException {
        if (!passed) {
            String detail = checks.stream()
                    .filter(check -> !check.passed())
                    .map(check -> check.name() + ": " + check.detail())
                    .sorted()
                    .reduce((first, second) -> first + "; " + second)
                    .orElse("Migration Preflight Failed");
            throw new MigrationException(detail);
        }
    }
}
