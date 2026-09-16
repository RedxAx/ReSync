package restudio.resync.server;

import restudio.resync.diagnostics.DiagnosticEvent;
import restudio.resync.diagnostics.DiagnosticSink;
import restudio.resync.flow.identity.ServerId;

import java.lang.reflect.Array;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

public final class TemporaryLifecycleDiagnostics {
    public static final String MODE_PROPERTY = LifecycleDiagnosticPolicy.MODE_PROPERTY;
    public static final String MODE_ENVIRONMENT = LifecycleDiagnosticPolicy.MODE_ENVIRONMENT;
    public static final String PROPERTY = LifecycleDiagnosticPolicy.LEGACY_PROPERTY;
    public static final String ENVIRONMENT = LifecycleDiagnosticPolicy.LEGACY_ENVIRONMENT;
    static final int MAX_FIELDS = 32;
    static final int MAX_VALUE_CHARS = 240;
    static final int MAX_LINE_CHARS = 4096;
    static final int MAX_COLLECTION_ITEMS = 16;
    static final int MAX_COLLECTION_DEPTH = 3;
    private static final String TRUNCATED_FIELD = " truncated=true";
    private static final AtomicReference<String> SERVER_ID = new AtomicReference<>("");
    private static final AtomicReference<DiagnosticSink> SINK = new AtomicReference<>(
        LifecycleDiagnosticFileSink.disabled());
    private static final AtomicReference<String> LAST_WARNING = new AtomicReference<>("");
    private static final LongAdder RESIDENT_PLAN_HITS = new LongAdder();
    private static final LongAdder RESIDENT_PLAN_MISSES = new LongAdder();
    private static final LongAdder RESIDENT_PLAN_REPLACEMENTS = new LongAdder();
    private static final LongAdder RESIDENT_PLAN_INVALIDATIONS = new LongAdder();
    private static final LongAdder FILESYSTEM_VALIDATION_ATTEMPTS = new LongAdder();
    private static final LongAdder SYNCHRONOUS_CANCELLATION_PREFIXES = new LongAdder();
    private static final LongAdder SYNCHRONOUS_CANCELLATION_PREFIX_NANOS = new LongAdder();
    private static final LongAdder DEFERRED_EXECUTIONS = new LongAdder();
    private static final LongAdder DEFERRED_EXECUTION_NANOS = new LongAdder();
    private static final LongAdder DEFERRED_BATCHES = new LongAdder();
    private static final Map<ExecutionPhase, TimingWindow> EXECUTION_TIMINGS = Map.of(
        ExecutionPhase.BLOCK_DISPATCH, new TimingWindow(),
        ExecutionPhase.CANCELLATION_RETURN, new TimingWindow(),
        ExecutionPhase.EVENT_COMPLETION, new TimingWindow(),
        ExecutionPhase.COMMAND_RETURN, new TimingWindow());
    private static final List<String> FIELD_ORDER = List.of(
        "stage", "serverId", "typedKey", "resourceType", "resourceId", "moduleId", "participantId", "operation", "requestId", "correlationId",
        "traceId", "mutationId", "generation", "authorityEpoch", "revision", "elapsedMs", "outcome",
        "diagnosticCode", "reason", "state", "receiptStatus", "phase", "stageName", "source", "protocol", "core",
        "recovery", "conflict", "rejection", "errorCode", "transactionId", "responseMutationId", "responseRevision",
        "resultRevision", "authoritativeRevision", "primaryRevision", "metadataRevision", "sequence", "keyCount",
        "count", "bindingCount", "participantCount", "resourceCount", "assetCount", "metadataDeltaCount",
        "removedResourceCount", "pendingCount",
        "recoveredCount", "skippedCount", "blockedCount", "unpublishedCount", "mutationCount", "journalCount",
        "preparedCount", "historyEntryCount", "inspectionCount", "inspectedResourceCount", "chunkCount", "packets",
        "bytes", "frameBytes", "connectionBytes", "connectionRequests", "globalBytes", "globalRequests",
        "queueBytes", "queuePackets", "requestBytes", "maxFrameBytes", "mailboxGeneration", "readySessions",
        "coordinatorSequence", "coordinatorSequenceBefore", "coordinatorSequenceAfter", "coordinatorStateCount",
        "durableMutationCount", "validationMode", "evidenceChange", "storageTraversal", "metadataCoupled",
        "inputChars", "outputChars", "participantElapsedMs", "totalElapsedMs", "flushMs", "quiesceMs", "rebindMs", "authoritativeChecksMs",
        "resumeMs", "derivedChecksMs", "ownershipValidationMs", "activationMs", "convergenceMs", "fullValidationPasses",
        "participantTimings", "cpuMs", "phaseCpuTimings",
        "incrementalValidationPasses",
        "managerAssetPathCanonicalizations", "managerFullJournalPasses", "managerIndexedMutationLookups",
        "managerJournalReads", "managerStagedPayloadReads", "watcherAcceptedEvidencePaths",
        "watcherFullRegistrationPasses", "watcherIncrementalDirectoryVisits", "watcherIncrementalRegistrationPasses",
        "watcherInvalidations", "watcherRegisteredDirectories", "property", "environment", "changed", "success",
        "requestHash", "queryHash", "cursorPresent", "requestedLimit", "discoveredCount", "materializedCount",
        "visibleCount", "pageStart", "pageEnd", "pageComplete", "adapterRegistered", "projectMetadataRegistered",
        "createSupported", "aggregateCreateSupported", "coreAuthorityBound", "coreAuthorityAvailable",
        "coreAggregateCreateSupported", "handlerCount", "matchedHandler", "subscriberHash", "notificationChannel",
        "connectionHash", "payloadKind", "storedState", "decoded", "validated", "durableCommitted", "transitionPublished",
        "receiptPersisted", "responsePrepared", "deliveryFinal", "transportCode", "hasResponse", "retry",
        "queueWaitMs", "requestAgeMs", "connectionRequestLimit", "connectionByteLimit", "globalRequestLimit",
        "globalByteLimit", "accepting", "shutdown", "laneClosed", "scheduled", "thread", "admittedThread", "failure"
    );
    private static final Set<String> DEFAULT_FIELDS = Set.of(
        "stage", "serverId", "typedKey", "operation", "requestId", "correlationId", "traceId", "mutationId",
        "generation", "authorityEpoch", "revision", "elapsedMs"
    );
    private static final Set<String> SENSITIVE_FIELDS = Set.of(
        "payload", "canonicalpayload", "authorization", "token", "secret", "password", "sql", "path",
        "session", "user", "address", "actor", "client"
    );
    private static final Pattern SENSITIVE_ASSIGNMENT = Pattern.compile(
        "(?i)\\b(?:payload|canonicalpayload|authorization|token|secret|password|session|user|address|actor|client)\\b\\s*[:=]"
    );
    private static final Pattern ABSOLUTE_PATH = Pattern.compile(
        "(?i)[a-z]:[\\\\/]|/(?:[^\\s/]+/)+"
    );
    private static final Pattern SQL_TEXT = Pattern.compile(
        "(?i)\\b(?:select|insert|update|delete|alter|create|drop|pragma)\\b.+\\b(?:from|into|table|where|set)\\b"
    );

