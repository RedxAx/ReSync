package restudio.resync.upgrade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import restudio.resync.flow.diagnostic.DiagnosticSet;
import restudio.resync.migration.AtomicDirectoryRootStore;
import restudio.resync.migration.MigrationCoordinator;
import restudio.resync.migration.MigrationFence;
import restudio.resync.migration.MigrationJournal;
import restudio.resync.migration.MigrationJournalState;
import restudio.resync.migration.MigrationOperation;
import restudio.resync.migration.MigrationPlan;
import restudio.resync.migration.MigrationRequest;
import restudio.resync.migration.PersistenceParticipant;
import restudio.resync.migration.PersistenceParticipantRegistry;
import restudio.resync.migration.QuarantineAcceptance;
import restudio.resync.migration.QuarantineRecord;
import restudio.resync.migration.QuarantineReport;
import restudio.resync.migration.RebindablePersistenceParticipant;
import restudio.resync.migration.Snapshot;
import restudio.resync.migration.SnapshotManifest;
import restudio.resync.migration.SnapshotMetadata;
import restudio.resync.migration.SnapshotService;

class ReplacementUpgraderTest {
    @TempDir
    Path temporary;

    @Test
    void dryRunIsDeterministicAndCreatesVerifiedSnapshot() throws IOException {
        Path source = source("dry-run");
        PersistenceParticipantRegistry participants = participants(source);
        SnapshotMetadata metadata = metadata("dry-run-snapshot");
        UpgradeSourceWindow window = window();
        MigrationFence fence = new MigrationFence();
        ReplacementUpgrader upgrader = new ReplacementUpgrader(new SnapshotService(fence), new MigrationCoordinator(fence, participants));
        Path snapshotStage = temporary.resolve("dry-run-snapshot-stage");
        int[] plannerCalls = {0};

        UpgradeDryRunResult result = upgrader.dryRun(request(source, snapshotStage, metadata, participants, window, (snapshot, sourceWindow) -> {
            plannerCalls[0]++;
            return proposal(snapshot.manifest(), QuarantineReport.empty());
        }));
        UpgradeDryRunResult repeated = upgrader.dryRun(request(source, temporary.resolve("dry-run-snapshot-stage-2"), metadata, participants, window, (snapshot, sourceWindow) -> proposal(snapshot.manifest(), QuarantineReport.empty())));

        assertEquals(UpgradeStatus.READY, result.status());
        assertTrue(result.canApply());
        assertEquals(2, plannerCalls[0]);
        assertEquals(result.proposal().orElseThrow().plan().planHash(), repeated.proposal().orElseThrow().plan().planHash());
        assertEquals(result.diagnostics().toJson(), repeated.diagnostics().toJson());
        assertTrue(result.sourceSnapshot().orElseThrow().verified());
        assertEquals(snapshotStage.toAbsolutePath().normalize(), result.sourceSnapshot().orElseThrow().root());
        assertTrue(Files.exists(snapshotStage));
        assertTrue(Files.exists(result.sourceSnapshot().orElseThrow().manifestPath()));
    }

    @Test
    void dryRunFencesParticipantsBeforePlanningFromTheRetainedSnapshot() throws IOException {
        Path source = source("fenced-dry-run");
        MigrationFence fence = new MigrationFence();
        int[] flushes = {0};
        int[] quiesces = {0};
        int[] resumes = {0};
        PersistenceParticipantRegistry participants = trackingParticipants(source, fence, flushes, quiesces, resumes);
        SnapshotMetadata metadata = metadata("fenced-dry-run-snapshot");
        UpgradeSourceWindow window = window();
        ReplacementUpgrader upgrader = new ReplacementUpgrader(new SnapshotService(fence), new MigrationCoordinator(fence, participants));
        Snapshot[] plannedSnapshots = new Snapshot[2];
        int[] plannerCalls = {0};

        UpgradeDryRunResult result = upgrader.dryRun(request(source, temporary.resolve("fenced-dry-run-stage"), metadata, participants, window, (snapshot, sourceWindow) -> {
            plannedSnapshots[plannerCalls[0]++] = snapshot;
            assertTrue(Files.exists(snapshot.root().resolve("value.txt")));
            assertTrue(snapshot.verified());
            assertEquals("legacy", Files.readString(snapshot.root().resolve("value.txt")));
            assertEquals(1, resumes[0]);
            return proposal(snapshot.manifest(), QuarantineReport.empty());
        }));

        assertEquals(UpgradeStatus.READY, result.status());
        assertEquals(1, flushes[0]);
        assertEquals(1, quiesces[0]);
        assertEquals(1, resumes[0]);
        assertSame(plannedSnapshots[0], plannedSnapshots[1]);
        assertEquals(result.sourceSnapshot().orElseThrow(), plannedSnapshots[0]);
    }

