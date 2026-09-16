package restudio.resync.flow.trigger;

import java.util.Locale;
import java.util.Objects;

public enum TriggerKind {
    EVENT,
    SYSTEM;

    public String wireValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static TriggerKind fromWireValue(String value) {
        Objects.requireNonNull(value, "Trigger Kind Is Required");
        for (TriggerKind kind : values()) {
            if (kind.wireValue().equals(value)) {
                return kind;
            }
        }
        throw new IllegalArgumentException("Unknown Trigger Kind: " + value);
    }
}