    private TemporaryLifecycleDiagnostics() {
    }

    public static boolean enabled() {
        return SINK.get().enabled();
    }

    public static DiagnosticSink.Status status() {
        return SINK.get().status();
    }

    static LifecycleDiagnosticFileSink bind(Path operatorDataRoot) {
        LifecycleDiagnosticFileSink sink = LifecycleDiagnosticFileSink.open(operatorDataRoot);
        bind(sink);
        return sink;
    }

    static void bind(DiagnosticSink sink) {
        DiagnosticSink replacement = sink == null ? LifecycleDiagnosticFileSink.disabled() : sink;
        DiagnosticSink previous = SINK.getAndSet(replacement);
        LAST_WARNING.set("");
        if (previous != replacement) {
            previous.close();
        }
    }

    public static DiagnosticSink.Flush flush() {
        return SINK.get().flush();
    }

    public static void close() {
        DiagnosticSink previous = SINK.getAndSet(LifecycleDiagnosticFileSink.disabled());
        previous.close();
        SERVER_ID.set("");
    }

    static void close(DiagnosticSink expected) {
        if (expected == null) {
            return;
        }
        if (SINK.compareAndSet(expected, LifecycleDiagnosticFileSink.disabled())) {
            SERVER_ID.set("");
        }
        expected.close();
    }

