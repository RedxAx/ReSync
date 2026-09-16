package restudio.resync.modules.flow;

import restudio.resync.core.Session;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.stream.Stream;

public final class FlowCatalogPublicationPolicy {
    static final long INITIAL_RETRY_DELAY_MS = 2000L;
    static final long MAX_RETRY_DELAY_MS = 30000L;
    static final int MAX_RETRY_ATTEMPTS = 8;
    private static final String RETRY_EXHAUSTED_CODE = "CATALOG_PUBLICATION.RETRY_EXHAUSTED";
    private static final String DEFAULT_FAILURE_CODE = "CATALOG_PUBLICATION.UNAVAILABLE";
    private final Set<Session> subscribedSessions;
    private final FlowCatalogPublicationPacketHandler handler;
    private final Object stateLock = new Object();
    private final Map<Session, RetryState> pendingSessions = new HashMap<>();
    private final LongSupplier clock;
    private volatile String lastFailureCode = "";

    public FlowCatalogPublicationPolicy(Set<Session> subscribedSessions, FlowCatalogPublicationPacketHandler handler) {
        this(subscribedSessions, handler, System::currentTimeMillis);
    }

    FlowCatalogPublicationPolicy(Set<Session> subscribedSessions, FlowCatalogPublicationPacketHandler handler,
                                 LongSupplier clock) {
        this.subscribedSessions = Objects.requireNonNull(subscribedSessions, "Subscribed sessions are required");
        this.handler = Objects.requireNonNull(handler, "Catalog publication handler is required");
        this.clock = Objects.requireNonNull(clock, "Catalog publication clock is required");
    }

    public boolean admitInitial(Session session) {
        synchronized (stateLock) {
            resetForSessionPublication(session);
            if (session == null) {
                fail("CATALOG_PUBLICATION.SEND_UNAVAILABLE");
                return false;
            }
            boolean admitted = handler.enqueueInitial(session);
            if (!admitted) {
                fail(handler.lastFailureCode().orElse("CATALOG_PUBLICATION.QUEUE_FULL"));
            } else {
                clearFailureIfIdle();
            }
            return admitted;
        }
    }

    public boolean admitRefresh() {
        synchronized (stateLock) {
            resetForNewPublication();
            boolean admitted = handler.enqueueRefresh();
            if (!admitted) {
                fail(handler.lastFailureCode().orElse("CATALOG_PUBLICATION.QUEUE_FULL"));
            } else {
                clearFailureIfIdle();
            }
            return admitted;
        }
    }

    public boolean admitTick(long now) {
        synchronized (stateLock) {
            removeUnsubscribed();
        }
        handler.drainReady(now);
        synchronized (stateLock) {
            handler.lastFailureCode().ifPresentOrElse(code -> {
                fail(code);
            }, this::clearFailureIfIdle);
        }
        return true;
    }

    public void shutdown() {
        synchronized (stateLock) {
            pendingSessions.clear();
            lastFailureCode = "";
        }
        handler.shutdown();
    }

    public boolean publishInitial(Session session) {
        synchronized (stateLock) {
            resetForSessionPublication(session);
            if (session == null) {
                fail("CATALOG_PUBLICATION.SEND_UNAVAILABLE");
                return false;
            }
        }
        boolean sent = sendFull(session);
        synchronized (stateLock) {
            if (sent) {
                clearFailureIfIdle();
            } else {
                recordFailure(session, failureCode(), now());
            }
        }
        return sent;
    }

    public boolean publishRefresh() {
        synchronized (stateLock) {
            resetForNewPublication();
        }
        boolean sent;
        try {
            sent = handler.broadcastRefresh();
        } catch (RuntimeException exception) {
            sent = false;
            synchronized (stateLock) {
                fail(handler.lastFailureCode().orElse(DEFAULT_FAILURE_CODE));
            }
        }
        synchronized (stateLock) {
            if (sent) {
                clearFailureIfIdle();
                return true;
            }
            String code = failureCode();
            fail(code);
            if (isTransient(code)) {
                scheduleAll(now(), code);
            }
            return false;
        }
    }

    public boolean retryPending() {
        return retryPending(now());
    }

    public boolean retryPending(long now) {
        List<Session> targets;
        synchronized (stateLock) {
            removeUnsubscribed();
            if (pendingSessions.isEmpty()) {
                return true;
            }
            targets = pendingSessions.keySet().stream()
                .sorted(Comparator.comparing(this::sessionKey))
                .toList();
        }
        boolean attempted = false;
        boolean terminalFailure = false;
        String firstFailure = null;
        for (Session session : targets) {
            RetryState state;
            synchronized (stateLock) {
                state = pendingSessions.get(session);
                if (state == null || state.retryAt() > now) {
                    continue;
                }
            }
            attempted = true;
            if (sendFull(session)) {
                synchronized (stateLock) {
                    pendingSessions.remove(session);
                }
                continue;
            }
            String code = failureCode();
            synchronized (stateLock) {
                if (!isTransient(code)) {
                    pendingSessions.remove(session);
                    terminalFailure = true;
                    if (firstFailure == null) {
                        firstFailure = code;
                    }
                    continue;
                }
                RetryState next = state.afterFailure(now);
                if (next.exhausted()) {
                    pendingSessions.remove(session);
                    terminalFailure = true;
                    if (firstFailure == null) {
                        firstFailure = RETRY_EXHAUSTED_CODE;
                    }
                    continue;
                }
                pendingSessions.put(session, next);
                if (firstFailure == null) {
                    firstFailure = code;
                }
            }
        }
        synchronized (stateLock) {
            if (pendingSessions.isEmpty()) {
                if (terminalFailure && firstFailure != null) {
                    fail(firstFailure);
                } else {
                    lastFailureCode = "";
                }
            } else if (attempted && firstFailure != null) {
                fail(firstFailure);
            }
            return pendingSessions.isEmpty();
        }
    }

