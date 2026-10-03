package restudio.resync.server;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.DefaultSSLWebSocketServerFactory;
import org.java_websocket.server.WebSocketServer;
import restudio.resync.Log;

import javax.net.ssl.SSLContext;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;

public abstract class ReSyncWebSocketListener {
    private final ReSyncConfig config;
    private final SSLContext tls;
    private final Object lifecycle = new Object();
    private Socket socket;
    private Thread worker;
    private boolean stopping;

    protected ReSyncWebSocketListener(ReSyncConfig config, SSLContext tls) {
        this.config = config;
        this.tls = tls;
        socket = new Socket(config.getPort());
    }

    public void start() {
        synchronized (lifecycle) {
            if (worker != null || stopping) {
                throw new IllegalStateException("ReSync WebSocket Listener Has Already Started Or Stopped");
            }
            worker = new Thread(this::run, "ReSync-WebSocket");
            worker.start();
        }
    }

    private void run() {
        Socket attempt;
        synchronized (lifecycle) {
            attempt = socket;
        }
        attempt.run();
        synchronized (lifecycle) {
            if (stopping || !attempt.retryLocalPort) {
                return;
            }
            Thread.interrupted();
            Log.warn("ReSync port " + config.getPort() + " is occupied. Selecting an available local port.");
            socket = new Socket(0);
            attempt = socket;
        }
        attempt.run();
    }

    public int getPort() {
        synchronized (lifecycle) {
            return socket.getPort();
        }
    }

    public void stop() throws InterruptedException {
        Socket current;
        Thread currentWorker;
        synchronized (lifecycle) {
            stopping = true;
            current = socket;
            currentWorker = worker;
        }
        current.stop(1000);
        if (currentWorker != null && currentWorker != Thread.currentThread()) {
            currentWorker.join(5000);
            if (currentWorker.isAlive()) {
                throw new IllegalStateException("ReSync WebSocket Listener Shutdown Is Still Pending");
            }
        }
    }

    public abstract void onOpen(WebSocket connection, ClientHandshake handshake);
    public abstract void onClose(WebSocket connection, int code, String reason, boolean remote);
    public abstract void onMessage(WebSocket connection, String message);
    public abstract void onMessage(WebSocket connection, ByteBuffer message);
    public abstract void onError(WebSocket connection, Exception exception);
    public abstract void onStart();

    private final class Socket extends WebSocketServer {
        private boolean retryLocalPort;
        private boolean ready;

        private Socket(int port) {
            super(new InetSocketAddress(config.getBindHost(), port));
            setReuseAddr(true);
            if (tls != null) {
                setWebSocketFactory(new DefaultSSLWebSocketServerFactory(tls));
            }
        }

        @Override
        public void onStart() {
            synchronized (lifecycle) {
                if (stopping) {
                    return;
                }
                try {
                    int port = getPort();
                    if (port != config.getPort()) {
                        config.getPersistenceParticipant().updateProperties(properties -> properties.setProperty("port", Integer.toString(port)));
                        config.setPort(port);
                    }
                    ReSyncWebSocketListener.this.onStart();
                    ready = true;
                } catch (Exception exception) {
                    ReSyncWebSocketListener.this.onError(null, exception);
                    try {
                        stop(1);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }

        @Override
        public void onError(WebSocket connection, Exception exception) {
            if (connection == null && exception instanceof BindException && getAddress().getPort() != 0
                && getAddress().getAddress() != null && getAddress().getAddress().isLoopbackAddress()) {
                retryLocalPort = true;
                return;
            }
            ReSyncWebSocketListener.this.onError(connection, exception);
        }

        @Override
        public void onOpen(WebSocket connection, ClientHandshake handshake) {
            if (!ready) {
                connection.close(1013, "ReSync WebSocket API Is Unavailable");
                return;
            }
            ReSyncWebSocketListener.this.onOpen(connection, handshake);
        }

        @Override
        public void onClose(WebSocket connection, int code, String reason, boolean remote) {
            ReSyncWebSocketListener.this.onClose(connection, code, reason, remote);
        }

        @Override
        public void onMessage(WebSocket connection, String message) {
            ReSyncWebSocketListener.this.onMessage(connection, message);
        }

        @Override
        public void onMessage(WebSocket connection, ByteBuffer message) {
            ReSyncWebSocketListener.this.onMessage(connection, message);
        }
    }
}
