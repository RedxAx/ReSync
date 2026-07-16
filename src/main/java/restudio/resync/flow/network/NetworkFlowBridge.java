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

public class NetworkFlowBridge implements ReSyncNetworkAgent.Listener {
    private final Plugin plugin;
    private final Map<String, Observation> observations = new ConcurrentHashMap<>();
    private ReSyncNetworkAgent agent;

    public NetworkFlowBridge(Plugin plugin) {
        this.plugin = plugin;
    }

    public synchronized void connect(ReSyncNetworkAgent networkAgent) {
        disconnect();
        agent = networkAgent;
        if (agent != null) {
            agent.addListener(this);
        }
    }

    public synchronized void disconnect() {
        if (agent != null) {
            agent.removeListener(this);
            agent = null;
        }
        observations.clear();
    }

    @Override
    public void onPresenceChanged(NetworkNodePresence presence) {
        if (presence.capacity() <= 0) {
            return;
        }
        String health = health(presence);
        Observation previous = observations.put(presence.nodeId(), new Observation(presence.status().name(), health));
        if (previous == null || previous.status().equals(presence.status().name()) && previous.health().equals(health)) {
            return;
        }
        dispatch(() -> Bukkit.getPluginManager().callEvent(new ReSyncNetworkServerStatusEvent(presence, previous.status(), health, previous.health())));
    }

    @Override
    public void onVariableChanged(NetworkVariable variable) {
        dispatch(() -> Bukkit.getPluginManager().callEvent(new ReSyncNetworkVariableChangedEvent(variable)));
    }

    @Override
    public CompletionStage<Void> onEventReceived(NetworkEvent event) {
        CompletableFuture<Void> completed = new CompletableFuture<>();
        dispatch(() -> {
            try {
                if (NetworkEventTopics.PLAYER_LIFECYCLE.equals(event.channel())) {
                    dispatchPlayerLifecycle(event.networkId(), NetworkPlayerLifecycleCodec.decode(event.payload()));
                }
                Bukkit.getPluginManager().callEvent(new ReSyncNetworkEventReceivedEvent(event));
                completed.complete(null);
            } catch (RuntimeException exception) {
                completed.completeExceptionally(exception);
            }
        });
        return completed;
    }

    private void dispatch(Runnable action) {
        if (!plugin.isEnabled()) {
            return;
        }
        if (Bukkit.isPrimaryThread()) {
            action.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, action);
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