    public static void bindServerId(ServerId serverId) {
        if (serverId != null) {
            SERVER_ID.set(serverId.canonicalText());
        }
    }

    public static long start() {
        return enabled() ? System.nanoTime() : 0L;
    }

    public static void residentPlanHit() {
        RESIDENT_PLAN_HITS.increment();
    }

    public static void residentPlanMiss() {
        RESIDENT_PLAN_MISSES.increment();
    }

    public static void residentPlanReplaced() {
        RESIDENT_PLAN_REPLACEMENTS.increment();
    }

    public static void residentPlanInvalidated() {
        RESIDENT_PLAN_INVALIDATIONS.increment();
    }

    public static void filesystemValidationAttempt() {
        FILESYSTEM_VALIDATION_ATTEMPTS.increment();
    }

    public static void synchronousCancellationPrefix(long elapsedNanos) {
        SYNCHRONOUS_CANCELLATION_PREFIXES.increment();
        SYNCHRONOUS_CANCELLATION_PREFIX_NANOS.add(Math.max(0L, elapsedNanos));
        recordTiming(ExecutionPhase.CANCELLATION_RETURN, elapsedNanos);
    }

    public static void deferredExecution(long elapsedNanos) {
        DEFERRED_EXECUTIONS.increment();
        DEFERRED_EXECUTION_NANOS.add(Math.max(0L, elapsedNanos));
    }

    public static void deferredBatch() {
        DEFERRED_BATCHES.increment();
    }

    public static void recordTiming(ExecutionPhase phase, long elapsedNanos) {
        EXECUTION_TIMINGS.get(phase).record(elapsedNanos);
    }

    public static Timing executionTiming(ExecutionPhase phase) {
        return EXECUTION_TIMINGS.get(phase).snapshot();
    }

    public static void resetExecutionTimings() {
        EXECUTION_TIMINGS.values().forEach(TimingWindow::reset);
    }

    public enum ExecutionPhase {
        BLOCK_DISPATCH("Block Dispatch"),
        CANCELLATION_RETURN("Cancellation Return"),
        EVENT_COMPLETION("Block Completion"),
        COMMAND_RETURN("Command Return");

        private final String label;

