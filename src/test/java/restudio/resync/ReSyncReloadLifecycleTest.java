package restudio.resync;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import restudio.resync.migration.MigrationFence;
import restudio.resync.network.paper.NetworkPersistenceDrainController;
import restudio.resync.network.paper.state.NetworkPlayerLocationPolicy;
import restudio.resync.network.paper.state.NetworkPlayerStateConfig;
import restudio.resync.network.paper.state.NetworkPlayerStateCoordinator;
import restudio.resync.network.paper.state.NetworkPlayerStateProfile;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReSyncReloadLifecycleTest {
    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void disableCancelsAnInProgressReloadBeforeReturningToNetworkShutdown() throws Exception {
        MockBukkit.mock();
        TestReSync plugin = MockBukkit.loadSimple(TestReSync.class);
        NetworkPlayerStateConfig config = new NetworkPlayerStateConfig(NetworkPlayerStateProfile.CUSTOM, "realm", "node", true, false, false, false, false, false, false, false, false, false, false, NetworkPlayerLocationPolicy.NEVER, Set.of());
        Path networkRoot = Files.createDirectories(plugin.getDataFolder().toPath().resolve("network"));
        NetworkPersistenceDrainController controller = new NetworkPersistenceDrainController(networkRoot, new MigrationFence(), Duration.ofSeconds(1));
        NetworkPlayerStateCoordinator previous = new NetworkPlayerStateCoordinator(plugin, config, controller);
        Method trackTask = NetworkPlayerStateCoordinator.class.getDeclaredMethod("trackTask", CompletableFuture.class);
        trackTask.setAccessible(true);
        CompletableFuture<Void> pendingTask = new CompletableFuture<>();
        trackTask.invoke(previous, pendingTask);
        previous.prepareForShutdown();
        CompletableFuture<Void> finalizer = previous.shutdownAfterPreparation(Duration.ofSeconds(1)).toCompletableFuture();
        NetworkPersistenceDrainController.ReplacementLease lease = controller.beginReplacement("network-player-state-reload");
        CompletableFuture<NetworkPlayerStateConfig> result = new CompletableFuture<>();

        Class<?> workType = java.util.Arrays.stream(ReSync.class.getDeclaredClasses())
            .filter(type -> type.getSimpleName().equals("NetworkStateReloadWork"))
            .findFirst().orElseThrow();
        Constructor<?> constructor = workType.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        Object work = constructor.newInstance(config, previous, null, result, lease, finalizer, false);
        Field workField = ReSync.class.getDeclaredField("networkStateReloadWork");
        workField.setAccessible(true);
        workField.set(plugin, work);
        Field inProgressField = ReSync.class.getDeclaredField("networkStateReloadInProgress");
        inProgressField.setAccessible(true);
        AtomicBoolean inProgress = (AtomicBoolean) inProgressField.get(plugin);
        inProgress.set(true);
        Field stoppingField = ReSync.class.getDeclaredField("lifecycleStopping");
        stoppingField.setAccessible(true);
        stoppingField.set(plugin, true);
        MockBukkit.getMock().getPluginManager().disablePlugin(plugin);
        Field monitorField = ReSync.class.getDeclaredField("networkLifecycleMonitor");
        monitorField.setAccessible(true);
        Object monitor = monitorField.get(plugin);
        Method prepare = ReSync.class.getDeclaredMethod("prepareNetworkStateReloadForDisable");
        prepare.setAccessible(true);

        boolean ready;
        synchronized (monitor) {
            ready = (boolean) prepare.invoke(plugin);
        }

        assertFalse(ready);
        assertFalse(inProgress.get());
        assertTrue(finalizer.isCompletedExceptionally());
        assertTrue(lease.active());
        assertTrue(result.isCompletedExceptionally());
        assertTrue(controller.componentCount() > 0);
        assertTrue(controller.producerCount() > 0);

        pendingTask.complete(null);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (lease.active() && System.nanoTime() < deadline) {
            Thread.yield();
        }
        assertFalse(lease.active());
        assertTrue(MockBukkit.getMock().getScheduler().getPendingTasks().isEmpty());
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
}
