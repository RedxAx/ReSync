package restudio.resync.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.resync.migration.PersistenceOwnershipContext;
import restudio.resync.migration.PersistenceOwnershipIndex;
import restudio.resync.migration.PersistenceRootReadiness;
import restudio.resync.migration.ReSyncPersistenceCoordinator;
import restudio.resync.world.WorldManagementPersistenceParticipant;
import restudio.resync.world.WorldStateStorage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldManagementPersistenceParticipantTest {
    @TempDir
    Path temporary;

    @Test
    void quiesceBlocksWorldManagementWritesAndResumeReopensAdmission() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("source"));
        WorldStateStorage storage = new WorldStateStorage(dataRoot);
        WorldManagementPersistenceParticipant participant = participant(dataRoot, storage);

        storage.saveWorlds(List.of());
        participant.quiesce();
        assertThrows(IllegalStateException.class, () -> storage.saveWorlds(List.of()));

        participant.resume();
        storage.saveWorlds(List.of());
        assertTrue(Files.exists(storage.getRootPath().resolve("worlds.json")));
    }

    @Test
    void rebindRequiresQuiescenceAndKeepsThePreviousRootOnFailure() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("source"));
        WorldStateStorage storage = new WorldStateStorage(dataRoot);
        WorldManagementPersistenceParticipant participant = participant(dataRoot, storage);
        Path previousRoot = participant.root();
        Path replacement = temporary.resolve("replacement");
        Files.createDirectories(replacement.resolve("world-management"));

        assertThrows(IOException.class, () -> participant.rebind(replacement));
        participant.quiesce();
        assertThrows(IOException.class, () -> participant.rebind(temporary.resolve("missing")));
        assertEquals(previousRoot, participant.root());
        participant.rebind(replacement);
        assertEquals(replacement.resolve("world-management").toAbsolutePath().normalize(), participant.root());
        participant.resume();
    }

    @Test
    void inventoryGapKeepsWorldParticipantRegisteredButBlocksSealAndSnapshotCapability() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("source"));
        WorldStateStorage storage = new WorldStateStorage(dataRoot);
        WorldManagementPersistenceParticipant participant = participant(dataRoot, storage);
        ReSyncPersistenceCoordinator coordinator = new ReSyncPersistenceCoordinator(dataRoot, temporary.resolve("coordination"));
        PersistenceRootReadiness.UncoveredWriter writer = PersistenceRootReadiness.UncoveredWriter.of(
            "resync.runtime", dataRoot.resolve("runtime"), "Runtime persistence is not coordinated");

        ReSyncPersistenceTopology.Registration registration = ReSyncPersistenceTopology.register(
            coordinator,
            dataRoot,
            List.of(ReSyncPersistenceTopology.requiredForRestore(participant.owner(), participant.root(), participant)),
            List.of(writer));

        assertFalse(registration.sealed());
        assertEquals(PersistenceRootReadiness.State.REGISTERED, registration.readiness().owner(participant.owner()).state());
        assertEquals(List.of(writer.id()), registration.readiness().uncoveredWriterIds());
        assertFalse(ReSyncServer.persistenceCapability(coordinator, registration).containsKey("snapshot"));
        assertFalse(ReSyncServer.persistenceCapability(coordinator, registration).containsKey("restore"));
    }

    @Test
    void ownershipIndexIncludesTheWorldManagementRootAndDescendants() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("source-index"));
        WorldStateStorage storage = new WorldStateStorage(dataRoot);
        WorldManagementPersistenceParticipant participant = participant(dataRoot, storage);
        PersistenceOwnershipContext context = new PersistenceOwnershipContext(dataRoot, participant.root());
        PersistenceOwnershipIndex index = participant.ownershipIndex(context);

        assertTrue(index.owns(context.relativeToSource(participant.root())));
        assertTrue(index.owns(context.relativeToSource(participant.root().resolve("nested/world.json"))));
    }

    private WorldManagementPersistenceParticipant participant(Path dataRoot, WorldStateStorage storage) {
        return new WorldManagementPersistenceParticipant(dataRoot, new WorldManagementPersistenceParticipant.Controller() {
            @Override
            public Path persistenceRoot() {
                return storage.getRootPath();
            }

            @Override
            public void flushPersistence() throws IOException {
                storage.flushPersistence();
            }

            @Override
            public void quiescePersistence() throws IOException {
                storage.quiescePersistence();
            }

            @Override
            public void resumePersistence() throws IOException {
                storage.resumePersistence();
            }

            @Override
            public void rebindPersistence(Path activeRoot) throws IOException {
                storage.rebindPersistence(activeRoot);
            }

            @Override
            public void healthCheckPersistence() throws IOException {
                storage.healthCheckPersistence();
            }
        });
    }
}