    @Test
    void applyRejectsAnAcceptanceFromAnotherQuarantineReport() throws IOException {
        Path source = source("quarantine");
        PersistenceParticipantRegistry participants = participants(source);
        SnapshotMetadata metadata = metadata("quarantine-snapshot");
        UpgradeSourceWindow window = window();
        MigrationFence fence = new MigrationFence();
        ReplacementUpgrader upgrader = new ReplacementUpgrader(new SnapshotService(fence), new MigrationCoordinator(fence, participants));
        QuarantineReport report = new QuarantineReport(List.of(new QuarantineRecord(
            "record-1",
            "MIGRATION.TEST",
            "value.txt",
            "Unsupported legacy value",
            List.of(),
            "Review the value",
            "1".repeat(64))));
        UpgradeDryRunResult dryRun = upgrader.dryRun(request(source, temporary.resolve("quarantine-snapshot-stage"), metadata, participants, window, (snapshot, sourceWindow) -> proposal(snapshot.manifest(), report)));
        MigrationPlan plan = dryRun.proposal().orElseThrow().plan();
        MigrationJournal journal = MigrationJournal.create(temporary.resolve("quarantine-journal"), "quarantine-migration", plan.planHash());
        QuarantineAcceptance wrongAcceptance = QuarantineReport.empty().accept("operator", Instant.EPOCH);
        MigrationRequest migration = migrationRequest(source, metadata, plan, journal, wrongAcceptance, report, new AtomicDirectoryRootStore(temporary.resolve("quarantine-control")));

        UpgradeApplyResult result = upgrader.apply(new UpgradeApplyRequest(dryRun, migration));

        assertEquals(UpgradeStatus.FAILED, result.status());
        assertFalse(result.changed());
        assertEquals(Optional.empty(), journal.currentState());
        assertTrue(result.diagnostics().diagnostics().stream().anyMatch(diagnostic -> diagnostic.code().equals("MIGRATION.QUARANTINE_UNRESOLVED")));
    }

    @Test
    void committedApplyIsIdempotentAndDoesNotInvokeTheCoordinatorAgain() throws IOException {
        Path source = source("apply");
        PersistenceParticipantRegistry participants = participants(source);
        SnapshotMetadata metadata = metadata("apply-snapshot");
        UpgradeSourceWindow window = window();
        MigrationFence fence = new MigrationFence();
        MigrationCoordinator coordinator = new MigrationCoordinator(fence, participants);
        ReplacementUpgrader upgrader = new ReplacementUpgrader(new SnapshotService(fence), coordinator);
        UpgradeDryRunResult dryRun = upgrader.dryRun(request(source, temporary.resolve("apply-snapshot-stage"), metadata, participants, window, (snapshot, sourceWindow) -> proposal(snapshot.manifest(), QuarantineReport.empty())));
        MigrationPlan plan = dryRun.proposal().orElseThrow().plan();
        MigrationJournal journal = MigrationJournal.create(temporary.resolve("apply-journal"), "apply-migration", plan.planHash());
        AtomicDirectoryRootStore roots = new AtomicDirectoryRootStore(temporary.resolve("control"));
        MigrationRequest migration = migrationRequest(source, metadata, plan, journal, QuarantineReport.empty().accept("operator", Instant.EPOCH), QuarantineReport.empty(), roots);

        UpgradeApplyResult applied = upgrader.apply(new UpgradeApplyRequest(dryRun, migration));
        String pointer = Files.readString(roots.pointerPath());
        UpgradeApplyResult replay = upgrader.apply(new UpgradeApplyRequest(dryRun, migration));

        assertEquals(UpgradeStatus.APPLIED, applied.status());
        assertTrue(applied.changed());
        assertEquals(UpgradeStatus.ALREADY_COMMITTED, replay.status());
        assertFalse(replay.changed());
        assertEquals(pointer, Files.readString(roots.pointerPath()));
        assertEquals(Optional.of(MigrationJournalState.COMMITTED), journal.currentState());
        assertEquals(6, journal.entries().size());
    }

