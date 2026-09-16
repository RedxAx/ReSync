package restudio.resync.flow.protocol;

import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerResourceLocator;

import java.util.Objects;

public record ResourceSubscribeRequest(ContractRef<ResourceTypeId> type, ServerResourceLocator resource, long afterRevision, boolean includePayload)
    implements ResourceOperation {
    public ResourceSubscribeRequest {
        if (type == null && resource == null) {
            throw new IllegalArgumentException("A subscription needs a resource type or resource");
        }
        if (type != null && resource != null && !type.equals(resource.type())) {
            throw new IllegalArgumentException("Subscription type does not match resource type");
        }
        afterRevision = ProtocolValues.revision(afterRevision, "afterRevision");
    }

    public ResourceSubscribeRequest(ServerResourceLocator resource, long afterRevision, boolean includePayload) {
        this(null, Objects.requireNonNull(resource, "resource"), afterRevision, includePayload);
    }

    public ResourceSubscribeRequest(ContractRef<ResourceTypeId> type, long afterRevision, boolean includePayload) {
        this(Objects.requireNonNull(type, "type"), null, afterRevision, includePayload);
    }

    @Override
    public ResourceOperationKind kind() {
        return ResourceOperationKind.SUBSCRIBE;
    }
}
