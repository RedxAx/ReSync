package restudio.resync.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import restudio.resync.flow.diagnostic.Diagnostic;
import restudio.resync.flow.diagnostic.DiagnosticPhase;
import restudio.resync.flow.diagnostic.DiagnosticSet;
import restudio.resync.flow.diagnostic.DiagnosticSeverity;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.SnapshotId;
import restudio.resync.upgrade.AssetAdoptionArtifactProducer;
import restudio.resync.upgrade.ReplacementUpgrader;
import restudio.resync.upgrade.UpgradeApplyResult;
import restudio.resync.upgrade.UpgradeDryRunResult;
import restudio.resync.upgrade.UpgradeProposal;
import restudio.resync.upgrade.UpgradeSourceWindow;
import restudio.resync.upgrade.UpgradeStatus;

class OfflineReplacementUpgradeTransactionTest {
    private static final String INVOCATION_HASH = "1".repeat(64);

    @TempDir
    Path temporary;

    private final Map<Path, AuthorityFixture> authorityFixtures = new HashMap<>();

    @Test
    void boundActivationPersistsAuthorityJournalReportAndExactArchive() throws IOException {
        Path source = source("bound");
        String originalDigest = TreeDigest.of(source);
        OfflineReplacementUpgradeEntrypoint entrypoint = entrypoint(source, "bound", boundMarkerWriter());

        OfflineReplacementUpgradeEntrypoint.RunResult run = entrypoint.execute();

        assertEquals(UpgradeStatus.APPLIED, run.apply().status());
        Path control = control(source);
        Path journalPath = control.resolve("journals/bound.journal");
        Path reportPath = control.resolve("plans/bound.plan.final-report");
        assertTrue(Files.readString(journalPath).startsWith(
            "format=" + MigrationJournal.PUBLICATION_AUTHORITY_GRANT_FORMAT_VERSION + "\n"));
        MigrationJournal journal = MigrationJournal.open(journalPath);
        MigrationJournal.Binding binding = journal.binding().orElseThrow();
        binding.requireStagedReplacementDigest();
        binding.requireArchivedSourceDigest();
        assertEquals(run.apply().planHash().orElseThrow(), journal.planHash());
        assertEquals(MigrationJournalState.COMMITTED, journal.currentState().orElseThrow());
        assertTrue(Files.isRegularFile(reportPath));
        assertEquals(originalDigest, TreeDigest.of(control.resolve("archives/bound-source")));

        MigrationActivationMarker.Values marker = MigrationActivationMarker.read(source);
        assertEquals(binding.stagedReplacementDigest(), marker.replacementRootHash());
        assertEquals(originalDigest, binding.archivedSourceDigest());
        assertEquals(originalDigest, marker.archivedSourceDigest());
        assertTrue(Files.readString(reportPath).contains("archived-source-digest=" + originalDigest + "\n"));
        assertEquals("replacement", Files.readString(source.resolve("value.txt")));
    }

    @Test
    void stagedRecoveryResumesAfterSourceWasArchivedBeforeReplacementSwap() throws IOException {
        Path source = source("staged-recovery");
        OfflineReplacementUpgradeEntrypoint entrypoint = entrypoint(source, "staged-recovery", boundMarkerWriter());
        UpgradeDryRunResult dryRun = entrypoint.dryRun();
        InterruptedTransaction interrupted = interrupt(dryRun, source, "staged-recovery", MigrationJournalState.STAGED, false);

        assertFalse(Files.exists(source));
        assertTrue(Files.isDirectory(control(source).resolve("staging/staged-recovery")));

        OfflineReplacementUpgradeEntrypoint.RunResult recovered = entrypoint.execute();

        assertEquals(UpgradeStatus.APPLIED, recovered.apply().status());
        assertEquals(MigrationJournalState.COMMITTED, MigrationJournal.open(interrupted.journalPath()).currentState().orElseThrow());
        assertEquals("replacement", Files.readString(source.resolve("value.txt")));
        assertEquals(interrupted.archivedSourceDigest(), TreeDigest.of(interrupted.archiveRoot()));
        assertEquals(interrupted.archivedSourceDigest(), MigrationActivationMarker.read(source).archivedSourceDigest());
        assertTrue(Files.exists(MigrationActivationMarker.markerPath(source)));
        assertTrue(Files.readString(control(source).resolve("plans/staged-recovery.plan.final-report"))
            .contains("final-state=COMMITTED\n"));

        String journalBeforeReplay = Files.readString(interrupted.journalPath());
        OfflineReplacementUpgradeEntrypoint.RunResult replay = entrypoint.execute();
        assertEquals(UpgradeStatus.ALREADY_COMMITTED, replay.apply().status());
        assertFalse(replay.apply().changed());
        assertEquals(journalBeforeReplay, Files.readString(interrupted.journalPath()));
    }

    @Test
    void stagedRecoveryResumesWhenCandidateAlreadyOccupiesSourceBeforeActivation() throws IOException {
        Path source = source("staged-source-occupied");
        OfflineReplacementUpgradeEntrypoint entrypoint = entrypoint(source, "staged-source-occupied", boundMarkerWriter());
        UpgradeDryRunResult dryRun = entrypoint.dryRun();
        InterruptedTransaction interrupted = interrupt(dryRun, source, "staged-source-occupied", MigrationJournalState.STAGED, true);

        assertTrue(Files.isDirectory(source));
        assertFalse(Files.exists(control(source).resolve("staging/staged-source-occupied")));
        assertEquals(MigrationJournalState.STAGED, MigrationJournal.open(interrupted.journalPath()).currentState().orElseThrow());

        OfflineReplacementUpgradeEntrypoint.RunResult recovered = entrypoint.execute();

        assertEquals(UpgradeStatus.APPLIED, recovered.apply().status());
        assertEquals(MigrationJournalState.COMMITTED, MigrationJournal.open(interrupted.journalPath()).currentState().orElseThrow());
        assertEquals("replacement", Files.readString(source.resolve("value.txt")));
        assertEquals(interrupted.archivedSourceDigest(), MigrationActivationMarker.read(source).archivedSourceDigest());
    }

    @Test
    void stagedRecoveryReconstructsTypedEvidenceWhenTheCandidateDisappearsAfterSourceArchive() throws IOException {
        Path source = source("staged-missing-candidate");
        OfflineReplacementUpgradeEntrypoint entrypoint = entrypoint(source, "staged-missing-candidate", boundMarkerWriter());
        UpgradeDryRunResult dryRun = entrypoint.dryRun();
        InterruptedTransaction interrupted = interrupt(dryRun, source, "staged-missing-candidate", MigrationJournalState.STAGED, false);
        deleteTree(control(source).resolve("staging/staged-missing-candidate"));

        OfflineReplacementUpgradeEntrypoint.RunResult recovered = entrypoint.execute();

        assertEquals(UpgradeStatus.APPLIED, recovered.apply().status());
        assertEquals(MigrationJournalState.COMMITTED, MigrationJournal.open(interrupted.journalPath()).currentState().orElseThrow());
        assertEquals("replacement", Files.readString(source.resolve("value.txt")));
        assertTrue(Files.exists(interrupted.archiveRoot()));
    }

