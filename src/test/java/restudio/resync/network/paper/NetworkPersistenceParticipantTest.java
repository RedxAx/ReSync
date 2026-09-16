package restudio.resync.network.paper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.resync.migration.MigrationException;
import restudio.resync.migration.PersistenceOwnershipContext;
import restudio.resync.migration.PersistenceOwnershipIndex;
import restudio.resync.migration.PersistenceParticipantRegistry;
import restudio.resync.migration.PersistenceRebindStatus;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkPersistenceParticipantTest {
    @TempDir
    Path temporary;

    @Test
    void quiesceStopsNetworkWritersAndResumeReopensAdmission() throws Exception {
        Path source = Files.createDirectories(temporary.resolve("source").resolve("network"));
        FakeController controller = new FakeController(source);
        NetworkPersistenceParticipant participant = new NetworkPersistenceParticipant(source.getParent(), controller);

        controller.write("before");
        participant.flush();
        participant.quiesce();

        assertThrows(IllegalStateException.class, () -> controller.write("blocked"));

        participant.resume();
        controller.write("after");

        assertEquals(List.of("flush", "quiesce", "resume"), controller.calls());
        assertEquals(List.of("before", "after"), controller.values());
    }

    @Test
    void rebindRequiresAValidQuiescedNetworkRootAndRetainsPreviousRootOnFailure() throws Exception {
        Path source = Files.createDirectories(temporary.resolve("source").resolve("network"));
        FakeController controller = new FakeController(source);
        NetworkPersistenceParticipant participant = new NetworkPersistenceParticipant(source.getParent(), controller);
        Path previous = participant.root();
        Path replacement = temporary.resolve("replacement");

        assertThrows(IOException.class, () -> participant.rebind(replacement));
        participant.quiesce();
        assertThrows(IOException.class, () -> participant.rebind(replacement));
        assertEquals(previous, participant.root());

        Files.createDirectories(replacement.resolve("network"));
        participant.rebind(replacement);

        assertEquals(replacement.resolve("network").toAbsolutePath().normalize(), participant.root());
        assertEquals(replacement.toAbsolutePath().normalize(), participant.rebindScope());
        assertTrue(controller.calls().contains("rebind:" + replacement.resolve("network").toAbsolutePath().normalize()));
        participant.resume();
    }

    @Test
    void registryRebindRollsBackNetworkParticipantWhenTheCandidateFailsHealth() throws Exception {
        Path source = Files.createDirectories(temporary.resolve("source").resolve("network"));
        FakeController controller = new FakeController(source);
        NetworkPersistenceParticipant participant = new NetworkPersistenceParticipant(source.getParent(), controller);
        PersistenceParticipantRegistry registry = new PersistenceParticipantRegistry(source.getParent());
        registry.register(participant);
        Path replacement = Files.createDirectories(temporary.resolve("replacement").resolve("network")).getParent();

        participant.quiesce();
        controller.failNextRebind = true;
        assertThrows(MigrationException.class, () -> registry.rebindAll(replacement));

        assertEquals(source, participant.root());
        assertEquals(PersistenceRebindStatus.State.ROLLED_BACK, registry.rebindStatus().state());
        assertTrue(controller.calls().contains("rebind:" + source));
        participant.resume();
    }

    @Test
    void constructorRejectsAControllerBoundToAnotherRoot() throws Exception {
        Path source = Files.createDirectories(temporary.resolve("source").resolve("network"));
        Path other = Files.createDirectories(temporary.resolve("other").resolve("network"));

        assertThrows(IllegalArgumentException.class, () -> new NetworkPersistenceParticipant(source.getParent(), new FakeController(other)));
    }

    @Test
    void ownershipIndexIncludesTheNetworkRootAndDescendants() throws Exception {
        Path source = Files.createDirectories(temporary.resolve("source-index").resolve("network"));
        NetworkPersistenceParticipant participant = new NetworkPersistenceParticipant(
            source.getParent(), new FakeController(source));
        PersistenceOwnershipContext context = new PersistenceOwnershipContext(source.getParent(), participant.root());
        PersistenceOwnershipIndex index = participant.ownershipIndex(context);

        assertTrue(index.owns(context.relativeToSource(participant.root())));
        assertTrue(index.owns(context.relativeToSource(participant.root().resolve("nested/network.json"))));
        assertTrue(index.owns(context.relativeToSource(participant.root().resolve(
            ".resync-00000000-0000-0000-0000-000000000001.tmp"))));
        assertTrue(index.owns(context.relativeToSource(participant.root().resolve(
            ".quarantine/network-exact-files/temporary.00000000-0000-0000-0000-000000000001.evidence"))));
    }

    private static final class FakeController implements NetworkPersistenceParticipant.Controller {
        private final List<String> calls = new ArrayList<>();
        private final List<String> values = new ArrayList<>();
        private Path root;
        private boolean open = true;
        private boolean failNextRebind;

        private FakeController(Path root) {
            this.root = root.toAbsolutePath().normalize();
        }

        @Override
        public Path persistenceRoot() {
            return root;
        }

        public void flush() {
            calls.add("flush");
        }

        @Override
        public void flushPersistence() {
            flush();
        }

        @Override
        public void quiescePersistence() {
            calls.add("quiesce");
            open = false;
        }

        @Override
        public void resumePersistence() {
            calls.add("resume");
            open = true;
        }

        @Override
        public void rebindPersistence(Path activeRoot) throws IOException {
            calls.add("rebind:" + activeRoot);
            if (failNextRebind) {
                failNextRebind = false;
                throw new IOException("Network candidate failed health validation");
            }
            if (!Files.isDirectory(activeRoot)) {
                throw new IOException("Network candidate root is missing");
            }
            root = activeRoot.toAbsolutePath().normalize();
        }

        @Override
        public void healthCheckPersistence() throws IOException {
            calls.add("health");
            if (!Files.isDirectory(root)) {
                throw new IOException("Network root is unavailable");
            }
        }

        private void write(String value) {
            if (!open) {
                throw new IllegalStateException("Network persistence is quiesced");
            }
            values.add(value);
        }

        private List<String> calls() {
            return List.copyOf(calls);
        }

        private List<String> values() {
            return List.copyOf(values);
        }
    }
}
