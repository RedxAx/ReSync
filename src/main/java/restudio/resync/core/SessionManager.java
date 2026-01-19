package restudio.resync.core;

import org.java_websocket.WebSocket;
import restudio.resync.memory.MemoryMonitor;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class SessionManager {
    private final ConcurrentHashMap<WebSocket, Session> sessionsByConnection;
    private final ConcurrentHashMap<String, Session> sessionsById;
    private final ConcurrentHashMap<ConnectionInfo, Session> sessionsByConnectionInfo;
    private final MemoryMonitor memoryMonitor;
    private final ScheduledExecutorService cleanupExecutor;
    private final long sessionTimeoutMs;
    private final AtomicInteger sessionCounter;
    private final long maxMemoryPerSession;

    public SessionManager(MemoryMonitor memoryMonitor, long sessionTimeoutSec, long maxMemoryPerSessionBytes) {
        this.sessionsByConnection = new ConcurrentHashMap<>();
        this.sessionsById = new ConcurrentHashMap<>();
        this.sessionsByConnectionInfo = new ConcurrentHashMap<>();
        this.memoryMonitor = memoryMonitor;
        this.sessionTimeoutMs = sessionTimeoutSec * 1000;
        this.maxMemoryPerSession = maxMemoryPerSessionBytes;
        this.sessionCounter = new AtomicInteger(0);
        this.cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ReSync-SessionCleanup");
            t.setDaemon(true);
            return t;
        });

        this.cleanupExecutor.scheduleWithFixedDelay(this::cleanupSessions, 30000, 30000, TimeUnit.MILLISECONDS);
    }

    public Session createSession(ConnectionInfo connection, String clientId) {
        String sessionId = UUID.randomUUID().toString();
        Session session = new Session(sessionId, clientId, connection);

        sessionsByConnection.put(connection.getWebSocket(), session);
        sessionsById.put(sessionId, session);
        sessionsByConnectionInfo.put(connection, session);

        memoryMonitor.trackSession(session);

        sessionCounter.incrementAndGet();
        connection.setState(ConnectionState.AUTHENTICATED);

        return session;
    }

    public Session getSession(WebSocket conn) {
        return sessionsByConnection.get(conn);
    }

    public Session getSession(ConnectionInfo connection) {
        return sessionsByConnectionInfo.get(connection);
    }

    public Session getSessionById(String sessionId) {
        return sessionsById.get(sessionId);
    }

    public void removeSession(WebSocket conn) {
        Session session = sessionsByConnection.remove(conn);
        if (session != null) {
            sessionsById.remove(session.getSessionId());
            sessionsByConnectionInfo.remove(session.getConnection());
            memoryMonitor.untrackSession(session);
        }
    }

    public int getSessionCount() {
        return sessionsByConnection.size();
    }

    public long getTotalSessionMemory() {
        long total = 0;
        for (Session session : sessionsById.values()) {
            total += session.getMemoryUsage();
        }
        return total;
    }

    public boolean canAcceptMemory(long additionalBytes) {
        return getTotalSessionMemory() + additionalBytes <= memoryMonitor.getMaxMemoryForSessions();
    }

    private void cleanupSessions() {
        long now = System.currentTimeMillis();

        for (Session session : sessionsById.values()) {
            long idleTime = session.getIdleTime();

            if (idleTime > sessionTimeoutMs) {
                session.getConnection().setState(ConnectionState.TIMED_OUT);
                session.getConnection().getWebSocket().close(1000, "Session timeout");
            } else if (session.getMemoryUsage() > maxMemoryPerSession) {
                session.getConnection().getWebSocket().close(1000, "Session memory limit exceeded");
            }
        }
    }

    public void shutdown() {
        cleanupExecutor.shutdown();
        try {
            if (!cleanupExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                cleanupExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            cleanupExecutor.shutdownNow();
        }
    }
}
