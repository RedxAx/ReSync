package restudio.resync.flow.protocol;

import restudio.resync.flow.identity.ServerResourceLocator;

import java.util.Objects;
import java.util.UUID;

public record ResourceDuplicateRequest(ServerResourceLocator source, ServerResourceLocator target, long expectedRevision, UUID mutationId)
    implements ResourceOperation {
    public ResourceDuplicateRequest {
        source = Objects.requireNonNull(source, "source");
        target = Objects.requireNonNull(target, "target");
        if (!source.serverId().equals(target.serverId()) || !source.type().equals(target.type())) {
            throw new IllegalArgumentException("Duplicate source and target must share server and resource type");
        }
        if (source.id().equals(target.id())) {
            throw new IllegalArgumentException("Duplicate target must differ from source");
        }
        expectedRevision = ProtocolValues.revision(expectedRevision, "expectedRevision");
        mutationId = Objects.requireNonNull(mutationId, "mutationId");
    }

    @Override
    public ResourceOperationKind kind() {
        return ResourceOperationKind.DUPLICATE;
    }
}
