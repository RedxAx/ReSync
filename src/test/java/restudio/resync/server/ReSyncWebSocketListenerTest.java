package restudio.resync.server;

import org.java_websocket.WebSocket;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.handshake.ServerHandshake;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.net.BindException;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReSyncWebSocketListenerTest {
    @TempDir
    Path root;

    @Test
    void occupiedLocalPortIsReplacedDurablyAndServesWebSocketTraffic() throws Exception {
        try (ServerSocket occupied = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            ReSyncConfig config = config("127.0.0.1", occupied.getLocalPort());
            TestListener listener = new TestListener(config);
            int port;
            try {
                listener.start();
                port = listener.started.get(5, TimeUnit.SECONDS);
                assertNotEquals(occupied.getLocalPort(), port);
                assertEquals(port, config.getPort());
                assertEquals(Integer.toString(port), ConfigLoader.load(root).getPersistenceParticipant().properties().getProperty("port"));
                CompletableFuture<String> received = new CompletableFuture<>();
                WebSocketClient client = new WebSocketClient(URI.create("ws://127.0.0.1:" + port)) {
                    @Override
                    public void onOpen(ServerHandshake handshake) { send("ReSync"); }
                    @Override
                    public void onMessage(String message) { received.complete(message); }
                    @Override
                    public void onClose(int code, String reason, boolean remote) { }
                    @Override
                    public void onError(Exception exception) { received.completeExceptionally(exception); }
                };
                try {
                    client.connect();
                    assertEquals("ReSync", received.get(5, TimeUnit.SECONDS));
                } finally {
                    client.closeBlocking();
                }
            } finally {
                listener.stop();
            }
            TestListener restarted = new TestListener(ConfigLoader.load(root));
            try {
                restarted.start();
                assertEquals(port, restarted.started.get(5, TimeUnit.SECONDS));
            } finally {
                restarted.stop();
            }
        }
    }

    @Test
    void occupiedPublicPortIsReportedWithoutChangingConfiguration() throws Exception {
        try (ServerSocket occupied = new ServerSocket(0)) {
            ReSyncConfig config = config("0.0.0.0", occupied.getLocalPort());
            TestListener listener = new TestListener(config);
            try {
                listener.start();
                assertTrue(listener.failed.get(5, TimeUnit.SECONDS) instanceof BindException);
                assertEquals(occupied.getLocalPort(), config.getPort());
                assertFalse(listener.started.isDone());
            } finally {
                listener.stop();
            }
        }
    }

    @Test
    void failedPortPersistenceDoesNotPublishAReadyEndpoint() throws Exception {
        try (ServerSocket occupied = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            ReSyncConfig config = config("127.0.0.1", occupied.getLocalPort());
            config.getPersistenceParticipant().quiesce();
            TestListener listener = new TestListener(config);
            try {
                listener.start();
                listener.failed.get(5, TimeUnit.SECONDS);
                assertFalse(listener.started.isDone());
                assertEquals(occupied.getLocalPort(), config.getPort());
            } finally {
                listener.stop();
            }
        }
    }

    private ReSyncConfig config(String host, int port) throws Exception {
        Files.writeString(root.resolve("config.properties"), "api-key=test\nbind-host=" + host + "\npublic-bind-enabled=true\nport=" + port + "\n");
        ReSyncConfig config = ConfigLoader.load(root);
        config.getPersistenceParticipant().activateAndFlush();
        return config;
    }

    private static final class TestListener extends ReSyncWebSocketListener {
        private final CompletableFuture<Integer> started = new CompletableFuture<>();
        private final CompletableFuture<Exception> failed = new CompletableFuture<>();

        private TestListener(ReSyncConfig config) { super(config, null); }
        @Override
        public void onOpen(WebSocket connection, ClientHandshake handshake) { }
        @Override
        public void onClose(WebSocket connection, int code, String reason, boolean remote) { }
        @Override
        public void onMessage(WebSocket connection, String message) { connection.send(message); }
        @Override
        public void onMessage(WebSocket connection, ByteBuffer message) { connection.send(message); }
        @Override
        public void onError(WebSocket connection, Exception exception) { failed.complete(exception); }
        @Override
        public void onStart() { started.complete(getPort()); }
    }
}
