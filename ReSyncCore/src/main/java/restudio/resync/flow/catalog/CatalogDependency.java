package restudio.resync.flow.catalog;

import restudio.resync.flow.identity.OwnerId;

import java.util.Objects;

public record CatalogDependency(OwnerId ownerId, String versionRange, boolean optional) {
    public CatalogDependency {
        ownerId = Objects.requireNonNull(ownerId, "ownerId");
        versionRange = CatalogIds.text(versionRange, "versionRange", 128);
    }

}
