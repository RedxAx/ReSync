package restudio.resync.flow.automation;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import restudio.resync.flow.FlowContext;

import java.util.UUID;

public record AutomationOwner(String id, Object value) {
    public AutomationOwner {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Automation owner is required");
        }
    }

    public static AutomationOwner resolve(AutomationScope scope, FlowContext context, Object owner) {
        return switch (scope) {
            case FLOW -> new AutomationOwner(flowId(context), null);
            case SERVER -> new AutomationOwner("server", null);
            case PLAYER -> entityOwner(owner != null ? owner : context.getPlayer(), "Player");
            case ENTITY -> entityOwner(owner, "Entity");
            case NETWORK -> textOwner(owner, "Network owner");
        };
    }

    private static AutomationOwner entityOwner(Object value, String label) {
        return switch (value) {
            case Entity entity -> new AutomationOwner(entity.getUniqueId().toString(), entity);
            case UUID uuid -> new AutomationOwner(uuid.toString(), uuid);
            case String text when !text.isBlank() -> new AutomationOwner(text, text);
            case null -> throw new IllegalArgumentException(label + " is required");
            default -> throw new IllegalArgumentException(label + " must be an entity or UUID");
        };
    }

    private static AutomationOwner textOwner(Object value, String label) {
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException(label + " is required");
        }
        return new AutomationOwner(value.toString(), value);
    }

    private static String flowId(FlowContext context) {
        String id = context != null ? context.getFlowId() : null;
        return id != null && !id.isBlank() ? id : "flow";
    }
}
