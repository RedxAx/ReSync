package restudio.resync.flow.automation.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import restudio.flow.data.FlowResourceReference;

import java.util.Map;
import java.util.Collections;
import java.util.LinkedHashMap;

public final class ScheduleFiredEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final FlowResourceReference schedule;
    private final Map<String, Object> task;
    private final Object owner;
    private final Map<String, Object> arguments;

    public ScheduleFiredEvent(FlowResourceReference schedule, Map<String, Object> task, Object owner, Map<String, Object> arguments) {
        super();
        this.schedule = schedule;
        this.task = task != null ? Map.copyOf(task) : Map.of();
        this.owner = owner;
        this.arguments = arguments != null ? Collections.unmodifiableMap(new LinkedHashMap<>(arguments)) : Map.of();
    }

    public FlowResourceReference getSchedule() {
        return schedule;
    }

    public String getDefinitionId() {
        return schedule.id();
    }

    public Map<String, Object> getTask() {
        return task;
    }

    public Object getOwner() {
        return owner;
    }

    public Map<String, Object> getArguments() {
        return arguments;
    }

    public Number getFiredAt() {
        Object value = task.get("lastRun");
        return value instanceof Number number ? number : 0L;
    }

    public Number getRunCount() {
        Object value = task.get("runCount");
        return value instanceof Number number ? number : 0L;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
