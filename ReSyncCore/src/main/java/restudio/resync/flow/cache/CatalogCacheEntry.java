package restudio.resync.flow.cache;

import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.IdentitySupport;
import restudio.resync.flow.identity.NodeId;

import java.util.Map;
import java.util.Objects;

public record CatalogCacheEntry(
    ContractRef<NodeId> definitionKey,
    long revision,
    CatalogCacheDefinition definition,
    boolean tombstone,
    Map<String, Object> unknown
) {
    public CatalogCacheEntry {
        definitionKey = Objects.requireNonNull(definitionKey, "definitionKey");
        if (revision < 0) {
            throw new IllegalArgumentException("Catalog cache entry revision cannot be negative");
        }
        if (tombstone && definition != null) {
            throw new IllegalArgumentException("Tombstone catalog cache entries cannot carry a definition");
        }
        if (!tombstone && definition == null) {
            throw new IllegalArgumentException("Present catalog cache entries require a definition");
        }
        if (definition != null && !definitionKey.equals(definition.key())) {
            throw new IllegalArgumentException("Catalog cache entry key does not match its definition");
        }
        unknown = IdentitySupport.unknown(unknown, "catalog cache entry unknown data");
    }

    public CatalogCacheEntry(ContractRef<NodeId> definitionKey, long revision,
                             CatalogCacheDefinition definition, boolean tombstone) {
        this(definitionKey, revision, definition, tombstone, Map.of());
    }

    public static CatalogCacheEntry present(ContractRef<NodeId> definitionKey, long revision, CatalogCacheDefinition definition) {
        return new CatalogCacheEntry(definitionKey, revision, Objects.requireNonNull(definition, "definition"), false);
    }

    public static CatalogCacheEntry present(ContractRef<NodeId> definitionKey, long revision,
                                            CatalogCacheDefinition definition, Map<String, Object> unknown) {
        return new CatalogCacheEntry(definitionKey, revision, Objects.requireNonNull(definition, "definition"), false, unknown);
    }

    public static CatalogCacheEntry tombstone(ContractRef<NodeId> definitionKey, long revision) {
        return new CatalogCacheEntry(definitionKey, revision, null, true);
    }

    public static CatalogCacheEntry tombstone(ContractRef<NodeId> definitionKey, long revision,
                                              Map<String, Object> unknown) {
        return new CatalogCacheEntry(definitionKey, revision, null, true, unknown);
    }

    public boolean present() {
        return !tombstone;
    }
}
