package restudio.resync.upgrade;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import restudio.resync.flow.diagnostic.DiagnosticSet;
import restudio.resync.flow.diagnostic.DiagnosticSeverity;
import restudio.resync.migration.PreflightResult;
import restudio.resync.migration.Snapshot;

public record UpgradeDryRunResult(
    UpgradeStatus status,
    Optional<Snapshot> sourceSnapshot,
    Optional<UpgradeProposal> proposal,
    UpgradeSourceWindow sourceWindow,
    PreflightResult preflight,
    DiagnosticSet diagnostics
) {
    public UpgradeDryRunResult {
        status = Objects.requireNonNull(status, "status");
        sourceSnapshot = sourceSnapshot == null ? Optional.empty() : sourceSnapshot;
        sourceSnapshot.ifPresent(snapshot -> {
            if (!snapshot.verified()) {
                throw new IllegalArgumentException("Dry-Run Snapshot Must Be Verified");
            }
            if (!snapshot.manifest().manifestHash().equals(snapshot.verification().manifestHash())) {
                throw new IllegalArgumentException("Dry-Run Snapshot Verification Hash Does Not Match Manifest");
            }
        });
        proposal = proposal == null ? Optional.empty() : proposal;
        sourceWindow = Objects.requireNonNull(sourceWindow, "sourceWindow");
        preflight = Objects.requireNonNull(preflight, "preflight");
        diagnostics = diagnostics == null ? new DiagnosticSet(List.of()) : diagnostics;
    }

    public boolean canApply() {
        return (status == UpgradeStatus.READY || status == UpgradeStatus.AWAITING_QUARANTINE_ACCEPTANCE)
            && sourceSnapshot.isPresent()
            && sourceSnapshot.get().verified()
            && proposal.isPresent()
            && !hasErrors();
    }

    public boolean hasErrors() {
        return diagnostics.diagnostics().stream().anyMatch(diagnostic -> diagnostic.severity() == DiagnosticSeverity.ERROR);
    }
}
