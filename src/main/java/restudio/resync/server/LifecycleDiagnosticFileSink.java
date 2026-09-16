package restudio.resync.server;

import restudio.resync.contract.canonical.JsonValue;
import restudio.resync.diagnostics.DiagnosticEvent;
import restudio.resync.diagnostics.DiagnosticIdentity;
import restudio.resync.diagnostics.DiagnosticSink;
import restudio.resync.diagnostics.DiagnosticValue;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.Stream;

final class LifecycleDiagnosticFileSink implements DiagnosticSink {
    static final String DIRECTORY = "diagnostic-channel";
    static final String FILE_NAME = "resync-lifecycle.jsonl";
    private static final String ROTATED_PREFIX = "resync-lifecycle.";
    private static final String ROTATED_SUFFIX = ".jsonl";
    private static final int MAX_RECOVERY_ATTEMPTS = 3;
    private static final long EXTERNAL_REPORT_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(1L);
    private static final FailureProbe NO_FAILURE = step -> { };
    private static final ExternalReporter STANDARD_REPORTER = System.err::println;

    private final DiagnosticSink.Mode mode;
    private final Path directory;
    private final Path activePath;
    private final long maxFileBytes;
    private final FailureProbe failureProbe;
    private final ExternalReporter externalReporter;
    private final BlockingQueue<QueuedEvent> criticalQueue =
        new ArrayBlockingQueue<>(LifecycleDiagnosticPolicy.CRITICAL_CAPACITY);
    private final BlockingQueue<QueuedEvent> normalQueue =
        new ArrayBlockingQueue<>(LifecycleDiagnosticPolicy.NORMAL_CAPACITY);
    private final AtomicReference<DiagnosticSink.State> state = new AtomicReference<>(DiagnosticSink.State.READY);
    private final AtomicBoolean ioFailed = new AtomicBoolean();
    private final AtomicReference<String> failureReason = new AtomicReference<>("");
    private final AtomicLong offered = new AtomicLong();
    private final AtomicLong accepted = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong droppedMode = new AtomicLong();
    private final AtomicLong droppedPaused = new AtomicLong();
    private final AtomicLong droppedClosed = new AtomicLong();
    private final AtomicLong droppedThrottle = new AtomicLong();
    private final AtomicLong droppedNormalCapacity = new AtomicLong();
    private final AtomicLong droppedCriticalCapacity = new AtomicLong();
    private final AtomicLong droppedCriticalPreempted = new AtomicLong();
    private final AtomicLong droppedTerminalCapacity = new AtomicLong();
    private final AtomicLong droppedFailed = new AtomicLong();
    private final AtomicLong droppedAcceptedFirstSequence = new AtomicLong(Long.MAX_VALUE);
    private final AtomicLong droppedAcceptedLastSequence = new AtomicLong();
    private final AtomicLong nextSequence = new AtomicLong();
    private final AtomicLong acceptedSequence = new AtomicLong();
    private final AtomicLong confirmedSequence = new AtomicLong();
    private final AtomicReference<FlushRequest> flushRequest = new AtomicReference<>();
    private final Object lifecycleMonitor = new Object();
    private final ConcurrentHashMap<String, Long> progressThrottle = new ConcurrentHashMap<>();
    private final Object progressThrottleMonitor = new Object();
    private final Object externalReportMonitor = new Object();
    private final UUID processSessionId = UUID.randomUUID();
    private final Thread writerThread;
    private final AtomicBoolean stopRequested = new AtomicBoolean();
    private volatile BufferedWriter writer;
    private volatile long activeBytes;
    private volatile QueuedEvent bufferedNormal;
    private volatile long heldFirstSequence;
    private volatile long writingSequence;
    private ExternalReport pendingDroppedReport;
    private ExternalReport pendingUncertainReport;
    private long lastExternalReportNanos;
    private long lastSummaryNanos = System.nanoTime();
    private long lastReportedDropped;
    private long lastReportedFailed;
    private long lastReportedOffered;
    private long lastReportedAccepted;
    private long rotationSequence;

    static LifecycleDiagnosticFileSink open(Path operatorDataRoot) {
        DiagnosticSink.Mode mode = LifecycleDiagnosticPolicy.resolveFromProcess();
        return new LifecycleDiagnosticFileSink(operatorDataRoot, mode);
    }

    static LifecycleDiagnosticFileSink disabled() {
        return new LifecycleDiagnosticFileSink(null, DiagnosticSink.Mode.OFF);
    }

    private LifecycleDiagnosticFileSink(Path operatorDataRoot, DiagnosticSink.Mode mode) {
        this(operatorDataRoot, mode, LifecycleDiagnosticPolicy.MAX_FILE_BYTES, NO_FAILURE);
    }

    LifecycleDiagnosticFileSink(Path operatorDataRoot, DiagnosticSink.Mode mode, long maxFileBytes,
                                FailureProbe failureProbe) {
        this(operatorDataRoot, mode, maxFileBytes, failureProbe, STANDARD_REPORTER);
    }

    LifecycleDiagnosticFileSink(Path operatorDataRoot, DiagnosticSink.Mode mode, long maxFileBytes,
                                FailureProbe failureProbe, ExternalReporter externalReporter) {
        this.mode = mode == null ? DiagnosticSink.Mode.OFF : mode;
        this.maxFileBytes = maxFileBytes;
        this.failureProbe = failureProbe == null ? NO_FAILURE : failureProbe;
        this.externalReporter = externalReporter == null ? STANDARD_REPORTER : externalReporter;
        if (this.mode.enabled()) {
            if (operatorDataRoot == null || maxFileBytes <= LifecycleDiagnosticPolicy.MAX_LINE_BYTES * 2L) {
                throw new IllegalArgumentException("Diagnostic root and rotation size are required");
            }
            Path root = operatorDataRoot.toAbsolutePath().normalize();
            this.directory = root.resolve(DIRECTORY).normalize();
            this.activePath = directory.resolve(FILE_NAME).normalize();
            this.writerThread = new Thread(this::runWriter, "resync-diagnostic-writer");
            this.writerThread.setDaemon(true);
            this.writerThread.start();
        } else {
            this.directory = null;
            this.activePath = null;
            this.writerThread = null;
        }
    }

