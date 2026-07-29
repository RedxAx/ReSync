package restudio.resync.flow.workspace;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class LiveDocumentChannel<D, O, A, I> {
    private final Transport<O, A> disconnectedTransport = new Transport<>() {
        @Override
        public void join(WorkspaceTarget target) {
        }

        @Override
        public void leave(WorkspaceTarget target) {
        }

        @Override
        public boolean publishOperation(WorkspaceTarget target, long baseSequence, String operationId, O operation) {
            return false;
        }

        @Override
        public boolean publishAwareness(WorkspaceTarget target, A awareness) {
            return false;
        }
    };
    private final Map<WorkspaceTarget, List<Listener<D, O, A, I>>> listeners = new HashMap<>();
    private final Map<WorkspaceTarget, Long> sequences = new HashMap<>();
    private final Map<String, WorkspaceTarget> pendingOperations = new HashMap<>();
    private final Set<WorkspaceTarget> resyncing = new HashSet<>();
    private volatile Transport<O, A> transport = disconnectedTransport;
    private volatile ConnectionState connectionState = ConnectionState.DISCONNECTED;

    public synchronized void bind(Transport<O, A> transport) {
        this.transport = transport != null ? transport : disconnectedTransport;
    }

    public boolean connect() {
        List<WorkspaceTarget> targets;
        Transport<O, A> activeTransport;
        synchronized (this) {
            if (connectionState == ConnectionState.CONNECTED) {
                return false;
            }
            connectionState = ConnectionState.CONNECTED;
            sequences.clear();
            pendingOperations.clear();
            resyncing.clear();
            targets = List.copyOf(listeners.keySet());
            activeTransport = transport;
        }
        targets.forEach(activeTransport::join);
        return true;
    }

    public boolean disconnect(String reason) {
        List<Listener<D, O, A, I>> currentListeners;
        synchronized (this) {
            if (connectionState == ConnectionState.DISCONNECTED) {
                return false;
            }
            connectionState = ConnectionState.DISCONNECTED;
            sequences.clear();
            pendingOperations.clear();
            resyncing.clear();
            currentListeners = listeners.values().stream().flatMap(List::stream).distinct().toList();
        }
        String message = reason != null && !reason.isBlank() ? reason : "Disconnected";
        currentListeners.forEach(listener -> listener.onResync(message));
        return true;
    }

    public synchronized boolean connected() {
        return connectionState == ConnectionState.CONNECTED;
    }

    public boolean join(WorkspaceTarget target, Listener<D, O, A, I> listener) {
        if (target == null || listener == null) {
            return false;
        }
        boolean first;
        boolean active;
        Transport<O, A> activeTransport;
        synchronized (this) {
            List<Listener<D, O, A, I>> documentListeners = listeners.computeIfAbsent(target, ignored -> new ArrayList<>());
            first = documentListeners.isEmpty();
            if (!documentListeners.contains(listener)) {
                documentListeners.add(listener);
            }
            active = connectionState == ConnectionState.CONNECTED;
            activeTransport = transport;
        }
        if (first && active) {
            activeTransport.join(target);
        }
        return first;
    }

    public boolean leave(WorkspaceTarget target, Listener<D, O, A, I> listener) {
        if (target == null || listener == null) {
            return false;
        }
        boolean last;
        boolean active;
        Transport<O, A> activeTransport;
        synchronized (this) {
            List<Listener<D, O, A, I>> documentListeners = listeners.get(target);
            if (documentListeners == null || !documentListeners.remove(listener)) {
                return false;
            }
            last = documentListeners.isEmpty();
            if (!last) {
                return false;
            }
            listeners.remove(target);
            sequences.remove(target);
            pendingOperations.values().removeIf(target::equals);
            resyncing.remove(target);
            active = connectionState == ConnectionState.CONNECTED;
            activeTransport = transport;
        }
        if (active) {
            activeTransport.leave(target);
        }
        return true;
    }

    public String publishOperation(WorkspaceTarget target, O operation) {
        if (target == null || operation == null) {
            return "";
        }
        String operationId = UUID.randomUUID().toString();
        long baseSequence;
        Transport<O, A> activeTransport;
        synchronized (this) {
            if (connectionState != ConnectionState.CONNECTED || !listeners.containsKey(target)) {
                return "";
            }
            baseSequence = sequences.getOrDefault(target, 0L);
            pendingOperations.put(operationId, target);
            activeTransport = transport;
        }
        if (activeTransport.publishOperation(target, baseSequence, operationId, operation)) {
            return operationId;
        }
        discard(operationId);
        return "";
    }

    public boolean publishAwareness(WorkspaceTarget target, A awareness) {
        Transport<O, A> activeTransport;
        synchronized (this) {
            if (target == null || connectionState != ConnectionState.CONNECTED || !listeners.containsKey(target)) {
                return false;
            }
            activeTransport = transport;
        }
        return activeTransport.publishAwareness(target, awareness);
    }

    public synchronized void sent(WorkspaceTarget target, String operationId) {
        if (target != null && operationId != null && !operationId.isBlank()) {
            pendingOperations.put(operationId, target);
        }
    }

    public synchronized void discard(String operationId) {
        pendingOperations.remove(operationId);
    }

    public synchronized long sequence(WorkspaceTarget target) {
        return target != null ? sequences.getOrDefault(target, 0L) : 0L;
    }

    public void acceptSnapshot(Snapshot<D, A, I> snapshot) {
        if (snapshot == null || snapshot.target() == null || snapshot.document() == null) {
            return;
        }
        List<Listener<D, O, A, I>> documentListeners;
        synchronized (this) {
            if (!listeners.containsKey(snapshot.target())) {
                return;
            }
            long current = sequences.getOrDefault(snapshot.target(), -1L);
            if (snapshot.sequence() < current) {
                return;
            }
            sequences.put(snapshot.target(), snapshot.sequence());
            pendingOperations.values().removeIf(snapshot.target()::equals);
            resyncing.remove(snapshot.target());
            documentListeners = List.copyOf(listeners.get(snapshot.target()));
        }
        documentListeners.forEach(listener -> {
            listener.onSnapshot(snapshot);
            if (snapshot.awareness() != null) {
                snapshot.awareness().forEach(listener::onAwareness);
            }
        });
    }

    public void acceptOperation(Operation<O, I> operation) {
        if (operation == null || operation.target() == null || operation.operation() == null) {
            return;
        }
        List<Listener<D, O, A, I>> documentListeners = List.of();
        boolean own = false;
        String resyncReason = "";
        synchronized (this) {
            if (!listeners.containsKey(operation.target())) {
                return;
            }
            if (!sequences.containsKey(operation.target())) {
                resyncReason = "Operation Before Snapshot";
            } else {
                long current = sequences.getOrDefault(operation.target(), 0L);
                if (operation.sequence() <= current) {
                    pendingOperations.remove(operation.operationId());
                    return;
                }
                if (operation.sequence() != current + 1L) {
                    resyncReason = "Operation Gap";
                } else {
                    sequences.put(operation.target(), operation.sequence());
                    resyncing.remove(operation.target());
                    own = operation.target().equals(pendingOperations.remove(operation.operationId()));
                    documentListeners = List.copyOf(listeners.get(operation.target()));
                }
            }
        }
        if (!resyncReason.isEmpty()) {
            requestResync(operation.target(), resyncReason);
            return;
        }
        boolean ownOperation = own;
        documentListeners.forEach(listener -> listener.onOperation(operation, ownOperation));
    }

    public void acceptAwareness(Awareness<A, I> awareness) {
        if (awareness == null || awareness.target() == null) {
            return;
        }
        List<Listener<D, O, A, I>> documentListeners;
        synchronized (this) {
            documentListeners = List.copyOf(listeners.getOrDefault(awareness.target(), List.of()));
        }
        documentListeners.forEach(listener -> listener.onAwareness(awareness));
    }

    public void acceptResync(WorkspaceTarget target, String reason) {
        requestResync(target, reason);
    }

    private void requestResync(WorkspaceTarget target, String reason) {
        if (target == null) {
            return;
        }
        List<Listener<D, O, A, I>> documentListeners;
        synchronized (this) {
            if (!resyncing.add(target)) {
                return;
            }
            documentListeners = List.copyOf(listeners.getOrDefault(target, List.of()));
        }
        documentListeners.forEach(listener -> listener.onResync(reason));
    }

    public interface Listener<D, O, A, I> {
        void onSnapshot(Snapshot<D, A, I> snapshot);

        void onOperation(Operation<O, I> operation, boolean own);

        void onAwareness(Awareness<A, I> awareness);

        void onResync(String reason);
    }

    public interface Transport<O, A> {
        void join(WorkspaceTarget target);

        void leave(WorkspaceTarget target);

        boolean publishOperation(WorkspaceTarget target, long baseSequence, String operationId, O operation);

        boolean publishAwareness(WorkspaceTarget target, A awareness);
    }

    public record Snapshot<D, A, I>(WorkspaceTarget target, long sequence, D document,
                                    List<Awareness<A, I>> awareness) {
    }

    public record Operation<O, I>(WorkspaceTarget target, long sequence, String operationId,
                                  String authorSessionId, I author, O operation) {
    }

    public record Awareness<A, I>(WorkspaceTarget target, String authorSessionId, I author, A state,
                                  long updatedAt) {
    }

    public enum ConnectionState {
        DISCONNECTED,
        CONNECTED
    }
}
