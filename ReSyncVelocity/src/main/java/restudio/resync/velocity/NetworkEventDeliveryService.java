package restudio.resync.velocity;

import restudio.resync.network.NetworkEvent;
import restudio.resync.network.NetworkPayloads;
import restudio.resync.network.SqliteNetworkHubStore;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

final class NetworkEventDeliveryService {
    private final SqliteNetworkHubStore store;
    private final String networkId;
    private final int deliveryBatch;
    private final DeliveryTarget target;
    private final Map<String, Set<String>> inFlightByNode = new ConcurrentHashMap<>();

    NetworkEventDeliveryService(SqliteNetworkHubStore store, String networkId, int deliveryBatch, DeliveryTarget target) {
        this.store = store;
        this.networkId = networkId;
        this.deliveryBatch = deliveryBatch;
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

    CompletableFuture<Void> acknowledge(String eventId, String nodeId, long now) {
        Set<String> inFlight = inFlightByNode.get(nodeId);
        if (inFlight == null || !inFlight.contains(eventId)) {
            throw new SecurityException("Network Event Was Not Delivered To This Session");
        }
        return store.acknowledgeEvent(eventId, nodeId, now).thenRun(() -> inFlight.remove(eventId));
    }

    void deliver(String nodeId) {
        if (!target.available(nodeId)) {
            return;
        }
        Set<String> inFlight = inFlightByNode.computeIfAbsent(nodeId, ignored -> ConcurrentHashMap.newKeySet());
        int available = Math.max(0, deliveryBatch - inFlight.size());
        if (available == 0) {
            return;
        }
        store.pendingEvents(networkId, nodeId, available, Instant.now().toEpochMilli()).thenAccept(events -> events.forEach(event -> {
            if (inFlight.add(event.eventId())) {
                target.send(nodeId, event);
            }
        })).exceptionally(throwable -> {
            target.failed(nodeId, throwable);
            return null;
        });
    }

    void deliverAll() {
        target.nodes().forEach(this::deliver);
    }

    void remove(String nodeId) {
        inFlightByNode.remove(nodeId);
    }

    interface DeliveryTarget {
        Set<String> nodes();

        boolean available(String nodeId);

        void send(String nodeId, NetworkEvent event);

        void failed(String nodeId, Throwable throwable);
    }
}