    @ParameterizedTest
    @EnumSource(value = MigrationJournalState.class, names = {"PREPARED", "TRANSFORMING", "VALIDATING"})
    void preActivationJournalStatesResumeDeterministically(MigrationJournalState state) throws IOException {
        String migrationId = "preactivation-" + state.name().toLowerCase();
        Path source = source(migrationId);
        OfflineReplacementUpgradeEntrypoint entrypoint = entrypoint(source, migrationId, boundMarkerWriter());
        UpgradeDryRunResult dryRun = entrypoint.dryRun();
        Path journalPath = prepareJournalAtState(dryRun, source, migrationId, state);

        OfflineReplacementUpgradeEntrypoint.RunResult recovered = entrypoint.execute();

        assertEquals(UpgradeStatus.APPLIED, recovered.apply().status());
        assertEquals(MigrationJournalState.COMMITTED, MigrationJournal.open(journalPath).currentState().orElseThrow());
        assertEquals("replacement", Files.readString(source.resolve("value.txt")));
        assertTrue(Files.isDirectory(control(source).resolve("archives").resolve(migrationId + "-source")));
        assertFalse(Files.exists(control(source).resolve("staging").resolve(migrationId)));
    }

    @Test
    void activatedRecoveryFinalizesAuthorityAfterRootSwap() throws IOException {
        Path source = source("activated-recovery");
        OfflineReplacementUpgradeEntrypoint entrypoint = entrypoint(source, "activated-recovery", boundMarkerWriter());
        UpgradeDryRunResult dryRun = entrypoint.dryRun();
        InterruptedTransaction interrupted = interrupt(dryRun, source, "activated-recovery", MigrationJournalState.ACTIVATED, true);

        OfflineReplacementUpgradeEntrypoint.RunResult recovered = entrypoint.execute();

        assertEquals(UpgradeStatus.APPLIED, recovered.apply().status());
        assertEquals(MigrationJournalState.COMMITTED, MigrationJournal.open(interrupted.journalPath()).currentState().orElseThrow());
        assertEquals("replacement", Files.readString(source.resolve("value.txt")));
        assertEquals(interrupted.archivedSourceDigest(), MigrationActivationMarker.read(source).archivedSourceDigest());
        assertTrue(Files.isDirectory(interrupted.archiveRoot()));
    }