    @Test
    void applyRejectsAChangedRetainedSnapshotBeforeMigration() throws IOException {
        Path source = source("tampered-snapshot");
        PersistenceParticipantRegistry participants = participants(source);
        SnapshotMetadata metadata = metadata("tampered-snapshot");
        MigrationFence fence = new MigrationFence();
        ReplacementUpgrader upgrader = new ReplacementUpgrader(new SnapshotService(fence), new MigrationCoordinator(fence, participants));
        UpgradeDryRunResult dryRun = upgrader.dryRun(request(source, temporary.resolve("tampered-snapshot-stage"), metadata, participants, window(), (snapshot, sourceWindow) -> proposal(snapshot.manifest(), QuarantineReport.empty())));
        Snapshot snapshot = dryRun.sourceSnapshot().orElseThrow();
        Files.writeString(snapshot.root().resolve("value.txt"), "tampered");
        MigrationPlan plan = dryRun.proposal().orElseThrow().plan();
        MigrationJournal journal = MigrationJournal.create(temporary.resolve("tampered-journal"), "tampered-migration", plan.planHash());
        MigrationRequest migration = migrationRequest(source, metadata, plan, journal, QuarantineReport.empty().accept("operator", Instant.EPOCH), QuarantineReport.empty(), new AtomicDirectoryRootStore(temporary.resolve("tampered-control")));

        UpgradeApplyResult result = upgrader.apply(new UpgradeApplyRequest(dryRun, migration));

        assertEquals(UpgradeStatus.FAILED, result.status());
        assertFalse(result.changed());
        assertEquals(Optional.empty(), journal.currentState());
        assertTrue(result.diagnostics().diagnostics().stream().anyMatch(diagnostic -> diagnostic.code().equals("SNAPSHOT.DRY_RUN_INVALID")));
    }

    private UpgradeDryRunRequest request(Path source, Path snapshotStage, SnapshotMetadata metadata, PersistenceParticipantRegistry participants, UpgradeSourceWindow window, UpgradePlanner planner) {
        return new UpgradeDryRunRequest(source, snapshotStage, metadata, participants, window, planner, 0);
    }

    private UpgradeProposal proposal(SnapshotManifest manifest, QuarantineReport report) {
        MigrationPlan plan = new MigrationPlan(
            manifest.metadata().snapshotId(),
            manifest.manifestHash(),
            1,
            2,
            report.reportHash(),
            List.of(new MigrationOperation("copy", "upgrade.copy", "value.txt", "value.txt", "", "")));
        return new UpgradeProposal(plan, report, new DiagnosticSet(List.of()));
    }

    private MigrationRequest migrationRequest(Path source, SnapshotMetadata metadata, MigrationPlan plan, MigrationJournal journal, QuarantineAcceptance acceptance, QuarantineReport report, AtomicDirectoryRootStore roots) {
        return new MigrationRequest(
            source,
            temporary.resolve(plan.sourceSnapshotId() + "-snapshot-stage"),
            metadata,
            temporary.resolve(plan.sourceSnapshotId() + "-migration-stage"),
            plan,
            journal,
            roots,
            staged -> {
            },
            roots,
            roots,
            null,
            report,
            acceptance,
            0);
    }

    private Path source(String name) throws IOException {
        Path source = Files.createDirectory(temporary.resolve(name));
        Files.writeString(source.resolve("value.txt"), "legacy");
        return source;
    }

    private PersistenceParticipantRegistry participants(Path source) {
        PersistenceParticipantRegistry participants = new PersistenceParticipantRegistry();
        participants.register(new RebindablePersistenceParticipant() {
            private Path activeRoot = source;

            @Override
            public String owner() {
                return "core";
            }

            @Override
            public Path root() {
                return activeRoot;
            }

            @Override
            public void flush() {
            }

            @Override
            public void quiesce() {
            }

            @Override
            public void resume() {
            }

            @Override
            public void rebind(Path activeRoot) {
                this.activeRoot = activeRoot;
            }

            @Override
            public void healthCheck() {
            }
        });
        return participants;
    }

    private PersistenceParticipantRegistry trackingParticipants(Path source, MigrationFence fence, int[] flushes, int[] quiesces, int[] resumes) {
        PersistenceParticipantRegistry participants = new PersistenceParticipantRegistry();
        participants.register(new PersistenceParticipant() {
            @Override
            public String owner() {
                return "core";
            }

            @Override
            public Path root() {
                return source;
            }

            @Override
            public void flush() {
                assertTrue(fence.migrationActive());
                flushes[0]++;
            }

            @Override
            public void quiesce() {
                assertTrue(fence.migrationActive());
                quiesces[0]++;
            }

            @Override
            public void resume() {
                assertTrue(fence.migrationActive());
                resumes[0]++;
            }
        });
        return participants;
    }

    private SnapshotMetadata metadata(String snapshotId) {
        return new SnapshotMetadata(1, snapshotId, Instant.parse("2026-01-01T00:00:00Z"), "legacy-build", "b".repeat(64), Map.of());
    }

    private UpgradeSourceWindow window() {
        return new UpgradeSourceWindow(ReplacementUpgrader.VERSION, 1, "legacy-build", 2, "replacement-1");
    }
}
