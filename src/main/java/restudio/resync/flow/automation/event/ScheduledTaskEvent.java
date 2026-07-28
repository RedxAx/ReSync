package restudio.resync.flow.automation.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import restudio.flow.data.FlowResourceReference;

import java.util.Map;

public final class ScheduledTaskEvent extends Event {
    public enum Type {
        FIRED,
        COMPLETED,
        FAILED,
        CANCELLED,
        PAUSED,
        RESUMED
    }

    private static final HandlerList HANDLERS = new HandlerList();
    private final FlowResourceReference schedule;
    private final Map<String, Object> task;
    private final Object owner;
    private final Type type;
    private final String targetType;
    private final String target;
    private final Object functionResult;
    private final String error;

    public ScheduledTaskEvent(FlowResourceReference schedule, Map<String, Object> task, Object owner, Type type,
                              String targetType, String target, Object functionResult, String error) {
        super();
        this.schedule = schedule;
        this.task = task != null ? Map.copyOf(task) : Map.of();
        this.owner = owner;
        this.type = type;
        this.targetType = targetType != null ? targetType : "";
        this.target = target != null ? target : "";
        this.functionResult = functionResult;
        this.error = error != null ? error : "";
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

    public String getEventType() {
        return type.name().toLowerCase();
    }

    public String getTargetType() {
        return targetType;
    }

    public String getTarget() {
        return target;
    }

    public Number getFiredAt() {
        Object value = task.get("lastRun");
        return value instanceof Number number ? number : 0L;
    }

    public Number getRunCount() {
        Object value = task.get("runCount");
        return value instanceof Number number ? number : 0L;
    }

    public Object getFunctionResult() {
        return functionResult;
    }

    public String getError() {
        return error;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
