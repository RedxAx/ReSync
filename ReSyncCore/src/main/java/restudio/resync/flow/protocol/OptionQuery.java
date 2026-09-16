package restudio.resync.flow.protocol;

import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.InspectorFieldId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.type.TypedValue;

import java.util.Map;
import java.util.Objects;

public record OptionQuery(ContractRef<InspectorFieldId> sourceRef, ContractRef<CapabilityId> query, ServerId serverId,
                          ServerResourceLocator resource, Map<String, TypedValue> context, Map<String, TypedValue> dependencies,
                          String cursor, int limit, String search, long revision, String invalidationKey) {
    public OptionQuery {
        sourceRef = Objects.requireNonNull(sourceRef, "sourceRef");
        query = Objects.requireNonNull(query, "query");
        serverId = Objects.requireNonNull(serverId, "serverId");
        if (resource != null && !serverId.equals(resource.serverId())) {
            throw new IllegalArgumentException("Resource server does not match option query server");
        }
        context = ProtocolValues.stringMap(context, "context");
        dependencies = ProtocolValues.stringMap(dependencies, "dependencies");
        cursor = ProtocolValues.optionalText(cursor, "cursor", 2048);
        limit = ProtocolValues.limit(limit);
        search = ProtocolValues.optionalText(search, "search", 512);
        revision = ProtocolValues.revision(revision, "revision");
        invalidationKey = ProtocolValues.requiredText(invalidationKey, "invalidationKey", 256);
    }
}
