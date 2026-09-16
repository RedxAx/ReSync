package restudio.resync.migration;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MigrationFoundationTest {
    @TempDir
    Path temporary;

    @Test
    void rejectsPathEscapeAndSymlinkTraversal() throws IOException {
        Path root = Files.createDirectory(temporary.resolve("root"));
        assertThrows(IllegalArgumentException.class, () -> MigrationPaths.resolveInside(root, "../outside"));
        assertThrows(IllegalArgumentException.class, () -> MigrationPaths.resolveInside(root, "/outside"));
        assertThrows(IllegalArgumentException.class, () -> MigrationPaths.resolveInside(root, "nested/../../outside"));
        assertThrows(IllegalArgumentException.class, () -> MigrationPaths.resolveInside(root, "nested\\outside"));

        Path outside = Files.createDirectory(temporary.resolve("outside"));
        Path link = root.resolve("link");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | IOException exception) {
            Assumptions.assumeTrue(false, "Symbolic links are unavailable");
        }
        assertThrows(MigrationException.class, () -> MigrationPaths.requireNoSymlinkTree(root));
    }

    @Test
    void rejectsSymbolicLinkFileInTree() throws IOException {
        Path root = Files.createDirectory(temporary.resolve("file-link-root"));
        Path target = Files.writeString(temporary.resolve("file-link-target"), "target");
        try {
            Files.createSymbolicLink(root.resolve("link"), target);
        } catch (UnsupportedOperationException | IOException exception) {
            Assumptions.assumeTrue(false, "Symbolic links are unavailable");
        }

        assertThrows(MigrationException.class, () -> MigrationPaths.requireNoSymlinkTree(root));
    }

    @Test
    void rejectsDanglingSymlinkAncestors() throws IOException {
        Path dangling = temporary.resolve("dangling");
        try {
            Files.createSymbolicLink(dangling, temporary.resolve("missing-target"));
        } catch (UnsupportedOperationException | IOException exception) {
            Assumptions.assumeTrue(false, "Symbolic links are unavailable");
        }

        assertThrows(IllegalArgumentException.class,
            () -> MigrationPaths.requirePath(dangling.resolve("nested/file"), "path"));
        assertThrows(IllegalArgumentException.class,
            () -> MigrationPaths.resolveInside(temporary, "dangling/nested/file"));
    }

    @Test
    void producesContentAddressedPlanIndependentOfInputOrder() {
        String sourceHash = "1".repeat(64);
        String quarantineHash = QuarantineReport.empty().reportHash();
        MigrationOperation first = new MigrationOperation("rename", "adapter.rename", "old/a.json", "new/a.json", sourceHash, "2".repeat(64));
        MigrationOperation second = new MigrationOperation("convert", "adapter.convert", "old/b.json", "new/b.json", "3".repeat(64), "4".repeat(64));
        MigrationPlan left = new MigrationPlan("snapshot-1", sourceHash, 1, 2, quarantineHash, List.of(first, second));
        MigrationPlan right = new MigrationPlan("snapshot-1", sourceHash, 1, 2, quarantineHash, List.of(second, first));

        assertEquals(left.planHash(), right.planHash());
        assertEquals(left.planId(), "sha256:" + left.planHash());
        assertNotEquals(left.planHash(), new MigrationPlan("snapshot-1", sourceHash, 1, 2, quarantineHash, List.of(first)).planHash());
    }

    @Test
    void verifiesCompleteManifestAndDetectsTampering() throws IOException {
        Path source = Files.createDirectory(temporary.resolve("source"));
        Files.createDirectories(source.resolve("nested/empty"));
        Files.writeString(source.resolve("nested/value.txt"), "original");
        PersistenceParticipantRegistry participants = registry(source, new ArrayList<>());
        SnapshotMetadata metadata = metadata("snapshot-1");
        SnapshotService service = new SnapshotService(new MigrationFence());

        Snapshot snapshot = service.create(source, temporary.resolve("snapshot"), metadata, participants);

        assertTrue(snapshot.verified());
        assertEquals("core", snapshot.manifest().entries().getFirst().owner());
        assertTrue(snapshot.manifest().directories().contains("nested/empty"));
        assertTrue(snapshot.manifest().verify(snapshot.root()).verified());
        Files.writeString(snapshot.root().resolve("nested/value.txt"), "tampered");
        SnapshotVerification verification = service.verify(snapshot);
        assertFalse(verification.verified());
        assertTrue(verification.failures().stream().anyMatch(failure -> failure.contains("Hash Mismatch")));
        assertEquals(SnapshotState.FAILED, SnapshotStateStore.readVerification(snapshot.statePath()).verified() ? SnapshotState.VERIFIED : SnapshotState.FAILED);
    }

    @Test
    void treeDigestExcludesOnlyAuthorityMetadataAndEphemeralCoordinatorLock() throws IOException {
        Path root = Files.createDirectory(temporary.resolve("digest-root"));
        Files.createDirectories(root.resolve("assets/.migrations"));
        Files.writeString(root.resolve("assets/.migrations/other.json"), "one");
        Path coordinator = Files.createDirectories(root.resolve(".asset-coordinator"));
        Path rootLock = Files.writeString(coordinator.resolve("root.lock"), "one");
        Path state = Files.writeString(coordinator.resolve("state.json"), "state-one");
        Path genesis = Files.writeString(coordinator.resolve("genesis.json"), "genesis-one");
        Path otherLock = Files.writeString(coordinator.resolve("durable.lock"), "one");
        String first = TreeDigest.of(root);

        try (FileChannel channel = FileChannel.open(rootLock, StandardOpenOption.WRITE);
             FileLock ignored = channel.lock()) {
            assertEquals(first, TreeDigest.of(root));
        }

        Files.writeString(rootLock, "two");
        assertEquals(first, TreeDigest.of(root));

        Files.writeString(state, "state-two");
        String withState = TreeDigest.of(root);
        assertNotEquals(first, withState);

        Files.writeString(genesis, "genesis-two");
        String withGenesis = TreeDigest.of(root);
        assertNotEquals(withState, withGenesis);

        Files.writeString(otherLock, "two");
        assertNotEquals(withGenesis, TreeDigest.of(root));

        Files.writeString(root.resolve("assets/.migrations/other.json"), "two");
        assertNotEquals(first, TreeDigest.of(root));

        Files.writeString(MigrationActivationMarker.markerPath(root), "format=1\n");
        String withMarker = TreeDigest.of(root);
        Files.writeString(MigrationActivationMarker.markerPath(root), "tampered");
        assertEquals(withMarker, TreeDigest.of(root));
    }

    @Test
    void persistsLegalJournalTransitionsAndRecoversInterruptedWork() throws IOException {
        String planHash = "a".repeat(64);
        MigrationJournal journal = MigrationJournal.create(temporary.resolve("journal"), "migration-1", planHash);
        journal.transition(MigrationJournalState.PREPARED, "prepared");
        journal.transition(MigrationJournalState.TRANSFORMING, "transforming");
        assertEquals(JournalRecoveryAction.RESUME, journal.recovery().action());
        journal.recoverToRollback("interrupted");
        assertEquals(Optional.of(MigrationJournalState.ROLLED_BACK), journal.currentState());
        assertThrows(IllegalStateException.class, () -> journal.transition(MigrationJournalState.COMMITTED, "illegal"));

        MigrationJournal reopened = MigrationJournal.open(journal.path());
        assertEquals(Optional.of(MigrationJournalState.ROLLED_BACK), reopened.currentState());
        assertEquals(JournalRecoveryAction.COMPLETE, reopened.recovery().action());

        MigrationJournal committed = MigrationJournal.create(temporary.resolve("committed-journal"), "migration-2", planHash);
        committed.transition(MigrationJournalState.PREPARED, "prepared");
        committed.transition(MigrationJournalState.TRANSFORMING, "transforming");
        committed.transition(MigrationJournalState.VALIDATING, "validating");
        committed.transition(MigrationJournalState.STAGED, "staged");
        committed.transition(MigrationJournalState.ACTIVATED, "activated");
        committed.transition(MigrationJournalState.COMMITTED, "committed");
        assertThrows(IllegalStateException.class, () -> committed.transition(MigrationJournalState.FAILED, "too late"));
    }

    @Test
    void repeatingJournalStepsIsIdempotentAcrossReopen() throws IOException {
        String planHash = "c".repeat(64);
        MigrationJournal journal = MigrationJournal.create(temporary.resolve("idempotent-journal"), "migration-idempotent", planHash);

        assertEquals(MigrationJournalState.PREPARED, journal.transition(MigrationJournalState.PREPARED, "first attempt"));
        assertEquals(MigrationJournalState.PREPARED, journal.transition(MigrationJournalState.PREPARED, "retry"));
        assertEquals(MigrationJournalState.TRANSFORMING, journal.transition(MigrationJournalState.TRANSFORMING, "transform"));
        assertEquals(MigrationJournalState.TRANSFORMING, journal.transition(MigrationJournalState.TRANSFORMING, "retry"));
        assertEquals(2, journal.entries().size());

        MigrationJournal reopened = MigrationJournal.open(journal.path());
        assertEquals(2, reopened.entries().size());
        assertEquals(MigrationJournalState.TRANSFORMING, reopened.currentState().orElseThrow());
        assertEquals(MigrationJournalState.TRANSFORMING, reopened.transition(MigrationJournalState.TRANSFORMING, "reopened retry"));
        assertEquals(2, reopened.entries().size());
    }

    @Test
    void failedActivationRestoresPreviousActiveRoot() throws IOException {
        Path source = Files.createDirectory(temporary.resolve("source"));
        Files.writeString(source.resolve("value.txt"), "source");
        List<String> lifecycle = new ArrayList<>();
        PersistenceParticipantRegistry participants = registry(source, lifecycle);
        SnapshotMetadata metadata = metadata("migration-snapshot");
        String sourceManifestHash = SnapshotManifest.scan(source, metadata, participants).manifestHash();
        MigrationPlan plan = new MigrationPlan("migration-snapshot", sourceManifestHash, 1, 2, QuarantineReport.empty().reportHash(), List.of(new MigrationOperation("copy", "adapter.copy", "value.txt", "value.txt", "", "")));
        AtomicDirectoryRootStore roots = new AtomicDirectoryRootStore(temporary.resolve("control"));
        StagedMigration initial = roots.stage(source, temporary.resolve("initial"), plan);
        roots.activate(initial);
        Path initialRoot = roots.activeRoot().orElseThrow();
        MigrationJournal journal = MigrationJournal.create(temporary.resolve("migration-journal"), "migration-transaction", plan.planHash());
        MigrationRequest request = new MigrationRequest(
                source,
                temporary.resolve("snapshot"),
                metadata,
                temporary.resolve("replacement"),
                plan,
                journal,
                roots,
                staged -> {
                    assertTrue(Files.exists(staged.root().resolve("value.txt")));
                },
                staged -> {
                    roots.activate(staged);
                    throw new IOException("activation health check failed");
                },
                roots,
                null,
                QuarantineReport.empty(),
                QuarantineReport.empty().accept("operator", Instant.EPOCH),
                0);

        assertThrows(IOException.class, () -> new MigrationCoordinator(new MigrationFence(), participants).execute(request));
        assertEquals(initialRoot, roots.activeRoot().orElseThrow());
        assertEquals(initialRoot, new AtomicDirectoryRootStore(temporary.resolve("control")).activeRoot().orElseThrow());
        MigrationJournal reopened = MigrationJournal.open(journal.path());
        assertEquals(Optional.of(MigrationJournalState.ROLLED_BACK), reopened.currentState());
        assertEquals(List.of(
                MigrationJournalState.PREPARED,
                MigrationJournalState.TRANSFORMING,
                MigrationJournalState.VALIDATING,
                MigrationJournalState.STAGED,
                MigrationJournalState.FAILED,
                MigrationJournalState.ROLLED_BACK),
            reopened.entries().stream().map(MigrationJournal.Entry::state).toList());
        assertEquals(List.of("flush", "quiesce", "resume"), lifecycle);
    }

    @Test
    void failedPostActivationHealthCheckRestoresParticipantsAndJournal() throws IOException {
        Path source = Files.createDirectory(temporary.resolve("health-source"));
        Path participantRoot = Files.createDirectory(source.resolve("core"));
        Files.writeString(participantRoot.resolve("value.txt"), "source");
        List<String> lifecycle = new ArrayList<>();
        PersistenceParticipantRegistry participants = new PersistenceParticipantRegistry(source);
        participants.register(new ScopedPersistenceParticipant("core", source, participantRoot, new ScopedPersistenceParticipant.Lifecycle() {
            @Override
            public void flush(Path root) {
                lifecycle.add("flush:" + root.getFileName());
            }

            @Override
            public void quiesce(Path root) {
                lifecycle.add("quiesce:" + root.getFileName());
            }

            @Override
            public void resume(Path root) {
                lifecycle.add("resume:" + root.getFileName());
            }

            @Override
            public void rebind(Path previousRoot, Path nextRoot) {
                lifecycle.add("rebind:" + nextRoot.getParent().getFileName());
            }

            @Override
            public void healthCheck(Path root) throws IOException {
                lifecycle.add("health:" + root.getParent().getFileName());
                if (!root.equals(participantRoot)) {
                    throw new IOException("replacement health check failed");
                }
            }
        }));
        SnapshotMetadata metadata = metadata("health-snapshot");
        String sourceManifestHash = SnapshotManifest.scan(source, metadata, participants).manifestHash();
        MigrationPlan plan = new MigrationPlan("health-snapshot", sourceManifestHash, 1, 2, QuarantineReport.empty().reportHash(), List.of());
        AtomicDirectoryRootStore roots = new AtomicDirectoryRootStore(temporary.resolve("health-control"));
        roots.activate(new StagedMigration(source, Optional.empty(), plan.planHash(), TreeDigest.of(source)));
        MigrationJournal journal = MigrationJournal.create(temporary.resolve("health-journal"), "health-migration", plan.planHash());
        MigrationRequest request = new MigrationRequest(
                source,
                temporary.resolve("health-snapshot-stage"),
                metadata,
                temporary.resolve("health-replacement"),
                plan,
                journal,
                roots,
                staged -> {
                },
                roots,
                roots,
                null,
                QuarantineReport.empty(),
                QuarantineReport.empty().accept("operator", Instant.EPOCH),
                0);

        assertThrows(IOException.class, () -> new MigrationCoordinator(new MigrationFence(), participants).execute(request));
        assertEquals(source, roots.activeRoot().orElseThrow());
        assertEquals(participantRoot, participants.participants().stream().findFirst().orElseThrow().root());
        assertEquals(Optional.of(MigrationJournalState.ROLLED_BACK), MigrationJournal.open(journal.path()).currentState());
        assertTrue(lifecycle.contains("health:health-replacement"), lifecycle.toString());
        assertTrue(lifecycle.contains("health:health-source"), lifecycle.toString());
    }

    @Test
    void failedParticipantCompensationLeavesJournalFailed() throws IOException {
        Path source = Files.createDirectory(temporary.resolve("compensation-source"));
        Path participantRoot = Files.createDirectory(source.resolve("core"));
        Files.writeString(participantRoot.resolve("value.txt"), "source");
        Path replacement = temporary.resolve("compensation-replacement");
        Path replacementParticipantRoot = replacement.resolve("core");
        boolean[] targetHealthFailed = {false};
        PersistenceParticipantRegistry participants = new PersistenceParticipantRegistry(source);
        participants.register(new ScopedPersistenceParticipant("core", source, participantRoot, new ScopedPersistenceParticipant.Lifecycle() {
            @Override
            public void flush(Path root) {
            }

            @Override
            public void quiesce(Path root) {
            }

            @Override
            public void resume(Path root) {
            }

            @Override
            public void rebind(Path previousRoot, Path nextRoot) throws IOException {
                if (targetHealthFailed[0] && (nextRoot.equals(participantRoot) || nextRoot.equals(replacementParticipantRoot))) {
                    throw new IOException("participant compensation failed");
                }
            }

            @Override
            public void healthCheck(Path root) throws IOException {
                if (root.equals(replacementParticipantRoot)) {
                    targetHealthFailed[0] = true;
                    throw new IOException("replacement health check failed");
                }
            }
        }));
        SnapshotMetadata metadata = metadata("compensation-snapshot");
        String sourceManifestHash = SnapshotManifest.scan(source, metadata, participants).manifestHash();
        MigrationPlan plan = new MigrationPlan("compensation-snapshot", sourceManifestHash, 1, 2, QuarantineReport.empty().reportHash(), List.of());
        AtomicDirectoryRootStore roots = new AtomicDirectoryRootStore(temporary.resolve("compensation-control"));
        roots.activate(new StagedMigration(source, Optional.empty(), plan.planHash(), TreeDigest.of(source)));
        MigrationJournal journal = MigrationJournal.create(temporary.resolve("compensation-journal"), "compensation-migration", plan.planHash());
        MigrationRequest request = new MigrationRequest(
                source,
                temporary.resolve("compensation-snapshot-stage"),
                metadata,
                replacement,
                plan,
                journal,
                roots,
                staged -> {
                },
                roots,
                roots,
                null,
                QuarantineReport.empty(),
                QuarantineReport.empty().accept("operator", Instant.EPOCH),
                0);

        assertThrows(IOException.class, () -> new MigrationCoordinator(new MigrationFence(), participants).execute(request));
        assertEquals(source, roots.activeRoot().orElseThrow());
        assertEquals(replacementParticipantRoot, participants.participants().stream().findFirst().orElseThrow().root());
        assertEquals(PersistenceRebindStatus.State.INCONSISTENT, participants.rebindStatus().state());
        assertEquals(Optional.of(MigrationJournalState.FAILED), MigrationJournal.open(journal.path()).currentState());
    }

    private PersistenceParticipantRegistry registry(Path root, List<String> lifecycle) {
        PersistenceParticipantRegistry participants = new PersistenceParticipantRegistry();
        participants.register(new PersistenceParticipant() {
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
                lifecycle.add("flush");
            }

            @Override
            public void quiesce() {
                lifecycle.add("quiesce");
            }

            @Override
            public void resume() {
                lifecycle.add("resume");
            }
        });
        return participants;
    }

    private SnapshotMetadata metadata(String snapshotId) {
        return new SnapshotMetadata(1, snapshotId, Instant.parse("2026-01-01T00:00:00Z"), "resync-test", "b".repeat(64), Map.of("extension.test", "1.0.0"));
    }
}
