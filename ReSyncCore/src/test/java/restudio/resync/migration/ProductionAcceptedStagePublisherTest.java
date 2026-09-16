package restudio.resync.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import restudio.resync.flow.identity.SnapshotId;
import restudio.resync.upgrade.AssetAdoptionArtifactProducer;

class ProductionAcceptedStagePublisherTest {
    @TempDir
    Path temporary;

    @Test
    void productionContractIdentityIsFixed() {
        assertEquals(AssetAdoptionArtifactProducer.FORMAT, ProductionAcceptedStagePublisher.CONTRACT_IDENTITY);
        assertThrows(IllegalArgumentException.class,
            () -> new ProductionAcceptedStagePublisher.ContractIdentity("custom-contract"));
    }

    @Test
    void productionPublisherLeavesRecoveryRetryableWithoutExactStageOutput() throws Exception {
        Path source = Files.createDirectories(temporary.resolve("source"));
        Files.writeString(source.resolve("owned.txt"), "publisher-test");
        Path staged = Files.createDirectories(temporary.resolve("staged"));
        Path coordination = Files.createDirectories(temporary.resolve("coordination"));
        SnapshotMetadata metadata = new SnapshotMetadata(1, SnapshotId.deterministic("publisher-test").canonicalText(),
            Instant.EPOCH, "source-build", "a".repeat(64), Map.of());
        PersistenceParticipantRegistry participants = new PersistenceParticipantRegistry();
        participants.register(new PersistenceParticipant() {
            @Override
            public String owner() {
                return "source-owner";
            }

            @Override
            public Path root() {
                return source;
            }
        });
        SnapshotService snapshots = new SnapshotService(new MigrationFence());
        Snapshot exported = snapshots.create(source, temporary.resolve("source-export"), metadata, participants);
        Snapshot snapshot = snapshots.admitExported(exported.root()).snapshot();
        QuarantineReport report = QuarantineReport.empty();
        QuarantineAcceptance acceptance = report.accept("publisher-test", Instant.EPOCH);
        AcceptedStagePublisher.Publication publication = new AcceptedStagePublisher.Publication(
            "publisher-test", snapshot, Optional.empty(), "b".repeat(64),
            new StagedMigration(staged, Optional.empty(), "b".repeat(64), TreeDigest.of(staged)), report, acceptance,
            null, ProductionAcceptedStagePublisher.CONTRACT_IDENTITY);

        AcceptedStagePublisher.Failure failure = assertThrows(AcceptedStagePublisher.Failure.class,
            () -> new ProductionAcceptedStagePublisher(coordination).publish(publication));

        assertTrue(failure.retryable());
        assertEquals("Production Accepted Stage Recovery Requires Exact Typed Stage Evidence", failure.getMessage());
        assertTrue(Files.notExists(coordination.resolve(AssetAdoptionArtifactProducer.ARTIFACT_RELATIVE_PATH)));
    }

    @Test
    void boundArtifactReplayRepopulatesResultAndRejectsChangedActiveBytes() throws Exception {
        Path source = Files.createDirectories(temporary.resolve("replay-source"));
        Files.writeString(source.resolve("value.txt"), "replacement");
        Path active = Files.createDirectories(temporary.resolve("replay-active"));
        Files.writeString(active.resolve("value.txt"), "replacement");
        Path coordination = Files.createDirectories(temporary.resolve("replay-coordination"));
        SnapshotMetadata sourceMetadata = new SnapshotMetadata(1, SnapshotId.deterministic("replay-source").canonicalText(),
            Instant.EPOCH, "source-build", "a".repeat(64), Map.of());
        SnapshotMetadata targetMetadata = new SnapshotMetadata(1, SnapshotId.deterministic("replay-target").canonicalText(),
            Instant.EPOCH, "target-build", "b".repeat(64), Map.of());
        PersistenceParticipantRegistry sourceParticipants = new PersistenceParticipantRegistry();
        sourceParticipants.register(participant("source-owner", source));
        SnapshotService snapshots = new SnapshotService(new MigrationFence());
        Snapshot sourceExport = snapshots.create(source, temporary.resolve("replay-source-export"), sourceMetadata, sourceParticipants);
        VerifiedSnapshotAdmission sourceAdmission = snapshots.admitExported(sourceExport.root());
        PersistenceParticipantRegistry activeParticipants = new PersistenceParticipantRegistry();
        activeParticipants.register(participant("source-owner", active));
        Snapshot postStageExport = snapshots.create(active, temporary.resolve("replay-post-stage-export"), targetMetadata, activeParticipants);
        VerifiedSnapshotAdmission postStageAdmission = snapshots.admitExported(postStageExport.root());
        QuarantineReport report = QuarantineReport.empty();
        QuarantineAcceptance acceptance = report.accept("replay-test", Instant.EPOCH);
        String planHash = "c".repeat(64);
        StagedMigration staged = new StagedMigration(active, Optional.empty(), planHash, TreeDigest.of(active));
        AssetAdoptionArtifactProducer.StageOutput output = new AssetAdoptionArtifactProducer.StageOutput(
            planHash, sourceAdmission.metadata().snapshotId(), sourceAdmission.manifestHash(), postStageAdmission,
            List.of(), report, acceptance, List.of());
        AssetAdoptionArtifactProducer.Result artifact = AssetAdoptionArtifactProducer.produce(coordination, sourceAdmission, output);
        MigrationJournal.PublicationBinding publicationBinding = new MigrationJournal.PublicationBinding(
            artifact.artifactHash(), ProductionAcceptedStagePublisher.CONTRACT_IDENTITY,
            sourceAdmission.manifestHash(), postStageAdmission.manifestHash());
        MigrationJournal.Binding journalBinding = new MigrationJournal.Binding(
            sourceAdmission.metadata().snapshotId(), sourceAdmission.manifestHash(), report.reportHash(),
            acceptance.acceptanceHash(), staged.contentHash(), "", "d".repeat(64), "e".repeat(64), 1,
            "f".repeat(64), 1).withPublicationBinding(publicationBinding);
        AcceptedStagePublisher.Publication publication = new AcceptedStagePublisher.Publication(
            "replay-test", sourceAdmission.snapshot(), Optional.of(journalBinding), planHash, staged,
            report, acceptance, null, ProductionAcceptedStagePublisher.CONTRACT_IDENTITY);
        ProductionAcceptedStagePublisher publisher = new ProductionAcceptedStagePublisher(coordination);

        AcceptedStagePublisher.Result replay = publisher.publish(publication);

        assertEquals(publicationBinding, replay.publicationBinding());
        assertEquals(replay, publisher.lastResult());
        Files.writeString(active.resolve("value.txt"), "tampered");
        AcceptedStagePublisher.Failure failure = assertThrows(AcceptedStagePublisher.Failure.class,
            () -> publisher.publish(publication));
        assertFalse(failure.retryable());
    }

    private static PersistenceParticipant participant(String owner, Path root) {
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
}
