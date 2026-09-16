package restudio.resync.flow.protocol;

import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.InspectorFieldId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

public record OptionInvalidation(ContractRef<InspectorFieldId> sourceRef, ContractRef<CapabilityId> query, ServerId serverId,
                                 ServerResourceLocator resource, long revision, String invalidationKey, Set<String> dependencyKeys) {
    public OptionInvalidation {
        sourceRef = Objects.requireNonNull(sourceRef, "sourceRef");
        query = Objects.requireNonNull(query, "query");
        serverId = Objects.requireNonNull(serverId, "serverId");
        if (resource != null && !serverId.equals(resource.serverId())) {
            throw new IllegalArgumentException("Resource server does not match option invalidation server");
        }
        revision = ProtocolValues.revision(revision, "revision");
        invalidationKey = ProtocolValues.requiredText(invalidationKey, "invalidationKey", 256);
        Objects.requireNonNull(dependencyKeys, "dependencyKeys");
        TreeSet<String> sorted = new TreeSet<>();
        for (String dependencyKey : dependencyKeys) {
            if (dependencyKey == null || dependencyKey.isBlank()) {
                throw new IllegalArgumentException("dependencyKeys must contain non-blank values");
            }
            sorted.add(dependencyKey);
        }
        dependencyKeys = Collections.unmodifiableSet(sorted);
    }
}
