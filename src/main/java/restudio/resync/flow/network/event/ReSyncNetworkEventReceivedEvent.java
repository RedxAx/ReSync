package restudio.resync.flow.network.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import restudio.resync.flow.network.NetworkFlowValues;
import restudio.resync.network.NetworkEvent;

public class ReSyncNetworkEventReceivedEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final NetworkEvent event;

    public ReSyncNetworkEventReceivedEvent(NetworkEvent event) {
        this.event = event;
    }

    public String getEventId() {
        return event.eventId();
    }

    public String getNetworkId() {
        return event.networkId();
    }

    public String getChannel() {
        return event.channel();
    }

    public String getSubject() {
        return event.subject();
    }

    public String getPayload() {
        return NetworkFlowValues.eventText(event.payload());
    }

    public Object getData() {
        return NetworkFlowValues.eventData(event.payload());
    }

    public String getOriginNodeId() {
        return event.originNodeId();
    }

    public long getCreatedAt() {
        return event.createdAt();
    }

    public long getExpiresAt() {
        return event.expiresAt();
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
