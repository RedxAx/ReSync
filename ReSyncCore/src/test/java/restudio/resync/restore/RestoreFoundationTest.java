package restudio.resync.restore;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.migration.MigrationFence;
import restudio.resync.migration.MigrationJournal;
import restudio.resync.migration.MigrationJournalState;
import restudio.resync.migration.PersistenceParticipant;
import restudio.resync.migration.PersistenceParticipantClassification;
import restudio.resync.migration.PersistenceParticipantRegistry;
import restudio.resync.migration.RebindablePersistenceParticipant;
import restudio.resync.migration.Snapshot;
import restudio.resync.migration.SnapshotMetadata;
import restudio.resync.migration.SnapshotService;
import restudio.resync.migration.StagedMigration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RestoreFoundationTest {
    private static final String CATALOG_CHECKSUM = "b".repeat(64);
    private static final String BINDING_HASH = "c".repeat(64);

    @TempDir
    Path temporary;

    @Test
    void rejectsIncompatibleBuildContractAndSnapshotBeforeMutation() throws IOException {
        Fixture fixture = fixture();
        Path initial = fixture.activation().activeRoot().orElseThrow();

        RestorePreflight buildResult = fixture.service().preflight(fixture.request(policy("other-build", 1, CATALOG_CHECKSUM)));
        assertFalse(buildResult.passed());
        assertTrue(buildResult.diagnostics().diagnostics().stream().anyMatch(value -> value.code().equals("RESTORE.BUILD")));

        RestorePreflight contractResult = fixture.service().preflight(fixture.request(policy("resync-test", 1, "d".repeat(64))));
        assertFalse(contractResult.passed());
        assertTrue(contractResult.diagnostics().diagnostics().stream().anyMatch(value -> value.code().equals("RESTORE.CONTRACT")));

        RestorePreflight snapshotResult = fixture.service().preflight(fixture.request(policy("resync-test", 2, CATALOG_CHECKSUM)));
        assertFalse(snapshotResult.passed());
        assertTrue(snapshotResult.diagnostics().diagnostics().stream().anyMatch(value -> value.code().equals("RESTORE.SNAPSHOT_FORMAT")));

        assertEquals(initial, fixture.activation().activeRoot().orElseThrow());
        RestoreRequest request = fixture.request(policy("resync-test", 1, CATALOG_CHECKSUM));
        assertFalse(Files.exists(request.journalPath()));
        assertFalse(Files.exists(request.restoreStagingRoot()));
    }

    @Test
    void rejectsRestoreWhenAParticipantCannotProveAtomicRebind() throws IOException {
        Fixture fixture = fixture();
        PersistenceParticipantRegistry unsafe = new PersistenceParticipantRegistry();
        Path activeRoot = fixture.activation().activeRoot().orElseThrow();
        unsafe.register(new PersistenceParticipant() {
            @Override
            public String owner() {
                return "unsafe";
            }

            @Override
            public Path root() {
                return activeRoot;
            }
        });
        RestoreService service = new RestoreService(new MigrationFence(), unsafe, fixture.activation());

        RestorePreflight result = service.preflight(fixture.request(policy("resync-test", 1, CATALOG_CHECKSUM)));

        assertFalse(result.passed());
        assertTrue(result.checks().stream().anyMatch(check -> check.name().equals("active-root") && !check.passed()));
    }

    @Test
    void snapshotsCurrentStateAndActivatesOnlyAfterFullVerification() throws IOException {
        Fixture fixture = fixture();
        Path previous = fixture.activation().activeRoot().orElseThrow();

        RestoreResult result = fixture.service().restore(fixture.request(policy("resync-test", 1, CATALOG_CHECKSUM)));

        Path active = fixture.activation().activeRoot().orElseThrow();
        assertEquals(result.staged().root(), active);
        assertEquals("target", Files.readString(active.resolve("value.txt")));
        assertEquals("previous", Files.readString(result.currentSnapshot().orElseThrow().root().resolve("value.txt")));
        assertTrue(Files.isDirectory(previous));
        assertEquals(MigrationJournalState.COMMITTED, result.finalState());
        assertEquals(MigrationJournalState.COMMITTED, MigrationJournal.open(result.journalPath()).currentState().orElseThrow());
    }

    @Test
    void validatesAuthoritativeParticipantsBeforeResumingAndRebuildingDerivedCaches() throws IOException {
        Path previousSource = Files.createDirectory(temporary.resolve("ordered-previous-source"));
        createState(previousSource, "previous");
        Snapshot previousSnapshot = snapshot(previousSource, "ordered-previous-snapshot");
        AtomicRestoreActivation activation = new AtomicRestoreActivation(temporary.resolve("ordered-restore-control"));
        activation.activate(activation.stage(previousSnapshot, temporary.resolve("ordered-initial-root")));

        List<String> calls = new ArrayList<>();
        Path previousRoot = activation.activeRoot().orElseThrow();
        PersistenceParticipantRegistry participants = new PersistenceParticipantRegistry();
        participants.register(new OrderedParticipant("authority", previousRoot, "authority", PersistenceParticipantClassification.AUTHORITATIVE, calls));
        participants.register(new OrderedParticipant("cache", previousRoot, "cache", PersistenceParticipantClassification.DERIVED_CACHE, calls));

        Path targetSource = Files.createDirectory(temporary.resolve("ordered-target-source"));
        createState(targetSource, "target");
        Snapshot targetSnapshot = snapshot(targetSource, "ordered-target-snapshot");
        RestoreRequest request = new RestoreRequest(targetSnapshot, policy("resync-test", 1, CATALOG_CHECKSUM),
            temporary.resolve("ordered-current-snapshot"), metadata("ordered-current"),
            temporary.resolve("ordered-restore-staging"), temporary.resolve("ordered-restore-journal"));

        RestoreResult result = new RestoreService(new MigrationFence(), participants, activation).restore(request);

        assertEquals(MigrationJournalState.COMMITTED, result.finalState());
        assertEquals(List.of("authority-rebind", "cache-rebind", "authority-health", "authority-readiness",
            "authority-resume", "cache-resume", "cache-health", "cache-readiness"), calls);
    }

    @Test
    void validationFailureRestoresPriorRootAndRemovesStaging() throws IOException {
        Fixture fixture = fixture();
        Path previous = fixture.activation().activeRoot().orElseThrow();
        RestoreService failing = new RestoreService(new MigrationFence(), fixture.participants(), fixture.activation(), (snapshot, staged) -> {
            throw new IOException("staged validation failed");
        });

        RestoreRequest request = fixture.request(policy("resync-test", 1, CATALOG_CHECKSUM));
        assertThrows(IOException.class, () -> failing.restore(request));

        assertEquals(previous, fixture.activation().activeRoot().orElseThrow());
        assertFalse(Files.exists(request.restoreStagingRoot()));
        assertEquals(MigrationJournalState.ROLLED_BACK, MigrationJournal.open(request.journalPath()).currentState().orElseThrow());
    }

    @Test
    void recoversAnInterruptedActivatedRestoreDeterministically() throws IOException {
        Fixture fixture = fixture();
        RestoreRequest request = fixture.request(policy("resync-test", 1, CATALOG_CHECKSUM));
        StagedMigration staged = fixture.activation().stage(fixture.snapshot(), request.restoreStagingRoot());
        RestoreJournalDetail detail = RestoreJournalDetail.of(staged, fixture.snapshot().manifest().manifestHash());
        MigrationJournal journal = MigrationJournal.create(request.journalPath(), "restore-transaction", staged.planHash());
        journal.transition(MigrationJournalState.PREPARED, "prepared");
        journal.transition(MigrationJournalState.TRANSFORMING, "transforming");
        journal.transition(MigrationJournalState.VALIDATING, detail.encode());
        journal.transition(MigrationJournalState.STAGED, detail.encode());
        fixture.activation.activate(staged);
        journal.transition(MigrationJournalState.ACTIVATED, detail.encode());

        RestoreRecoveryResult result = fixture.service().recover(request);

        assertEquals(MigrationJournalState.COMMITTED, result.finalState());
        assertEquals(staged.root(), fixture.activation().activeRoot().orElseThrow());
        assertEquals(MigrationJournalState.COMMITTED, MigrationJournal.open(request.journalPath()).currentState().orElseThrow());
    }

    @Test
    void explicitlyRollsBackAnActivatedRestoreAndRebindsThePreviousRoot() throws IOException {
        Fixture fixture = fixture();
        Path previous = fixture.activation().activeRoot().orElseThrow();
        RestoreRequest request = fixture.request(policy("resync-test", 1, CATALOG_CHECKSUM));
        StagedMigration staged = fixture.activation().stage(fixture.snapshot(), request.restoreStagingRoot());
        RestoreJournalDetail detail = RestoreJournalDetail.of(staged, fixture.snapshot().manifest().manifestHash());
        MigrationJournal journal = MigrationJournal.create(request.journalPath(), "restore-transaction", staged.planHash());
        journal.transition(MigrationJournalState.PREPARED, "prepared");
        journal.transition(MigrationJournalState.TRANSFORMING, "transforming");
        journal.transition(MigrationJournalState.VALIDATING, detail.encode());
        journal.transition(MigrationJournalState.STAGED, detail.encode());
        fixture.activation.activate(staged);
        journal.transition(MigrationJournalState.ACTIVATED, detail.encode());

        RestoreRecoveryResult result = fixture.service().rollback(request);

        assertEquals(MigrationJournalState.ROLLED_BACK, result.finalState());
        assertEquals(previous, fixture.activation().activeRoot().orElseThrow());
        assertEquals("previous", Files.readString(previous.resolve("value.txt")));
        assertFalse(Files.exists(request.restoreStagingRoot()));
        assertEquals(MigrationJournalState.ROLLED_BACK, MigrationJournal.open(request.journalPath()).currentState().orElseThrow());
    }

    @Test
    void fullValidatorRejectsTamperedStagedTree() throws IOException {
        Fixture fixture = fixture();
        RestoreRequest request = fixture.request(policy("resync-test", 1, CATALOG_CHECKSUM));
        StagedMigration staged = fixture.activation().stage(fixture.snapshot(), request.restoreStagingRoot());
        Files.writeString(staged.root().resolve("value.txt"), "tampered");

        assertThrows(IOException.class, () -> new FullRestoreValidator().validate(fixture.snapshot(), staged));

        fixture.activation().rollback(staged);
        fixture.activation().discard(staged.root());
    }

    @Test
    void discardRejectsOutsideScopeAndActivationRootsWithoutDeletion() throws IOException {
        Fixture fixture = fixture();
        Path scopeMarker = Files.writeString(temporary.resolve("scope-marker.txt"), "keep");
        Path controlMarker = Files.writeString(temporary.resolve("restore-control").resolve("control-marker.txt"), "keep");
        Path outside = Files.createTempDirectory(temporary.getParent(), "restore-outside-");
        Path outsideMarker = Files.writeString(outside.resolve("outside-marker.txt"), "keep");
        try {
            assertThrows(IOException.class, () -> fixture.activation().discard(outside));
            assertTrue(Files.exists(outsideMarker));

            assertThrows(IOException.class, () -> fixture.activation().discard(temporary));
            assertTrue(Files.exists(scopeMarker));

            assertThrows(IOException.class, () -> fixture.activation().discard(temporary.resolve("restore-control")));
            assertTrue(Files.exists(controlMarker));
        } finally {
            Files.deleteIfExists(outsideMarker);
            Files.deleteIfExists(outside);
            Files.deleteIfExists(scopeMarker);
            Files.deleteIfExists(controlMarker);
        }
    }

    private Fixture fixture() throws IOException {
        Path previousSource = Files.createDirectory(temporary.resolve("previous-source"));
        Files.writeString(previousSource.resolve("value.txt"), "previous");
        Snapshot previousSnapshot = snapshot(previousSource, "previous-snapshot");
        AtomicRestoreActivation activation = new AtomicRestoreActivation(temporary.resolve("restore-control"));
        StagedMigration initial = activation.stage(previousSnapshot, temporary.resolve("initial-root"));
        activation.activate(initial);

        Path targetSource = Files.createDirectory(temporary.resolve("target-source"));
        Files.writeString(targetSource.resolve("value.txt"), "target");
        Snapshot targetSnapshot = snapshot(targetSource, "restore-snapshot");
        PersistenceParticipantRegistry participants = registry(activation.activeRoot().orElseThrow());
        RestoreService service = new RestoreService(new MigrationFence(), participants, activation);
        return new Fixture(targetSnapshot, activation, participants, service);
    }

    private Snapshot snapshot(Path source, String id) throws IOException {
        return new SnapshotService(new MigrationFence()).create(source, temporary.resolve(id), metadata(id), registry(source));
    }

    private void createState(Path root, String state) throws IOException {
        Files.createDirectories(root.resolve("authority"));
        Files.writeString(root.resolve("authority").resolve("marker.txt"), state);
        Files.createDirectories(root.resolve("cache"));
        Files.writeString(root.resolve("cache").resolve("marker.txt"), state);
    }

    private PersistenceParticipantRegistry registry(Path root) {
        PersistenceParticipantRegistry participants = new PersistenceParticipantRegistry();
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

    private SnapshotMetadata metadata(String id) {
        return new SnapshotMetadata(1, id, Instant.parse("2026-01-01T00:00:00Z"), "resync-test", CATALOG_CHECKSUM, Map.of("extension.test", "1.0.0"));
    }

    private static RestoreCompatibilityPolicy policy(String build, int format, String checksum) {
        return new RestoreCompatibilityPolicy(format, format, Set.of(build), new CatalogVersion(1, 0), new CatalogBinding(1, checksum, BINDING_HASH), Map.of("extension.test", "1.0.0"));
    }

    private static final class OrderedParticipant implements RebindablePersistenceParticipant {
        private final String owner;
        private final Path scopeRoot;
        private final Path relativeRoot;
        private final PersistenceParticipantClassification classification;
        private final List<String> calls;
        private Path root;

        private OrderedParticipant(String owner, Path scopeRoot, String relativeRoot,
                                   PersistenceParticipantClassification classification, List<String> calls) {
            this.owner = owner;
            this.scopeRoot = scopeRoot.toAbsolutePath().normalize();
            this.relativeRoot = Path.of(relativeRoot);
            this.classification = classification;
            this.calls = calls;
            this.root = this.scopeRoot.resolve(this.relativeRoot).normalize();
        }

        @Override
        public String owner() {
            return owner;
        }

        @Override
        public Path root() {
            return root;
        }

        @Override
        public Path rebindScope() {
            return scopeRoot;
        }

        @Override
        public PersistenceParticipantClassification classification() {
            return classification;
        }

        @Override
        public void flush() {
        }

        @Override
        public void quiesce() {
        }

        @Override
        public void rebind(Path activeRoot) throws IOException {
            Path next = activeRoot.toAbsolutePath().normalize().resolve(relativeRoot).normalize();
            if (!Files.isDirectory(next)) {
                throw new IOException("Participant Root Is Missing");
            }
            root = next;
            record("rebind");
        }

        @Override
        public void resume() throws IOException {
            record("resume");
        }

        @Override
        public void healthCheck() throws IOException {
            record("health");
        }

        @Override
        public void readinessCheck() throws IOException {
            record("readiness");
        }

        private void record(String phase) throws IOException {
            if ("target".equals(Files.readString(root.resolve("marker.txt")))) {
                calls.add(owner + "-" + phase);
            }
        }
    }

    private record Fixture(Snapshot snapshot, AtomicRestoreActivation activation, PersistenceParticipantRegistry participants, RestoreService service) {
        RestoreRequest request(RestoreCompatibilityPolicy compatibility) {
            return new RestoreRequest(snapshot, compatibility, snapshot.root().resolveSibling("current-snapshot"), new SnapshotMetadata(1, "before-restore", Instant.parse("2026-01-01T00:00:00Z"), "resync-test", CATALOG_CHECKSUM, Map.of("extension.test", "1.0.0")), snapshot.root().resolveSibling("restore-staging"), snapshot.root().resolveSibling("restore-journal"));
        }
    }
}
