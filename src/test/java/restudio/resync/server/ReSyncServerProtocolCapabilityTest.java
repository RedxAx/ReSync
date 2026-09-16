package restudio.resync.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.migration.PersistenceParticipant;
import restudio.resync.migration.PersistenceRootReadiness;
import restudio.resync.migration.RebindablePersistenceParticipant;
import restudio.resync.migration.ReSyncPersistenceCoordinator;
import restudio.resync.migration.Snapshot;
import restudio.resync.migration.SnapshotMetadata;
import restudio.resync.restore.RestoreCompatibilityPolicy;
import restudio.resync.restore.RestoreRequest;
import restudio.resync.server.ReSyncPersistenceTopology.Registration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReSyncServerProtocolCapabilityTest {
    @TempDir
    Path temporary;

    @Test
    void handshakePublishesCanonicalServerIdentityBeforeAuthorityCapabilities() {
        ServerId serverId = ServerId.of(UUID.fromString("11111111-1111-4111-8111-111111111111"));

        Map<String, Object> capabilities = ReSyncServer.identityFirstCapabilities(serverId, Map.of("authorityEpoch", 7L));

        assertEquals(List.of("serverId", "authorityEpoch"), List.copyOf(capabilities.keySet()));
        assertEquals(serverId.canonicalText(), capabilities.get("serverId"));
    }

    @Test
    void handshakeFailsClosedWithoutCanonicalServerIdentity() {
        assertThrows(IllegalArgumentException.class,
            () -> ReSyncServer.identityFirstCapabilities(null, Map.of("authorityEpoch", 7L)));
    }

    @Test
    void publishesEnvelopeAuthorityOnlyWhenDispatchIsAvailable() {
        Map<String, Object> supported = ReSyncServer.protocolEnvelopeCapability(true);

        assertEquals(true, supported.get("supported"));
        assertEquals("PROTOCOL_ENVELOPE", supported.get("messageType"));
        assertEquals("MESSAGE_PROTOCOL_ENVELOPE", supported.get("id"));
        assertEquals("generic-envelope", supported.get("authority"));
    }

    @Test
    void failsClosedWhenDispatchIsUnavailable() {
        assertTrue(ReSyncServer.protocolEnvelopeCapability(false).isEmpty());
    }

    @Test
    void advertisesMutationAuthorityOnlyWhenDurableAuthorityIsInitialized() {
        Map<String, Object> unavailable = ReSyncServer.protocolEnvelopeCapability(true, false);
        Map<?, ?> unavailableAuthority = (Map<?, ?>) unavailable.get("mutationAuthority");
        assertEquals(false, unavailableAuthority.get("supported"));
        assertEquals(false, unavailableAuthority.get("durable"));
        assertEquals("blocked", unavailableAuthority.get("legacyResourceMutations"));

        Map<String, Object> available = ReSyncServer.protocolEnvelopeCapability(true, true);
        Map<?, ?> mutationAuthority = (Map<?, ?>) available.get("mutationAuthority");
        assertEquals(true, mutationAuthority.get("supported"));
        assertEquals(true, mutationAuthority.get("durable"));
        assertEquals("blocked", mutationAuthority.get("legacyResourceMutations"));
    }

    @Test
    void doesNotAdvertiseSnapshotOrRestoreBeforeParticipantSealing() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("resync"));
        ReSyncPersistenceCoordinator persistence = new ReSyncPersistenceCoordinator(dataRoot, temporary.resolve("coordination"));

        Map<String, Object> capability = ReSyncServer.persistenceCapability(persistence);

        assertEquals(false, capability.get("available"));
        assertTrue(!capability.containsKey("snapshot"));
        assertTrue(!capability.containsKey("restore"));
    }

    @Test
    void doesNotAdvertiseRestoreWhenARequiredParticipantIsUnavailable() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("resync-unavailable"));
        ReSyncPersistenceCoordinator persistence = new ReSyncPersistenceCoordinator(dataRoot, temporary.resolve("coordination-unavailable"));
        Registration registration = ReSyncPersistenceTopology.register(
            persistence,
            dataRoot,
            List.of(ReSyncPersistenceTopology.unavailable(
                "runtime.state", dataRoot.resolve("missing"), "Runtime State Is Unavailable")));

        Map<String, Object> capability = ReSyncServer.persistenceCapability(persistence, registration);

        assertEquals(false, registration.sealed());
        assertEquals(false, capability.get("available"));
        assertTrue(!capability.containsKey("snapshot"));
        assertTrue(!capability.containsKey("restore"));
    }

    @Test
    void doesNotAdvertiseRestoreForASealedCoordinatorWithoutAnActiveRebindableRoot() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("resync-sealed"));
        ReSyncPersistenceCoordinator persistence = new ReSyncPersistenceCoordinator(dataRoot, temporary.resolve("coordination-sealed"));
        persistence.register(new PersistenceParticipant() {
            @Override
            public String owner() {
                return "snapshot-only";
            }

            @Override
            public Path root() {
                return dataRoot;
            }
        });
        persistence.seal();

        Map<String, Object> capability = ReSyncServer.persistenceCapability(persistence);

        assertEquals(true, capability.get("available"));
        assertEquals(true, capability.get("snapshot"));
        assertEquals(false, capability.get("restoreReady"));
        assertTrue(!capability.containsKey("restore"));
    }

    @Test
    void fullyAvailableProductionTopologyExposesSnapshotAndRestoreWithExternalAffectedWriters() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("resync-production"));
        Path stateRoot = Files.createDirectory(dataRoot.resolve("state"));
        Files.writeString(stateRoot.resolve("state.txt"), "state");
        Path worldPlayerData = Files.createDirectories(temporary.resolve("paper-world").resolve("playerdata"));
        ReSyncPersistenceCoordinator persistence = new ReSyncPersistenceCoordinator(
            dataRoot, temporary.resolve("coordination-production"));
        RebindablePersistenceParticipant participant = restoreSafeParticipant("production.state", dataRoot, stateRoot);
        Registration registration = ReSyncPersistenceTopology.register(
            persistence,
            dataRoot,
            List.of(ReSyncPersistenceTopology.requiredForRestore("production.state", stateRoot, participant)),
            List.of(PersistenceRootReadiness.UncoveredWriter.externalAffected(
                "resync.paper.playerdata", worldPlayerData, "Paper owns world player data outside the ReSync root",
                "Bukkit/Paper world persistence authority")));

        assertTrue(registration.sealed());
        assertTrue(registration.readiness().complete());
        assertEquals(List.of("resync.paper.playerdata"), registration.readiness().externalAffectedWriters().stream()
            .map(PersistenceRootReadiness.UncoveredWriter::id).toList());
        Snapshot snapshot = persistence.createSnapshot(
            persistence.snapshotRoot().resolve("production"),
            new SnapshotMetadata(1, "production", Instant.EPOCH, "resync-test", "a".repeat(64), Map.of()));
        assertTrue(snapshot.verified());
        persistence.restore(new RestoreRequest(
            snapshot,
            new RestoreCompatibilityPolicy(1, 1, Set.of("resync-test"), new CatalogVersion(1, 0),
                new CatalogBinding(1, "a".repeat(64), "a".repeat(64)), Map.of()),
            persistence.coordinationRoot().resolve("production-current"),
            new SnapshotMetadata(1, "production-current", Instant.EPOCH, "resync-test", "a".repeat(64), Map.of()),
            persistence.coordinationRoot().resolve("production-restore"),
            persistence.coordinationRoot().resolve("production-journal")));

        assertTrue(persistence.restoreReady());
        Map<String, Object> capability = ReSyncServer.persistenceCapability(persistence, registration);
        assertEquals(true, capability.get("available"));
        assertEquals(true, capability.get("snapshot"));
        assertEquals(true, capability.get("restoreReady"));
        assertEquals(true, capability.get("restore"));
        Map<?, ?> readiness = (Map<?, ?>) capability.get("rootReadiness");
        assertEquals(List.of("resync.paper.playerdata"), readiness.get("externalAffectedWriters"));
        assertEquals(List.of(), readiness.get("localUncoveredWriterIds"));
    }

    private RebindablePersistenceParticipant restoreSafeParticipant(String owner, Path scopeRoot, Path initialRoot) {
        Path relativeRoot = scopeRoot.toAbsolutePath().normalize()
            .relativize(initialRoot.toAbsolutePath().normalize());
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
                this.activeRoot = activeRoot.resolve(relativeRoot).normalize();
            }

            @Override
            public void healthCheck() {
            }
        };
    }
}
