package restudio.flow.data;

import java.util.Map;

public record FlowResourceReference(String kind, String id, String owner, boolean available, Map<String, Object> metadata) {
    public FlowResourceReference {
        kind = kind != null ? kind.strip() : "";
        id = id != null ? id.strip() : "";
        owner = owner != null ? owner.strip() : "";
        metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
    }

    public FlowResourceReference(String kind, String id, String owner) {
        this(kind, id, owner, true, Map.of());
    }
}
