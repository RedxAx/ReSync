package restudio.resync.migration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReSyncPersistenceReadinessObservationTest {
    @TempDir
    Path temporary;

    @Test
    void observesAuthorityWithoutWaitingForTheCoordinatorMonitor() throws Exception {
        ReSyncPersistenceCoordinator coordinator = coordinator("nonblocking");
        ReSyncPersistenceCoordinator.ReadinessObservation observation = coordinator.readinessObservation();
        CountDownLatch monitorHeld = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<?> holder = executor.submit(() -> {
            synchronized (coordinator) {
                monitorHeld.countDown();
                await(release);
            }
        });
        try {
            assertTrue(monitorHeld.await(5, TimeUnit.SECONDS));

            assertTimeoutPreemptively(Duration.ofSeconds(1), () -> {
                assertTrue(coordinator.isCurrent(observation));
                assertEquals(1L, observation.authorityEpoch());
                assertEquals(1L, coordinator.authorityEpoch());
                assertTrue(coordinator.isCurrent(coordinator.readinessObservation()));
            });
        } finally {
            release.countDown();
            holder.get();
            executor.shutdownNow();
            coordinator.close();
        }
    }

    @Test
    void rejectsCertificateReplacementEvenWhenReadinessIdentityReturns() throws Exception {
        ReSyncPersistenceCoordinator coordinator = coordinator("certificate");
        try {
            PersistenceRootReadiness readiness = readiness(coordinator);
            ReSyncPersistenceCoordinator.ReadinessProof firstProof = coordinator.validateRestoreReadiness(readiness)
                .orElseThrow();
            PersistenceReadinessCertificate firstCertificate = coordinator.publishReadinessCertificate(firstProof)
                .orElseThrow();
            ReSyncPersistenceCoordinator.ReadinessObservation first = coordinator.readinessObservation();

            coordinator.invalidateReadinessCertificate();
            ReSyncPersistenceCoordinator.ReadinessProof secondProof = coordinator.validateRestoreReadiness(readiness)
                .orElseThrow();
            PersistenceReadinessCertificate secondCertificate = coordinator.publishReadinessCertificate(secondProof)
                .orElseThrow();
            ReSyncPersistenceCoordinator.ReadinessObservation second = coordinator.readinessObservation();

            assertFalse(firstCertificate == secondCertificate);
            assertFalse(coordinator.isCurrent(first));
            assertTrue(coordinator.isCurrent(second));
        } finally {
            coordinator.close();
        }
    }

    @Test
    void rejectsObservationsAcrossAuthorityRebindAndShutdown() throws Exception {
        ReSyncPersistenceCoordinator coordinator = coordinator("lifecycle");
        try {
            ReSyncPersistenceCoordinator.ReadinessObservation initial = coordinator.readinessObservation();
            long initialEpoch = initial.authorityEpoch();
            Path replacement = Files.createDirectory(temporary.resolve("replacement-root"));
            Method bind = ReSyncPersistenceCoordinator.class.getDeclaredMethod("bindAuthorityEpoch", Path.class);
            bind.setAccessible(true);
            bind.invoke(coordinator, replacement);

            ReSyncPersistenceCoordinator.ReadinessObservation rebound = coordinator.readinessObservation();
            assertFalse(coordinator.isCurrent(initial));
            assertEquals(initialEpoch + 1L, rebound.authorityEpoch());
            assertTrue(coordinator.isCurrent(rebound));

            coordinator.beginShutdown();

            assertFalse(coordinator.isCurrent(rebound));
            assertTrue(coordinator.isCurrent(coordinator.readinessObservation()));
        } finally {
            coordinator.close();
        }
    }

    private ReSyncPersistenceCoordinator coordinator(String name) throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve(name + "-data"));
        ReSyncPersistenceCoordinator coordinator = new ReSyncPersistenceCoordinator(
            dataRoot, temporary.resolve(name + "-coordination"), new MigrationFence());
        coordinator.register(participant(dataRoot));
        coordinator.seal();
        assertTrue(coordinator.restoreReady());
        return coordinator;
    }

    private PersistenceRootReadiness readiness(ReSyncPersistenceCoordinator coordinator) throws IOException {
        return new PersistenceRootReadiness(List.of(
            PersistenceRootReadiness.Owner.registered("state", coordinator.activeDataRoot(), true)));
    }

    private RebindablePersistenceParticipant participant(Path initialRoot) {
        return new RebindablePersistenceParticipant() {
            private Path root = initialRoot;

            @Override
            public String owner() {
                return "state";
            }

            @Override
            public Path root() {
                return root;
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
                root = activeRoot;
            }

            @Override
            public void healthCheck() {
            }
        };
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
