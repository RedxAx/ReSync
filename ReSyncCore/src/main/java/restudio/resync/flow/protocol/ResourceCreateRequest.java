package restudio.resync.flow.protocol;

import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ServerResourceLocator;

import java.util.Objects;
import java.util.UUID;

public record ResourceCreateRequest<P>(ServerResourceLocator resource, CanonicalPayload<P> canonicalPayload, UUID mutationId,
                                       ResourcePresentationIntent presentation)
    implements ResourceOperation {
    public ResourceCreateRequest {
        resource = Objects.requireNonNull(resource, "resource");
        canonicalPayload = Objects.requireNonNull(canonicalPayload, "canonicalPayload");
        mutationId = Objects.requireNonNull(mutationId, "mutationId");
    }

    public ResourceCreateRequest(ServerResourceLocator resource, CanonicalPayload<P> canonicalPayload, UUID mutationId) {
        this(resource, canonicalPayload, mutationId, null);
    }

    public P payload() {
        return canonicalPayload.value();
    }

    public ContentHash payloadHash() {
        return canonicalPayload.checksum();
    }

    @Override
    public ResourceOperationKind kind() {
        return ResourceOperationKind.CREATE;
    }
}
