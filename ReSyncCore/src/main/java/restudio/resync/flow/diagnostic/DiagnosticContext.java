package restudio.resync.flow.diagnostic;

import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.PinId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;

public record DiagnosticContext(
    ServerId serverId,
    ServerResourceLocator resource,
    NodeId nodeId,
    PinId pinId,
    Long catalogGeneration,
    DiagnosticProvenance provenance
) {
    public DiagnosticContext {
        if (pinId != null && nodeId == null) {
            throw new IllegalArgumentException("A diagnostic pin context requires a node context");
        }
        if (resource != null) {
            if (serverId == null) {
                serverId = resource.serverId();
            } else if (!serverId.equals(resource.serverId())) {
                throw new IllegalArgumentException("Diagnostic server ID does not match resource server ID");
            }
        }
        if (catalogGeneration != null && catalogGeneration < 1) {
            throw new IllegalArgumentException("catalogGeneration must be positive");
        }
    }

    public static DiagnosticContext empty() {
        return new DiagnosticContext(null, null, null, null, null, null);
    }

    public OwnerId ownerId() {
        return provenance == null ? null : provenance.ownerId();
    }
}