        ExecutionPhase(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public record Timing(long observed, int retained, long p95Nanos, long p99Nanos, long maximumNanos) {}

    static final class TimingWindow {
        private final long[] samples = new long[4096];
        private long observed;
        private int cursor;
        private int retained;

        synchronized void record(long nanos) {
            samples[cursor] = Math.max(0L, nanos);
            cursor = (cursor + 1) % samples.length;
            retained = Math.min(samples.length, retained + 1);
            observed++;
        }

        Timing snapshot() {
            long count;
            long[] values;
            synchronized (this) {
                count = observed;
                values = Arrays.copyOf(samples, retained);
            }
            if (values.length == 0) {
                return new Timing(count, 0, 0L, 0L, 0L);
            }
            Arrays.sort(values);
            return new Timing(count, values.length, values[(values.length * 95 + 99) / 100 - 1],
                values[(values.length * 99 + 99) / 100 - 1], values[values.length - 1]);
        }

        synchronized void reset() {
            observed = 0L;
            cursor = 0;
            retained = 0;
        }
    }

    public static HotPathSnapshot hotPathSnapshot() {
        return new HotPathSnapshot(
            RESIDENT_PLAN_HITS.sum(),
            RESIDENT_PLAN_MISSES.sum(),
            RESIDENT_PLAN_REPLACEMENTS.sum(),
            RESIDENT_PLAN_INVALIDATIONS.sum(),
            FILESYSTEM_VALIDATION_ATTEMPTS.sum(),
            SYNCHRONOUS_CANCELLATION_PREFIXES.sum(),
            SYNCHRONOUS_CANCELLATION_PREFIX_NANOS.sum(),
            DEFERRED_EXECUTIONS.sum(),
            DEFERRED_EXECUTION_NANOS.sum(),
            DEFERRED_BATCHES.sum());
    }

    public record HotPathSnapshot(
        long residentPlanHits,
        long residentPlanMisses,
        long residentPlanReplacements,
        long residentPlanInvalidations,
        long filesystemValidationAttempts,
        long synchronousCancellationPrefixes,
        long synchronousCancellationPrefixNanos,
        long deferredExecutions,
        long deferredExecutionNanos,
        long deferredBatches
    ) {
        public double averageSynchronousCancellationPrefixMicros() {
            return synchronousCancellationPrefixes == 0L ? 0.0
                : synchronousCancellationPrefixNanos / 1_000.0 / synchronousCancellationPrefixes;
        }

        public double averageDeferredExecutionMicros() {
            return deferredExecutions == 0L ? 0.0 : deferredExecutionNanos / 1_000.0 / deferredExecutions;
        }
    }

    public static String safeHash(Object value) {
        if (!enabled() || value == null) {
            return "";
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(String.valueOf(value).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 12);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public static void event(String stage, long startedNanos, Map<String, ?> values) {
        emit(stage, startedNanos, values, false);
    }

    public static void eventElapsed(String stage, long elapsedMillis, Map<String, ?> values) {
        emitElapsed(stage, elapsedMillis, values, false);
    }

    public static void terminal(String stage, long startedNanos, Map<String, Object> identity, String outcome,
                                String diagnosticCode, String reason) {
        emit(stage, startedNanos, with(identity,
            "outcome", terminalValue(outcome, "unknown"),
            "diagnosticCode", terminalValue(diagnosticCode, "LIFECYCLE.UNSPECIFIED"),
            "reason", reason), true);
    }

    private static void emit(String stage, long startedNanos, Map<String, ?> values, boolean terminal) {
        emitElapsed(stage, elapsedMillis(startedNanos), values, terminal);
    }

    private static void emitElapsed(String stage, long elapsedMillis, Map<String, ?> values, boolean terminal) {
        DiagnosticSink sink = SINK.get();
        DiagnosticSink.Status status = sink.status();
        if (status.failed()) {
            warn(status);
            return;
        }
        if (!status.enabled()) {
            return;
        }
        try {
            DiagnosticEvent event = LifecycleDiagnosticEventAdapter.event(stage, elapsedMillis,
                SERVER_ID.get(), values, terminal);
            DiagnosticSink.Offer offer = sink.offer(event);
            if (offer == DiagnosticSink.Offer.FAILED) {
                warn(sink.status());
            }
        } catch (RuntimeException exception) {
            warn("offer:" + exception.getClass().getSimpleName());
        }
    }

    static String lastWarning() {
        return LAST_WARNING.get();
    }

    private static void warn(DiagnosticSink.Status status) {
        warn("state=" + status.state() + " reason=" + status.reason() + " failures=" + status.failures()
            + " dropped=" + status.dropped());
    }

    private static void warn(String detail) {
        if (LAST_WARNING.compareAndSet("", detail)) {
            System.err.println("ReSync lifecycle diagnostics warning: " + detail);
        }
    }

    public static Map<String, Object> identity(Object serverId, Object typedKey, Object mutationId, Object requestId,
                                                Object correlationId, Object revision, Object authorityEpoch,
                                                Object generation) {
        Object[] values = {serverId, typedKey, mutationId, requestId, correlationId, revision, authorityEpoch, generation};
        String[] fields = {
            "serverId", "typedKey", "mutationId", "requestId", "correlationId", "revision", "authorityEpoch", "generation"
        };
        LinkedHashMap<String, Object> identity = new LinkedHashMap<>();
        for (int index = 0; index < fields.length; index++) {
            if (values[index] != null) {
                identity.put(fields[index], values[index]);
            }
        }
        return identity;
    }

    public static Map<String, Object> identity(Object serverId, Object typedKey, Object operation, Object mutationId,
                                                Object requestId, Object correlationId, Object traceId, Object revision,
                                                Object authorityEpoch, Object generation) {
        Map<String, Object> identity = identity(serverId, typedKey, mutationId, requestId, correlationId, revision,
            authorityEpoch, generation);
        if (operation != null) {
            identity.put("operation", operation);
        }
        if (traceId != null) {
            identity.put("traceId", traceId);
        }
        return identity;
    }

    public static Map<String, Object> with(Map<String, Object> identity, Object... values) {
        LinkedHashMap<String, Object> fields = new LinkedHashMap<>();
        if (identity != null) {
            fields.putAll(identity);
        }
        if (values != null) {
            for (int index = 0; index + 1 < values.length; index += 2) {
                Object key = values[index];
                if (key != null) {
                    fields.put(key.toString(), values[index + 1]);
                }
            }
        }
        return fields;
    }

    static String format(String stage, long elapsedMs, String serverId, Map<String, ?> values) {
        List<Field> fields = new ArrayList<>();
        for (String name : FIELD_ORDER) {
            boolean provided = values != null && values.containsKey(name);
            if (!provided && !DEFAULT_FIELDS.contains(name)) {
                continue;
            }
            Object value = switch (name) {
                case "stage" -> stage;
                case "serverId" -> provided ? values.get(name) : serverId;
                case "elapsedMs" -> Math.max(0L, elapsedMs);
                default -> provided ? values.get(name) : null;
            };
            fields.add(new Field(name, encoded(normalized(value))));
        }

        boolean truncated = fields.size() > MAX_FIELDS;
        int fieldLimit = truncated ? MAX_FIELDS - 1 : MAX_FIELDS;
        StringBuilder line = new StringBuilder("TEMP lifecycle");
        int appended = 0;
        for (Field field : fields) {
            if (appended >= fieldLimit) {
                truncated = true;
                break;
            }
            String token = " " + field.name() + "=" + field.value();
            int reserve = fields.size() > appended + 1 ? TRUNCATED_FIELD.length() : 0;
            if (line.length() + token.length() + reserve > MAX_LINE_CHARS) {
                truncated = true;
                break;
            }
            line.append(token);
            appended++;
        }
        if (truncated && appended < MAX_FIELDS && line.length() + TRUNCATED_FIELD.length() <= MAX_LINE_CHARS) {
            line.append(TRUNCATED_FIELD);
        }
        return line.toString();
    }

    private static Object normalized(Object value) {
        return normalized(value, 0);
    }

    private static Object normalized(Object value, int depth) {
        try {
            return normalizeValue(value, depth);
        } catch (RuntimeException exception) {
            return "unavailable";
        }
    }

    private static Object normalizeValue(Object value, int depth) {
        if (value == null) {
            return "";
        }
        if (value instanceof AuthorityEpoch authorityEpoch) {
            return authorityEpoch.current();
        }
        if (value instanceof Path) {
            return "[path]";
        }
        if (value instanceof byte[] bytes) {
            return "[bytes:" + bytes.length + "]";
        }
        if (value instanceof char[] characters) {
            return "[chars:" + characters.length + "]";
        }
        if (value instanceof Map<?, ?> map) {
            return depth >= MAX_COLLECTION_DEPTH ? "[nested]" : boundedMap(map, depth + 1);
        }
        if (value instanceof Iterable<?> iterable) {
            return depth >= MAX_COLLECTION_DEPTH ? "[nested]"
                : boundedIterable(iterable, value instanceof Collection<?> collection ? collection.size() : -1, depth + 1);
        }
        if (value.getClass().isArray()) {
            return depth >= MAX_COLLECTION_DEPTH ? "[nested]" : boundedArray(value, depth + 1);
        }
        if (value instanceof Enum<?> enumeration) {
            return enumeration.name();
        }
        if (value instanceof Number || value instanceof Boolean || value instanceof Character) {
            return value;
        }
        return boundedText(value.toString());
    }

    private static String boundedMap(Map<?, ?> map, int depth) {
        StringBuilder value = new StringBuilder("{");
        int included = 0;
        int visited = 0;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (visited >= MAX_COLLECTION_ITEMS) {
                break;
            }
            visited++;
            String key = entry.getKey() == null ? "" : boundedText(entry.getKey().toString());
            if (!allowedNestedKey(key)) {
                continue;
            }
            if (included++ > 0) {
                value.append(',');
            }
            value.append(key).append(':').append(normalized(entry.getValue(), depth));
        }
        appendRemainder(value, map.size(), visited);
        return value.append('}').toString();
    }

    private static String boundedIterable(Iterable<?> iterable, int size, int depth) {
        StringBuilder value = new StringBuilder("[");
        int included = 0;
        Iterator<?> iterator = iterable.iterator();
        while (included < MAX_COLLECTION_ITEMS && iterator.hasNext()) {
            Object element = iterator.next();
            if (included++ > 0) {
                value.append(',');
            }
            value.append(normalized(element, depth));
        }
        appendRemainder(value, size, included, iterator.hasNext());
        return value.append(']').toString();
    }

    private static String boundedArray(Object array, int depth) {
        int length = Array.getLength(array);
        StringBuilder value = new StringBuilder("[");
        int limit = Math.min(length, MAX_COLLECTION_ITEMS);
        for (int index = 0; index < limit; index++) {
            if (index > 0) {
                value.append(',');
            }
            value.append(normalized(Array.get(array, index), depth));
        }
        appendRemainder(value, length, limit, length > limit);
        return value.append(']').toString();
    }

    private static void appendRemainder(StringBuilder value, int size, int included) {
        appendRemainder(value, size, included, size < 0);
    }

    private static void appendRemainder(StringBuilder value, int size, int included, boolean hasMore) {
        if (size > included) {
            if (included > 0) {
                value.append(',');
            }
            value.append("...(+").append(size - included).append(')');
        } else if (size < 0 && hasMore) {
            value.append(",...");
        }
    }

    private static boolean allowedNestedKey(String key) {
        String normalized = key.toLowerCase(Locale.ROOT);
        return SENSITIVE_FIELDS.stream().noneMatch(normalized::contains);
    }

    private static String boundedText(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        String text = value.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ').strip();
        boolean structured = text.length() > 1
            && ((text.charAt(0) == '{' && text.charAt(text.length() - 1) == '}')
            || (text.charAt(0) == '[' && text.charAt(text.length() - 1) == ']'));
        if (structured || SENSITIVE_ASSIGNMENT.matcher(text).find() || ABSOLUTE_PATH.matcher(text).find()
            || SQL_TEXT.matcher(text).find()) {
            return "[redacted]";
        }
        return limitedText(text);
    }

    private static String limitedText(String value) {
        String text = value.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ').strip();
        if (text.length() <= MAX_VALUE_CHARS) {
            return text;
        }
        return text.substring(0, MAX_VALUE_CHARS - 3) + "...";
    }

    private static long elapsedMillis(long startedNanos) {
        return startedNanos <= 0L ? 0L : TimeUnit.NANOSECONDS.toMillis(Math.max(0L, System.nanoTime() - startedNanos));
    }

    static boolean enabled(String property, String environment) {
        return LifecycleDiagnosticPolicy.resolveMode(null, null, property, environment).enabled();
    }

    private static String terminalValue(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String encoded(Object value) {
        String text = limitedText(value == null ? "" : value.toString());
        if (text.isEmpty()) {
            return "-";
        }
        boolean quoted = text.chars().anyMatch(character -> Character.isWhitespace(character) || character == '=' || character == '"');
        String escaped = text.replace("\\", "\\\\").replace("\"", "\\\"");
        return quoted ? '"' + escaped + '"' : escaped;
    }

    private record Field(String name, String value) {
    }
}
