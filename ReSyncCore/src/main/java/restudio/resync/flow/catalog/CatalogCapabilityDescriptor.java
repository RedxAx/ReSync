package restudio.resync.flow.catalog;

import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.inspector.InspectorFallback;

import java.util.Objects;

public record CatalogCapabilityDescriptor(CapabilityId id, int version, boolean optional, InspectorFallback fallback) {
    public CatalogCapabilityDescriptor {
        id = Objects.requireNonNull(id, "capabilityId");
        if (version < 1) {
            throw new IllegalArgumentException("Capability version must be positive");
        }
        fallback = Objects.requireNonNull(fallback, "fallback");
    }

    public ContractRef<CapabilityId> reference(OwnerId owner) {
        return ContractRef.of(owner, id);
    }
}
