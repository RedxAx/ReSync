package restudio.resync.flow.workspace;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

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
    private final Map<WorkspaceTarget, Map<Listener<D, O, A, I>, Long>> listenerEpochs = new HashMap<>();
    private final Map<Listener<D, O, A, I>, Long> listenerRegistrationEpochs = new HashMap<>();
    private final Map<WorkspaceTarget, Long> membershipEpochs = new HashMap<>();
    private final Map<WorkspaceTarget, JoinAuthority> joinAuthorities = new HashMap<>();
    private final Map<WorkspaceTarget, Long> sequences = new HashMap<>();
    private final Map<String, WorkspaceTarget> pendingOperations = new HashMap<>();
    private final Map<String, PublicationClaim> publishingOperations = new HashMap<>();
    private final Set<WorkspaceTarget> resyncing = new HashSet<>();
    private final ArrayDeque<Runnable> acceptQueue = new ArrayDeque<>();
    private Transport<O, A> transport = disconnectedTransport;
    private Consumer<Runnable> callbackDispatcher = Runnable::run;
    private ConnectionState connectionState = ConnectionState.DISCONNECTED;
    private long connectionEpoch;
    private long connectionGeneration = Long.MIN_VALUE;
    private long transportEpoch;
    private long membershipEpoch;
    private boolean accepting;

    public synchronized long bind(Transport<O, A> transport) {
        return bind(transport, Runnable::run);
    }

    public synchronized long bind(Transport<O, A> transport, Consumer<Runnable> callbackDispatcher) {
        Transport<O, A> nextTransport = transport != null ? transport : disconnectedTransport;
        Consumer<Runnable> nextDispatcher = callbackDispatcher != null ? callbackDispatcher : Runnable::run;
        if (this.transport == nextTransport && this.callbackDispatcher == nextDispatcher) {
            return transportEpoch;
        }
        this.transport = nextTransport;
        this.callbackDispatcher = nextDispatcher;
        long activeTransportEpoch = ++transportEpoch;
        if (connectionState != ConnectionState.CONNECTED) {
            return activeTransportEpoch;
        }
        long activeConnectionEpoch = ++connectionEpoch;
        sequences.clear();
        resyncing.clear();
        joinAuthorities.clear();
        for (WorkspaceTarget target : List.copyOf(listeners.keySet())) {
            if (connectionState != ConnectionState.CONNECTED || connectionEpoch != activeConnectionEpoch) {
                break;
            }
            join(target, activeConnectionEpoch);
        }
        return activeTransportEpoch;
    }

    public synchronized boolean connect(long generation) {
        if (generation <= connectionGeneration) {
            return false;
        }
        connectionGeneration = generation;
        connectionState = ConnectionState.CONNECTED;
        long activeConnectionEpoch = ++connectionEpoch;
        sequences.clear();
        resyncing.clear();
        joinAuthorities.clear();
        for (WorkspaceTarget target : List.copyOf(listeners.keySet())) {
            if (connectionState != ConnectionState.CONNECTED || connectionEpoch != activeConnectionEpoch) {
                break;
            }
            join(target, activeConnectionEpoch);
        }
        return true;
    }

    public synchronized boolean disconnect(String reason) {
        if (connectionState == ConnectionState.DISCONNECTED) {
            return false;
        }
        connectionState = ConnectionState.DISCONNECTED;
        long disconnectedEpoch = ++connectionEpoch;
        long disconnectedSourceEpoch = transportEpoch;
        sequences.clear();
        resyncing.clear();
        joinAuthorities.clear();
        String message = reason != null && !reason.isBlank() ? reason : "Disconnected";
        Map<Listener<D, O, A, I>, Long> currentListeners = disconnectedListeners();
        for (Map.Entry<Listener<D, O, A, I>, Long> delivery : currentListeners.entrySet()) {
            dispatchDisconnected(disconnectedEpoch, disconnectedSourceEpoch, delivery.getKey(), delivery.getValue(),
                () -> delivery.getKey().onResync(message));
        }
        return true;
    }

    public synchronized boolean connected() {
        return connectionState == ConnectionState.CONNECTED;
    }

    public synchronized boolean join(WorkspaceTarget target, Listener<D, O, A, I> listener) {
        if (target == null || listener == null) {
            return false;
        }
        List<Listener<D, O, A, I>> documentListeners = listeners.computeIfAbsent(target, ignored -> new ArrayList<>());
        boolean first = documentListeners.isEmpty();
        if (documentListeners.contains(listener)) {
            return false;
        }
        listenerRegistrationEpochs.computeIfAbsent(listener, ignored -> ++membershipEpoch);
        documentListeners.add(listener);
        listenerEpochs.computeIfAbsent(target, ignored -> new HashMap<>()).put(listener, ++membershipEpoch);
        if (first) {
            membershipEpochs.put(target, membershipEpoch);
            if (connectionState == ConnectionState.CONNECTED) {
                join(target, connectionEpoch);
            }
        }
        return first;
    }

    public synchronized boolean leave(WorkspaceTarget target, Listener<D, O, A, I> listener) {
        if (target == null || listener == null) {
            return false;
        }
        List<Listener<D, O, A, I>> documentListeners = listeners.get(target);
        if (documentListeners == null || !documentListeners.remove(listener)) {
            return false;
        }
        Map<Listener<D, O, A, I>, Long> documentListenerEpochs = listenerEpochs.get(target);
        if (documentListenerEpochs != null) {
            documentListenerEpochs.remove(listener);
        }
        if (listeners.values().stream().noneMatch(currentListeners -> currentListeners.contains(listener))) {
            listenerRegistrationEpochs.remove(listener);
        }
        if (!documentListeners.isEmpty()) {
            return false;
        }
        JoinAuthority authority = joinAuthorities.get(target);
        boolean joined = active(authority, target);
        joinAuthorities.remove(target);
        listeners.remove(target);
        listenerEpochs.remove(target);
        membershipEpochs.remove(target);
        sequences.remove(target);
        resyncing.remove(target);
        if (joined) {
            transport.leave(target);
        }
        return true;
    }

    public synchronized String publishOperation(WorkspaceTarget target, O operation) {
        if (target == null || operation == null || !active(joinAuthorities.get(target), target)) {
            return "";
        }
        String operationId = UUID.randomUUID().toString();
        long baseSequence = sequences.getOrDefault(target, 0L);
        PublicationClaim claim = new PublicationClaim(target);
        publishingOperations.put(operationId, claim);
        boolean accepted;
        try {
            accepted = transport.publishOperation(target, baseSequence, operationId, operation);
        } catch (RuntimeException | Error exception) {
            publishingOperations.remove(operationId, claim);
            pendingOperations.remove(operationId, target);
            throw exception;
        }
        boolean claimed = publishingOperations.remove(operationId, claim);
        if (!accepted) {
            pendingOperations.remove(operationId, target);
            return claim.echoed ? operationId : "";
        }
        if (claim.echoed) {
            pendingOperations.remove(operationId, target);
        } else if (claimed) {
            pendingOperations.put(operationId, target);
        }
        return operationId;
    }

    public synchronized boolean publishAwareness(WorkspaceTarget target, A awareness) {
        return target != null && active(joinAuthorities.get(target), target) && transport.publishAwareness(target, awareness);
    }

    public synchronized void sent(WorkspaceTarget target, String operationId) {
        if (target != null && operationId != null && !operationId.isBlank()) {
            pendingOperations.put(operationId, target);
        }
    }

    public synchronized void discard(String operationId) {
        pendingOperations.remove(operationId);
        publishingOperations.remove(operationId);
    }

    public synchronized long sequence(WorkspaceTarget target) {
        return target != null ? sequences.getOrDefault(target, 0L) : 0L;
    }

    public synchronized void acceptSnapshot(Snapshot<D, A, I> snapshot, long generation, long sourceEpoch) {
        if (generation != connectionGeneration || sourceEpoch != transportEpoch) {
            return;
        }
        JoinAuthority authority = snapshot != null ? joinAuthorities.get(snapshot.target()) : null;
        Map<Listener<D, O, A, I>, Long> deliveryListeners = snapshot != null ? deliveryListeners(snapshot.target()) : Map.of();
        accept(() -> applySnapshot(snapshot, authority, deliveryListeners));
    }

    private void applySnapshot(Snapshot<D, A, I> snapshot, JoinAuthority authority,
                               Map<Listener<D, O, A, I>, Long> deliveryListeners) {
        if (snapshot == null || snapshot.target() == null || snapshot.document() == null) {
            return;
        }
        WorkspaceTarget target = snapshot.target();
        if (!active(authority, target)) {
            return;
        }
        long current = sequences.getOrDefault(target, -1L);
        if (snapshot.sequence() < current || snapshot.sequence() == current && !resyncing.contains(target)) {
            return;
        }
        sequences.put(target, snapshot.sequence());
        resyncing.remove(target);
        for (Map.Entry<Listener<D, O, A, I>, Long> delivery : deliveryListeners.entrySet()) {
            Listener<D, O, A, I> listener = delivery.getKey();
            if (!active(authority, target, listener, delivery.getValue())) {
                continue;
            }
            dispatch(authority, target, listener, delivery.getValue(), () -> listener.onSnapshot(snapshot));
            if (snapshot.awareness() == null) {
                continue;
            }
            for (Awareness<A, I> awareness : snapshot.awareness()) {
                if (!active(authority, target, listener, delivery.getValue())) {
                    break;
                }
                dispatch(authority, target, listener, delivery.getValue(), () -> listener.onAwareness(awareness));
            }
        }
    }

    public synchronized void acceptOperation(Operation<O, I> operation, long generation, long sourceEpoch) {
        if (generation != connectionGeneration || sourceEpoch != transportEpoch) {
            return;
        }
        JoinAuthority authority = operation != null ? joinAuthorities.get(operation.target()) : null;
        Map<Listener<D, O, A, I>, Long> deliveryListeners = operation != null ? deliveryListeners(operation.target()) : Map.of();
        accept(() -> applyOperation(operation, authority, deliveryListeners));
    }

    private void applyOperation(Operation<O, I> operation, JoinAuthority authority,
                                Map<Listener<D, O, A, I>, Long> deliveryListeners) {
        if (operation == null || operation.target() == null || operation.operation() == null) {
            return;
        }
        WorkspaceTarget target = operation.target();
        if (!active(authority, target)) {
            return;
        }
        boolean publishing = markEchoed(operation.operationId(), target);
        if (!sequences.containsKey(target)) {
            requestResync(target, authority, deliveryListeners, "Operation Before Snapshot");
            return;
        }
        long current = sequences.get(target);
        if (operation.sequence() <= current) {
            pendingOperations.remove(operation.operationId(), target);
            return;
        }
        if (operation.sequence() != current + 1L) {
            requestResync(target, authority, deliveryListeners, "Operation Gap");
            return;
        }
        sequences.put(target, operation.sequence());
        boolean pending = pendingOperations.remove(operation.operationId(), target);
        boolean own = pending || publishing;
        for (Map.Entry<Listener<D, O, A, I>, Long> delivery : deliveryListeners.entrySet()) {
            if (active(authority, target, delivery.getKey(), delivery.getValue())) {
                dispatch(authority, target, delivery.getKey(), delivery.getValue(),
                    () -> delivery.getKey().onOperation(operation, own));
            }
        }
    }

    public synchronized void acceptAwareness(Awareness<A, I> awareness, long generation, long sourceEpoch) {
        if (generation != connectionGeneration || sourceEpoch != transportEpoch) {
            return;
        }
        JoinAuthority authority = awareness != null ? joinAuthorities.get(awareness.target()) : null;
        Map<Listener<D, O, A, I>, Long> deliveryListeners = awareness != null ? deliveryListeners(awareness.target()) : Map.of();
        accept(() -> applyAwareness(awareness, authority, deliveryListeners));
    }

    private void applyAwareness(Awareness<A, I> awareness, JoinAuthority authority,
                                Map<Listener<D, O, A, I>, Long> deliveryListeners) {
        if (awareness == null || awareness.target() == null) {
            return;
        }
        WorkspaceTarget target = awareness.target();
        if (!active(authority, target)) {
            return;
        }
        for (Map.Entry<Listener<D, O, A, I>, Long> delivery : deliveryListeners.entrySet()) {
            if (active(authority, target, delivery.getKey(), delivery.getValue())) {
                dispatch(authority, target, delivery.getKey(), delivery.getValue(),
                    () -> delivery.getKey().onAwareness(awareness));
            }
        }
    }

    public synchronized void acceptResync(WorkspaceTarget target, String reason, long generation, long sourceEpoch) {
        if (generation != connectionGeneration || sourceEpoch != transportEpoch) {
            return;
        }
        JoinAuthority authority = joinAuthorities.get(target);
        Map<Listener<D, O, A, I>, Long> deliveryListeners = deliveryListeners(target);
        accept(() -> requestResync(target, authority, deliveryListeners, reason));
    }

    private void accept(Runnable delivery) {
        acceptQueue.addLast(delivery);
        if (accepting) {
            return;
        }
        accepting = true;
        try {
            Runnable next;
            while ((next = acceptQueue.pollFirst()) != null) {
                next.run();
            }
        } finally {
            accepting = false;
            acceptQueue.clear();
        }
    }

    private void join(WorkspaceTarget target, long activeConnectionEpoch) {
        Long activeMembershipEpoch = membershipEpochs.get(target);
        if (activeMembershipEpoch == null || connectionState != ConnectionState.CONNECTED || connectionEpoch != activeConnectionEpoch) {
            return;
        }
        JoinAuthority authority = new JoinAuthority(activeConnectionEpoch, activeMembershipEpoch, transportEpoch);
        if (authority.equals(joinAuthorities.get(target))) {
            return;
        }
        joinAuthorities.put(target, authority);
        transport.join(target);
    }

    private void requestResync(WorkspaceTarget target, JoinAuthority authority,
                               Map<Listener<D, O, A, I>, Long> deliveryListeners, String reason) {
        if (target == null || !active(authority, target) || !resyncing.add(target)) {
            return;
        }
        for (Map.Entry<Listener<D, O, A, I>, Long> delivery : deliveryListeners.entrySet()) {
            if (active(authority, target, delivery.getKey(), delivery.getValue())) {
                dispatch(authority, target, delivery.getKey(), delivery.getValue(),
                    () -> delivery.getKey().onResync(reason));
            }
        }
    }

    private boolean active(JoinAuthority authority, WorkspaceTarget target) {
        return authority != null && connectionState == ConnectionState.CONNECTED && authority.equals(joinAuthorities.get(target))
            && authority.connectionEpoch() == connectionEpoch && authority.transportEpoch() == transportEpoch
            && authority.membershipEpoch() == membershipEpochs.getOrDefault(target, -1L);
    }

    private boolean active(JoinAuthority authority, WorkspaceTarget target, Listener<D, O, A, I> listener, long listenerEpoch) {
        return active(authority, target) && listenerEpochs.getOrDefault(target, Map.of()).getOrDefault(listener, -1L) == listenerEpoch;
    }

    private Map<Listener<D, O, A, I>, Long> deliveryListeners(WorkspaceTarget target) {
        Map<Listener<D, O, A, I>, Long> epochs = listenerEpochs.getOrDefault(target, Map.of());
        Map<Listener<D, O, A, I>, Long> deliveries = new LinkedHashMap<>();
        for (Listener<D, O, A, I> listener : listeners.getOrDefault(target, List.of())) {
            Long epoch = epochs.get(listener);
            if (epoch != null) {
                deliveries.put(listener, epoch);
            }
        }
        return deliveries;
    }

    private Map<Listener<D, O, A, I>, Long> disconnectedListeners() {
        Map<Listener<D, O, A, I>, Long> deliveries = new LinkedHashMap<>();
        for (List<Listener<D, O, A, I>> documentListeners : listeners.values()) {
            for (Listener<D, O, A, I> listener : documentListeners) {
                deliveries.put(listener, listenerEpoch(listener));
            }
        }
        return deliveries;
    }

    private long listenerEpoch(Listener<D, O, A, I> listener) {
        return listenerRegistrationEpochs.getOrDefault(listener, -1L);
    }

    private void dispatch(JoinAuthority authority, WorkspaceTarget target, Listener<D, O, A, I> listener,
                          long listenerEpoch, Runnable callback) {
        dispatch(() -> active(authority, target, listener, listenerEpoch), callback);
    }

    private void dispatchDisconnected(long disconnectedEpoch, long sourceEpoch, Listener<D, O, A, I> listener,
                                      long listenerEpoch, Runnable callback) {
        dispatch(() -> connectionState == ConnectionState.DISCONNECTED && connectionEpoch == disconnectedEpoch
            && transportEpoch == sourceEpoch && listenerEpoch(listener) == listenerEpoch, callback);
    }

    private void dispatch(Guard guard, Runnable callback) {
        Consumer<Runnable> dispatcher = callbackDispatcher;
        try {
            dispatcher.accept(() -> {
                boolean deliver;
                synchronized (this) {
                    deliver = guard.active();
                }
                if (deliver) {
                    notifyListener(callback);
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private void notifyListener(Runnable callback) {
        try {
            callback.run();
        } catch (Throwable ignored) {
        }
    }

    private boolean markEchoed(String operationId, WorkspaceTarget target) {
        PublicationClaim claim = publishingOperations.get(operationId);
        if (claim == null || !claim.target.equals(target)) {
            return false;
        }
        claim.echoed = true;
        return true;
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

    private interface Guard {
        boolean active();
    }

    private record JoinAuthority(long connectionEpoch, long membershipEpoch, long transportEpoch) {
    }

    private static final class PublicationClaim {
        private final WorkspaceTarget target;
        private boolean echoed;

        private PublicationClaim(WorkspaceTarget target) {
            this.target = target;
        }
    }
}
