package restudio.resync.flow.trigger;

import java.util.Objects;

public record TriggerSourceDescriptor(boolean active, TriggerKind kind) {
    public TriggerSourceDescriptor {
        kind = Objects.requireNonNull(kind, "Trigger Source Kind Is Required");
    }
}
