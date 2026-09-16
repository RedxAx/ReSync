package restudio.resync.upgrade;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import restudio.resync.flow.diagnostic.DiagnosticSet;
import restudio.resync.migration.MigrationResult;

public record UpgradeApplyResult(
    UpgradeStatus status,
    Optional<MigrationResult> migration,
    Optional<String> planHash,
    DiagnosticSet diagnostics,
    boolean changed
) {
    public UpgradeApplyResult {
        status = Objects.requireNonNull(status, "status");
        migration = migration == null ? Optional.empty() : migration;
        planHash = planHash == null ? Optional.empty() : planHash;
        diagnostics = diagnostics == null ? new DiagnosticSet(List.of()) : diagnostics;
    }
}
