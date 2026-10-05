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
import restudio.resync.network.NetworkCredentials;
import restudio.resync.network.NetworkResourceQuery;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import restudio.resync.network.NetworkFrame;
import restudio.resync.network.NetworkFrameCodec;
import restudio.resync.network.NetworkFrameType;
import restudio.resync.network.NetworkRequestContext;
import restudio.resync.network.NetworkChannels;
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

    @Test
    void refusedConnectionRecoversAgainstAnOlderHubWithoutDeletingTheSavedCredential() throws Exception {
        TestReSync plugin = plugin();
        int port;
        try (ServerSocket socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        Path credential = temporary.resolve("network/node.credential");
        ReSyncNetworkAgentConfig config = new ReSyncNetworkAgentConfig(true, ReSyncNetworkAgentConfig.ChatPolicy.disabled(),
            ReSyncNetworkAgentConfig.ResourcePolicy.disabled(), List.of(), "test-network", "test-backend", "Test",
            "ws://127.0.0.1:" + port, "token", "", 0, 1_048_576, 500_000, 100, 20,
            ReSyncNetworkAgentConfig.Tls.disabled(), credential);
        ReSyncNetworkAgent agent = new ReSyncNetworkAgent(plugin, config, new MigrationFence());
        Hub hub = new Hub(port, credential);
        try {
            agent.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (agent.status().failure().isBlank() && System.nanoTime() < deadline) Thread.sleep(10);
            assertTrue(agent.status().failure().contains("refused"));
            assertTrue(Files.exists(credential));
            hub.start();
            assertTrue(hub.ready.await(5, TimeUnit.SECONDS));
            agent.reconnect();
            MockBukkit.getMock().getScheduler().performTicks(2);
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!agent.connected() && System.nanoTime() < deadline) {
                Thread.sleep(10);
                MockBukkit.getMock().getScheduler().performTicks(20);
            }
            assertTrue(agent.connected(), agent.status().failure());
            assertTrue(agent.status().failure().isBlank());
        } finally {
            agent.prepareForShutdown();
            assertTrue(agent.shutdownAfterPreparation(() -> CompletableFuture.completedFuture(null)).toCompletableFuture().get(5, TimeUnit.SECONDS).completed());
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

        private final Path credential;
        private String issued = "";

        private Hub() { this(0, null); }

        private Hub(int port, Path credential) {
            super(new InetSocketAddress("127.0.0.1", port));
            this.credential = credential;
        }

        @Override
        public void onStart() {
            ready.countDown();
        }

        @Override
        public void onOpen(WebSocket connection, ClientHandshake handshake) {
            connections.add(connection);
            if (credential != null) {
                try {
                    String authenticated = handshake.getFieldValue("X-ReSync-Credential");
                    if (!authenticated.isBlank() && !authenticated.equals(issued)) {
                        connection.close(1008, "Network Credential Rejected");
                        return;
                    }
                    String saved = Files.readString(credential).trim();
                    if (!saved.equals(handshake.getFieldValue("X-ReSync-Enrollment-Credential"))) {
                        connection.close(1008, "Credential Was Not Saved Before Enrollment");
                        return;
                    }
                    issued = NetworkCredentials.generate();
                    NetworkRequestContext context = new NetworkRequestContext(1, "test-network", "proxy", "session", System.currentTimeMillis() + 10_000, Set.of("node.heartbeat"));
                    connection.send(new NetworkFrameCodec(1_048_576, 500_000).encode(new NetworkFrame(context, NetworkChannels.CONTROL, NetworkFrameType.ENROLL_ACK, issued.getBytes(StandardCharsets.UTF_8))));
                } catch (IOException failure) { connection.close(1011, failure.getMessage()); }
            }
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
