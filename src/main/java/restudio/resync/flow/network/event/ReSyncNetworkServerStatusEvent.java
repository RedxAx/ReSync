package restudio.resync.flow.network.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import restudio.resync.network.NetworkNodePresence;

public class ReSyncNetworkServerStatusEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final NetworkNodePresence presence;
    private final String previousStatus;
    private final String health;
    private final String previousHealth;

    public ReSyncNetworkServerStatusEvent(NetworkNodePresence presence, String previousStatus, String health, String previousHealth) {
        this.presence = presence;
        this.previousStatus = previousStatus;
        this.health = health;
        this.previousHealth = previousHealth;
    }

    public String getNetworkId() {
        return presence.networkId();
    }

    public String getNodeId() {
        return presence.nodeId();
    }

    public String getStatus() {
        return presence.status().name();
    }

    public String getPreviousStatus() {
        return previousStatus;
    }

    public String getHealth() {
        return health;
    }

    public String getPreviousHealth() {
        return previousHealth;
    }

    public int getPlayers() {
        return presence.players();
    }

    public int getCapacity() {
        return presence.capacity();
    }

    public double getTps() {
        return presence.tps();
    }

    public double getMspt() {
        return presence.mspt();
    }

    public long getObservedAt() {
        return presence.observedAt();
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
