package restudio.resync.flow.automation.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import restudio.flow.data.FlowResourceReference;

import java.util.Map;

public final class TimerEvent extends Event {
    public enum Type {
        STARTED,
        TICK,
        PAUSED,
        RESUMED,
        STOPPED,
        FINISHED
    }

    private static final HandlerList HANDLERS = new HandlerList();
    private final FlowResourceReference timer;
    private final Object owner;
    private final Type type;
    private final Map<String, Object> snapshot;

    public TimerEvent(FlowResourceReference timer, Object owner, Type type, Map<String, Object> snapshot) {
        super();
        this.timer = timer;
        this.owner = owner;
        this.type = type;
        this.snapshot = snapshot != null ? Map.copyOf(snapshot) : Map.of();
    }

    public FlowResourceReference getTimer() {
        return timer;
    }

    public String getDefinitionId() {
        return timer.id();
    }

    public Object getOwner() {
        return owner;
    }

    public String getEventType() {
        return type.name().toLowerCase();
    }

    public String getState() {
        return String.valueOf(snapshot.getOrDefault("state", "inactive"));
    }

    public Number getRemaining() {
        return number("remaining");
    }

    public Number getElapsed() {
        return number("elapsed");
    }

    public Number getDuration() {
        return number("duration");
    }

    public Number getProgress() {
        return number("progress");
    }

    private Number number(String key) {
        Object value = snapshot.get(key);
        return value instanceof Number number ? number : 0D;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
