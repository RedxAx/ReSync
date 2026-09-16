package restudio.resync.migration;

import java.nio.file.Path;
import java.util.Objects;

public record MigrationRequest(Path sourceRoot, Path snapshotStagingRoot, SnapshotMetadata snapshotMetadata, Path migrationStagingRoot, MigrationPlan plan, MigrationJournal journal, MigrationStager stager, StagedMigrationValidator validator, MigrationActivator activator, MigrationRollback rollback, ActivatedMigrationVerifier verifier, QuarantineReport quarantineReport, QuarantineAcceptance quarantineAcceptance, long reservedBytes, ActivationMarkerWriter activationMarkerWriter) {
    public MigrationRequest(Path sourceRoot, Path snapshotStagingRoot, SnapshotMetadata snapshotMetadata, Path migrationStagingRoot, MigrationPlan plan, MigrationJournal journal, MigrationStager stager, StagedMigrationValidator validator, MigrationActivator activator, MigrationRollback rollback, ActivatedMigrationVerifier verifier, QuarantineReport quarantineReport, QuarantineAcceptance quarantineAcceptance, long reservedBytes) {
        this(sourceRoot, snapshotStagingRoot, snapshotMetadata, migrationStagingRoot, plan, journal, stager, validator, activator, rollback, verifier, quarantineReport, quarantineAcceptance, reservedBytes, ActivationMarkerWriter.none());
    }

    public MigrationRequest {
        sourceRoot = Objects.requireNonNull(sourceRoot, "sourceRoot");
        snapshotStagingRoot = Objects.requireNonNull(snapshotStagingRoot, "snapshotStagingRoot");
        snapshotMetadata = Objects.requireNonNull(snapshotMetadata, "snapshotMetadata");
        migrationStagingRoot = Objects.requireNonNull(migrationStagingRoot, "migrationStagingRoot");
        plan = Objects.requireNonNull(plan, "plan");
        journal = Objects.requireNonNull(journal, "journal");
        stager = Objects.requireNonNull(stager, "stager");
        validator = Objects.requireNonNull(validator, "validator");
        activator = Objects.requireNonNull(activator, "activator");
        rollback = Objects.requireNonNull(rollback, "rollback");
        quarantineReport = Objects.requireNonNull(quarantineReport, "quarantineReport");
        quarantineAcceptance = Objects.requireNonNull(quarantineAcceptance, "quarantineAcceptance");
        activationMarkerWriter = activationMarkerWriter == null ? ActivationMarkerWriter.none() : activationMarkerWriter;
        if (!plan.quarantineReportHash().equals(quarantineReport.reportHash())) {
            throw new IllegalArgumentException("Migration Plan Does Not Match Quarantine Report");
        }
        if (reservedBytes < 0) {
            throw new IllegalArgumentException("reservedBytes Must Be Non-Negative");
        }
    }

    public MigrationRequest withActivationMarker(ActivationMarkerWriter writer) {
        return new MigrationRequest(
            sourceRoot,
            snapshotStagingRoot,
            snapshotMetadata,
            migrationStagingRoot,
            plan,
            journal,
            stager,
            validator,
            activator,
            rollback,
            verifier,
            quarantineReport,
            quarantineAcceptance,
            reservedBytes,
            writer);
    }
}
