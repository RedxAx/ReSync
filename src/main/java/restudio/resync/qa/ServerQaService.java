package restudio.resync.qa;

import org.bukkit.command.CommandSender;
import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.canonical.CanonicalLimits;
import restudio.resync.server.ProtocolRequestAuthority;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.function.LongSupplier;

public final class ServerQaService implements QaService {
    private static final int MAX_ACTIVE = 8;
    private static final int MAX_RECEIPTS = 256;
    private static final long MAX_RECEIPT_BYTES = 64L * 1024 * 1024;
    private final LongSupplier epoch;
    private final Map<String, Operation> operations = new LinkedHashMap<>();
    private final Map<UUID, Run> runs = new LinkedHashMap<>();
    private final UUID processId = UUID.randomUUID();
    private Map<String, Object> description;
    private int active;
    private long residentBytes;
    private boolean closed;

    public ServerQaService(LongSupplier epoch) {
        this.epoch = Objects.requireNonNull(epoch, "Authority epoch is required");
    }

    public synchronized void register(Map<String, Object> group, Handler handler) {
        Objects.requireNonNull(handler, "QA handler is required");
        if (closed || !runs.isEmpty()) throw new IllegalStateException("QA operations are already active");
        if (!(group.get("operations") instanceof List<?> descriptors)) throw new IllegalArgumentException("QA operation descriptions are required");
        for (Object descriptor : descriptors) {
            if (!(descriptor instanceof Map<?, ?> map) || !(map.get("id") instanceof String id) || id.isBlank()) {
                throw new IllegalArgumentException("QA operation identity is required");
            }
            Map<String, Object> admitted = immutable((Map<String, Object>) map);
            if (operations.putIfAbsent(id, new Operation(admitted, handler)) != null) throw new IllegalArgumentException("Duplicate QA operation: " + id);
        }
        description = null;
    }

    @Override
    public synchronized Map<String, Object> describe() {
        if (description == null) {
            description = immutable(Map.of("version", 1, "processId", processId.toString(), "operations",
                operations.values().stream().map(Operation::description).toList(), "limits", Map.of(
                    "activeRequests", MAX_ACTIVE, "retainedReceipts", MAX_RECEIPTS, "receiptBytes", MAX_RECEIPT_BYTES,
                    "requestBytes", CanonicalLimits.standard().inputBytes()), "receiptLifetime", "This server process"));
        }
        return description;
    }

    @Override
    public synchronized Map<String, Object> describe(String operation) {
        Operation found = operations.get(operation);
        return found == null ? error("QA_OPERATION_UNKNOWN", "Unknown QA operation: " + operation) : found.description();
    }

    @Override
    public Map<String, Object> submit(CommandSender actor, String operation, Map<String, Object> input) {
        String actorId = ProtocolRequestAuthority.trustedOperatorId(actor);
        if (actorId == null) return error("QA_PERMISSION_DENIED", "QA requires permission and admission on the server thread");
        Operation handler;
        Run run;
        Map<String, Object> admitted;
        String fingerprint;
        UUID id;
        try {
            if (input == null) throw new IllegalArgumentException("QA input is required");
            Object requestedId = input.get("requestId");
            id = requestedId == null ? UUID.randomUUID() : canonicalUuid(requestedId);
            Map<String, Object> request = new LinkedHashMap<>(input);
            request.put("requestId", id.toString());
            String canonical = stringify(request);
            admitted = parse(canonical);
            fingerprint = CanonicalJson.sha256("resync.qa.request.v1", Map.of("actor", actorId, "operation", operation, "input", admitted));
        } catch (RuntimeException failure) {
            return error("QA_INVALID_ARGUMENT", message(failure));
        }
        synchronized (this) {
            Run previous = runs.get(id);
            if (previous != null) {
                if (!previous.actorId.equals(actorId)) return error("QA_RECEIPT_NOT_FOUND", "QA receipt is unavailable");
                if (!previous.fingerprint.equals(fingerprint)) return error("QA_REQUEST_CONFLICT", "Request ID already belongs to different input");
                return previous.snapshot;
            }
            if (closed) return error("QA_UNAVAILABLE", "QA admission is closed");
            handler = operations.get(operation);
            if (handler == null) return error("QA_OPERATION_UNKNOWN", "Unknown QA operation: " + operation);
            trimReceipts();
            if (active >= MAX_ACTIVE || runs.size() >= MAX_RECEIPTS || residentBytes >= MAX_RECEIPT_BYTES) {
                return error("QA_BUSY", "QA capacity is full. Inspect pending receipts and retry");
            }
            run = new Run(id, actorId, operation, fingerprint, epoch.getAsLong());
            runs.put(id, run);
            active++;
            residentBytes += run.bytes;
        }
        try {
            CompletionStage<Map<String, Object>> pending = Objects.requireNonNull(handler.handler().invoke(actor, operation, admitted), "QA operation completion is required");
            pending.whenComplete((result, failure) -> finish(run, result, failure));
        } catch (Throwable failure) {
            finish(run, null, failure);
        }
        synchronized (this) {
            return run.snapshot;
        }
    }

