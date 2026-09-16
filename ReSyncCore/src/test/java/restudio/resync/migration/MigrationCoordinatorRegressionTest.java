package restudio.resync.migration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import restudio.resync.upgrade.AssetAdoptionArtifactProducer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MigrationCoordinatorRegressionTest {
    @TempDir
    Path temporary;

    @Test
    void requiresQuarantineAcceptanceToMatchReportAndHash() throws IOException {
        QuarantineReport report = new QuarantineReport(List.of(
                record("record-a", "a"),
                record("record-b", "b")));
        QuarantineAcceptance accepted = report.accept("operator", Instant.EPOCH);

        report.requireAccepted(accepted);

        QuarantineAcceptance wrongReport = new QuarantineAcceptance(
                "0".repeat(64),
                accepted.acceptedRecordIds(),
                accepted.acceptedBy(),
                accepted.acceptedAt(),
                accepted.acceptanceHash());
        MigrationException reportFailure = assertThrows(MigrationException.class, () -> report.requireAccepted(wrongReport));
        assertEquals("Quarantine Acceptance Does Not Match Report", reportFailure.getMessage());

        QuarantineAcceptance wrongHash = new QuarantineAcceptance(
                accepted.reportHash(),
                accepted.acceptedRecordIds(),
                accepted.acceptedBy(),
                accepted.acceptedAt(),
                "0".repeat(64));
        MigrationException hashFailure = assertThrows(MigrationException.class, () -> report.requireAccepted(wrongHash));
        assertEquals("Quarantine Acceptance Hash Is Invalid", hashFailure.getMessage());

        QuarantineAcceptance incomplete = report.acceptance(List.of("record-a"), "operator", Instant.EPOCH);
        MigrationException incompleteFailure = assertThrows(MigrationException.class, () -> report.requireAccepted(incomplete));
        assertEquals("Every Quarantine Record Requires Explicit Acceptance", incompleteFailure.getMessage());
    }

    @Test
    void coordinatorRejectsInvalidQuarantineAcceptanceBeforeParticipantMutation() throws IOException {
        Path source = Files.createDirectory(temporary.resolve("invalid-acceptance-source"));
        Files.writeString(source.resolve("value.txt"), "source");
        List<String> lifecycle = new ArrayList<>();
        PersistenceParticipantRegistry participants = registry(source, lifecycle);
        SnapshotMetadata metadata = metadata("invalid-acceptance-snapshot");
        QuarantineReport report = new QuarantineReport(List.of(record("record-a", "value.txt")));
        MigrationPlan plan = plan(source, metadata, report, List.of(new MigrationOperation("copy", "adapter.copy", "value.txt", "value.txt", "", "")), participants);
        MigrationJournal journal = MigrationJournal.create(temporary.resolve("invalid-acceptance-journal"), "invalid-acceptance-migration", plan.planHash());
        QuarantineAcceptance valid = report.accept("operator", Instant.EPOCH);
        QuarantineAcceptance invalid = new QuarantineAcceptance(valid.reportHash(), valid.acceptedRecordIds(), valid.acceptedBy(), valid.acceptedAt(), "0".repeat(64));
        AtomicDirectoryRootStore roots = new AtomicDirectoryRootStore(temporary.resolve("invalid-acceptance-control"));

        MigrationRequest request = request(source, metadata, plan, journal, roots, staged -> {
        }, roots, roots, report, invalid);
        MigrationException failure = assertThrows(MigrationException.class, () -> new MigrationCoordinator(new MigrationFence(), participants).execute(request));

        assertEquals("Quarantine Acceptance Hash Is Invalid", failure.getMessage());
        assertEquals(List.of(), lifecycle);
        assertEquals(Optional.empty(), journal.currentState());
    }

    @Test
    void coordinatorRejectsChangedUntouchedFileBeforeValidationOrActivation() throws IOException {
        Path source = Files.createDirectory(temporary.resolve("untouched-source"));
        Files.writeString(source.resolve("touched.txt"), "legacy");
        Files.writeString(source.resolve("untouched.txt"), "keep!!");
        List<String> lifecycle = new ArrayList<>();
        PersistenceParticipantRegistry participants = registry(source, lifecycle);
        SnapshotMetadata metadata = metadata("untouched-snapshot");
        QuarantineReport report = QuarantineReport.empty();
        MigrationPlan plan = plan(source, metadata, report, List.of(new MigrationOperation("replace", "adapter.replace", "touched.txt", "touched.txt", "", "")), participants);
        MigrationJournal journal = MigrationJournal.create(temporary.resolve("untouched-journal"), "untouched-migration", plan.planHash());
        boolean[] validated = {false};
        boolean[] activated = {false};
        MigrationStager stager = (snapshotRoot, stagingRoot, ignored) -> {
            Files.createDirectories(stagingRoot);
            Files.writeString(stagingRoot.resolve("touched.txt"), "target!");
            Files.writeString(stagingRoot.resolve("untouched.txt"), "alter!");
            return new StagedMigration(stagingRoot, Optional.empty(), ignored.planHash(), TreeDigest.of(stagingRoot));
        };
        MigrationRequest request = request(source, metadata, plan, journal, stager, staged -> validated[0] = true, staged -> activated[0] = true, staged -> {
        }, report, report.accept("operator", Instant.EPOCH));

        MigrationException failure = assertThrows(MigrationException.class, () -> new MigrationCoordinator(new MigrationFence(), participants).execute(request));

        assertEquals("Untouched Source File Was Changed: untouched.txt", failure.getMessage());
        assertFalse(validated[0]);
        assertFalse(activated[0]);
        assertEquals(List.of("flush", "quiesce", "resume"), lifecycle);
        assertEquals(Optional.of(MigrationJournalState.ROLLED_BACK), journal.currentState());
    }

    @Test
    void stagedRecoveryRestagesTypedEvidenceWhenTheStagingRootWasLost() throws Exception {
        Path source = Files.createDirectory(temporary.resolve("typed-recovery-source"));
        Files.writeString(source.resolve("owned.txt"), "typed-recovery");
        SnapshotMetadata metadata = metadata("typed-recovery-snapshot");
        PersistenceParticipantRegistry participants = new PersistenceParticipantRegistry();
        AtomicReference<Path> participantRoot = new AtomicReference<>(source);
        participants.register(new PersistenceParticipant() {
            @Override
            public String owner() {
                return "typed-recovery";
            }

            @Override
            public Path root() {
                return participantRoot.get();
            }

            @Override
            public void rebind(Path activeRoot) {
                participantRoot.set(activeRoot);
            }
        });
        SnapshotService snapshots = new SnapshotService(new MigrationFence());
        PersistenceParticipantRegistry sourceParticipants = new PersistenceParticipantRegistry();
        sourceParticipants.register(participant("typed-recovery-source", source));
        Snapshot retained = snapshots.create(source, temporary.resolve("typed-recovery-snapshot-stage"), metadata, sourceParticipants);
        VerifiedSnapshotAdmission sourceAdmission = snapshots.admitExported(retained.root());
        Path postSource = Files.createDirectory(temporary.resolve("typed-recovery-post-source"));
        Files.writeString(postSource.resolve("owned.txt"), "typed-recovery");
        Snapshot post = snapshots.create(postSource, temporary.resolve("typed-recovery-post-stage"),
            metadata("typed-recovery-post-snapshot"), participantRegistry("typed-recovery-post", postSource));
        VerifiedSnapshotAdmission postAdmission = snapshots.admitExported(post.root());
        QuarantineReport report = QuarantineReport.empty();
        QuarantineAcceptance acceptance = report.accept("typed-recovery", Instant.EPOCH);
        MigrationPlan plan = new MigrationPlan(metadata.snapshotId(), retained.manifest().manifestHash(), 1, 2,
            report.reportHash(), List.of());
        MigrationJournal journal = MigrationJournal.create(temporary.resolve("typed-recovery-journal"),
            "typed-recovery-migration", plan.planHash());
        journal.transition(MigrationJournalState.PREPARED, "prepared");
        journal.transition(MigrationJournalState.TRANSFORMING, "transforming");
        journal.transition(MigrationJournalState.VALIDATING, "validating");
        journal.transition(MigrationJournalState.STAGED, "staged");
        AtomicInteger stageCalls = new AtomicInteger();
        AtomicReference<AcceptedStagePublisher.Publication> publication = new AtomicReference<>();
        AssetAdoptionArtifactProducer.StageOutput stageOutput = new AssetAdoptionArtifactProducer.StageOutput(
            plan.planHash(), sourceAdmission.metadata().snapshotId(), sourceAdmission.manifestHash(), postAdmission,
            List.of(), report, acceptance, List.of());
        MigrationStager stager = (snapshotRoot, stagingRoot, ignored) -> {
            stageCalls.incrementAndGet();
            Files.createDirectories(stagingRoot);
            Files.writeString(stagingRoot.resolve("owned.txt"), "typed-recovery");
            return new StagedMigration(stagingRoot, Optional.empty(), ignored.planHash(), TreeDigest.of(stagingRoot),
                new StagedMigration.TypedEvidence(sourceAdmission, stageOutput));
        };
        AcceptedStagePublisher publisher = new AcceptedStagePublisher() {
            @Override
            public boolean requiresTypedEvidenceForRecovery() {
                return true;
            }

            @Override
            public Result publish(Publication value) {
                publication.set(value);
                return Result.none();
            }
        };
        AtomicReference<Path> activeRoot = new AtomicReference<>();
        MigrationActivator activator = new MigrationActivator() {
            @Override
            public void activate(StagedMigration staged) {
                activeRoot.set(staged.root());
            }

            @Override
            public Optional<Path> activeRoot() {
                return Optional.ofNullable(activeRoot.get());
            }
        };
        MigrationRequest request = new MigrationRequest(
            source,
            retained.root(),
            metadata,
            temporary.resolve("typed-recovery-migration-stage"),
            plan,
            journal,
            stager,
            staged -> {
            },
            activator,
            staged -> {
            },
            null,
            report,
            acceptance,
            0);

        new MigrationCoordinator(new MigrationFence(), participants, publisher).recover(request);

        assertEquals(1, stageCalls.get());
        assertTrue(publication.get().typedStageOutput().isPresent());
        assertEquals(Optional.of(MigrationJournalState.COMMITTED), journal.currentState());
    }

    @Test
    void committedRecoveryRejectsRetainedSnapshotWithVerifiedStateButNonEmptyFailures() throws IOException {
        Path source = Files.createDirectory(temporary.resolve("committed-retained-failures-source"));
        Files.writeString(source.resolve("value.txt"), "committed-retained-failures");
        SnapshotMetadata metadata = metadata("committed-retained-failures-snapshot");
        PersistenceParticipantRegistry participants = participantRegistry("committed-retained-failures", source);
        Snapshot retained = new SnapshotService(new MigrationFence()).create(source,
            temporary.resolve("committed-retained-failures-snapshot-stage"), metadata, participants);
        String content = Files.readString(retained.statePath()).replace("failures=0", "failures=1");
        Files.writeString(retained.statePath(), content + "failure="
            + MigrationCanonical.encode("retained-failure") + '\n');
        QuarantineReport report = QuarantineReport.empty();
        QuarantineAcceptance acceptance = report.accept("committed-recovery", Instant.EPOCH);
        MigrationPlan plan = new MigrationPlan(metadata.snapshotId(), retained.manifest().manifestHash(), 1, 2,
            report.reportHash(), List.of());
        MigrationJournal journal = MigrationJournal.create(temporary.resolve("committed-retained-failures-journal"),
            "committed-retained-failures", plan.planHash());
        journal.transition(MigrationJournalState.PREPARED, "prepared");
        journal.transition(MigrationJournalState.TRANSFORMING, "transforming");
        journal.transition(MigrationJournalState.VALIDATING, "validating");
        journal.transition(MigrationJournalState.STAGED, "staged");
        journal.transition(MigrationJournalState.ACTIVATED, "activated");
        journal.transition(MigrationJournalState.COMMITTED, "committed");
        MigrationActivator activator = new MigrationActivator() {
            @Override
            public void activate(StagedMigration staged) {
            }

            @Override
            public Optional<Path> activeRoot() {
                return Optional.of(source);
            }
        };
        MigrationRequest request = new MigrationRequest(
            source,
            retained.root(),
            metadata,
            temporary.resolve("committed-retained-failures-migration-stage"),
            plan,
            journal,
            (snapshotRoot, stagingRoot, ignored) -> {
                throw new AssertionError("Committed recovery must not restage a rejected snapshot");
            },
            staged -> {
            },
            activator,
            staged -> {
            },
            null,
            report,
            acceptance,
            0);

        MigrationException failure = assertThrows(MigrationException.class,
            () -> new MigrationCoordinator(new MigrationFence(), participants).recover(request));

        assertEquals("Retained Migration Snapshot Is Not Bound To The Request", failure.getMessage());
        assertEquals(Optional.of(MigrationJournalState.COMMITTED), journal.currentState());
    }

    private MigrationRequest request(Path source, SnapshotMetadata metadata, MigrationPlan plan, MigrationJournal journal, MigrationStager stager, StagedMigrationValidator validator, MigrationActivator activator, MigrationRollback rollback, QuarantineReport report, QuarantineAcceptance acceptance) {
        return new MigrationRequest(
                source,
                temporary.resolve(plan.sourceSnapshotId() + "-snapshot-stage"),
                metadata,
                temporary.resolve(plan.sourceSnapshotId() + "-migration-stage"),
                plan,
                journal,
                stager,
                validator,
                activator,
                rollback,
                null,
                report,
                acceptance,
                0);
    }

    private MigrationPlan plan(Path source, SnapshotMetadata metadata, QuarantineReport report, List<MigrationOperation> operations, PersistenceParticipantRegistry participants) throws IOException {
        return new MigrationPlan(metadata.snapshotId(), SnapshotManifest.scan(source, metadata, participants).manifestHash(), 1, 2, report.reportHash(), operations);
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

    private PersistenceParticipantRegistry participantRegistry(String owner, Path root) {
        PersistenceParticipantRegistry participants = new PersistenceParticipantRegistry();
        participants.register(participant(owner, root));
        return participants;
    }

    private PersistenceParticipant participant(String owner, Path root) {
        return new PersistenceParticipant() {
            @Override
            public String owner() {
                return owner;
            }

            @Override
            public Path root() {
                return root;
            }
        };
    }

    private QuarantineRecord record(String id, String sourceLocation) {
        return new QuarantineRecord(id, "MIGRATION.TEST", sourceLocation, "Unsupported value", List.of(), "Review the value", "1".repeat(64));
    }

    private SnapshotMetadata metadata(String snapshotId) {
        return new SnapshotMetadata(1, snapshotId, Instant.parse("2026-01-01T00:00:00Z"), "resync-test", "b".repeat(64), Map.of());
    }
}
