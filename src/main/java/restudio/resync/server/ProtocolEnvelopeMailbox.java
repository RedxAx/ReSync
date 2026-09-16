package restudio.resync.server;

import restudio.resync.core.ConnectionInfo;
import restudio.resync.core.Session;
import restudio.resync.flow.protocol.ProtocolRejectionCode;
import restudio.resync.protocol.FrameSender;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class ProtocolEnvelopeMailbox implements AutoCloseable {
    private static final int MAX_DELIVERY_ATTEMPTS = 8;
    private static final long INITIAL_BACKPRESSURE_RETRY_MILLIS = 10L;
    private static final long MAX_BACKPRESSURE_RETRY_MILLIS = 250L;
    private static final long DELIVERY_RETRY_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(2L);
    private final ProtocolEnvelopeDispatchBoundary boundary;
    private final Limits limits;
    private final ScheduledThreadPoolExecutor workers;
    private final Object admissionFence = new Object();
    private final Map<ConnectionInfo, Lane> lanes = new IdentityHashMap<>();
    private final Map<ConnectionInfo, Long> closingGenerations = new IdentityHashMap<>();
    private final AtomicLong generationSequence = new AtomicLong(1L);
    private long admittedRequests;
    private long admittedBytes;
    private boolean accepting = true;
    private boolean shutdown;
    private CompletableFuture<Void> idle = CompletableFuture.completedFuture(null);

    public ProtocolEnvelopeMailbox(ProtocolEnvelopeDispatchBoundary boundary, Limits limits) {
        this.boundary = Objects.requireNonNull(boundary, "Protocol envelope boundary is required");
        this.limits = Objects.requireNonNull(limits, "Protocol mailbox limits are required");
        AtomicInteger threadSequence = new AtomicInteger();
        this.workers = new ScheduledThreadPoolExecutor(limits.workers(), runnable -> {
            Thread thread = new Thread(runnable, "ReSync-Protocol-" + threadSequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        this.workers.setRemoveOnCancelPolicy(true);
        this.workers.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        this.workers.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
    }

    public long activate(ConnectionInfo connection, Session session) {
        if (connection == null || session == null || session.getConnection() != connection) {
            throw new IllegalArgumentException("Protocol mailbox activation requires an exact connection session");
        }
        CompletableFuture<Void> completion;
        long generation;
        synchronized (admissionFence) {
            if (!accepting || shutdown) {
                throw new IllegalStateException("Protocol mailbox admission is closed");
            }
            if (closingGenerations.containsKey(connection)) {
                throw new IllegalStateException("Protocol mailbox connection is closing");
            }
            Lane previous = lanes.remove(connection);
            if (previous != null) {
                retireLocked(previous);
            }
            generation = generationSequence.getAndUpdate(current -> current == Long.MAX_VALUE ? 1L : current + 1L);
            lanes.put(connection, new Lane(connection, session, generation));
            completion = idleCompletionLocked();
        }
        completeIdle(completion);
        return generation;
    }

    public Admission admit(ConnectionInfo connection, Session session, byte[] payload, SessionFence sessionFence,
                           DeliveryFactory deliveryFactory) {
        return admitEncoded(connection, session, payload, encoded -> encoded, sessionFence, deliveryFactory);
    }

    public Admission admitEncoded(ConnectionInfo connection, Session session, byte[] payload, PayloadDecoder payloadDecoder,
                                  SessionFence sessionFence, DeliveryFactory deliveryFactory) {
        long started = TemporaryLifecycleDiagnostics.start();
        byte[] requestPayload = payload == null ? new byte[0] : payload.clone();
        if (connection == null || session == null || session.getConnection() != connection) {
            return rejectAdmission(started, null, requestPayload.length, ProtocolRejectionCode.AUTHENTICATION_REQUIRED,
                "session_fence", Map.of());
        }
        if (requestPayload.length > limits.maxRequestBytes()) {
            return rejectAdmission(started, null, requestPayload.length, ProtocolRejectionCode.INVALID_PAYLOAD,
                "request_bytes", Map.of());
        }
        Objects.requireNonNull(sessionFence, "Protocol session fence is required");
        Objects.requireNonNull(deliveryFactory, "Protocol delivery factory is required");
        Objects.requireNonNull(payloadDecoder, "Protocol payload decoder is required");
        if (!sessionCurrent(sessionFence, connection, session)) {
            return rejectAdmission(started, null, requestPayload.length, ProtocolRejectionCode.AUTHENTICATION_REQUIRED,
                "session_fence", Map.of());
        }
        return admitEntry(started, connection, session,
            Entry.inbound(session, requestPayload, payloadDecoder, sessionFence, deliveryFactory));
    }

    public Admission admitOutbound(ConnectionInfo connection, Session session, byte[] encodedPayload,
                                   SessionFence sessionFence, EventFence eventFence, PendingDelivery delivery) {
        long started = TemporaryLifecycleDiagnostics.start();
        byte[] outboundPayload = encodedPayload == null ? new byte[0] : encodedPayload.clone();
        if (connection == null || session == null || session.getConnection() != connection) {
            return rejectAdmission(started, null, outboundPayload.length, ProtocolRejectionCode.AUTHENTICATION_REQUIRED,
                "session_fence", Map.of());
        }
        if (outboundPayload.length > limits.maxRequestBytes()) {
            return rejectAdmission(started, null, outboundPayload.length, ProtocolRejectionCode.INVALID_PAYLOAD,
                "request_bytes", Map.of());
        }
        Objects.requireNonNull(sessionFence, "Protocol session fence is required");
        Objects.requireNonNull(eventFence, "Protocol event fence is required");
        Objects.requireNonNull(delivery, "Protocol outbound delivery is required");
        if (!sessionCurrent(sessionFence, connection, session) || !eventCurrent(eventFence)) {
            return rejectAdmission(started, null, outboundPayload.length, ProtocolRejectionCode.AUTHENTICATION_REQUIRED,
                "session_fence", Map.of());
        }
        return admitEntry(started, connection, session,
            Entry.outbound(session, outboundPayload, sessionFence, eventFence, delivery));
    }

    private Admission admitEntry(long started, ConnectionInfo connection, Session session, Entry entry) {
        Lane lane;
        boolean schedule = false;
        Rejection rejection = null;
        Map<String, Object> admissionDiagnostic;
        synchronized (admissionFence) {
            if (!accepting || shutdown) {
                rejection = rejection(
                    ProtocolRejectionCode.RESOURCE_AUTHORITY_CLOSED, "mailbox_state",
                    Map.of("accepting", accepting, "shutdown", shutdown));
                lane = null;
                schedule = false;
            } else {
                lane = lanes.get(connection);
            }
            if (rejection == null && (lane == null || lane.closed || lane.session != session)) {
                rejection = rejection(
                    ProtocolRejectionCode.RESOURCE_READ_UNAVAILABLE, "connection_generation",
                    Map.of("laneActive", lane != null && !lane.closed,
                        "exactSession", lane != null && lane.session == session));
                schedule = false;
            }
            if (rejection == null) {
                boolean connectionRequestLimit = lane.requests >= limits.maxRequestsPerConnection();
                boolean connectionByteLimit = entry.payload.length > limits.maxBytesPerConnection() - lane.bytes;
                boolean globalRequestLimit = admittedRequests >= limits.maxGlobalRequests();
                boolean globalByteLimit = entry.payload.length > limits.maxGlobalBytes() - admittedBytes;
                if (connectionRequestLimit || connectionByteLimit || globalRequestLimit || globalByteLimit) {
                    rejection = rejection(ProtocolRejectionCode.RESOURCE_READ_UNAVAILABLE, "backpressure",
                        Map.of("connectionRequestLimit", connectionRequestLimit,
                            "connectionByteLimit", connectionByteLimit, "globalRequestLimit", globalRequestLimit,
                            "globalByteLimit", globalByteLimit));
                    schedule = false;
                }
            }
            if (rejection == null) {
                entry.generation = lane.generation;
                lane.queue.addLast(entry);
                lane.requests++;
                lane.bytes += entry.payload.length;
                if (admittedRequests == 0L && closingGenerations.isEmpty()) {
                    idle = new CompletableFuture<>();
                }
                admittedRequests++;
                admittedBytes += entry.payload.length;
                schedule = markScheduledLocked(lane);
            }
            admissionDiagnostic = admissionDiagnostic(lane, entry.payload.length, rejection,
                entry.inbound ? "inbound_admission" : "outbound_admission", schedule);
        }
        if (rejection != null) {
            TemporaryLifecycleDiagnostics.event("protocol_mailbox_admission", started, admissionDiagnostic);
            return rejection.admission();
        }
        if (schedule) {
            execute(lane, 0L);
        }
        TemporaryLifecycleDiagnostics.event("protocol_mailbox_admission", started, admissionDiagnostic);
        return Admission.accepted(lane.generation);
    }

    public void retire(ConnectionInfo connection) {
        if (connection == null) {
            return;
        }
        CompletableFuture<Void> completion;
        synchronized (admissionFence) {
            Lane lane = lanes.remove(connection);
            if (lane != null) {
                retireLocked(lane);
            }
            completion = idleCompletionLocked();
        }
        completeIdle(completion);
    }

    public void closeAdmission() {
        CompletableFuture<Void> completion;
        synchronized (admissionFence) {
            if (!accepting) {
                return;
            }
            accepting = false;
            for (Lane lane : new ArrayList<>(lanes.values())) {
                retireLocked(lane);
            }
            lanes.clear();
            completion = idleCompletionLocked();
        }
        completeIdle(completion);
    }

    public boolean isIdle() {
        synchronized (admissionFence) {
            return admittedRequests == 0L && closingGenerations.isEmpty();
        }
    }

    public CompletableFuture<Void> whenIdle() {
        synchronized (admissionFence) {
            return idle;
        }
    }

    public long admittedRequestCount() {
        synchronized (admissionFence) {
            return admittedRequests;
        }
    }

    public long admittedByteCount() {
        synchronized (admissionFence) {
            return admittedBytes;
        }
    }

    public void shutdown() {
        closeAdmission();
        synchronized (admissionFence) {
            if (shutdown) {
                return;
            }
            shutdown = true;
        }
        workers.shutdown();
    }

    @Override
    public void close() {
        shutdown();
    }

    private void runLane(Lane lane) {
        Entry entry;
        synchronized (admissionFence) {
            lane.scheduled = false;
            if (lane.processing || lane.queue.isEmpty() || lane.closed) {
                return;
            }
            entry = lane.queue.peekFirst();
            lane.processing = true;
        }
        try {
            if (entry.inbound && entry.result == null) {
                if (!currentForDispatch(lane, entry)) {
                    TemporaryLifecycleDiagnostics.event("protocol_mailbox_dispatch", entry.admittedAt,
                        TemporaryLifecycleDiagnostics.with(diagnostic(lane, entry.payload.length, "retired"),
                            "phase", "session_fence", "deliveryFinal", true));
                    settle(lane, entry, FrameSender.SendResult.CLOSED, false);
                    return;
                }
                dispatch(lane, entry);
            }

            if (!currentForDelivery(lane, entry)) {
                TemporaryLifecycleDiagnostics.event("protocol_mailbox_delivery", entry.admittedAt,
                    TemporaryLifecycleDiagnostics.with(diagnostic(lane, entry.payload.length, "retired"),
                        "phase", "delivery_fence", "deliveryFinal", true));
                settle(lane, entry, FrameSender.SendResult.CLOSED, false);
                return;
            }
            if (entry.delivery == null) {
                long prepareStarted = TemporaryLifecycleDiagnostics.start();
                entry.delivery = entry.deliveryFactory.prepare(entry.result, entry.authorityEpoch);
                TemporaryLifecycleDiagnostics.event("protocol_mailbox_delivery_prepare", prepareStarted,
                    TemporaryLifecycleDiagnostics.with(diagnostic(lane, entry.payload.length,
                        entry.delivery == null ? "missing" : "prepared"), "responsePrepared", entry.delivery != null));
            }
            if (entry.delivery == null || !currentForDelivery(lane, entry)) {
                settle(lane, entry, FrameSender.SendResult.CLOSED, false);
                return;
            }
            entry.recordDeliveryAttempt();
            FrameSender.SendResult outcome = entry.delivery.tryDeliver();
            settle(lane, entry, outcome, outcome == FrameSender.SendResult.BACKPRESSURED);
        } catch (RuntimeException exception) {
            TemporaryLifecycleDiagnostics.event("protocol_mailbox_delivery", entry.admittedAt,
                TemporaryLifecycleDiagnostics.with(diagnostic(lane, entry.payload.length, "failed"),
                    "failure", exception.getClass().getSimpleName()));
            settle(lane, entry, FrameSender.SendResult.CLOSED, false);
        }
    }

    private void dispatch(Lane lane, Entry entry) {
        ProtocolEnvelopeDispatchResult result = null;
        long started = TemporaryLifecycleDiagnostics.start();
        try {
            entry.authorityEpoch = boundary.currentAuthorityEpoch();
        } catch (RuntimeException exception) {
            result = ProtocolEnvelopeDispatchResult.rejected(ProtocolRejectionCode.RESOURCE_AUTHORITY_CLOSED,
                "Protocol envelope authority is unavailable");
        }
        byte[] decodedPayload;
        try {
            decodedPayload = Objects.requireNonNull(entry.payloadDecoder.decode(entry.payload),
                "Decoded protocol payload is required");
            TemporaryLifecycleDiagnostics.event("protocol_mailbox_decode", started,
                TemporaryLifecycleDiagnostics.with(diagnostic(lane, entry.payload.length, "decoded"),
                    "decoded", true, "bytes", decodedPayload.length));
        } catch (RuntimeException exception) {
            decodedPayload = new byte[0];
            result = boundary.rejectPayload(decodedPayload, ProtocolRejectionCode.INVALID_PAYLOAD,
                "Protocol envelope frame is malformed");
            TemporaryLifecycleDiagnostics.event("protocol_mailbox_decode", started,
                TemporaryLifecycleDiagnostics.with(diagnostic(lane, entry.payload.length, "rejected"),
                    "decoded", false, "failure", exception.getClass().getSimpleName()));
        }
        if (result == null && !sameAuthority(entry)) {
            result = boundary.rejectPayload(decodedPayload, ProtocolRejectionCode.RESOURCE_REVISION_CONFLICT,
                "Protocol envelope authority epoch changed before dispatch");
        } else if (result == null) {
            result = boundary.dispatch(lane.connection, entry.session, decodedPayload);
        }
        entry.result = result;
        TemporaryLifecycleDiagnostics.event("protocol_mailbox_dispatch", started,
            TemporaryLifecycleDiagnostics.with(diagnostic(lane, entry.payload.length, result.status().name()),
                "transportCode", result.transportCode(), "hasResponse", result.response() != null,
                "thread", Thread.currentThread().getName(), "admittedThread", entry.admittedThread,
                "queueWaitMs", elapsedMillis(entry.admittedAt)));
    }

    private void settle(Lane lane, Entry entry, FrameSender.SendResult outcome, boolean retry) {
        boolean retryCurrent = retry && currentForDelivery(lane, entry);
        boolean schedule = false;
        boolean closeOwner = false;
        long delay = 0L;
        boolean deliveryFinal;
        Map<String, Object> diagnostic;
        CompletableFuture<Void> completion;
        synchronized (admissionFence) {
            if (lane.queue.peekFirst() != entry) {
                return;
            }
            lane.processing = false;
            boolean internallyCurrent = currentLocked(lane, entry);
            boolean retryAvailable = retryCurrent && internallyCurrent && entry.retryAvailable();
            if (retryCurrent && internallyCurrent && !retryAvailable) {
                lanes.remove(lane.connection, lane);
                closingGenerations.put(lane.connection, lane.generation);
                retireLocked(lane);
                closeOwner = true;
                deliveryFinal = true;
            } else if (!retryAvailable) {
                settleFirstLocked(lane);
                schedule = markScheduledLocked(lane);
                deliveryFinal = true;
            } else {
                schedule = markScheduledLocked(lane);
                delay = retryDelayMillis(entry.deliveryAttempts);
                deliveryFinal = false;
            }
            diagnostic = TemporaryLifecycleDiagnostics.with(diagnostic(lane, entry.payload.length, outcome.name()),
                "retry", retryAvailable, "deliveryAttempts", entry.deliveryAttempts,
                "retryDelayMs", delay, "retryExhausted", closeOwner, "thread", Thread.currentThread().getName(),
                "requestAgeMs", elapsedMillis(entry.admittedAt), "deliveryFinal", deliveryFinal);
            completion = idleCompletionLocked();
        }
        completeIdle(completion);
        TemporaryLifecycleDiagnostics.event("protocol_mailbox_delivery", entry.admittedAt, diagnostic);
        if (closeOwner) {
            closeOwner(lane);
        }
        if (schedule) {
            execute(lane, delay);
        }
    }

    private boolean currentForDispatch(Lane lane, Entry entry) {
        return internallyCurrent(lane, entry)
            && sessionCurrent(entry.sessionFence, lane.connection, entry.session)
            && internallyCurrent(lane, entry);
    }

    private boolean currentForDelivery(Lane lane, Entry entry) {
        if (!internallyCurrent(lane, entry)
            || !sessionCurrent(entry.sessionFence, lane.connection, entry.session)
            || entry.eventFence != null && !eventCurrent(entry.eventFence)
            || !authorityCurrent(entry)) {
            return false;
        }
        return internallyCurrent(lane, entry);
    }

    private boolean internallyCurrent(Lane lane, Entry entry) {
        synchronized (admissionFence) {
            return currentLocked(lane, entry);
        }
    }

    private boolean currentLocked(Lane lane, Entry entry) {
        return !lane.closed && lanes.get(lane.connection) == lane && lane.session == entry.session
            && lane.generation == entry.generation;
    }

    private boolean sameAuthority(Entry entry) {
        try {
            return boundary.currentAuthorityEpoch() == entry.authorityEpoch;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private boolean authorityCurrent(Entry entry) {
        return !entry.inbound || entry.authorityEpoch != 0L && sameAuthority(entry);
    }

    private boolean sessionCurrent(SessionFence fence, ConnectionInfo connection, Session session) {
        try {
            return fence.current(connection, session);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private boolean eventCurrent(EventFence fence) {
        try {
            return fence.current();
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private boolean markScheduledLocked(Lane lane) {
        if (lane.scheduled || lane.processing || lane.queue.isEmpty() || lane.closed) {
            return false;
        }
        lane.scheduled = true;
        return true;
    }

    private void execute(Lane lane, long delayMillis) {
        try {
            if (delayMillis > 0L) {
                workers.schedule(() -> runLane(lane), delayMillis, TimeUnit.MILLISECONDS);
            } else {
                workers.execute(() -> runLane(lane));
            }
        } catch (RuntimeException exception) {
            TemporaryLifecycleDiagnostics.event("protocol_mailbox_schedule", 0L,
                TemporaryLifecycleDiagnostics.with(diagnostic(lane, 0, "failed"), "phase", "worker_submit",
                    "failure", exception.getClass().getSimpleName(), "deliveryFinal", true));
            CompletableFuture<Void> completion;
            synchronized (admissionFence) {
                lane.scheduled = false;
                lanes.remove(lane.connection, lane);
                retireLocked(lane);
                completion = idleCompletionLocked();
            }
            completeIdle(completion);
        }
    }

    private void retireLocked(Lane lane) {
        lane.closed = true;
        lane.scheduled = false;
        if (lane.processing && !lane.queue.isEmpty()) {
            Entry active = lane.queue.removeFirst();
            while (!lane.queue.isEmpty()) {
                settleEntryLocked(lane.queue.removeFirst(), lane);
            }
            lane.queue.addFirst(active);
        } else {
            while (!lane.queue.isEmpty()) {
                settleEntryLocked(lane.queue.removeFirst(), lane);
            }
        }
    }

    private void settleFirstLocked(Lane lane) {
        Entry settled = lane.queue.pollFirst();
        if (settled != null) {
            settleEntryLocked(settled, lane);
        }
        if (lane.closed && lane.queue.isEmpty()) {
            lanes.remove(lane.connection, lane);
        }
    }

    private void settleEntryLocked(Entry entry, Lane lane) {
        lane.requests--;
        lane.bytes -= entry.payload.length;
        admittedRequests--;
        admittedBytes -= entry.payload.length;
        if (lane.requests < 0L || lane.bytes < 0L || admittedRequests < 0L || admittedBytes < 0L) {
            throw new IllegalStateException("Protocol mailbox accounting is inconsistent");
        }
    }

    private CompletableFuture<Void> idleCompletionLocked() {
        return admittedRequests == 0L && closingGenerations.isEmpty() && !idle.isDone() ? idle : null;
    }

    private void completeIdle(CompletableFuture<Void> completion) {
        if (completion != null) {
            completion.complete(null);
        }
    }

    private void closeOwner(Lane lane) {
        try {
            lane.connection.getFrameSender().close(1013, "Protocol delivery remained backpressured");
        } catch (RuntimeException exception) {
            TemporaryLifecycleDiagnostics.event("protocol_mailbox_delivery", 0L,
                TemporaryLifecycleDiagnostics.with(diagnostic(lane, 0, "close_failed"),
                    "failure", exception.getClass().getSimpleName(), "deliveryFinal", true));
        } finally {
            CompletableFuture<Void> completion;
            synchronized (admissionFence) {
                Long closingGeneration = closingGenerations.get(lane.connection);
                if (closingGeneration != null && closingGeneration.longValue() == lane.generation) {
                    closingGenerations.remove(lane.connection);
                }
                completion = idleCompletionLocked();
            }
            completeIdle(completion);
        }
    }

    private static long retryDelayMillis(int attempts) {
        int shift = Math.max(0, Math.min(30, attempts - 1));
        long delay = INITIAL_BACKPRESSURE_RETRY_MILLIS << shift;
        return Math.min(MAX_BACKPRESSURE_RETRY_MILLIS, delay);
    }

    private Map<String, Object> diagnostic(Lane lane, int requestBytes, Object outcome) {
        return TemporaryLifecycleDiagnostics.with(
            TemporaryLifecycleDiagnostics.identity(null, "protocol", null, null, null, null,
                null, lane == null ? null : lane.generation),
            "outcome", outcome, "requestBytes", requestBytes, "connectionHash",
            TemporaryLifecycleDiagnostics.safeHash(lane == null ? null : lane.connection.getConnectionId()),
            "connectionRequests", lane == null ? 0L : lane.requests,
            "connectionBytes", lane == null ? 0L : lane.bytes,
            "globalRequests", admittedRequests, "globalBytes", admittedBytes);
    }

    private Admission rejectAdmission(long started, Lane lane, int requestBytes, ProtocolRejectionCode code,
                                      String phase, Map<String, ?> gates) {
        Rejection rejection = rejection(code, phase, gates);
        TemporaryLifecycleDiagnostics.event("protocol_mailbox_admission", started,
            admissionDiagnostic(lane, requestBytes, rejection, phase, false));
        return rejection.admission();
    }

    private Rejection rejection(ProtocolRejectionCode code, String phase, Map<String, ?> gates) {
        ProtocolEnvelopeDispatchResult result = ProtocolEnvelopeDispatchResult.rejected(code,
            switch (phase) {
                case "session_fence" -> "Protocol envelope requires an authenticated session";
                case "request_bytes" -> "Protocol envelope exceeds the mailbox request limit";
                case "mailbox_state" -> "Protocol envelope admission is closed";
                case "connection_generation" -> "Protocol envelope connection generation is unavailable";
                default -> "Protocol envelope mailbox is busy";
            });
        return new Rejection(Admission.rejected(result), phase, gates == null ? Map.of() : Map.copyOf(gates));
    }

    private Map<String, Object> admissionDiagnostic(Lane lane, int requestBytes, Rejection rejection,
                                                    String acceptedPhase, boolean schedule) {
        if (rejection == null) {
            return TemporaryLifecycleDiagnostics.with(diagnostic(lane, requestBytes, "accepted"),
                "phase", acceptedPhase, "scheduled", schedule);
        }
        Map<String, Object> values = TemporaryLifecycleDiagnostics.with(diagnostic(lane, requestBytes, "rejected"),
            "phase", rejection.phase(), "diagnosticCode", rejection.admission().rejection().rejectionCode(),
            "deliveryFinal", false);
        Map<String, ?> gates = rejection.gates();
        if (gates != null) {
            values.putAll(gates);
        }
        return values;
    }

    private static long elapsedMillis(long startedNanos) {
        return startedNanos <= 0L ? 0L : TimeUnit.NANOSECONDS.toMillis(Math.max(0L, System.nanoTime() - startedNanos));
    }

    public record Limits(int workers, int maxRequestsPerConnection, long maxBytesPerConnection,
                         int maxGlobalRequests, long maxGlobalBytes, int maxRequestBytes) {
        public Limits {
            if (workers < 1 || maxRequestsPerConnection < 1 || maxBytesPerConnection < 1L
                || maxGlobalRequests < maxRequestsPerConnection || maxGlobalBytes < maxBytesPerConnection
                || maxRequestBytes < 1 || maxRequestBytes > maxBytesPerConnection) {
                throw new IllegalArgumentException("Protocol mailbox limits are invalid");
            }
        }

        public static Limits standard(int maxRequestsPerConnection, int maxGlobalRequests, int maxRequestBytes) {
            int perConnection = Math.max(1, Math.min(64, maxRequestsPerConnection));
            int global = Math.max(perConnection, Math.min(512, maxGlobalRequests));
            int requestBytes = Math.max(1, maxRequestBytes);
            long connectionBytes = Math.max(requestBytes, Math.min(16L * 1024L * 1024L,
                (long) requestBytes * Math.min(8, perConnection)));
            long globalBytes = Math.max(connectionBytes, Math.min(64L * 1024L * 1024L,
                connectionBytes * Math.min(8L, Math.max(1L, global / perConnection))));
            return new Limits(2, perConnection, connectionBytes, global, globalBytes, requestBytes);
        }
    }

    public record Admission(Status status, long generation, ProtocolEnvelopeDispatchResult rejection) {
        public static Admission accepted(long generation) {
            return new Admission(Status.ACCEPTED, generation, null);
        }

        public static Admission rejected(ProtocolEnvelopeDispatchResult rejection) {
            return new Admission(Status.REJECTED, 0L, Objects.requireNonNull(rejection, "Rejection is required"));
        }

        public boolean accepted() {
            return status == Status.ACCEPTED;
        }
    }

    public enum Status {
        ACCEPTED,
        REJECTED
    }

    private record Rejection(Admission admission, String phase, Map<String, ?> gates) {
    }

    @FunctionalInterface
    public interface SessionFence {
        boolean current(ConnectionInfo connection, Session session);
    }

    @FunctionalInterface
    public interface EventFence {
        boolean current();
    }

    @FunctionalInterface
    public interface PayloadDecoder {
        byte[] decode(byte[] encodedPayload);
    }

    @FunctionalInterface
    public interface DeliveryFactory {
        PendingDelivery prepare(ProtocolEnvelopeDispatchResult result, long authorityEpoch);
    }

    @FunctionalInterface
    public interface PendingDelivery {
        FrameSender.SendResult tryDeliver();
    }

    private static final class Lane {
        private final ConnectionInfo connection;
        private final Session session;
        private final long generation;
        private final ArrayDeque<Entry> queue = new ArrayDeque<>();
        private long requests;
        private long bytes;
        private boolean scheduled;
        private boolean processing;
        private boolean closed;

        private Lane(ConnectionInfo connection, Session session, long generation) {
            this.connection = connection;
            this.session = session;
            this.generation = generation;
        }
    }

    private static final class Entry {
        private long generation;
        private final Session session;
        private final byte[] payload;
        private final boolean inbound;
        private volatile long authorityEpoch;
        private final PayloadDecoder payloadDecoder;
        private final SessionFence sessionFence;
        private final EventFence eventFence;
        private final DeliveryFactory deliveryFactory;
        private final String admittedThread = Thread.currentThread().getName();
        private final long admittedAt = TemporaryLifecycleDiagnostics.start();
        private volatile ProtocolEnvelopeDispatchResult result;
        private volatile PendingDelivery delivery;
        private int deliveryAttempts;
        private long firstDeliveryAttemptAt;

        private Entry(Session session, byte[] payload, boolean inbound, PayloadDecoder payloadDecoder,
                      SessionFence sessionFence, EventFence eventFence, DeliveryFactory deliveryFactory,
                      PendingDelivery delivery) {
            this.session = session;
            this.payload = payload;
            this.inbound = inbound;
            this.payloadDecoder = payloadDecoder;
            this.sessionFence = sessionFence;
            this.eventFence = eventFence;
            this.deliveryFactory = deliveryFactory;
            this.delivery = delivery;
        }

        private static Entry inbound(Session session, byte[] payload, PayloadDecoder payloadDecoder,
                                     SessionFence sessionFence, DeliveryFactory deliveryFactory) {
            return new Entry(session, payload, true, payloadDecoder, sessionFence, null, deliveryFactory, null);
        }

        private static Entry outbound(Session session, byte[] payload, SessionFence sessionFence,
                                      EventFence eventFence, PendingDelivery delivery) {
            return new Entry(session, payload, false, null, sessionFence, eventFence, null, delivery);
        }

        private void recordDeliveryAttempt() {
            if (deliveryAttempts++ == 0) {
                firstDeliveryAttemptAt = System.nanoTime();
            }
        }

        private boolean retryAvailable() {
            return deliveryAttempts < MAX_DELIVERY_ATTEMPTS
                && System.nanoTime() - firstDeliveryAttemptAt < DELIVERY_RETRY_TIMEOUT_NANOS;
        }
    }
}