    @Override
    public synchronized Map<String, Object> poll(CommandSender actor, UUID runId) {
        String actorId = ProtocolRequestAuthority.trustedOperatorId(actor);
        if (actorId == null) return error("QA_PERMISSION_DENIED", "QA requires permission and admission on the server thread");
        Run run = runs.get(runId);
        return run == null || !run.actorId.equals(actorId) ? error("QA_RECEIPT_NOT_FOUND", "QA receipt is unavailable") : run.snapshot;
    }

    public synchronized CompletionStage<Void> whenIdle() {
        return CompletableFuture.allOf(runs.values().stream().filter(run -> !run.terminal)
            .map(run -> run.completion).toArray(CompletableFuture[]::new));
    }

    @Override
    public synchronized void close() {
        closed = true;
    }

    private void finish(Run run, Map<String, Object> result, Throwable failure) {
        Map<String, Object> outcome = new LinkedHashMap<>(run.base);
        outcome.put("finishedAt", Instant.now().toString());
        try {
            if (failure != null) {
                outcome.put("status", "failed");
                outcome.put("code", "QA_FAILED");
                outcome.put("message", message(failure));
            } else {
                outcome.put("status", "completed");
                outcome.put("result", Objects.requireNonNull(result, "QA result is required"));
            }
            String canonical = stringify(outcome);
            long bytes = 4L * canonical.getBytes(StandardCharsets.UTF_8).length;
            if (bytes > MAX_RECEIPT_BYTES) throw new ReceiptTooLarge();
            Map<String, Object> snapshot = parse(canonical);
            synchronized (this) {
                if (run.terminal) return;
                residentBytes -= run.bytes;
                run.snapshot = snapshot;
                run.bytes = bytes;
                run.terminal = true;
                active--;
                residentBytes += bytes;
                trimReceipts();
            }
        } catch (Throwable invalid) {
            synchronized (this) {
                if (run.terminal) return;
                residentBytes -= run.bytes;
                outcome.remove("result");
                outcome.put("status", "failed");
                outcome.put("code", invalid instanceof ReceiptTooLarge ? "QA_RESULT_TOO_LARGE" : "QA_RESULT_INVALID");
                outcome.put("message", message(invalid));
                run.snapshot = immutable(outcome);
                run.bytes = 4L * stringify(run.snapshot).length();
                run.terminal = true;
                active--;
                residentBytes += run.bytes;
                trimReceipts();
            }
        } finally {
            run.completion.complete(null);
        }
    }

    private void trimReceipts() {
        var entries = runs.entrySet().iterator();
        while ((runs.size() >= MAX_RECEIPTS || residentBytes > MAX_RECEIPT_BYTES) && entries.hasNext()) {
            Run run = entries.next().getValue();
            if (run.terminal) {
                residentBytes -= run.bytes;
                entries.remove();
            }
        }
    }

    private Map<String, Object> immutable(Map<String, Object> value) {
        return parse(stringify(value));
    }

    private static UUID canonicalUuid(Object value) {
        if (!(value instanceof String text)) throw new IllegalArgumentException("Request ID must be a UUID string");
        UUID id = UUID.fromString(text);
        if (!id.toString().equalsIgnoreCase(text)) throw new IllegalArgumentException("Request ID must be a complete UUID");
        return id;
    }

    private static Map<String, Object> error(String code, String message) {
        return Map.of("status", "error", "code", code, "message", message);
    }

    private static String message(Throwable failure) {
        Throwable cause = failure;
        while (cause instanceof CompletionException && cause.getCause() != null) cause = cause.getCause();
        String text = cause.getMessage();
        if (text == null || text.isBlank()) return cause.getClass().getSimpleName();
        StringBuilder safe = new StringBuilder();
        for (int index = 0; index < text.length() && safe.length() < 4096; index++) {
            char value = text.charAt(index);
            if (Character.isHighSurrogate(value)) {
                if (index + 1 < text.length() && Character.isLowSurrogate(text.charAt(index + 1))) {
                    if (safe.length() > 4094) break;
                    safe.append(value).append(text.charAt(++index));
                } else safe.append('\uFFFD');
            } else safe.append(Character.isLowSurrogate(value) ? '\uFFFD' : value);
        }
        return safe.toString();
    }

    private static final class ReceiptTooLarge extends IllegalArgumentException {
        private ReceiptTooLarge() {
            super("QA result exceeds the retained receipt budget");
        }
    }

    private record Operation(Map<String, Object> description, Handler handler) {
    }

    private final class Run {
        private final String actorId;
        private final String fingerprint;
        private final Map<String, Object> base;
        private final CompletableFuture<Void> completion = new CompletableFuture<>();
        private Map<String, Object> snapshot;
        private long bytes;
        private boolean terminal;

        private Run(UUID id, String actorId, String operation, String fingerprint, long epoch) {
            this.actorId = actorId;
            this.fingerprint = fingerprint;
            this.base = immutable(Map.of("requestId", id.toString(), "runId", id.toString(), "operation", operation, "processId", processId.toString(),
                "actor", actorId, "authorityEpoch", epoch, "acceptedAt", Instant.now().toString(), "requestFingerprint", fingerprint));
            Map<String, Object> accepted = new LinkedHashMap<>(base);
            accepted.put("status", "accepted");
            snapshot = immutable(accepted);
            bytes = 4L * stringify(snapshot).length();
        }
    }
}
