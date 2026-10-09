package restudio.resync.velocity;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import restudio.resync.network.NetworkCredentials;
import restudio.resync.network.NetworkAuthentication;
import restudio.resync.network.NetworkAuthenticationCodec;
import restudio.resync.network.NetworkChannels;
import restudio.resync.network.NetworkRequestContext;
import restudio.resync.network.NetworkFrame;
import restudio.resync.network.NetworkFrameCodec;
import restudio.resync.network.NetworkFrameType;

import java.net.ServerSocket;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class NetworkEnrollmentRecoveryTest {
    @TempDir
    Path directory;

    @Test
    void persistedCredentialRecoversLostAdmissionAcrossHubRestartWithoutReplayingToken() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        VelocityNetworkConfig.EnrollmentNode node = new VelocityNetworkConfig.EnrollmentNode("backend", "Backend", "BACKEND",
            NetworkCredentials.hash("token"), System.currentTimeMillis() + 60_000, Set.of("node.heartbeat"));
        VelocityNetworkConfig config = new VelocityNetworkConfig(true, "network", "proxy", "Proxy", "127.0.0.1", port,
            directory.resolve("hub.db"), 1_048_576, 500_000, 15_000, 86_400_000, 3,
            VelocityNetworkConfig.Tls.disabled(), Map.of("backend", node), Map.of(), "", List.of(), directory);
        Files.writeString(directory.resolve("network.properties"), "network.enabled=true\nnetwork.id=network\nnetwork.node-id=proxy\nhub.bind-host=127.0.0.1\nhub.port=" + port
            + "\nnodes=backend\nnode.backend.enrollment-token-hash=" + Base64.getUrlEncoder().withoutPadding().encodeToString(NetworkCredentials.hash("token")) + "\n");
        String durable = NetworkCredentials.generate();
        ReSyncVelocityHub hub = new ReSyncVelocityHub(config, LoggerFactory.getLogger(getClass()), null);
        try {
            hub.startHub();
            try (Client enrollment = new Client(port, Map.of("X-ReSync-Enrollment", "token", "X-ReSync-Enrollment-Credential", durable), true)) {
                assertTrue(enrollment.connectBlocking(5, TimeUnit.SECONDS));
                NetworkFrame accepted = enrollment.frames.poll(5, TimeUnit.SECONDS);
                assertNotNull(accepted, () -> "Enrollment Closed: " + enrollment.closed.peek());
                assertEquals(NetworkFrameType.ENROLL_ACK, accepted.type());
            }
            hub.stopHub();
            hub = new ReSyncVelocityHub(config, LoggerFactory.getLogger(getClass()), null);
            hub.startHub();
            try (Client recovered = new Client(port, Map.of("X-ReSync-Credential", durable))) {
                assertTrue(recovered.connectBlocking(5, TimeUnit.SECONDS));
                NetworkFrame accepted = recovered.frames.poll(5, TimeUnit.SECONDS);
                assertNotNull(accepted);
                assertEquals(NetworkFrameType.RESPONSE, accepted.type());
                assertFalse(accepted.context().authorizationScopes().contains("editors.open"));
            }
            try (Client replay = new Client(port, Map.of("X-ReSync-Enrollment", "token", "X-ReSync-Enrollment-Credential", NetworkCredentials.generate()))) {
                replay.connectBlocking(5, TimeUnit.SECONDS);
                assertEquals("Enrollment Token Rejected", replay.closed.poll(5, TimeUnit.SECONDS));
            }
            Files.writeString(directory.resolve("network.properties"), Files.readString(directory.resolve("network.properties")) + "node.backend.capabilities=presence,editors\n");
            try (Client recovered = new Client(port, Map.of("X-ReSync-Credential", durable))) {
                recovered.connectBlocking(5, TimeUnit.SECONDS);
                NetworkFrame accepted = recovered.frames.poll(5, TimeUnit.SECONDS);
                assertNotNull(accepted);
                assertTrue(accepted.context().authorizationScopes().contains("editors.open"));
            }
        } finally {
            hub.stopHub();
        }
    }

    @Test
    void occupiedHubPortFailsStartup() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            VelocityNetworkConfig config = new VelocityNetworkConfig(true, "network", "proxy", "Proxy", "127.0.0.1", socket.getLocalPort(),
                directory.resolve("hub.db"), 1_048_576, 500_000, 15_000, 86_400_000, 3,
                VelocityNetworkConfig.Tls.disabled(), Map.of(), Map.of(), "", List.of(), directory);
            ReSyncVelocityHub hub = new ReSyncVelocityHub(config, LoggerFactory.getLogger(getClass()), null);
            try { assertThrows(Exception.class, hub::startHub); }
            finally { hub.stopHub(); }
        }
    }

    private static final class Client extends WebSocketClient implements AutoCloseable {
        private final ArrayBlockingQueue<NetworkFrame> frames = new ArrayBlockingQueue<>(16);
        private final CountDownLatch stopped = new CountDownLatch(1);
        private final ArrayBlockingQueue<String> closed = new ArrayBlockingQueue<>(1);
        private final NetworkFrameCodec codec = new NetworkFrameCodec(1_048_576, 500_000);
        private final NetworkAuthentication authentication;

        private Client(int port, Map<String, String> auth) {
            this(port, auth, false);
        }

        private Client(int port, Map<String, String> auth, boolean binary) {
            super(URI.create("ws://127.0.0.1:" + port), binary ? Map.of() : auth);
            authentication = binary ? new NetworkAuthentication("network", "backend", auth.getOrDefault("X-ReSync-Credential", ""),
                auth.getOrDefault("X-ReSync-Enrollment", ""), auth.getOrDefault("X-ReSync-Enrollment-Credential", "")) : null;
            if (!binary) {
                addHeader("X-ReSync-Network", "network");
                addHeader("X-ReSync-Node", "backend");
            }
        }

        @Override
        public void onOpen(ServerHandshake handshake) {
            if (authentication != null) {
                NetworkRequestContext context = new NetworkRequestContext(1, "network", "backend", "authentication", System.currentTimeMillis() + 10_000, Set.of());
                send(codec.encode(new NetworkFrame(context, NetworkChannels.CONTROL, NetworkFrameType.ENROLL, NetworkAuthenticationCodec.encode(authentication))));
            }
        }
        @Override
        public void onMessage(String message) {}
        @Override
        public void onMessage(ByteBuffer bytes) {
            byte[] value = new byte[bytes.remaining()];
            bytes.get(value);
            frames.offer(codec.decode(value));
        }
        @Override
        public void onClose(int code, String reason, boolean remote) { closed.offer(reason); stopped.countDown(); }
        @Override
        public void onError(Exception failure) {}
        @Override
        public void close() { super.close(); try { stopped.await(5, TimeUnit.SECONDS); } catch (InterruptedException failure) { Thread.currentThread().interrupt(); } }
    }
}
