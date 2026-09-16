package restudio.resync.flow.catalog;

import restudio.resync.flow.identity.ContractRef;

import java.util.Objects;

public record CatalogOwned<T>(ContractRef<?> key, T descriptor, CatalogProvenance provenance) {
    public CatalogOwned {
        key = Objects.requireNonNull(key, "key");
        descriptor = Objects.requireNonNull(descriptor, "descriptor");
        provenance = Objects.requireNonNull(provenance, "provenance");
    }
}
