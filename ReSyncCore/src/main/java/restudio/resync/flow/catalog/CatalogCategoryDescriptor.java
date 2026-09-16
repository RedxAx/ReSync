package restudio.resync.flow.catalog;

import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.OwnerId;

import java.util.Objects;

public record CatalogCategoryDescriptor(CapabilityId id, String displayName, String description, int order) {
    public CatalogCategoryDescriptor(String id, String displayName, String description, int order) {
        this(CapabilityId.of(id), displayName, description, order);
    }

    public CatalogCategoryDescriptor {
        id = Objects.requireNonNull(id, "categoryId");
        displayName = CatalogIds.text(displayName, "displayName", 128);
        description = CatalogIds.description(description, "description", 240, 16);
    }

    public ContractRef<CapabilityId> reference(OwnerId owner) {
        return ContractRef.of(owner, id);
    }
}
