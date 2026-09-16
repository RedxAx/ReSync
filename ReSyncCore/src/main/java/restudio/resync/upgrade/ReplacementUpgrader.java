package restudio.resync.upgrade;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.diagnostic.Diagnostic;
import restudio.resync.flow.diagnostic.DiagnosticPhase;
import restudio.resync.flow.diagnostic.DiagnosticProvenance;
import restudio.resync.flow.diagnostic.DiagnosticRedaction;
import restudio.resync.flow.diagnostic.DiagnosticSet;
import restudio.resync.flow.diagnostic.DiagnosticSeverity;
import restudio.resync.flow.diagnostic.DiagnosticSourceKind;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.OperationId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.migration.MigrationCoordinator;
import restudio.resync.migration.MigrationJournalState;
import restudio.resync.migration.MigrationPlan;
import restudio.resync.migration.MigrationRequest;
import restudio.resync.migration.MigrationResult;
import restudio.resync.migration.PreflightResult;
import restudio.resync.migration.Snapshot;
import restudio.resync.migration.SnapshotService;
import restudio.resync.migration.SnapshotVerification;

public final class ReplacementUpgrader {
    public static final UpgradeVersion VERSION = UpgradeVersion.current();

    private static final ContractRef<OperationId> MESSAGE_KEY = ContractRef.of(OwnerId.of("restudio.resync"), OperationId.of("upgrade"));
    private static final String ZERO_HASH = "0".repeat(64);

    private final SnapshotService snapshots;
    private final MigrationCoordinator coordinator;

    public ReplacementUpgrader(SnapshotService snapshots, MigrationCoordinator coordinator) {
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
    }