    Path activePath() {
        return activePath;
    }

    boolean flushPendingForTest() {
        return flushRequest.get() != null;
    }

    boolean writerAliveForTest() {
        return writerThread != null && writerThread.isAlive();
    }

    @Override
    public Status status() {
        DiagnosticSink.State current = ioFailed.get() ? DiagnosticSink.State.FAILED : state.get();
        return new Status(mode, current, offered.get(), accepted.get(), dropped.get(), failed.get(), failureReason.get());
    }

    @Override
    public Offer offer(DiagnosticEvent event) {
        if (event == null) {
            return Offer.REJECTED;
        }
        if (!mode.enabled()) {
            return Offer.DISABLED;
        }
        synchronized (lifecycleMonitor) {
            offered.incrementAndGet();
            if (ioFailed.get()) {
                failed.incrementAndGet();
                recordDrop(droppedFailed);
                return Offer.FAILED;
            }
            DiagnosticSink.State current = state.get();
            if (current == DiagnosticSink.State.PAUSED) {
                recordDrop(droppedPaused);
                return Offer.PAUSED;
            }
            if (current == DiagnosticSink.State.FAILED) {
                failed.incrementAndGet();
                recordDrop(droppedFailed);
                return Offer.FAILED;
            }
            if (current == DiagnosticSink.State.CLOSED) {
                recordDrop(droppedClosed);
                return Offer.CLOSED;
            }
            if (!mode.accepts(event.priority())) {
                recordDrop(droppedMode);
                return Offer.REJECTED;
            }
            if (LifecycleDiagnosticPolicy.healthyProgress(event) && throttled(event)) {
                recordDrop(droppedThrottle);
                return Offer.DROPPED;
            }
            QueuedEvent queued = new QueuedEvent(event, Thread.currentThread().getName(),
                System.currentTimeMillis(), nextSequence.incrementAndGet());
            BlockingQueue<QueuedEvent> queue = event.priority().atLeast(DiagnosticSink.Priority.IMPORTANT)
                ? criticalQueue : normalQueue;
            if (!offerQueued(queue, queued)) {
                return Offer.DROPPED;
            }
            accepted.incrementAndGet();
            acceptedSequence.accumulateAndGet(queued.sequence(), Math::max);
        }
        LockSupport.unpark(writerThread);
        return Offer.ACCEPTED;
    }

    @Override
    public Status pause() {
        state.compareAndSet(DiagnosticSink.State.READY, DiagnosticSink.State.PAUSED);
        return status();
    }

    @Override
    public Status resume() {
        state.compareAndSet(DiagnosticSink.State.PAUSED, DiagnosticSink.State.READY);
        return status();
    }

