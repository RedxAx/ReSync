package restudio.resync.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.resync.migration.PersistenceExternalInput;
import restudio.resync.migration.PersistenceParticipant;
import restudio.resync.migration.RebindablePersistenceParticipant;
import restudio.resync.migration.ReSyncPersistenceCoordinator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReSyncExternalInputTopologyTest {
    @TempDir
    Path temporary;

    @Test
    void topologyRegistersInputsWithoutTurningThemIntoWriters() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("resync"));
        Path owned = Files.writeString(root.resolve("owned.json"), "{}");
        ReSyncPersistenceCoordinator coordinator = new ReSyncPersistenceCoordinator(root, temporary.resolve("coordination"));
        PersistenceParticipant participant = new PersistenceParticipant() {
            @Override
            public String owner() {
                return "owned";
            }

            @Override
            public Path root() {
                return owned;
            }
        };

        ReSyncPersistenceTopology.Registration registration = ReSyncPersistenceTopology.register(
            coordinator, root, List.of(ReSyncPersistenceTopology.safeNoOp("owned", owned, participant)), List.of());

        assertTrue(registration.sealed());
        assertEquals(List.of("dataRoot/nodes", "resync.properties"), registration.readiness().externalInputs().stream()
            .map(PersistenceExternalInput.Input::id).toList());
        assertEquals(List.of("dataRoot/nodes", "resync.properties"), coordinator.participants().externalInputs().stream()
            .map(PersistenceExternalInput.Input::id).toList());
    }

    @Test
    void topologyUsesOriginalRootForExternalInputsAfterPreparingActiveRoot() throws Exception {
        Path originalRoot = Files.createDirectory(temporary.resolve("resync-original"));
        Files.createDirectory(originalRoot.resolve("nodes"));
        Files.writeString(originalRoot.resolve("resync.properties"), "resync.enabled=true\n");
        Files.createDirectories(originalRoot.resolve("owned"));
        ReSyncPersistenceCoordinator coordinator = new ReSyncPersistenceCoordinator(originalRoot, temporary.resolve("coordination"));
        Path activeRoot = coordinator.prepareActiveRoot();
        Path ownedRoot = activeRoot.resolve("owned");
        PersistenceParticipant participant = activeRootParticipant(activeRoot, ownedRoot);

        ReSyncPersistenceTopology.Registration registration = ReSyncPersistenceTopology.register(
            coordinator, activeRoot,
            List.of(ReSyncPersistenceTopology.requiredForRestore("active", ownedRoot, participant)), List.of());

        assertTrue(registration.sealed());
        assertEquals(ownedRoot, registration.readiness().owner("active").root());
        assertEquals(ownedRoot, coordinator.registeredParticipants().stream().findFirst().orElseThrow().root());
        assertEquals(List.of(originalRoot.resolve("nodes"), originalRoot.resolve("resync.properties")),
            registration.readiness().externalInputs().stream().map(PersistenceExternalInput.Input::path).toList());
        assertEquals(registration.readiness().externalInputs(), List.copyOf(coordinator.participants().externalInputs()));
        assertTrue(registration.readiness().externalInputs().stream().allMatch(PersistenceExternalInput.Input::excludedFromPersistence));
    }

    private PersistenceParticipant activeRootParticipant(Path scopeRoot, Path root) {
        Path relativeRoot = scopeRoot.relativize(root);
        return new RebindablePersistenceParticipant() {
            private Path activeRoot = root;

            @Override
            public String owner() {
                return "active";
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
                this.activeRoot = activeRoot.resolve(relativeRoot).normalize();
            }

            @Override
            public void healthCheck() {
            }
        };
    }
}