    public UpgradeDryRunResult dryRun(UpgradeDryRunRequest request) {
        Objects.requireNonNull(request, "request");
        List<Diagnostic> diagnostics = new ArrayList<>();
        if (!VERSION.equals(request.sourceWindow().upgraderVersion())) {
            diagnostics.add(diagnostic(
                "UPGRADE.VERSION_UNSUPPORTED",
                DiagnosticSeverity.ERROR,
                DiagnosticPhase.CAPABILITY,
                "The request targets a different standalone upgrader version.",
                "Use the upgrader version that owns this source window.",
                Map.of("requestedVersion", request.sourceWindow().upgraderVersion().value(), "activeVersion", VERSION.value()),
                request.snapshotMetadata().snapshotId(),
                ZERO_HASH,
                request.sourceWindow()));
            return failed(request, preflightFailure("upgrader-version", "Standalone Upgrader Version Is Not Supported"), diagnostics);
        }
        if (!request.sourceWindow().supports(request.snapshotMetadata())) {
            diagnostics.add(diagnostic(
                "UPGRADE.SOURCE_UNSUPPORTED",
                DiagnosticSeverity.ERROR,
                DiagnosticPhase.ENVIRONMENT,
                "The source snapshot is outside the supported upgrade window.",
                "Use a supported offline source release.",
                Map.of("sourceBuild", request.snapshotMetadata().build(), "sourceFormat", request.snapshotMetadata().formatVersion()),
                request.snapshotMetadata().snapshotId(),
                ZERO_HASH,
                request.sourceWindow()));
            return failed(request, preflightFailure("source-window", "Snapshot Is Outside The Supported Upgrade Window"), diagnostics);
        }

        PreflightResult preflight = snapshots.preflight(
            request.sourceRoot(),
            request.snapshotStagingRoot(),
            request.participants(),
            request.reservedBytes());
        if (!preflight.passed()) {
            diagnostics.add(diagnostic(
                "SNAPSHOT.PREFLIGHT_REJECTED",
                DiagnosticSeverity.ERROR,
                DiagnosticPhase.ENVIRONMENT,
                "The source snapshot cannot be read or staged safely.",
                "Resolve every failed preflight check before applying the upgrade.",
                Map.of("checks", preflight.checks().stream().map(check -> check.name() + "=" + check.detail()).toList()),
                request.snapshotMetadata().snapshotId(),
                ZERO_HASH,
                request.sourceWindow()));
            return failed(request, preflight, diagnostics);
        }

        Snapshot sourceSnapshot;
        try {
            sourceSnapshot = snapshots.create(request.sourceRoot(), request.snapshotStagingRoot(), request.snapshotMetadata(), request.participants());
            sourceSnapshot.verification().requireVerified();
        } catch (IOException | RuntimeException exception) {
            diagnostics.add(diagnostic(
                "SNAPSHOT.CREATION_FAILED",
                DiagnosticSeverity.ERROR,
                DiagnosticPhase.ENVIRONMENT,
                "The fenced source snapshot could not be created and verified.",
                "Fix the source tree, participant state, or staging root before retrying.",
                Map.of("reason", message(exception)),
                request.snapshotMetadata().snapshotId(),
                ZERO_HASH,
                request.sourceWindow()));
            return new UpgradeDryRunResult(UpgradeStatus.FAILED, Optional.empty(), Optional.empty(), request.sourceWindow(), preflight, new DiagnosticSet(diagnostics));
        }

        UpgradeProposal first;
        UpgradeProposal second;
        try {
            first = Objects.requireNonNull(request.planner().plan(sourceSnapshot, request.sourceWindow()), "planner result");
            second = Objects.requireNonNull(request.planner().plan(sourceSnapshot, request.sourceWindow()), "planner result");
        } catch (IOException | RuntimeException exception) {
            diagnostics.add(diagnostic(
                "MIGRATION.PLAN_INVALID",
                DiagnosticSeverity.ERROR,
                DiagnosticPhase.SEMANTIC,
                "The upgrade planner did not produce a valid migration proposal.",
                "Repair the versioned upgrade rules and retry the dry run.",
                Map.of("reason", message(exception)),
                request.snapshotMetadata().snapshotId(),
                sourceSnapshot.manifest().manifestHash(),
                request.sourceWindow()));
            return new UpgradeDryRunResult(UpgradeStatus.FAILED, Optional.of(sourceSnapshot), Optional.empty(), request.sourceWindow(), preflight, new DiagnosticSet(diagnostics));
        }

        diagnostics.addAll(first.diagnostics().diagnostics());
        if (!sameProposal(first, second)) {
            diagnostics.add(diagnostic(
                "MIGRATION.ADAPTER_NONDETERMINISTIC",
                DiagnosticSeverity.ERROR,
                DiagnosticPhase.SEMANTIC,
                "The same source snapshot produced different upgrade plans.",
                "Make every upgrade rule deterministic and independent of time, order, and runtime state.",
                Map.of("firstPlanHash", first.plan().planHash(), "secondPlanHash", second.plan().planHash()),
                request.snapshotMetadata().snapshotId(),
                sourceSnapshot.manifest().manifestHash(),
                request.sourceWindow()));
        }
        validateProposal(first, sourceSnapshot, request, diagnostics);
        if (first.hasErrors()) {
            return new UpgradeDryRunResult(UpgradeStatus.FAILED, Optional.of(sourceSnapshot), Optional.of(first), request.sourceWindow(), preflight, new DiagnosticSet(diagnostics));
        }
        if (!first.quarantineReport().records().isEmpty()) {
            diagnostics.add(diagnostic(
                "MIGRATION.QUARANTINE_UNRESOLVED",
                DiagnosticSeverity.WARNING,
                DiagnosticPhase.SEMANTIC,
                "The dry run contains material that requires explicit quarantine acceptance.",
                "Review every quarantine record and submit an acceptance bound to this report hash before applying.",
                Map.of("reportHash", first.quarantineReport().reportHash(), "records", first.quarantineReport().records().size()),
                request.snapshotMetadata().snapshotId(),
                sourceSnapshot.manifest().manifestHash(),
                request.sourceWindow()));
        }
        DiagnosticSet resultDiagnostics = new DiagnosticSet(diagnostics);
        UpgradeStatus status = resultDiagnostics.diagnostics().stream().anyMatch(diagnostic -> diagnostic.severity() == DiagnosticSeverity.ERROR)
            ? UpgradeStatus.FAILED
            : first.quarantineReport().records().isEmpty() ? UpgradeStatus.READY : UpgradeStatus.AWAITING_QUARANTINE_ACCEPTANCE;
        return new UpgradeDryRunResult(status, Optional.of(sourceSnapshot), Optional.of(first), request.sourceWindow(), preflight, resultDiagnostics);
    }

