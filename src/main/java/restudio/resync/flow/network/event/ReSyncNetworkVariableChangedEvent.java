package restudio.resync.flow.network.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import restudio.resync.flow.network.NetworkFlowValues;
import restudio.resync.network.NetworkVariable;

public class ReSyncNetworkVariableChangedEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final NetworkVariable variable;

    public ReSyncNetworkVariableChangedEvent(NetworkVariable variable) {
        this.variable = variable;
    }

    public String getNetworkId() {
        return variable.networkId();
    }

    public String getScope() {
        return variable.scope().name();
    }

    public String getScopeId() {
        return variable.scopeId();
    }

    public String getKey() {
        return variable.key();
    }

    public String getType() {
        return variable.type().name();
    }

    public Object getValue() {
        return NetworkFlowValues.decode(variable);
    }

    public long getRevision() {
        return variable.revision();
    }

    public long getExpiresAt() {
        return variable.expiresAt();
    }

    public String getOriginNodeId() {
        return variable.originNodeId();
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
