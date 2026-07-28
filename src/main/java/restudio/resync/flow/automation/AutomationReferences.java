package restudio.resync.flow.automation;

import restudio.flow.data.FlowResourceReference;

import java.util.Map;

public final class AutomationReferences {
    private AutomationReferences() {
    }

    public static String id(Object value) {
        return switch (value) {
            case FlowResourceReference reference -> reference.id();
            case Map<?, ?> map when map.get("id") != null -> map.get("id").toString();
            case Map<?, ?> map when map.get("resourceId") != null -> map.get("resourceId").toString();
            case null -> "";
            default -> value.toString();
        };
    }
}