    public UpgradeApplyResult apply(UpgradeApplyRequest request) {
        Objects.requireNonNull(request, "request");
        UpgradeDryRunResult dryRun = request.dryRun();
        MigrationRequest migration = request.migration();
        Optional<UpgradeProposal> optionalProposal = dryRun.proposal();
        if (!dryRun.canApply() || optionalProposal.isEmpty() || dryRun.sourceSnapshot().isEmpty()) {
            return rejected(dryRun, "MIGRATION.PLAN_INVALID", "The upgrade can only be applied from a successful dry run.", "Run a deterministic dry run and resolve its diagnostics.");
        }

        UpgradeProposal proposal = optionalProposal.get();
        MigrationPlan plan = proposal.plan();
        Snapshot sourceSnapshot = dryRun.sourceSnapshot().get();
        List<Diagnostic> diagnostics = new ArrayList<>(dryRun.diagnostics().diagnostics());
        SnapshotVerification verification;
        try {
            verification = snapshots.verify(sourceSnapshot);
            verification.requireVerified();
        } catch (IOException | RuntimeException exception) {
            diagnostics.add(diagnostic(
                "SNAPSHOT.DRY_RUN_INVALID",
                DiagnosticSeverity.ERROR,
                DiagnosticPhase.ENVIRONMENT,
                "The verified dry-run snapshot changed or is no longer readable.",
                "Create a new dry run and do not modify its retained snapshot before applying.",
                Map.of("reason", message(exception)),
                sourceSnapshot.metadata().snapshotId(),
                sourceSnapshot.manifest().manifestHash(),
                dryRun.sourceWindow()));
            return new UpgradeApplyResult(UpgradeStatus.FAILED, Optional.empty(), Optional.of(plan.planHash()), new DiagnosticSet(diagnostics), false);
        }
        if (!verification.manifestHash().equals(sourceSnapshot.manifest().manifestHash())
            || !plan.canonicalText().equals(migration.plan().canonicalText())
            || !migration.snapshotMetadata().equals(sourceSnapshot.metadata())
            || !dryRun.sourceWindow().supports(migration.snapshotMetadata())
            || plan.sourceFormatVersion() != dryRun.sourceWindow().sourceFormatVersion()
            || plan.targetFormatVersion() != dryRun.sourceWindow().targetFormatVersion()
            || !proposal.quarantineReport().reportHash().equals(migration.quarantineReport().reportHash())
            || !plan.planHash().equals(migration.journal().planHash())
            || !plan.sourceSnapshotId().equals(sourceSnapshot.metadata().snapshotId())
            || !plan.sourceManifestHash().equals(sourceSnapshot.manifest().manifestHash())) {
            diagnostics.add(diagnostic(
                "MIGRATION.PLAN_MISMATCH",
                DiagnosticSeverity.ERROR,
                DiagnosticPhase.SEMANTIC,
                "The apply request does not match the approved dry-run plan.",
                "Create a new apply request from the exact dry-run result and source snapshot.",
                Map.of("planHash", plan.planHash()),
                migration.snapshotMetadata().snapshotId(),
                sourceSnapshot.manifest().manifestHash(),
                dryRun.sourceWindow()));
            return new UpgradeApplyResult(UpgradeStatus.FAILED, Optional.empty(), Optional.of(plan.planHash()), new DiagnosticSet(diagnostics), false);
        }

        try {
            proposal.quarantineReport().requireAccepted(migration.quarantineAcceptance());
        } catch (IOException | RuntimeException exception) {
            diagnostics.add(diagnostic(
                "MIGRATION.QUARANTINE_UNRESOLVED",
                DiagnosticSeverity.ERROR,
                DiagnosticPhase.SEMANTIC,
                "The migration cannot activate without explicit quarantine acceptance.",
                "Accept every quarantine record using the exact report hash from the dry run.",
                Map.of("reportHash", proposal.quarantineReport().reportHash(), "reason", message(exception)),
                migration.snapshotMetadata().snapshotId(),
                plan.sourceManifestHash(),
                dryRun.sourceWindow()));
            return new UpgradeApplyResult(UpgradeStatus.FAILED, Optional.empty(), Optional.of(plan.planHash()), new DiagnosticSet(diagnostics), false);
        }

        Optional<MigrationJournalState> currentState = migration.journal().currentState();
        if (currentState.isPresent() && currentState.get() == MigrationJournalState.COMMITTED) {
            diagnostics.add(diagnostic(
                "MIGRATION.ALREADY_COMMITTED",
                DiagnosticSeverity.INFO,
                DiagnosticPhase.ENVIRONMENT,
                "This migration plan is already committed.",
                "No files were changed.",
                Map.of("planHash", plan.planHash()),
                migration.snapshotMetadata().snapshotId(),
                plan.sourceManifestHash(),
                dryRun.sourceWindow()));
            return new UpgradeApplyResult(UpgradeStatus.ALREADY_COMMITTED, Optional.empty(), Optional.of(plan.planHash()), new DiagnosticSet(diagnostics), false);
        }
        if (currentState.isPresent()) {
            diagnostics.add(diagnostic(
                "MIGRATION.JOURNAL_NOT_FRESH",
                DiagnosticSeverity.ERROR,
                DiagnosticPhase.ENVIRONMENT,
                "The migration journal already contains an unfinished or rolled-back run.",
                "Recover or replace the journal through the explicit migration recovery path before applying.",
                Map.of("state", currentState.get().name()),
                migration.snapshotMetadata().snapshotId(),
                plan.sourceManifestHash(),
                dryRun.sourceWindow()));
            return new UpgradeApplyResult(UpgradeStatus.FAILED, Optional.empty(), Optional.of(plan.planHash()), new DiagnosticSet(diagnostics), false);
        }

        try {
            MigrationResult result = coordinator.execute(migration);
            return new UpgradeApplyResult(UpgradeStatus.APPLIED, Optional.of(result), Optional.of(plan.planHash()), new DiagnosticSet(diagnostics), true);
        } catch (IOException | RuntimeException exception) {
            diagnostics.add(diagnostic(
                "MIGRATION.APPLY_FAILED",
                DiagnosticSeverity.ERROR,
                DiagnosticPhase.ENVIRONMENT,
                "The replacement migration failed before it could commit.",
                "Inspect the journal and restore the retained source snapshot before retrying.",
                Map.of("planHash", plan.planHash(), "reason", message(exception)),
                migration.snapshotMetadata().snapshotId(),
                plan.sourceManifestHash(),
                dryRun.sourceWindow()));
            return new UpgradeApplyResult(UpgradeStatus.FAILED, Optional.empty(), Optional.of(plan.planHash()), new DiagnosticSet(diagnostics), false);
        }
    }

