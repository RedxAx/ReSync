package restudio.resync.modules.flow;

import restudio.resync.Log;
import restudio.resync.core.Session;
import restudio.resync.flow.cache.CatalogCacheKey;
import restudio.resync.flow.cache.CatalogCachePublication;
import restudio.resync.flow.cache.CatalogCachePublicationTransport;
import restudio.resync.flow.cache.CatalogCacheSnapshot;
import restudio.resync.flow.cache.CatalogPublicationReceipt;
import restudio.resync.flow.cache.CatalogPublicationReceiptPacket;
import restudio.resync.flow.cache.CatalogPublicationReceiptReplay;
import restudio.resync.flow.cache.CatalogPublicationReceiptStore;
import restudio.resync.flow.catalog.CatalogRuntimeActivation;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.server.TemporaryLifecycleDiagnostics;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class FlowCatalogPublicationPacketHandler {
    private static final int MAX_SESSION_PUBLICATION_IDENTITIES = 2048;
    static final int MAX_DISPATCH_QUEUE = 64;
    static final int MAX_OUTBOX_ENTRIES = 256;
    static final long MAX_OUTBOX_BYTES = 64L * 1024L * 1024L;
    static final int MAX_DRAIN_FRAMES = 16;
    static final long MAX_DRAIN_BYTES = 512L * 1024L;
    private static final long PREWARM_RETRY_DELAY_MS = 250L;
    private static final long BACKPRESSURE_RETRY_DELAY_MS = 50L;
    private static final long TRANSIENT_RETRY_DELAY_MS = 1000L;
    private static final long MAX_RETRY_DELAY_MS = 30000L;
    private static final int MAX_RETRY_ATTEMPTS = 8;
    private final FlowPacketSender sender;
    private final CatalogCachePublicationTransport transport;
    private final CatalogPublicationReceiptStore receiptStore;
    private final Collection<Session> broadcastSessions;
    private final boolean broadcastSessionsBound;
    private final ConcurrentHashMap<Session, CatalogCachePublication> sentPublications = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Session, CatalogCachePublication> acknowledgedPublications = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Session, CatalogCachePublication> dispatchingPublications = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Session, CatalogCachePublication> pendingAcknowledgements = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Session> currentSessions = new ConcurrentHashMap<>();
    private final Set<Session> retiredSessions = ConcurrentHashMap.newKeySet();
    private final Object sessionOwnershipLock = new Object();
    private final Object admissionLock = new Object();
    private final Object outboxLock = new Object();
    private final ThreadPoolExecutor dispatchExecutor;
    private final Map<Session, DispatchWork> queuedInitialSessions = new IdentityHashMap<>();
    private final Map<Session, DispatchWork> queuedRequestSessions = new IdentityHashMap<>();
    private final List<DispatchWork> waitingDispatches = new ArrayList<>();
    private final List<OutboxEntry> outbox = new ArrayList<>();
    private long reservedOutboxBytes;
    private int reservedOutboxEntries;
    private int drainCursor;
    private boolean queuedRefresh;
    private volatile boolean shuttingDown;
    private volatile CatalogCachePublication pendingBroadcastPublication;
    private volatile String lastFailureCode = "";

    public record DiagnosticSnapshot(int queuedWork, int waitingWork, int executorQueue,
                                     int outboxEntries, long outboxBytes,
                                     int reservedOutboxEntries, long reservedOutboxBytes,
                                     String lastFailureCode, boolean shuttingDown) {
        public DiagnosticSnapshot {
            lastFailureCode = lastFailureCode == null ? "" : lastFailureCode;
        }
    }

    public FlowCatalogPublicationPacketHandler(FlowPacketSender sender, CatalogCachePublicationTransport transport) {
        throw new IllegalStateException("Catalog publication receipt store is required");
    }

    public FlowCatalogPublicationPacketHandler(FlowPacketSender sender, CatalogCachePublicationTransport transport,
                                               CatalogPublicationReceiptStore receiptStore) {
        this(sender, transport, receiptStore, null);
    }

    public FlowCatalogPublicationPacketHandler(FlowPacketSender sender, CatalogCachePublicationTransport transport,
                                               CatalogPublicationReceiptStore receiptStore,
                                               Collection<Session> broadcastSessions) {
        this.sender = Objects.requireNonNull(sender, "Catalog publication sender is required");
        this.transport = Objects.requireNonNull(transport, "Catalog publication transport is required");
        this.receiptStore = Objects.requireNonNull(receiptStore, "Catalog publication receipt store is required");
        this.broadcastSessions = broadcastSessions;
        this.broadcastSessionsBound = broadcastSessions != null;
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable, "resync-catalog-publication");
            thread.setDaemon(true);
            return thread;
        };
        this.dispatchExecutor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(MAX_DISPATCH_QUEUE), threadFactory, new ThreadPoolExecutor.AbortPolicy());
        garbageCollectRetiredReceipts();
    }

    public boolean enqueueInitial(Session session) {
        if (session == null || sessionOwner(session) == null || !registerCurrentSession(session)) {
            fail(session == null ? "CATALOG_PUBLICATION.RECEIPT_NO_SESSION"
                : sessionOwner(session) == null ? ownerFailureCode(session)
                : "CATALOG_PUBLICATION.RECEIPT_OWNER_STALE");
            return false;
        }
        return enqueueWork(DispatchWork.initial(session));
    }

    public boolean enqueueRefresh() {
        return enqueueWork(DispatchWork.refresh());
    }

    public int drainReady(long now) {
        if (shuttingDown) {
            return 0;
        }
        releaseDueWaitingDispatches(now);
        int sentFrames = 0;
        long sentBytes = 0L;
        while (sentFrames < MAX_DRAIN_FRAMES) {
            OutboxEntry entry = nextReadyOutboxEntry(now);
            if (entry == null) {
                return sentFrames;
            }
            int frameIndex = entry.nextFrame();
            long frameBytes;
            try {
                frameBytes = entry.plan().frameBytes(frameIndex);
            } catch (RuntimeException exception) {
                if (removeOutboxEntry(entry)) {
                    recordOutboxFailure(entry, "CATALOG_PUBLICATION.SEND_FAILED");
                }
                continue;
            }
            if (frameBytes > MAX_DRAIN_BYTES - sentBytes) {
                return sentFrames;
            }
            String failure = sendOutboxFrame(entry, frameIndex);
            if (failure == null) {
                advanceOutboxEntry(entry);
                clearBackpressureFailureIfSettled();
                sentFrames++;
                sentBytes += frameBytes;
                continue;
            }
            if (shuttingDown) {
                return sentFrames;
            }
            if (failure.endsWith(".SEND_BACKPRESSURE") && deferOutboxEntry(entry, now)) {
                fail(failure);
                continue;
            }
            if (!removeOutboxEntry(entry)) {
                continue;
            }
            if (isTransient(failure)) {
                if (!defer(entry.work(), TRANSIENT_RETRY_DELAY_MS)) {
                    recordOutboxFailure(entry, failure);
                } else {
                    fail(failure);
                }
            } else {
                recordOutboxFailure(entry, failure);
            }
        }
        return sentFrames;
    }

    public boolean hasPendingDispatches() {
        if (shuttingDown) {
            return false;
        }
        synchronized (admissionLock) {
            if (!queuedInitialSessions.isEmpty() || !queuedRequestSessions.isEmpty() || queuedRefresh
                || !waitingDispatches.isEmpty()
                || dispatchExecutor.getQueue().size() > 0) {
                return true;
            }
        }
        synchronized (outboxLock) {
            return !outbox.isEmpty() || reservedOutboxEntries > 0;
        }
    }

    public DiagnosticSnapshot diagnosticSnapshot() {
        int queuedWork;
        int waitingWork;
        int executorQueue;
        synchronized (admissionLock) {
            queuedWork = queuedInitialSessions.size() + queuedRequestSessions.size() + (queuedRefresh ? 1 : 0);
            waitingWork = waitingDispatches.size();
            executorQueue = dispatchExecutor.getQueue().size();
        }
        int outboxEntries;
        long outboxBytes;
        int reservedEntries;
        long reservedBytes;
        synchronized (outboxLock) {
            outboxEntries = outbox.size();
            outboxBytes = outboxBytes();
            reservedEntries = reservedOutboxEntries;
            reservedBytes = reservedOutboxBytes;
        }
        return new DiagnosticSnapshot(queuedWork, waitingWork, executorQueue, outboxEntries, outboxBytes,
            reservedEntries, reservedBytes, lastFailureCode, shuttingDown);
    }

    public List<String> pendingDispatchSessionIds() {
        Set<String> values = new HashSet<>();
        synchronized (admissionLock) {
            for (Session session : queuedInitialSessions.keySet()) {
                String key = sessionKey(session);
                if (!key.isBlank()) {
                    values.add(key);
                }
            }
            for (Session session : queuedRequestSessions.keySet()) {
                String key = sessionKey(session);
                if (!key.isBlank()) {
                    values.add(key);
                }
            }
            for (DispatchWork work : waitingDispatches) {
                String key = sessionKey(work.session());
                if (!key.isBlank()) {
                    values.add(key);
                }
            }
        }
        synchronized (outboxLock) {
            for (OutboxEntry entry : outbox) {
                String key = sessionKey(entry.session());
                if (!key.isBlank()) {
                    values.add(key);
                }
            }
        }
        return values.stream().sorted().toList();
    }

    public void shutdown() {
        if (shuttingDown) {
            return;
        }
        shuttingDown = true;
        List<OutboxEntry> abandoned;
        synchronized (outboxLock) {
            abandoned = List.copyOf(outbox);
            outbox.clear();
            reservedOutboxBytes = 0L;
            reservedOutboxEntries = 0;
            drainCursor = 0;
        }
        for (OutboxEntry entry : abandoned) {
            recordOutboxFailure(entry, "CATALOG_PUBLICATION.SHUTDOWN");
        }
        List<DispatchWork> abandonedWork;
        synchronized (admissionLock) {
            abandonedWork = List.copyOf(waitingDispatches);
            queuedInitialSessions.clear();
            queuedRequestSessions.clear();
            waitingDispatches.clear();
            queuedRefresh = false;
        }
        for (DispatchWork work : abandonedWork) {
            recordTerminalWorkFailure(work, "CATALOG_PUBLICATION.SHUTDOWN");
            fail("CATALOG_PUBLICATION.SHUTDOWN");
        }
        dispatchExecutor.shutdownNow();
    }

    public void close() {
        shutdown();
    }

    private boolean enqueueWork(DispatchWork work) {
        if (work == null || shuttingDown) {
            fail("CATALOG_PUBLICATION.SHUTDOWN");
            return false;
        }
        synchronized (admissionLock) {
            if (shuttingDown) {
                fail("CATALOG_PUBLICATION.SHUTDOWN");
                return false;
            }
            if (!reserveAdmission(work)) {
                return true;
            }
            try {
                dispatchExecutor.execute(() -> executeWork(work));
                return true;
            } catch (RejectedExecutionException exception) {
                releaseAdmission(work);
                fail("CATALOG_PUBLICATION.QUEUE_FULL");
                return false;
            }
        }
    }

    private boolean reserveAdmission(DispatchWork work) {
        if (work.type() == DispatchType.INITIAL) {
            if (queuedInitialSessions.containsKey(work.session())) {
                return false;
            }
            queuedInitialSessions.put(work.session(), work);
            return true;
        }
        if (work.type() == DispatchType.REQUEST) {
            if (queuedRequestSessions.containsKey(work.session())) {
                return false;
            }
            queuedRequestSessions.put(work.session(), work);
            return true;
        }
        if (work.type() == DispatchType.REFRESH) {
            if (queuedRefresh) {
                return false;
            }
            queuedRefresh = true;
            return true;
        }
        throw new IllegalStateException("Unsupported catalog publication dispatch type");
    }

    private void releaseAdmission(DispatchWork work) {
        if (work.type() == DispatchType.INITIAL) {
            queuedInitialSessions.remove(work.session(), work);
        } else if (work.type() == DispatchType.REQUEST) {
            queuedRequestSessions.remove(work.session(), work);
        } else if (work.type() == DispatchType.REFRESH) {
            queuedRefresh = false;
        }
    }

    private void executeWork(DispatchWork work) {
        long started = TemporaryLifecycleDiagnostics.start();
        String outcome = "complete";
        try {
            if (shuttingDown) {
                outcome = "shutdown";
                return;
            }
            switch (work.type()) {
                case INITIAL -> prepareInitial(work);
                case REQUEST -> prepareInitial(work);
                case REFRESH -> prepareRefresh(work);
            }
        } catch (RuntimeException exception) {
            outcome = "failed";
            String failure = exception.getMessage();
            fail(failure);
            deferOrRecord(work, TRANSIENT_RETRY_DELAY_MS, failure);
        } finally {
            synchronized (admissionLock) {
                releaseAdmission(work);
            }
            if (TemporaryLifecycleDiagnostics.enabled()) {
                DiagnosticSnapshot snapshot = diagnosticSnapshot();
                TemporaryLifecycleDiagnostics.event("catalog_prepare", started,
                    TemporaryLifecycleDiagnostics.with(Map.of("moduleId", "flow", "resourceType", "catalog", "operation", work.type().name(),
                        "outcome", outcome, "queuedCount", snapshot.queuedWork(), "waitingCount", snapshot.waitingWork(),
                        "outboxEntries", snapshot.outboxEntries(), "outboxBytes", snapshot.outboxBytes())));
            }
        }
    }

    private void prepareInitial(DispatchWork work) {
        Session session = work.session();
        if (session == null || !isCurrentSessionFast(session)) {
            return;
        }
        if (hasEquivalentOutbox(session, work.expectedKey())) {
            return;
        }
        if (hasOutboxForSession(session)) {
            deferOrRecord(work, TRANSIENT_RETRY_DELAY_MS, "CATALOG_PUBLICATION.SESSION_BUSY");
            return;
        }
        CatalogCachePublicationTransport.PreparationResult result;
        try {
            result = transport.prepareFullAsync(work.expectedKey()).join();
        } catch (RuntimeException exception) {
            fail("CATALOG_PUBLICATION.PROJECTION_REJECTED");
            deferOrRecord(work, TRANSIENT_RETRY_DELAY_MS, "CATALOG_PUBLICATION.PROJECTION_REJECTED");
            return;
        }
        handlePreparation(work, result, session);
    }

    private void prepareRefresh(DispatchWork work) {
        CatalogCachePublicationTransport.PreparationResult result;
        try {
            result = transport.prepareRefreshAsync().join();
        } catch (RuntimeException exception) {
            fail("CATALOG_PUBLICATION.PROJECTION_REJECTED");
            deferOrRecord(work, TRANSIENT_RETRY_DELAY_MS, "CATALOG_PUBLICATION.PROJECTION_REJECTED");
            return;
        }
        if (!result.ready()) {
            handlePreparation(work, result, null);
            return;
        }
        CatalogCachePublicationTransport.PreparedPublication prepared = result.prepared();
        List<Session> targets = broadcastTargetsForAsync();
        if (targets == null) {
            fail("CATALOG_PUBLICATION.RECEIPT_NO_SESSION");
            return;
        }
        prepareBroadcast(work, prepared, targets);
    }

    private void handlePreparation(DispatchWork work,
                                   CatalogCachePublicationTransport.PreparationResult result,
                                   Session session) {
        if (result == null) {
            fail("CATALOG_PUBLICATION.PROJECTION_REJECTED");
            deferOrRecord(work, TRANSIENT_RETRY_DELAY_MS, "CATALOG_PUBLICATION.PROJECTION_REJECTED");
            return;
        }
        if (!result.ready()) {
            fail(result.code());
            TemporaryLifecycleDiagnostics.event("catalog_prepare", 0L,
                TemporaryLifecycleDiagnostics.with(Map.of("moduleId", "flow", "resourceType", "catalog",
                    "operation", work.type().name(), "outcome", "not_ready", "attempt", work.attempts(),
                    "diagnosticCode", result.code(), "retryable", result.retryable())));
            if (result.retryable() || isTransient(result.code())) {
                deferOrRecord(work, result.code().endsWith("AUTHORING_NOT_READY")
                    ? PREWARM_RETRY_DELAY_MS : TRANSIENT_RETRY_DELAY_MS, result.code());
            } else {
                recordTerminalWorkFailure(work, result.code());
            }
            return;
        }
        if (session == null || !isCurrentSessionFast(session)) {
            return;
        }
        prepareSingle(work, result.prepared(), session);
    }

    private void prepareSingle(DispatchWork work,
                               CatalogCachePublicationTransport.PreparedPublication prepared,
                               Session session) {
        CatalogCachePublication publication = prepared.publication();
        Optional<FlowPacketSender.CatalogPublicationSendPlan> plan = preflightAsync(session, publication);
        if (plan.isEmpty()) {
            handleSendFailure(work, session, sender.lastCatalogPublicationSendFailureCode()
                .orElse("CATALOG_PUBLICATION.SEND_FAILED"));
            return;
        }
        FlowPacketSender.CatalogPublicationSendPlan sendPlan = plan.orElseThrow();
        TemporaryLifecycleDiagnostics.event("catalog_prepare", 0L,
            TemporaryLifecycleDiagnostics.with(Map.of("moduleId", "flow", "resourceType", "catalog",
                "operation", "prepare", "outcome", "ready", "publicationKey", sendPlan.publication().key().canonicalText(),
                "revision", sendPlan.publication().revision(), "authoring", sendPlan.publication().hasAuthoringPublication(),
                "frameCount", sendPlan.frameCount(), "bytes", sendPlan.retainedBytes())));
        if (hasEquivalentOutbox(session, sendPlan.publication(), sendPlan)) {
            return;
        }
        if (hasOutboxForSession(session)) {
            deferOrRecord(work, TRANSIENT_RETRY_DELAY_MS, "CATALOG_PUBLICATION.SESSION_BUSY");
            return;
        }
        OutboxReservation reservation = reserveOutbox(1, sendPlan.retainedBytes());
        if (reservation == null) {
            fail("CATALOG_PUBLICATION.OUTBOX_FULL");
            return;
        }
        if (shuttingDown) {
            releaseReservation(reservation);
            return;
        }
        if (!transport.commitAsyncPreparedPublication(prepared)) {
            releaseReservation(reservation);
            handlePreparationFailure(work, transport.lastFailureCode().orElse("CATALOG_PUBLICATION.ASYNC_STALE"));
            return;
        }
        CatalogRuntimeActivation.ActivationRecord expectedActivation = transport.committedActivationFor(publication)
            .orElse(prepared.activation());
        if (!recordDispatchIntent(session, sendPlan.publication())) {
            releaseReservation(reservation);
            handlePreparationFailure(work, lastFailureCode);
            return;
        }
        if (shuttingDown) {
            releaseReservation(reservation);
            recordDispatchFailure(session, sendPlan.publication(), "CATALOG_PUBLICATION.SHUTDOWN");
            return;
        }
        if (!activationStillCurrent(publication, expectedActivation)) {
            releaseReservation(reservation);
            recordDispatchFailure(session, sendPlan.publication(), "CATALOG_PUBLICATION.KEY_MISMATCH");
            handlePreparationFailure(work, "CATALOG_PUBLICATION.KEY_MISMATCH");
            return;
        }
        if (!isCurrentSessionFast(session)) {
            releaseReservation(reservation);
            recordDispatchFailure(session, sendPlan.publication(), "CATALOG_PUBLICATION.RECEIPT_OWNER_STALE");
            handlePreparationFailure(work, "CATALOG_PUBLICATION.RECEIPT_OWNER_STALE");
            return;
        }
        OutboxEntry entry = new OutboxEntry(work.type(), session, work, sessionOwner(session), publication,
            sendPlan.publication(), sendPlan, expectedActivation, reservation);
        if (!publishReserved(List.of(entry), reservation)) {
            releaseReservation(reservation);
            String failure = shuttingDown ? "CATALOG_PUBLICATION.SHUTDOWN" : "CATALOG_PUBLICATION.OUTBOX_FULL";
            recordDispatchFailure(session, sendPlan.publication(), failure);
            handlePreparationFailure(work, failure);
            return;
        }
        TemporaryLifecycleDiagnostics.event("catalog_outbox", 0L,
            TemporaryLifecycleDiagnostics.with(Map.of("moduleId", "flow", "resourceType", "catalog", "operation", "reserve",
                "outboxEntries", 1, "outboxBytes", sendPlan.retainedBytes(), "outcome", "published",
                "revision", publication.revision())));
        storePublicationIdentity(dispatchingPublications, session, sendPlan.publication());
    }

    private void prepareBroadcast(DispatchWork work,
                                  CatalogCachePublicationTransport.PreparedPublication prepared,
                                  List<Session> targets) {
        Map<Session, FlowPacketSender.CatalogPublicationSendPlan> plans = new LinkedHashMap<>();
        long bytes = 0L;
        for (Session session : targets) {
            if (session == null) {
                fail("CATALOG_PUBLICATION.RECEIPT_NO_SESSION");
                return;
            }
            if (sessionOwner(session) == null) {
                fail(ownerFailureCode(session));
                return;
            }
            if (!isCurrentSessionFast(session)) {
                fail("CATALOG_PUBLICATION.RECEIPT_OWNER_STALE");
                return;
            }
            Optional<FlowPacketSender.CatalogPublicationSendPlan> plan = preflightAsync(session,
                prepared.publication());
            if (plan.isEmpty()) {
                handlePreparationFailure(work, sender.lastCatalogPublicationSendFailureCode()
                    .orElse("CATALOG_PUBLICATION.SEND_FAILED"));
                return;
            }
            FlowPacketSender.CatalogPublicationSendPlan sendPlan = plan.orElseThrow();
            plans.put(session, sendPlan);
            try {
                bytes = Math.addExact(bytes, sendPlan.retainedBytes());
            } catch (ArithmeticException exception) {
                fail("CATALOG_PUBLICATION.OUTBOX_FULL");
                return;
            }
        }
        OutboxReservation reservation = reserveOutbox(plans.size(), bytes);
        if (reservation == null) {
            fail("CATALOG_PUBLICATION.OUTBOX_FULL");
            return;
        }
        if (shuttingDown) {
            releaseReservation(reservation);
            return;
        }
        if (!transport.commitAsyncPreparedPublication(prepared)) {
            releaseReservation(reservation);
            handlePreparationFailure(work, transport.lastFailureCode().orElse("CATALOG_PUBLICATION.ASYNC_STALE"));
            return;
        }
        if (!recordDispatchBatch(targets, prepared.publication())) {
            releaseReservation(reservation);
            handlePreparationFailure(work, lastFailureCode);
            return;
        }
        if (shuttingDown) {
            releaseReservation(reservation);
            for (Session session : targets) {
                FlowPacketSender.CatalogPublicationSendPlan plan = plans.get(session);
                if (plan != null) {
                    recordDispatchFailure(session, plan.publication(), "CATALOG_PUBLICATION.SHUTDOWN");
                }
            }
            return;
        }
        CatalogRuntimeActivation.ActivationRecord expectedActivation = transport.committedActivationFor(
            prepared.publication()).orElse(prepared.activation());
        if (!activationStillCurrent(prepared.publication(), expectedActivation) || !ensureCurrentSessionsFast(targets)) {
            releaseReservation(reservation);
            for (Session session : targets) {
                FlowPacketSender.CatalogPublicationSendPlan plan = plans.get(session);
                recordDispatchFailure(session, plan.publication(), "CATALOG_PUBLICATION.RECEIPT_OWNER_STALE");
            }
            handlePreparationFailure(work, "CATALOG_PUBLICATION.RECEIPT_OWNER_STALE");
            return;
        }
        List<OutboxEntry> entries = new ArrayList<>(plans.size());
        for (Session session : targets) {
            FlowPacketSender.CatalogPublicationSendPlan plan = plans.get(session);
            entries.add(new OutboxEntry(DispatchType.REFRESH, session, work, sessionOwner(session),
                prepared.publication(), plan.publication(), plan, expectedActivation, reservation));
            storePublicationIdentity(dispatchingPublications, session, plan.publication());
        }
        if (!publishReserved(entries, reservation)) {
            releaseReservation(reservation);
            String failure = shuttingDown ? "CATALOG_PUBLICATION.SHUTDOWN" : "CATALOG_PUBLICATION.OUTBOX_FULL";
            for (OutboxEntry entry : entries) {
                dispatchingPublications.remove(entry.session(), entry.publication());
                pendingAcknowledgements.remove(entry.session(), entry.publication());
                recordDispatchFailure(entry.session(), entry.publication(), failure);
            }
            handlePreparationFailure(work, failure);
            return;
        }
        TemporaryLifecycleDiagnostics.event("catalog_outbox", 0L,
            TemporaryLifecycleDiagnostics.with(Map.of("moduleId", "flow", "resourceType", "catalog", "operation", "reserve",
                "outboxEntries", entries.size(), "outboxBytes", bytes, "outcome", "published",
                "revision", prepared.publication().revision())));
    }

    private Optional<FlowPacketSender.CatalogPublicationSendPlan> preflightAsync(
        Session session, CatalogCachePublication publication
    ) {
        try {
            return sender.preflightCatalogCachePublicationAsync(session, publication).join();
        } catch (RuntimeException exception) {
            fail("CATALOG_PUBLICATION.SEND_ENCODE_FAILED");
            return Optional.empty();
        }
    }

    private void handlePreparationFailure(DispatchWork work, String code) {
        String failure = code == null || code.isBlank() ? "CATALOG_PUBLICATION.SEND_FAILED" : code;
        fail(failure);
        if (isTransient(failure) || "CATALOG_PUBLICATION.ASYNC_STALE".equals(failure)) {
            deferOrRecord(work, TRANSIENT_RETRY_DELAY_MS, failure);
        } else {
            recordTerminalWorkFailure(work, failure);
        }
    }

    private void handleSendFailure(DispatchWork work, Session session, String code) {
        String failure = code == null || code.isBlank() ? "CATALOG_PUBLICATION.SEND_FAILED" : code;
        fail(failure);
        if (isTransient(failure) || "CATALOG_PUBLICATION.RECEIPT_OWNER_STALE".equals(failure)) {
            deferOrRecord(work, TRANSIENT_RETRY_DELAY_MS, failure);
        } else {
            recordTerminalWorkFailure(work, failure);
        }
    }

    private List<Session> broadcastTargetsForAsync() {
        return broadcastSessionsBound ? broadcastTargets() : sender.subscribedSessionsSnapshot();
    }

    private OutboxReservation reserveOutbox(int entries, long bytes) {
        if (entries < 0 || bytes < 0L) {
            fail("CATALOG_PUBLICATION.OUTBOX_FULL");
            return null;
        }
        synchronized (outboxLock) {
            if (entries > MAX_OUTBOX_ENTRIES - reservedOutboxEntries
                || bytes > MAX_OUTBOX_BYTES - reservedOutboxBytes) {
                return null;
            }
            reservedOutboxEntries += entries;
            reservedOutboxBytes += bytes;
            return new OutboxReservation(entries, bytes);
        }
    }

    private void releaseReservation(OutboxReservation reservation) {
        if (reservation == null || reservation.released()) {
            return;
        }
        synchronized (outboxLock) {
            if (reservation.released()) {
                return;
            }
            reservedOutboxEntries = Math.max(0, reservedOutboxEntries - reservation.entries());
            reservedOutboxBytes = Math.max(0L, reservedOutboxBytes - reservation.bytes());
            reservation.release();
        }
    }

    private boolean publishReserved(List<OutboxEntry> entries, OutboxReservation reservation) {
        if (shuttingDown || entries == null || reservation == null || entries.size() != reservation.entries()) {
            return false;
        }
        synchronized (outboxLock) {
            if (reservation.released() || reservation.entries() > MAX_OUTBOX_ENTRIES - outbox.size()
                || reservation.bytes() > MAX_OUTBOX_BYTES - outboxBytes()) {
                return false;
            }
            for (OutboxEntry entry : entries) {
                if (entry == null || duplicateOutboxEntry(entry)) {
                    return false;
                }
            }
            outbox.addAll(entries);
            reservedOutboxEntries = Math.max(0, reservedOutboxEntries - reservation.entries());
            reservedOutboxBytes = Math.max(0L, reservedOutboxBytes - reservation.bytes());
            reservation.release();
            return true;
        }
    }

    private boolean duplicateOutboxEntry(OutboxEntry candidate) {
        return outbox.stream().anyMatch(existing -> existing.session() == candidate.session()
            && existing.publication().key().equals(candidate.publication().key())
            && existing.publication().revision() == candidate.publication().revision()
            && existing.publication().equals(candidate.publication()));
    }

    private boolean hasOutboxForSession(Session session) {
        if (session == null) {
            return false;
        }
        synchronized (outboxLock) {
            return outbox.stream().anyMatch(entry -> entry.session() == session);
        }
    }

    private boolean hasEquivalentQueuedWork(Session session, CatalogCacheKey expectedKey) {
        synchronized (admissionLock) {
            return equivalentWork(queuedInitialSessions.get(session), expectedKey)
                || equivalentWork(queuedRequestSessions.get(session), expectedKey);
        }
    }

    private static boolean equivalentWork(DispatchWork work, CatalogCacheKey expectedKey) {
        return work != null && (work.expectedKey() == null ? expectedKey == null : work.expectedKey().equals(expectedKey));
    }

    private boolean hasEquivalentOutbox(Session session, CatalogCacheKey expectedKey) {
        if (session == null) {
            return false;
        }
        synchronized (outboxLock) {
            return outbox.stream().anyMatch(entry -> entry.session() == session
                && (entry.type() == DispatchType.INITIAL || entry.type() == DispatchType.REQUEST)
                && entry.publication().kind() == CatalogCachePublication.Kind.FULL
                && (expectedKey == null || expectedKey.equals(entry.publication().key())));
        }
    }

    private boolean hasEquivalentOutbox(Session session, CatalogCachePublication publication,
                                        FlowPacketSender.CatalogPublicationSendPlan plan) {
        if (session == null || publication == null || plan == null) {
            return false;
        }
        synchronized (outboxLock) {
            return outbox.stream().anyMatch(entry -> entry.session() == session
                && entry.outboundPublication().equals(publication)
                && sameSendPlan(entry.plan(), plan));
        }
    }

    private static boolean sameSendPlan(FlowPacketSender.CatalogPublicationSendPlan first,
                                        FlowPacketSender.CatalogPublicationSendPlan second) {
        if (first == null || second == null || first.session() != second.session()
            || !first.publication().equals(second.publication()) || first.frameCount() != second.frameCount()
            || first.payloadBytes() != second.payloadBytes()) {
            return false;
        }
        return true;
    }

    private long outboxBytes() {
        long total = 0L;
        for (OutboxEntry entry : outbox) {
            total = Math.addExact(total, entry.plan().retainedBytes());
        }
        return total;
    }

    private boolean defer(DispatchWork work, long delay) {
        if (work == null || shuttingDown) {
            return false;
        }
        long now = System.currentTimeMillis();
        int attempts = work.attempts() == Integer.MAX_VALUE ? Integer.MAX_VALUE : work.attempts() + 1;
        if (attempts > MAX_RETRY_ATTEMPTS) {
            return false;
        }
        long retryDelay = delay == PREWARM_RETRY_DELAY_MS
            ? prewarmRetryDelay(attempts) : Math.max(delay, retryDelay(attempts));
        long retryAt = now > Long.MAX_VALUE - retryDelay ? Long.MAX_VALUE : now + retryDelay;
        DispatchWork next = work.retry(retryAt, attempts);
        synchronized (admissionLock) {
            if (waitingDispatches.stream().anyMatch(existing -> sameWork(existing, next))) {
                return true;
            }
            if (waitingDispatches.size() >= MAX_DISPATCH_QUEUE) {
                fail("CATALOG_PUBLICATION.QUEUE_FULL");
                return false;
            }
            waitingDispatches.add(next);
            return true;
        }
    }

    private void deferOrRecord(DispatchWork work, long delay, String code) {
        if (defer(work, delay)) {
            return;
        }
        String failure = code == null || code.isBlank() ? "CATALOG_PUBLICATION.SEND_FAILED" : code;
        recordTerminalWorkFailure(work, failure);
        fail(failure);
    }

    private void recordTerminalWorkFailure(DispatchWork work, String code) {
        if (work == null) {
            return;
        }
        if (work.type() == DispatchType.INITIAL || work.type() == DispatchType.REQUEST) {
            if (work.publication() != null) {
                recordMatchingWorkFailure(work.session(), work.publication(), code);
            }
            if (work.type() == DispatchType.REQUEST) {
                sendError(work.session(), "CATALOG_PUBLICATION_UNAVAILABLE");
            }
            return;
        }
        if (work.publication() == null) {
            return;
        }
        List<Session> targets = broadcastTargetsForAsync();
        if (targets == null) {
            return;
        }
        for (Session session : targets) {
            recordMatchingWorkFailure(session, work.publication(), code);
        }
    }

    private void recordMatchingWorkFailure(Session session, CatalogCachePublication publication, String code) {
        SessionOwner owner = sessionOwner(session);
        if (owner == null || publication == null) {
            return;
        }
        try {
            Optional<CatalogPublicationReceipt> baseline = receiptStore.baseline(owner.sessionKey());
            if (baseline.isEmpty() || !baseline.orElseThrow().matches(publication.key(), publication.revision())) {
                return;
            }
            CatalogPublicationReceipt.Transition transition = receiptStore.tryRecordDispatchFailure(
                owner.sessionKey(), owner.ownerToken(), publication, code);
            if (!transition.accepted()) {
                fail(transition.code());
            }
        } catch (RuntimeException exception) {
            fail("CATALOG_PUBLICATION.RECEIPT_PERSISTENCE_FAILED");
        }
    }

    private static boolean sameWork(DispatchWork first, DispatchWork second) {
        return first.type() == second.type() && first.session() == second.session()
            && Objects.equals(first.expectedKey(), second.expectedKey());
    }

    private static long retryDelay(int attempts) {
        long delay = TRANSIENT_RETRY_DELAY_MS;
        for (int index = 1; index < attempts && delay < MAX_RETRY_DELAY_MS; index++) {
            delay = Math.min(MAX_RETRY_DELAY_MS, delay * 2L);
        }
        return delay;
    }

    private static long prewarmRetryDelay(int attempts) {
        long delay = PREWARM_RETRY_DELAY_MS;
        for (int index = 1; index < attempts && delay < MAX_RETRY_DELAY_MS; index++) {
            delay = Math.min(MAX_RETRY_DELAY_MS, delay * 2L);
        }
        return delay;
    }

    private static boolean isTransient(String code) {
        if (code == null || code.isBlank()) {
            return true;
        }
        return code.endsWith(".SEND_UNAVAILABLE") || code.endsWith(".SEND_BACKPRESSURE") || code.endsWith(".SEND_FAILED")
            || code.endsWith(".PREPARATION_BUSY") || code.endsWith(".ASYNC_STALE")
            || code.endsWith(".AUTHORING_NOT_READY") || code.endsWith(".RECEIPT_PERSISTENCE_FAILED");
    }

    private void releaseDueWaitingDispatches(long now) {
        List<DispatchWork> due = new ArrayList<>();
        synchronized (admissionLock) {
            for (int index = waitingDispatches.size() - 1; index >= 0; index--) {
                DispatchWork waiting = waitingDispatches.get(index);
                if (waiting.retryAt() <= now) {
                    due.add(waiting);
                    waitingDispatches.remove(index);
                }
            }
        }
        for (DispatchWork waiting : due) {
            if (!enqueueWork(waiting)) {
                deferOrRecord(waiting, TRANSIENT_RETRY_DELAY_MS,
                    shuttingDown ? "CATALOG_PUBLICATION.SHUTDOWN" : "CATALOG_PUBLICATION.QUEUE_FULL");
            }
        }
    }

    private OutboxEntry nextReadyOutboxEntry(long now) {
        synchronized (outboxLock) {
            if (outbox.isEmpty()) {
                return null;
            }
            int size = outbox.size();
            int start = Math.floorMod(drainCursor, size);
            for (int offset = 0; offset < size; offset++) {
                int index = (start + offset) % size;
                OutboxEntry entry = outbox.get(index);
                if (!hasEarlierSessionEntry(index, entry.session())
                    && entry.retryAt() <= now && entry.nextFrame() < entry.plan().frameCount()) {
                    drainCursor = (index + 1) % size;
                    return entry;
                }
            }
            return null;
        }
    }

    private boolean hasEarlierSessionEntry(int index, Session session) {
        for (int previous = 0; previous < index; previous++) {
            if (outbox.get(previous).session() == session) {
                return true;
            }
        }
        return false;
    }

    private String sendOutboxFrame(OutboxEntry entry, int frameIndex) {
        try {
            AtomicReference<FlowPacketSender.CatalogFrameSendResult> sendResult = new AtomicReference<>();
            boolean sent = transport.withPublicationFence(entry.fencePublication(), entry.expectedActivation(), () -> {
                if (shuttingDown || !isCurrentSessionFast(entry.session())) {
                    return false;
                }
                FlowPacketSender.CatalogFrameSendResult result = sender.sendCatalogCachePublicationFrameResult(
                    entry.session(), entry.plan(), frameIndex);
                sendResult.set(result);
                return result.accepted();
            });
            if (sent) {
                return null;
            }
            if (shuttingDown) {
                return "CATALOG_PUBLICATION.SHUTDOWN";
            }
            if (!isCurrentSessionFast(entry.session())) {
                return "CATALOG_PUBLICATION.RECEIPT_OWNER_STALE";
            }
            FlowPacketSender.CatalogFrameSendResult result = sendResult.get();
            return result != null ? result.failureCode()
                : transport.lastFailureCode().orElse("CATALOG_PUBLICATION.SEND_FAILED");
        } catch (RuntimeException exception) {
            return "CATALOG_PUBLICATION.SEND_FAILED";
        }
    }

    private void advanceOutboxEntry(OutboxEntry entry) {
        boolean completed;
        synchronized (outboxLock) {
            if (!outbox.contains(entry)) {
                return;
            }
            entry.advance();
            completed = entry.nextFrame() >= entry.plan().frameCount();
            if (completed) {
                removeOutboxEntryLocked(entry);
            }
        }
        if (completed) {
            recordSentPublication(entry.session(), entry.outboundPublication());
            TemporaryLifecycleDiagnostics.event("catalog_outbox", 0L,
                TemporaryLifecycleDiagnostics.with(Map.of("moduleId", "flow", "resourceType", "catalog",
                    "operation", "send", "outcome", "sent", "publicationKey",
                    entry.outboundPublication().key().canonicalText(), "revision", entry.outboundPublication().revision(),
                    "frameCount", entry.plan().frameCount())));
        }
    }

    private boolean deferOutboxEntry(OutboxEntry entry, long now) {
        synchronized (outboxLock) {
            return outbox.contains(entry) && entry.defer(now);
        }
    }

    private void clearBackpressureFailureIfSettled() {
        if (!lastFailureCode.endsWith(".SEND_BACKPRESSURE")) {
            return;
        }
        synchronized (outboxLock) {
            if (lastFailureCode.endsWith(".SEND_BACKPRESSURE")
                && outbox.stream().noneMatch(OutboxEntry::backpressured)) {
                lastFailureCode = "";
            }
        }
    }

    private boolean removeOutboxEntry(OutboxEntry entry) {
        synchronized (outboxLock) {
            return removeOutboxEntryLocked(entry);
        }
    }

    private boolean removeOutboxEntryLocked(OutboxEntry entry) {
        if (entry == null || !outbox.remove(entry)) {
            return false;
        }
        if (drainCursor >= outbox.size()) {
            drainCursor = 0;
        }
        return true;
    }

    private List<OutboxEntry> removeOutboxEntries(Session session) {
        if (session == null) {
            return List.of();
        }
        synchronized (outboxLock) {
            List<OutboxEntry> removed = outbox.stream()
                .filter(entry -> entry.session() == session)
                .toList();
            if (removed.isEmpty()) {
                return List.of();
            }
            outbox.removeIf(entry -> entry.session() == session);
            if (drainCursor >= outbox.size()) {
                drainCursor = 0;
            }
            return removed;
        }
    }

    public void handleRequest(Session session, ByteBuffer buffer) {
        beginOperation();
        CatalogCacheKey expectedKey;
        try {
            expectedKey = expectedKey(buffer);
        } catch (RuntimeException exception) {
            fail("CATALOG_PUBLICATION.INVALID_REQUEST");
            sendError(session, "CATALOG_PUBLICATION_INVALID_REQUEST");
            return;
        }
        if (session == null || sessionOwner(session) == null || !registerCurrentSession(session)) {
            fail(session == null ? "CATALOG_PUBLICATION.RECEIPT_NO_SESSION" : ownerFailureCode(session));
            sendError(session, "CATALOG_PUBLICATION_UNAVAILABLE");
            return;
        }
        if (hasEquivalentQueuedWork(session, expectedKey) || hasEquivalentOutbox(session, expectedKey)) {
            return;
        }
        if (!enqueueWork(DispatchWork.request(session, expectedKey))) {
            sendError(session, "CATALOG_PUBLICATION_UNAVAILABLE");
        }
    }

    public boolean sendFull(Session session) {
        beginOperation();
        Optional<CatalogCachePublication> publication = transport.prepareFull();
        if (publication.isEmpty()) {
            fail(transport.lastFailureCode().orElse("CATALOG_PUBLICATION.UNAVAILABLE"));
            sendError(session, "CATALOG_PUBLICATION_UNAVAILABLE");
            return false;
        }
        boolean sent = sendPublication(session, publication.orElseThrow());
        if (!sent) {
            sendError(session, "CATALOG_PUBLICATION_UNAVAILABLE");
        }
        return sent;
    }

    public boolean broadcastFull() {
        beginOperation();
        Optional<CatalogCachePublication> publication = transport.prepareFull();
        if (publication.isEmpty()) {
            fail(transport.lastFailureCode().orElse("CATALOG_PUBLICATION.UNAVAILABLE"));
            return false;
        }
        Optional<CatalogCachePublication> selected = requireFullForAuthoring(publication.orElseThrow());
        return selected.isPresent() && broadcastPublication(selected.orElseThrow());
    }

    public boolean sendRefresh(Session session) {
        beginOperation();
        Optional<CatalogCachePublication> publication = transport.prepareRefresh();
        if (publication.isEmpty()) {
            fail(transport.lastFailureCode().orElse("CATALOG_PUBLICATION.UNAVAILABLE"));
            sendError(session, "CATALOG_PUBLICATION_UNAVAILABLE");
            return false;
        }
        Optional<CatalogCachePublication> selected = requireFullForAuthoring(session, publication.orElseThrow());
        if (selected.isEmpty()) {
            sendError(session, "CATALOG_PUBLICATION_UNAVAILABLE");
            return false;
        }
        boolean sent = sendPublication(session, selected.orElseThrow());
        if (!sent) {
            sendError(session, "CATALOG_PUBLICATION_UNAVAILABLE");
        }
        return sent;
    }

    public boolean broadcastRefresh() {
        beginOperation();
        Optional<CatalogCachePublication> publication = transport.prepareRefresh();
        if (publication.isEmpty()) {
            fail(transport.lastFailureCode().orElse("CATALOG_PUBLICATION.UNAVAILABLE"));
            return false;
        }
        Optional<CatalogCachePublication> selected = requireFullForAuthoring(publication.orElseThrow());
        return selected.isPresent() && broadcastPublication(selected.orElseThrow());
    }

    public boolean sendDelta(Session session, CatalogCacheSnapshot previous, CatalogCacheSnapshot current) {
        beginOperation();
        Optional<CatalogCachePublication> publication = transport.prepareDelta(previous, current);
        if (publication.isEmpty()) {
            fail(transport.lastFailureCode().orElse("CATALOG_PUBLICATION.DELTA_REJECTED"));
            sendError(session, "CATALOG_PUBLICATION_UNAVAILABLE");
            return false;
        }
        boolean sent = sendPublication(session, publication.orElseThrow());
        if (!sent) {
            sendError(session, "CATALOG_PUBLICATION_UNAVAILABLE");
        }
        return sent;
    }

    public boolean broadcastDelta(CatalogCacheSnapshot previous, CatalogCacheSnapshot current) {
        beginOperation();
        Optional<CatalogCachePublication> publication = transport.prepareDelta(previous, current);
        if (publication.isEmpty()) {
            fail(transport.lastFailureCode().orElse("CATALOG_PUBLICATION.DELTA_REJECTED"));
            return false;
        }
        return broadcastPublication(publication.orElseThrow());
    }

    public List<CatalogPublicationReceiptReplay> pendingReplays() {
        return receiptStore.pendingReplays();
    }

    public Optional<CatalogCachePublication> lastValidPublication() {
        return transport.lastValidPublication();
    }

    public Optional<CatalogCacheKey> activePublicationKey() {
        return transport.activeKey();
    }

    public Optional<CatalogPublicationReceipt> publicationReceipt(Session session) {
        return ownedBaseline(session);
    }

    public CatalogPublicationReceipt.Transition acknowledgeClientReceipt(Session session, CatalogCacheKey key, long revision) {
        SessionOwner owner = sessionOwner(session);
        if (owner == null) {
            return rejectedTransition(ownerFailureCode(session));
        }
        if (!isCurrentSession(session)) {
            return rejectedTransition("CATALOG_PUBLICATION.RECEIPT_OWNER_STALE");
        }
        try {
            return receiptStore.acknowledgeClientReceipt(owner.sessionKey(), owner.ownerToken(), key, revision);
        } catch (RuntimeException exception) {
            fail("CATALOG_PUBLICATION.RECEIPT_PERSISTENCE_FAILED");
            return rejectedTransition("CATALOG_PUBLICATION.RECEIPT_PERSISTENCE_FAILED");
        }
    }

    public CatalogPublicationReceipt.Transition acknowledgeCacheApplication(Session session, CatalogCacheKey key, long revision) {
        SessionOwner owner = sessionOwner(session);
        if (owner == null) {
            return rejectedTransition(ownerFailureCode(session));
        }
        if (!isCurrentSession(session)) {
            return rejectedTransition("CATALOG_PUBLICATION.RECEIPT_OWNER_STALE");
        }
        try {
            CatalogPublicationReceipt.Transition transition = receiptStore.acknowledgeCacheApplication(
                owner.sessionKey(), owner.ownerToken(), key, revision);
            if (transition.accepted()) {
                CatalogCachePublication sent = sentPublications.get(session);
                if (sent != null && sent.key().equals(key) && sent.revision() == revision) {
                    storePublicationIdentity(acknowledgedPublications, session, sent);
                } else {
                    CatalogCachePublication dispatching = dispatchingPublications.get(session);
                    if (dispatching != null && dispatching.key().equals(key) && dispatching.revision() == revision) {
                        storePublicationIdentity(pendingAcknowledgements, session, dispatching);
                    }
                }
            }
            return transition;
        } catch (RuntimeException exception) {
            fail("CATALOG_PUBLICATION.APPLICATION_PERSISTENCE_FAILED");
            return rejectedTransition("CATALOG_PUBLICATION.APPLICATION_PERSISTENCE_FAILED");
        }
    }

    public CatalogPublicationReceipt.Transition rejectCacheApplication(Session session, CatalogCacheKey key, long revision,
                                                                         String diagnosticCode) {
        SessionOwner owner = sessionOwner(session);
        if (owner == null) {
            return rejectedTransition(ownerFailureCode(session));
        }
        if (!isCurrentSession(session)) {
            return rejectedTransition("CATALOG_PUBLICATION.RECEIPT_OWNER_STALE");
        }
        try {
            return receiptStore.rejectCacheApplication(owner.sessionKey(), owner.ownerToken(), key, revision,
                diagnosticCode);
        } catch (RuntimeException exception) {
            fail("CATALOG_PUBLICATION.APPLICATION_PERSISTENCE_FAILED");
            return rejectedTransition("CATALOG_PUBLICATION.APPLICATION_PERSISTENCE_FAILED");
        }
    }

    public void handleReceipt(Session session, byte packetId, ByteBuffer payload) {
        CatalogPublicationReceiptPacket.Packet packet;
        try {
            packet = CatalogPublicationReceiptPacket.decode(packetId, payload);
        } catch (RuntimeException exception) {
            rejectReceipt(session, "CATALOG_PUBLICATION.RECEIPT_INVALID_PACKET");
            return;
        }
        CatalogPublicationReceipt.Transition transition;
        try {
            transition = switch (packet.kind()) {
                case CLIENT_RECEIVED -> acknowledgeClientReceipt(session, packet.key(), packet.revision());
                case CACHE_APPLIED -> acknowledgeCacheApplication(session, packet.key(), packet.revision());
                case CACHE_REJECTED -> rejectCacheApplication(session, packet.key(), packet.revision(), packet.diagnosticCode());
            };
        } catch (RuntimeException exception) {
            rejectReceipt(session, "CATALOG_PUBLICATION.RECEIPT_REJECTED");
            return;
        }
        if (!transition.accepted()) {
            rejectReceipt(session, transition.code());
        } else {
            TemporaryLifecycleDiagnostics.event("catalog_receipt", TemporaryLifecycleDiagnostics.start(),
                TemporaryLifecycleDiagnostics.with(Map.of("moduleId", "flow", "resourceType", "catalog", "operation", "handleReceipt",
                    "outcome", "accepted", "revision", packet.revision(), "receiptStatus", packet.kind().name())));
        }
    }

    public boolean recordBroadcastDispatch(Collection<Session> sessions) {
        CatalogCachePublication publication = pendingBroadcastPublication;
        if (publication == null) {
            publication = transport.lastValidPublication().orElse(null);
        }
        if (publication == null) {
            fail("CATALOG_PUBLICATION.RECEIPT_NO_PUBLICATION");
            return false;
        }
        if (sessions == null) {
            discardPendingBroadcast(publication);
            fail("CATALOG_PUBLICATION.RECEIPT_NO_SESSION");
            return false;
        }
        if (pendingBroadcastPublication != null) {
            if (!transport.commitPreparedPublication(publication)) {
                fail(transport.lastFailureCode().orElse("CATALOG_PUBLICATION.COMMIT_REJECTED"));
                transport.discardPreparedPublication(publication);
                pendingBroadcastPublication = null;
                return false;
            }
            pendingBroadcastPublication = null;
        }
        if (!recordDispatchBatch(sessions, publication)) {
            return false;
        }
        return true;
    }

    private boolean sendPublication(Session session, CatalogCachePublication publication) {
        if (!registerDispatchSession(session)) {
            return false;
        }
        if (!transport.validatesAgainstActive(publication)) {
            transport.discardPreparedPublication(publication);
            fail(transport.lastFailureCode().orElse("CATALOG_PUBLICATION.INVALID"));
            return false;
        }
        CatalogCachePublication outbound = publicationForSession(session, publication);
        if (!authoringReady(session, outbound)) {
            transport.discardPreparedPublication(publication);
            return false;
        }
        Optional<FlowPacketSender.CatalogPublicationSendPlan> prepared = sender.preflightCatalogCachePublication(session, outbound);
        if (prepared.isEmpty()) {
            transport.discardPreparedPublication(publication);
            fail(sender.lastCatalogPublicationSendFailureCode().orElse("CATALOG_PUBLICATION.SEND_FAILED"));
            return false;
        }
        FlowPacketSender.CatalogPublicationSendPlan sendPlan = prepared.orElseThrow();
        if (!matchesSendPlan(session, outbound, sendPlan)) {
            transport.discardPreparedPublication(publication);
            fail("CATALOG_PUBLICATION.SEND_PLAN_MISMATCH");
            return false;
        }
        if (session != null) {
            if (!ensureCurrentSessionFast(session)) {
                transport.discardPreparedPublication(publication);
                fail("CATALOG_PUBLICATION.RECEIPT_OWNER_STALE");
                return false;
            }
        }
        if (!transport.commitPreparedPublication(publication)) {
            fail(transport.lastFailureCode().orElse("CATALOG_PUBLICATION.COMMIT_REJECTED"));
            transport.discardPreparedPublication(publication);
            return false;
        }
        storePublicationIdentity(dispatchingPublications, session, outbound);
        if (!recordDispatchIntent(session, outbound)) {
            if (session != null) {
                dispatchingPublications.remove(session, outbound);
                pendingAcknowledgements.remove(session, outbound);
            }
            return false;
        }
        CatalogRuntimeActivation.ActivationRecord expectedActivation = transport.committedActivationFor(publication)
            .orElse(null);
        if (!sendPublicationFrames(session, publication, sendPlan, expectedActivation)) {
            if (session != null) {
                dispatchingPublications.remove(session, outbound);
                pendingAcknowledgements.remove(session, outbound);
            }
            recordDispatchFailure(session, outbound, lastFailureCode);
            return false;
        }
        recordSentPublication(session, outbound);
        return true;
    }

    private boolean broadcastPublication(CatalogCachePublication publication) {
        if (!transport.validatesAgainstActive(publication)) {
            transport.discardPreparedPublication(publication);
            fail(transport.lastFailureCode().orElse("CATALOG_PUBLICATION.INVALID"));
            return false;
        }
        List<Session> targets = broadcastSessionsBound ? broadcastTargets() : sender.subscribedSessionsSnapshot();
        if (targets == null) {
            transport.discardPreparedPublication(publication);
            fail("CATALOG_PUBLICATION.RECEIPT_NO_SESSION");
            return false;
        }
        Map<Session, FlowPacketSender.CatalogPublicationSendPlan> preparedPlans = new LinkedHashMap<>();
        for (Session target : targets) {
            if (target == null || !registerDispatchSession(target) || !ensureCurrentSessionFast(target)) {
                transport.discardPreparedPublication(publication);
                fail(target == null ? "CATALOG_PUBLICATION.SEND_UNAVAILABLE"
                    : "CATALOG_PUBLICATION.RECEIPT_OWNER_STALE");
                return false;
            }
            CatalogCachePublication outbound = publicationForSession(target, publication);
            if (!authoringReady(target, outbound)) {
                transport.discardPreparedPublication(publication);
                return false;
            }
            Optional<FlowPacketSender.CatalogPublicationSendPlan> prepared = sender.preflightCatalogCachePublication(target, outbound);
            if (prepared.isEmpty()) {
                transport.discardPreparedPublication(publication);
                fail(sender.lastCatalogPublicationSendFailureCode().orElse("CATALOG_PUBLICATION.SEND_FAILED"));
                return false;
            }
            FlowPacketSender.CatalogPublicationSendPlan plan = prepared.orElseThrow();
            if (!matchesSendPlan(target, outbound, plan)) {
                transport.discardPreparedPublication(publication);
                fail("CATALOG_PUBLICATION.SEND_PLAN_MISMATCH");
                return false;
            }
            preparedPlans.put(target, plan);
        }
        if (!transport.commitPreparedPublication(publication)) {
            fail(transport.lastFailureCode().orElse("CATALOG_PUBLICATION.COMMIT_REJECTED"));
            transport.discardPreparedPublication(publication);
            return false;
        }
        for (Session target : targets) {
            storePublicationIdentity(dispatchingPublications, target, preparedPlans.get(target).publication());
        }
        if (!recordDispatchBatch(targets, publication, preparedPlans)) {
            for (Session target : targets) {
                dispatchingPublications.remove(target, preparedPlans.get(target).publication());
                pendingAcknowledgements.remove(target, preparedPlans.get(target).publication());
            }
            return false;
        }
        CatalogRuntimeActivation.ActivationRecord expectedActivation = transport.committedActivationFor(publication)
            .orElse(null);
        if (!activationStillCurrent(publication, expectedActivation) || !ensureCurrentSessionsFast(targets)) {
            for (Session target : targets) {
                FlowPacketSender.CatalogPublicationSendPlan plan = preparedPlans.get(target);
                dispatchingPublications.remove(target, plan.publication());
                pendingAcknowledgements.remove(target, plan.publication());
                recordDispatchFailure(target, plan.publication(), "CATALOG_PUBLICATION.RECEIPT_OWNER_STALE");
            }
            fail("CATALOG_PUBLICATION.RECEIPT_OWNER_STALE");
            return false;
        }
        return sendBroadcastTargets(targets, publication, preparedPlans, expectedActivation);
    }

    public Optional<String> lastFailureCode() {
        return lastFailureCode.isBlank() ? Optional.empty() : Optional.of(lastFailureCode);
    }

    private CatalogCacheKey expectedKey(ByteBuffer buffer) {
        if (buffer == null || !buffer.hasRemaining()) {
            return null;
        }
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (text.isBlank()) {
            return null;
        }
        CatalogCacheKey key = CatalogCacheKey.parseCanonicalText(text);
        if (!key.canonicalText().equals(text)) {
            throw new IllegalArgumentException("Catalog publication request key is not canonical");
        }
        return key;
    }

    private void beginOperation() {
        pendingBroadcastPublication = null;
        lastFailureCode = "";
    }

    private void fail(String code) {
        lastFailureCode = code == null || code.isBlank() ? "CATALOG_PUBLICATION.SEND_FAILED" : code;
    }

    private void sendError(Session session, String code) {
        if (session != null) {
            try {
                sender.sendError(session, code, lastFailureCode.isBlank() ? code : lastFailureCode);
            } catch (RuntimeException exception) {
                fail("CATALOG_PUBLICATION.SEND_FAILED");
            }
        }
    }

    private void rejectReceipt(Session session, String code) {
        String diagnostic = code == null || code.isBlank() ? "CATALOG_PUBLICATION.RECEIPT_REJECTED" : code;
        fail(diagnostic);
        sendError(session, "CATALOG_PUBLICATION_RECEIPT_REJECTED");
    }

    private boolean recordDispatchIntent(Session session, CatalogCachePublication publication) {
        if (session == null) {
            return true;
        }
        SessionOwner owner = sessionOwner(session);
        if (owner == null) {
            fail(ownerFailureCode(session));
            return false;
        }
        if (!ensureCurrentSession(session)) {
            return false;
        }
        if (!claimOrAdopt(owner, true, publication)) {
            return false;
        }
        try {
            CatalogPublicationReceipt.Transition transition = receiptStore.tryRecordDispatch(
                owner.sessionKey(), owner.ownerToken(), publication);
            if (!transition.accepted()) {
                fail(transition.code());
                return false;
            }
            TemporaryLifecycleDiagnostics.event("catalog_receipt", 0L,
                TemporaryLifecycleDiagnostics.with(Map.of("moduleId", "flow", "resourceType", "catalog", "operation", "dispatch",
                    "outcome", "recorded", "revision", publication.revision(), "receiptStatus", "dispatch")));
            return true;
        } catch (RuntimeException exception) {
            fail("CATALOG_PUBLICATION.RECEIPT_PERSISTENCE_FAILED");
            return false;
        }
    }

    private boolean recordDispatchBatch(Collection<Session> sessions, CatalogCachePublication publication) {
        return recordDispatchBatchLocked(sessions, publication);
    }

    private boolean recordDispatchBatchLocked(Collection<Session> sessions, CatalogCachePublication publication) {
        Map<String, String> sessionOwners = new LinkedHashMap<>();
        for (Session session : sessions) {
            SessionOwner owner = sessionOwner(session);
            if (owner == null) {
                fail(ownerFailureCode(session));
                return false;
            }
            if (!isCurrentSession(session)) {
                fail("CATALOG_PUBLICATION.RECEIPT_OWNER_STALE");
                return false;
            }
            if (sessionOwners.putIfAbsent(owner.sessionKey(), owner.ownerToken()) != null) {
                fail("CATALOG_PUBLICATION.RECEIPT_DUPLICATE_SESSION");
                return false;
            }
        }
        for (Map.Entry<String, String> entry : sessionOwners.entrySet()) {
            if (!claimOrAdopt(new SessionOwner(entry.getKey(), entry.getValue()), true, publication)) {
                return false;
            }
        }
        try {
            CatalogPublicationReceipt.BatchTransition transition = receiptStore.tryRecordDispatchBatch(
                sessionOwners, publication);
            if (!transition.accepted()) {
                fail(transition.code());
                return false;
            }
            TemporaryLifecycleDiagnostics.event("catalog_receipt", 0L,
                TemporaryLifecycleDiagnostics.with(Map.of("moduleId", "flow", "resourceType", "catalog", "operation", "dispatchBatch",
                    "outcome", "recorded", "revision", publication.revision(), "receiptStatus", "dispatch",
                    "count", sessionOwners.size())));
            return true;
        } catch (RuntimeException exception) {
            fail("CATALOG_PUBLICATION.RECEIPT_PERSISTENCE_FAILED");
            return false;
        }
    }

    private boolean recordDispatchBatch(Collection<Session> sessions, CatalogCachePublication publication,
                                        Map<Session, FlowPacketSender.CatalogPublicationSendPlan> preparedPlans) {
        CatalogCachePublication receiptPublication = publication;
        boolean first = true;
        for (Session session : sessions) {
            FlowPacketSender.CatalogPublicationSendPlan plan = preparedPlans.get(session);
            if (plan == null || !sameReceiptIdentity(publication, plan.publication())) {
                fail("CATALOG_PUBLICATION.SEND_PLAN_MISMATCH");
                return false;
            }
            if (first) {
                receiptPublication = plan.publication();
                first = false;
            }
        }
        return recordDispatchBatchLocked(sessions, receiptPublication);
    }

    private boolean sendPublicationFrames(Session session, CatalogCachePublication fencePublication,
                                          FlowPacketSender.CatalogPublicationSendPlan plan,
                                          CatalogRuntimeActivation.ActivationRecord expectedActivation) {
        for (int frameIndex = 0; frameIndex < plan.frameCount(); frameIndex++) {
            int currentFrame = frameIndex;
            boolean sent;
            try {
                sent = transport.withPublicationFence(fencePublication, expectedActivation, () ->
                    (session == null || isCurrentSessionFast(session))
                        && sender.sendCatalogCachePublicationFrame(session, plan, currentFrame));
            } catch (RuntimeException exception) {
                fail("CATALOG_PUBLICATION.SEND_FAILED");
                return false;
            }
            if (!sent) {
                String failure = session != null && !isCurrentSessionFast(session)
                    ? "CATALOG_PUBLICATION.RECEIPT_OWNER_STALE"
                    : sender.lastCatalogPublicationSendFailureCode()
                        .orElse(transport.lastFailureCode().orElse("CATALOG_PUBLICATION.SEND_FAILED"));
                fail(failure);
                return false;
            }
        }
        return true;
    }

    private boolean sendBroadcastTargets(Collection<Session> targets, CatalogCachePublication publication,
                                          Map<Session, FlowPacketSender.CatalogPublicationSendPlan> preparedPlans,
                                          CatalogRuntimeActivation.ActivationRecord expectedActivation) {
        String firstFailure = null;
        for (Session target : targets) {
            FlowPacketSender.CatalogPublicationSendPlan prepared = preparedPlans.get(target);
            CatalogCachePublication outbound = prepared == null ? null : prepared.publication();
            if (outbound == null) {
                recordDispatchFailure(target, publication, "CATALOG_PUBLICATION.SEND_UNAVAILABLE");
                if (firstFailure == null) {
                    firstFailure = "CATALOG_PUBLICATION.SEND_UNAVAILABLE";
                }
                continue;
            }
            if (!isCurrentSessionFast(target)) {
                if (firstFailure == null) {
                    firstFailure = "CATALOG_PUBLICATION.RECEIPT_OWNER_STALE";
                }
                continue;
            }
            if (sendPublicationFrames(target, publication, prepared, expectedActivation)) {
                recordSentPublication(target, outbound);
                continue;
            }
            dispatchingPublications.remove(target, outbound);
            pendingAcknowledgements.remove(target, outbound);
            String failure = lastFailureCode.isBlank() ? sender.lastCatalogPublicationSendFailureCode()
                .orElse("CATALOG_PUBLICATION.SEND_FAILED") : lastFailureCode;
            recordDispatchFailure(target, outbound, failure);
            if (firstFailure == null) {
                firstFailure = failure;
            }
        }
        if (firstFailure == null) {
            return true;
        }
        fail(firstFailure);
        return false;
    }

    private boolean authoringReady(Session session, CatalogCachePublication publication) {
        if (!sender.supportsCatalogAuthoring(session)) {
            return true;
        }
        if (!transport.catalogAuthoringAvailable() || publication == null || !publication.hasAuthoringPublication()) {
            fail("CATALOG_PUBLICATION.AUTHORING_NOT_READY");
            return false;
        }
        return true;
    }

    private Optional<CatalogCachePublication> requireFullForAuthoring(Session session,
                                                                       CatalogCachePublication publication) {
        if (!requiresFullForAuthoring(session, publication)) {
            return Optional.of(publication);
        }
        if (!transport.catalogAuthoringAvailable()) {
            transport.discardPreparedPublication(publication);
            fail("CATALOG_PUBLICATION.AUTHORING_NOT_READY");
            return Optional.empty();
        }
        transport.discardPreparedPublication(publication);
        Optional<CatalogCachePublication> full = transport.prepareFull();
        if (full.isEmpty() || !full.orElseThrow().hasAuthoringPublication()) {
            fail(full.isEmpty() ? transport.lastFailureCode().orElse("CATALOG_PUBLICATION.AUTHORING_NOT_READY")
                : "CATALOG_PUBLICATION.AUTHORING_NOT_READY");
            return Optional.empty();
        }
        return full;
    }

    private Optional<CatalogCachePublication> requireFullForAuthoring(CatalogCachePublication publication) {
        if (publication.kind() != CatalogCachePublication.Kind.DELTA || publication.hasAuthoringPublication()
            || !broadcastAuthoringTargetPresent()) {
            return Optional.of(publication);
        }
        if (!transport.catalogAuthoringAvailable()) {
            transport.discardPreparedPublication(publication);
            fail("CATALOG_PUBLICATION.AUTHORING_NOT_READY");
            return Optional.empty();
        }
        transport.discardPreparedPublication(publication);
        Optional<CatalogCachePublication> full = transport.prepareFull();
        if (full.isEmpty() || !full.orElseThrow().hasAuthoringPublication()) {
            fail(full.isEmpty() ? transport.lastFailureCode().orElse("CATALOG_PUBLICATION.AUTHORING_NOT_READY")
                : "CATALOG_PUBLICATION.AUTHORING_NOT_READY");
            return Optional.empty();
        }
        return full;
    }

    private boolean requiresFullForAuthoring(Session session, CatalogCachePublication publication) {
        return publication.kind() == CatalogCachePublication.Kind.DELTA
            && sender.supportsCatalogAuthoring(session)
            && !publication.hasAuthoringPublication();
    }

    private boolean broadcastAuthoringTargetPresent() {
        List<Session> targets = broadcastSessionsBound ? broadcastTargets() : sender.subscribedSessionsSnapshot();
        return targets != null && targets.stream().anyMatch(sender::supportsCatalogAuthoring);
    }

    private CatalogCachePublication publicationForSession(Session session, CatalogCachePublication publication) {
        if (!sender.supportsCatalogAuthoring(session)) {
            return publication.hasAuthoringPublication() ? publication.withAuthoringPublication(null) : publication;
        }
        return transport.publicationForSession(publication, acknowledgedCapabilities(session));
    }

    private Set<ContractRef<CapabilityId>> acknowledgedCapabilities(Session session) {
        if (session == null || session.getConnection() == null || session.getConnection().getClientCapabilities() == null) {
            return Set.of();
        }
        Set<ContractRef<CapabilityId>> capabilities = new HashSet<>();
        for (String value : session.getConnection().getClientCapabilities()) {
            if (value == null || value.isBlank() || !value.contains("/")) {
                continue;
            }
            try {
                capabilities.add(ContractRef.parseCanonicalText(value, CapabilityId::new));
            } catch (RuntimeException exception) {
                continue;
            }
        }
        return Set.copyOf(capabilities);
    }

    private void recordSentPublication(Session session, CatalogCachePublication publication) {
        if (session != null && publication != null) {
            if (!publication.equals(dispatchingPublications.get(session))) {
                return;
            }
            storePublicationIdentity(sentPublications, session, publication);
            dispatchingPublications.remove(session, publication);
            CatalogCachePublication pending = pendingAcknowledgements.get(session);
            if (publication.equals(pending)) {
                storePublicationIdentity(acknowledgedPublications, session, publication);
                pendingAcknowledgements.remove(session, pending);
            }
        }
    }

    private void storePublicationIdentity(Map<Session, CatalogCachePublication> identities,
                                          Session session, CatalogCachePublication publication) {
        if (session == null || publication == null) {
            return;
        }
        synchronized (identities) {
            identities.put(session, publication);
            trimPublicationIdentities(identities);
        }
    }

    private void trimPublicationIdentities(Map<Session, CatalogCachePublication> identities) {
        while (identities.size() > MAX_SESSION_PUBLICATION_IDENTITIES) {
            Session eldest = identities.keySet().stream().findFirst().orElse(null);
            if (eldest == null || identities.remove(eldest) == null) {
                return;
            }
        }
    }

    private List<Session> broadcastTargets() {
        List<Session> targets = new ArrayList<>();
        for (Session session : broadcastSessions) {
            if (session == null || sessionKey(session).isBlank()) {
                return null;
            }
            targets.add(session);
        }
        return targets;
    }

    public void cleanupSession(Session session) {
        if (session == null) {
            return;
        }
        try {
            SessionOwner owner = sessionOwner(session);
            String canonicalKey = owner == null ? sessionKey(session) : owner.sessionKey();
            boolean current;
            synchronized (sessionOwnershipLock) {
                sentPublications.remove(session);
                acknowledgedPublications.remove(session);
                dispatchingPublications.remove(session);
                pendingAcknowledgements.remove(session);
                current = canonicalKey.isBlank() || isCurrentSession(session);
                if (!canonicalKey.isBlank()) {
                    if (current) {
                        currentSessions.remove(canonicalKey, session);
                    }
                }
                retireSession(session);
            }
            for (OutboxEntry entry : removeOutboxEntries(session)) {
                recordOutboxFailure(entry, "CATALOG_PUBLICATION.RECEIPT_OWNER_STALE");
            }
            if (!current) {
                return;
            }
            boolean canonicalBaselinePresent = !canonicalKey.isBlank() && receiptStore.baseline(canonicalKey).isPresent();
            if (owner != null) {
                removeConverged(canonicalKey, owner.ownerToken());
            }
            String sessionId = session.getSessionId();
            if (sessionId != null && !sessionId.isBlank() && !sessionId.equals(canonicalKey)) {
                String retiredKey = sessionId.strip();
                if (owner != null) {
                    if (canonicalBaselinePresent) {
                        removeOwnedReceipt(retiredKey, owner.ownerToken());
                    } else {
                        removeConverged(retiredKey, owner.ownerToken());
                    }
                }
            }
        } catch (RuntimeException exception) {
            fail("CATALOG_PUBLICATION.RECEIPT_PERSISTENCE_FAILED");
        }
    }

    public void resetSession(Session session) {
        if (session == null) {
            return;
        }
        if (!registerCurrentSession(session)) {
            return;
        }
        synchronized (sessionOwnershipLock) {
            sentPublications.remove(session);
            acknowledgedPublications.remove(session);
            dispatchingPublications.remove(session);
            pendingAcknowledgements.remove(session);
        }
    }

    public Optional<CatalogCachePublication> catalogPublicationForSession(Session session) {
        SessionOwner owner = sessionOwner(session);
        if (owner == null) {
            return Optional.empty();
        }
        Optional<CatalogPublicationReceipt> receipt = ownedBaseline(session);
        if (receipt.isEmpty() || !receipt.orElseThrow().converged()) {
            return Optional.empty();
        }
        Optional<CatalogCacheKey> activeKey = transport.activeKey();
        if (activeKey.isEmpty() || !activeKey.orElseThrow().equals(receipt.orElseThrow().publicationKey())) {
            return Optional.empty();
        }
        CatalogCachePublication publication = acknowledgedPublications.get(session);
        if (publication == null || !publication.key().equals(receipt.orElseThrow().publicationKey())
            || publication.revision() != receipt.orElseThrow().revision()) {
            return Optional.empty();
        }
        return Optional.of(publication);
    }

    private void garbageCollectRetiredReceipts() {
        try {
            for (CatalogPublicationReceipt receipt : receiptStore.baselines()) {
                if (receipt.converged() && receipt.ownerState() == CatalogPublicationReceipt.OwnerState.CLAIMED
                    && ephemeralSessionKey(receipt.sessionKey())) {
                    receiptStore.remove(receipt.sessionKey(), receipt.ownerToken());
                }
            }
        } catch (RuntimeException exception) {
            fail("CATALOG_PUBLICATION.RECEIPT_PERSISTENCE_FAILED");
        }
    }

    private void removeConverged(String key, String ownerToken) {
        if (key == null || key.isBlank() || ownerToken == null || ownerToken.isBlank()) {
            return;
        }
        receiptStore.baseline(key).filter(CatalogPublicationReceipt::converged)
            .filter(receipt -> receipt.ownerState() == CatalogPublicationReceipt.OwnerState.CLAIMED
                && ownerToken.equals(receipt.ownerToken()))
            .ifPresent(receipt -> receiptStore.remove(receipt.sessionKey(), ownerToken));
    }

    private void removeOwnedReceipt(String key, String ownerToken) {
        if (key == null || key.isBlank() || ownerToken == null || ownerToken.isBlank()) {
            return;
        }
        receiptStore.remove(key, ownerToken);
    }

    private static boolean ephemeralSessionKey(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private void discardPendingBroadcast(CatalogCachePublication publication) {
        if (pendingBroadcastPublication != null && pendingBroadcastPublication.equals(publication)) {
            transport.discardPreparedPublication(publication);
            pendingBroadcastPublication = null;
        }
    }

    private void recordDispatchFailure(Session session, CatalogCachePublication publication, String code) {
        SessionOwner owner = sessionOwner(session);
        if (owner == null || !isCurrentSession(session)) {
            return;
        }
        try {
            CatalogPublicationReceipt.Transition transition = receiptStore.tryRecordDispatchFailure(
                owner.sessionKey(), owner.ownerToken(), publication, code);
            if (!transition.accepted()) {
                fail(transition.code());
            }
        } catch (RuntimeException exception) {
            fail("CATALOG_PUBLICATION.RECEIPT_PERSISTENCE_FAILED");
        }
    }

    private void recordOutboxFailure(OutboxEntry entry, String code) {
        if (entry == null) {
            return;
        }
        dispatchingPublications.remove(entry.session(), entry.publication());
        pendingAcknowledgements.remove(entry.session(), entry.publication());
        String failure = code == null || code.isBlank() ? "CATALOG_PUBLICATION.SEND_FAILED" : code;
        try {
            CatalogPublicationReceipt.Transition transition = receiptStore.tryRecordDispatchFailure(
                entry.owner().sessionKey(), entry.owner().ownerToken(), entry.outboundPublication(), failure);
            if (!transition.accepted()) {
                fail(transition.code());
                return;
            }
            fail(failure);
        } catch (RuntimeException exception) {
            fail("CATALOG_PUBLICATION.RECEIPT_PERSISTENCE_FAILED");
        }
    }

    private static CatalogPublicationReceipt.Transition rejectedTransition(String code) {
        return new CatalogPublicationReceipt.Transition(false, code, Optional.empty());
    }

    private boolean ensureCurrentSession(Session session) {
        if (session == null) {
            return true;
        }
        if (isCurrentSession(session)) {
            return true;
        }
        fail("CATALOG_PUBLICATION.RECEIPT_OWNER_STALE");
        return false;
    }

    private boolean ensureCurrentSessionFast(Session session) {
        if (session == null || isCurrentSessionFast(session)) {
            return true;
        }
        fail("CATALOG_PUBLICATION.RECEIPT_OWNER_STALE");
        return false;
    }

    private boolean ensureCurrentSessionsFast(Collection<Session> sessions) {
        for (Session session : sessions) {
            if (!isCurrentSessionFast(session)) {
                fail("CATALOG_PUBLICATION.RECEIPT_OWNER_STALE");
                return false;
            }
        }
        return true;
    }

    private boolean isCurrentSessionFast(Session session) {
        if (session == null) {
            return false;
        }
        String key = sessionKey(session);
        return !key.isBlank() && currentSessions.get(key) == session && !retiredSessions.contains(session);
    }

    private boolean activationStillCurrent(CatalogCachePublication publication,
                                            CatalogRuntimeActivation.ActivationRecord expectedActivation) {
        try {
            return transport.withPublicationFence(publication, expectedActivation, () -> true);
        } catch (RuntimeException exception) {
            fail("CATALOG_PUBLICATION.KEY_MISMATCH");
            return false;
        }
    }

    private static boolean sameReceiptIdentity(CatalogCachePublication first, CatalogCachePublication second) {
        return first != null && second != null && first.key().equals(second.key())
            && first.revision() == second.revision();
    }

    private static boolean matchesSendPlan(Session session, CatalogCachePublication publication,
                                           FlowPacketSender.CatalogPublicationSendPlan plan) {
        return plan != null && plan.session() == session && publication.equals(plan.publication());
    }

    private Optional<CatalogPublicationReceipt> ownedBaseline(Session session) {
        SessionOwner owner = sessionOwner(session);
        if (owner == null || !isCurrentSession(session)) {
            return Optional.empty();
        }
        try {
            return receiptStore.baseline(owner.sessionKey())
                .filter(receipt -> receipt.ownerState() == CatalogPublicationReceipt.OwnerState.CLAIMED
                    && owner.ownerToken().equals(receipt.ownerToken()));
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    private boolean claimOrAdopt(SessionOwner owner, boolean allowMissingBaseline,
                                 CatalogCachePublication publication) {
        try {
            CatalogPublicationReceipt.Claim claim = receiptStore.claim(owner.sessionKey(), owner.ownerToken());
            if (claim.accepted()) {
                return true;
            }
            if ("CATALOG_PUBLICATION.RECEIPT_NO_BASELINE".equals(claim.code())) {
                if (allowMissingBaseline) {
                    return true;
                }
                fail(claim.code());
                return false;
            }
            if ("CATALOG_PUBLICATION.OWNER_TOKEN_MISMATCH".equals(claim.code())) {
                CatalogPublicationReceipt.Claim adopted = receiptStore.adoptForRedispatch(
                    owner.sessionKey(), owner.ownerToken(), publication);
                if (adopted.accepted()) {
                    return true;
                }
                fail(adopted.code());
                return false;
            }
            fail(claim.code());
            return false;
        } catch (RuntimeException exception) {
            fail("CATALOG_PUBLICATION.RECEIPT_PERSISTENCE_FAILED");
            return false;
        }
    }

    private static String ownerFailureCode(Session session) {
        return session == null || sessionKey(session).isBlank()
            ? "CATALOG_PUBLICATION.RECEIPT_NO_SESSION"
            : "CATALOG_PUBLICATION.OWNER_TOKEN_REQUIRED";
    }

    private static SessionOwner sessionOwner(Session session) {
        if (session == null) {
            return null;
        }
        String key = sessionKey(session);
        String token = session.getSessionId();
        if (key.isBlank() || token == null || token.isBlank() || !token.equals(token.strip())) {
            return null;
        }
        return new SessionOwner(key, token);
    }

    private boolean registerCurrentSession(Session session) {
        if (session == null) {
            return false;
        }
        String key = sessionKey(session);
        if (key.isBlank()) {
            return false;
        }
        synchronized (sessionOwnershipLock) {
            if (retiredSessions.contains(session)) {
                return currentSessions.get(key) == session;
            }
            Session current = currentSessions.get(key);
            if (current != null && current != session) {
                retireSession(current);
            }
            currentSessions.put(key, session);
            TemporaryLifecycleDiagnostics.event("catalog_session", 0L,
                TemporaryLifecycleDiagnostics.with(Map.of("moduleId", "flow", "resourceType", "catalog", "operation", "register",
                    "outcome", "current")));
            return true;
        }
    }

    private boolean registerDispatchSession(Session session) {
        if (session == null) {
            return true;
        }
        SessionOwner owner = sessionOwner(session);
        if (owner == null) {
            fail(ownerFailureCode(session));
            return false;
        }
        synchronized (sessionOwnershipLock) {
            if (retiredSessions.contains(session)) {
                fail("CATALOG_PUBLICATION.RECEIPT_OWNER_STALE");
                return false;
            }
            Session current = currentSessions.putIfAbsent(owner.sessionKey(), session);
            if (current != null && current != session) {
                fail("CATALOG_PUBLICATION.RECEIPT_OWNER_STALE");
                return false;
            }
        }
        return true;
    }

    private boolean isCurrentSession(Session session) {
        if (session == null) {
            return false;
        }
        String key = sessionKey(session);
        if (key.isBlank()) {
            return false;
        }
        synchronized (sessionOwnershipLock) {
            return currentSessions.get(key) == session;
        }
    }

    private void retireSession(Session session) {
        if (session == null) {
            return;
        }
        retiredSessions.add(session);
        while (retiredSessions.size() > MAX_SESSION_PUBLICATION_IDENTITIES) {
            Session eldest = retiredSessions.iterator().next();
            if (!retiredSessions.remove(eldest)) {
                return;
            }
        }
    }

    private static String sessionKey(Session session) {
        if (session == null) {
            return "";
        }
        String clientId = session.getClientId();
        if (clientId != null && !clientId.isBlank()) {
            return clientId.strip();
        }
        String sessionId = session.getSessionId();
        return sessionId == null ? "" : sessionId.strip();
    }

    private enum DispatchType {
        INITIAL,
        REQUEST,
        REFRESH
    }

    private record DispatchWork(DispatchType type, Session session, long retryAt, int attempts,
                                CatalogCachePublication publication, CatalogCacheKey expectedKey) {
        private DispatchWork {
            type = Objects.requireNonNull(type, "Dispatch work type is required");
            if (retryAt < 0L || attempts < 0) {
                throw new IllegalArgumentException("Dispatch work retry state is invalid");
            }
        }

        private static DispatchWork initial(Session session) {
            return new DispatchWork(DispatchType.INITIAL, Objects.requireNonNull(session, "Initial session is required"),
                0L, 0, null, null);
        }

        private static DispatchWork request(Session session, CatalogCacheKey expectedKey) {
            return new DispatchWork(DispatchType.REQUEST, Objects.requireNonNull(session, "Request session is required"),
                0L, 0, null, expectedKey);
        }

        private static DispatchWork refresh() {
            return new DispatchWork(DispatchType.REFRESH, null, 0L, 0, null, null);
        }

        private DispatchWork retry(long nextRetryAt, int nextAttempts) {
            return new DispatchWork(type, session, nextRetryAt, nextAttempts, publication, expectedKey);
        }

        private DispatchWork withPublication(CatalogCachePublication value) {
            return new DispatchWork(type, session, retryAt, attempts, value, expectedKey);
        }
    }

    private static final class OutboxReservation {
        private final int entries;
        private final long bytes;
        private boolean released;

        private OutboxReservation(int entries, long bytes) {
            this.entries = entries;
            this.bytes = bytes;
        }

        private int entries() {
            return entries;
        }

        private long bytes() {
            return bytes;
        }

        private boolean released() {
            return released;
        }

        private void release() {
            released = true;
        }
    }

    private static final class OutboxEntry {
        private final DispatchType type;
        private final Session session;
        private final DispatchWork work;
        private final SessionOwner owner;
        private final CatalogCachePublication fencePublication;
        private final CatalogCachePublication outboundPublication;
        private final FlowPacketSender.CatalogPublicationSendPlan plan;
        private final CatalogRuntimeActivation.ActivationRecord expectedActivation;
        private int nextFrame;
        private long retryAt;
        private int backpressureAttempts;

        private OutboxEntry(DispatchType type, Session session, DispatchWork work, SessionOwner owner,
                            CatalogCachePublication fencePublication,
                            CatalogCachePublication outboundPublication,
                            FlowPacketSender.CatalogPublicationSendPlan plan,
                            CatalogRuntimeActivation.ActivationRecord expectedActivation,
                            OutboxReservation reservation) {
            this.type = Objects.requireNonNull(type, "Outbox dispatch type is required");
            this.session = Objects.requireNonNull(session, "Outbox session is required");
            this.work = Objects.requireNonNull(work, "Outbox dispatch work is required")
                .withPublication(work.publication() == null ? fencePublication : work.publication());
            this.owner = Objects.requireNonNull(owner, "Outbox owner is required");
            this.fencePublication = Objects.requireNonNull(fencePublication, "Outbox fence publication is required");
            this.outboundPublication = Objects.requireNonNull(outboundPublication, "Outbox publication is required");
            this.plan = Objects.requireNonNull(plan, "Outbox send plan is required");
            this.expectedActivation = expectedActivation;
            if (reservation == null || reservation.entries() < 1 || reservation.bytes() < plan.retainedBytes()) {
                throw new IllegalArgumentException("Outbox reservation does not cover the send plan");
            }
        }

        private DispatchType type() {
            return type;
        }

        private Session session() {
            return session;
        }

        private SessionOwner owner() {
            return owner;
        }

        private CatalogCachePublication fencePublication() {
            return fencePublication;
        }

        private CatalogCachePublication publication() {
            return outboundPublication;
        }

        private CatalogCachePublication outboundPublication() {
            return outboundPublication;
        }

        private FlowPacketSender.CatalogPublicationSendPlan plan() {
            return plan;
        }

        private CatalogRuntimeActivation.ActivationRecord expectedActivation() {
            return expectedActivation;
        }

        private int nextFrame() {
            return nextFrame;
        }

        private long retryAt() {
            return retryAt;
        }

        private boolean backpressured() {
            return retryAt > 0L;
        }

        private void advance() {
            nextFrame++;
            retryAt = 0L;
            backpressureAttempts = 0;
        }

        private boolean defer(long now) {
            backpressureAttempts = Math.min(MAX_RETRY_ATTEMPTS, backpressureAttempts + 1);
            long delay = BACKPRESSURE_RETRY_DELAY_MS;
            for (int index = 1; index < backpressureAttempts && delay < TRANSIENT_RETRY_DELAY_MS; index++) {
                delay = Math.min(TRANSIENT_RETRY_DELAY_MS, delay * 2L);
            }
            retryAt = now > Long.MAX_VALUE - delay ? Long.MAX_VALUE : now + delay;
            return true;
        }

        private DispatchWork work() {
            return work;
        }
    }

    private record SessionOwner(String sessionKey, String ownerToken) {
    }
}
