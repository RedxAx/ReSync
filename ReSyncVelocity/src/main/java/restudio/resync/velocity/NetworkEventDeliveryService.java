package restudio.resync.velocity;

import restudio.resync.network.NetworkEvent;
import restudio.resync.network.NetworkPayloads;
import restudio.resync.network.SqliteNetworkHubStore;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

final class NetworkEventDeliveryService {
    private final SqliteNetworkHubStore store;
    private final String networkId;
    private final int deliveryBatch;
    private final DeliveryTarget target;
    private final Object monitor = new Object();
    private final Map<DeliverySession, DeliveryState> states = new HashMap<>();
    private final Map<String, DeliveryState> queries = new HashMap<>();

    NetworkEventDeliveryService(SqliteNetworkHubStore store, String networkId, int deliveryBatch, DeliveryTarget target) {
        this.store = store;
        this.networkId = networkId;
        this.deliveryBatch = Math.clamp(deliveryBatch, 1, 1000);
        this.target = target;
    }

    CompletableFuture<NetworkEvent> publish(NetworkEvent event, long now) {
        return store.publishEvent(event).thenCompose(stored -> store.appendAudit(networkId, event.originNodeId(), "event.published", stored.channel() + ":" + stored.subject(), NetworkPayloads.sha256(stored.payload()), now).thenApply(unused -> stored));
    }

    CompletableFuture<NetworkEvent> publishWithoutAudit(NetworkEvent event) {
        return store.publishEvent(event).thenApply(stored -> {
            deliverAll();
            return stored;
        });
    }

    CompletableFuture<Void> acknowledge(String eventId, DeliverySession session, long now) {
        if (!target.matches(session)) {
            throw new SecurityException("Network Event Session Is No Longer Active");
        }
        synchronized (monitor) {
            DeliveryState state = states.get(session);
            if (state == null || !state.inFlight.containsKey(eventId)) {
                throw new SecurityException("Network Event Was Not Delivered To This Session");
            }
            CompletableFuture<Void> pending = state.acknowledgements.get(eventId);
            if (pending != null) {
                return pending;
            }
            CompletableFuture<Void> result = new CompletableFuture<>();
            state.acknowledgements.put(eventId, result);
            store.acknowledgeEvent(eventId, session.nodeId(), now).whenComplete((unused, failure) -> {
                synchronized (monitor) {
                    state.acknowledgements.remove(eventId, result);
                    if (failure == null) {
                        state.inFlight.remove(eventId);
                    }
                }
                if (failure == null) {
                    result.complete(null);
                } else {
                    result.completeExceptionally(failure);
                }
            });
            return result;
        }
    }

    void deliver(String nodeId) {
        DeliverySession session = target.capture(nodeId);
        if (session == null) {
            return;
        }
        long now = Instant.now().toEpochMilli();
        DeliveryState state;
        boolean query = false;
        synchronized (monitor) {
            state = states.computeIfAbsent(session, DeliveryState::new);
            state.inFlight.entrySet().removeIf(entry -> entry.getValue() > 0 && entry.getValue() <= now);
            if (state.inFlight.size() < deliveryBatch) {
                DeliveryState pending = queries.get(nodeId);
                if (pending != null) {
                    pending.requested = session;
                } else {
                    state.requested = null;
                    queries.put(nodeId, state);
                    query = true;
                }
            }
        }
        if (!target.matches(session)) {
            remove(session);
            if (query) {
                completeQuery(state, List.of(), null);
            }
            return;
        }
        if (query) {
            DeliveryState pending = state;
            store.pendingEvents(networkId, nodeId, deliveryBatch, now).whenComplete((events, failure) -> completeQuery(pending, events, failure));
        }
    }

    private void completeQuery(DeliveryState state, List<NetworkEvent> events, Throwable failure) {
        String nodeId = state.session.nodeId();
        try {
            if (failure != null) {
                if (target.matches(state.session)) {
                    target.failed(nodeId, failure);
                }
            } else {
                for (NetworkEvent event : events) {
                    boolean[] admitted = {false};
                    try {
                        boolean sent = target.send(state.session, event, () -> admitted[0] = admit(state, event));
                        if (!sent && admitted[0]) {
                            synchronized (monitor) {
                                state.inFlight.remove(event.eventId());
                            }
                        }
                    } catch (RuntimeException exception) {
                        if (admitted[0]) {
                            synchronized (monitor) {
                                state.inFlight.remove(event.eventId());
                            }
                        }
                        target.failed(nodeId, exception);
                        break;
                    }
                }
            }
        } finally {
            boolean active = target.matches(state.session);
            boolean retry;
            synchronized (monitor) {
                queries.remove(nodeId, state);
                if (!active) {
                    states.remove(state.session, state);
                }
                retry = !active || state.requested != null && (failure == null || !state.session.equals(state.requested));
                state.requested = null;
            }
            if (retry) {
                deliver(nodeId);
            }
        }
    }

    private boolean admit(DeliveryState state, NetworkEvent event) {
        synchronized (monitor) {
            long now = Instant.now().toEpochMilli();
            if (states.get(state.session) != state || state.inFlight.size() >= deliveryBatch || state.inFlight.containsKey(event.eventId()) || event.expiresAt() > 0 && event.expiresAt() <= now) {
                return false;
            }
            state.inFlight.put(event.eventId(), event.expiresAt());
            return true;
        }
    }

    void deliverAll() {
        target.nodes().forEach(this::deliver);
    }

    void remove(DeliverySession session) {
        synchronized (monitor) {
            states.remove(session);
        }
    }

    void remove(String nodeId) {
        synchronized (monitor) {
            states.keySet().removeIf(session -> session.nodeId().equals(nodeId));
        }
    }

    record DeliverySession(String nodeId, UUID token) {
        DeliverySession {
            Objects.requireNonNull(nodeId, "Node ID");
            Objects.requireNonNull(token, "Session Token");
        }
    }

    private static final class DeliveryState {
        private final DeliverySession session;
        private final Map<String, Long> inFlight = new HashMap<>();
        private final Map<String, CompletableFuture<Void>> acknowledgements = new HashMap<>();
        private DeliverySession requested;

        private DeliveryState(DeliverySession session) {
            this.session = session;
        }
    }

    interface DeliveryTarget {
        Set<String> nodes();

        DeliverySession capture(String nodeId);

        boolean matches(DeliverySession session);

        boolean send(DeliverySession session, NetworkEvent event, BooleanSupplier admit);

        void failed(String nodeId, Throwable throwable);
    }
}
