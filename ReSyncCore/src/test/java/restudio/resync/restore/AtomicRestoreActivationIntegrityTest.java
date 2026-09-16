package restudio.resync.restore;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.resync.migration.AtomicDirectoryRootStore;
import restudio.resync.migration.MigrationException;
import restudio.resync.migration.StagedMigration;
import restudio.resync.migration.TreeDigest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AtomicRestoreActivationIntegrityTest {
    @TempDir
    Path temporary;

    @Test
    void initialCompletionRejectsChangedSourceTargetPlanAndExpectedSourceWithoutPromotingIntent() throws IOException {
        for (String corruption : List.of("source", "target", "plan", "expected-source")) {
            Path root = Files.createDirectory(temporary.resolve(corruption));
            Path source = Files.createDirectory(root.resolve("source"));
            Files.writeString(source.resolve("value.txt"), "original");
            Path control = root.resolve("coordination/restore-control");
            AtomicDirectoryRootStore roots = new AtomicDirectoryRootStore(control);
            StagedMigration staged = roots.stage(source, control.getParent().resolve("active-roots/target"),
                RestoreActivationRecovery.bootstrapPlan(TreeDigest.migratableOf(source)));
            roots.activate(staged);
            RestoreActivationRecovery recovery = new RestoreActivationRecovery(control.resolve("activation-intent"));
            recovery.activated(source, corruption.equals("plan")
                ? new StagedMigration(staged.root(), Optional.empty(), "7".repeat(64), staged.contentHash()) : staged);
            Path expected = corruption.equals("expected-source") ? Files.createDirectory(root.resolve("other-source")) : source;
            if (corruption.equals("source") || corruption.equals("target")) {
                Files.writeString((corruption.equals("source") ? source : staged.root()).resolve("value.txt"), "changed");
            }
            byte[] intent = Files.readAllBytes(control.resolve("activation-intent"));
            AtomicRestoreActivation activation = new AtomicRestoreActivation(control);
            assertThrows(MigrationException.class, () -> activation.initialize(expected), corruption);
            assertArrayEquals(intent, Files.readAllBytes(control.resolve("activation-intent")), corruption);
            assertEquals(staged.root(), roots.activeRoot().orElseThrow());
            assertEquals(RestoreActivationRecovery.Phase.ACTIVATED, recovery.read().orElseThrow().phase());
        }
    }

    @Test
    void completedBootstrapRejectsChangedPointerAndDifferentExpectedSource() throws IOException {
        Path source = Files.createDirectory(temporary.resolve("completed-source"));
        Path control = temporary.resolve("completed-coordination/restore-control");
        AtomicRestoreActivation activation = new AtomicRestoreActivation(control);
        Path active = activation.initialize(source);
        Path otherSource = Files.createDirectory(temporary.resolve("different-source"));
        assertThrows(MigrationException.class, () -> activation.initialize(otherSource));
        Path unknown = Files.createDirectories(control.getParent().resolve("unknown"));
        new AtomicDirectoryRootStore(control).activate(new StagedMigration(unknown, Optional.of(active),
            "9".repeat(64), TreeDigest.of(unknown)));
        assertThrows(MigrationException.class, activation::recover);
        assertThrows(MigrationException.class, activation::activeRoot);
        assertThrows(MigrationException.class, activation::recoveryPending);
        assertThrows(MigrationException.class, () -> activation.converged(unknown));
    }

    @Test
    void completedPhaseCannotRepresentNormalRestoreOrUnknownBootstrapPlan() throws IOException {
        Fixture fixture = fixture();
        assertThrows(IllegalArgumentException.class, () -> new RestoreActivationRecovery.Intent(
            RestoreActivationRecovery.Phase.BOOTSTRAP_COMPLETE, fixture.previous().root(), fixture.staged()));
        StagedMigration unknown = new StagedMigration(fixture.staged().root(), Optional.empty(),
            "4".repeat(64), fixture.staged().contentHash());
        assertThrows(IllegalArgumentException.class, () -> new RestoreActivationRecovery.Intent(
            RestoreActivationRecovery.Phase.BOOTSTRAP_COMPLETE, fixture.previous().root(), unknown));
    }

    @Test
    void initializationDoesNotCompleteAnActivatedNormalRestore() throws IOException {
        Fixture fixture = fixture();
        fixture.roots().activate(fixture.staged());
        RestoreActivationRecovery recovery = new RestoreActivationRecovery(fixture.control().resolve("activation-intent"));
        recovery.activated(fixture.staged().root(), fixture.staged());
        AtomicRestoreActivation activation = new AtomicRestoreActivation(fixture.control());
        assertEquals(fixture.staged().root(), activation.initialize(temporary.resolve("removed-original-source")));
        assertTrue(activation.recoveryPending());
        assertEquals(RestoreActivationRecovery.Phase.ACTIVATED, recovery.read().orElseThrow().phase());
        Files.writeString(fixture.staged().root().resolve("value.txt"), "tampered");
        assertThrows(MigrationException.class, () -> activation.converged(fixture.staged().root()));
    }

    @Test
    void completedBootstrapCanReopenAfterOriginalSourceWasRemoved() throws IOException {
        Path source = Files.createDirectory(temporary.resolve("removed-source"));
        Files.writeString(source.resolve("value.txt"), "copied");
        Path control = temporary.resolve("removed-source-coordination/restore-control");
        Path active = new AtomicRestoreActivation(control).initialize(source);
        Files.delete(source.resolve("value.txt"));
        Files.delete(source);
        AtomicRestoreActivation activation = new AtomicRestoreActivation(control);
        assertEquals(active, activation.initialize(source));
        assertEquals("copied", Files.readString(active.resolve("value.txt")));
    }

    @Test
    void malformedCompletedEnvelopeBlocksEveryTerminalObserver() throws IOException {
        Path source = Files.createDirectory(temporary.resolve("malformed-source"));
        Path control = temporary.resolve("malformed-coordination/restore-control");
        AtomicRestoreActivation activation = new AtomicRestoreActivation(control);
        Path active = activation.initialize(source);
        Path intent = control.resolve("activation-intent");
        Files.writeString(intent, Files.readString(intent).replace("phase=BOOTSTRAP_COMPLETE", "phase=ACTIVATED"));
        assertThrows(MigrationException.class, activation::recover);
        assertThrows(MigrationException.class, activation::activeRoot);
        assertThrows(MigrationException.class, activation::recoveryPending);
        assertThrows(MigrationException.class, () -> activation.initialize(source));
        assertThrows(MigrationException.class, () -> activation.converged(active));
    }

    @Test
    void activatedRestartRejectsTamperedActiveContentBeforeRecovery() throws IOException {
        Fixture fixture = fixture();
        fixture.roots().activate(fixture.staged());
        RestoreActivationRecovery recovery = new RestoreActivationRecovery(fixture.control().resolve("activation-intent"));
        recovery.activated(fixture.staged().root(), fixture.staged());
        Files.writeString(fixture.staged().root().resolve("value.txt"), "tampered");

        AtomicRestoreActivation activation = new AtomicRestoreActivation(fixture.control());

        assertThrows(MigrationException.class, activation::recover);
        assertEquals(fixture.staged().root(), fixture.roots().activeRoot().orElseThrow());
        assertEquals(RestoreActivationRecovery.Phase.ACTIVATED, recovery.read().orElseThrow().phase());
        assertTrue(Files.exists(fixture.staged().root()));
    }

    @Test
    void activatedConvergenceRejectsTamperedActiveContentBeforeClearingIntent() throws IOException {
        Fixture fixture = fixture();
        fixture.roots().activate(fixture.staged());
        RestoreActivationRecovery recovery = new RestoreActivationRecovery(fixture.control().resolve("activation-intent"));
        recovery.activated(fixture.staged().root(), fixture.staged());
        Files.writeString(fixture.staged().root().resolve("value.txt"), "tampered");

        AtomicRestoreActivation activation = new AtomicRestoreActivation(fixture.control());

        assertThrows(MigrationException.class, () -> activation.converged(fixture.staged().root()));
        assertTrue(recovery.pending());
        assertEquals(RestoreActivationRecovery.Phase.ACTIVATED, recovery.read().orElseThrow().phase());
    }

    @Test
    void activatedRestartRejectsTamperedStagingBeforeRecordingRollback() throws IOException {
        Fixture fixture = fixture();
        RestoreActivationRecovery recovery = new RestoreActivationRecovery(fixture.control().resolve("activation-intent"));
        recovery.activated(fixture.staged().root(), fixture.staged());
        Files.writeString(fixture.staged().root().resolve("value.txt"), "tampered");

        assertThrows(MigrationException.class, () -> new AtomicRestoreActivation(fixture.control()).recover());
        assertEquals(fixture.previous().root(), fixture.roots().activeRoot().orElseThrow());
        assertEquals(RestoreActivationRecovery.Phase.ACTIVATED, recovery.read().orElseThrow().phase());
        assertTrue(Files.exists(fixture.staged().root()));
    }

    @Test
    void activatedRestartRejectsMissingStagingBeforeRecordingRollback() throws IOException {
        Fixture fixture = fixture();
        RestoreActivationRecovery recovery = new RestoreActivationRecovery(fixture.control().resolve("activation-intent"));
        recovery.activated(fixture.staged().root(), fixture.staged());
        Files.delete(fixture.staged().root().resolve("value.txt"));
        Files.delete(fixture.staged().root());

        assertThrows(MigrationException.class, () -> new AtomicRestoreActivation(fixture.control()).recover());
        assertEquals(fixture.previous().root(), fixture.roots().activeRoot().orElseThrow());
        assertEquals(RestoreActivationRecovery.Phase.ACTIVATED, recovery.read().orElseThrow().phase());
    }

    @Test
    void rollbackRejectsUnknownActiveRootWithoutChangingPointer() throws IOException {
        Fixture fixture = fixture();
        Path unknown = Files.createDirectories(fixture.control().getParent().resolve("unknown"));
        Files.writeString(unknown.resolve("value.txt"), "unknown");
        StagedMigration unknownStage = new StagedMigration(unknown, Optional.of(fixture.previous().root()),
            fixture.staged().planHash(), TreeDigest.of(unknown));
        fixture.roots().activate(unknownStage);

        AtomicRestoreActivation activation = new AtomicRestoreActivation(fixture.control());

        assertThrows(MigrationException.class, () -> activation.rollback(fixture.staged()));
        assertEquals(unknown, fixture.roots().activeRoot().orElseThrow());
        assertTrue(Files.exists(unknown.resolve("value.txt")));
    }

    @Test
    void rollbackAcceptsAlreadyRestoredPreviousRootWithoutRewritingPointer() throws IOException {
        Fixture fixture = fixture();
        fixture.roots().activate(fixture.previous());
        byte[] pointer = Files.readAllBytes(fixture.roots().pointerPath());

        new AtomicRestoreActivation(fixture.control()).rollback(fixture.staged());

        assertEquals(fixture.previous().root(), fixture.roots().activeRoot().orElseThrow());
        assertArrayEquals(pointer, Files.readAllBytes(fixture.roots().pointerPath()));
        assertTrue(Files.exists(fixture.staged().root()));
    }

    private Fixture fixture() throws IOException {
        Path control = temporary.resolve("coordination/restore-control");
        Path previousRoot = Files.createDirectories(control.getParent().resolve("previous"));
        Files.writeString(previousRoot.resolve("value.txt"), "previous");
        Path stagedRoot = Files.createDirectories(control.getParent().resolve("staged"));
        Files.writeString(stagedRoot.resolve("value.txt"), "staged");
        AtomicDirectoryRootStore roots = new AtomicDirectoryRootStore(control);
        StagedMigration previous = new StagedMigration(previousRoot, Optional.empty(), "a".repeat(64), TreeDigest.of(previousRoot));
        roots.activate(previous);
        StagedMigration staged = new StagedMigration(stagedRoot, Optional.of(previousRoot), "b".repeat(64), TreeDigest.of(stagedRoot));
        return new Fixture(control, roots, previous, staged);
    }

    private record Fixture(Path control, AtomicDirectoryRootStore roots, StagedMigration previous, StagedMigration staged) {
    }
}
