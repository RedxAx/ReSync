package restudio.resync.flow.protocol;

import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.ResourceTypeId;

import java.util.Objects;

public record ResourceListRequest(ContractRef<ResourceTypeId> type, String cursor, int limit, String search) implements ResourceOperation {
    public ResourceListRequest {
        type = Objects.requireNonNull(type, "type");
        cursor = ProtocolValues.optionalText(cursor, "cursor", 2048);
        limit = ProtocolValues.limit(limit);
        search = ProtocolValues.optionalText(search, "search", 512);
    }

    @Override
    public ResourceOperationKind kind() {
        return ResourceOperationKind.LIST;
    }
}
