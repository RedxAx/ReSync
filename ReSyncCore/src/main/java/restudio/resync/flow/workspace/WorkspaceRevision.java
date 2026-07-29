package restudio.resync.flow.workspace;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongFunction;

public final class WorkspaceRevision<E> {
    public static final int DEFAULT_OPERATION_LIMIT = 2_048;
    private final int operationLimit;
    private final LinkedHashMap<String, E> operations = new LinkedHashMap<>();
    private long sequence;

    public WorkspaceRevision() {
        this(0L, DEFAULT_OPERATION_LIMIT);
    }

    public WorkspaceRevision(long sequence, int operationLimit) {
        this.sequence = Math.max(0L, sequence);
        this.operationLimit = Math.max(1, operationLimit);
    }

    public synchronized long sequence() {
        return sequence;
    }

    public synchronized Assessment<E> assess(long baseSequence, String operationId) {
        String id = safeOperationId(operationId);
        E existing = operations.get(id);
        if (existing != null) {
            return new Assessment<>(Status.DUPLICATE, sequence, existing);
        }
        return new Assessment<>(baseSequence == sequence ? Status.ACCEPT : Status.CONFLICT, sequence, null);
    }

    public synchronized E advance(String operationId, LongFunction<E> eventFactory) {
        String id = safeOperationId(operationId);
        E existing = operations.get(id);
        if (existing != null) {
            return existing;
        }
        sequence++;
        E event = eventFactory.apply(sequence);
        operations.put(id, event);
        trimOperations();
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
        return Map.copyOf(operations);
    }

    private void trimOperations() {
        while (operations.size() > operationLimit) {
            operations.remove(operations.keySet().iterator().next());
        }
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
        CONFLICT
    }

    public record Assessment<E>(Status status, long sequence, E existing) {
    }
}
