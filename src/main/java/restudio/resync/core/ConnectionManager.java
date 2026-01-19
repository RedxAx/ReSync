package restudio.resync.core;

import org.java_websocket.WebSocket;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class ConnectionManager {
    private final ConcurrentHashMap<WebSocket, ConnectionInfo> connections;
    private final ScheduledExecutorService heartbeatExecutor;
    private final int heartbeatIntervalMs;
    private final int connectionTimeoutMs;
    private final AtomicInteger connectionCounter;

    public ConnectionManager(int heartbeatIntervalSec, int connectionTimeoutSec) {
        this.connections = new ConcurrentHashMap<>();
        this.heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ReSync-Heartbeat");
            t.setDaemon(true);
            return t;
        });
        this.heartbeatIntervalMs = heartbeatIntervalSec * 1000;
        this.connectionTimeoutMs = connectionTimeoutSec * 1000;
        this.connectionCounter = new AtomicInteger(0);

        this.heartbeatExecutor.scheduleWithFixedDelay(this::checkHeartbeats, heartbeatIntervalMs, heartbeatIntervalMs, TimeUnit.MILLISECONDS);
    }

    public ConnectionInfo createConnection(WebSocket conn) {
        ConnectionInfo info = new ConnectionInfo(conn, connectionCounter.incrementAndGet());
        connections.put(conn, info);
        return info;
    }

    public void removeConnection(WebSocket conn) {
        ConnectionInfo info = connections.remove(conn);
        if (info != null) {
            info.setState(ConnectionState.CLOSING);
        }
    }

    public ConnectionInfo getConnection(WebSocket conn) {
        return connections.get(conn);
    }

    public int getConnectionCount() {
        return connections.size();
    }

    public void updateHeartbeat(WebSocket conn) {
        ConnectionInfo info = connections.get(conn);
        if (info != null) {
            info.updateHeartbeat();
        }
    }

    private void checkHeartbeats() {
        long now = System.currentTimeMillis();
        for (ConnectionInfo info : connections.values()) {
            if (now - info.getLastHeartbeat() > connectionTimeoutMs) {
                info.setState(ConnectionState.TIMED_OUT);
                info.getWebSocket().close(1000, "Heartbeat timeout");
            }
        }
    }

    public void shutdown() {
        heartbeatExecutor.shutdown();
        try {
            if (!heartbeatExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                heartbeatExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            heartbeatExecutor.shutdownNow();
        }
    }
}