    @Test
    void activatedRecoveryUsesRetainedPostStageTopology() throws IOException {
        String migrationId = "activated-retained-topology";
        Path source = source(migrationId);
        AtomicBoolean owns = new AtomicBoolean(true);
        AtomicReference<Path> activeRoot = new AtomicReference<>(source);
        PersistenceParticipantRegistry participants = new PersistenceParticipantRegistry();
        participants.register(new RebindablePersistenceParticipant() {
            @Override
            public String owner() {
                return "core";
            }

            @Override
            public Path root() {
                return activeRoot.get();
            }

            @Override
            public boolean owns(Path file) {
                return owns.get();
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
            public void rebind(Path root) {
                activeRoot.set(root);
            }

            @Override
            public void healthCheck() {
            }
        });
        OfflineReplacementUpgradeEntrypoint entrypoint = entrypoint(
            source, migrationId, boundMarkerWriter(), new DiagnosticSet(List.of()),
            OfflineReplacementUpgradeEntrypoint.CommittedRepairFault.none(), participants);
        UpgradeDryRunResult dryRun = entrypoint.dryRun();
        InterruptedTransaction interrupted = interrupt(dryRun, source, migrationId, MigrationJournalState.ACTIVATED, true);
        owns.set(false);

        OfflineReplacementUpgradeEntrypoint.RunResult recovered = entrypoint.execute();

        assertEquals(UpgradeStatus.APPLIED, recovered.apply().status());
        assertEquals(MigrationJournalState.COMMITTED,
            MigrationJournal.open(interrupted.journalPath()).currentState().orElseThrow());
        assertEquals("replacement", Files.readString(source.resolve("value.txt")));
    }

    @Test
    void activatedRecoveryFinalizesWithAValidMarkerAlreadyWritten() throws IOException {
        Path source = source("activated-marker-present");
        OfflineReplacementUpgradeEntrypoint entrypoint = entrypoint(source, "activated-marker-present", boundMarkerWriter());
        UpgradeDryRunResult dryRun = entrypoint.dryRun();
        InterruptedTransaction interrupted = interrupt(dryRun, source, "activated-marker-present", MigrationJournalState.ACTIVATED, true);
        MigrationPlan plan = dryRun.proposal().orElseThrow().plan();
        AuthorityFixture authority = authority(source, "activated-marker-present");
        MigrationActivationMarker.write(source, new MigrationActivationMarker.Values(
            plan.sourceManifestHash(),
            plan.planHash(),
            TreeDigest.of(source),
            interrupted.archivedSourceDigest(),
            authority.binding().replacementCatalogHash(),
            authority.binding().runtimeBindingManifestHash(),
            authority.binding().runtimeBindingManifestVersion(),
            authority.binding().participantReadinessHash(),
            authority.binding().participantReadinessVersion(),
            true,
            authority.bundle(),
            authority.binding().authorityTrustAnchorHash(),
            authority.grant()));
        String markerBefore = Files.readString(MigrationActivationMarker.markerPath(source));

        OfflineReplacementUpgradeEntrypoint.RunResult recovered = entrypoint.execute();

        assertEquals(UpgradeStatus.APPLIED, recovered.apply().status());
        assertEquals(MigrationJournalState.COMMITTED, MigrationJournal.open(interrupted.journalPath()).currentState().orElseThrow());
        assertEquals(markerBefore, Files.readString(MigrationActivationMarker.markerPath(source)));
        assertEquals(interrupted.archivedSourceDigest(), MigrationActivationMarker.read(source).archivedSourceDigest());
    }

    @Test
    void failedRecoveryRollsBackAnInterruptedRootSwap() throws IOException {
        Path source = source("failed-recovery");
        OfflineReplacementUpgradeEntrypoint entrypoint = entrypoint(source, "failed-recovery", boundMarkerWriter());
        UpgradeDryRunResult dryRun = entrypoint.dryRun();
        InterruptedTransaction interrupted = interrupt(dryRun, source, "failed-recovery", MigrationJournalState.FAILED, true);

        OfflineReplacementUpgradeEntrypoint.RunResult recovered = entrypoint.execute();

        assertEquals(UpgradeStatus.FAILED, recovered.apply().status());
        assertFalse(recovered.apply().changed());
        assertEquals(MigrationJournalState.ROLLED_BACK, MigrationJournal.open(interrupted.journalPath()).currentState().orElseThrow());
        assertEquals("legacy", Files.readString(source.resolve("value.txt")));
        assertFalse(Files.exists(interrupted.archiveRoot()));
        assertFalse(Files.exists(MigrationActivationMarker.markerPath(source)));
    }

    @Test
    void rolledBackRecoveryReplayDoesNotMutateTheTerminalTransaction() throws IOException {
        Path source = source("rolled-back-replay");
        OfflineReplacementUpgradeEntrypoint entrypoint = entrypoint(source, "rolled-back-replay", boundMarkerWriter());
        UpgradeDryRunResult dryRun = entrypoint.dryRun();
        InterruptedTransaction interrupted = interrupt(dryRun, source, "rolled-back-replay", MigrationJournalState.FAILED, true);

        OfflineReplacementUpgradeEntrypoint.RunResult first = entrypoint.execute();
        assertEquals(UpgradeStatus.FAILED, first.apply().status());
        assertEquals(MigrationJournalState.ROLLED_BACK, MigrationJournal.open(interrupted.journalPath()).currentState().orElseThrow());
        String controlBeforeReplay = TreeDigest.of(control(source));
        String journalBeforeReplay = Files.readString(interrupted.journalPath());
        String reportBeforeReplay = Files.readString(control(source).resolve("plans/rolled-back-replay.plan.final-report"));

        OfflineReplacementUpgradeEntrypoint.RunResult replay = entrypoint.execute();

        assertEquals(UpgradeStatus.FAILED, replay.apply().status());
        assertFalse(replay.apply().changed());
        assertEquals(controlBeforeReplay, TreeDigest.of(control(source)));
        assertEquals(journalBeforeReplay, Files.readString(interrupted.journalPath()));
        assertEquals(reportBeforeReplay, Files.readString(control(source).resolve("plans/rolled-back-replay.plan.final-report")));
        assertEquals("legacy", Files.readString(source.resolve("value.txt")));
    }

    @Test
    void restartedMarkerFailureRollsBackAndPersistsFinalReport() throws IOException {
        Path source = source("restarted-marker-failure");
        TransactionMarkerWriter failing = new TransactionMarkerWriter() {
            @Override
            public void write(MigrationPlan plan, StagedMigration staged) throws IOException {
                throw new IOException("marker write failed after restart");
            }
        };
        OfflineReplacementUpgradeEntrypoint entrypoint = entrypoint(source, "restarted-marker-failure", failing);
        UpgradeDryRunResult dryRun = entrypoint.dryRun();
        InterruptedTransaction interrupted = interrupt(dryRun, source, "restarted-marker-failure", MigrationJournalState.ACTIVATED, true);

        OfflineReplacementUpgradeEntrypoint.RunResult recovered = entrypoint.execute();

        assertEquals(UpgradeStatus.FAILED, recovered.apply().status());
        assertEquals(MigrationJournalState.ROLLED_BACK, MigrationJournal.open(interrupted.journalPath()).currentState().orElseThrow());
        assertEquals("legacy", Files.readString(source.resolve("value.txt")));
        assertFalse(Files.exists(interrupted.archiveRoot()));
        assertTrue(Files.readString(control(source).resolve("plans/restarted-marker-failure.plan.final-report"))
            .contains("final-state=ROLLED_BACK\n"));
    }

    @Test
    void exactCommittedPlanIsReadOnlyAndReturnsAlreadyCommitted() throws IOException {
        Path source = source("replay");
        OfflineReplacementUpgradeEntrypoint entrypoint = entrypoint(source, "replay", boundMarkerWriter());
        UpgradeApplyResult first = entrypoint.execute().apply();
        Path journal = control(source).resolve("journals/replay.journal");
        Path report = control(source).resolve("plans/replay.plan.final-report");
        Path snapshotState = control(source).resolve("snapshots/replay.state");
        String journalBefore = Files.readString(journal);
        String reportBefore = Files.readString(report);
        String snapshotStateBefore = Files.readString(snapshotState);
        UpgradeApplyResult replay = entrypoint.execute().apply();

        assertEquals(UpgradeStatus.APPLIED, first.status());
        assertEquals(UpgradeStatus.ALREADY_COMMITTED, replay.status());
        assertFalse(replay.changed());
        assertEquals(journalBefore, Files.readString(journal));
        assertEquals(reportBefore, Files.readString(report));
        assertEquals(snapshotStateBefore, Files.readString(snapshotState));
    }

    @Test
    void committedReplayBypassesSnapshotScopedParticipantValidation() throws IOException {
        String migrationId = "committed-snapshot-participant";
        Path source = source(migrationId);
        entrypoint(source, migrationId, boundMarkerWriter()).execute();
        PersistenceParticipantRegistry snapshotParticipants = new PersistenceParticipantRegistry();
        snapshotParticipants.register(new PersistenceParticipant() {
            @Override
            public String owner() {
                return "snapshot-only";
            }

            @Override
            public Path root() {
                return source;
            }

            @Override
            public boolean owns(Path file) {
                return false;
            }
        });
        OfflineReplacementUpgradeEntrypoint replay = entrypoint(
            source, migrationId, boundMarkerWriter(), new DiagnosticSet(List.of()),
            OfflineReplacementUpgradeEntrypoint.CommittedRepairFault.none(), snapshotParticipants);

        UpgradeApplyResult result = replay.execute().apply();

        assertEquals(UpgradeStatus.ALREADY_COMMITTED, result.status());
        assertFalse(result.changed());
    }

    @Test
    void committedReplayRejectsTamperedArchivedSource() throws IOException {
        Path source = source("tampered-archive");
        OfflineReplacementUpgradeEntrypoint entrypoint = entrypoint(source, "tampered-archive", boundMarkerWriter());
        entrypoint.execute();
        Path archive = control(source).resolve("archives/tampered-archive-source");
        Files.writeString(archive.resolve("tampered.txt"), "tampered");

        MigrationException failure = assertThrows(MigrationException.class, entrypoint::execute);

        assertEquals("Committed Offline Upgrade Authority Does Not Match The Retained Plan", failure.getMessage());
        assertEquals("replacement", Files.readString(source.resolve("value.txt")));
    }

    @Test
    void committedReplayRepairsACorruptActiveCandidateFromTheRetainedForcedCandidate() throws IOException {
        Path source = source("repair-corrupt-candidate");
        OfflineReplacementUpgradeEntrypoint entrypoint = entrypoint(source, "repair-corrupt-candidate", boundMarkerWriter());
        OfflineReplacementUpgradeEntrypoint.RunResult first = entrypoint.execute();
        Path control = control(source);
        Path replacementArchive = control.resolve("archives/repair-corrupt-candidate-replacement");
        Path sourceArchive = control.resolve("archives/repair-corrupt-candidate-source");
        assertTrue(Files.isDirectory(replacementArchive));
        assertEquals(first.apply().migration().orElseThrow().staged().contentHash(), TreeDigest.of(replacementArchive));
        Files.writeString(source.resolve("value.txt"), "corrupt-after-commit");

        UpgradeApplyResult repaired = entrypoint.execute().apply();

        assertEquals(UpgradeStatus.ALREADY_COMMITTED, repaired.status());
        assertEquals("replacement", Files.readString(source.resolve("value.txt")));
        assertEquals("legacy", Files.readString(sourceArchive.resolve("value.txt")));
        assertEquals(TreeDigest.of(replacementArchive), TreeDigest.of(source));
        assertTrue(Files.exists(MigrationActivationMarker.markerPath(source)));
    }

    @Test
    void committedReplayRebuildsMissingRetainedCandidateFromMatchingActiveRoot() throws IOException {
        String migrationId = "repair-missing-retained-candidate";
        Path source = source(migrationId);
        OfflineReplacementUpgradeEntrypoint entrypoint = entrypoint(source, migrationId, boundMarkerWriter());
        entrypoint.execute();
        Path retained = control(source).resolve("archives").resolve(migrationId + "-replacement");
        deleteTree(retained);

        UpgradeApplyResult replay = entrypoint.execute().apply();

        assertEquals(UpgradeStatus.ALREADY_COMMITTED, replay.status());
        assertEquals("replacement", Files.readString(source.resolve("value.txt")));
        assertEquals(TreeDigest.of(source), TreeDigest.of(retained));
    }

    @Test
    void committedReplayQuarantinesAndRebuildsCorruptRetainedCandidateFromMatchingActiveRoot() throws IOException {
        String migrationId = "repair-corrupt-retained-candidate";
        Path source = source(migrationId);
        OfflineReplacementUpgradeEntrypoint entrypoint = entrypoint(source, migrationId, boundMarkerWriter());
        entrypoint.execute();
        Path archives = control(source).resolve("archives");
        Path retained = archives.resolve(migrationId + "-replacement");
        Files.writeString(retained.resolve("value.txt"), "corrupt-retained");

        UpgradeApplyResult replay = entrypoint.execute().apply();

        assertEquals(UpgradeStatus.ALREADY_COMMITTED, replay.status());
        assertEquals("replacement", Files.readString(source.resolve("value.txt")));
        assertEquals("corrupt-retained",
            Files.readString(archives.resolve(migrationId + "-corrupt-replacement/value.txt")));
        assertEquals(TreeDigest.of(source), TreeDigest.of(retained));
    }

    @Test
    void partialRetainedCandidateIsQuarantinedBeforeRetryingRetention() throws IOException {
        String migrationId = "partial-retained-candidate";
        Path source = source(migrationId);
        OfflineReplacementUpgradeEntrypoint entrypoint = entrypoint(source, migrationId, boundMarkerWriter());
        Path archives = control(source).resolve("archives");
        Path retained = archives.resolve(migrationId + "-replacement");
        Files.createDirectories(retained);
        Files.writeString(retained.resolve("value.txt"), "partial");

        OfflineReplacementUpgradeEntrypoint.RunResult result = entrypoint.execute();

        assertEquals(UpgradeStatus.APPLIED, result.apply().status());
        assertEquals("replacement", Files.readString(source.resolve("value.txt")));
        assertEquals("partial", Files.readString(archives.resolve(migrationId + "-corrupt-replacement/value.txt")));
        assertEquals(result.apply().migration().orElseThrow().staged().contentHash(), TreeDigest.of(retained));
        assertFalse(Files.exists(archives.resolve(migrationId + "-replacement.stage")));
    }

    @Test
    void partialRetainedCandidateStageIsQuarantinedBeforeRetryingRetention() throws IOException {
        String migrationId = "partial-retained-candidate-stage";
        Path source = source(migrationId);
        OfflineReplacementUpgradeEntrypoint entrypoint = entrypoint(source, migrationId, boundMarkerWriter());
        Path archives = control(source).resolve("archives");
        Path stage = archives.resolve(migrationId + "-replacement.stage");
        Files.createDirectories(stage);
        Files.writeString(stage.resolve("value.txt"), "partial-stage");

        OfflineReplacementUpgradeEntrypoint.RunResult result = entrypoint.execute();

        assertEquals(UpgradeStatus.APPLIED, result.apply().status());
        assertEquals("replacement", Files.readString(source.resolve("value.txt")));
        assertEquals("partial-stage", Files.readString(archives.resolve(migrationId + "-corrupt-replacement/value.txt")));
        assertEquals(result.apply().migration().orElseThrow().staged().contentHash(),
            TreeDigest.of(archives.resolve(migrationId + "-replacement")));
        assertFalse(Files.exists(stage));
    }

    @Test
    void retainedCandidateEvidenceCollisionFailsClosed() throws IOException {
        String migrationId = "retained-candidate-evidence-collision";
        Path source = source(migrationId);
        OfflineReplacementUpgradeEntrypoint entrypoint = entrypoint(source, migrationId, boundMarkerWriter());
        Path archives = control(source).resolve("archives");
        Path retained = archives.resolve(migrationId + "-replacement");
        Path evidence = archives.resolve(migrationId + "-corrupt-replacement");
        Files.createDirectories(retained);
        Files.writeString(retained.resolve("value.txt"), "partial");
        Files.writeString(evidence, "unknown-collision");

        OfflineReplacementUpgradeEntrypoint.RunResult result = entrypoint.execute();

        assertEquals(UpgradeStatus.FAILED, result.apply().status());
        assertEquals("legacy", Files.readString(source.resolve("value.txt")));
        assertTrue(Files.isRegularFile(evidence));
        assertEquals("unknown-collision", Files.readString(evidence));
    }

    @ParameterizedTest
    @EnumSource(OfflineReplacementUpgradeEntrypoint.CommittedRepairCut.class)
    void committedRepairResumesAfterEveryDurableCut(OfflineReplacementUpgradeEntrypoint.CommittedRepairCut cut) throws IOException {
        String migrationId = "repair-cut-" + cut.name().toLowerCase();
        Path source = source(migrationId);
        entrypoint(source, migrationId, boundMarkerWriter()).execute();
        Files.writeString(source.resolve("value.txt"), "corrupt-after-commit");
        AtomicBoolean fail = new AtomicBoolean(true);
        OfflineReplacementUpgradeEntrypoint interrupted = entrypoint(source, migrationId, boundMarkerWriter(),
            new DiagnosticSet(List.of()), value -> {
                if (value == cut && fail.compareAndSet(true, false)) {
                    throw new IOException("injected committed repair cut: " + value);
                }
            });

        assertThrows(IOException.class, interrupted::execute);

        OfflineReplacementUpgradeEntrypoint.RunResult recovered = entrypoint(source, migrationId, boundMarkerWriter()).execute();

        assertEquals(UpgradeStatus.ALREADY_COMMITTED, recovered.apply().status());
        assertEquals("replacement", Files.readString(source.resolve("value.txt")));
        assertEquals(MigrationJournalState.COMMITTED,
            MigrationJournal.open(control(source).resolve("journals").resolve(migrationId + ".journal"))
                .currentState().orElseThrow());
        assertEquals(CommittedReplacementRepairJournal.State.MARKER_PUBLISHED,
            CommittedReplacementRepairJournal.read(CommittedReplacementRepairJournal.path(control(source), migrationId)).state());
        assertEquals(TreeDigest.of(control(source).resolve("archives").resolve(migrationId + "-replacement")),
            TreeDigest.of(source));
        assertTrue(Files.exists(MigrationActivationMarker.markerPath(source)));
    }

    @Test
    void committedRepairAcceptsAnExactPreexistingCorruptArchiveAfterArchiveMove() throws IOException {
        String migrationId = "repair-preexisting-archive";
        Path source = source(migrationId);
        entrypoint(source, migrationId, boundMarkerWriter()).execute();
        Files.writeString(source.resolve("value.txt"), "corrupt-after-commit");
        AtomicBoolean fail = new AtomicBoolean(true);
        OfflineReplacementUpgradeEntrypoint interrupted = entrypoint(source, migrationId, boundMarkerWriter(),
            new DiagnosticSet(List.of()), value -> {
                if (value == OfflineReplacementUpgradeEntrypoint.CommittedRepairCut.CORRUPT_ARCHIVE_MOVED
                    && fail.compareAndSet(true, false)) {
                    throw new IOException("archive move cut");
                }
            });

        assertThrows(IOException.class, interrupted::execute);
        Path damaged = control(source).resolve("archives").resolve(migrationId + "-corrupt-replacement");
        Path repairJournal = CommittedReplacementRepairJournal.path(control(source), migrationId);
        assertTrue(Files.isDirectory(damaged));
        assertEquals(CommittedReplacementRepairJournal.State.PREPARED,
            CommittedReplacementRepairJournal.read(repairJournal).state());

        OfflineReplacementUpgradeEntrypoint.RunResult recovered = entrypoint(source, migrationId, boundMarkerWriter()).execute();

        assertEquals(UpgradeStatus.ALREADY_COMMITTED, recovered.apply().status());
        assertEquals("replacement", Files.readString(source.resolve("value.txt")));
        assertEquals(TreeDigest.of(damaged),
            CommittedReplacementRepairJournal.read(repairJournal).corruptArchiveDigest());
    }

    @Test
    void committedRepairRecoversMarkerlessActiveRootAfterTheArchiveMoveCut() throws IOException {
        String migrationId = "repair-markerless-active";
        Path source = source(migrationId);
        entrypoint(source, migrationId, boundMarkerWriter()).execute();
        Files.delete(MigrationActivationMarker.markerPath(source));
        Files.writeString(source.resolve("value.txt"), "corrupt-after-commit");
        AtomicBoolean fail = new AtomicBoolean(true);
        OfflineReplacementUpgradeEntrypoint interrupted = entrypoint(source, migrationId, boundMarkerWriter(),
            new DiagnosticSet(List.of()), value -> {
                if (value == OfflineReplacementUpgradeEntrypoint.CommittedRepairCut.CORRUPT_ARCHIVE_MOVED
                    && fail.compareAndSet(true, false)) {
                    throw new IOException("markerless archive move cut");
                }
            });

        assertThrows(IOException.class, interrupted::execute);
        Path damaged = control(source).resolve("archives").resolve(migrationId + "-corrupt-replacement");
        Path repairJournal = CommittedReplacementRepairJournal.path(control(source), migrationId);
        CommittedReplacementRepairJournal.Values interruptedRepair = CommittedReplacementRepairJournal.read(repairJournal);
        assertEquals("", interruptedRepair.activeMarkerHash());
        assertFalse(Files.exists(source));
        assertFalse(Files.exists(MigrationActivationMarker.markerPath(damaged)));
        assertEquals(interruptedRepair.activeReplacementDigest(), TreeDigest.of(damaged));

        OfflineReplacementUpgradeEntrypoint.RunResult recovered = entrypoint(source, migrationId, boundMarkerWriter()).execute();

        assertEquals(UpgradeStatus.ALREADY_COMMITTED, recovered.apply().status());
        assertEquals("replacement", Files.readString(source.resolve("value.txt")));
        assertTrue(Files.exists(MigrationActivationMarker.markerPath(source)));
        assertEquals("corrupt-after-commit", Files.readString(damaged.resolve("value.txt")));
        assertEquals(TreeDigest.of(damaged),
            CommittedReplacementRepairJournal.read(repairJournal).corruptArchiveDigest());
    }

    @Test
    void committedRepairRejectsAMismatchedPreexistingCorruptArchive() throws IOException {
        String migrationId = "repair-mismatched-archive";
        Path source = source(migrationId);
        entrypoint(source, migrationId, boundMarkerWriter()).execute();
        Files.writeString(source.resolve("value.txt"), "corrupt-after-commit");
        AtomicBoolean fail = new AtomicBoolean(true);
        OfflineReplacementUpgradeEntrypoint interrupted = entrypoint(source, migrationId, boundMarkerWriter(),
            new DiagnosticSet(List.of()), value -> {
                if (value == OfflineReplacementUpgradeEntrypoint.CommittedRepairCut.CORRUPT_ARCHIVE_MOVED
                    && fail.compareAndSet(true, false)) {
                    throw new IOException("archive move cut");
                }
            });
        assertThrows(IOException.class, interrupted::execute);
        Path damaged = control(source).resolve("archives").resolve(migrationId + "-corrupt-replacement");
        Files.writeString(damaged.resolve("value.txt"), "mismatched-corrupt-archive");

        MigrationException failure = assertThrows(MigrationException.class,
            () -> entrypoint(source, migrationId, boundMarkerWriter()).execute());

        assertEquals("Committed Offline Upgrade Corrupt Replacement Archive Does Not Match Journal", failure.getMessage());
        assertFalse(Files.exists(source));
    }

    @Test
    void committedReplayRejectsMarkerAuthorityThatDoesNotMatchTheJournal() throws IOException {
        Path source = source("tampered-authority");
        OfflineReplacementUpgradeEntrypoint entrypoint = entrypoint(source, "tampered-authority", boundMarkerWriter());
        entrypoint.execute();
        Path markerPath = MigrationActivationMarker.markerPath(source);
        String marker = Files.readString(markerPath);
        String canonical = marker.substring(0, marker.lastIndexOf("marker-hash="))
            .replace("authority-trust-anchor-hash=" + MigrationCanonical.sha256(
                authority(source, "tampered-authority").trustAnchor().canonicalBytes()),
                "authority-trust-anchor-hash=" + "0".repeat(64));
        Files.writeString(markerPath, canonical + "marker-hash=" + MigrationCanonical.sha256(canonical) + "\n");

        MigrationException failure = assertThrows(MigrationException.class, entrypoint::execute);

        assertEquals("Committed Offline Upgrade Authority Does Not Match The Retained Plan", failure.getMessage());
    }

    @Test
    void committedReplayRecreatesOnlyAReportLostAfterCommit() throws IOException {
        Path source = source("report-recovery");
        OfflineReplacementUpgradeEntrypoint entrypoint = entrypoint(source, "report-recovery", boundMarkerWriter());
        UpgradeDryRunResult dryRun = entrypoint.dryRun();
        UpgradeApplyResult first = entrypoint.apply(dryRun);
        Path report = control(source).resolve("plans/report-recovery.plan.final-report");
        Files.delete(report);

        UpgradeApplyResult replay = entrypoint.apply(dryRun);

        assertEquals(UpgradeStatus.APPLIED, first.status());
        assertEquals(UpgradeStatus.ALREADY_COMMITTED, replay.status());
        assertFalse(replay.changed());
        assertTrue(Files.isRegularFile(report));
        assertTrue(Files.readString(report).contains("final-state=COMMITTED\n"));
    }

    @Test
    void committedReplayRejectsAValidlyHashedButMismatchedFinalReport() throws IOException {
        Path source = source("tampered-final-report");
        OfflineReplacementUpgradeEntrypoint entrypoint = entrypoint(source, "tampered-final-report", boundMarkerWriter());
        entrypoint.execute();
        Path report = control(source).resolve("plans/tampered-final-report.plan.final-report");
        String content = Files.readString(report);
        String tampered = content.replace(
            "accepted-by=" + MigrationCanonical.encode("offline-upgrader") + "\n",
            "accepted-by=" + MigrationCanonical.encode("attacker") + "\n");
        String canonical = tampered.substring(0, tampered.lastIndexOf("report-hash="));
        Files.writeString(report, canonical + "report-hash=" + MigrationCanonical.sha256(canonical) + "\n");

        MigrationException failure = assertThrows(MigrationException.class, entrypoint::execute);

        assertEquals("Committed Offline Upgrade Final Report Transaction Binding Does Not Match", failure.getMessage());
    }

    @Test
    void committedReplayRejectsAValidlyHashedDiagnosticTamper() throws IOException {
        Path source = source("tampered-diagnostics");
        DiagnosticSet diagnostics = new DiagnosticSet(List.of(deterministicDiagnostic()));
        OfflineReplacementUpgradeEntrypoint entrypoint = entrypoint(source, "tampered-diagnostics", boundMarkerWriter(), diagnostics);
        entrypoint.execute();
        Path report = control(source).resolve("plans/tampered-diagnostics.plan.final-report");
        String content = Files.readString(report);
        String tampered = content.replace(
            "diagnostic=" + MigrationCanonical.encode("CAPABILITY.FIELD_READ_ONLY") + "|"
                + MigrationCanonical.encode("The capability reported field read only.") + "\n",
            "diagnostic=" + MigrationCanonical.encode("CAPABILITY.FIELD_READ_ONLY") + "|"
                + MigrationCanonical.encode("Tampered Diagnostic Message") + "\n");
        String canonical = tampered.substring(0, tampered.lastIndexOf("report-hash="));
        Files.writeString(report, canonical + "report-hash=" + MigrationCanonical.sha256(canonical) + "\n");

        MigrationException failure = assertThrows(MigrationException.class, entrypoint::execute);

        assertEquals("Committed Offline Upgrade Final Report Diagnostics Do Not Match", failure.getMessage());
    }

    @Test
    void committedReplayRejectsAValidlyHashedDiagnosticCountTamper() throws IOException {
        Path source = source("tampered-diagnostic-count");
        DiagnosticSet diagnostics = new DiagnosticSet(List.of(deterministicDiagnostic()));
        OfflineReplacementUpgradeEntrypoint entrypoint = entrypoint(source, "tampered-diagnostic-count", boundMarkerWriter(), diagnostics);
        entrypoint.execute();
        Path report = control(source).resolve("plans/tampered-diagnostic-count.plan.final-report");
        String content = Files.readString(report);
        String diagnostic = "diagnostic=" + MigrationCanonical.encode("CAPABILITY.FIELD_READ_ONLY") + "|"
            + MigrationCanonical.encode("The capability reported field read only.") + "\n";
        String tampered = content.replace("diagnostics=1\n", "diagnostics=2\n");
        int reportHashIndex = tampered.lastIndexOf("report-hash=");
        tampered = tampered.substring(0, reportHashIndex) + diagnostic + tampered.substring(reportHashIndex);
        String canonical = tampered.substring(0, tampered.lastIndexOf("report-hash="));
        Files.writeString(report, canonical + "report-hash=" + MigrationCanonical.sha256(canonical) + "\n");

        MigrationException failure = assertThrows(MigrationException.class, entrypoint::execute);

        assertEquals("Committed Offline Upgrade Final Report Diagnostics Do Not Match", failure.getMessage());
    }

    @Test
    void retainedPlanAndQuarantineReportBodiesAreVerifiedBeforeJournalCreation() throws IOException {
        Path planSource = source("tampered-plan");
        OfflineReplacementUpgradeEntrypoint planEntrypoint = entrypoint(planSource, "tampered-plan", boundMarkerWriter());
        UpgradeDryRunResult planDryRun = planEntrypoint.dryRun();
        Path plan = control(planSource).resolve("plans/tampered-plan.plan");
        Files.writeString(plan, Files.readString(plan).replace("target-format=2", "target-format=1"));
        assertThrows(MigrationException.class, () -> planEntrypoint.apply(planDryRun));
        assertFalse(Files.exists(control(planSource).resolve("journals/tampered-plan.journal")));

        Path reportSource = source("tampered-report");
        OfflineReplacementUpgradeEntrypoint reportEntrypoint = entrypoint(reportSource, "tampered-report", boundMarkerWriter());
        UpgradeDryRunResult reportDryRun = reportEntrypoint.dryRun();
        Path report = control(reportSource).resolve("plans/tampered-report.plan.report");
        Files.writeString(report, Files.readString(report).replace("records=0", "records=1"));
        assertThrows(MigrationException.class, () -> reportEntrypoint.apply(reportDryRun));
        assertFalse(Files.exists(control(reportSource).resolve("journals/tampered-report.journal")));
    }

    @Test
    void markerFailureAfterRootSwapRollsBackAndNeverCommits() throws IOException {
        Path source = source("marker-failure");
        TransactionMarkerWriter failing = new TransactionMarkerWriter() {
            @Override
            public void write(MigrationPlan plan, StagedMigration staged) throws IOException {
                throw new IOException("marker write failed");
            }
        };
        OfflineReplacementUpgradeEntrypoint.RunResult run = entrypoint(source, "marker-failure", failing).execute();

        assertEquals(UpgradeStatus.FAILED, run.apply().status());
        assertFalse(run.apply().changed());
        assertEquals("legacy", Files.readString(source.resolve("value.txt")));
        assertFalse(Files.exists(MigrationActivationMarker.markerPath(source)));
        assertEquals(MigrationJournalState.ROLLED_BACK,
            MigrationJournal.open(control(source).resolve("journals/marker-failure.journal")).currentState().orElseThrow());
    }

    @Test
    void sourceMutationAfterDryRunIsRejectedBeforeActivation() throws IOException {
        Path source = source("mutated");
        OfflineReplacementUpgradeEntrypoint entrypoint = entrypoint(source, "mutated", boundMarkerWriter());
        UpgradeDryRunResult dryRun = entrypoint.dryRun();
        Files.writeString(source.resolve("value.txt"), "mutated-after-dry-run");

        UpgradeApplyResult result = entrypoint.apply(dryRun);

        assertEquals(UpgradeStatus.FAILED, result.status());
        assertFalse(result.changed());
        assertFalse(Files.exists(MigrationActivationMarker.markerPath(source)));
        assertEquals("mutated-after-dry-run", Files.readString(source.resolve("value.txt")));
        assertFalse(Files.exists(control(source).resolve("journals/mutated.journal")));
    }

    private OfflineReplacementUpgradeEntrypoint entrypoint(Path source, String migrationId, ActivationMarkerWriter writer)
        throws IOException {
        return entrypoint(source, migrationId, writer, new DiagnosticSet(List.of()));
    }

    private OfflineReplacementUpgradeEntrypoint entrypoint(
        Path source,
        String migrationId,
        ActivationMarkerWriter writer,
        DiagnosticSet diagnostics
    ) throws IOException {
        return entrypoint(source, migrationId, writer, diagnostics, OfflineReplacementUpgradeEntrypoint.CommittedRepairFault.none());
    }

    private OfflineReplacementUpgradeEntrypoint entrypoint(
        Path source,
        String migrationId,
        ActivationMarkerWriter writer,
        DiagnosticSet diagnostics,
        OfflineReplacementUpgradeEntrypoint.CommittedRepairFault committedRepairFault
    ) throws IOException {
        return entrypoint(source, migrationId, writer, diagnostics, committedRepairFault, participants(source));
    }

    private OfflineReplacementUpgradeEntrypoint entrypoint(
        Path source,
        String migrationId,
        ActivationMarkerWriter writer,
        DiagnosticSet diagnostics,
        OfflineReplacementUpgradeEntrypoint.CommittedRepairFault committedRepairFault,
        PersistenceParticipantRegistry participants
    ) throws IOException {
        SnapshotMetadata metadata = metadata(migrationId);
        UpgradeSourceWindow window = new UpgradeSourceWindow(ReplacementUpgrader.VERSION, 1, "legacy-build", 2, "replacement-1");
        MigrationOperation operation = operation();
        AuthorityFixture authority = authority(source, migrationId, metadata, participants, operation);
        OfflineReplacementUpgradeEntrypoint.Request request = new OfflineReplacementUpgradeEntrypoint.Request(
            source,
            control(source),
            metadata,
            window,
            (snapshot, ignored) -> new UpgradeProposal(
                new MigrationPlan(snapshot.metadata().snapshotId(), snapshot.manifest().manifestHash(), 1, 2,
                    QuarantineReport.empty().reportHash(), List.of(operation), INVOCATION_HASH),
                QuarantineReport.empty(),
                diagnostics),
            participants,
            typedStager(new ReplacementStager(), migrationId),
            null,
            null,
            null,
            0,
            migrationId,
            activationWriter(writer, authority.binding()),
            authority.trustAnchor(),
            authority.grant());
        return new OfflineReplacementUpgradeEntrypoint(request.withProductionPersistenceCoordination(
            coordination(source, migrationId)), committedRepairFault);
    }

    private static Diagnostic deterministicDiagnostic() {
        return Diagnostic.builder("CAPABILITY.FIELD_READ_ONLY", DiagnosticSeverity.WARNING, DiagnosticPhase.CAPABILITY, "client")
            .messageKey(new ContractRef<>(new OwnerId("restudio.resync"), new NodeId("diagnostic.capability-field-read-only")))
            .message("The capability reported field read only.")
            .remediation("Use the declared fallback or leave the field unchanged.")
            .correlationId(UUID.fromString("33333333-3333-4333-8333-333333333333"))
            .build();
    }

    private static final class ReplacementStager implements MigrationStager {
        @Override
        public StagedMigration stage(Path sourceRoot, Path stagingRoot, MigrationPlan plan) throws IOException {
            StagedMigration staged = new DirectoryMigrationStager().stage(sourceRoot, stagingRoot, plan);
            Files.writeString(staged.root().resolve("value.txt"), "replacement");
            return new StagedMigration(staged.root(), staged.previousRoot(), staged.planHash(), TreeDigest.of(staged.root()));
        }
    }

    private interface TransactionMarkerWriter extends ActivationMarkerWriter {
    }

    private record AuthorityFixture(
        ProductionAuthorityBundle bundle,
        ProductionAuthorityTrustAnchor trustAnchor,
        AuthorityUseGrant grant,
        ActivationMarkerWriter.Binding binding
    ) {
    }

    private static final class DeterministicAuthoritySigner implements ProductionAuthoritySigner {
        private static final String PRIVATE_KEY = "MC4CAQAwBQYDK2VwBCIEIJ1hsZ3v/VpguoRK9JLsLMREScVpezJpGXA7rAMcrn9g";
        private static final String PUBLIC_KEY = "MCowBQYDK2VwAyEA11qYAYKxCrfVS/7TyWQHOg7hcvPapiMlrwIaaPcHURo=";

        private final ServerId serverId;
        private final Path dataRoot;
        private final PrivateKey privateKey;

        private DeterministicAuthoritySigner(ServerId serverId, Path dataRoot) throws GeneralSecurityException {
            this.serverId = serverId;
            this.dataRoot = dataRoot;
            this.privateKey = KeyFactory.getInstance(ProductionAuthorityBundle.SIGNATURE_ALGORITHM)
                .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(PRIVATE_KEY)));
        }

