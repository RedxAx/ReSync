package restudio.resync.flow.protocol;

import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ServerResourceLocator;

import java.util.Objects;
import java.util.UUID;

public record ResourceSaveRequest<P>(ServerResourceLocator resource, long expectedRevision, CanonicalPayload<P> canonicalPayload, UUID mutationId)
    implements ResourceOperation {
    public ResourceSaveRequest {
        resource = Objects.requireNonNull(resource, "resource");
        expectedRevision = ProtocolValues.revision(expectedRevision, "expectedRevision");
        canonicalPayload = Objects.requireNonNull(canonicalPayload, "canonicalPayload");
        mutationId = Objects.requireNonNull(mutationId, "mutationId");
    }

    public P payload() {
        return canonicalPayload.value();
    }

    public ContentHash payloadHash() {
        return canonicalPayload.checksum();
    }

    @Override
    public ResourceOperationKind kind() {
        return ResourceOperationKind.SAVE;
    }
}
