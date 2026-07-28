package restudio.resync.flow.automation.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import restudio.flow.data.FlowResourceReference;

public final class VariableChangedEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final FlowResourceReference variable;
    private final Object owner;
    private final Object oldValue;
    private final Object newValue;

    public VariableChangedEvent(FlowResourceReference variable, Object owner, Object oldValue, Object newValue) {
        super();
        this.variable = variable;
        this.owner = owner;
        this.oldValue = oldValue;
        this.newValue = newValue;
    }

    public FlowResourceReference getVariable() {
        return variable;
    }

    public String getDefinitionId() {
        return variable.id();
    }

    public Object getOwner() {
        return owner;
    }

    public Object getOldValue() {
        return oldValue;
    }

    public Object getNewValue() {
        return newValue;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
