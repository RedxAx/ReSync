package restudio.resync.core;

import org.java_websocket.WebSocket;
import restudio.resync.protocol.FrameSender;

import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class ConnectionManager {
    private final ConcurrentHashMap<WebSocket, ConnectionInfo> connections;
    private final ConcurrentHashMap<Integer, ConnectionInfo> virtualConnections;
    private final ScheduledExecutorService heartbeatExecutor;
    private final int heartbeatIntervalMs;
    private final int connectionTimeoutMs;
    private final AtomicInteger connectionCounter;

    public ConnectionManager(int heartbeatIntervalSec, int connectionTimeoutSec) {
        this.connections = new ConcurrentHashMap<>();
        this.virtualConnections = new ConcurrentHashMap<>();
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

    public ConnectionInfo createVirtualConnection(FrameSender sender) {
        ConnectionInfo info = new ConnectionInfo(null, sender, connectionCounter.incrementAndGet());
        virtualConnections.put(info.getConnectionId(), info);
        return info;
    }

    public void removeVirtualConnection(ConnectionInfo info) {
        if (info != null) {
            virtualConnections.remove(info.getConnectionId());
            info.setState(ConnectionState.CLOSING);
        }
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

    public int reconnectWebSocketClients(String reason) {
        String closeReason = reason != null && !reason.isBlank() ? reason : "Server reconnect requested";
        ArrayList<ConnectionInfo> activeConnections = new ArrayList<>(connections.values());
        for (ConnectionInfo info : activeConnections) {
            info.setState(ConnectionState.CLOSING);
            info.getFrameSender().close(1012, closeReason);
        }
        return activeConnections.size();
    }

    public void updateHeartbeat(WebSocket conn) {
        ConnectionInfo info = connections.get(conn);
        if (info != null) {
            info.updateHeartbeat();
        }
    }

    public void updateHeartbeat(ConnectionInfo info) {
        if (info != null) {
            info.updateHeartbeat();
        }
    }

    private void checkHeartbeats() {
        long now = System.currentTimeMillis();
        for (ConnectionInfo info : connections.values()) {
            closeTimedOutConnection(now, info);
        }
        for (ConnectionInfo info : virtualConnections.values()) {
            closeTimedOutConnection(now, info);
        }
    }

    private void closeTimedOutConnection(long now, ConnectionInfo info) {
        if (info != null && now - info.getLastHeartbeat() > connectionTimeoutMs) {
            info.setState(ConnectionState.TIMED_OUT);
            info.getFrameSender().close(1000, "Heartbeat timeout");
            if (info.getWebSocket() == null) {
                virtualConnections.remove(info.getConnectionId());
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
