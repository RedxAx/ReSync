package restudio.resync.restore;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.migration.DirectoryMigrationStager;
import restudio.resync.migration.MigrationFence;
import restudio.resync.migration.MigrationJournal;
import restudio.resync.migration.MigrationJournalState;
import restudio.resync.migration.MigrationOperation;
import restudio.resync.migration.MigrationOperationType;
import restudio.resync.migration.MigrationPlan;
import restudio.resync.migration.PersistenceParticipantRegistry;
import restudio.resync.migration.ProductionSnapshotMetadataManifest;
import restudio.resync.migration.QuarantineReport;
import restudio.resync.migration.RebindablePersistenceParticipant;
import restudio.resync.migration.Snapshot;
import restudio.resync.migration.SnapshotMetadata;
import restudio.resync.migration.SnapshotService;
import restudio.resync.migration.StagingArtifactRecovery;
import restudio.resync.migration.StagedMigration;
import restudio.resync.migration.TreeDigest;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RestoreCrashMatrixTest {
    private static final String CATALOG_CHECKSUM = "b".repeat(64);
    private static final String BINDING_HASH = "c".repeat(64);
    private static final Map<String, String> EXTENSIONS = Map.of("extension.test", "1.0.0");

    @TempDir
    Path temporary;

    @ParameterizedTest
    @EnumSource(TerminalCut.class)
    void recoversPersistedFailureAndRollbackStates(TerminalCut cut) throws IOException {
        Fixture fixture = fixture(metadata("restore-snapshot", "resync-test", 1, CATALOG_CHECKSUM, EXTENSIONS));
        RestoreRequest request = fixture.request(fixture.snapshot(), policy(), "current-before-crash", "restore-staging", "restore-journal");
        Path previous = fixture.activation().activeRoot().orElseThrow();
        String previousDigest = TreeDigest.of(previous);

        prepareCrashCut(fixture, request, cut);

        RestoreRecoveryResult result = fixture.service().recover(request);
        Path active = fixture.activation().activeRoot().orElseThrow();

        assertEquals(cut.finalState, result.finalState());
        assertEquals(cut.finalState, MigrationJournal.open(request.journalPath()).currentState().orElseThrow());
        assertEquals(cut.targetActive ? request.restoreStagingRoot() : previous, active);
        assertEquals(cut.targetActive ? "target" : "previous", Files.readString(active.resolve("value.txt")));
        assertEquals(previousDigest, TreeDigest.of(previous));
        assertEquals("previous", Files.readString(previous.resolve("value.txt")));
        assertEquals(active, fixture.participant().root());
        assertEquals(cut.targetActive, Files.exists(request.restoreStagingRoot()));
    }

    @ParameterizedTest
    @EnumSource(ProductionCut.class)
    void recoversEveryProductionRestoreCrashCut(ProductionCut cut) throws IOException {
        ProductionFixture fixture = productionFixture(cut);
        RestoreRequest request = fixture.request();
        Path previous = fixture.activation().activeRoot().orElseThrow();
        String previousDigest = TreeDigest.of(previous);

        assertThrows(RestoreCrash.class, () -> fixture.service().restore(request));
        assertEquals(cut.journalState, MigrationJournal.open(request.journalPath()).currentState().orElseThrow());
        if (cut == ProductionCut.PREPARED_AFTER_CURRENT_SNAPSHOT) {
            assertEquals(previousDigest, TreeDigest.of(request.currentSnapshotStagingRoot()));
            assertTrue(Files.exists(sidecar(request.currentSnapshotStagingRoot(), ".manifest")));
            assertTrue(Files.exists(sidecar(request.currentSnapshotStagingRoot(), ".state")));
        }
        if (cut == ProductionCut.PREPARED_BEFORE_CURRENT_SNAPSHOT) {
            assertFalse(Files.exists(request.currentSnapshotStagingRoot()));
        }
        fixture.fault().disarm();

        RestoreRecoveryResult result = fixture.service().recover(request);
        Path active = fixture.activation().activeRoot().orElseThrow();

        assertEquals(cut.finalState, result.finalState());
        assertEquals(cut.finalState, MigrationJournal.open(request.journalPath()).currentState().orElseThrow());
        assertEquals(cut.targetActive ? request.restoreStagingRoot() : previous, active);
        assertEquals(cut.targetActive ? "target" : "previous", Files.readString(active.resolve("value.txt")));
        assertEquals(previousDigest, TreeDigest.of(previous));
        assertEquals(active, fixture.participant().root());
        if (cut.journalState == MigrationJournalState.ACTIVATED) {
            assertTrue(fixture.participant().rebinds > 0);
            assertTrue(fixture.participant().healthChecks > 0);
            assertTrue(fixture.participant().resumes > 0);
        }
    }

    @Test
    void committedRecoveryReestablishesParticipantBindingHealthAndResume() throws IOException {
        ProductionFixture fixture = productionFixture(null);
        RestoreRequest request = fixture.request();

        RestoreResult restored = fixture.service().restore(request);

        assertEquals(MigrationJournalState.COMMITTED, restored.finalState());
        assertEquals(request.restoreStagingRoot(), fixture.participant().root());
        assertTrue(fixture.participant().rebinds > 0);
        assertTrue(fixture.participant().healthChecks > 0);
        assertTrue(fixture.participant().resumes > 0);
        fixture.participant().reset();

        RestoreRecoveryResult recovered = fixture.service().recover(request);

        assertEquals(MigrationJournalState.COMMITTED, recovered.finalState());
        assertEquals(request.restoreStagingRoot(), fixture.participant().root());
        assertEquals(1, fixture.participant().rebinds);
        assertEquals(2, fixture.participant().healthChecks);
        assertEquals(1, fixture.participant().resumes);
    }

    @Test
    void interruptedRestoreMovesCurrentSnapshotAndPartialRestoreToDeterministicEvidence() throws IOException {
        ProductionFixture fixture = productionFixture(ProductionCut.TRANSFORMING_AFTER_STAGE);
        RestoreRequest request = fixture.request();

        assertThrows(RestoreCrash.class, () -> fixture.service().restore(request));
        fixture.fault().disarm();

        RestoreRecoveryResult result = fixture.service().recover(request);

        assertEquals(MigrationJournalState.ROLLED_BACK, result.finalState());
        assertFalse(Files.exists(request.currentSnapshotStagingRoot()));
        assertFalse(Files.exists(request.restoreStagingRoot()));
        Path currentEvidence = StagingArtifactRecovery.quarantinePath(request.currentSnapshotStagingRoot(),
            StagingArtifactRecovery.RESTORE_CURRENT_SNAPSHOT_DIRECTORY);
        Path restoreEvidence = StagingArtifactRecovery.quarantinePath(request.restoreStagingRoot(),
            StagingArtifactRecovery.RESTORE_STAGING_DIRECTORY);
        assertTrue(Files.exists(currentEvidence.resolve("value.txt")));
        assertTrue(Files.exists(restoreEvidence.resolve("value.txt")));
    }

    @Test
    void interruptedRollbackRejectsChangedPreviousRootBeforeRebindingOrQuarantine() throws IOException {
        ProductionFixture fixture = productionFixture(ProductionCut.ACTIVATED_AFTER_VERIFY);
        RestoreRequest request = fixture.request();

        assertThrows(RestoreCrash.class, () -> fixture.service().restore(request));
        RestoreJournalDetail detail = RestoreJournalDetail.find(MigrationJournal.open(request.journalPath()).entries()).orElseThrow();
        Path previous = detail.staged().previousRoot().orElseThrow();
        Files.writeString(previous.resolve("value.txt"), "changed");
        Files.writeString(request.restoreStagingRoot().resolve("value.txt"), "tampered");
        fixture.fault().disarm();

        assertThrows(IOException.class, () -> fixture.service().recover(request));

        assertEquals(previous, fixture.activation().activeRoot().orElseThrow());
        assertEquals(detail.staged().root(), fixture.participant().root());
        assertTrue(Files.exists(request.restoreStagingRoot()));
        assertTrue(Files.exists(request.currentSnapshotStagingRoot()));
        assertEquals("changed", Files.readString(previous.resolve("value.txt")));
        assertEquals(MigrationJournalState.FAILED, MigrationJournal.open(request.journalPath()).currentState().orElseThrow());
    }

    @Test
    void recoveryRefusesToQuarantineAStagingRootNestedUnderTheActiveRoot() throws IOException {
        Fixture fixture = fixture(metadata("restore-snapshot", "resync-test", 1, CATALOG_CHECKSUM, EXTENSIONS));
        Path active = fixture.activation().activeRoot().orElseThrow();
        Path nested = active.resolve("nested-restore-staging");
        Files.createDirectories(nested);
        Path marker = Files.writeString(nested.resolve("keep.txt"), "keep");
        RestoreRequest base = fixture.request(fixture.snapshot(), policy(), "boundary-current", "boundary-staging", "boundary-journal");
        RestoreRequest request = new RestoreRequest(fixture.snapshot(), policy(), base.currentSnapshotStagingRoot(),
            base.currentSnapshotMetadata(), nested, base.journalPath());
        MigrationJournal journal = MigrationJournal.create(request.journalPath(), "restore-boundary", fixture.activation().planHash(fixture.snapshot()));
        journal.transition(MigrationJournalState.PREPARED, "prepared");
        journal.transition(MigrationJournalState.FAILED, "failed");

        assertThrows(IOException.class, () -> fixture.service().rollback(request));

        assertEquals(active, fixture.activation().activeRoot().orElseThrow());
        assertTrue(Files.exists(marker));
        assertEquals(active, fixture.participant().root());
    }

    @Test
    void preflightRejectsSnapshotsWithoutProductionMetadataEvidence() throws IOException {
        Fixture fixture = fixture(metadata("restore-snapshot", "resync-test", 1, CATALOG_CHECKSUM, EXTENSIONS));
        Path metadata = ProductionSnapshotMetadataManifest.pathFor(fixture.snapshot().root());
        Files.delete(metadata);

        RestorePreflight result = fixture.service().preflight(fixture.request(fixture.snapshot(), policy(),
            "metadata-current", "metadata-staging", "metadata-journal"));

        assertFalse(result.passed());
        assertTrue(result.checks().stream().anyMatch(check -> check.name().equals("snapshot-manifest") && !check.passed()));
    }

    @Test
    void preflightRejectsSnapshotsWithoutVerifiedStateEvidence() throws IOException {
        Fixture fixture = fixture(metadata("restore-snapshot", "resync-test", 1, CATALOG_CHECKSUM, EXTENSIONS));
        Files.delete(fixture.snapshot().statePath());

        RestorePreflight result = fixture.service().preflight(fixture.request(fixture.snapshot(), policy(),
            "state-missing-current", "state-missing-staging", "state-missing-journal"));

        assertFalse(result.passed());
        assertTrue(result.checks().stream().anyMatch(check -> check.name().equals("snapshot-manifest") && !check.passed()));
    }

    @Test
    void preflightRejectsSnapshotsWithUnverifiedStateEvidence() throws IOException {
        Fixture fixture = fixture(metadata("restore-snapshot", "resync-test", 1, CATALOG_CHECKSUM, EXTENSIONS));
        String hash = fixture.snapshot().manifest().manifestHash();
        Files.writeString(fixture.snapshot().statePath(), "state=VERIFIED\nverified=false\nmanifest-hash=" + hash + "\nfailures=0\n");

        RestorePreflight result = fixture.service().preflight(fixture.request(fixture.snapshot(), policy(),
            "state-unverified-current", "state-unverified-staging", "state-unverified-journal"));

        assertFalse(result.passed());
        assertTrue(result.checks().stream().anyMatch(check -> check.name().equals("snapshot-manifest") && !check.passed()));
    }

    @Test
    void preflightRejectsSnapshotsWithFailedStateEvidence() throws IOException {
        Fixture fixture = fixture(metadata("restore-snapshot", "resync-test", 1, CATALOG_CHECKSUM, EXTENSIONS));
        String hash = fixture.snapshot().manifest().manifestHash();
        Files.writeString(fixture.snapshot().statePath(), "state=FAILED\nverified=false\nmanifest-hash=" + hash + "\nfailures=1\nfailure=ZmFpbGVk\n");

        RestorePreflight result = fixture.service().preflight(fixture.request(fixture.snapshot(), policy(),
            "state-failed-current", "state-failed-staging", "state-failed-journal"));

        assertFalse(result.passed());
        assertTrue(result.checks().stream().anyMatch(check -> check.name().equals("snapshot-manifest") && !check.passed()));
    }

    @ParameterizedTest
    @EnumSource(Incompatibility.class)
    void rejectsEveryIncompatibleRestoreBeforeMutation(Incompatibility incompatibility) throws IOException {
        SnapshotMetadata targetMetadata = incompatibility.metadata();
        Fixture fixture = fixture(targetMetadata);
        RestoreRequest request = fixture.request(fixture.snapshot(), policy(), "incompatible-current", "incompatible-staging", "incompatible-journal");
        Path previous = fixture.activation().activeRoot().orElseThrow();
        String previousDigest = TreeDigest.of(previous);
        Path control = temporary.resolve("restore-control");
        Path pointer = control.resolve("active-root");
        String controlDigest = TreeDigest.of(control);
        byte[] pointerBytes = Files.readAllBytes(pointer);
        String targetDigest = TreeDigest.of(fixture.snapshot().root());
        byte[] targetManifest = Files.readAllBytes(fixture.snapshot().manifestPath());
        byte[] targetState = Files.readAllBytes(fixture.snapshot().statePath());
        fixture.participant().reset();

        assertThrows(IOException.class, () -> fixture.service().restore(request));

        assertEquals(previous, fixture.activation().activeRoot().orElseThrow());
        assertEquals(previous, fixture.participant().root());
        assertEquals(previousDigest, TreeDigest.of(previous));
        assertEquals(controlDigest, TreeDigest.of(control));
        assertArrayEquals(pointerBytes, Files.readAllBytes(pointer));
        assertEquals(targetDigest, TreeDigest.of(fixture.snapshot().root()));
        assertArrayEquals(targetManifest, Files.readAllBytes(fixture.snapshot().manifestPath()));
        assertArrayEquals(targetState, Files.readAllBytes(fixture.snapshot().statePath()));
        assertEquals(0, fixture.participant().flushes);
        assertEquals(0, fixture.participant().quiesces);
        assertEquals(0, fixture.participant().rebinds);
        assertEquals(0, fixture.participant().resumes);
        assertFalse(Files.exists(request.currentSnapshotStagingRoot()));
        assertFalse(Files.exists(request.restoreStagingRoot()));
        assertFalse(Files.exists(request.journalPath()));
    }

    @Test
    void restoresActualPreAndPostMigrationSnapshotsInTheirCompatibleEnvironment() throws IOException, URISyntaxException {
        Path preMigration = Files.createDirectory(temporary.resolve("pre-migration"));
        Path legacyGraph = preMigration.resolve("legacy/flows/fixture-flow.json");
        Files.createDirectories(legacyGraph.getParent());
        Path graphFixture = Path.of(getClass().getClassLoader().getResource(
            "fixtures/node-replacement/full-folder/populated/assets/Blueprints/Flows/fixture-flow.json").toURI());
        Files.copy(graphFixture, legacyGraph);
        Files.writeString(preMigration.resolve("server-state.txt"), "preserved");
        Snapshot preSnapshot = snapshot(preMigration, temporary.resolve("pre-migration-snapshot"),
            metadata("pre-migration", "legacy-build", 1, CATALOG_CHECKSUM, EXTENSIONS));

        MigrationPlan plan = new MigrationPlan(preSnapshot.metadata().snapshotId(), preSnapshot.manifest().manifestHash(), 1, 2,
            QuarantineReport.empty().reportHash(), List.of(new MigrationOperation(MigrationOperationType.MOVE, "flow.graph",
                "legacy/flows/fixture-flow.json", "replacement/graphs/fixture-flow.json", "", "")));
        StagedMigration migrated = new DirectoryMigrationStager().stage(preSnapshot.root(), temporary.resolve("post-migration-tree"), plan);
        Snapshot postSnapshot = snapshot(migrated.root(), temporary.resolve("post-migration-snapshot"),
            metadata("post-migration", "replacement-build", 1, CATALOG_CHECKSUM, EXTENSIONS));

        AtomicRestoreActivation activation = new AtomicRestoreActivation(temporary.resolve("migration-restore-control"));
        activation.activate(activation.stage(postSnapshot, temporary.resolve("initial-post-migration-active")));
        TrackingParticipant participant = new TrackingParticipant(activation.activeRoot().orElseThrow());
        PersistenceParticipantRegistry participants = registry(participant);
        RestoreService service = new RestoreService(new MigrationFence(), participants, activation);
        RestoreCompatibilityPolicy compatibility = new RestoreCompatibilityPolicy(1, 1, Set.of("legacy-build", "replacement-build"),
            new CatalogVersion(1, 0), new CatalogBinding(1, CATALOG_CHECKSUM, BINDING_HASH), EXTENSIONS);

        String postMigrationDigest = TreeDigest.of(activation.activeRoot().orElseThrow());
        RestoreRequest restorePre = request(preSnapshot, compatibility, "post-before-pre-restore", "pre-restore-staging", "pre-restore-journal",
            metadata("post-before-pre-restore", "replacement-build", 1, CATALOG_CHECKSUM, EXTENSIONS));
        RestoreResult preResult = service.restore(restorePre);
        Path restoredPre = activation.activeRoot().orElseThrow();

        assertEquals(MigrationJournalState.COMMITTED, preResult.finalState());
        assertEquals(TreeDigest.of(preSnapshot.root()), TreeDigest.of(restoredPre));
        assertTrue(Files.exists(restoredPre.resolve("legacy/flows/fixture-flow.json")));
        assertFalse(Files.exists(restoredPre.resolve("replacement/graphs/fixture-flow.json")));
        assertEquals("preserved", Files.readString(restoredPre.resolve("server-state.txt")));
        Snapshot retainedPost = preResult.currentSnapshot().orElseThrow();
        assertEquals(postMigrationDigest, TreeDigest.of(retainedPost.root()));

        RestoreRequest restorePost = request(retainedPost, compatibility, "pre-before-post-restore", "post-restore-staging", "post-restore-journal",
            metadata("pre-before-post-restore", "legacy-build", 1, CATALOG_CHECKSUM, EXTENSIONS));
        RestoreResult postResult = service.restore(restorePost);
        Path restoredPost = activation.activeRoot().orElseThrow();

        assertEquals(MigrationJournalState.COMMITTED, postResult.finalState());
        assertFalse(Files.exists(restoredPost.resolve("legacy/flows/fixture-flow.json")));
        assertTrue(Files.exists(restoredPost.resolve("replacement/graphs/fixture-flow.json")));
        assertEquals("preserved", Files.readString(restoredPost.resolve("server-state.txt")));
        assertEquals(postMigrationDigest, TreeDigest.of(restoredPost));
        assertEquals(restoredPost, participant.root());
    }

    private void prepareCrashCut(Fixture fixture, RestoreRequest request, TerminalCut cut) throws IOException {
        MigrationJournal journal = MigrationJournal.create(request.journalPath(), "restore-crash", fixture.activation().planHash(fixture.snapshot()));
        journal.transition(MigrationJournalState.PREPARED, "prepared");
        if (cut == TerminalCut.FAILED_BEFORE_STAGING) {
            journal.transition(MigrationJournalState.FAILED, "failed-before-staging");
            return;
        }
        if (cut == TerminalCut.ROLLED_BACK) {
            journal.transition(MigrationJournalState.ROLLED_BACK, "rolled-back");
            return;
        }
        journal.transition(MigrationJournalState.TRANSFORMING, "transforming");
        StagedMigration staged = fixture.activation().stage(fixture.snapshot(), request.restoreStagingRoot());
        RestoreJournalDetail detail = RestoreJournalDetail.of(staged, fixture.snapshot().manifest().manifestHash());
        journal.transition(MigrationJournalState.VALIDATING, detail.encode());
        if (cut == TerminalCut.FAILED_AFTER_STAGING) {
            journal.transition(MigrationJournalState.FAILED, "failed-after-staging");
            return;
        }
        journal.transition(MigrationJournalState.STAGED, detail.encode());
        fixture.activation().activate(staged);
        journal.transition(MigrationJournalState.ACTIVATED, detail.encode());
        journal.transition(MigrationJournalState.FAILED, "failed-after-activation");
    }

    private ProductionFixture productionFixture(ProductionCut cut) throws IOException {
        Path previousSource = Files.createDirectory(temporary.resolve("production-previous-source"));
        Files.writeString(previousSource.resolve("value.txt"), "previous");
        Snapshot previousSnapshot = snapshot(previousSource, temporary.resolve("production-previous-snapshot"),
            metadata("production-previous", "resync-test", 1, CATALOG_CHECKSUM, EXTENSIONS));
        AtomicRestoreActivation activation = new AtomicRestoreActivation(temporary.resolve("production-restore-control"));
        activation.activate(activation.stage(previousSnapshot, temporary.resolve("production-initial-active")));

        Path targetSource = Files.createDirectory(temporary.resolve("production-target-source"));
        Files.writeString(targetSource.resolve("value.txt"), "target");
        Snapshot target = snapshot(targetSource, temporary.resolve("production-target-snapshot"),
            metadata("production-target", "resync-test", 1, CATALOG_CHECKSUM, EXTENSIONS));
        RestoreFault fault = new RestoreFault(cut);
        TrackingParticipant participant = new TrackingParticipant(activation.activeRoot().orElseThrow(), fault);
        PersistenceParticipantRegistry participants = registry(participant);
        RestoreActivation faultingActivation = new FaultingRestoreActivation(activation, fault);
        RestoreService service = new RestoreService(new MigrationFence(), participants, faultingActivation);
        return new ProductionFixture(target, activation, participant, fault, service);
    }

    private Fixture fixture(SnapshotMetadata targetMetadata) throws IOException {
        Path previousSource = Files.createDirectory(temporary.resolve("previous-source"));
        Files.writeString(previousSource.resolve("value.txt"), "previous");
        Snapshot previousSnapshot = snapshot(previousSource, temporary.resolve("previous-snapshot"),
            metadata("previous-snapshot", "resync-test", 1, CATALOG_CHECKSUM, EXTENSIONS));
        AtomicRestoreActivation activation = new AtomicRestoreActivation(temporary.resolve("restore-control"));
        activation.activate(activation.stage(previousSnapshot, temporary.resolve("initial-active")));

        Path targetSource = Files.createDirectory(temporary.resolve("target-source"));
        Files.writeString(targetSource.resolve("value.txt"), "target");
        Snapshot targetSnapshot = snapshot(targetSource, temporary.resolve("target-snapshot"), targetMetadata);
        TrackingParticipant participant = new TrackingParticipant(activation.activeRoot().orElseThrow());
        PersistenceParticipantRegistry participants = registry(participant);
        return new Fixture(targetSnapshot, activation, participant, new RestoreService(new MigrationFence(), participants, activation));
    }

    private Snapshot snapshot(Path source, Path staging, SnapshotMetadata metadata) throws IOException {
        return new SnapshotService(new MigrationFence()).create(source, staging, metadata, registry(new TrackingParticipant(source)));
    }

    private PersistenceParticipantRegistry registry(TrackingParticipant participant) {
        PersistenceParticipantRegistry participants = new PersistenceParticipantRegistry();
        participants.register(participant);
        return participants;
    }

    private RestoreRequest request(Snapshot snapshot, RestoreCompatibilityPolicy compatibility, String currentSnapshot,
                                   String restoreStaging, String journal, SnapshotMetadata currentMetadata) {
        return new RestoreRequest(snapshot, compatibility, temporary.resolve(currentSnapshot), currentMetadata,
            temporary.resolve(restoreStaging), temporary.resolve(journal));
    }

    private RestoreCompatibilityPolicy policy() {
        return new RestoreCompatibilityPolicy(1, 1, Set.of("resync-test"), new CatalogVersion(1, 0),
            new CatalogBinding(1, CATALOG_CHECKSUM, BINDING_HASH), EXTENSIONS);
    }

    private static SnapshotMetadata metadata(String id, String build, int format, String checksum, Map<String, String> extensions) {
        return new SnapshotMetadata(format, id, Instant.parse("2026-08-23T00:00:00Z"), build, checksum, extensions);
    }

    private static Path sidecar(Path root, String suffix) {
        return root.resolveSibling(root.getFileName() + suffix);
    }

    private enum TerminalCut {
        FAILED_BEFORE_STAGING(MigrationJournalState.ROLLED_BACK, false),
        FAILED_AFTER_STAGING(MigrationJournalState.ROLLED_BACK, false),
        FAILED_AFTER_ACTIVATION(MigrationJournalState.ROLLED_BACK, false),
        ROLLED_BACK(MigrationJournalState.ROLLED_BACK, false);

        private final MigrationJournalState finalState;
        private final boolean targetActive;

        TerminalCut(MigrationJournalState finalState, boolean targetActive) {
            this.finalState = finalState;
            this.targetActive = targetActive;
        }
    }

    private enum ProductionCut {
        PREPARED_BEFORE_CURRENT_SNAPSHOT(MigrationJournalState.PREPARED, MigrationJournalState.ROLLED_BACK, false),
        PREPARED_AFTER_CURRENT_SNAPSHOT(MigrationJournalState.PREPARED, MigrationJournalState.ROLLED_BACK, false),
        TRANSFORMING_BEFORE_STAGE(MigrationJournalState.TRANSFORMING, MigrationJournalState.ROLLED_BACK, false),
        TRANSFORMING_AFTER_STAGE(MigrationJournalState.TRANSFORMING, MigrationJournalState.ROLLED_BACK, false),
        VALIDATING_AFTER_PREPARATION(MigrationJournalState.VALIDATING, MigrationJournalState.COMMITTED, true),
        STAGED_BEFORE_ACTIVATION(MigrationJournalState.STAGED, MigrationJournalState.COMMITTED, true),
        STAGED_AFTER_ACTIVATION(MigrationJournalState.STAGED, MigrationJournalState.ROLLED_BACK, false),
        ACTIVATED_BEFORE_REBIND(MigrationJournalState.ACTIVATED, MigrationJournalState.COMMITTED, true),
        ACTIVATED_AFTER_REBIND(MigrationJournalState.ACTIVATED, MigrationJournalState.COMMITTED, true),
        ACTIVATED_AFTER_HEALTH(MigrationJournalState.ACTIVATED, MigrationJournalState.COMMITTED, true),
        ACTIVATED_AFTER_VERIFY(MigrationJournalState.ACTIVATED, MigrationJournalState.COMMITTED, true),
        ACTIVATED_AFTER_RESUME(MigrationJournalState.ACTIVATED, MigrationJournalState.COMMITTED, true);

        private final MigrationJournalState journalState;
        private final MigrationJournalState finalState;
        private final boolean targetActive;

        ProductionCut(MigrationJournalState journalState, MigrationJournalState finalState, boolean targetActive) {
            this.journalState = journalState;
            this.finalState = finalState;
            this.targetActive = targetActive;
        }
    }

    private enum Incompatibility {
        BUILD,
        FORMAT,
        CATALOG,
        EXTENSIONS;

        private SnapshotMetadata metadata() {
            return switch (this) {
                case BUILD -> RestoreCrashMatrixTest.metadata("restore-snapshot", "other-build", 1, CATALOG_CHECKSUM, RestoreCrashMatrixTest.EXTENSIONS);
                case FORMAT -> RestoreCrashMatrixTest.metadata("restore-snapshot", "resync-test", 2, CATALOG_CHECKSUM, RestoreCrashMatrixTest.EXTENSIONS);
                case CATALOG -> RestoreCrashMatrixTest.metadata("restore-snapshot", "resync-test", 1, "d".repeat(64), RestoreCrashMatrixTest.EXTENSIONS);
                case EXTENSIONS -> RestoreCrashMatrixTest.metadata("restore-snapshot", "resync-test", 1, CATALOG_CHECKSUM, Map.of("extension.test", "2.0.0"));
            };
        }
    }

    private record Fixture(Snapshot snapshot, AtomicRestoreActivation activation, TrackingParticipant participant, RestoreService service) {
        private RestoreRequest request(Snapshot target, RestoreCompatibilityPolicy compatibility, String currentSnapshot,
                                       String restoreStaging, String journal) {
            return new RestoreRequest(target, compatibility, target.root().resolveSibling(currentSnapshot),
                metadata("current-snapshot", "resync-test", 1, CATALOG_CHECKSUM, EXTENSIONS),
                target.root().resolveSibling(restoreStaging), target.root().resolveSibling(journal));
        }
    }

    private record ProductionFixture(Snapshot snapshot, AtomicRestoreActivation activation, TrackingParticipant participant,
                                     RestoreFault fault, RestoreService service) {
        private RestoreRequest request() {
            return new RestoreRequest(snapshot, new RestoreCompatibilityPolicy(1, 1, Set.of("resync-test"),
                new CatalogVersion(1, 0), new CatalogBinding(1, CATALOG_CHECKSUM, BINDING_HASH), EXTENSIONS),
                snapshot.root().resolveSibling("production-current-snapshot"),
                metadata("production-current", "resync-test", 1, CATALOG_CHECKSUM, EXTENSIONS),
                snapshot.root().resolveSibling("production-restore-staging"),
                snapshot.root().resolveSibling("production-restore-journal"));
        }
    }

    private static final class RestoreFault {
        private final ProductionCut cut;
        private boolean armed = true;

        private RestoreFault(ProductionCut cut) {
            this.cut = cut;
        }

        private void hit(ProductionCut point) {
            if (armed && cut == point) {
                armed = false;
                throw new RestoreCrash();
            }
        }

        private void disarm() {
            armed = false;
        }
    }

    private static final class FaultingRestoreActivation implements RestoreActivation {
        private final AtomicRestoreActivation delegate;
        private final RestoreFault fault;

        private FaultingRestoreActivation(AtomicRestoreActivation delegate, RestoreFault fault) {
            this.delegate = delegate;
            this.fault = fault;
        }

        @Override
        public Optional<Path> activeRoot() throws IOException {
            return delegate.activeRoot();
        }

        @Override
        public String planHash(Snapshot snapshot) {
            return delegate.planHash(snapshot);
        }

        @Override
        public StagedMigration stage(Snapshot snapshot, Path stagingRoot) throws IOException {
            fault.hit(ProductionCut.TRANSFORMING_BEFORE_STAGE);
            StagedMigration staged = delegate.stage(snapshot, stagingRoot);
            fault.hit(ProductionCut.TRANSFORMING_AFTER_STAGE);
            return staged;
        }

        @Override
        public RestoreActivation.Candidate prepare(Snapshot snapshot, StagedMigration staged, StagedRestoreValidator validator) throws IOException {
            RestoreActivation.Candidate candidate = delegate.prepare(snapshot, staged, validator);
            fault.hit(ProductionCut.VALIDATING_AFTER_PREPARATION);
            return candidate;
        }

        @Override
        public void swap(RestoreActivation.Candidate candidate) throws IOException {
            fault.hit(ProductionCut.STAGED_BEFORE_ACTIVATION);
            delegate.swap(candidate);
            fault.hit(ProductionCut.STAGED_AFTER_ACTIVATION);
        }

        @Override
        public void activate(StagedMigration staged) throws IOException {
            delegate.activate(staged);
        }

        @Override
        public void validateBeforeActivation(Snapshot snapshot, StagedMigration staged) throws IOException {
            delegate.validateBeforeActivation(snapshot, staged);
        }

        @Override
        public void rollback(StagedMigration staged) throws IOException {
            delegate.rollback(staged);
        }

        @Override
        public void verify(Snapshot snapshot, StagedMigration staged) throws IOException {
            delegate.verify(snapshot, staged);
            fault.hit(ProductionCut.ACTIVATED_AFTER_VERIFY);
        }

        @Override
        public void discard(Path stagingRoot) throws IOException {
            delegate.discard(stagingRoot);
        }
    }

    private static final class RestoreCrash extends Error {
    }

    private static final class TrackingParticipant implements RebindablePersistenceParticipant {
        private Path root;
        private final RestoreFault fault;
        private int flushes;
        private int quiesces;
        private int rebinds;
        private int resumes;
        private int healthChecks;

        private TrackingParticipant(Path root) {
            this(root, new RestoreFault(null));
        }

        private TrackingParticipant(Path root, RestoreFault fault) {
            this.root = root.toAbsolutePath().normalize();
            this.fault = fault;
        }

        @Override
        public String owner() {
            return "core";
        }

        @Override
        public Path root() {
            return root;
        }

        @Override
        public void flush() {
            fault.hit(ProductionCut.PREPARED_BEFORE_CURRENT_SNAPSHOT);
            flushes++;
        }

        @Override
        public void quiesce() {
            quiesces++;
            if (quiesces == 2) {
                fault.hit(ProductionCut.PREPARED_AFTER_CURRENT_SNAPSHOT);
            }
        }

        @Override
        public void resume() {
            resumes++;
            if (rebinds > 0) {
                fault.hit(ProductionCut.ACTIVATED_AFTER_RESUME);
            }
        }

        @Override
        public void rebind(Path activeRoot) {
            fault.hit(ProductionCut.ACTIVATED_BEFORE_REBIND);
            root = activeRoot.toAbsolutePath().normalize();
            rebinds++;
            fault.hit(ProductionCut.ACTIVATED_AFTER_REBIND);
        }

        @Override
        public void healthCheck() throws IOException {
            if (!Files.isDirectory(root)) {
                throw new IOException("Participant Root Is Missing");
            }
            healthChecks++;
            fault.hit(ProductionCut.ACTIVATED_AFTER_HEALTH);
        }

        private void reset() {
            flushes = 0;
            quiesces = 0;
            rebinds = 0;
            resumes = 0;
            healthChecks = 0;
        }
    }
}
