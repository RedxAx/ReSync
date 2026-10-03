package restudio.resync.flow;

import restudio.flow.data.FlowNode;
import restudio.resync.flow.registry.NodeDefinition;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public class CustomEventManager {
    private static final int MAX_PENDING_EVENTS = 256;
    private static CustomEventManager instance;

    private final Map<String, List<Listener>> listeners = new HashMap<>();
    private final Map<String, Map<String, Object>> lastEventData = new HashMap<>();
    private final Map<Listener, Long> listenerTimeouts = new HashMap<>();
    private long currentTick;

    public static synchronized CustomEventManager getInstance() {
        if (instance == null) {
            instance = new CustomEventManager();
        }
        return instance;
    }

    private CustomEventManager() {
    }

    public void listen(String eventId, Listener listener) {
        requireEventId(eventId);
        Objects.requireNonNull(listener, "Custom event listener is required");
        FlowRuntime runtime = listener.ctx.getRuntime();
        FlowNode node = runtime.getGraph().getNodes().get(listener.nodeId);
        NodeDefinition definition = runtime.getDefinition(node);
        Set<String> dataPins = null;
        if (definition != null) {
            NodeDefinition.PinDefinition continuation = runtime.resolveOutputPin(node, listener.outputPin);
            if (continuation == null || continuation.getType() == NodeDefinition.PinType.DATA) {
                throw new IllegalArgumentException("Custom event continuation output is not declared: " + listener.outputPin);
            }
            dataPins = new HashSet<>();
            for (NodeDefinition.PinDefinition pin : definition.getOutputs()) {
                if (pin.getType() == NodeDefinition.PinType.DATA) {
                    dataPins.add(pin.getName());
                    dataPins.add(pin.getRuntimeName());
                }
            }
            dataPins = Set.copyOf(dataPins);
        }
        synchronized (this) {
            if (listener.owner != null) {
                throw new IllegalStateException("Custom event listener is already registered");
            }
            listener.owner = this;
            listener.eventId = eventId;
            listener.dataPins = dataPins;
            listener.deadline = listener.timeoutTicks > 0
                ? currentTick + Math.min(listener.timeoutTicks, Long.MAX_VALUE - currentTick) : Long.MAX_VALUE;
        }
        listener.ctx.haltContinuation();
        try {
            listener.ctx.trackOperation(listener.completion, () -> stop(listener, true, null));
        } catch (RuntimeException | Error failure) {
            stop(listener, false, failure);
            throw failure;
        }
        synchronized (this) {
            if (!listener.stopped && listener.deadline > currentTick) {
                listeners.computeIfAbsent(eventId, key -> new ArrayList<>()).add(listener);
                if (listener.timeoutTicks > 0) {
                    listenerTimeouts.put(listener, listener.deadline);
                }
                return;
            }
        }
        stop(listener, false, null);
    }

    public void emit(String eventId, Map<String, Object> data) {
        requireEventId(eventId);
        Map<String, Object> payload = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(data,
            "Custom event data is required")));
        List<Listener> selected = new ArrayList<>();
        List<Listener> rejected = new ArrayList<>();
        Throwable firstFailure = null;
        synchronized (this) {
            lastEventData.put(eventId, payload);
            for (Listener listener : List.copyOf(listeners.getOrDefault(eventId, List.of()))) {
                if (listener.stopped || listener.deadline <= currentTick || listener.once && listener.claimed) {
                    continue;
                }
                if (listener.pending.size() >= MAX_PENDING_EVENTS) {
                    IllegalStateException failure = new IllegalStateException("Custom event listener backlog exceeds " + MAX_PENDING_EVENTS + " events");
                    stopLocked(listener, false, failure);
                    rejected.add(listener);
                    if (firstFailure == null) {
                        firstFailure = failure;
                    } else {
                        firstFailure.addSuppressed(failure);
                    }
                    continue;
                }
                if (listener.once) {
                    listener.claimed = true;
                }
                listener.pending.addLast(payload);
                if (!listener.draining) {
                    listener.draining = true;
                    selected.add(listener);
                }
            }
        }
        rejected.forEach(this::settle);
        for (Listener listener : selected) {
            try {
                drain(listener);
            } catch (RuntimeException | Error failure) {
                if (firstFailure == null) {
                    firstFailure = failure;
                } else if (firstFailure != failure) {
                    firstFailure.addSuppressed(failure);
                }
            }
        }
        if (firstFailure instanceof RuntimeException failure) {
            throw failure;
        }
        if (firstFailure instanceof Error failure) {
            throw failure;
        }
    }

    private void drain(Listener listener) {
        Throwable firstFailure = null;
        while (true) {
            Map<String, Object> next;
            synchronized (this) {
                if (listener.ctx.isExecutionCancelled()) {
                    stopLocked(listener, true, null);
                } else if (listener.deadline <= currentTick) {
                    stopLocked(listener, false, null);
                }
                next = listener.stopped ? null : listener.pending.pollFirst();
                if (next == null) {
                    listener.draining = false;
                } else {
                    listener.inFlight++;
                }
            }
            if (next == null) {
                break;
            }
            Throwable failure = null;
            try {
                FlowContext context = listener.ctx;
                for (Map.Entry<String, Object> entry : next.entrySet()) {
                    context.getRuntime().getEventVariables().put("custom." + listener.eventId + "." + entry.getKey(), entry.getValue());
                    publish(listener, entry.getKey(), entry.getValue());
                }
                publish(listener, "event_data", next);
                publish(listener, "triggered", true);
                context.triggerOutput(listener.outputPin);
            } catch (RuntimeException | Error thrown) {
                failure = thrown;
                if (firstFailure == null) {
                    firstFailure = thrown;
                } else if (firstFailure != thrown) {
                    firstFailure.addSuppressed(thrown);
                }
            } finally {
                synchronized (this) {
                    listener.inFlight--;
                    if (failure != null || listener.once) {
                        stopLocked(listener, false, failure);
                    }
                }
            }
        }
        settle(listener);
        if (firstFailure instanceof RuntimeException failure) {
            throw failure;
        }
        if (firstFailure instanceof Error failure) {
            throw failure;
        }
    }

    private void publish(Listener listener, String pin, Object value) {
        if (listener.dataPins == null || listener.dataPins.contains(pin)) {
            listener.ctx.setNodeOutput(listener.nodeId, pin, value);
        }
    }

    public void clearListeners(String eventId) {
        requireEventId(eventId);
        List<Listener> removed;
        synchronized (this) {
            removed = listeners.remove(eventId);
            lastEventData.remove(eventId);
            if (removed != null) {
                for (Listener listener : removed) {
                    stopLocked(listener, false, null);
                }
            }
        }
        if (removed != null) {
            removed.forEach(this::settle);
        }
    }

    public synchronized Map<String, Object> getLastEventData(String eventId) {
        return lastEventData.get(eventId);
    }

    public void tick() {
        List<Listener> expired = new ArrayList<>();
        synchronized (this) {
            if (currentTick < Long.MAX_VALUE) {
                currentTick++;
            }
            Iterator<Map.Entry<Listener, Long>> iterator = listenerTimeouts.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<Listener, Long> entry = iterator.next();
                if (currentTick >= entry.getValue()) {
                    Listener listener = entry.getKey();
                    listener.stopped = true;
                    listener.pending.clear();
                    if (listener.inFlight == 0) {
                        listener.draining = false;
                    }
                    removeListener(listener);
                    iterator.remove();
                    expired.add(listener);
                }
            }
        }
        expired.forEach(this::settle);
    }

    private void stop(Listener listener, boolean cancelled, Throwable failure) {
        synchronized (this) {
            stopLocked(listener, cancelled, failure);
        }
        settle(listener);
    }

    private void stopLocked(Listener listener, boolean cancelled, Throwable failure) {
        listener.stopped = true;
        listener.cancelled |= cancelled;
        if (listener.failure == null) {
            listener.failure = failure;
        }
        listener.pending.clear();
        if (listener.inFlight == 0) {
            listener.draining = false;
        }
        removeListener(listener);
        listenerTimeouts.remove(listener);
    }

    private void removeListener(Listener listener) {
        List<Listener> registered = listeners.get(listener.eventId);
        if (registered != null) {
            registered.remove(listener);
            if (registered.isEmpty()) {
                listeners.remove(listener.eventId);
            }
        }
    }

    private void settle(Listener listener) {
        Throwable failure;
        boolean cancelled;
        synchronized (this) {
            if (!listener.stopped || listener.inFlight != 0 || listener.draining || listener.completion.isDone()) {
                return;
            }
            failure = listener.failure;
            cancelled = listener.cancelled;
        }
        if (cancelled) {
            listener.completion.cancel(false);
        } else if (failure != null) {
            listener.completion.completeExceptionally(failure);
        } else {
            listener.completion.complete(null);
        }
    }

    private static void requireEventId(String eventId) {
        if (eventId == null || eventId.isBlank()) {
            throw new IllegalArgumentException("Custom event ID is required");
        }
    }

    public static class Listener {
        final FlowContext ctx;
        final String nodeId;
        final int timeoutTicks;
        private final String outputPin;
        private final boolean once;
        private final Deque<Map<String, Object>> pending = new ArrayDeque<>();
        private final CompletableFuture<Void> completion = new CompletableFuture<>();
        private CustomEventManager owner;
        private String eventId;
        private long deadline;
        private boolean claimed;
        private boolean stopped;
        private boolean cancelled;
        private int inFlight;
        private boolean draining;
        private Throwable failure;
        private Set<String> dataPins;

        public Listener(FlowContext ctx, String nodeId, int timeoutTicks) {
            this(ctx, nodeId, timeoutTicks, "next");
        }

        public Listener(FlowContext ctx, String nodeId, int timeoutTicks, String outputPin) {
            this(ctx, nodeId, timeoutTicks, outputPin, timeoutTicks == 0);
        }

        public Listener(FlowContext ctx, String nodeId, int timeoutTicks, String outputPin, boolean once) {
            this.ctx = Objects.requireNonNull(ctx, "Custom event flow context is required");
            this.nodeId = Objects.requireNonNull(nodeId, "Custom event node ID is required");
            this.timeoutTicks = timeoutTicks;
            if (outputPin == null || outputPin.isBlank()) {
                throw new IllegalArgumentException("Custom event continuation output is required");
            }
            this.outputPin = outputPin;
            this.once = once;
        }
    }
}
