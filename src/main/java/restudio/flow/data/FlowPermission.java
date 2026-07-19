package restudio.flow.data;

import java.util.Map;

public record FlowPermission(String node, Map<String, String> context) {
    public FlowPermission {
        node = node != null ? node.strip() : "";
        context = context != null ? Map.copyOf(context) : Map.of();
    }

    public FlowPermission(String node) {
        this(node, Map.of());
    }
}
