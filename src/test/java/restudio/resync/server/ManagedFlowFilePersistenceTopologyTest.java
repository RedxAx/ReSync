package restudio.resync.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.resync.flow.handler.generic.ManagedFlowFileCapability;
import restudio.resync.flow.handler.generic.ManagedFlowFilePersistenceParticipant;
import restudio.resync.flow.handler.generic.SqliteManagedFlowFileCapability;
import restudio.resync.migration.PersistenceParticipant;
import restudio.resync.migration.PersistenceRootReadiness;
import restudio.resync.migration.ReSyncPersistenceCoordinator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagedFlowFilePersistenceTopologyTest {
    @TempDir
    Path temporary;

    @Test
    void registersTheExactStoreAndClosesItThroughTheCoordinator() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("resync"));
        SqliteManagedFlowFileCapability capability = new SqliteManagedFlowFileCapability(dataRoot);
        ManagedFlowFilePersistenceParticipant participant = new ManagedFlowFilePersistenceParticipant(dataRoot, capability);
        ReSyncPersistenceCoordinator coordinator = coordinator(dataRoot);

        ReSyncPersistenceTopology.Registration registration = ReSyncPersistenceTopology.register(
            coordinator,
            dataRoot,
            List.of(ReSyncPersistenceTopology.requiredForRestore(
                ManagedFlowFilePersistenceParticipant.OWNER, participant.root(), participant)),
            List.of(PersistenceRootReadiness.UncoveredWriter.of(
                ManagedFlowFilePersistenceParticipant.OWNER, participant.root(), "managed flow files")));

        assertTrue(registration.sealed());
        assertEquals(List.of(ManagedFlowFilePersistenceParticipant.OWNER), registration.registeredOwners());
        assertTrue(registration.readiness().complete());
        assertTrue(registration.readiness().uncoveredWriterIds().isEmpty());

        coordinator.close();
        assertFalse(capability.available());
    }

    @Test
    void restoreRebindMovesTheExactStoreOnlyToAnExistingValidatedDatabase() throws Exception {
        Path sourceRoot = Files.createDirectory(temporary.resolve("source"));
        Path targetRoot = Files.createDirectory(temporary.resolve("target"));
        try (SqliteManagedFlowFileCapability target = new SqliteManagedFlowFileCapability(targetRoot)) {
            target.write("from-target.txt", "target");
        }
        SqliteManagedFlowFileCapability source = new SqliteManagedFlowFileCapability(sourceRoot);
        ManagedFlowFilePersistenceParticipant participant = new ManagedFlowFilePersistenceParticipant(sourceRoot, source);
        ReSyncPersistenceCoordinator coordinator = coordinator(sourceRoot);
        ReSyncPersistenceTopology.register(
            coordinator,
            sourceRoot,
            List.of(ReSyncPersistenceTopology.requiredForRestore(
                ManagedFlowFilePersistenceParticipant.OWNER, participant.root(), participant)),
            List.of());

        coordinator.participants().quiesceAll();
        coordinator.participants().rebindAll(targetRoot);
        coordinator.participants().resumeAll();

        assertEquals(targetRoot.resolve(ManagedFlowFileCapability.ROOT_DIRECTORY).toAbsolutePath().normalize(), source.persistenceRoot());
        assertEquals("target", source.read("from-target.txt"));
        coordinator.close();
    }

    @Test
    void unavailableFileStorageKeepsOnlyItsOwnerAsTheMigrationReadinessGap() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("corrupt"));
        Path flowRoot = Files.createDirectories(dataRoot.resolve(ManagedFlowFileCapability.ROOT_DIRECTORY));
        Files.writeString(flowRoot.resolve(ManagedFlowFileCapability.DATABASE_FILE), "not sqlite");
        ReSyncPersistenceCoordinator coordinator = coordinator(dataRoot);

        ReSyncPersistenceTopology.Registration registration = ReSyncPersistenceTopology.register(
            coordinator,
            dataRoot,
            List.of(ReSyncPersistenceTopology.unavailable(
                ManagedFlowFilePersistenceParticipant.OWNER,
                flowRoot,
                "Managed flow-file store is corrupt; File nodes remain disabled; if legacy physical FileHandler bytes are present, they require an explicit manifest-backed offline migration; runtime startup does not scan or delete arbitrary legacy bytes")),
            ReSyncUncoveredWriterInventory.forDataRoot(dataRoot));

        assertFalse(registration.sealed());
        assertTrue(registration.unavailableOwners().contains(ManagedFlowFilePersistenceParticipant.OWNER));
        assertTrue(registration.readiness().uncoveredWriterIds().contains(ManagedFlowFilePersistenceParticipant.OWNER));
        assertTrue(registration.readiness().owner(ManagedFlowFilePersistenceParticipant.OWNER).reason()
            .contains("manifest-backed offline migration"));
    }

    @Test
    void stagedParticipantClosesWhenTopologyAdmissionFails() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("topology-failure"));
        SqliteManagedFlowFileCapability capability = new SqliteManagedFlowFileCapability(dataRoot);
        ManagedFlowFilePersistenceParticipant participant = new ManagedFlowFilePersistenceParticipant(dataRoot, capability);
        ReSyncPersistenceCoordinator coordinator = coordinator(dataRoot);
        coordinator.register(participant);
        Path overlappingRoot = Files.createDirectory(participant.root().resolve("overlap"));
        PersistenceParticipant overlappingParticipant = new PersistenceParticipant() {
            @Override
            public String owner() {
                return "resync.flow.files.overlap";
            }

            @Override
            public Path root() {
                return overlappingRoot;
            }
        };

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () ->
            ReSyncPersistenceTopology.register(
                coordinator,
                dataRoot,
                List.of(
                    ReSyncPersistenceTopology.requiredForRestore(
                        ManagedFlowFilePersistenceParticipant.OWNER, participant.root(), participant),
                    ReSyncPersistenceTopology.requiredForRestore(
                        overlappingParticipant.owner(), overlappingRoot, overlappingParticipant))));

        assertEquals("CLOSED", coordinator.close().payload().get("state"));
        try (SqliteManagedFlowFileCapability reopened = new SqliteManagedFlowFileCapability(dataRoot, false)) {
            assertTrue(reopened.available());
        }
    }

    @Test
    void stagedParticipantClosesWhenModuleInitializationAborts() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("module-failure"));
        SqliteManagedFlowFileCapability capability = new SqliteManagedFlowFileCapability(dataRoot);
        ManagedFlowFilePersistenceParticipant participant = new ManagedFlowFilePersistenceParticipant(dataRoot, capability);
        ReSyncPersistenceCoordinator coordinator = coordinator(dataRoot);
        coordinator.register(participant);

        try {
            throw new IllegalStateException("module initialization failed");
        } catch (IllegalStateException ignored) {
            assertEquals("CLOSED", coordinator.close().payload().get("state"));
        }

        try (SqliteManagedFlowFileCapability reopened = new SqliteManagedFlowFileCapability(dataRoot, false)) {
            assertTrue(reopened.available());
        }
    }

    private ReSyncPersistenceCoordinator coordinator(Path dataRoot) throws Exception {
        return new ReSyncPersistenceCoordinator(dataRoot, temporary.resolve("coordination-" + dataRoot.getFileName()));
    }
}
