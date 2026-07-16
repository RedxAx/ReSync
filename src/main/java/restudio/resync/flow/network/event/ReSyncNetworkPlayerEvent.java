package restudio.resync.flow.network.event;

import org.bukkit.event.Event;
import restudio.resync.network.NetworkPlayerLifecycle;

import java.util.UUID;

public abstract class ReSyncNetworkPlayerEvent extends Event {
    private final String networkId;
    private final NetworkPlayerLifecycle lifecycle;

    protected ReSyncNetworkPlayerEvent(String networkId, NetworkPlayerLifecycle lifecycle) {
        this.networkId = networkId;
        this.lifecycle = lifecycle;
    }

    public String getNetworkId() {
        return networkId;
    }

    public UUID getPlayerId() {
        return lifecycle.playerId();
    }

    public String getPlayerName() {
        return lifecycle.playerName();
    }

    public String getSourceServer() {
        return lifecycle.sourceRoute();
    }

    public String getTargetServer() {
        return lifecycle.targetRoute();
    }

    public String getFailure() {
        return lifecycle.failure();
    }

    public long getOccurredAt() {
        return lifecycle.occurredAt();
    }
}
