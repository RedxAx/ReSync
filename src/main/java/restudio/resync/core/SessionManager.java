package restudio.resync.core;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.java_websocket.WebSocket;
import restudio.resync.memory.MemoryMonitor;
import restudio.resync.security.ClientIdentity;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class SessionManager {
    private final ConcurrentHashMap<WebSocket, Session> sessionsByConnection;
    private final ConcurrentHashMap<String, Session> sessionsById;
    private final ConcurrentHashMap<String, Session> sessionsByClientId;
    private final ConcurrentHashMap<ConnectionInfo, Session> sessionsByConnectionInfo;
    private final ConcurrentHashMap<UUID, Session> sessionsByPlayer;
    private final MemoryMonitor memoryMonitor;
    private final ScheduledExecutorService cleanupExecutor;
    private final long sessionTimeoutMs;
    private final AtomicInteger sessionCounter;
    private final long maxMemoryPerSession;

    public SessionManager(MemoryMonitor memoryMonitor, long sessionTimeoutSec, long maxMemoryPerSessionBytes) {
        this.sessionsByConnection = new ConcurrentHashMap<>();
        this.sessionsById = new ConcurrentHashMap<>();
        this.sessionsByClientId = new ConcurrentHashMap<>();
        this.sessionsByConnectionInfo = new ConcurrentHashMap<>();
        this.sessionsByPlayer = new ConcurrentHashMap<>();
        this.memoryMonitor = memoryMonitor;
        this.sessionTimeoutMs = sessionTimeoutSec *1000;
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
        return createSession(connection, new ClientIdentity(clientId, connection.getClientVersion()));
    }

    public Session createSession(ConnectionInfo connection, ClientIdentity identity) {
        String sessionId = UUID.randomUUID().toString();
        Session session = new Session(sessionId, identity.clientId(), connection, identity);

        if (connection.getWebSocket() != null) {
            sessionsByConnection.put(connection.getWebSocket(), session);
        }
        sessionsById.put(sessionId, session);
        sessionsByConnectionInfo.put(connection, session);
        sessionsByClientId.put(identity.clientId(), session);

        memoryMonitor.trackSession(session);

        sessionCounter.incrementAndGet();
        connection.setState(ConnectionState.AUTHENTICATED);

        return session;
    }

    public Session getSessionByClientId(String clientId) {
        return sessionsByClientId.get(clientId);
    }

    public Session getSession(WebSocket conn) {
        return sessionsByConnection.get(conn);
    }

    public Session getSession(ConnectionInfo connection) {
        return sessionsByConnectionInfo.get(connection);
    }

    public Session getSessionByPlayer(UUID playerUuid) {
        return sessionsByPlayer.get(playerUuid);
    }

    public void linkPlayerToSession(UUID playerUuid, Session session) {
        sessionsByPlayer.put(playerUuid, session);
    }

    public void unlinkPlayer(UUID playerUuid) {
        sessionsByPlayer.remove(playerUuid);
    }

    public Session getSessionById(String sessionId) {
        return sessionsById.get(sessionId);
    }

    public void removeSession(WebSocket conn) {
        Session session = sessionsByConnection.remove(conn);
        if (session != null) {
            sessionsById.remove(session.getSessionId());
            sessionsByClientId.remove(session.getClientId(), session);
            sessionsByConnectionInfo.remove(session.getConnection());
            sessionsByPlayer.entrySet().removeIf(entry -> entry.getValue() == session);
            memoryMonitor.untrackSession(session);
        }
    }

    public void removeSession(ConnectionInfo connection) {
        if (connection == null) {
            return;
        }
        Session session = sessionsByConnectionInfo.remove(connection);
        if (session != null) {
            if (connection.getWebSocket() != null) {
                sessionsByConnection.remove(connection.getWebSocket());
            }
            sessionsById.remove(session.getSessionId());
            sessionsByClientId.remove(session.getClientId(), session);
            sessionsByPlayer.entrySet().removeIf(entry -> entry.getValue() == session);
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
                session.getConnection().getFrameSender().close(1000, "Session timeout");
            } else if (session.getMemoryUsage() > maxMemoryPerSession) {
                session.getConnection().getFrameSender().close(1000, "Session memory limit exceeded");
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

    public java.util.Collection<Session> getSessions() {
        return sessionsById.values();
    }
}