    @Override
    public Flush flush() {
        if (!mode.enabled()) {
            return Flush.DISABLED;
        }
        if (ioFailed.get()) {
            return Flush.FAILED;
        }
        DiagnosticSink.State current = state.get();
        if (current == DiagnosticSink.State.CLOSED) {
            return Flush.CLOSED;
        }
        if (current == DiagnosticSink.State.FAILED) {
            return Flush.FAILED;
        }
        if (Thread.currentThread() == writerThread) {
            try {
                flushWriter();
                return Flush.FLUSHED;
            } catch (IOException exception) {
                failSink(exception, unconfirmedFirstSequence(), acceptedSequence.get(), "flush");
                return Flush.FAILED;
            }
        }
        FlushRequest request;
        synchronized (lifecycleMonitor) {
            if (ioFailed.get()) {
                return Flush.FAILED;
            }
            if (state.get() == DiagnosticSink.State.CLOSED) {
                return Flush.CLOSED;
            }
            if (state.get() == DiagnosticSink.State.FAILED) {
                return Flush.FAILED;
            }
            long watermark = acceptedSequence.get();
            request = flushRequest.get();
            if (request == null) {
                request = new FlushRequest(watermark);
                flushRequest.set(request);
            } else {
                request.include(watermark);
            }
        }
        LockSupport.unpark(writerThread);
        try {
            if (!request.latch().await(LifecycleDiagnosticPolicy.CLOSE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                return Flush.FAILED;
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return Flush.FAILED;
        }
        if (ioFailed.get()) {
            return Flush.FAILED;
        }
        return state.get() == DiagnosticSink.State.CLOSED ? Flush.CLOSED : Flush.FLUSHED;
    }

    @Override
    public void close() {
        if (!mode.enabled()) {
            state.set(DiagnosticSink.State.CLOSED);
            return;
        }
        boolean close = false;
        synchronized (lifecycleMonitor) {
            if (state.get() != DiagnosticSink.State.CLOSED) {
                state.set(DiagnosticSink.State.CLOSED);
                stopRequested.set(true);
                close = true;
            }
        }
        if (close) {
            LockSupport.unpark(writerThread);
            if (Thread.currentThread() != writerThread) {
                joinWriter(LifecycleDiagnosticPolicy.CLOSE_TIMEOUT_MILLIS);
                if (writerThread.isAlive()) {
                    writerThread.interrupt();
                    joinWriter(100L);
                    if (writerThread.isAlive()) {
                        failSink(new IOException("Diagnostic writer did not stop"), unconfirmedFirstSequence(),
                            acceptedSequence.get(), "close-timeout");
                    }
                }
            }
        }
    }

    private void runWriter() {
        try {
            if (!openActiveRecovering()) {
                completeFlushRequest();
                return;
            }
            reportOutsideIfDue(false);
            while (!stopRequested.get() || !criticalQueue.isEmpty() || !normalQueue.isEmpty()
                || bufferedNormal != null || flushRequest.get() != null) {
                QueuedEvent first = pollNext();
                if (ioFailed.get()) {
                    completeFlushRequest();
                    return;
                }
                if (first == null) {
                    if (!writeDropSummaryIfNeeded()) {
                        completeFlushRequest();
                        return;
                    }
                    reportOutsideIfDue(false);
                    completeFlushRequest();
                    continue;
                }
                List<QueuedEvent> batch = new ArrayList<>(LifecycleDiagnosticPolicy.MAX_BATCH_SIZE);
                batch.add(first);
                synchronized (lifecycleMonitor) {
                    while (batch.size() < LifecycleDiagnosticPolicy.MAX_BATCH_SIZE) {
                        QueuedEvent next = pollAvailableNext();
                        if (next == null) {
                            break;
                        }
                        batch.add(next);
                    }
                }
                for (int index = 0; index < batch.size(); index++) {
                    QueuedEvent event = batch.get(index);
                    if (ioFailed.get()) {
                        completeFlushRequest();
                        return;
                    }
                    writingSequence = event.sequence();
                    if (!writeEvent(event)) {
                        completeFlushRequest();
                        return;
                    }
                    if (ioFailed.get()) {
                        completeFlushRequest();
                        return;
                    }
                    synchronized (lifecycleMonitor) {
                        heldFirstSequence = index + 1 < batch.size() ? batch.get(index + 1).sequence() : 0L;
                    }
                    writingSequence = 0L;
                }
                if (!writeDropSummaryIfNeeded()) {
                    completeFlushRequest();
                    return;
                }
                reportOutsideIfDue(false);
                completeFlushRequest();
            }
            writeDropSummaryIfNeeded();
            reportOutsideIfDue(true);
            completeFlushRequest();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            long firstSequence = unconfirmedFirstSequence();
            if (!stopRequested.get() || firstSequence > 0L) {
                failSink(exception, firstSequence, acceptedSequence.get(),
                    stopRequested.get() ? "close-interrupted" : "interrupted");
            }
            completeFlushRequest();
        } catch (IOException exception) {
            failSink(exception, unconfirmedFirstSequence(), acceptedSequence.get(), "writer");
            completeFlushRequest();
        } catch (RuntimeException exception) {
            failSink(exception, unconfirmedFirstSequence(), acceptedSequence.get(), "writer");
            completeFlushRequest();
        } finally {
            closeWriterQuietly();
        }
    }

    private QueuedEvent pollNext() throws InterruptedException, IOException {
        synchronized (lifecycleMonitor) {
            QueuedEvent next = pollAvailableNext();
            if (next != null) {
                return next;
            }
        }
        failureProbe.check("normal-poll");
        QueuedEvent normal = normalQueue.poll(LifecycleDiagnosticPolicy.MAX_BATCH_DELAY_MILLIS, TimeUnit.MILLISECONDS);
        if (normal == null) {
            return null;
        }
        bufferedNormal = normal;
        synchronized (lifecycleMonitor) {
            return pollAvailableNext();
        }
    }

    private QueuedEvent pollAvailableNext() {
        QueuedEvent critical = criticalQueue.peek();
        QueuedEvent normal = normalQueue.peek();
        QueuedEvent next = bufferedNormal;
        int source = next == null ? 0 : 1;
        if (critical != null && (next == null || critical.sequence() < next.sequence())) {
            next = critical;
            source = 2;
        }
        if (normal != null && (next == null || normal.sequence() < next.sequence())) {
            next = normal;
            source = 3;
        }
        if (source == 1) {
            bufferedNormal = null;
            holdSequence(next);
            return next;
        }
        if (source == 2) {
            next = criticalQueue.poll();
            holdSequence(next);
            return next;
        }
        if (source == 3) {
            next = normalQueue.poll();
            holdSequence(next);
            return next;
        }
        return null;
    }

    private void holdSequence(QueuedEvent event) {
        if (event != null && (heldFirstSequence <= 0L || event.sequence() < heldFirstSequence)) {
            heldFirstSequence = event.sequence();
        }
    }

    private boolean openActiveRecovering() {
        IOException failure = null;
        for (int attempt = 1; attempt <= MAX_RECOVERY_ATTEMPTS; attempt++) {
            try {
                openActive(true);
                markRecovered(failure);
                return true;
            } catch (IOException exception) {
                failure = exception;
                noteFailure(exception, "open", attempt);
                closeWriterAfterFailure();
            }
        }
        failSink(failure, unconfirmedFirstSequence(), acceptedSequence.get(), "open-exhausted");
        return false;
    }

    private void openActive(boolean sessionHeader) throws IOException {
        Files.createDirectories(directory);
        boolean recovered = recoverTrailingLine(activePath);
        if (Files.exists(activePath, LinkOption.NOFOLLOW_LINKS) && Files.size(activePath) >= maxFileBytes) {
            rotateAndOpen();
            retainFiles();
            return;
        }
        activeBytes = Files.exists(activePath, LinkOption.NOFOLLOW_LINKS) ? Files.size(activePath) : 0L;
        failureProbe.check("open");
        writer = Files.newBufferedWriter(activePath, StandardCharsets.UTF_8,
            StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        if (!sessionHeader && activeBytes > 0L) {
            return;
        }
        writeHeader(header(recovered));
        flushWriter();
        retainFiles();
    }

    private Map<String, Object> header(boolean recovered) {
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("kind", "header");
        header.put("schema", 1);
        header.put("channel", "resync-lifecycle");
        header.put("processSessionId", processSessionId.toString());
        header.put("sequence", 0L);
        header.put("timestamp", Instant.now().toString());
        header.put("thread", LifecycleDiagnosticPolicy.safeThread(writerThread.getName()));
        header.put("mode", mode.wireName());
        header.put("tailRecovered", recovered);
        header.put("criticalCapacity", LifecycleDiagnosticPolicy.CRITICAL_CAPACITY);
        header.put("normalCapacity", LifecycleDiagnosticPolicy.NORMAL_CAPACITY);
        header.put("batchSize", LifecycleDiagnosticPolicy.MAX_BATCH_SIZE);
        header.put("batchDelayMs", LifecycleDiagnosticPolicy.MAX_BATCH_DELAY_MILLIS);
        header.put("rotationBytes", maxFileBytes);
        header.put("retentionAgeMs", LifecycleDiagnosticPolicy.RETENTION_AGE_MILLIS);
        header.put("retentionBytes", LifecycleDiagnosticPolicy.RETENTION_BYTES);
        return header;
    }

    private void writeHeader(Map<String, Object> header) throws IOException {
        failureProbe.check("header");
        writeRawLine(json(header));
    }

    private boolean writeEvent(QueuedEvent queued) {
        DiagnosticEvent event = queued.event();
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("kind", "event");
        document.put("schema", 1);
        document.put("processSessionId", processSessionId.toString());
        document.put("sequence", queued.sequence());
        document.put("timestamp", Instant.ofEpochMilli(queued.timestampMillis()).toString());
        document.put("thread", LifecycleDiagnosticPolicy.safeThread(queued.threadName()));
        document.put("stage", event.stage());
        document.put("priority", event.priority().wireName());
        document.put("elapsedMs", event.elapsedMillis());
        document.put("identity", identity(event.identity()));
        document.put("values", values(event.values()));
        return writeSequencedRecovering(json(document), queued.sequence(), "event");
    }

    private boolean writeDropSummaryIfNeeded() {
        long droppedNow = dropped.get();
        long failedNow = failed.get();
        long offeredNow = offered.get();
        long acceptedNow = accepted.get();
        boolean healthDue = System.nanoTime() - lastSummaryNanos >= TimeUnit.SECONDS.toNanos(30L);
        if (droppedNow == lastReportedDropped && failedNow == lastReportedFailed && !healthDue) {
            return true;
        }
        long summarySequence;
        synchronized (lifecycleMonitor) {
            if (!criticalQueue.isEmpty() || !normalQueue.isEmpty() || bufferedNormal != null) {
                return true;
            }
            summarySequence = nextSequence.incrementAndGet();
        }
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("kind", healthDue ? "health" : "drop-summary");
        document.put("schema", 1);
        document.put("processSessionId", processSessionId.toString());
        document.put("sequence", summarySequence);
        document.put("timestamp", Instant.now().toString());
        document.put("thread", LifecycleDiagnosticPolicy.safeThread(writerThread.getName()));
        document.put("dropped", droppedNow);
        document.put("offered", offeredNow);
        document.put("accepted", acceptedNow);
        document.put("failed", failedNow);
        document.put("state", state.get().name());
        document.put("lastAcceptedSequence", acceptedSequence.get());
        document.put("lastConfirmedSequence", confirmedSequence.get());
        document.put("reason", failureReason.get());
        document.put("queuedCritical", criticalQueue.size());
        document.put("queuedNormal", normalQueue.size());
        document.put("droppedMode", droppedMode.get());
        document.put("droppedPaused", droppedPaused.get());
        document.put("droppedClosed", droppedClosed.get());
        document.put("droppedThrottle", droppedThrottle.get());
        document.put("droppedNormalCapacity", droppedNormalCapacity.get());
        document.put("droppedCriticalCapacity", droppedCriticalCapacity.get());
        document.put("droppedCriticalPreempted", droppedCriticalPreempted.get());
        document.put("droppedTerminalCapacity", droppedTerminalCapacity.get());
        document.put("droppedFailed", droppedFailed.get());
        long droppedAcceptedFirst = droppedAcceptedFirstSequence.get();
        if (droppedAcceptedFirst != Long.MAX_VALUE) {
            document.put("droppedAcceptedFirstSequence", droppedAcceptedFirst);
            document.put("droppedAcceptedLastSequence", droppedAcceptedLastSequence.get());
        }
        writingSequence = summarySequence;
        if (!writeSequencedRecovering(json(document), summarySequence, "drop-summary")) {
            return false;
        }
        writingSequence = 0L;
        lastSummaryNanos = System.nanoTime();
        lastReportedDropped = droppedNow;
        lastReportedFailed = failedNow;
        lastReportedOffered = offeredNow;
        lastReportedAccepted = acceptedNow;
        return true;
    }

    private Map<String, Object> identity(DiagnosticIdentity identity) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        if (identity == null) {
            return result;
        }
        if (identity.serverId() != null) {
            result.put("serverId", identity.serverId().canonicalText());
        }
        if (identity.resource() != null) {
            result.put("resource", identity.resource().canonicalText());
        }
        if (identity.operation() != null) {
            result.put("operation", identity.operation().canonicalText());
        }
        if (identity.requestId() != null) {
            result.put("requestId", identity.requestId().toString());
        }
        if (identity.correlationId() != null) {
            result.put("correlationId", identity.correlationId().canonicalText());
        }
        if (identity.traceId() != null) {
            result.put("traceId", identity.traceId().canonicalText());
        }
        if (identity.mutationId() != null) {
            result.put("mutationId", identity.mutationId().toString());
        }
        if (identity.generation() != null) {
            result.put("generation", identity.generation());
        }
        if (identity.authorityEpoch() != null) {
            result.put("authorityEpoch", identity.authorityEpoch());
        }
        if (identity.revision() != null) {
            result.put("revision", identity.revision().value());
        }
        return result;
    }

    private Map<String, Object> values(Map<String, DiagnosticValue> source) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        if (source == null || source.isEmpty()) {
            return result;
        }
        int visited = 0;
        for (Map.Entry<String, DiagnosticValue> entry : source.entrySet()) {
            if (visited >= LifecycleDiagnosticPolicy.MAX_FIELDS) {
                break;
            }
            visited++;
            if (LifecycleDiagnosticPolicy.sensitiveField(entry.getKey())) {
                continue;
            }
            result.put(entry.getKey(), entry.getValue() instanceof DiagnosticValue.Text text
                ? LifecycleDiagnosticPolicy.safeFieldText(entry.getKey(), text.value()) : safeValue(entry.getValue(), 0));
        }
        if (source.size() > visited) {
            result.put("truncated", true);
        }
        return result;
    }

    private Object safeValue(Object value, int depth) {
        if (value == null) {
            return "";
        }
        if (value instanceof DiagnosticValue.Text text) {
            return LifecycleDiagnosticPolicy.safeText(text.value());
        }
        if (value instanceof DiagnosticValue.IntegerValue integer) {
            return integer.value();
        }
        if (value instanceof DiagnosticValue.DecimalValue decimal) {
            return decimal.value();
        }
        if (value instanceof DiagnosticValue.BooleanValue booleanValue) {
            return booleanValue.value();
        }
        if (value instanceof String text) {
            return LifecycleDiagnosticPolicy.safeText(text);
        }
        if (value instanceof Character character) {
            return LifecycleDiagnosticPolicy.safeText(character.toString());
        }
        if (value instanceof Boolean || value instanceof Long || value instanceof Integer
            || value instanceof Short || value instanceof Byte) {
            return value;
        }
        if (value instanceof Number number) {
            String text = number.toString();
            return text.length() <= LifecycleDiagnosticPolicy.MAX_VALUE_CHARS ? number : "[redacted]";
        }
        if (depth >= LifecycleDiagnosticPolicy.MAX_COLLECTION_DEPTH) {
            return "[nested]";
        }
        if (value instanceof DiagnosticValue.MapValue mapValue) {
            return safeMap(mapValue.values(), depth);
        }
        if (value instanceof DiagnosticValue.ListValue listValue) {
            return safeIterable(listValue.values(), depth);
        }
        if (value instanceof Map<?, ?> map) {
            return safeMap(map, depth);
        }
        if (value instanceof Iterable<?> iterable) {
            return safeIterable(iterable, depth);
        }
        return LifecycleDiagnosticPolicy.safeText(value.toString());
    }

    private Map<String, Object> safeMap(Map<?, ?> map, int depth) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        int visited = 0;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (visited >= LifecycleDiagnosticPolicy.MAX_COLLECTION_ITEMS) {
                break;
            }
            visited++;
            if (!(entry.getKey() instanceof String key) || LifecycleDiagnosticPolicy.sensitiveField(key)) {
                continue;
            }
            result.put(key, safeValue(entry.getValue(), depth + 1));
        }
        if (map.size() > visited) {
            result.put("truncated", true);
        }
        return result;
    }

    private List<Object> safeIterable(Iterable<?> iterable, int depth) {
        List<Object> result = new ArrayList<>();
        int included = 0;
        for (Object item : iterable) {
            if (included >= LifecycleDiagnosticPolicy.MAX_COLLECTION_ITEMS) {
                break;
            }
            result.add(safeValue(item, depth + 1));
            included++;
        }
        if (iterable instanceof Collection<?> collection && collection.size() > included) {
            result.add("[truncated]");
        }
        return result;
    }

    private String json(Map<String, Object> document) {
        String line = JsonValue.fromJava(document).canonicalText();
        if (line.getBytes(StandardCharsets.UTF_8).length < LifecycleDiagnosticPolicy.MAX_LINE_BYTES) return line;
        LinkedHashMap<String, Object> reduced = new LinkedHashMap<>(document);
        LinkedHashMap<String, Object> fields = new LinkedHashMap<>();
        if (document.get("values") instanceof Map<?, ?> source) {
            for (String key : List.of("outcome", "diagnosticCode", "errorType", "reason", "status", "phase", "elapsedMs",
                "requestId", "correlationId", "mutationId", "generation", "revision")) {
                if (source.containsKey(key)) fields.put(key, source.get(key));
            }
            reduced.put("values", fields);
        }
        reduced.put("truncated", true);
        line = JsonValue.fromJava(reduced).canonicalText();
        while (line.getBytes(StandardCharsets.UTF_8).length >= LifecycleDiagnosticPolicy.MAX_LINE_BYTES && !fields.isEmpty()) {
            fields.remove(new ArrayList<>(fields.keySet()).get(fields.size() - 1));
            line = JsonValue.fromJava(reduced).canonicalText();
        }
        if (line.getBytes(StandardCharsets.UTF_8).length >= LifecycleDiagnosticPolicy.MAX_LINE_BYTES
            && reduced.get("identity") instanceof Map<?, ?> identity) {
            LinkedHashMap<String, Object> boundedIdentity = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : identity.entrySet()) {
                if (entry.getKey() instanceof String key && entry.getValue() != null) {
                    String text = entry.getValue().toString();
                    boundedIdentity.put(key, text.length() <= LifecycleDiagnosticPolicy.MAX_VALUE_CHARS
                        ? entry.getValue() : "[truncated]");
                }
            }
            reduced.put("identity", boundedIdentity);
            line = JsonValue.fromJava(reduced).canonicalText();
        }
        return line;
    }

    private boolean writeSequencedRecovering(String line, long sequence, String step) {
        if (sequence <= confirmedSequence.get()) {
            failSink(new IOException("Diagnostic sequence did not advance"), sequence, sequence, step + "-order");
            return false;
        }
        IOException failure = null;
        boolean rewriteProvenSafe = true;
        for (int attempt = 1; attempt <= MAX_RECOVERY_ATTEMPTS; attempt++) {
            if (!rewriteProvenSafe) {
                PersistenceEvidence evidence = persistenceEvidence(line);
                if (evidence == PersistenceEvidence.PRESENT) {
                    confirmSequence(sequence);
                    markRecovered(failure);
                    return true;
                }
                if (evidence == PersistenceEvidence.UNKNOWN) {
                    Thread.yield();
                    continue;
                }
                rewriteProvenSafe = true;
            }
            try {
                if (writer == null) {
                    openActive(false);
                }
                writeLine(line);
                flushWriter();
                confirmSequence(sequence);
                markRecovered(failure);
                return true;
            } catch (IOException exception) {
                failure = exception;
                noteFailure(exception, step, attempt);
                CloseResult closeResult = closeWriterAfterFailure();
                if (closeResult.failure() != null) {
                    exception.addSuppressed(closeResult.failure());
                }
                PersistenceEvidence evidence = persistenceEvidence(line);
                if (evidence == PersistenceEvidence.PRESENT) {
                    confirmSequence(sequence);
                    markRecovered(exception);
                    return true;
                }
                rewriteProvenSafe = evidence == PersistenceEvidence.ABSENT && closeResult.closed();
            }
        }
        failSink(failure, sequence, acceptedSequence.get(), step + "-exhausted");
        return false;
    }

    private void confirmSequence(long sequence) {
        long previous = confirmedSequence.get();
        if (sequence <= previous) {
            throw new IllegalStateException("Diagnostic sequence did not advance");
        }
        confirmedSequence.set(sequence);
    }

    private PersistenceEvidence persistenceEvidence(String line) {
        byte[] expected = (line + "\n").getBytes(StandardCharsets.UTF_8);
        try {
            long size = Files.size(activePath);
            if (size < expected.length) {
                return PersistenceEvidence.ABSENT;
            }
            ByteBuffer buffer = ByteBuffer.allocate(expected.length);
            try (FileChannel channel = FileChannel.open(activePath, StandardOpenOption.READ)) {
                channel.position(size - expected.length);
                while (buffer.hasRemaining()) {
                    if (channel.read(buffer) < 0) {
                        return PersistenceEvidence.UNKNOWN;
                    }
                }
            }
            return Arrays.equals(buffer.array(), expected) ? PersistenceEvidence.PRESENT : PersistenceEvidence.ABSENT;
        } catch (NoSuchFileException exception) {
            return PersistenceEvidence.ABSENT;
        } catch (IOException exception) {
            return PersistenceEvidence.UNKNOWN;
        }
    }

    private void writeLine(String line) throws IOException {
        byte[] bytes = (line + "\n").getBytes(StandardCharsets.UTF_8);
        if (bytes.length > LifecycleDiagnosticPolicy.MAX_LINE_BYTES) {
            throw new IOException("Diagnostic line exceeds the bounded size");
        }
        if (activeBytes > 0L && activeBytes + bytes.length > maxFileBytes) {
            rotateAndOpen();
        }
        writeRawLine(line);
    }

    private void writeRawLine(String line) throws IOException {
        if (writer == null) {
            throw new IOException("Diagnostic writer is unavailable");
        }
        failureProbe.check("write");
        writer.write(line);
        writer.write('\n');
        activeBytes += line.getBytes(StandardCharsets.UTF_8).length + 1L;
        failureProbe.check("post-write");
    }

    private void flushWriter() throws IOException {
        BufferedWriter current = writer;
        if (current == null) {
            throw new IOException("Diagnostic writer is unavailable");
        }
        failureProbe.check("flush");
        current.flush();
        failureProbe.check("post-flush");
    }

    private void completeFlushRequest() {
        synchronized (lifecycleMonitor) {
            FlushRequest request = flushRequest.get();
            if (request == null || !ioFailed.get()
                && hasPendingAtOrBefore(request.watermark())) {
                return;
            }
            if (flushRequest.compareAndSet(request, null)) {
                request.latch().countDown();
            }
        }
    }

    private boolean hasPendingAtOrBefore(long watermark) {
        return writingSequence > 0L && writingSequence <= watermark
            || heldFirstSequence > 0L && heldFirstSequence <= watermark
            || bufferedNormal != null && bufferedNormal.sequence() <= watermark
            || hasPendingAtOrBefore(criticalQueue, watermark) || hasPendingAtOrBefore(normalQueue, watermark);
    }

    private boolean hasPendingAtOrBefore(BlockingQueue<QueuedEvent> queue, long watermark) {
        for (QueuedEvent event : queue) {
            if (event.sequence() <= watermark) {
                return true;
            }
        }
        return false;
    }

    private void rotateAndOpen() throws IOException {
        closeWriter();
        if (Files.exists(activePath, LinkOption.NOFOLLOW_LINKS) && Files.size(activePath) > 0L) {
            Path target = rotatedPath();
            moveWithoutReplacement(activePath, target);
            failureProbe.check("post-move");
        }
        failureProbe.check("open");
        writer = Files.newBufferedWriter(activePath, StandardCharsets.UTF_8,
            StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        activeBytes = Files.size(activePath);
        writeHeader(header(false));
        flushWriter();
        retainFiles();
    }

    private Path rotatedPath() throws IOException {
        for (int attempt = 0; attempt < 100; attempt++) {
            rotationSequence++;
            Path target = directory.resolve(rotatedFileName(System.currentTimeMillis(), rotationSequence,
                processSessionId));
            if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                return target;
            }
        }
        throw new IOException("Diagnostic rotation name is unavailable");
    }

    static String rotatedFileName(long timestamp, long sequence, UUID sessionId) {
        return ROTATED_PREFIX + String.format(Locale.ROOT, "%020d.%020d", timestamp, sequence) + "."
            + sessionId + ROTATED_SUFFIX;
    }

    private void moveWithoutReplacement(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target);
        } catch (FileAlreadyExistsException exception) {
            Files.move(source, rotatedPath());
        }
    }

    private void retainFiles() {
        if (directory == null) {
            return;
        }
        try (Stream<Path> paths = Files.list(directory)) {
            List<Path> rotated = paths.filter(this::isRotatedFile)
                .sorted(Comparator.comparingLong(this::lastModified).reversed())
                .toList();
            long total = Files.exists(activePath, LinkOption.NOFOLLOW_LINKS) ? Files.size(activePath) : 0L;
            long now = System.currentTimeMillis();
            for (Path path : rotated) {
                long size = Files.size(path);
                long age = Math.max(0L, now - lastModified(path));
                if (age > LifecycleDiagnosticPolicy.RETENTION_AGE_MILLIS || total + size > LifecycleDiagnosticPolicy.RETENTION_BYTES) {
                    Files.deleteIfExists(path);
                } else {
                    total += size;
                }
            }
        } catch (IOException ignored) {
        }
    }

    private boolean isRotatedFile(Path path) {
        String name = path.getFileName().toString();
        return !path.equals(activePath) && name.startsWith(ROTATED_PREFIX) && name.endsWith(ROTATED_SUFFIX);
    }

    private long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis();
        } catch (IOException ignored) {
            return 0L;
        }
    }

    private boolean recoverTrailingLine(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        long size = Files.size(path);
        if (size == 0L) {
            return false;
        }
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            ByteBuffer last = ByteBuffer.allocate(1);
            channel.position(size - 1L);
            if (channel.read(last) != 1) {
                return false;
            }
            if (last.array()[0] == '\n') {
                return false;
            }
            long keep = lastNewline(channel, size);
            channel.truncate(keep);
            return true;
        }
    }

    private long lastNewline(FileChannel channel, long size) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(4096);
        long cursor = size;
        while (cursor > 0L) {
            int length = (int) Math.min(buffer.capacity(), cursor);
            cursor -= length;
            buffer.clear().limit(length);
            channel.position(cursor);
            int read = channel.read(buffer);
            if (read <= 0) {
                continue;
            }
            for (int index = read - 1; index >= 0; index--) {
                if (buffer.get(index) == '\n') {
                    return cursor + index + 1L;
                }
            }
        }
        return 0L;
    }

    private boolean throttled(DiagnosticEvent event) {
        String key = LifecycleDiagnosticPolicy.progressKey(event);
        synchronized (progressThrottleMonitor) {
            long now = System.nanoTime();
            Long previous = progressThrottle.get(key);
            if (previous != null && now - previous < LifecycleDiagnosticPolicy.PROGRESS_THROTTLE_NANOS) {
                return true;
            }
            if (previous != null) {
                progressThrottle.put(key, now);
                return false;
            }
            while (progressThrottle.size() >= 256) {
                String first = progressThrottle.keySet().stream().findFirst().orElse(null);
                if (first == null) {
                    break;
                }
                progressThrottle.remove(first);
            }
            progressThrottle.put(key, now);
            return false;
        }
    }

    private boolean offerQueued(BlockingQueue<QueuedEvent> queue, QueuedEvent queued) {
        if (queue.offer(queued)) {
            return true;
        }
        if (queued.event().priority() == DiagnosticSink.Priority.TERMINAL) {
            QueuedEvent displaced = criticalQueue.stream()
                .filter(candidate -> candidate.event().priority() != DiagnosticSink.Priority.TERMINAL)
                .findFirst().orElse(null);
            if (displaced != null && criticalQueue.remove(displaced) && criticalQueue.offer(queued)) {
                recordDrop(droppedCriticalPreempted);
                recordDroppedAccepted(displaced.sequence());
                return true;
            }
            recordDrop(droppedTerminalCapacity);
            scheduleExternalReport("terminal-capacity", queued.sequence(), queued.sequence(), false);
            return false;
        }
        recordDrop(queue == criticalQueue ? droppedCriticalCapacity : droppedNormalCapacity);
        return false;
    }

    private void recordDrop(AtomicLong reason) {
        dropped.incrementAndGet();
        reason.incrementAndGet();
    }

    private void recordDroppedAccepted(long sequence) {
        droppedAcceptedFirstSequence.accumulateAndGet(sequence, Math::min);
        droppedAcceptedLastSequence.accumulateAndGet(sequence, Math::max);
        scheduleExternalReport("critical-preempted", sequence, sequence, false);
    }

    private void noteFailure(Throwable failure, String step, int attempt) {
        failed.incrementAndGet();
        failureReason.set("recovering:" + step + ":" + failure.getClass().getSimpleName() + ":attempt=" + attempt);
    }

    private void markRecovered(Throwable failure) {
        if (failure != null) {
            failureReason.set("recovered:" + failure.getClass().getSimpleName());
        }
    }

    private void failSink(Throwable failure, long firstSequence, long lastSequence, String step) {
        failed.incrementAndGet();
        String type = failure == null ? "UnknownFailure" : failure.getClass().getSimpleName();
        long first;
        long last;
        synchronized (lifecycleMonitor) {
            ioFailed.set(true);
            long pending = unconfirmedFirstSequence();
            long preempted = droppedAcceptedFirstSequence.get();
            if (preempted != Long.MAX_VALUE) {
                pending = pending <= 0L ? preempted : Math.min(pending, preempted);
            }
            if (firstSequence <= 0L) {
                first = pending;
            } else if (pending <= 0L) {
                first = firstSequence;
            } else {
                first = Math.min(firstSequence, pending);
            }
            last = Math.max(first, Math.max(lastSequence, acceptedSequence.get()));
            String range = first > 0L && first <= last ? first + "-" + last : "none";
            failureReason.set(step + ":" + type + ":unconfirmed=" + range);
            if (!stopRequested.get()) {
                state.set(DiagnosticSink.State.FAILED);
            }
        }
        scheduleExternalReport(step + ":" + type, first, last, true);
        completeFlushRequest();
        reportOutsideIfDue(true);
    }

    private long unconfirmedFirstSequence() {
        long first = Long.MAX_VALUE;
        long confirmed = confirmedSequence.get();
        long acceptedNow = acceptedSequence.get();
        if (acceptedNow > confirmed) {
            first = confirmed + 1L;
        }
        if (writingSequence > 0L) {
            first = Math.min(first, writingSequence);
        }
        if (heldFirstSequence > 0L) {
            first = Math.min(first, heldFirstSequence);
        }
        if (bufferedNormal != null) {
            first = Math.min(first, bufferedNormal.sequence());
        }
        QueuedEvent critical = criticalQueue.peek();
        QueuedEvent normal = normalQueue.peek();
        if (critical != null) {
            first = Math.min(first, critical.sequence());
        }
        if (normal != null) {
            first = Math.min(first, normal.sequence());
        }
        return first == Long.MAX_VALUE ? 0L : first;
    }

    private void scheduleExternalReport(String reason, long firstSequence, long lastSequence, boolean uncertain) {
        synchronized (externalReportMonitor) {
            ExternalReport next = new ExternalReport(reason, 1L, firstSequence, lastSequence, uncertain);
            if (uncertain) {
                pendingUncertainReport = pendingUncertainReport == null ? next : pendingUncertainReport.merge(next);
            } else {
                pendingDroppedReport = pendingDroppedReport == null ? next : pendingDroppedReport.merge(next);
            }
        }
    }

    private void reportOutsideIfDue(boolean force) {
        ExternalReport droppedReport;
        ExternalReport uncertainReport;
        long now = System.nanoTime();
        synchronized (externalReportMonitor) {
            if (pendingDroppedReport == null && pendingUncertainReport == null) {
                return;
            }
            if (!force && lastExternalReportNanos != 0L
                && now - lastExternalReportNanos < EXTERNAL_REPORT_INTERVAL_NANOS) {
                return;
            }
            droppedReport = pendingDroppedReport;
            uncertainReport = pendingUncertainReport;
            pendingDroppedReport = null;
            pendingUncertainReport = null;
            lastExternalReportNanos = now;
        }
        reportOutside(droppedReport);
        reportOutside(uncertainReport);
    }

    private void reportOutside(ExternalReport report) {
        if (report == null) {
            return;
        }
        String label = report.uncertain() ? "unconfirmedSequence" : "droppedSequence";
        String range = report.firstSequence() > 0L && report.firstSequence() <= report.lastSequence()
            ? report.firstSequence() + "-" + report.lastSequence() : "none";
        try {
            externalReporter.report("ReSync lifecycle diagnostics warning: reason=" + report.reason()
                + " count=" + report.count() + " " + label + "=" + range);
        } catch (RuntimeException ignored) {
        }
    }

    private void closeWriter() throws IOException {
        BufferedWriter current = writer;
        writer = null;
        if (current == null) {
            return;
        }
        IOException failure = null;
        try {
            current.flush();
        } catch (IOException exception) {
            failure = exception;
        }
        try {
            current.close();
            failureProbe.check("close");
        } catch (IOException exception) {
            if (failure == null) {
                failure = exception;
            } else {
                failure.addSuppressed(exception);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private void closeWriterQuietly() {
        try {
            closeWriter();
        } catch (IOException exception) {
            failSink(exception, unconfirmedFirstSequence(), acceptedSequence.get(), "close");
        }
    }

    private CloseResult closeWriterAfterFailure() {
        BufferedWriter current = writer;
        writer = null;
        if (current == null) {
            return new CloseResult(null, true);
        }
        try {
            current.close();
        } catch (IOException exception) {
            return new CloseResult(exception, false);
        }
        try {
            failureProbe.check("close");
            return new CloseResult(null, true);
        } catch (IOException exception) {
            return new CloseResult(exception, true);
        }
    }

    private void joinWriter(long timeoutMillis) {
        try {
            writerThread.join(timeoutMillis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private record QueuedEvent(DiagnosticEvent event, String threadName, long timestampMillis, long sequence) {
    }

    @FunctionalInterface
    interface FailureProbe {
        void check(String step) throws IOException;
    }

    @FunctionalInterface
    interface ExternalReporter {
        void report(String message);
    }

    private enum PersistenceEvidence {
        PRESENT,
        ABSENT,
        UNKNOWN
    }

    private record ExternalReport(String reason, long count, long firstSequence, long lastSequence, boolean uncertain) {
        private ExternalReport merge(ExternalReport other) {
            String mergedReason = reason.equals(other.reason) ? reason : "multiple";
            long mergedFirst;
            if (firstSequence <= 0L) {
                mergedFirst = other.firstSequence;
            } else if (other.firstSequence <= 0L) {
                mergedFirst = firstSequence;
            } else {
                mergedFirst = Math.min(firstSequence, other.firstSequence);
            }
            return new ExternalReport(mergedReason, count + other.count, mergedFirst,
                Math.max(lastSequence, other.lastSequence), uncertain || other.uncertain);
        }
    }

    private record CloseResult(IOException failure, boolean closed) {
    }

    private static final class FlushRequest {
        private final AtomicLong watermark;
        private final CountDownLatch latch = new CountDownLatch(1);

        private FlushRequest(long watermark) {
            this.watermark = new AtomicLong(watermark);
        }

        private long watermark() {
            return watermark.get();
        }

        private void include(long value) {
            watermark.accumulateAndGet(value, Math::max);
        }

        private CountDownLatch latch() {
            return latch;
        }
    }
}
