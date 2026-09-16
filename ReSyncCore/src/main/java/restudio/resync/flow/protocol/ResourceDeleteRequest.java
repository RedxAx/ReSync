package restudio.resync.flow.protocol;

import restudio.resync.flow.identity.ServerResourceLocator;

import java.util.Objects;
import java.util.UUID;

public record ResourceDeleteRequest(ServerResourceLocator resource, long expectedRevision, UUID mutationId) implements ResourceOperation {
    public ResourceDeleteRequest {
        resource = Objects.requireNonNull(resource, "resource");
        expectedRevision = ProtocolValues.revision(expectedRevision, "expectedRevision");
        mutationId = Objects.requireNonNull(mutationId, "mutationId");
    }

    @Override
    public ResourceOperationKind kind() {
        return ResourceOperationKind.DELETE;
    }
}
