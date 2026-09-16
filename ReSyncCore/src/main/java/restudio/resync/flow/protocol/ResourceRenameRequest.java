package restudio.resync.flow.protocol;

import restudio.resync.flow.identity.ServerResourceLocator;

import java.util.Objects;
import java.util.UUID;

public record ResourceRenameRequest(ServerResourceLocator resource, long expectedRevision, String newName, UUID mutationId)
    implements ResourceOperation {
    public ResourceRenameRequest {
        resource = Objects.requireNonNull(resource, "resource");
        expectedRevision = ProtocolValues.revision(expectedRevision, "expectedRevision");
        newName = ProtocolValues.requiredText(newName, "newName", 256);
        mutationId = Objects.requireNonNull(mutationId, "mutationId");
    }

    @Override
    public ResourceOperationKind kind() {
        return ResourceOperationKind.RENAME;
    }
}
