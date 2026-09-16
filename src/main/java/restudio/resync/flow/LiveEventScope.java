package restudio.resync.flow;

import org.bukkit.event.Event;
import restudio.resync.flow.identity.CorrelationId;
import restudio.resync.flow.runtime.CompiledRuntimeContext;

import java.util.Objects;

public final class LiveEventScope implements AutoCloseable {
    public static boolean requiresWindow(String definition) {
        if (definition == null) {
            return false;
        }
        String local = definition.substring(definition.indexOf('/') + 1);
        return switch (local) {
            case "event_cancel", "chat_cancel", "chat_set_message", "chat_add_viewer", "chat_remove_viewer",
                 "ability_cancel_damage", "ability_reflect_damage" -> true;
            default -> false;
        };
    }

    private final CompiledRuntimeContext context;
    private final CorrelationId invocationId;
    private final Thread origin;
    private final LiveEventScope parent;
    private volatile Event event;

    LiveEventScope(CompiledRuntimeContext context, CorrelationId invocationId, Event event, LiveEventScope parent) {
        this.context = Objects.requireNonNull(context, "Compiled runtime context is required");
        this.invocationId = Objects.requireNonNull(invocationId, "Invocation ID is required");
        this.event = Objects.requireNonNull(event, "Live event is required");
        this.origin = Thread.currentThread();
        this.parent = parent;
    }

    public Event event(CompiledRuntimeContext context, CorrelationId invocationId) {
        return this.context == context && this.invocationId.equals(invocationId) && isOpen() ? event : null;
    }

    public boolean isOpen() {
        return event != null && Thread.currentThread() == origin && (parent == null || parent.isOpen());
    }

    @Override
    public void close() {
        event = null;
    }
}
