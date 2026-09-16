package restudio.resync.flow.workspace;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongFunction;

public final class WorkspaceRevision<E> {
    public static final int DEFAULT_OPERATION_LIMIT = Integer.MAX_VALUE;
    private final LinkedHashMap<String, Accepted<E>> operations = new LinkedHashMap<>();
    private long sequence;

    public WorkspaceRevision() {
        this(0L, DEFAULT_OPERATION_LIMIT);
    }

    public WorkspaceRevision(long sequence, int operationLimit) {
        if (operationLimit < 1) {
            throw new IllegalArgumentException("Operation limit must be positive");
        }
        this.sequence = Math.max(0L, sequence);
    }

    public synchronized long sequence() {
        return sequence;
    }

    public synchronized Assessment<E> assess(long baseSequence, String operationId) {
        return assess(baseSequence, operationId, "");
    }

    public synchronized Assessment<E> assess(long baseSequence, String operationId, String payloadHash) {
        String id = safeOperationId(operationId);
        Accepted<E> existing = operations.get(id);
        if (existing != null) {
            return new Assessment<>(existing.payloadHash().equals(payloadHash != null ? payloadHash : "") ? Status.DUPLICATE : Status.MISMATCH,
                sequence, existing.event());
        }
        return new Assessment<>(baseSequence == sequence ? Status.ACCEPT : Status.CONFLICT, sequence, null);
    }

    public synchronized E advance(String operationId, LongFunction<E> eventFactory) {
        return advance(operationId, "", eventFactory);
    }

    public synchronized E advance(String operationId, String payloadHash, LongFunction<E> eventFactory) {
        String id = safeOperationId(operationId);
        Accepted<E> existing = operations.get(id);
        if (existing != null) {
            if (!existing.payloadHash().equals(payloadHash != null ? payloadHash : "")) {
                throw new IllegalStateException("Operation ID was already accepted with a different payload");
            }
            return existing.event();
        }
        sequence++;
        E event = eventFactory.apply(sequence);
        operations.put(id, new Accepted<>(payloadHash != null ? payloadHash : "", event));
        return event;
    }

    public synchronized long advance() {
        return ++sequence;
    }

    public synchronized void reset(long sequence) {
        this.sequence = Math.max(0L, sequence);
        operations.clear();
    }

    public synchronized Map<String, E> operations() {
        LinkedHashMap<String, E> result = new LinkedHashMap<>();
        operations.forEach((id, accepted) -> result.put(id, accepted.event()));
        return Map.copyOf(result);
    }

    private String safeOperationId(String operationId) {
        String id = operationId != null ? operationId.trim() : "";
        if (id.isEmpty()) {
            throw new IllegalArgumentException("Operation ID is required");
        }
        return id;
    }

    public enum Status {
        ACCEPT,
        DUPLICATE,
        MISMATCH,
        CONFLICT
    }

    public record Assessment<E>(Status status, long sequence, E existing) {
    }

    private record Accepted<E>(String payloadHash, E event) {
    }
}
