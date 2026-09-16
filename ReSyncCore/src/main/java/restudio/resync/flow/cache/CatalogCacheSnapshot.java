package restudio.resync.flow.cache;

import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.NodeId;

import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class CatalogCacheSnapshot {
    private final CatalogCacheKey key;
    private final long revision;
    private final Map<ContractRef<NodeId>, CatalogCacheEntry> entries;

    public CatalogCacheSnapshot(CatalogCacheKey key, long revision, Collection<CatalogCacheEntry> entries) {
        this.key = Objects.requireNonNull(key, "key");
        if (revision < 0) {
            throw new IllegalArgumentException("Catalog cache revision cannot be negative");
        }
        this.revision = revision;
        this.entries = immutableEntries(entries, revision);
    }

    public static CatalogCacheSnapshot empty(CatalogCacheKey key) {
        return new CatalogCacheSnapshot(key, 0, List.of());
    }

    public CatalogCacheKey key() {
        return key;
    }

    public long revision() {
        return revision;
    }

    public Map<ContractRef<NodeId>, CatalogCacheEntry> entries() {
        return entries;
    }

    public Optional<CatalogCacheEntry> entry(ContractRef<NodeId> key) {
        return Optional.ofNullable(entries.get(Objects.requireNonNull(key, "key")));
    }

    public List<CatalogCacheDefinition> definitions() {
        return entries.values().stream()
            .filter(entry -> !entry.tombstone())
            .map(CatalogCacheEntry::definition)
            .toList();
    }

    public Optional<CatalogCacheDefinition> selectable(ContractRef<NodeId> key) {
        return entry(key)
            .filter(entry -> !entry.tombstone())
            .map(CatalogCacheEntry::definition)
            .filter(definition -> definition.state() != CatalogCacheState.UNAVAILABLE);
    }

    private static Map<ContractRef<NodeId>, CatalogCacheEntry> immutableEntries(Collection<CatalogCacheEntry> values, long revision) {
        Objects.requireNonNull(values, "entries");
        Map<ContractRef<NodeId>, CatalogCacheEntry> ordered = new LinkedHashMap<>();
        for (CatalogCacheEntry entry : values) {
            Objects.requireNonNull(entry, "entries contains null");
            if (entry.revision() > revision) {
                throw new IllegalArgumentException("Catalog cache entry revision exceeds snapshot revision");
            }
            ContractRef<NodeId> entryKey = Objects.requireNonNull(entry.definitionKey(), "entry key");
            if (ordered.putIfAbsent(entryKey, entry) != null) {
                throw new IllegalArgumentException("Duplicate catalog cache entry key: " + entryKey.canonicalText());
            }
        }
        return Collections.unmodifiableMap(ordered.entrySet().stream()
            .sorted(Map.Entry.comparingByKey(Comparator.comparing(ContractRef::canonicalText)))
            .collect(LinkedHashMap::new, (map, entry) -> map.put(entry.getKey(), entry.getValue()), Map::putAll));
    }
}
