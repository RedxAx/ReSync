package restudio.resync.flow.protocol;

import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.type.TypedValue;

import java.util.Map;
import java.util.Objects;

public record ResourceQueryRequest(ContractRef<ResourceTypeId> type, Map<String, TypedValue> filters, String cursor, int limit, String search)
    implements ResourceOperation {
    public ResourceQueryRequest {
        type = Objects.requireNonNull(type, "type");
        filters = ProtocolValues.stringMap(filters, "filters");
        cursor = ProtocolValues.optionalText(cursor, "cursor", 2048);
        limit = ProtocolValues.limit(limit);
        search = ProtocolValues.optionalText(search, "search", 512);
    }

    @Override
    public ResourceOperationKind kind() {
        return ResourceOperationKind.QUERY;
    }
}
