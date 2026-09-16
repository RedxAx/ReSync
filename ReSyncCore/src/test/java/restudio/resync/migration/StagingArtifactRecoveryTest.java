package restudio.resync.migration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StagingArtifactRecoveryTest {
    private static final String HASH = "a".repeat(64);

    @TempDir
    Path temporary;

    @Test
    void movesFailedSnapshotRootAndSidecarsToDeterministicQuarantineAndAllowsRetry() throws Exception {
        Path source = Files.createDirectory(temporary.resolve("source"));
        Files.writeString(source.resolve("value.txt"), "source");
        Path staging = temporary.resolve("snapshot");
        Files.createDirectories(staging);
        Files.writeString(staging.resolve("partial.txt"), "partial");
        Path state = staging.resolveSibling("snapshot.state");
        SnapshotStateStore.write(state, SnapshotState.FAILED,
            new SnapshotVerification(false, HASH, List.of("copy failed")));

        StagingArtifactRecovery.prepareSnapshot(staging);

        Path quarantine = StagingArtifactRecovery.quarantinePath(staging,
            StagingArtifactRecovery.SNAPSHOT_STAGING_DIRECTORY);
        Path evidenceSidecars = quarantine.resolve(".quarantine-sidecars");
        assertFalse(Files.exists(staging));
        assertFalse(Files.exists(state));
        assertEquals("partial", Files.readString(quarantine.resolve("partial.txt")));
        assertEquals("copy failed", SnapshotStateStore.readVerification(
            evidenceSidecars.resolve(".state")).failures().getFirst());
        assertTrue(Files.readString(quarantine.resolveSibling(quarantine.getFileName() + ".journal"))
            .contains("transaction=COMPLETE\n"));

        SnapshotMetadata metadata = new SnapshotMetadata(1, "retry", Instant.parse("2026-08-26T00:00:00Z"),
            "build", HASH, Map.of());
        PersistenceParticipantRegistry participants = new PersistenceParticipantRegistry();
        participants.register(new PersistenceParticipant() {
            @Override
            public String owner() {
                return "source";
            }

            @Override
            public Path root() {
                return source;
            }
        });
        Snapshot snapshot = new SnapshotService(new MigrationFence()).create(source, staging, metadata, participants);

        assertTrue(snapshot.verified());
        assertTrue(Files.exists(staging));
        assertArrayEquals(Files.readAllBytes(source.resolve("value.txt")), Files.readAllBytes(staging.resolve("value.txt")));
    }

    @Test
    void rejectsVerifiedSnapshotReplacementWithoutMovingEvidence() throws Exception {
        Path source = Files.createDirectory(temporary.resolve("verified-source"));
        Files.writeString(source.resolve("value.txt"), "verified");
        Path staging = temporary.resolve("verified-snapshot");
        PersistenceParticipantRegistry participants = participants(source);
        SnapshotMetadata metadata = new SnapshotMetadata(1, "verified", Instant.parse("2026-08-26T00:00:00Z"),
            "build", HASH, Map.of());
        new SnapshotService(new MigrationFence()).create(source, staging, metadata, participants);

        assertThrows(MigrationException.class, () -> StagingArtifactRecovery.prepareSnapshot(staging));
        assertTrue(Files.exists(staging));
        assertTrue(Files.exists(staging.resolveSibling("verified-snapshot.state")));
    }

    @Test
    void movesInterruptedStagingStateBeforeARepeatAttempt() throws Exception {
        Path staging = temporary.resolve("interrupted");
        Files.createDirectories(staging);
        Files.writeString(staging.resolve("partial.txt"), "partial");
        Path state = staging.resolveSibling("interrupted.state");
        SnapshotStateStore.write(state, SnapshotState.STAGING,
            new SnapshotVerification(false, "0".repeat(64), List.of("in progress")));

        StagingArtifactRecovery.prepareSnapshot(staging);

        Path quarantine = StagingArtifactRecovery.quarantinePath(staging,
            StagingArtifactRecovery.SNAPSHOT_STAGING_DIRECTORY);
        assertFalse(Files.exists(staging));
        assertFalse(Files.exists(state));
        assertEquals("partial", Files.readString(quarantine.resolve("partial.txt")));
        assertTrue(Files.exists(quarantine.resolve(".quarantine-sidecars/.state")));
    }

    @Test
    void rejectsUnknownSnapshotArtifactAndQuarantineCollisionWithoutMovingIt() throws Exception {
        Path staging = temporary.resolve("ambiguous");
        Files.createDirectories(staging);
        Files.writeString(staging.resolve("partial.txt"), "partial");
        Path state = staging.resolveSibling("ambiguous.state");
        SnapshotStateStore.write(state, SnapshotState.STAGING,
            new SnapshotVerification(false, "0".repeat(64), List.of("in progress")));
        Files.writeString(staging.resolveSibling("ambiguous.tmp"), "unknown");

        assertThrows(MigrationException.class, () -> StagingArtifactRecovery.prepareSnapshot(staging));
        assertTrue(Files.exists(staging));
        assertTrue(Files.exists(state));

        Files.delete(staging.resolveSibling("ambiguous.tmp"));
        Path quarantine = StagingArtifactRecovery.quarantinePath(staging,
            StagingArtifactRecovery.SNAPSHOT_STAGING_DIRECTORY);
        Files.createDirectories(quarantine);

        assertThrows(MigrationException.class, () -> StagingArtifactRecovery.prepareSnapshot(staging));
        assertTrue(Files.exists(staging));
        assertTrue(Files.exists(state));
    }

    @Test
    void resumesAfterRootMoveBeforeSidecarMove() throws Exception {
        Path staging = temporary.resolve("resumable");
        Files.createDirectories(staging);
        Files.writeString(staging.resolve("partial.txt"), "partial");
        Path state = staging.resolveSibling("resumable.state");
        SnapshotStateStore.write(state, SnapshotState.STAGING,
            new SnapshotVerification(false, "0".repeat(64), List.of("in progress")));

        Path quarantine = StagingArtifactRecovery.quarantinePath(staging,
            StagingArtifactRecovery.SNAPSHOT_STAGING_DIRECTORY);
        Files.createDirectories(quarantine.getParent());
        Files.move(staging, quarantine);
        String body = "format=1\n"
            + "category=" + MigrationCanonical.encode(StagingArtifactRecovery.SNAPSHOT_STAGING_DIRECTORY) + "\n"
            + "source-root=" + MigrationCanonical.encode(staging.toAbsolutePath().normalize().toString()) + "\n"
            + "snapshot-state=STAGING\n"
            + "artifacts=2\n"
            + "artifact=ROOT|" + MigrationCanonical.encode(staging.toAbsolutePath().normalize().toString())
            + "|" + MigrationCanonical.encode(".") + "|DIRECTORY|MOVED\n"
            + "artifact=STATE|" + MigrationCanonical.encode(state.toAbsolutePath().normalize().toString())
            + "|" + MigrationCanonical.encode(".quarantine-sidecars/.state") + "|REGULAR|PENDING\n"
            + "transaction=OPEN\n";
        Files.writeString(quarantine.resolveSibling(quarantine.getFileName() + ".journal"), body
            + "journal-hash=" + MigrationCanonical.sha256(body) + "\n");

        StagingArtifactRecovery.prepareSnapshot(staging);

        assertFalse(Files.exists(staging));
        assertFalse(Files.exists(state));
        assertEquals("partial", Files.readString(quarantine.resolve("partial.txt")));
        assertEquals("in progress", SnapshotStateStore.readVerification(
            quarantine.resolve(".quarantine-sidecars").resolve(".state")).failures().getFirst());
        assertTrue(Files.readString(quarantine.resolveSibling(quarantine.getFileName() + ".journal"))
            .contains("transaction=COMPLETE\n"));
    }

    @Test
    void verificationDoesNotPublishVerifiedWithoutProductionMetadata() throws Exception {
        Path source = Files.createDirectory(temporary.resolve("metadata-source"));
        Files.writeString(source.resolve("value.txt"), "value");
        Path staging = temporary.resolve("metadata-snapshot");
        SnapshotMetadata metadata = new SnapshotMetadata(1, "metadata", Instant.parse("2026-08-26T00:00:00Z"),
            "build", HASH, Map.of());
        Snapshot snapshot = new SnapshotService(new MigrationFence()).create(source, staging, metadata, participants(source));
        Files.delete(ProductionSnapshotMetadataManifest.pathFor(staging));

        SnapshotVerification verification = new SnapshotService(new MigrationFence()).verify(snapshot);

        assertFalse(verification.verified());
        assertEquals(SnapshotState.FAILED, SnapshotStateStore.readVerification(snapshot.statePath()).verified()
            ? SnapshotState.VERIFIED : SnapshotState.FAILED);
    }

    @Test
    void movesFailedRestoreRootAndRejectsDestinationCollision() throws Exception {
        Path staging = temporary.resolve("restore-staging");
        Files.createDirectories(staging);
        Files.writeString(staging.resolve("partial.txt"), "partial");

        StagingArtifactRecovery.quarantineRestore(staging);

        Path quarantine = StagingArtifactRecovery.quarantinePath(staging,
            StagingArtifactRecovery.RESTORE_STAGING_DIRECTORY);
        assertFalse(Files.exists(staging));
        assertEquals("partial", Files.readString(quarantine.resolve("partial.txt")));
        assertTrue(Files.readString(quarantine.resolveSibling(quarantine.getFileName() + ".journal"))
            .contains("transaction=COMPLETE\n"));

        Path second = temporary.resolve("restore-second");
        Files.createDirectories(second);
        Files.writeString(second.resolve("partial.txt"), "partial");
        Files.createDirectories(StagingArtifactRecovery.quarantinePath(second,
            StagingArtifactRecovery.RESTORE_STAGING_DIRECTORY));

        assertThrows(MigrationException.class, () -> StagingArtifactRecovery.quarantineRestore(second));
        assertTrue(Files.exists(second));
    }

    private PersistenceParticipantRegistry participants(Path root) {
        PersistenceParticipantRegistry participants = new PersistenceParticipantRegistry();
        participants.register(new PersistenceParticipant() {
            @Override
            public String owner() {
                return "source";
            }

            @Override
            public Path root() {
                return root;
            }
        });
        return participants;
    }
}
