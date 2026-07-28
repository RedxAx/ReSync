package restudio.resync.flow.automation;

import java.util.Locale;

public enum AutomationScope {
    FLOW,
    SERVER,
    PLAYER,
    ENTITY,
    NETWORK;

    public boolean requiresOwner() {
        return this == PLAYER || this == ENTITY || this == NETWORK;
    }

    public static AutomationScope parse(String value) {
        if (value == null || value.isBlank()) {
            return FLOW;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if ("LOCAL".equals(normalized)) {
            return FLOW;
        }
        if ("GLOBAL".equals(normalized)) {
            return SERVER;
        }
        return AutomationScope.valueOf(normalized);
    }
}
