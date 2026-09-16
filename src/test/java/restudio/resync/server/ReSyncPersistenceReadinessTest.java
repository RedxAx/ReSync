package restudio.resync.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.resync.api.ExtensionPersistenceParticipant;
import restudio.resync.migration.PersistenceRootReadiness;
import restudio.resync.migration.RebindablePersistenceParticipant;
import restudio.resync.migration.ReSyncPersistenceCoordinator;
import restudio.resync.flow.triggers.TriggerPersistenceParticipant;
import restudio.resync.flow.triggers.TriggerRegistry;
import restudio.resync.player.PlayerTrackingManager;
import restudio.resync.player.PlayerTrackingPersistenceParticipant;
import restudio.resync.structure.StructureLibrary;
import restudio.resync.structure.StructurePersistenceParticipant;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReSyncPersistenceReadinessTest {
    @TempDir
    Path temporary;

    @Test
    void recordsTheExactRequiredGapWithoutClaimingTheWholeRoot() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("resync"));
        Path diagnosticsRoot = Files.createDirectory(dataRoot.resolve("diagnostics"));
        Path networkRoot = Files.createDirectory(dataRoot.resolve("network"));
        ReSyncPersistenceCoordinator coordinator = coordinator(dataRoot);

        ReSyncPersistenceTopology.Registration registration = ReSyncPersistenceTopology.register(
            coordinator,
            dataRoot,
            List.of(
                ReSyncPersistenceTopology.requiredForRestore("resync.diagnostics", diagnosticsRoot, restoreSafe("resync.diagnostics", dataRoot, diagnosticsRoot)),
                ReSyncPersistenceTopology.unavailable("resync.network", networkRoot, "Network state is not rebound atomically")));

        PersistenceRootReadiness readiness = registration.readiness();
        assertFalse(registration.sealed());
        assertFalse(readiness.complete());
        assertEquals(List.of("resync.network"), readiness.requiredGaps().stream().map(PersistenceRootReadiness.Owner::owner).toList());
        assertEquals("Network state is not rebound atomically", readiness.owner("resync.network").reason());
        assertEquals(diagnosticsRoot, readiness.owner("resync.diagnostics").root());
        assertNotNull(readiness.payload().get("owners"));
        Map<String, Object> capability = ReSyncServer.persistenceCapability(coordinator, registration);
        Map<?, ?> capabilityReadiness = (Map<?, ?>) capability.get("rootReadiness");
        assertEquals(false, capabilityReadiness.get("complete"));
        assertEquals(List.of("resync.network"), capabilityReadiness.get("requiredGaps"));
    }

    @Test
    void preservesASpecificLocalUnavailableBlockerWithoutAnAggregateRootFallback() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("resync-specific-local-blocker"));
        Path networkRoot = Files.createDirectory(dataRoot.resolve("network"));
        Path worldPlayerData = Files.createDirectories(temporary.resolve("world-specific").resolve("playerdata"));
        String reason = "Network adapter has no restore-safe participant identity";
        ReSyncPersistenceCoordinator coordinator = coordinator(dataRoot);

        ReSyncPersistenceTopology.Registration registration = ReSyncPersistenceTopology.register(
            coordinator,
            dataRoot,
            List.of(ReSyncPersistenceTopology.unavailable("resync.network", networkRoot, reason)),
            List.of(PersistenceRootReadiness.UncoveredWriter.externalAffected(
                "resync.paper.playerdata", worldPlayerData, "Paper owns world player data outside the ReSync root",
                "Bukkit/Paper world persistence authority")));

        assertFalse(registration.sealed());
        assertEquals(List.of("resync.network"), registration.unavailableOwners());
        assertEquals(reason, registration.unavailableReasons().get("resync.network"));
        assertEquals(List.of("resync.network"), registration.readiness().requiredGaps().stream()
            .map(PersistenceRootReadiness.Owner::owner).toList());
        assertEquals(List.of("resync.paper.playerdata"), registration.readiness().externalAffectedWriters().stream()
            .map(PersistenceRootReadiness.UncoveredWriter::id).toList());
        assertTrue(registration.readiness().unavailableOwners().stream()
            .noneMatch(owner -> owner.owner().equals("resync.root")));
        assertTrue(!ReSyncServer.persistenceCapability(coordinator, registration).containsKey("snapshot"));
    }

    @Test
    void optionalMissingOwnersDoNotHideRequiredReadiness() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("resync-optional"));
        Path requiredRoot = Files.createDirectory(dataRoot.resolve("required"));
        ReSyncPersistenceCoordinator coordinator = coordinator(dataRoot);

        ReSyncPersistenceTopology.Registration registration = ReSyncPersistenceTopology.register(
            coordinator,
            dataRoot,
            List.of(
                ReSyncPersistenceTopology.requiredForRestore("required", requiredRoot, restoreSafe("required", dataRoot, requiredRoot)),
                ReSyncPersistenceTopology.optional("optional", dataRoot.resolve("optional"), null)));

        assertTrue(registration.sealed());
        assertTrue(registration.readiness().complete());
        assertEquals(List.of("optional"), registration.readiness().unavailableOwners().stream()
            .map(PersistenceRootReadiness.Owner::owner).toList());
        assertTrue(registration.readiness().requiredGaps().isEmpty());
    }

    @Test
    void boundedAssetOwnerRemainsVisibleWhileWholeRootIsUnavailable() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("resync-assets"));
        Path diagnosticsRoot = Files.createDirectory(dataRoot.resolve("diagnostics"));
        Path assetsRoot = Files.createDirectory(dataRoot.resolve("assets"));
        Path networkRoot = Files.createDirectory(dataRoot.resolve("network"));
        ReSyncPersistenceCoordinator coordinator = coordinator(dataRoot);

        ReSyncPersistenceTopology.Registration registration = ReSyncPersistenceTopology.register(
            coordinator,
            dataRoot,
            List.of(
                ReSyncPersistenceTopology.requiredForRestore(
                    "resync.diagnostics", diagnosticsRoot, restoreSafe("resync.diagnostics", dataRoot, diagnosticsRoot)),
                ReSyncPersistenceTopology.requiredForRestore(
                    "resync.assets", assetsRoot, restoreSafe("resync.assets", dataRoot, assetsRoot)),
                ReSyncPersistenceTopology.unavailable(
                    "resync.network", networkRoot, "Network persistence ownership is not proven")));

        assertFalse(registration.sealed());
        assertEquals(List.of("resync.assets", "resync.diagnostics"), registration.registeredOwners());
        assertEquals(List.of("resync.network"), registration.unavailableOwners());
        assertEquals(List.of("resync.network"), registration.readiness().requiredGaps().stream()
            .map(PersistenceRootReadiness.Owner::owner).toList());
        assertEquals(List.of("resync.assets", "resync.diagnostics"), coordinator.registeredParticipants().stream()
            .map(participant -> participant.owner()).sorted().toList());

        Map<String, Object> capability = ReSyncServer.persistenceCapability(coordinator, registration);
        assertEquals(List.of("resync.assets", "resync.diagnostics"), capability.get("registeredOwners"));
        assertEquals(List.of("resync.network"), capability.get("unavailableOwners"));
        assertEquals(false, capability.get("available"));
        assertTrue(!capability.containsKey("snapshot"));
        assertTrue(!capability.containsKey("restore"));
    }

    @Test
    void uncoveredWriterInventoryKeepsReadinessIncompleteAndHidesSnapshotRestore() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("resync-writer-inventory"));
        Path stateRoot = Files.createDirectory(dataRoot.resolve("state"));
        ReSyncPersistenceCoordinator coordinator = coordinator(dataRoot);
        List<PersistenceRootReadiness.UncoveredWriter> inventory = List.of(
            PersistenceRootReadiness.UncoveredWriter.of(
                "resync.runtime", dataRoot.resolve("runtime"), "Runtime state has no coordinated participant"),
            PersistenceRootReadiness.UncoveredWriter.of(
                "resync.network", dataRoot.resolve("network"), "Network state has no coordinated participant"));

        ReSyncPersistenceTopology.Registration registration = ReSyncPersistenceTopology.register(
            coordinator,
            dataRoot,
            List.of(ReSyncPersistenceTopology.requiredForRestore("state", stateRoot, restoreSafe("state", dataRoot, stateRoot))),
            inventory);

        assertFalse(registration.sealed());
        assertFalse(coordinator.sealed());
        assertFalse(registration.readiness().complete());
        assertEquals(List.of("resync.network", "resync.runtime"), registration.readiness().uncoveredWriterIds());
        assertEquals(List.of("resync.network", "resync.runtime"), registration.readiness().requiredGaps().stream()
            .map(PersistenceRootReadiness.Owner::owner).toList());
        Map<?, ?> readinessPayload = registration.readiness().payload();
        assertEquals(false, readinessPayload.get("complete"));
        assertEquals(false, readinessPayload.get("writerInventoryComplete"));
        assertEquals(List.of("resync.network", "resync.runtime"), readinessPayload.get("uncoveredWriterIds"));

        Map<String, Object> capability = ReSyncServer.persistenceCapability(coordinator, registration);
        assertEquals(false, capability.get("available"));
        assertTrue(!capability.containsKey("snapshot"));
        assertTrue(!capability.containsKey("restore"));
    }

    @Test
    void registeredDirectoryFamiliesAreRemovedFromTheReadinessInventory() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("resync-bounded-families"));
        PlayerTrackingManager playerTrackingManager = new PlayerTrackingManager(dataRoot);
        StructureLibrary structureLibrary = new StructureLibrary(dataRoot);
        ReSyncPersistenceCoordinator coordinator = coordinator(dataRoot);

        ReSyncPersistenceTopology.Registration registration = ReSyncPersistenceTopology.register(
            coordinator,
            dataRoot,
            List.of(
                ReSyncPersistenceTopology.requiredForRestore(
                    PlayerTrackingPersistenceParticipant.OWNER,
                    playerTrackingManager.getDossierDirectory(),
                    new PlayerTrackingPersistenceParticipant(dataRoot, playerTrackingManager)),
                ReSyncPersistenceTopology.requiredForRestore(
                    StructurePersistenceParticipant.OWNER,
                    structureLibrary.getStructuresDir(),
                    new StructurePersistenceParticipant(dataRoot, structureLibrary))),
            ReSyncUncoveredWriterInventory.forDataRoot(dataRoot));

        assertFalse(registration.sealed());
        assertEquals(List.of("resync.player-dossiers", "resync.structures"), registration.registeredOwners());
        assertEquals(List.of(
            "resync.catalog-publication-receipts",
            "resync.configuration",
            "resync.extensions",
            "resync.flow-regions",
            "resync.flow-variables",
            "resync.flow.files",
            "resync.install-identity",
            "resync.migration-reports",
            "resync.network",
            "resync.runtime.luckperms-backend",
            "resync.triggers",
            "resync.world-management"), registration.readiness().uncoveredWriterIds());
        assertFalse(registration.readiness().writerInventoryComplete());
        assertEquals(List.of(
            "resync.catalog-publication-receipts",
            "resync.configuration",
            "resync.extensions",
            "resync.flow-regions",
            "resync.flow-variables",
            "resync.flow.files",
            "resync.install-identity",
            "resync.migration-reports",
            "resync.network",
            "resync.triggers",
            "resync.world-management"), registration.readiness().requiredGaps().stream()
            .map(PersistenceRootReadiness.Owner::owner).toList());
    }

    @Test
    void registeredExtensionFamilyIsRemovedFromTheReadinessInventory() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("resync-extensions"));
        Path extensionRoot = Files.createDirectory(dataRoot.resolve("extensions"));
        ReSyncPersistenceCoordinator coordinator = coordinator(dataRoot);
        ExtensionPersistenceParticipant.Controller controller = new ExtensionPersistenceParticipant.Controller() {
            private Path activeRoot = extensionRoot;

            @Override
            public Path persistenceRoot() {
                return activeRoot;
            }

            @Override
            public void flushPersistence() {
            }

            @Override
            public void quiescePersistence() {
            }

            @Override
            public void resumePersistence() {
            }

            @Override
            public void rebindPersistence(Path activeRoot) {
                this.activeRoot = activeRoot;
            }

            @Override
            public void healthCheckPersistence() {
            }
        };
        ExtensionPersistenceParticipant participant = new ExtensionPersistenceParticipant(dataRoot, controller);

        ReSyncPersistenceTopology.Registration registration = ReSyncPersistenceTopology.register(
            coordinator,
            dataRoot,
            List.of(ReSyncPersistenceTopology.requiredForRestore(
                ExtensionPersistenceParticipant.OWNER,
                extensionRoot,
                participant)),
            ReSyncUncoveredWriterInventory.forDataRoot(dataRoot));

        assertFalse(registration.sealed());
        assertEquals(List.of(ExtensionPersistenceParticipant.OWNER), registration.registeredOwners());
        assertFalse(registration.readiness().uncoveredWriterIds().contains(ExtensionPersistenceParticipant.OWNER));
        assertEquals(List.of(
            "resync.catalog-publication-receipts",
            "resync.configuration",
            "resync.flow-regions",
            "resync.flow-variables",
            "resync.flow.files",
            "resync.install-identity",
            "resync.migration-reports",
            "resync.network",
            "resync.player-dossiers",
            "resync.runtime.luckperms-backend",
            "resync.structures",
            "resync.triggers",
            "resync.world-management"), registration.readiness().uncoveredWriterIds());
    }

    @Test
    void registeredRootFileFamiliesAreRemovedFromTheReadinessInventory() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("resync-file-family"));
        Path triggerFile = dataRoot.resolve("triggers.json");
        Files.writeString(triggerFile, "[]\n");
        TriggerRegistry triggerRegistry = new TriggerRegistry(triggerFile.toFile());
        ReSyncPersistenceCoordinator coordinator = coordinator(dataRoot);

        ReSyncPersistenceTopology.Registration registration = ReSyncPersistenceTopology.register(
            coordinator,
            dataRoot,
            List.of(ReSyncPersistenceTopology.requiredForRestore(
                TriggerPersistenceParticipant.OWNER,
                triggerRegistry.getPersistenceFile(),
                new TriggerPersistenceParticipant(dataRoot, triggerRegistry))),
            ReSyncUncoveredWriterInventory.forDataRoot(dataRoot));

        assertFalse(registration.sealed());
        assertEquals(List.of(TriggerPersistenceParticipant.OWNER), registration.registeredOwners());
        assertEquals(List.of(
            "resync.catalog-publication-receipts",
            "resync.configuration",
            "resync.extensions",
            "resync.flow-regions",
            "resync.flow-variables",
            "resync.flow.files",
            "resync.install-identity",
            "resync.migration-reports",
            "resync.network",
            "resync.player-dossiers",
            "resync.runtime.luckperms-backend",
            "resync.structures",
            "resync.world-management"), registration.readiness().uncoveredWriterIds());
        assertEquals(triggerFile, registration.readiness().owner(TriggerPersistenceParticipant.OWNER).root());
    }

    @Test
    void topologyFallbackPublishesAnExplicitRootGap() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("resync-fallback"));

        ReSyncPersistenceTopology.Registration registration = ReSyncPersistenceTopology.failClosed(
            dataRoot, "Topology registration failed before participant inventory was complete");

        assertFalse(registration.sealed());
        assertEquals(List.of("resync.root"), registration.unavailableOwners());
        assertEquals("Topology registration failed before participant inventory was complete",
            registration.unavailableReasons().get("resync.root"));
        assertEquals(List.of("resync.root"), registration.readiness().requiredGaps().stream()
            .map(PersistenceRootReadiness.Owner::owner).toList());
        assertFalse(registration.readiness().complete());
    }

    @Test
    void sealFailureAddsAnExplicitSealGap() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("resync-unowned"));
        Path requiredRoot = Files.createDirectory(dataRoot.resolve("required"));
        Files.writeString(dataRoot.resolve("unowned.txt"), "unowned");
        ReSyncPersistenceCoordinator coordinator = coordinator(dataRoot);

        ReSyncPersistenceTopology.Registration registration = ReSyncPersistenceTopology.register(
            coordinator,
            dataRoot,
            List.of(ReSyncPersistenceTopology.requiredForRestore("required", requiredRoot, restoreSafe("required", dataRoot, requiredRoot))));

        assertFalse(registration.sealed());
        assertFalse(registration.readiness().complete());
        assertEquals(List.of("resync.persistence.seal"), registration.unavailableOwners());
        assertEquals(registration.readiness().unavailableReasons(), registration.unavailableReasons());
        assertEquals(List.of("resync.persistence.seal"), registration.readiness().requiredGaps().stream()
            .map(PersistenceRootReadiness.Owner::owner).toList());
        assertEquals("Persistence Participant Rebind Failed: topology validation: No Persistence Participant Owns File: unowned.txt (previous participant roots restored)",
            registration.readiness().owner("resync.persistence.seal").reason());
        assertEquals("Persistence Participant Rebind Failed: topology validation: No Persistence Participant Owns File: unowned.txt (previous participant roots restored)",
            registration.unavailableReasons().get("resync.persistence.seal"));
        Map<?, ?> readinessPayload = registration.readiness().payload();
        assertEquals(registration.registeredOwners(), readinessPayload.get("registeredOwners"));
        assertEquals(registration.unavailableOwners(), readinessPayload.get("unavailableOwners"));
        assertEquals(registration.unavailableReasons(), readinessPayload.get("unavailableReasons"));
        Map<String, Object> capability = ReSyncServer.persistenceCapability(coordinator, registration);
        assertEquals(registration.unavailableOwners(), capability.get("unavailableOwners"));
        assertEquals(registration.unavailableReasons(), capability.get("unavailableReasons"));
    }

    @Test
    void shutdownRemovesPersistenceCapabilityAfterTheFenceBoundary() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("resync-shutdown"));
        Path stateRoot = Files.createDirectory(dataRoot.resolve("state"));
        ReSyncPersistenceCoordinator coordinator = coordinator(dataRoot);
        ReSyncPersistenceTopology.Registration registration = ReSyncPersistenceTopology.register(
            coordinator,
            dataRoot,
            List.of(ReSyncPersistenceTopology.requiredForRestore("state", stateRoot, restoreSafe("state", dataRoot, stateRoot))));

        assertTrue(registration.sealed());
        assertTrue((Boolean) ReSyncServer.persistenceCapability(coordinator, registration).get("available"));

        coordinator.close();

        Map<String, Object> capability = ReSyncServer.persistenceCapability(coordinator, registration);
        assertFalse((Boolean) capability.get("available"));
        assertFalse((Boolean) capability.get("restoreReady"));
        assertFalse(capability.containsKey("snapshot"));
        assertEquals("CLOSED", ((Map<?, ?>) capability.get("shutdown")).get("state"));
    }

    @Test
    void productionAuthorityExportRequiresCurrentRestoreReadyPersistence() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("resync-authority-export-readiness"));
        Path stateRoot = Files.createDirectory(dataRoot.resolve("state"));
        ReSyncPersistenceCoordinator coordinator = coordinator(dataRoot);
        ReSyncPersistenceTopology.Registration registration = ReSyncPersistenceTopology.register(
            coordinator,
            dataRoot,
            List.of(ReSyncPersistenceTopology.requiredForRestore("state", stateRoot, restoreSafe("state", dataRoot, stateRoot))));

        assertTrue(registration.sealed());
        assertTrue(coordinator.restoreReady());

        coordinator.beginShutdown();

        assertThrows(IOException.class,
            () -> ReSyncServer.requireProductionAuthorityExportReady(coordinator, registration));
    }

    private ReSyncPersistenceCoordinator coordinator(Path dataRoot) throws IOException {
        return new ReSyncPersistenceCoordinator(dataRoot, temporary.resolve("coordination"));
    }

    private RebindablePersistenceParticipant restoreSafe(String owner, Path dataRoot, Path initialRoot) {
        Path relative = dataRoot.toAbsolutePath().normalize().relativize(initialRoot.toAbsolutePath().normalize());
        return new RebindablePersistenceParticipant() {
            private Path activeRoot = initialRoot;

            @Override
            public String owner() {
                return owner;
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
                this.activeRoot = activeRoot.resolve(relative).normalize();
            }

            @Override
            public void healthCheck() {
            }
        };
    }
}
