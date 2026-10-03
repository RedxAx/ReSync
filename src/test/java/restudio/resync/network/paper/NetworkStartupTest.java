package restudio.resync.network.paper;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;
import restudio.resync.ReSync;
import restudio.resync.migration.MigrationFence;
import restudio.resync.modules.flow.FlowResourceRegistry;
import restudio.resync.network.NetworkResourcePage;
import restudio.resync.network.NetworkResourceQuery;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkStartupTest {
    @TempDir
    Path temporary;

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void resourceCheckpointResumeCannotHoldAStartupFenceWaitingForAServerTick() throws Exception {
        TestReSync plugin = plugin();
        MigrationFence fence = new MigrationFence();
        ConnectedAgent agent = new ConnectedAgent(plugin, temporary, fence);
        ReSyncNetworkAgentConfig.ResourcePolicy policy = new ReSyncNetworkAgentConfig.ResourcePolicy(true,
            ReSyncNetworkAgentConfig.SelectionMode.ALL, Set.of(), ReSyncNetworkAgentConfig.ResourceConflictPolicy.NETWORK_WINS);
        NetworkResourceSynchronizer synchronizer = new NetworkResourceSynchronizer(plugin, agent,
            new FlowResourceRegistry(), policy, temporary, resource -> {});
        try {
            for (int checkpoint = 0; checkpoint < 2; checkpoint++) {
                agent.persistenceDrain().quiescePersistence();
                agent.persistenceDrain().resumePersistence();
            }
            try (MigrationFence.MigrationLease ignored = fence.tryAcquireMigration(Duration.ZERO).orElseThrow()) {
                assertTrue(fence.migrationActive());
            }
        } finally {
            synchronizer.shutdown();
            agent.prepareForShutdown();
            assertTrue(agent.shutdownAfterPreparation(() -> CompletableFuture.completedFuture(null))
                .toCompletableFuture().get(5, TimeUnit.SECONDS).completed());
        }
    }

    @Test
    void persistenceCheckpointsCannotEnrollAnUnstartedBackendOrBlockFinalActivation() throws Exception {
        TestReSync plugin = plugin();
        MigrationFence fence = new MigrationFence();
        Hub hub = new Hub();
        ReSyncNetworkAgent agent = null;
        hub.start();
        try {
            assertTrue(hub.ready.await(5, TimeUnit.SECONDS));
            Path credential = temporary.resolve("network/node.credential");
            ReSyncNetworkAgentConfig config = new ReSyncNetworkAgentConfig(true,
                ReSyncNetworkAgentConfig.ChatPolicy.disabled(), ReSyncNetworkAgentConfig.ResourcePolicy.disabled(),
                List.of(), "test-network", "test-backend", "Test", "ws://127.0.0.1:" + hub.getPort(),
                "test-token", "", 0, 1_048_576, 500_000, 100, 100,
                ReSyncNetworkAgentConfig.Tls.disabled(), credential);
            agent = new ReSyncNetworkAgent(plugin, config, fence);

            for (int checkpoint = 0; checkpoint < 2; checkpoint++) {
                agent.persistenceDrain().quiescePersistence();
                agent.persistenceDrain().resumePersistence();
            }

            assertNull(hub.connections.poll(300, TimeUnit.MILLISECONDS));
            assertTrue(Files.notExists(credential));
            try (MigrationFence.MigrationLease ignored = fence.tryAcquireMigration(Duration.ZERO).orElseThrow()) {
                assertTrue(fence.migrationActive());
            }

            agent.start();
            assertNotNull(hub.connections.poll(5, TimeUnit.SECONDS));
            agent.start();
            assertNull(hub.connections.poll(300, TimeUnit.MILLISECONDS));
        } finally {
            if (agent != null) {
                agent.prepareForShutdown();
                assertTrue(agent.shutdownAfterPreparation(() -> CompletableFuture.completedFuture(null))
                    .toCompletableFuture().get(5, TimeUnit.SECONDS).completed());
            }
            hub.stop(1_000);
        }
    }

    public static class TestReSync extends ReSync {
        @Override
        public void onEnable() {
        }

        @Override
        public void onDisable() {
        }
    }

    private TestReSync plugin() throws IOException {
        MockBukkit.mock(new Server(temporary));
        Path world = Files.createDirectories(temporary.resolve("world/playerdata")).getParent();
        MockBukkit.getMock().addWorld(new World(world));
        return MockBukkit.loadSimple(TestReSync.class);
    }

    private static final class World extends WorldMock {
        private final File folder;

        private World(Path folder) {
            this.folder = folder.toFile();
        }

        @Override
        public File getWorldFolder() {
            return folder;
        }
    }

    private static final class Server extends ServerMock {
        private final File worlds;

        private Server(Path worlds) {
            this.worlds = worlds.toFile();
        }

        @Override
        public File getWorldContainer() {
            return worlds;
        }
    }

    private static final class ConnectedAgent extends ReSyncNetworkAgent {
        private ConnectedAgent(ReSync plugin, Path root, MigrationFence fence) {
            super(plugin, new ReSyncNetworkAgentConfig(false, ReSyncNetworkAgentConfig.ChatPolicy.disabled(),
                ReSyncNetworkAgentConfig.ResourcePolicy.disabled(), List.of(), "test-network", "test-backend", "Test",
                "", "", "", 0, 1_048_576, 500_000, 100, 100, ReSyncNetworkAgentConfig.Tls.disabled(),
                root.resolve("network/node.credential")), fence);
        }

        @Override
        public boolean connected() {
            return true;
        }

        @Override
        public CompletableFuture<NetworkResourcePage> listResources(NetworkResourceQuery query) {
            return CompletableFuture.completedFuture(new NetworkResourcePage(List.of(), "", ""));
        }
    }

    private static final class Hub extends WebSocketServer {
        private final CountDownLatch ready = new CountDownLatch(1);
        private final ArrayBlockingQueue<WebSocket> connections = new ArrayBlockingQueue<>(4);

        private Hub() {
            super(new InetSocketAddress("127.0.0.1", 0));
        }

        @Override
        public void onStart() {
            ready.countDown();
        }

        @Override
        public void onOpen(WebSocket connection, ClientHandshake handshake) {
            connections.add(connection);
        }

        @Override
        public void onClose(WebSocket connection, int code, String reason, boolean remote) {
        }

        @Override
        public void onMessage(WebSocket connection, String message) {
        }

        @Override
        public void onError(WebSocket connection, Exception exception) {
        }
    }
}