    private static void validateProposal(UpgradeProposal proposal, Snapshot sourceSnapshot, UpgradeDryRunRequest request, List<Diagnostic> diagnostics) {
        MigrationPlan plan = proposal.plan();
        if (!plan.sourceSnapshotId().equals(request.snapshotMetadata().snapshotId())
            || !plan.sourceManifestHash().equals(sourceSnapshot.manifest().manifestHash())
            || plan.sourceFormatVersion() != request.sourceWindow().sourceFormatVersion()
            || plan.targetFormatVersion() != request.sourceWindow().targetFormatVersion()) {
            diagnostics.add(diagnostic(
                "MIGRATION.PLAN_INVALID",
                DiagnosticSeverity.ERROR,
                DiagnosticPhase.SEMANTIC,
                "The migration plan does not describe this source snapshot and upgrade window.",
                "Regenerate the plan from the exact source manifest and supported version window.",
                Map.of("planHash", plan.planHash(), "sourceManifestHash", sourceSnapshot.manifest().manifestHash()),
                request.snapshotMetadata().snapshotId(),
                sourceSnapshot.manifest().manifestHash(),
                request.sourceWindow()));
        }
    }

    private static boolean sameProposal(UpgradeProposal first, UpgradeProposal second) {
        return first.plan().canonicalText().equals(second.plan().canonicalText())
            && first.quarantineReport().canonicalText().equals(second.quarantineReport().canonicalText())
            && first.diagnostics().toJson().equals(second.diagnostics().toJson());
    }

