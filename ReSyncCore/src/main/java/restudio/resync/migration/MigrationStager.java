package restudio.resync.migration;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

@FunctionalInterface
public interface MigrationStager {
    StagedMigration stage(Path sourceRoot, Path stagingRoot, MigrationPlan plan) throws IOException;

    default StagedMigration stage(
        Path sourceRoot,
        Path stagingRoot,
        MigrationPlan plan,
        QuarantineReport quarantineReport,
        QuarantineAcceptance quarantineAcceptance
    ) throws IOException {
        Objects.requireNonNull(quarantineReport, "quarantineReport");
        Objects.requireNonNull(quarantineAcceptance, "quarantineAcceptance");
        quarantineReport.requireAccepted(quarantineAcceptance);
        return stage(sourceRoot, stagingRoot, plan);
    }
}
