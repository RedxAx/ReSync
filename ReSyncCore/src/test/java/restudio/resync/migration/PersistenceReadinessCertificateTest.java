package restudio.resync.migration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersistenceReadinessCertificateTest {
    @TempDir
    Path temporary;

    @Test
    void publishesOnlyTheCurrentFullyReadyGeneration() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("data"));
        ReSyncPersistenceCoordinator coordinator = new ReSyncPersistenceCoordinator(
            dataRoot, temporary.resolve("coordination"), new MigrationFence());
        try {
            coordinator.register(participant(dataRoot));
            coordinator.seal();
            assertTrue(coordinator.restoreReady());

            Path activeRoot = coordinator.activeDataRoot();
            PersistenceRootReadiness readiness = new PersistenceRootReadiness(List.of(
                PersistenceRootReadiness.Owner.registered("state", activeRoot, true)));
            long validationGeneration = coordinator.readinessGeneration();
            ReSyncPersistenceCoordinator.ReadinessProof proof = coordinator.validateRestoreReadiness(readiness)
                .orElseThrow();
            PersistenceReadinessCertificate certificate = coordinator.publishReadinessCertificate(proof).orElseThrow();

            assertEquals(activeRoot, certificate.activeRoot());
            assertEquals(readiness, certificate.readiness());
            assertEquals(proof.topologyEpoch(), certificate.topologyEpoch());
            assertEquals(proof.derivationGeneration(), certificate.derivationGeneration());
            assertTrue(coordinator.readinessCertificate().isPresent());
            assertSame(certificate, coordinator.currentReadinessCertificate(readiness).orElseThrow());
            assertTrue(coordinator.currentReadinessCertificate(new PersistenceRootReadiness(List.of(
                PersistenceRootReadiness.Owner.registered("state", activeRoot, true)))).isEmpty());

            coordinator.invalidateReadinessCertificate();
            assertTrue(coordinator.readinessCertificate().isEmpty());
            assertTrue(coordinator.publishReadinessCertificate(readiness, validationGeneration).isEmpty());
        } finally {
            coordinator.close();
        }
    }

    @Test
    void rawReadinessCannotBypassCoordinatorValidationProof() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("untrusted-readiness"));
        ReSyncPersistenceCoordinator coordinator = new ReSyncPersistenceCoordinator(
            dataRoot, temporary.resolve("untrusted-coordination"), new MigrationFence());
        try {
            coordinator.register(participant(dataRoot));
            coordinator.seal();
            coordinator.restoreReady();
            PersistenceRootReadiness wrongOwner = new PersistenceRootReadiness(List.of(
                PersistenceRootReadiness.Owner.registered("other", coordinator.activeDataRoot(), true)));

            assertFalse(coordinator.publishReadinessCertificate(wrongOwner, coordinator.readinessGeneration()).isPresent());
            assertTrue(coordinator.currentReadinessCertificate(wrongOwner).isEmpty());
        } finally {
            coordinator.close();
        }
    }

    @Test
    void reusesCurrentValidationProofUntilAForcedHealthProbeInvalidatesIt() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("memoized-readiness"));
        AtomicInteger healthChecks = new AtomicInteger();
        ReSyncPersistenceCoordinator coordinator = new ReSyncPersistenceCoordinator(
            dataRoot, temporary.resolve("memoized-coordination"), new MigrationFence());
        try {
            coordinator.register(participant(dataRoot, healthChecks));
            coordinator.seal();
            healthChecks.set(0);
            PersistenceRootReadiness readiness = new PersistenceRootReadiness(List.of(
                PersistenceRootReadiness.Owner.registered("state", coordinator.activeDataRoot(), true)));

            ReSyncPersistenceCoordinator.ReadinessProof first = coordinator.validateRestoreReadiness(readiness).orElseThrow();
            int validatedChecks = healthChecks.get();
            ReSyncPersistenceCoordinator.ReadinessProof second = coordinator.currentValidatedReadinessProof(readiness).orElseThrow();

            assertTrue(validatedChecks > 0);
            assertSame(first, second);
            assertEquals(validatedChecks, healthChecks.get());
            assertTrue(coordinator.restoreReady());
            int forcedChecks = healthChecks.get();
            assertTrue(forcedChecks > validatedChecks);
            assertTrue(coordinator.currentValidatedReadinessProof(readiness).isEmpty());
            assertTrue(coordinator.validateRestoreReadiness(readiness).isPresent());
            assertTrue(healthChecks.get() > forcedChecks);
        } finally {
            coordinator.close();
        }
    }

    @Test
    void derivesScopedStartupReadinessWithoutReplacingTheGlobalFences() throws Exception {
        Path dataRoot = temporary.resolve("scoped-derived");
        ReSyncPersistenceCoordinator coordinator = ReSyncPersistenceCoordinator.bootstrap(
            dataRoot, temporary.resolve("scoped-derived-coordination"));
        try {
            Path activeRoot = coordinator.prepareActiveRoot();
            AtomicInteger derivedHealthChecks = new AtomicInteger();
            coordinator.register(scopedParticipant("authoritative", activeRoot, PersistenceParticipantClassification.AUTHORITATIVE,
                new AtomicInteger()));
            coordinator.register(scopedParticipant("derived", activeRoot, PersistenceParticipantClassification.DERIVED_CACHE,
                derivedHealthChecks));
            PersistenceRootReadiness readiness = new PersistenceRootReadiness(List.of(
                PersistenceRootReadiness.Owner.registered("authoritative", activeRoot.resolve("authoritative"), true),
                PersistenceRootReadiness.Owner.unavailable("derived", activeRoot.resolve("derived"), false,
                    PersistenceParticipantClassification.DERIVED_CACHE, "Derived Output Is Pending")));

            ReSyncPersistenceCoordinator.ReadinessProof global = coordinator.seal(readiness);
            int healthChecksAtSeal = derivedHealthChecks.get();
            ReSyncPersistenceCoordinator.ReadinessProof derived = coordinator.deriveStartupReadiness(global, Set.of("derived"));

            assertEquals(global.generation(), derived.generation());
            assertEquals(global.topologyEpoch(), derived.topologyEpoch());
            assertEquals(global.authorityEpoch(), derived.authorityEpoch());
            assertEquals(global.derivationGeneration() + 1L, derived.derivationGeneration());
            assertEquals(PersistenceRootReadiness.State.REGISTERED, derived.readiness().owner("derived").state());
            assertTrue(derivedHealthChecks.get() > healthChecksAtSeal);
            assertSame(derived, coordinator.currentValidatedReadinessProof(derived.readiness()).orElseThrow());
            assertTrue(coordinator.currentValidatedReadinessProof(global.readiness()).isEmpty());
        } finally {
            coordinator.close();
        }
    }

    private PersistenceParticipant participant(Path root) {
        return participant(root, new AtomicInteger());
    }

    private PersistenceParticipant participant(Path root, AtomicInteger healthChecks) {
        return new RebindablePersistenceParticipant() {
            private Path activeRoot = root;

            @Override
            public String owner() {
                return "state";
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
            public void rebind(Path activeRoot) throws IOException {
                this.activeRoot = activeRoot;
            }

            @Override
            public void healthCheck() {
                healthChecks.incrementAndGet();
            }
        };
    }

    private PersistenceParticipant scopedParticipant(String owner, Path activeRoot,
                                                     PersistenceParticipantClassification classification,
                                                     AtomicInteger healthChecks) throws IOException {
        Path initialRoot = activeRoot.resolve(owner).toAbsolutePath().normalize();
        return new RebindablePersistenceParticipant() {
            private Path root = initialRoot;

            @Override
            public String owner() {
                return owner;
            }

            @Override
            public Path root() {
                return root;
            }

            @Override
            public Path rebindScope() {
                return activeRoot;
            }

            @Override
            public PersistenceParticipantClassification classification() {
                return classification;
            }

            @Override
            public boolean rootMayBeAbsent() {
                return true;
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
            public void rebind(Path activeRoot) throws IOException {
                root = activeRoot.resolve(owner).toAbsolutePath().normalize();
            }

            @Override
            public void healthCheck() {
                healthChecks.incrementAndGet();
            }
        };
    }
}
