package restudio.resync.flow.automation;

import restudio.flow.data.FlowResourceReference;
import restudio.resync.flow.identity.ServerResourceLocator;

import java.util.Map;

public final class AutomationReferences {
    private AutomationReferences() {
    }

    public static String id(Object value) {
        return switch (value) {
            case ServerResourceLocator locator -> locator.id();
            case FlowResourceReference reference -> reference.id();
            case Map<?, ?> map when map.get("id") != null -> map.get("id").toString();
            case Map<?, ?> map when map.get("resourceId") != null -> map.get("resourceId").toString();
            case String text -> locatorId(text);
            case null -> "";
            default -> locatorId(value.toString());
        };
    }

    private static String locatorId(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        try {
            return ServerResourceLocator.parseCanonicalText(text).id();
        } catch (RuntimeException ignored) {
            return text;
        }
    }
}
