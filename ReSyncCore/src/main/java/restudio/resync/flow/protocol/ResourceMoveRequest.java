package restudio.resync.flow.protocol;

import restudio.resync.flow.identity.ServerResourceLocator;

import java.util.Objects;
import java.util.UUID;

public record ResourceMoveRequest(ServerResourceLocator resource, long expectedRevision, String destination, UUID mutationId)
    implements ResourceOperation {
    public ResourceMoveRequest {
        resource = Objects.requireNonNull(resource, "resource");
        expectedRevision = ProtocolValues.revision(expectedRevision, "expectedRevision");
        destination = ProtocolValues.requiredText(destination, "destination", 1024);
        mutationId = Objects.requireNonNull(mutationId, "mutationId");
    }

    @Override
    public ResourceOperationKind kind() {
        return ResourceOperationKind.MOVE;
    }
}