    public void resetForReconnect(Session session) {
        synchronized (stateLock) {
            if (session != null) {
                pendingSessions.remove(session);
            }
            clearFailureIfIdle();
        }
    }

    public boolean retryDue() {
        return retryDue(now());
    }

    public boolean retryDue(long now) {
        synchronized (stateLock) {
            removeUnsubscribed();
            return pendingSessions.values().stream().anyMatch(state -> state.retryAt() <= now);
        }
    }

    public void cleanup(Session session) {
        synchronized (stateLock) {
            if (session != null) {
                pendingSessions.remove(session);
            }
            clearFailureIfIdle();
        }
    }

    public boolean hasPending() {
        boolean pending;
        synchronized (stateLock) {
            removeUnsubscribed();
            pending = !pendingSessions.isEmpty();
        }
        return pending || handler.hasPendingDispatches();
    }

    public Optional<String> lastFailureCode() {
        String code = lastFailureCode;
        return code.isBlank() ? Optional.empty() : Optional.of(code);
    }

    public List<String> pendingSessionIds() {
        List<String> pending;
        synchronized (stateLock) {
            removeUnsubscribed();
            pending = pendingSessions.keySet().stream()
                .map(this::sessionKey)
                .filter(value -> !value.isBlank())
                .toList();
        }
        return Stream.concat(pending.stream(), handler.pendingDispatchSessionIds().stream())
            .distinct()
            .sorted()
            .toList();
    }

    private void resetForNewPublication() {
        pendingSessions.clear();
        lastFailureCode = "";
    }

    private void resetForSessionPublication(Session session) {
        if (session != null) {
            pendingSessions.remove(session);
        }
        clearFailureIfIdle();
    }

    private void scheduleAll(long now, String code) {
        if (!isTransient(code)) {
            return;
        }
        for (Session session : subscribedSessions) {
            if (session != null) {
                pendingSessions.put(session, RetryState.first(now));
            }
        }
    }

    private void recordFailure(Session session, String code, long now) {
        fail(code);
        if (isTransient(code) && subscribedSessions.contains(session)) {
            pendingSessions.put(session, RetryState.first(now));
        }
    }

    private boolean sendFull(Session session) {
        try {
            return handler.sendFull(session);
        } catch (RuntimeException exception) {
            fail(handler.lastFailureCode().orElse(DEFAULT_FAILURE_CODE));
            return false;
        }
    }

    private String failureCode() {
        String code = handler.lastFailureCode().orElse(DEFAULT_FAILURE_CODE);
        fail(code);
        return code;
    }

    private long now() {
        return clock.getAsLong();
    }

    private String sessionKey(Session session) {
        if (session == null) {
            return "";
        }
        String sessionId = session.getSessionId();
        if (sessionId != null && !sessionId.isBlank()) {
            return sessionId;
        }
        String clientId = session.getClientId();
        return clientId == null ? "" : clientId;
    }

    private void removeUnsubscribed() {
        pendingSessions.keySet().removeIf(session -> session == null || !subscribedSessions.contains(session));
    }

    private void clearFailureIfIdle() {
        if (pendingSessions.isEmpty()) {
            lastFailureCode = "";
        }
    }

    private void fail(String code) {
        lastFailureCode = code == null || code.isBlank() ? DEFAULT_FAILURE_CODE : code;
    }

    private static boolean isTransient(String code) {
        if (code == null || code.isBlank()) {
            return true;
        }
        String normalized = code.toUpperCase(Locale.ROOT);
        return DEFAULT_FAILURE_CODE.equals(normalized)
            || normalized.endsWith(".SEND_UNAVAILABLE")
            || normalized.endsWith(".SEND_BACKPRESSURE")
            || normalized.endsWith(".SEND_FAILED");
    }

    private static long retryDelay(int failures) {
        long delay = INITIAL_RETRY_DELAY_MS;
        for (int index = 1; index < failures && delay < MAX_RETRY_DELAY_MS; index++) {
            delay = Math.min(MAX_RETRY_DELAY_MS, delay * 2L);
        }
        return delay;
    }

    private static long addDelay(long now, long delay) {
        if (now > Long.MAX_VALUE - delay) {
            return Long.MAX_VALUE;
        }
        return now + delay;
    }

    private record RetryState(int failures, long retryAt) {
        private static RetryState first(long now) {
            return new RetryState(1, addDelay(now, retryDelay(1)));
        }

        private RetryState afterFailure(long now) {
            int nextFailures = failures == Integer.MAX_VALUE ? Integer.MAX_VALUE : failures + 1;
            return new RetryState(nextFailures, addDelay(now, retryDelay(nextFailures)));
        }

        private boolean exhausted() {
            return failures >= MAX_RETRY_ATTEMPTS;
        }
    }
}
