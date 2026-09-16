package restudio.resync.flow.protocol;

import restudio.resync.flow.identity.ServerResourceLocator;

import java.util.Objects;
import java.util.UUID;

public record ResourceActivateRequest(ServerResourceLocator resource, long expectedRevision,
                                      ResourceActivationState targetState, UUID mutationId) implements ResourceOperation {
    public ResourceActivateRequest(ServerResourceLocator resource, long expectedRevision, UUID mutationId) {
        this(resource, expectedRevision, ResourceActivationState.ACTIVE, mutationId);
    }

    public ResourceActivateRequest(ServerResourceLocator resource, long expectedRevision, UUID mutationId,
                                   ResourceActivationState targetState) {
        this(resource, expectedRevision, targetState, mutationId);
    }

    public ResourceActivateRequest {
        resource = Objects.requireNonNull(resource, "resource");
        expectedRevision = ProtocolValues.revision(expectedRevision, "expectedRevision");
        targetState = Objects.requireNonNull(targetState, "targetState");
        mutationId = Objects.requireNonNull(mutationId, "mutationId");
    }

    @Override
    public ResourceOperationKind kind() {
        return ResourceOperationKind.ACTIVATE;
    }
}
