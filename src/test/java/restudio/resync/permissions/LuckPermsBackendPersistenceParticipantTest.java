package restudio.resync.permissions;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.resync.migration.MigrationFence;
import restudio.resync.migration.PersistenceOwnershipContext;
import restudio.resync.migration.PersistenceOwnershipIndex;
import restudio.resync.migration.ReSyncPersistenceCoordinator;
import restudio.resync.server.ReSyncPersistenceTopology;
import restudio.resync.server.ReSyncServer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LuckPermsBackendPersistenceParticipantTest {
    @TempDir
    Path temporary;

    @Test
    void capableProviderIsRegisteredAsTheSameCentralParticipantAndCompletesRebind() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("resync"));
        FakeAdapter adapter = new FakeAdapter(true);
        LuckPermsBackendPersistenceCapability capability = new LuckPermsBackendPersistenceCapability(adapter);
        LuckPermsBackendPersistenceParticipant participant =
            new LuckPermsBackendPersistenceParticipant(dataRoot, capability);

        ReSyncPersistenceTopology.Binding binding =
            ReSyncServer.luckPermsBackendBinding(dataRoot, capability, participant);
        assertSame(participant, binding.participant());
        assertSame(capability, participant.capability());

        ReSyncPersistenceCoordinator coordinator = new ReSyncPersistenceCoordinator(
            dataRoot, temporary.resolve("coordination"), new MigrationFence());
        ReSyncPersistenceTopology.Registration registration = ReSyncPersistenceTopology.register(
            coordinator, dataRoot, List.of(binding));
        assertTrue(registration.sealed());
        assertTrue(registration.registeredOwners().contains(LuckPermsBackendPersistenceParticipant.OWNER));
        assertSame(participant, coordinator.registeredParticipants().stream()
            .filter(value -> value.owner().equals(LuckPermsBackendPersistenceParticipant.OWNER))
            .findFirst().orElseThrow());

        participant.quiesce();
        participant.resume();
        participant.quiesce();
        Path replacement = Files.createDirectory(temporary.resolve("replacement"));
        copyTree(participant.root(), replacement.resolve("runtime").resolve("luckperms-backend"));
        Path previousBackendRoot = capability.activeRoot().orElseThrow();
        participant.rebind(replacement);
        assertEquals(previousBackendRoot, capability.activeRoot().orElseThrow());
        participant.resume();

        assertEquals(replacement.resolve("runtime").resolve("luckperms-backend").toAbsolutePath().normalize(), participant.root());
        assertEquals(replacement.toAbsolutePath().normalize(), capability.activeRoot().orElseThrow());
        assertTrue(adapter.operations.stream().anyMatch(value -> value.startsWith("rebind:")));
        coordinator.close();
    }

    @Test
    void incapableProviderRemainsAnExplicitCentralReadinessGap() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("resync-incapable"));
        LuckPermsBackendPersistenceCapability capability =
            new LuckPermsBackendPersistenceCapability(new FakeAdapter(false));
        LuckPermsBackendPersistenceParticipant participant =
            new LuckPermsBackendPersistenceParticipant(dataRoot, capability);

        ReSyncPersistenceTopology.Binding binding =
            ReSyncServer.luckPermsBackendBinding(dataRoot, capability, participant);
        assertTrue(binding.required());
        assertTrue(binding.unavailableReason().contains("backend"));
        assertFalse(capability.readiness().available());

        ReSyncPersistenceCoordinator coordinator = new ReSyncPersistenceCoordinator(
            dataRoot, temporary.resolve("coordination-incapable"), new MigrationFence());
        ReSyncPersistenceTopology.Registration registration = ReSyncPersistenceTopology.register(
            coordinator, dataRoot, List.of(binding));
        assertFalse(registration.sealed());
        assertTrue(registration.unavailableOwners().contains(LuckPermsBackendPersistenceParticipant.OWNER));
        assertTrue(coordinator.registeredParticipants().isEmpty());
    }

    @Test
    void ownershipIndexIncludesTheBackendRootAndDescendants() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("resync-index"));
        LuckPermsBackendPersistenceCapability capability =
            new LuckPermsBackendPersistenceCapability(new FakeAdapter(true));
        LuckPermsBackendPersistenceParticipant participant =
            new LuckPermsBackendPersistenceParticipant(dataRoot, capability);
        PersistenceOwnershipContext context = new PersistenceOwnershipContext(dataRoot, participant.root());
        PersistenceOwnershipIndex index = participant.ownershipIndex(context);

        assertTrue(index.owns(context.relativeToSource(participant.root())));
        assertTrue(index.owns(context.relativeToSource(participant.root().resolve("nested/snapshot.bin"))));
        participant.close();
    }

    @Test
    void closeFailureLeavesParticipantRetryableUntilAdapterCloses() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("resync-close"));
        FakeAdapter adapter = new FakeAdapter(true);
        LuckPermsBackendPersistenceCapability capability = new LuckPermsBackendPersistenceCapability(adapter);
        LuckPermsBackendPersistenceParticipant participant =
            new LuckPermsBackendPersistenceParticipant(dataRoot, capability);

        participant.quiesce();
        adapter.closeFailure = new IOException("close failed");
        assertThrows(IOException.class, participant::close);
        assertFalse(adapter.closed);
        assertEquals(1, adapter.closeAttempts);

        adapter.closeFailure = null;
        participant.close();
        assertTrue(adapter.closed);
        assertEquals(2, adapter.closeAttempts);
    }

    private static void copyTree(Path source, Path target) throws IOException {
        Files.walk(source).forEach(path -> {
            try {
                Path relative = source.relativize(path);
                Path destination = target.resolve(relative).normalize();
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(path, destination);
                }
            } catch (IOException exception) {
                throw new CopyFailure(exception);
            }
        });
    }

    private static final class CopyFailure extends RuntimeException {
        private CopyFailure(IOException cause) {
            super(cause);
        }
    }

    private static final class FakeAdapter implements LuckPermsBackendPersistenceCapability.Adapter {
        private final boolean capable;
        private final Path backendRoot = Path.of("luckperms-backend").toAbsolutePath().normalize();
        private final List<String> operations = new ArrayList<>();
        private IOException closeFailure;
        private int closeAttempts;
        private boolean closed;
        private final LuckPermsBackendPersistenceCapability.BackendIdentity identity =
            new LuckPermsBackendPersistenceCapability.BackendIdentity("luckperms", "participant-test");
        private final LuckPermsBackendPersistenceCapability.AdapterContract contract =
            new LuckPermsBackendPersistenceCapability.AdapterContract("participant-test", "1");

        private FakeAdapter(boolean capable) {
            this.capable = capable;
        }

        @Override
        public String id() {
            return "participant-test";
        }

        @Override
        public Path activeRoot() {
            return backendRoot;
        }

        @Override
        public LuckPermsBackendPersistenceCapability.BackendIdentity identity() {
            return identity;
        }

        @Override
        public LuckPermsBackendPersistenceCapability.AdapterContract contract() {
            return contract;
        }

        @Override
        public LuckPermsBackendPersistenceCapability.Support support() {
            return capable ? LuckPermsBackendPersistenceCapability.Support.capable()
                : LuckPermsBackendPersistenceCapability.Support.unavailable("backend adapter is incapable");
        }

        @Override
        public void flush() {
            operations.add("flush");
        }

        @Override
        public LuckPermsBackendPersistenceCapability.SnapshotReceipt backup(Path snapshotRoot, long generation)
            throws IOException {
            operations.add("backup");
            Files.createDirectories(snapshotRoot);
            Path artifact = snapshotRoot.resolve("adapter.bin");
            Files.writeString(artifact, "participant snapshot", StandardCharsets.UTF_8);
            String hash;
            try {
                hash = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(artifact)));
            } catch (NoSuchAlgorithmException exception) {
                throw new IOException(exception);
            }
            return LuckPermsBackendPersistenceCapability.SnapshotReceipt.local(
                identity, generation, contract, hash, artifact);
        }

        @Override
        public void restore(LuckPermsBackendPersistenceCapability.SnapshotReceipt receipt) {
            operations.add("restore");
        }

        @Override
        public void rebind(Path activeRoot, LuckPermsBackendPersistenceCapability.SnapshotReceipt receipt) {
            operations.add("rebind:" + activeRoot.toAbsolutePath().normalize());
        }

        @Override
        public void verifySnapshot(LuckPermsBackendPersistenceCapability.SnapshotReceipt receipt) {
        }

        @Override
        public void quiesce() {
            operations.add("quiesce");
        }

        @Override
        public void resume() {
            operations.add("resume");
        }

        @Override
        public void healthCheck() {
            operations.add("health");
        }

        @Override
        public void close() throws IOException {
            closeAttempts++;
            if (closeFailure != null) {
                throw closeFailure;
            }
            closed = true;
            operations.add("close");
        }
    }
}
