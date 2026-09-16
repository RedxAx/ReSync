package restudio.resync.restore;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.resync.migration.AtomicDirectoryRootStore;
import restudio.resync.migration.MigrationException;
import restudio.resync.migration.MigrationFence;
import restudio.resync.migration.MigrationPlan;
import restudio.resync.migration.PersistenceParticipantRegistry;
import restudio.resync.migration.QuarantineReport;
import restudio.resync.migration.RebindablePersistenceParticipant;
import restudio.resync.migration.ReSyncPersistenceCoordinator;
import restudio.resync.migration.Snapshot;
import restudio.resync.migration.SnapshotMetadata;
import restudio.resync.migration.SnapshotService;
import restudio.resync.migration.StagedMigration;
import restudio.resync.migration.TreeDigest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AtomicRestoreActivationBootstrapTest {
    private static final String CATALOG_CHECKSUM = "d".repeat(64);

    @TempDir
    Path temporary;

    @Test
    void initializesManagedActiveRootWithoutChangingPriorData() throws IOException {
        Path source = Files.createDirectory(temporary.resolve("resync"));
        Files.writeString(source.resolve("state.txt"), "prior");
        Path control = temporary.resolve("coordination/restore-control");
        AtomicRestoreActivation activation = new AtomicRestoreActivation(control);

        Path active = activation.initialize(source);

        assertNotEquals(source, active);
        assertEquals("prior", Files.readString(source.resolve("state.txt")));
        assertEquals("prior", Files.readString(active.resolve("state.txt")));
        assertFalse(activation.recoveryPending());
        assertEquals(RestoreActivationRecovery.Phase.BOOTSTRAP_COMPLETE,
            new RestoreActivationRecovery(control.resolve("activation-intent")).read().orElseThrow().phase());

        activation.converged(active);

        assertFalse(activation.recoveryPending());
        assertEquals(active, new AtomicRestoreActivation(control).recover().orElseThrow());
    }

    @Test
    void completedCopySurvivesNormalWritersAndChangedOriginalSourceWithoutRehashing() throws IOException {
        Path source = Files.createDirectory(temporary.resolve("completed-source"));
        Files.writeString(source.resolve("state.txt"), "original");
        Path control = temporary.resolve("completed-coordination/restore-control");
        AtomicRestoreActivation activation = new AtomicRestoreActivation(control);
        Path active = activation.initialize(source);
        Path intent = control.resolve("activation-intent");
        byte[] completed = Files.readAllBytes(intent);
        Files.writeString(active.resolve("state.txt"), "committed user state");
        Files.writeString(source.resolve("state.txt"), "later operator state");

        AtomicRestoreActivation restarted = new AtomicRestoreActivation(control);
        assertEquals(active, restarted.recover().orElseThrow());
        assertEquals(active, restarted.initialize(source));
        restarted.converged(active);
        assertFalse(restarted.recoveryPending());
        assertArrayEquals(completed, Files.readAllBytes(intent));
        assertEquals("committed user state", Files.readString(active.resolve("state.txt")));
    }

    @Test
    void completedBootstrapObservesTheValidatedActiveRootOncePerCall() throws IOException {
        Path source = Files.createDirectory(temporary.resolve("single-observation-source"));
        Files.writeString(source.resolve("state.txt"), "active");
        Path control = temporary.resolve("single-observation-coordination/restore-control");
        Path active = new AtomicRestoreActivation(control).initialize(source);
        AtomicDirectoryRootStore roots = new AtomicDirectoryRootStore(control);
        AtomicInteger reads = new AtomicInteger();
        AtomicRestoreActivation activation = new AtomicRestoreActivation(control, () -> {
            reads.incrementAndGet();
            return roots.activeRoot();
        });

        assertEquals(Optional.of(active), activation.activeRoot());
        assertEquals(1, reads.get());

        reads.set(0);
        assertEquals(Optional.of(active), activation.restoreReadyActiveRoot());
        assertEquals(1, reads.get());

        reads.set(0);
        assertFalse(activation.recoveryIntentPending());
        assertEquals(0, reads.get());

        reads.set(0);
        assertThrows(MigrationException.class, () -> activation.converged(active.resolveSibling("wrong-root")));
        assertEquals(1, reads.get());
        assertEquals(RestoreActivationRecovery.Phase.BOOTSTRAP_COMPLETE,
            new RestoreActivationRecovery(control.resolve("activation-intent")).read().orElseThrow().phase());

        reads.set(0);
        activation.converged(active.resolve("."));
        assertEquals(1, reads.get());
        assertEquals(RestoreActivationRecovery.Phase.BOOTSTRAP_COMPLETE,
            new RestoreActivationRecovery(control.resolve("activation-intent")).read().orElseThrow().phase());
    }

    @Test
    void initializationCompletesEveryInterruptedInitialCopyPhaseBeforeReturningWritableRoot() throws IOException {
        for (RestoreActivationRecovery.Phase phase : List.of(RestoreActivationRecovery.Phase.PREPARING,
            RestoreActivationRecovery.Phase.READY, RestoreActivationRecovery.Phase.ACTIVATED)) {
            Path fixture = Files.createDirectory(temporary.resolve(phase.name()));
            Path source = Files.createDirectory(fixture.resolve("source"));
            Files.writeString(source.resolve("state.txt"), "original");
            Path control = fixture.resolve("coordination/restore-control");
            AtomicDirectoryRootStore roots = new AtomicDirectoryRootStore(control);
            Path target = control.getParent().resolve("active-roots/interrupted");
            MigrationPlan plan = RestoreActivationRecovery.bootstrapPlan(TreeDigest.migratableOf(source));
            RestoreActivationRecovery recovery = new RestoreActivationRecovery(control.resolve("activation-intent"));
            if (phase == RestoreActivationRecovery.Phase.PREPARING) {
                Files.createDirectories(target);
                Files.writeString(target.resolve("partial.txt"), "interrupted copy");
                recovery.preparing(source, target, Optional.empty(), plan.planHash());
            } else {
                StagedMigration staged = roots.stage(source, target, plan);
                recovery.ready(source, staged);
                if (phase == RestoreActivationRecovery.Phase.ACTIVATED) {
                    roots.activate(staged);
                    recovery.activated(source, staged);
                }
            }
            AtomicRestoreActivation activation = new AtomicRestoreActivation(control);
            assertEquals(target, activation.initialize(source));
            assertEquals(RestoreActivationRecovery.Phase.BOOTSTRAP_COMPLETE, recovery.read().orElseThrow().phase());
            assertFalse(activation.recoveryPending());
            assertEquals("original", Files.readString(target.resolve("state.txt")));
            Files.writeString(target.resolve("state.txt"), "after completion");
            assertEquals(target, new AtomicRestoreActivation(control).initialize(source));
        }
    }

    @Test
    void activatedCopyCrashBeforeCompletionRemainsStrictAndCompletionCannotRunFromReady() throws IOException {
        Path source = Files.createDirectory(temporary.resolve("completion-source"));
        Files.writeString(source.resolve("state.txt"), "original");
        Path control = temporary.resolve("completion-coordination/restore-control");
        AtomicDirectoryRootStore roots = new AtomicDirectoryRootStore(control);
        StagedMigration staged = roots.stage(source, control.getParent().resolve("active-roots/target"),
            RestoreActivationRecovery.bootstrapPlan(TreeDigest.migratableOf(source)));
        RestoreActivationRecovery recovery = new RestoreActivationRecovery(control.resolve("activation-intent"));
        recovery.ready(source, staged);
        assertThrows(MigrationException.class, () -> recovery.bootstrapComplete(source, staged));
        roots.activate(staged);
        recovery.activated(source, staged);
        AtomicRestoreActivation activation = new AtomicRestoreActivation(control);
        assertTrue(activation.recoveryPending());
        assertThrows(MigrationException.class, () -> activation.converged(staged.root()));
        assertEquals(RestoreActivationRecovery.Phase.ACTIVATED, recovery.read().orElseThrow().phase());
        assertEquals(staged.root(), activation.initialize(source));
        assertFalse(activation.recoveryPending());
        assertThrows(MigrationException.class, recovery::clear);
    }

    @Test
    void resumesInterruptedBootstrapFromPreservedSource() throws IOException {
        Path source = Files.createDirectory(temporary.resolve("resync-interrupted"));
        Files.writeString(source.resolve("state.txt"), "authoritative");
        Path control = temporary.resolve("coordination-interrupted/restore-control");
        Files.createDirectories(control);
        Path target = control.getParent().resolve("active-roots/interrupted");
        Files.createDirectories(target);
        Files.writeString(target.resolve("partial.txt"), "partial");
        RestoreActivationRecovery recovery = new RestoreActivationRecovery(control.resolve("activation-intent"));
        MigrationPlan plan = new MigrationPlan("restore-bootstrap", TreeDigest.of(source), 1, 1,
            QuarantineReport.empty().reportHash(), List.of());
        recovery.preparing(source, target, Optional.empty(), plan.planHash());

        AtomicRestoreActivation activation = new AtomicRestoreActivation(control);
        Path active = activation.recover().orElseThrow();

        assertEquals(target, active);
        assertEquals("authoritative", Files.readString(active.resolve("state.txt")));
        assertFalse(Files.exists(active.resolve("partial.txt")));
        assertEquals("authoritative", Files.readString(source.resolve("state.txt")));
        assertTrue(activation.recoveryPending());
    }

    @Test
    void preparedCoordinatorBootstrapCompletesInterruptedInitialCopy() throws IOException {
        Path source = Files.createDirectory(temporary.resolve("prepared-coordinator-source"));
        Files.writeString(source.resolve("state.txt"), "authoritative");
        Path coordination = temporary.resolve("prepared-coordinator-coordination");
        Path control = coordination.resolve("restore-control");
        Files.createDirectories(control);
        Path target = coordination.resolve("active-roots/interrupted");
        Files.createDirectories(target);
        Files.writeString(target.resolve("partial.txt"), "partial");
        RestoreActivationRecovery recovery = new RestoreActivationRecovery(control.resolve("activation-intent"));
        MigrationPlan plan = RestoreActivationRecovery.bootstrapPlan(TreeDigest.migratableOf(source));
        recovery.preparing(source, target, Optional.empty(), plan.planHash());

        ReSyncPersistenceCoordinator.PreparedBootstrap prepared = ReSyncPersistenceCoordinator.bootstrapPrepared(
            source, coordination);

        assertEquals(target, prepared.activeRoot());
        assertEquals(target, prepared.coordinator().activeDataRoot());
        assertEquals("authoritative", Files.readString(target.resolve("state.txt")));
        assertFalse(Files.exists(target.resolve("partial.txt")));
        assertEquals(RestoreActivationRecovery.Phase.BOOTSTRAP_COMPLETE, recovery.read().orElseThrow().phase());
        assertFalse(prepared.coordinator().activation().recoveryPending());
    }

    @Test
    void durablyRollsBackReadyRestoreBeforeClearingIntent() throws IOException {
        Path source = Files.createDirectory(temporary.resolve("resync-ready"));
        Files.writeString(source.resolve("state.txt"), "prior");
        Path control = temporary.resolve("coordination-ready/restore-control");
        AtomicRestoreActivation activation = new AtomicRestoreActivation(control);
        Path previous = activation.initialize(source);
        activation.converged(previous);
        Path target = Files.createDirectories(control.getParent().resolve("restore-ready"));
        Files.writeString(target.resolve("state.txt"), "restored");
        StagedMigration staged = new StagedMigration(target, Optional.of(previous), "b".repeat(64), TreeDigest.of(target));
        RestoreActivationRecovery recovery = new RestoreActivationRecovery(control.resolve("activation-intent"));
        recovery.ready(target, staged);

        Path recovered = new AtomicRestoreActivation(control).recover().orElseThrow();

        assertEquals(previous, recovered);
        assertFalse(Files.exists(target));
        assertTrue(activation.recoveryPending());

        activation.converged(previous);

        assertFalse(activation.recoveryPending());
    }

    @Test
    void rejectsChangedReadyRootBeforeRecordingActivation() throws IOException {
        Path source = Files.createDirectory(temporary.resolve("resync-changed"));
        Files.writeString(source.resolve("state.txt"), "prior");
        Path control = temporary.resolve("coordination-changed/restore-control");
        AtomicRestoreActivation activation = new AtomicRestoreActivation(control);
        Path previous = activation.initialize(source);
        activation.converged(previous);
        Path target = Files.createDirectories(control.getParent().resolve("restore-changed"));
        Files.writeString(target.resolve("state.txt"), "restored");
        StagedMigration staged = new StagedMigration(target, Optional.of(previous), "c".repeat(64), TreeDigest.of(target));
        RestoreActivationRecovery recovery = new RestoreActivationRecovery(control.resolve("activation-intent"));
        recovery.ready(target, staged);
        new AtomicDirectoryRootStore(control).activate(staged);
        Files.writeString(target.resolve("state.txt"), "changed");

        assertThrows(MigrationException.class, () -> new AtomicRestoreActivation(control).recover());
        assertTrue(activation.recoveryPending());
    }

    @Test
    void retainsInterruptedEvidenceWhenBootstrapSourceChanged() throws IOException {
        Path source = Files.createDirectory(temporary.resolve("resync-source-changed"));
        Files.writeString(source.resolve("state.txt"), "prior");
        Path control = temporary.resolve("coordination-source-changed/restore-control");
        Files.createDirectories(control);
        Path target = control.getParent().resolve("active-roots/interrupted");
        Files.createDirectories(target);
        Files.writeString(target.resolve("partial.txt"), "partial");
        MigrationPlan plan = new MigrationPlan("restore-bootstrap", TreeDigest.of(source), 1, 1,
            QuarantineReport.empty().reportHash(), List.of());
        RestoreActivationRecovery recovery = new RestoreActivationRecovery(control.resolve("activation-intent"));
        recovery.preparing(source, target, Optional.empty(), plan.planHash());
        Files.writeString(source.resolve("state.txt"), "changed");

        assertThrows(MigrationException.class, () -> new AtomicRestoreActivation(control).recover());
        assertEquals("changed", Files.readString(source.resolve("state.txt")));
        assertEquals("partial", Files.readString(target.resolve("partial.txt")));
        assertTrue(recovery.pending());
    }

    @Test
    void candidateSwapPreservesExactSnapshotEvidenceThroughRollback() throws IOException {
        Path source = Files.createDirectory(temporary.resolve("candidate-source"));
        Files.writeString(source.resolve("state.txt"), "prior");
        Path control = temporary.resolve("coordination-candidate/restore-control");
        AtomicRestoreActivation activation = new AtomicRestoreActivation(control);
        Path previous = activation.initialize(source);
        activation.converged(previous);
        Path snapshotSource = Files.createDirectory(temporary.resolve("candidate-snapshot-source"));
        Files.writeString(snapshotSource.resolve("state.txt"), "restored");
        Snapshot snapshot = snapshot(snapshotSource, "candidate-snapshot");
        StagedMigration staged = activation.stage(snapshot, control.getParent().resolve("candidate-target"));

        activation.swap(new RestoreActivation.Candidate(snapshot, staged));

        RestoreActivationRecovery recovery = new RestoreActivationRecovery(control.resolve("activation-intent"));
        RestoreActivationRecovery.Intent activated = recovery.read().orElseThrow();
        assertEquals(RestoreActivationRecovery.Phase.ACTIVATED, activated.phase());
        assertEquals(snapshot.root(), activated.evidenceRoot());

        activation.rollback(staged);

        RestoreActivationRecovery.Intent rolledBack = recovery.read().orElseThrow();
        assertEquals(RestoreActivationRecovery.Phase.ROLLED_BACK, rolledBack.phase());
        assertEquals(snapshot.root(), rolledBack.evidenceRoot());
        assertEquals("restored", Files.readString(snapshot.root().resolve("state.txt")));
    }

    @Test
    void reconstructsReadyIntentAfterPointerSwapIdempotently() throws IOException {
        Path source = Files.createDirectory(temporary.resolve("ready-swapped-source"));
        Files.writeString(source.resolve("state.txt"), "prior");
        Path evidence = Files.createDirectory(temporary.resolve("ready-swapped-evidence"));
        Files.writeString(evidence.resolve("snapshot.txt"), "snapshot");
        Path control = temporary.resolve("coordination-ready-swapped/restore-control");
        AtomicRestoreActivation activation = new AtomicRestoreActivation(control);
        Path previous = activation.initialize(source);
        activation.converged(previous);
        Path target = Files.createDirectories(control.getParent().resolve("ready-swapped-target"));
        Files.writeString(target.resolve("state.txt"), "restored");
        StagedMigration staged = new StagedMigration(target, Optional.of(previous), "e".repeat(64), TreeDigest.of(target));
        RestoreActivationRecovery recovery = new RestoreActivationRecovery(control.resolve("activation-intent"));
        recovery.ready(evidence, staged);
        new AtomicDirectoryRootStore(control).activate(staged);

        assertEquals(target, new AtomicRestoreActivation(control).recover().orElseThrow());
        RestoreActivationRecovery.Intent reconstructed = recovery.read().orElseThrow();
        assertEquals(RestoreActivationRecovery.Phase.ACTIVATED, reconstructed.phase());
        assertEquals(evidence, reconstructed.evidenceRoot());
        assertEquals(target, new AtomicRestoreActivation(control).recover().orElseThrow());
        assertEquals("snapshot", Files.readString(evidence.resolve("snapshot.txt")));
    }

    @Test
    void reconstructsActivatedIntentForTargetAndPreviousPointers() throws IOException {
        Path source = Files.createDirectory(temporary.resolve("activated-source"));
        Files.writeString(source.resolve("state.txt"), "prior");
        Path evidence = Files.createDirectory(temporary.resolve("activated-evidence"));
        Files.writeString(evidence.resolve("snapshot.txt"), "snapshot");
        Path control = temporary.resolve("coordination-activated/restore-control");
        AtomicRestoreActivation activation = new AtomicRestoreActivation(control);
        Path previous = activation.initialize(source);
        activation.converged(previous);
        Path target = Files.createDirectories(control.getParent().resolve("activated-target"));
        Files.writeString(target.resolve("state.txt"), "restored");
        StagedMigration staged = new StagedMigration(target, Optional.of(previous), "f".repeat(64), TreeDigest.of(target));
        RestoreActivationRecovery recovery = new RestoreActivationRecovery(control.resolve("activation-intent"));
        recovery.activated(evidence, staged);
        new AtomicDirectoryRootStore(control).activate(staged);

        assertEquals(target, new AtomicRestoreActivation(control).recover().orElseThrow());
        assertEquals(target, new AtomicRestoreActivation(control).recover().orElseThrow());
        assertEquals(evidence, recovery.read().orElseThrow().evidenceRoot());

        new AtomicDirectoryRootStore(control).rollback(staged);

        assertEquals(previous, new AtomicRestoreActivation(control).recover().orElseThrow());
        RestoreActivationRecovery.Intent rolledBack = recovery.read().orElseThrow();
        assertEquals(RestoreActivationRecovery.Phase.ROLLED_BACK, rolledBack.phase());
        assertEquals(evidence, rolledBack.evidenceRoot());
        assertFalse(Files.exists(target));
        assertEquals(previous, new AtomicRestoreActivation(control).recover().orElseThrow());
        assertEquals("snapshot", Files.readString(evidence.resolve("snapshot.txt")));
    }

    @Test
    void completesRolledBackCleanupIdempotentlyWithoutLosingEvidence() throws IOException {
        Path source = Files.createDirectory(temporary.resolve("rolled-back-source"));
        Files.writeString(source.resolve("state.txt"), "prior");
        Path evidence = Files.createDirectory(temporary.resolve("rolled-back-evidence"));
        Files.writeString(evidence.resolve("snapshot.txt"), "snapshot");
        Path control = temporary.resolve("coordination-rolled-back/restore-control");
        AtomicRestoreActivation activation = new AtomicRestoreActivation(control);
        Path previous = activation.initialize(source);
        activation.converged(previous);
        Path target = Files.createDirectories(control.getParent().resolve("rolled-back-target"));
        Files.writeString(target.resolve("state.txt"), "restored");
        StagedMigration staged = new StagedMigration(target, Optional.of(previous), "1".repeat(64), TreeDigest.of(target));
        RestoreActivationRecovery recovery = new RestoreActivationRecovery(control.resolve("activation-intent"));
        recovery.rolledBack(evidence, staged);

        assertEquals(previous, new AtomicRestoreActivation(control).recover().orElseThrow());
        assertFalse(Files.exists(target));
        assertEquals(previous, new AtomicRestoreActivation(control).recover().orElseThrow());
        RestoreActivationRecovery.Intent retained = recovery.read().orElseThrow();
        assertEquals(RestoreActivationRecovery.Phase.ROLLED_BACK, retained.phase());
        assertEquals(evidence, retained.evidenceRoot());
        assertEquals("snapshot", Files.readString(evidence.resolve("snapshot.txt")));
    }

    private Snapshot snapshot(Path source, String id) throws IOException {
        return new SnapshotService(new MigrationFence()).create(source, temporary.resolve(id),
            new SnapshotMetadata(1, id, Instant.parse("2026-01-01T00:00:00Z"), "resync-test", CATALOG_CHECKSUM, Map.of()), registry(source));
    }

    private PersistenceParticipantRegistry registry(Path root) {
        PersistenceParticipantRegistry participants = new PersistenceParticipantRegistry(root);
        participants.register(new RebindablePersistenceParticipant() {
            private Path activeRoot = root;

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
}
