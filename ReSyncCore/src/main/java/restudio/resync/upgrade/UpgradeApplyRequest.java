package restudio.resync.upgrade;

import java.util.Objects;

import restudio.resync.migration.MigrationRequest;

public record UpgradeApplyRequest(UpgradeDryRunResult dryRun, MigrationRequest migration) {
    public UpgradeApplyRequest {
        dryRun = Objects.requireNonNull(dryRun, "dryRun");
        migration = Objects.requireNonNull(migration, "migration");
    }
}
