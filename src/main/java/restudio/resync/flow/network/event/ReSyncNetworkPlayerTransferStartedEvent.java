package restudio.resync.flow.network.event;

import org.bukkit.event.HandlerList;
import restudio.resync.network.NetworkPlayerLifecycle;

public class ReSyncNetworkPlayerTransferStartedEvent extends ReSyncNetworkPlayerEvent {
    private static final HandlerList HANDLERS = new HandlerList();

    public ReSyncNetworkPlayerTransferStartedEvent(String networkId, NetworkPlayerLifecycle lifecycle) {
        super(networkId, lifecycle);
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
