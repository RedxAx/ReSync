package restudio.resync.flow.protocol;

import restudio.resync.flow.type.TypedValue;

import java.util.Objects;

public record OptionItem(TypedValue value, String label, String description, boolean available, String reason) {
    public OptionItem {
        value = Objects.requireNonNull(value, "value");
        label = ProtocolValues.requiredText(label, "label", 256);
        description = ProtocolValues.requiredText(description, "description", 512);
        reason = ProtocolValues.optionalText(reason, "reason", 512);
        if (!available && (reason == null || reason.isBlank())) {
            throw new IllegalArgumentException("Unavailable options require a reason");
        }
    }
}
