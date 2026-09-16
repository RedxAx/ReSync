package restudio.resync.diagnostics;

import restudio.resync.contract.identity.Revision;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.CorrelationId;
import restudio.resync.flow.identity.OperationId;
import restudio.resync.flow.identity.ResourceKey;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.identity.TraceId;

import java.util.UUID;

public record DiagnosticIdentity(
    ServerId serverId,
    ServerResourceLocator resource,
    ContractRef<OperationId> operation,
    UUID requestId,
    CorrelationId correlationId,
    TraceId traceId,
    UUID mutationId,
    Long generation,
    Long authorityEpoch,
    Revision revision
) {
    public DiagnosticIdentity {
        if (resource != null) {
            if (serverId == null) {
                serverId = resource.serverId();
            } else if (!serverId.equals(resource.serverId())) {
                throw new IllegalArgumentException("Diagnostic server ID does not match resource server ID");
            }
        }
        requireNonNegative(generation, "generation");
        requireNonNegative(authorityEpoch, "authorityEpoch");
    }

    public static DiagnosticIdentity empty() {
        return new DiagnosticIdentity(null, null, null, null, null, null, null, null, null, null);
    }

    public ResourceKey typedKey() {
        return resource == null ? null : resource.key();
    }

    private static void requireNonNegative(Long value, String name) {
        if (value != null && value < 0L) {
            throw new IllegalArgumentException("Diagnostic " + name + " cannot be negative");
        }
    }
}
