package restudio.resync.flow.cache;

import restudio.resync.contract.cache.CatalogProjectionVersion;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ServerId;

import java.util.Objects;

public record CatalogCacheKey(ServerId serverId, long catalogGeneration, ContentHash snapshotChecksum,
                              CatalogProjectionVersion projectionVersion, ContentHash bindingManifestHash)
    implements Comparable<CatalogCacheKey> {
    public CatalogCacheKey {
        serverId = Objects.requireNonNull(serverId, "Server ID is required");
        if (catalogGeneration < 1) {
            throw new IllegalArgumentException("Catalog generation must be positive");
        }
        snapshotChecksum = Objects.requireNonNull(snapshotChecksum, "Snapshot checksum is required");
        projectionVersion = Objects.requireNonNull(projectionVersion, "Catalog projection version is required");
    }

    public CatalogCacheKey(ServerId serverId, long catalogGeneration, ContentHash snapshotChecksum,
                           CatalogProjectionVersion projectionVersion) {
        this(serverId, catalogGeneration, snapshotChecksum, projectionVersion, null);
    }

    public CatalogCacheKey(ServerId serverId, long catalogGeneration, ContentHash snapshotChecksum,
                           ContentHash bindingManifestHash, CatalogProjectionVersion projectionVersion) {
        this(serverId, catalogGeneration, snapshotChecksum, projectionVersion, bindingManifestHash);
    }

    public CatalogCacheKey(ServerId serverId, CatalogBinding binding, CatalogProjectionVersion projectionVersion) {
        this(serverId, Objects.requireNonNull(binding, "Catalog binding is required").generation(),
            binding.catalogChecksum(), projectionVersion, binding.bindingManifestHash());
    }

    public CatalogCacheKey(ServerId serverId, CatalogBinding binding) {
        this(serverId, binding, CatalogProjectionVersion.current());
    }

    public CatalogCacheKey(ServerId serverId, ContentHash snapshotChecksum) {
        this(serverId, 1, snapshotChecksum, CatalogProjectionVersion.LEGACY, null);
    }

    public CatalogCacheKey(ServerId serverId, long catalogGeneration, ContentHash snapshotChecksum) {
        this(serverId, catalogGeneration, snapshotChecksum, CatalogProjectionVersion.LEGACY, null);
    }

    public static CatalogCacheKey of(ServerId serverId, ContentHash snapshotChecksum) {
        return new CatalogCacheKey(serverId, snapshotChecksum);
    }

    public static CatalogCacheKey of(ServerId serverId, long catalogGeneration, ContentHash snapshotChecksum,
                                     CatalogProjectionVersion projectionVersion) {
        return new CatalogCacheKey(serverId, catalogGeneration, snapshotChecksum, projectionVersion);
    }

    public static CatalogCacheKey of(ServerId serverId, CatalogBinding binding,
                                     CatalogProjectionVersion projectionVersion) {
        return new CatalogCacheKey(serverId, binding, projectionVersion);
    }

    public static CatalogCacheKey of(ServerId serverId, CatalogBinding binding) {
        return new CatalogCacheKey(serverId, binding);
    }

    public static CatalogCacheKey of(ServerId serverId, long catalogGeneration, ContentHash snapshotChecksum,
                                     ContentHash bindingManifestHash, CatalogProjectionVersion projectionVersion) {
        return new CatalogCacheKey(serverId, catalogGeneration, snapshotChecksum, bindingManifestHash, projectionVersion);
    }

    public static CatalogCacheKey parseCanonicalText(String value) {
        Objects.requireNonNull(value, "Catalog cache key text is required");
        String[] parts = value.split("\\|", -1);
        if (parts.length != 4 && parts.length != 5) {
            throw new IllegalArgumentException("Catalog cache key text must contain server ID, generation, snapshot checksum, and projection version");
        }
        long generation;
        try {
            generation = Long.parseLong(parts[1]);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Catalog cache generation is not numeric", exception);
        }
        ContentHash bindingManifestHash = parts.length == 5 ? ContentHash.parseCanonicalText(parts[3]) : null;
        CatalogProjectionVersion projectionVersion = CatalogProjectionVersion.parseCanonicalText(parts[parts.length - 1]);
        CatalogCacheKey key = new CatalogCacheKey(ServerId.parseCanonicalText(parts[0]), generation,
            ContentHash.parseCanonicalText(parts[2]), projectionVersion, bindingManifestHash);
        if (!key.canonicalText().equals(value)) {
            throw new IllegalArgumentException("Catalog cache key text is not canonical");
        }
        return key;
    }

    public String canonicalText() {
        String base = serverId.canonicalText() + "|" + catalogGeneration + "|" + snapshotChecksum.canonicalText();
        return bindingManifestHash == null
            ? base + "|" + projectionVersion.canonicalText()
            : base + "|" + bindingManifestHash.canonicalText() + "|" + projectionVersion.canonicalText();
    }

    public CatalogBinding catalogBinding() {
        return bindingManifestHash == null ? null : new CatalogBinding(catalogGeneration, snapshotChecksum, bindingManifestHash);
    }

    public CatalogBinding binding() {
        return catalogBinding();
    }

    public boolean hasCatalogBinding() {
        return bindingManifestHash != null;
    }

    public CatalogCacheKey withCatalogBinding(CatalogBinding binding) {
        CatalogBinding checked = Objects.requireNonNull(binding, "Catalog binding is required");
        if (checked.generation() != catalogGeneration || !checked.catalogChecksum().equals(snapshotChecksum)) {
            throw new IllegalArgumentException("Catalog binding does not match cache key");
        }
        return new CatalogCacheKey(serverId, catalogGeneration, snapshotChecksum, projectionVersion,
            checked.bindingManifestHash());
    }

    public CatalogCacheKey withBinding(CatalogBinding binding) {
        return withCatalogBinding(binding);
    }

    @Override
    public int compareTo(CatalogCacheKey other) {
        int serverOrder = serverId.compareTo(other.serverId);
        if (serverOrder != 0) {
            return serverOrder;
        }
        int generationOrder = Long.compare(catalogGeneration, other.catalogGeneration);
        if (generationOrder != 0) {
            return generationOrder;
        }
        int checksumOrder = snapshotChecksum.compareTo(other.snapshotChecksum);
        if (checksumOrder != 0) {
            return checksumOrder;
        }
        if (bindingManifestHash == null && other.bindingManifestHash != null) {
            return -1;
        }
        if (bindingManifestHash != null && other.bindingManifestHash == null) {
            return 1;
        }
        int bindingOrder = bindingManifestHash == null ? 0 : bindingManifestHash.compareTo(other.bindingManifestHash);
        return bindingOrder != 0 ? bindingOrder : projectionVersion.compareTo(other.projectionVersion);
    }

    @Override
    public String toString() {
        return canonicalText();
    }
}
