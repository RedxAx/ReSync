package restudio.resync.modules.flow;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.resync.core.Session;
import restudio.resync.flow.cache.CatalogCacheKey;
import restudio.resync.flow.cache.CatalogCachePublicationTransport;
import restudio.resync.flow.cache.CatalogPublicationReceiptStore;
import restudio.resync.flow.catalog.CatalogBindingProof;
import restudio.resync.flow.catalog.CatalogCompiler;
import restudio.resync.flow.catalog.CatalogRuntimeActivation;
import restudio.resync.flow.catalog.CatalogSnapshot;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.runtime.RuntimeRegistrySnapshot;
import restudio.resync.flow.identity.ServerId;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.AbstractSet;
import java.util.Iterator;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowCatalogPublicationPolicyTest {
    private static final ServerId SERVER = new ServerId(UUID.fromString("11111111-1111-4111-8111-111111111111"));
    private final AtomicLong clock = new AtomicLong();

    @TempDir
    Path temporary;

    @Test
    void failedRefreshIsNotReportedAsConvergedAndKeepsPendingTargets() {
        Set<Session> sessions = ConcurrentHashMap.newKeySet();
        Session session = new Session("refresh-session", "client", null);
        sessions.add(session);
        ToggleSender sender = new ToggleSender(sessions);
        FlowCatalogPublicationPolicy policy = policy(sessions, sender);

        assertFalse(policy.publishRefresh());
        assertTrue(policy.hasPending());
        assertFalse(policy.retryDue());
        assertEquals(List.of("refresh-session"), policy.pendingSessionIds());
        assertEquals("CATALOG_PUBLICATION.SEND_FAILED", policy.lastFailureCode().orElseThrow());
    }

    @Test
    void refreshDoesNotRecordAReceiptForASessionJoinedAfterBroadcastPreflight() {
        Session initial = new Session("initial-refresh-session", "initial-client", null);
        Session joined = new Session("joined-refresh-session", "joined-client", null);
        LateJoiningSessions sessions = new LateJoiningSessions(initial, joined);
        ToggleSender sender = new ToggleSender(sessions);
        sender.fail = false;
        CatalogSnapshot active = CatalogSnapshot.empty(new CatalogVersion(1, 0));
        CatalogPublicationReceiptStore store = receiptStore("broadcast-refresh");
        FlowCatalogPublicationPacketHandler handler = new FlowCatalogPublicationPacketHandler(sender,
            new CatalogCachePublicationTransport(SERVER, () -> active, Set.of()), store);
        FlowCatalogPublicationPolicy policy = new FlowCatalogPublicationPolicy(sessions, handler, clock::get);

        assertTrue(policy.publishRefresh());
        assertTrue(store.baseline(initial.getClientId()).isPresent());
        assertTrue(store.baseline(joined.getClientId()).isEmpty());
    }

    @Test
    void failedInitialSendRemainsPendingAndRetriesDeterministically() {
        Set<Session> sessions = ConcurrentHashMap.newKeySet();
        Session session = new Session("initial-session", "client", null);
        sessions.add(session);
        ToggleSender sender = new ToggleSender(sessions);
        FlowCatalogPublicationPolicy policy = policy(sessions, sender);

        assertFalse(policy.publishInitial(session));
        assertTrue(policy.hasPending());
        assertEquals(List.of("initial-session"), policy.pendingSessionIds());

        sender.fail = false;
        assertFalse(policy.retryPending());
        clock.addAndGet(FlowCatalogPublicationPolicy.INITIAL_RETRY_DELAY_MS);
        assertTrue(policy.retryPending());
        assertFalse(policy.hasPending());
        assertTrue(policy.lastFailureCode().isEmpty());
    }

    @Test
    void pendingDeliveryRetryKeepsTheActivationPublicationKey() {
        Set<Session> sessions = ConcurrentHashMap.newKeySet();
        Session session = new Session("activation-session", "client", null);
        sessions.add(session);
        ToggleSender sender = new ToggleSender(sessions);
        RuntimeRegistrySnapshot runtime = RuntimeRegistrySnapshot.empty();
        CatalogSnapshot active = new CatalogCompiler(new CatalogVersion(1, 0), CatalogBindingProof.snapshot(runtime))
            .compile(List.of(), 1).snapshot().orElseThrow();
        CatalogRuntimeActivation activation = new CatalogRuntimeActivation(active, runtime);
        FlowCatalogPublicationPacketHandler handler = new FlowCatalogPublicationPacketHandler(sender,
            new CatalogCachePublicationTransport(SERVER, activation, Set.of()), receiptStore("activation"));
        FlowCatalogPublicationPolicy policy = new FlowCatalogPublicationPolicy(sessions, handler, clock::get);

        assertFalse(policy.publishInitial(session));
        CatalogCacheKey key = activation.activePublicationKey().orElseThrow();

        sender.fail = false;
        clock.addAndGet(FlowCatalogPublicationPolicy.INITIAL_RETRY_DELAY_MS);
        assertTrue(policy.retryPending());
        assertEquals(key, activation.activePublicationKey().orElseThrow());
    }

    @Test
    void permanentFailureBecomesTerminalUntilTheNextPublication() {
        Set<Session> sessions = ConcurrentHashMap.newKeySet();
        Session session = new Session("terminal-session", "client", null);
        sessions.add(session);
        FailureSender sender = new FailureSender(sessions, "CATALOG_PUBLICATION.SEND_TOO_LARGE");
        FlowCatalogPublicationPolicy policy = policy(sessions, sender);

        assertFalse(policy.publishInitial(session));
        assertFalse(policy.hasPending());
        assertFalse(policy.retryDue());
        assertEquals("CATALOG_PUBLICATION.SEND_TOO_LARGE", policy.lastFailureCode().orElseThrow());
        assertTrue(policy.retryPending());
        assertEquals(1, sender.attempts);

        sender.fail = false;
        assertTrue(policy.publishInitial(session));
        assertTrue(policy.lastFailureCode().isEmpty());
    }

    @Test
    void transientFailuresUseBoundedExponentialBackoff() {
        Set<Session> sessions = ConcurrentHashMap.newKeySet();
        Session session = new Session("backoff-session", "client", null);
        sessions.add(session);
        ToggleSender sender = new ToggleSender(sessions);
        FlowCatalogPublicationPolicy policy = policy(sessions, sender);

        assertFalse(policy.publishInitial(session));
        assertFalse(policy.retryDue());
        clock.addAndGet(FlowCatalogPublicationPolicy.INITIAL_RETRY_DELAY_MS);
        assertFalse(policy.retryPending());
        assertEquals(2, sender.attempts);
        assertFalse(policy.retryDue());
        clock.addAndGet(FlowCatalogPublicationPolicy.INITIAL_RETRY_DELAY_MS * 2L);
        assertFalse(policy.retryPending());
        assertEquals(3, sender.attempts);
        assertFalse(policy.retryDue());
    }

    @Test
    void unrecoveredTransientFailureBecomesTerminalAfterTheAttemptBudget() {
        Set<Session> sessions = ConcurrentHashMap.newKeySet();
        Session session = new Session("exhausted-session", "client", null);
        sessions.add(session);
        ToggleSender sender = new ToggleSender(sessions);
        FlowCatalogPublicationPolicy policy = policy(sessions, sender);

        assertFalse(policy.publishInitial(session));
        for (int attempt = 2; attempt <= FlowCatalogPublicationPolicy.MAX_RETRY_ATTEMPTS; attempt++) {
            clock.set(Long.MAX_VALUE);
            boolean complete = policy.retryPending();
            if (attempt < FlowCatalogPublicationPolicy.MAX_RETRY_ATTEMPTS) {
                assertFalse(complete);
            } else {
                assertTrue(complete);
            }
        }
        assertFalse(policy.hasPending());
        assertEquals("CATALOG_PUBLICATION.RETRY_EXHAUSTED", policy.lastFailureCode().orElseThrow());
        assertEquals(FlowCatalogPublicationPolicy.MAX_RETRY_ATTEMPTS, sender.attempts);
    }

    private FlowCatalogPublicationPolicy policy(Set<Session> sessions, FlowPacketSender sender) {
        CatalogSnapshot active = CatalogSnapshot.empty(new CatalogVersion(1, 0));
        FlowCatalogPublicationPacketHandler handler = new FlowCatalogPublicationPacketHandler(sender,
            new CatalogCachePublicationTransport(SERVER, () -> active, Set.of()), receiptStore("policy"));
        return new FlowCatalogPublicationPolicy(sessions, handler, clock::get);
    }

    private CatalogPublicationReceiptStore receiptStore(String name) {
        Path root = temporary.resolve(name);
        try {
            Files.createDirectory(root);
        } catch (IOException exception) {
            throw new IllegalStateException("Receipt test root could not be created", exception);
        }
        return new CatalogPublicationReceiptStore(root, root.resolve(CatalogPublicationReceiptStore.FILE_NAME));
    }

    private static final class ToggleSender extends FlowPacketSender {
        private boolean fail = true;
        private int attempts;

        private ToggleSender(Set<Session> sessions) {
            super(null, 0, sessions);
        }

        @Override
        protected CatalogFrameSendResult sendCatalogFrameResult(Session session, byte[] payload) {
            attempts++;
            return fail ? CatalogFrameSendResult.failure("CATALOG_PUBLICATION.SEND_FAILED") : CatalogFrameSendResult.success();
        }

        @Override
        public void sendError(Session session, String errorCode, String message) {
        }
    }

    private static final class FailureSender extends FlowPacketSender {
        private final String failureCode;
        private boolean fail = true;
        private int attempts;

        private FailureSender(Set<Session> sessions, String failureCode) {
            super(null, 0, sessions);
            this.failureCode = failureCode;
        }

        @Override
        protected CatalogFrameSendResult sendCatalogFrameResult(Session session, byte[] payload) {
            attempts++;
            return fail ? CatalogFrameSendResult.failure(failureCode) : CatalogFrameSendResult.success();
        }

        @Override
        public Optional<String> lastCatalogPublicationSendFailureCode() {
            return fail ? Optional.of(failureCode) : Optional.empty();
        }

        @Override
        public void sendError(Session session, String errorCode, String message) {
        }
    }

    private static final class LateJoiningSessions extends AbstractSet<Session> {
        private final Set<Session> sessions = ConcurrentHashMap.newKeySet();
        private final Session joined;
        private boolean joinedAdded;

        private LateJoiningSessions(Session initial, Session joined) {
            sessions.add(initial);
            this.joined = joined;
        }

        @Override
        public Iterator<Session> iterator() {
            List<Session> snapshot = new ArrayList<>(sessions);
            if (!joinedAdded) {
                joinedAdded = true;
                sessions.add(joined);
            }
            return snapshot.iterator();
        }

        @Override
        public int size() {
            return sessions.size();
        }

        @Override
        public boolean contains(Object value) {
            return sessions.contains(value);
        }
    }
}
