package restudio.resync.network;

import java.util.Objects;
import java.util.UUID;

public record NetworkEditorOpen(UUID tunnelId, String targetNodeId) {
    public NetworkEditorOpen {
        Objects.requireNonNull(tunnelId, "Tunnel ID");
        targetNodeId = NetworkValues.required(targetNodeId, "Target Node ID");
        if (targetNodeId.length() > 128) throw new IllegalArgumentException("Target Node ID Is Too Long");
    }
}
