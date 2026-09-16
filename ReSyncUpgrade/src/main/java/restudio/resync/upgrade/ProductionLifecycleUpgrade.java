package restudio.resync.upgrade;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import restudio.resync.migration.MigrationPlan;
import restudio.resync.migration.MigrationStager;
import restudio.resync.migration.QuarantineAcceptance;
import restudio.resync.migration.QuarantineReport;
import restudio.resync.migration.Snapshot;
import restudio.resync.migration.StagedMigration;

public final class ProductionLifecycleUpgrade implements UpgradePlanner, MigrationStager {
    private final ReSyncTypedLifecycleUpgrade lifecycle;
    private final Path invocationPath;
    private final String invocationHash;

    public ProductionLifecycleUpgrade(Path invocationPath, String invocationHash) {
        this.lifecycle = ReSyncTypedLifecycleUpgrade.production();
        this.invocationPath = Objects.requireNonNull(invocationPath, "invocationPath").toAbsolutePath().normalize();
        this.invocationHash = requireDigest(invocationHash);
    }

    public List<TypedLifecycleMigrationAdapter> adapters() {
        return lifecycle.adapters();
    }

    public Map<String, TypedLifecycleMigrationAdapter.Adaptation> authoritativeAdaptations(Snapshot snapshot) throws IOException {
        return lifecycle.authoritativeAdaptations(snapshot);
    }

    @Override
    public UpgradeProposal plan(Snapshot sourceSnapshot, UpgradeSourceWindow sourceWindow) throws IOException {
        UpgradeProposal proposal = lifecycle.plan(sourceSnapshot, sourceWindow);
        return new UpgradeProposal(
            proposal.plan().withInvocationHash(invocationHash),
            proposal.quarantineReport(),
            proposal.diagnostics());
    }

    @Override
    public StagedMigration stage(Path sourceRoot, Path stagingRoot, MigrationPlan plan) throws IOException {
        InvocationBinding.verifyDigest(invocationPath, invocationHash);
        return lifecycle.stage(sourceRoot, stagingRoot, plan);
    }

    @Override
    public StagedMigration stage(
        Path sourceRoot,
        Path stagingRoot,
        MigrationPlan plan,
        QuarantineReport report,
        QuarantineAcceptance acceptance
    ) throws IOException {
        InvocationBinding.verifyDigest(invocationPath, invocationHash);
        return lifecycle.stage(sourceRoot, stagingRoot, plan, report, acceptance);
    }

    private static String requireDigest(String value) {
        if (value == null || !value.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException("invocationHash Must Be A SHA-256 Digest");
        }
        return value.toLowerCase(Locale.ROOT);
    }
}
