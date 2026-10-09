package restudio.resync.flow.network;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import restudio.resync.flow.network.event.ReSyncNetworkEventReceivedEvent;
import restudio.resync.flow.network.event.ReSyncNetworkPlayerJoinedEvent;
import restudio.resync.flow.network.event.ReSyncNetworkPlayerLeftEvent;
import restudio.resync.flow.network.event.ReSyncNetworkPlayerTransferCompletedEvent;
import restudio.resync.flow.network.event.ReSyncNetworkPlayerTransferFailedEvent;
import restudio.resync.flow.network.event.ReSyncNetworkPlayerTransferStartedEvent;
import restudio.resync.flow.network.event.ReSyncNetworkServerStatusEvent;
import restudio.resync.flow.network.event.ReSyncNetworkVariableChangedEvent;
import restudio.resync.network.NetworkEvent;
import restudio.resync.network.NetworkEventTopics;
import restudio.resync.network.NetworkNodePresence;
import restudio.resync.network.NetworkPlayerLifecycle;
import restudio.resync.network.NetworkPlayerLifecycleCodec;
import restudio.resync.network.NetworkVariable;
import restudio.resync.network.paper.ReSyncNetworkAgent;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.Set;

public class NetworkFlowBridge implements ReSyncNetworkAgent.Listener {
    private final Plugin plugin;
    private volatile Delivery current;

    public NetworkFlowBridge(Plugin plugin) {
        this.plugin = plugin;
    }

    public synchronized void connect(ReSyncNetworkAgent networkAgent) {
        disconnect();
        if (networkAgent != null) {
            Delivery delivery = new Delivery(networkAgent);
            current = delivery;
            networkAgent.addListener(delivery);
        }
    }

    public synchronized void disconnect() {
        Delivery delivery = current;
        current = null;
        if (delivery != null) {
            delivery.close();
            delivery.agent.removeListener(delivery);
        }
    }

    @Override
    public void onPresenceChanged(NetworkNodePresence presence) {
        Delivery delivery = current;
        if (delivery != null) {
            delivery.onPresenceChanged(presence);
        }
    }

    @Override
    public void onVariableChanged(NetworkVariable variable) {
        Delivery delivery = current;
        if (delivery != null) {
            delivery.onVariableChanged(variable);
        }
    }

    @Override
    public CompletionStage<Void> onEventReceived(NetworkEvent event) {
        Delivery delivery = current;
        return delivery == null ? CompletableFuture.failedFuture(unavailable()) : delivery.onEventReceived(event);
    }

    private IllegalStateException unavailable() {
        return new IllegalStateException("Network Flow Delivery Is Unavailable");
    }

    private final class Delivery implements ReSyncNetworkAgent.Listener {
        private final ReSyncNetworkAgent agent;
        private final Map<String, Observation> observations = new ConcurrentHashMap<>();
        private final Set<CompletableFuture<Void>> pending = ConcurrentHashMap.newKeySet();
        private volatile boolean closed;

        private Delivery(ReSyncNetworkAgent agent) {
            this.agent = agent;
        }

        private boolean active() {
            return !closed && current == this && plugin.isEnabled();
        }

        @Override
        public void onPresenceChanged(NetworkNodePresence presence) {
            if (presence.capacity() <= 0 || !active()) {
                return;
            }
            dispatch(() -> {
                String health = health(presence);
                Observation previous = observations.put(presence.nodeId(), new Observation(presence.status().name(), health));
                if (previous != null && (!previous.status().equals(presence.status().name()) || !previous.health().equals(health))) {
                    Bukkit.getPluginManager().callEvent(new ReSyncNetworkServerStatusEvent(presence, previous.status(), health, previous.health()));
                }
            });
        }

        @Override
        public void onVariableChanged(NetworkVariable variable) {
            dispatch(() -> Bukkit.getPluginManager().callEvent(new ReSyncNetworkVariableChangedEvent(variable)));
        }

        @Override
        public CompletionStage<Void> onEventReceived(NetworkEvent event) {
            return dispatch(() -> {
                if (NetworkEventTopics.PLAYER_LIFECYCLE.equals(event.channel())) {
                    dispatchPlayerLifecycle(event.networkId(), NetworkPlayerLifecycleCodec.decode(event.payload()));
                }
                if (!active()) {
                    throw unavailable();
                }
                Bukkit.getPluginManager().callEvent(new ReSyncNetworkEventReceivedEvent(event));
            });
        }

        private CompletableFuture<Void> dispatch(Runnable action) {
            CompletableFuture<Void> completed = new CompletableFuture<>();
            pending.add(completed);
            completed.whenComplete((ignored, failure) -> pending.remove(completed));
            if (!active()) {
                completed.completeExceptionally(unavailable());
                return completed;
            }
            Runnable delivery = () -> {
                if (completed.isDone()) {
                    return;
                }
                try {
                    if (!active()) {
                        throw unavailable();
                    }
                    action.run();
                    completed.complete(null);
                } catch (RuntimeException exception) {
                    completed.completeExceptionally(exception);
                }
            };
            try {
                if (Bukkit.isPrimaryThread()) {
                    delivery.run();
                } else {
                    Bukkit.getScheduler().runTask(plugin, delivery);
                }
            } catch (RuntimeException exception) {
                completed.completeExceptionally(exception);
            }
            return completed;
        }

        private void close() {
            closed = true;
            pending.forEach(future -> future.completeExceptionally(unavailable()));
            observations.clear();
        }
    }

    private String health(NetworkNodePresence presence) {
        if (presence.status().name().equals("OFFLINE") || presence.status().name().equals("REVOKED")) {
            return "UNAVAILABLE";
        }
        if ((presence.tps() >= 0 && presence.tps() < 18) || presence.mspt() > 50) {
            return "DEGRADED";
        }
        return "HEALTHY";
    }

    private void dispatchPlayerLifecycle(String networkId, NetworkPlayerLifecycle lifecycle) {
        switch (lifecycle.type()) {
            case JOINED -> Bukkit.getPluginManager().callEvent(new ReSyncNetworkPlayerJoinedEvent(networkId, lifecycle));
            case LEFT -> Bukkit.getPluginManager().callEvent(new ReSyncNetworkPlayerLeftEvent(networkId, lifecycle));
            case TRANSFER_STARTED -> Bukkit.getPluginManager().callEvent(new ReSyncNetworkPlayerTransferStartedEvent(networkId, lifecycle));
            case TRANSFER_COMPLETED -> Bukkit.getPluginManager().callEvent(new ReSyncNetworkPlayerTransferCompletedEvent(networkId, lifecycle));
            case TRANSFER_FAILED -> Bukkit.getPluginManager().callEvent(new ReSyncNetworkPlayerTransferFailedEvent(networkId, lifecycle));
        }
    }

    private record Observation(String status, String health) {
    }
}