    private static UpgradeDryRunResult failed(UpgradeDryRunRequest request, PreflightResult preflight, List<Diagnostic> diagnostics) {
        return new UpgradeDryRunResult(UpgradeStatus.FAILED, Optional.empty(), Optional.empty(), request.sourceWindow(), preflight, new DiagnosticSet(diagnostics));
    }

    private static PreflightResult preflightFailure(String name, String detail) {
        return new PreflightResult(false, List.of(new PreflightResult.Check(name, false, detail)));
    }

    private static UpgradeApplyResult rejected(UpgradeDryRunResult dryRun, String code, String message, String remediation) {
        String snapshotId = dryRun.sourceSnapshot().map(snapshot -> snapshot.metadata().snapshotId()).orElse("unknown");
        String sourceHash = dryRun.sourceSnapshot().map(snapshot -> snapshot.manifest().manifestHash()).orElse(ZERO_HASH);
        Diagnostic diagnostic = diagnostic(code, DiagnosticSeverity.ERROR, DiagnosticPhase.SEMANTIC, message, remediation, Map.of(), snapshotId, sourceHash, dryRun.sourceWindow());
        List<Diagnostic> diagnostics = new ArrayList<>(dryRun.diagnostics().diagnostics());
        diagnostics.add(diagnostic);
        return new UpgradeApplyResult(UpgradeStatus.FAILED, Optional.empty(), dryRun.proposal().map(proposal -> proposal.plan().planHash()), new DiagnosticSet(diagnostics), false);
    }

    private static Diagnostic diagnostic(String code, DiagnosticSeverity severity, DiagnosticPhase phase, String message, String remediation, Map<String, ?> evidence, String snapshotId, String sourceHash, UpgradeSourceWindow window) {
        String normalizedHash = sourceHash == null || !sourceHash.matches("[0-9a-fA-F]{64}") ? ZERO_HASH : sourceHash.toLowerCase(Locale.ROOT);
        String normalizedSnapshotId = snapshotId == null || snapshotId.isBlank() ? "unknown" : snapshotId;
        String evidenceHash = CanonicalJson.sha256("resync.upgrade.diagnostic-evidence", evidence);
        UUID correlation = UUID.nameUUIDFromBytes((code + "\n" + normalizedHash + "\n" + evidenceHash).getBytes(StandardCharsets.UTF_8));
        DiagnosticProvenance provenance = new DiagnosticProvenance(
            OwnerId.of("restudio.resync"),
            DiagnosticSourceKind.LOCAL,
            "snapshot://" + normalizedSnapshotId,
            normalizedHash,
            window.sourceBuild(),
            window.upgraderVersion().value(),
            null);
        return Diagnostic.builder(code, severity, phase, "standalone-upgrader")
            .messageKey(MESSAGE_KEY)
            .message(message)
            .evidence(evidence)
            .remediation(remediation)
            .correlationId(correlation)
            .provenance(provenance)
            .durable(true)
            .redaction(DiagnosticRedaction.TECHNICAL)
            .build();
    }

    private static String message(Exception exception) {
        return exception.getMessage() == null || exception.getMessage().isBlank() ? exception.getClass().getSimpleName() : exception.getMessage();
    }
}
