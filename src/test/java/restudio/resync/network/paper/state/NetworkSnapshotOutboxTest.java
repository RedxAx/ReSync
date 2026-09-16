package restudio.resync.network.paper.state;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;
import restudio.resync.network.NetworkPayloads;
import restudio.resync.network.PlayerStateSnapshot;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkSnapshotOutboxTest {
    @TempDir
    Path temporary;

    @Test
    void retriesOfTheSameSnapshotAreIdempotentAndConflictingPayloadsFailClosed() {
        NetworkSnapshotOutbox outbox = new NetworkSnapshotOutbox(temporary.resolve("source"));
        PlayerStateSnapshot snapshot = snapshot("same");

        outbox.save(snapshot);
        outbox.save(snapshot);

        assertEquals(List.of(snapshot), outbox.load());
        assertThrows(IllegalStateException.class, () -> outbox.save(new PlayerStateSnapshot(
            snapshot.snapshotId(), snapshot.networkId(), snapshot.playerId(), snapshot.fenceEpoch(), snapshot.family(),
            new byte[]{9}, NetworkPayloads.sha256(new byte[]{9}), snapshot.schemaVersion(), snapshot.dataVersion(),
            snapshot.originNodeId(), snapshot.createdAt(), snapshot.pinned())));
    }

    @Test
    void quiesceBlocksOutboxMutationAndResumeReopensIt() throws Exception {
        NetworkSnapshotOutbox outbox = new NetworkSnapshotOutbox(temporary.resolve("source"));
        PlayerStateSnapshot snapshot = snapshot("quiesce");

        outbox.save(snapshot);
        outbox.quiesce();

        assertThrows(IllegalStateException.class, () -> outbox.save(snapshot("blocked")));
        assertThrows(IllegalStateException.class, () -> outbox.remove(snapshot.snapshotId()));

        outbox.resume();
        outbox.remove(snapshot.snapshotId());
        assertTrue(outbox.load().isEmpty());
    }

    @Test
    void rebindRequiresQuiescenceAndPreservesThePreviousRootWhenTheCandidateIsInvalid() throws Exception {
        Path source = Files.createDirectories(temporary.resolve("source"));
        NetworkSnapshotOutbox outbox = new NetworkSnapshotOutbox(source);
        outbox.save(snapshot("rebind"));
        Path previous = outbox.root();

        assertThrows(IOException.class, () -> outbox.rebind(temporary.resolve("missing")));
        outbox.quiesce();
        assertThrows(IOException.class, () -> outbox.rebind(temporary.resolve("missing")));
        assertEquals(previous, outbox.root());

        Path replacement = Files.createDirectories(temporary.resolve("replacement"));
        outbox.rebind(replacement);
        outbox.resume();

        assertEquals(replacement, outbox.root());
        assertTrue(outbox.load().isEmpty());
    }

    @Test
    void malformedSnapshotEntryFailsHealthCheck() throws Exception {
        Path root = Files.createDirectories(temporary.resolve("source"));
        Path entry = root.resolve(NetworkPayloads.sha256("broken".getBytes()) + ".snapshot");
        Files.write(entry, new byte[]{1, 2, 3});
        NetworkSnapshotOutbox outbox = new NetworkSnapshotOutbox(root);

        assertThrows(IOException.class, outbox::healthCheck);
        assertThrows(RuntimeException.class, outbox::load);
    }

    @Test
    void canonicalTemporaryEntryIsQuarantinedWithoutChangingTheOutbox() throws Exception {
        Path root = Files.createDirectories(temporary.resolve("temporary"));
        PlayerStateSnapshot snapshot = snapshot("temporary");
        Path destination = root.resolve(NetworkPayloads.sha256(snapshot.snapshotId().getBytes()) + ".snapshot");
        Files.write(destination.resolveSibling(destination.getFileName() + ".tmp"), new byte[]{1, 2, 3});

        NetworkSnapshotOutbox outbox = new NetworkSnapshotOutbox(root);

        assertTrue(outbox.load().isEmpty());
        assertTrue(!Files.exists(destination.resolveSibling(destination.getFileName() + ".tmp")));
        assertTrue(Files.isDirectory(root.resolve(".quarantine/network-exact-files")));
    }

    @Test
    void temporaryCollisionFailsClosedAfterPreservingTheTemporaryEntry() throws Exception {
        Path root = Files.createDirectories(temporary.resolve("collision"));
        NetworkSnapshotOutbox outbox = new NetworkSnapshotOutbox(root);
        PlayerStateSnapshot snapshot = snapshot("collision");
        outbox.save(snapshot);
        Path destination = root.resolve(NetworkPayloads.sha256(snapshot.snapshotId().getBytes()) + ".snapshot");
        Files.write(destination.resolveSibling(destination.getFileName() + ".tmp"), new byte[]{1, 2, 3});

        assertThrows(IllegalStateException.class, () -> new NetworkSnapshotOutbox(root));
        assertTrue(!Files.exists(destination.resolveSibling(destination.getFileName() + ".tmp")));
        assertTrue(Files.isDirectory(root.resolve(".quarantine/network-exact-files")));
    }

    @Test
    void temporarySymlinkFailsClosedWithoutReadingItsTarget() throws Exception {
        Path root = Files.createDirectories(temporary.resolve("symlink"));
        PlayerStateSnapshot snapshot = snapshot("symlink");
        Path destination = root.resolve(NetworkPayloads.sha256(snapshot.snapshotId().getBytes()) + ".snapshot");
        Path target = root.resolve("outside");
        Files.write(target, new byte[]{1, 2, 3});
        try {
            Files.createSymbolicLink(destination.resolveSibling(destination.getFileName() + ".tmp"), target);
        } catch (UnsupportedOperationException | IOException exception) {
            Assumptions.assumeTrue(false);
        }

        assertThrows(IllegalStateException.class, () -> new NetworkSnapshotOutbox(root));
        assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(target));
    }

    @Test
    void unknownOutboxEntryFailsClosed() throws Exception {
        Path root = Files.createDirectories(temporary.resolve("unknown"));
        Files.write(root.resolve("unknown.tmp"), new byte[]{1});

        assertThrows(IllegalStateException.class, () -> new NetworkSnapshotOutbox(root));
    }

    @Test
    void startupRejectsAnOutboxWithTooManyEntries() throws Exception {
        Path root = Files.createDirectories(temporary.resolve("too-many"));
        for (int index = 0; index < 65; index++) {
            Files.write(root.resolve(String.format("%064x.snapshot", index)), new byte[]{1});
        }

        assertThrows(IllegalStateException.class, () -> new NetworkSnapshotOutbox(root));
    }

    @Test
    void startupRejectsAnOutboxThatExceedsItsTotalByteLimit() throws Exception {
        Path root = Files.createDirectories(temporary.resolve("too-large"));
        long maximumFileBytes = 33554432L;
        for (int index = 0; index < 8; index++) {
            Path entry = root.resolve(String.format("%064x.snapshot", index));
            try (FileChannel channel = FileChannel.open(entry, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                channel.position(maximumFileBytes - 1);
                channel.write(ByteBuffer.wrap(new byte[]{1}));
            }
        }
        Files.write(root.resolve(String.format("%064x.snapshot", 8)), new byte[]{1});

        assertThrows(IllegalStateException.class, () -> new NetworkSnapshotOutbox(root));
    }

    @Test
    void storageSafetyTemporaryEntryIsQuarantinedUsingItsCanonicalGrammar() throws Exception {
        Path root = Files.createDirectories(temporary.resolve("storage-temporary"));
        Path storageTemporary = root.resolve(".resync-00000000-0000-0000-0000-000000000001.tmp");
        Files.write(storageTemporary, new byte[]{1, 2, 3});

        NetworkSnapshotOutbox outbox = new NetworkSnapshotOutbox(root);

        assertTrue(outbox.load().isEmpty());
        assertTrue(!Files.exists(storageTemporary));
        try (var files = Files.list(root.resolve(".quarantine/network-exact-files"))) {
            assertTrue(files.anyMatch(path -> path.getFileName().toString().startsWith("temporary.")
                && path.getFileName().toString().endsWith(".evidence")));
        }
    }

    @Test
    void mismatchedSnapshotPayloadHashCannotBePublished() {
        NetworkSnapshotOutbox outbox = new NetworkSnapshotOutbox(temporary.resolve("hash-mismatch"));
        PlayerStateSnapshot snapshot = snapshot("hash-mismatch");
        PlayerStateSnapshot invalid = new PlayerStateSnapshot(snapshot.snapshotId(), snapshot.networkId(),
            snapshot.playerId(), snapshot.fenceEpoch(), snapshot.family(), snapshot.payload(), "0".repeat(64),
            snapshot.schemaVersion(), snapshot.dataVersion(), snapshot.originNodeId(), snapshot.createdAt(),
            snapshot.pinned());

        assertThrows(IllegalArgumentException.class, () -> outbox.save(invalid));
        assertTrue(outbox.load().isEmpty());
    }

    @Test
    void removeValidatesStoredSnapshotIdentityBeforeDeleting() throws Exception {
        Path root = Files.createDirectories(temporary.resolve("remove-integrity"));
        NetworkSnapshotOutbox outbox = new NetworkSnapshotOutbox(root);
        PlayerStateSnapshot snapshot = snapshot("remove-integrity");
        outbox.save(snapshot);
        Path destination = root.resolve(NetworkPayloads.sha256(snapshot.snapshotId().getBytes()) + ".snapshot");
        Files.write(destination, new byte[]{1, 2, 3});

        assertThrows(IllegalStateException.class, () -> outbox.remove(snapshot.snapshotId()));
        assertTrue(Files.exists(destination));
    }

    private PlayerStateSnapshot snapshot(String id) {
        byte[] payload = id.getBytes();
        return new PlayerStateSnapshot(id, "network", UUID.randomUUID(), 1, "survival", payload,
            NetworkPayloads.sha256(payload), 1, 5000, "node", 1000, false);
    }
}
