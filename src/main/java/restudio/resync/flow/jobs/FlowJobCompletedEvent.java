package restudio.resync.flow.jobs;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import restudio.flow.data.FlowJobReference;

public final class FlowJobCompletedEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final FlowJobReference.Snapshot<?> snapshot;

    public FlowJobCompletedEvent(FlowJobReference.Snapshot<?> snapshot) {
        this.snapshot = snapshot;
    }

    public FlowJobReference.Snapshot<?> getSnapshot() {
        return snapshot;
    }

    public FlowJobReference<?> getReference() {
        return FlowJobReference.restore(snapshot);
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
