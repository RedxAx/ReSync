package restudio.resync.upgrade;

import java.util.List;
import java.util.Objects;

import restudio.resync.flow.diagnostic.DiagnosticSet;
import restudio.resync.flow.diagnostic.DiagnosticSeverity;
import restudio.resync.migration.MigrationPlan;
import restudio.resync.migration.QuarantineReport;

public record UpgradeProposal(MigrationPlan plan, QuarantineReport quarantineReport, DiagnosticSet diagnostics) {
    public UpgradeProposal {
        plan = Objects.requireNonNull(plan, "plan");
        quarantineReport = Objects.requireNonNull(quarantineReport, "quarantineReport");
        if (!plan.quarantineReportHash().equals(quarantineReport.reportHash())) {
            throw new IllegalArgumentException("Migration Plan Does Not Match Quarantine Report");
        }
        diagnostics = diagnostics == null ? new DiagnosticSet(List.of()) : diagnostics;
    }

    public boolean hasErrors() {
        return diagnostics.diagnostics().stream().anyMatch(diagnostic -> diagnostic.severity() == DiagnosticSeverity.ERROR);
    }
}