        @Override
        public ServerId serverId() {
            return serverId;
        }

        @Override
        public String installAuthorityHash() throws IOException {
            return ProductionAuthorityBundle.installAuthorityDigest(dataRoot);
        }

        @Override
        public String signingPublicKey() {
            return PUBLIC_KEY;
        }

        @Override
        public String sign(byte[] canonicalPayload) throws IOException {
            try {
                Signature signature = Signature.getInstance(ProductionAuthorityBundle.SIGNATURE_ALGORITHM);
                signature.initSign(privateKey);
                signature.update(canonicalPayload);
                return Base64.getEncoder().encodeToString(signature.sign());
            } catch (GeneralSecurityException exception) {
                throw new IOException("Deterministic Authority Signature Is Unavailable", exception);
            }
        }
    }

    private ActivationMarkerWriter boundMarkerWriter() {
        return ActivationMarkerWriter.none();
    }

    private ActivationMarkerWriter activationWriter(ActivationMarkerWriter writer, ActivationMarkerWriter.Binding binding) {
        if (!(writer instanceof TransactionMarkerWriter)) {
            return ActivationMarkerWriter.replacementRoot(binding);
        }
        return new ActivationMarkerWriter() {
            @Override
            public void write(MigrationPlan plan, StagedMigration staged) throws IOException {
                writer.write(plan, staged);
            }

            @Override
            public Optional<Binding> binding() {
                return Optional.of(binding);
            }
        };
    }

    private SnapshotMetadata metadata(String migrationId) {
        return new SnapshotMetadata(1, SnapshotId.deterministic(migrationId).canonicalText(),
            Instant.parse("2026-01-01T00:00:00Z"), "legacy-build", "b".repeat(64), Map.of());
    }

    private PersistenceParticipantRegistry participants(Path source) {
        PersistenceParticipantRegistry participants = new PersistenceParticipantRegistry();
        participants.register(new RebindableParticipant(source));
        return participants;
    }

    private MigrationOperation operation() {
        return new MigrationOperation("copy", "offline.copy", "value.txt", "value.txt", "", "");
    }

    private Path coordination(Path source, String migrationId) throws IOException {
        return Files.createDirectories(temporary.resolve("coordination").resolve(migrationId));
    }

    private MigrationStager typedStager(MigrationStager delegate, String migrationId) {
        return new MigrationStager() {
            @Override
            public StagedMigration stage(Path sourceRoot, Path stagingRoot, MigrationPlan plan) throws IOException {
                QuarantineReport report = QuarantineReport.empty();
                return typedEvidence(delegate.stage(sourceRoot, stagingRoot, plan), sourceRoot, plan, report,
                    report.accept("offline-stager", Instant.EPOCH), migrationId);
            }

            @Override
            public StagedMigration stage(
                Path sourceRoot,
                Path stagingRoot,
                MigrationPlan plan,
                QuarantineReport report,
                QuarantineAcceptance acceptance
            ) throws IOException {
                return typedEvidence(delegate.stage(sourceRoot, stagingRoot, plan, report, acceptance), sourceRoot,
                    plan, report, acceptance, migrationId);
            }
        };
    }

    private StagedMigration typedEvidence(
        StagedMigration staged,
        Path sourceRoot,
        MigrationPlan plan,
        QuarantineReport report,
        QuarantineAcceptance acceptance,
        String migrationId
    ) throws IOException {
        SnapshotService snapshots = new SnapshotService(new MigrationFence());
        VerifiedSnapshotAdmission sourceAdmission = snapshots.admitExported(sourceRoot);
        Path postStageRoot = temporary.resolve("typed-stage-evidence").resolve(migrationId);
        deleteTree(postStageRoot);
        deleteTree(postStageRoot.resolveSibling(postStageRoot.getFileName() + ".manifest"));
        deleteTree(postStageRoot.resolveSibling(postStageRoot.getFileName() + ".state"));
        deleteTree(postStageRoot.resolveSibling(postStageRoot.getFileName() + ".metadata"));
        PersistenceParticipantRegistry evidenceParticipants = participants(staged.root());
        SnapshotMetadata postStageMetadata = new SnapshotMetadata(
            sourceAdmission.metadata().formatVersion(),
            SnapshotId.deterministic(migrationId + "-post-stage").canonicalText(),
            Instant.parse("2026-01-02T00:00:00Z"),
            "replacement-1",
            sourceAdmission.metadata().catalogChecksum(),
            sourceAdmission.metadata().extensionVersions());
        Snapshot postStage = snapshots.create(staged.root(), postStageRoot, postStageMetadata, evidenceParticipants);
        VerifiedSnapshotAdmission postStageAdmission = snapshots.admitExported(postStage.root());
        AssetAdoptionArtifactProducer.StageOutput output = new AssetAdoptionArtifactProducer.StageOutput(
            plan.planHash(), sourceAdmission.metadata().snapshotId(), sourceAdmission.manifestHash(),
            postStageAdmission, List.of(), report, acceptance, List.of());
        return staged.attachTypedEvidence(new StagedMigration.TypedEvidence(sourceAdmission, output));
    }

    private AuthorityFixture authority(Path source, String migrationId) throws IOException {
        SnapshotMetadata metadata = metadata(migrationId);
        return authority(source, migrationId, metadata, participants(source), operation());
    }

    private AuthorityFixture authority(
        Path source,
        String migrationId,
        SnapshotMetadata metadata,
        PersistenceParticipantRegistry participants,
        MigrationOperation operation
    ) throws IOException {
        Path key = source.toAbsolutePath().normalize();
        AuthorityFixture retained = authorityFixtures.get(key);
        if (retained != null) {
            return retained;
        }
        SnapshotManifest manifest = SnapshotManifest.scan(source, metadata, participants);
        MigrationPlan plan = new MigrationPlan(metadata.snapshotId(), manifest.manifestHash(), 1, 2,
            QuarantineReport.empty().reportHash(), List.of(operation), INVOCATION_HASH);
        try {
            ServerId serverId = ServerId.deterministic(source.getFileName().toString());
            DeterministicAuthoritySigner signer = new DeterministicAuthoritySigner(serverId, source);
            ProductionAuthorityTrustAnchor trustAnchor = ProductionAuthorityTrustAnchor.pinned(serverId,
                "installation-" + migrationId, ProductionAuthorityBundle.installAuthorityDigest(source),
                "authority-key-" + migrationId, signer.signingPublicKey());
            Instant now = Instant.now();
            ProductionAuthorityBundle bundle = ProductionAuthorityBundle.create(signer, trustAnchor,
                SnapshotId.parseCanonicalText(metadata.snapshotId()), now.minusSeconds(1),
                ContentHash.of("b".repeat(64)), 1, new CatalogVersion(1, 0), ContentHash.of("e".repeat(64)), 1,
                "f".repeat(64), 1);
            AuthorityUseGrant grant = AuthorityUseGrant.issue(bundle, trustAnchor, signer, source, migrationId,
                INVOCATION_HASH, AuthorityUseGrant.planPreimageHash(plan.canonicalText()),
                now.minusSeconds(1), now.plusSeconds(3600),
                "grant-" + migrationId);
            ActivationMarkerWriter.Binding binding = new ActivationMarkerWriter.Binding(bundle, trustAnchor, grant);
            AuthorityFixture fixture = new AuthorityFixture(bundle, trustAnchor, grant, binding);
            authorityFixtures.put(key, fixture);
            return fixture;
        } catch (GeneralSecurityException exception) {
            throw new IOException("Could Not Build Deterministic Authority Fixture", exception);
        }
    }

    private Path source(String name) throws IOException {
        Path source = Files.createDirectory(temporary.resolve(name));
        Files.writeString(source.resolve("value.txt"), "legacy");
        ServerId serverId = ServerId.deterministic(name);
        Files.writeString(source.resolve("server-id"), serverId.canonicalText() + "\n");
        Files.writeString(source.resolve(ProductionAuthorityBundle.INSTALL_AUTHORITY_FILE), "install-authority-" + name);
        return source;
    }

    private static Path control(Path source) {
        return source.getParent().resolve(OfflineReplacementUpgradeEntrypoint.CONTROL_DIRECTORY_NAME);
    }

    private InterruptedTransaction interrupt(
        UpgradeDryRunResult dryRun,
        Path source,
        String migrationId,
        MigrationJournalState finalState,
        boolean completeRootSwap
    ) throws IOException {
        UpgradeProposal proposal = dryRun.proposal().orElseThrow();
        Snapshot sourceSnapshot = dryRun.sourceSnapshot().orElseThrow();
        MigrationPlan plan = proposal.plan();
        QuarantineReport report = proposal.quarantineReport();
        QuarantineAcceptance acceptance = report.accept("offline-upgrader", Instant.EPOCH);
        Path control = control(source);
        Path staging = control.resolve("staging").resolve(migrationId);
        StagedMigration staged = typedEvidence(
            new ReplacementStager().stage(sourceSnapshot.root(), staging, plan),
            sourceSnapshot.root(), plan, report, acceptance, migrationId);
        AuthorityFixture authority = authority(source, migrationId);
        String archivedSourceDigest = TreeDigest.of(source);
        Path journalPath = control.resolve("journals").resolve(migrationId + ".journal");
        MigrationJournal journal = MigrationJournal.create(journalPath, migrationId, plan.planHash(), new MigrationJournal.Binding(
            plan.sourceSnapshotId(),
            plan.sourceManifestHash(),
            report.reportHash(),
            acceptance.acceptanceHash(),
            "",
            archivedSourceDigest,
            authority.binding().replacementCatalogHash(),
            authority.binding().runtimeBindingManifestHash(),
            authority.binding().runtimeBindingManifestVersion(),
            authority.binding().participantReadinessHash(),
            authority.binding().participantReadinessVersion(),
            authority.bundle(),
            authority.binding().authorityTrustAnchorHash(),
            authority.grant()));
        journal.transition(MigrationJournalState.PREPARED, "prepared before crash");
        journal.transition(MigrationJournalState.TRANSFORMING, "transforming before crash");
        journal.transition(MigrationJournalState.VALIDATING, "validating before crash");
        journal.bindStagedReplacementDigest(staged.contentHash());
        if (completeRootSwap) {
            AcceptedStagePublisher.Result publication = new ProductionAcceptedStagePublisher(
                coordination(source, migrationId)).publish(new AcceptedStagePublisher.Publication(
                    migrationId,
                    sourceSnapshot,
                    journal.binding(),
                    plan.planHash(),
                    staged,
                    report,
                    acceptance,
                    staged.stageOutput().orElseThrow(),
                    ProductionAcceptedStagePublisher.CONTRACT_IDENTITY));
            journal.bindPublication(publication.publicationBinding());
        }
        journal.transition(MigrationJournalState.STAGED, "staged before crash");
        moveSourceToArchive(source, control, migrationId);
        if (completeRootSwap) {
            Files.move(staging, source, StandardCopyOption.ATOMIC_MOVE);
        }
        if (finalState == MigrationJournalState.FAILED) {
            journal.transition(MigrationJournalState.FAILED, "failure persisted before restart");
        } else if (finalState == MigrationJournalState.ACTIVATED) {
            journal.transition(MigrationJournalState.ACTIVATED, "root swap persisted before restart");
        }
        return new InterruptedTransaction(journalPath, control.resolve("archives").resolve(migrationId + "-source"), archivedSourceDigest);
    }

    private Path prepareJournalAtState(
        UpgradeDryRunResult dryRun,
        Path source,
        String migrationId,
        MigrationJournalState state
    ) throws IOException {
        UpgradeProposal proposal = dryRun.proposal().orElseThrow();
        MigrationPlan plan = proposal.plan();
        QuarantineReport report = proposal.quarantineReport();
        QuarantineAcceptance acceptance = report.accept("offline-upgrader", Instant.EPOCH);
        AuthorityFixture authority = authority(source, migrationId);
        Path control = control(source);
        Path journalPath = control.resolve("journals").resolve(migrationId + ".journal");
        MigrationJournal journal = MigrationJournal.create(journalPath, migrationId, plan.planHash(), new MigrationJournal.Binding(
            plan.sourceSnapshotId(),
            plan.sourceManifestHash(),
            report.reportHash(),
            acceptance.acceptanceHash(),
            "",
            TreeDigest.of(source),
            authority.binding().replacementCatalogHash(),
            authority.binding().runtimeBindingManifestHash(),
            authority.binding().runtimeBindingManifestVersion(),
            authority.binding().participantReadinessHash(),
            authority.binding().participantReadinessVersion(),
            authority.bundle(),
            authority.binding().authorityTrustAnchorHash(),
            authority.grant()));
        journal.transition(MigrationJournalState.PREPARED, "prepared before crash");
        if (state == MigrationJournalState.PREPARED) {
            return journalPath;
        }
        journal.transition(MigrationJournalState.TRANSFORMING, "transforming before crash");
        if (state == MigrationJournalState.TRANSFORMING) {
            return journalPath;
        }
        journal.transition(MigrationJournalState.VALIDATING, "validating before crash");
        return journalPath;
    }

    private static void moveSourceToArchive(Path source, Path control, String migrationId) throws IOException {
        Path archive = control.resolve("archives").resolve(migrationId + "-source");
        Files.createDirectories(archive.getParent());
        Files.move(source, archive, StandardCopyOption.ATOMIC_MOVE);
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException exception) {
                    throw new UncheckedIOException(exception);
                }
            });
        } catch (UncheckedIOException exception) {
            throw exception.getCause();
        }
    }

    private final class RebindableParticipant implements RebindablePersistenceParticipant {
        private final Path initialRoot;
        private Path activeRoot;

        private RebindableParticipant(Path root) {
            initialRoot = root.toAbsolutePath().normalize();
            activeRoot = initialRoot;
        }

        @Override
        public String owner() {
            return "core";
        }

        @Override
        public Path root() {
            return activeRoot;
        }

        @Override
        public Path rebindScope() {
            return initialRoot;
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
        public void rebind(Path activeRoot) throws IOException {
            this.activeRoot = MigrationPaths.requireDirectory(activeRoot, "activeRoot");
        }

        @Override
        public void healthCheck() {
        }
    }

    private record InterruptedTransaction(Path journalPath, Path archiveRoot, String archivedSourceDigest) {
    }
}
