package restudio.resync;

import org.bukkit.Bukkit;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import restudio.resync.bridge.ReSyncPluginMessageBridge;
import restudio.resync.network.paper.NetworkPersistenceDrainController;
import restudio.resync.network.paper.ReSyncNetworkAgent;
import restudio.resync.network.paper.ReSyncNetworkAgentConfig;
import restudio.resync.network.paper.state.NetworkPlayerLocationPolicy;
import restudio.resync.network.paper.state.NetworkPlayerStateConfig;
import restudio.resync.network.paper.state.NetworkPlayerStateCoordinator;
import restudio.resync.network.paper.state.NetworkPlayerStateProfile;
import restudio.resync.selection.InteractiveSelectionManager;
import restudio.resync.migration.MigrationFence;
import restudio.resync.server.ReSyncConfig;
import restudio.resync.server.ReSyncWebSocketListener;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReSyncDisableLifecycleTest {
    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void inheritedDisableCleansPluginResourcesBeforePhysicalReloadAndClearsReferencesAfterLateRetry() throws Exception {
        MockBukkit.mock();
        LifecycleReSync plugin = MockBukkit.loadSimple(LifecycleReSync.class);
        activateTestInstance(plugin);
        Path dataRoot = plugin.getDataFolder().toPath();
        Files.createDirectories(dataRoot.resolve("network"));
        ReSyncNetworkAgent agent = new ReSyncNetworkAgent(plugin, new ReSyncNetworkAgentConfig(
            false, ReSyncNetworkAgentConfig.ChatPolicy.disabled(), ReSyncNetworkAgentConfig.ResourcePolicy.disabled(),
            List.of(), "network-1", "node-1", "Test", "", "", "", 0,
            1_048_576, 500_000, 100, 100, ReSyncNetworkAgentConfig.Tls.disabled(),
            dataRoot.resolve("network/node.credential")), new MigrationFence());
        NetworkPlayerStateConfig stateConfig = new NetworkPlayerStateConfig(NetworkPlayerStateProfile.CUSTOM,
            "realm", "node-1", true, false, false, false, false, false, false, false, false, false, false,
            NetworkPlayerLocationPolicy.NEVER, Set.of());
        NetworkPlayerStateCoordinator previous = new NetworkPlayerStateCoordinator(plugin, stateConfig,
            agent.persistenceDrain());
        CompletableFuture<Void> pendingTask = new CompletableFuture<>();
        Method trackTask = NetworkPlayerStateCoordinator.class.getDeclaredMethod("trackTask", CompletableFuture.class);
        trackTask.setAccessible(true);
        trackTask.invoke(previous, pendingTask);
        previous.prepareForShutdown();
        CompletableFuture<Void> finalizer = previous.shutdownAfterPreparation(Duration.ofSeconds(1)).toCompletableFuture();
        NetworkPersistenceDrainController.ReplacementLease lease = agent.persistenceDrain()
            .beginReplacement("network-player-state-reload");
        CompletableFuture<NetworkPlayerStateConfig> reloadResult = new CompletableFuture<>();

        setField(plugin, "networkAgent", agent);
        setField(plugin, "networkPlayerStateCoordinator", previous);
        agent.setTransferHandler(previous, false);
        setField(plugin, "pluginMessageBridge", new CountingBridge(plugin));
        CountingBridge bridge = (CountingBridge) getField(plugin, "pluginMessageBridge");
        bridge.register();
        CountingSelection selection = new CountingSelection(plugin);
        selection.start();
        setField(plugin, "interactiveSelectionManager", selection);
        CountingExpansion expansion = new CountingExpansion();
        setField(plugin, "placeholderExpansion", expansion);
        CountingWebSocketListener webSocket = new CountingWebSocketListener();
        setField(plugin, "wsServer", webSocket);
        ((AtomicBoolean) getField(plugin, "networkStateReloadInProgress")).set(true);
        setField(plugin, "networkStateReloadAttempt", reloadResult);

        Class<?> workType = Arrays.stream(ReSync.class.getDeclaredClasses())
            .filter(type -> type.getSimpleName().equals("NetworkStateReloadWork"))
            .findFirst().orElseThrow();
        Constructor<?> constructor = Arrays.stream(workType.getDeclaredConstructors())
            .filter(value -> value.getParameterCount() == 7)
            .findFirst().orElseThrow();
        constructor.setAccessible(true);
        Object work = constructor.newInstance(stateConfig, previous, null, reloadResult, lease, finalizer, false);
        setField(plugin, "networkStateReloadWork", work);

        plugin.onDisable();

        assertEquals(1, bridge.unregisterCount.get());
        assertEquals(1, selection.shutdownCount.get());
        assertEquals(1, expansion.unregisterCount.get());
        assertEquals(1, webSocket.stopCount.get());
        assertTrue(finalizer.isCompletedExceptionally());
        assertTrue(lease.active());
        assertNull(plugin.getReSyncServer());
        assertTrue(plugin.getNetworkAgent() != null);

        plugin.onDisable();
        assertEquals(1, bridge.unregisterCount.get());
        assertEquals(1, selection.shutdownCount.get());
        assertEquals(1, expansion.unregisterCount.get());
        assertEquals(1, webSocket.stopCount.get());

        pendingTask.complete(null);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (lease.active() && System.nanoTime() < deadline) {
            Thread.yield();
        }

        assertFalse(lease.active());
        assertNull(plugin.getReSyncServer());
        assertNull(plugin.getNetworkAgent());
        assertNull(plugin.getInteractiveSelectionManager());
        assertTrue(Bukkit.getScheduler().getPendingTasks().isEmpty());
    }

    @Test
    void cleanupAttemptsEveryResourceAndRetriesOnlyTheFailedResources() throws Exception {
        MockBukkit.mock();
        LifecycleReSync plugin = MockBukkit.loadSimple(LifecycleReSync.class);
        CountingBridge bridge = new CountingBridge(plugin, true);
        bridge.register();
        CountingSelection selection = new CountingSelection(plugin, true);
        selection.start();
        CountingExpansion expansion = new CountingExpansion();
        CountingWebSocketListener webSocket = new CountingWebSocketListener();
        setField(plugin, "pluginMessageBridge", bridge);
        setField(plugin, "interactiveSelectionManager", selection);
        setField(plugin, "placeholderExpansion", expansion);
        setField(plugin, "wsServer", webSocket);

        plugin.onDisable();

        assertEquals(2, bridge.unregisterCount.get());
        assertEquals(2, selection.shutdownCount.get());
        assertEquals(1, expansion.unregisterCount.get());
        assertEquals(1, webSocket.stopCount.get());
        assertNull(getField(plugin, "pluginMessageBridge"));
        assertNull(getField(plugin, "interactiveSelectionManager"));

        plugin.onDisable();

        assertEquals(2, bridge.unregisterCount.get());
        assertEquals(2, selection.shutdownCount.get());
        assertEquals(1, expansion.unregisterCount.get());
        assertEquals(1, webSocket.stopCount.get());
        assertNull(getField(plugin, "pluginMessageBridge"));
        assertNull(getField(plugin, "interactiveSelectionManager"));
        assertNull(getField(plugin, "placeholderExpansion"));
        assertNull(getField(plugin, "wsServer"));
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = ReSync.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static void activateTestInstance(ReSync plugin) throws Exception {
        Field field = ReSync.class.getDeclaredField("instance");
        field.setAccessible(true);
        field.set(null, plugin);
    }

    private static Object getField(Object target, String name) throws Exception {
        Field field = ReSync.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    public static class LifecycleReSync extends ReSync {
        @Override
        public void onEnable() {
        }
    }

    private static final class CountingBridge extends ReSyncPluginMessageBridge {
        private final AtomicInteger unregisterCount = new AtomicInteger();
        private final boolean failFirst;

        private CountingBridge(ReSync plugin) {
            this(plugin, false);
        }

        private CountingBridge(ReSync plugin, boolean failFirst) {
            super(plugin);
            this.failFirst = failFirst;
        }

        @Override
        public void unregister() {
            int count = unregisterCount.incrementAndGet();
            if (failFirst && count == 1) {
                throw new IllegalStateException("injected bridge cleanup failure");
            }
            super.unregister();
        }
    }

    private static final class CountingSelection extends InteractiveSelectionManager {
        private final AtomicInteger shutdownCount = new AtomicInteger();
        private final boolean failFirst;

        private CountingSelection(ReSync plugin) {
            this(plugin, false);
        }

        private CountingSelection(ReSync plugin, boolean failFirst) {
            super(plugin);
            this.failFirst = failFirst;
        }

        @Override
        public void shutdown() {
            int count = shutdownCount.incrementAndGet();
            if (failFirst && count == 1) {
                throw new IllegalStateException("injected selection cleanup failure");
            }
            super.shutdown();
        }
    }

    public static final class CountingExpansion {
        private final AtomicInteger unregisterCount = new AtomicInteger();

        public void unregister() {
            unregisterCount.incrementAndGet();
        }
    }

    private static final class CountingWebSocketListener extends ReSyncWebSocketListener {
        private final AtomicInteger stopCount = new AtomicInteger();

        private CountingWebSocketListener() {
            super(configuration(), null);
        }

        private static ReSyncConfig configuration() {
            ReSyncConfig config = new ReSyncConfig();
            config.setBindHost("127.0.0.1");
            config.setPort(0);
            return config;
        }

        @Override
        public void stop() {
            stopCount.incrementAndGet();
        }

        @Override
        public void onOpen(WebSocket conn, ClientHandshake handshake) {
        }

        @Override
        public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        }

        @Override
        public void onMessage(WebSocket conn, String message) {
        }

        @Override
        public void onMessage(WebSocket conn, ByteBuffer message) {
        }

        @Override
        public void onError(WebSocket conn, Exception ex) {
        }

        @Override
        public void onStart() {
        }
    }
}
