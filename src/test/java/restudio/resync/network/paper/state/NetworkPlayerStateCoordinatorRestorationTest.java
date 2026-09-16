package restudio.resync.network.paper.state;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.bukkit.Bukkit;
import restudio.resync.ReSync;
import restudio.resync.migration.MigrationFence;
import restudio.resync.network.paper.NetworkPersistenceDrainController;
import restudio.resync.network.NetworkStateReconciliationTask;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkPlayerStateCoordinatorRestorationTest {
    @TempDir
    Path temporary;

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void restorationLeaseStaysHeldUntilTheAsyncCallbackCompletes() throws Exception {
        DrainContext context = quiescingController();
        NetworkPersistenceDrainController controller = context.controller();
        CompletableFuture<Void> restoration = new CompletableFuture<>();
        CompletableFuture<Void> tracked = NetworkPlayerStateCoordinator.trackRestorationDuringDrain(controller, "restoration", restoration).toCompletableFuture();

        assertEquals(2, controller.activeLeaseCount());
        assertTrue(context.fence().tryAcquireMigration(Duration.ZERO).isEmpty());
        restoration.complete(null);
        tracked.get(1, TimeUnit.SECONDS);
        assertEquals(1, controller.activeLeaseCount());

        finishQuiesce(context);
    }

    @Test
    void restorationFailurePropagatesAndStillReleasesItsLease() throws Exception {
        DrainContext context = quiescingController();
        NetworkPersistenceDrainController controller = context.controller();
        CompletableFuture<Void> restoration = new CompletableFuture<>();
        CompletableFuture<Void> tracked = NetworkPlayerStateCoordinator.trackRestorationDuringDrain(controller, "restoration", restoration).toCompletableFuture();
        restoration.completeExceptionally(new IllegalStateException("restore failed"));

        CompletionException failure = assertThrows(CompletionException.class, () -> tracked.join());
        assertEquals("restore failed", failure.getCause().getMessage());
        assertEquals(1, controller.activeLeaseCount());

        finishQuiesce(context);
    }

    @Test
    void shutdownDoesNotBlockTheServerThreadForAPlayerSchedulerTask() throws Exception {
        MockBukkit.mock();
        TestReSync plugin = MockBukkit.loadSimple(TestReSync.class);
        NetworkPlayerStateConfig config = new NetworkPlayerStateConfig(NetworkPlayerStateProfile.CUSTOM, "realm", "node", true, false, false, false, false, false, false, false, false, false, false, NetworkPlayerLocationPolicy.NEVER, Set.of());
        Path networkRoot = Files.createDirectories(plugin.getDataFolder().toPath().resolve("network"));
        NetworkPersistenceDrainController controller = new NetworkPersistenceDrainController(networkRoot, new MigrationFence(), Duration.ofSeconds(1));
        NetworkPlayerStateCoordinator coordinator = new NetworkPlayerStateCoordinator(plugin, config, controller);
        CompletableFuture<Void> delayedTask = new CompletableFuture<>();
        coordinator.trackTask(delayedTask);
        NetworkPersistenceDrainController.Lease lease = controller.acquire("delayed-server-task");
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            delayedTask.complete(null);
            lease.close();
        }, 1L);

        long started = System.nanoTime();
        CompletableFuture<Void> shutdown = coordinator.shutdown(Duration.ofMillis(100)).toCompletableFuture();
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertTrue(elapsedMillis < 1000);
        assertThrows(ExecutionException.class, () -> shutdown.get(1, TimeUnit.SECONDS));
        assertEquals(1, controller.componentCount());
        assertEquals(1, controller.producerCount());

        MockBukkit.getMock().getScheduler().performOneTick();
        coordinator.shutdown(Duration.ofSeconds(1)).toCompletableFuture().get(1, TimeUnit.SECONDS);
        assertEquals(0, controller.componentCount());
        assertEquals(0, controller.producerCount());
    }

    @Test
    void replacementCancellationCompletesBeforeDisableAndRejectsLateFinalization() throws Exception {
        MockBukkit.mock();
        TestReSync plugin = MockBukkit.loadSimple(TestReSync.class);
        NetworkPlayerStateConfig config = new NetworkPlayerStateConfig(NetworkPlayerStateProfile.CUSTOM, "realm", "node", true, false, false, false, false, false, false, false, false, false, false, NetworkPlayerLocationPolicy.NEVER, Set.of());
        Path networkRoot = Files.createDirectories(plugin.getDataFolder().toPath().resolve("network"));
        NetworkPersistenceDrainController controller = new NetworkPersistenceDrainController(networkRoot, new MigrationFence(), Duration.ofSeconds(1));
        NetworkPlayerStateCoordinator coordinator = new NetworkPlayerStateCoordinator(plugin, config, controller);
        CompletableFuture<Void> pendingTask = new CompletableFuture<>();
        coordinator.trackTask(pendingTask);
        coordinator.prepareForShutdown();
        CompletionStage<Void> finalizer = coordinator.shutdownAfterPreparation(Duration.ofSeconds(1));

        CompletionStage<Void> physical = coordinator.cancelShutdownForReplacement(new IllegalStateException("reload cancelled"));

        assertThrows(ExecutionException.class, () -> finalizer.toCompletableFuture().get(1, TimeUnit.SECONDS));
        assertTrue(!physical.toCompletableFuture().isDone());
        assertEquals(1, controller.componentCount());
        assertEquals(1, controller.producerCount());

        pendingTask.complete(null);
        physical.toCompletableFuture().get(1, TimeUnit.SECONDS);
        coordinator.reactivateAfterReplacementFailure();
        coordinator.start();
        coordinator.prepareForShutdown();
        coordinator.shutdownAfterPreparation(Duration.ofSeconds(1)).toCompletableFuture().get(1, TimeUnit.SECONDS);
        assertEquals(0, controller.componentCount());
        assertEquals(0, controller.producerCount());
    }

    @Test
    void retiredPaperSchedulerCannotAdmitNewReconciliationWork() throws Exception {
        MockBukkit.mock();
        TestReSync plugin = MockBukkit.loadSimple(TestReSync.class);
        NetworkPlayerStateConfig config = new NetworkPlayerStateConfig(NetworkPlayerStateProfile.CUSTOM, "realm", "node", true, false, false, false, false, false, false, false, false, false, false, NetworkPlayerLocationPolicy.NEVER, Set.of());
        Path networkRoot = Files.createDirectories(plugin.getDataFolder().toPath().resolve("network"));
        NetworkPersistenceDrainController controller = new NetworkPersistenceDrainController(networkRoot, new MigrationFence(), Duration.ofSeconds(1));
        NetworkPlayerStateReconciler reconciler = new NetworkPlayerStateReconciler(plugin, controller);

        MockBukkit.getMock().getPluginManager().disablePlugin(plugin);
        reconciler.prepareForShutdown();
        CompletionException failure = assertThrows(CompletionException.class, () -> reconciler.reconcile(
            new NetworkStateReconciliationTask("retired-scheduler", Set.of(UUID.randomUUID()), Set.of("inventory"))).join());

        assertTrue(failure.getCause().getMessage().contains("shut down"));
        assertTrue(MockBukkit.getMock().getScheduler().getPendingTasks().isEmpty());
        reconciler.prepareForShutdown();
        reconciler.finalizeShutdown();
        controller.closePersistence();
    }

    public static class TestReSync extends ReSync {
        @Override
        public void onEnable() {
        }

        @Override
        public void onDisable() {
        }

        @Override
        public restudio.resync.network.paper.ReSyncNetworkAgent getNetworkAgent() {
            return null;
        }
    }

    private DrainContext quiescingController() throws Exception {
        Path source = Files.createDirectories(temporary.resolve("network"));
        MigrationFence fence = new MigrationFence();
        NetworkPersistenceDrainController controller = new NetworkPersistenceDrainController(source, fence, Duration.ofSeconds(1));
        NetworkPersistenceDrainController.Lease blocker = controller.acquire("migration-blocker");
        CompletableFuture<Void> quiesce = CompletableFuture.runAsync(() -> {
            try {
                controller.quiescePersistence(Duration.ofSeconds(1));
            } catch (IOException exception) {
                throw new CompletionException(exception);
            }
        });
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (controller.state() != NetworkPersistenceDrainController.State.QUIESCING && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertEquals(NetworkPersistenceDrainController.State.QUIESCING, controller.state());
        return new DrainContext(controller, fence, blocker, quiesce);
    }

    private void finishQuiesce(DrainContext context) throws Exception {
        assertTrue(context.controller().activeLeaseCount() > 0);
        context.blocker().close();
        context.quiesce().get(1, TimeUnit.SECONDS);
        assertEquals(NetworkPersistenceDrainController.State.QUIESCED, context.controller().state());
    }

    private record DrainContext(NetworkPersistenceDrainController controller, MigrationFence fence, NetworkPersistenceDrainController.Lease blocker, CompletableFuture<Void> quiesce) {
    }
}
