package restudio.resync.flow.protocol;

import restudio.resync.flow.identity.ServerResourceLocator;

import java.util.Objects;

public record ResourceLoadRequest(ServerResourceLocator resource) implements ResourceOperation {
    public ResourceLoadRequest {
        resource = Objects.requireNonNull(resource, "resource");
    }

    @Override
    public ResourceOperationKind kind() {
        return ResourceOperationKind.LOAD;
    }
}
