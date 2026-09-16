package restudio.resync.flow.cache;

import restudio.resync.contract.cache.CatalogProjectionVersion;
import restudio.resync.flow.catalog.CatalogCanonicalizer;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.IdentitySupport;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.ServerId;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public record CatalogCachePublication(
    Kind kind,
    CatalogCacheKey key,
    CatalogBinding catalogBinding,
    long revision,
    List<Entry> entries,
    CatalogAuthoringPublication authoringPublication,
    Map<String, Object> unknown
) {
    public static final int MAX_REQUIRED_CAPABILITIES = CatalogAuthoringPublication.MAX_REQUIRED_CAPABILITIES;
    private static final Set<String> KNOWN_FIELDS = Set.of("kind", "serverId", "catalogGeneration", "snapshotChecksum",
        "bindingManifestHash", "catalogBinding", "projectionVersion", "revision", "entries", "authoringPublication");

    public CatalogCachePublication {
        kind = Objects.requireNonNull(kind, "kind");
        key = Objects.requireNonNull(key, "key");
        if (catalogBinding != null) {
            if (catalogBinding.generation() != key.catalogGeneration()
                || !catalogBinding.catalogChecksum().equals(key.snapshotChecksum())) {
                throw new IllegalArgumentException("Catalog publication binding does not match its cache key");
            }
            if (key.bindingManifestHash() != null
                && !catalogBinding.bindingManifestHash().equals(key.bindingManifestHash())) {
                throw new IllegalArgumentException("Catalog publication binding manifest does not match its cache key");
            }
            if (key.bindingManifestHash() == null) {
                key = key.withCatalogBinding(catalogBinding);
            }
        } else if (key.catalogBinding() != null) {
            catalogBinding = key.catalogBinding();
        }
        if (key.projectionVersion().requiresCatalogBinding() && catalogBinding == null) {
            throw new IllegalArgumentException("Current catalog publications require an exact catalog binding");
        }
        if (authoringPublication != null) {
            if (catalogBinding == null || !catalogBinding.equals(authoringPublication.binding())) {
                throw new IllegalArgumentException("Catalog authoring publication binding does not match its cache publication");
            }
            if (!key.projectionVersion().equals(authoringPublication.projectionVersion())) {
                throw new IllegalArgumentException("Catalog authoring publication projection does not match its cache publication");
            }
        }
        if (revision < 0) {
            throw new IllegalArgumentException("Catalog publication revision cannot be negative");
        }
        entries = immutableEntries(entries, revision);
        unknown = IdentitySupport.unknown(unknown, "catalog publication unknown data");
        rejectUnknownCollisions(unknown, KNOWN_FIELDS);
    }

    public CatalogCachePublication(Kind kind, CatalogCacheKey key, long revision, Collection<Entry> entries) {
        this(kind, key, key.catalogBinding(), revision, entries == null ? List.of() : List.copyOf(entries), null, Map.of());
    }

    public CatalogCachePublication(Kind kind, CatalogCacheKey key, long revision, Collection<Entry> entries,
                                   Map<String, ?> unknown) {
        this(kind, key, key.catalogBinding(), revision, entries == null ? List.of() : List.copyOf(entries), null,
            IdentitySupport.unknown(unknown, "catalog publication unknown data"));
    }

    public CatalogCachePublication(Kind kind, CatalogCacheKey key, CatalogBinding catalogBinding, long revision,
                                   Collection<Entry> entries) {
        this(kind, key, catalogBinding, revision, entries == null ? List.of() : List.copyOf(entries), null, Map.of());
    }

    public CatalogCachePublication(Kind kind, CatalogCacheKey key, CatalogBinding catalogBinding, long revision,
                                   Collection<Entry> entries, Map<String, ?> unknown) {
        this(kind, key, catalogBinding, revision, entries == null ? List.of() : List.copyOf(entries), null,
            IdentitySupport.unknown(unknown, "catalog publication unknown data"));
    }

    public static CatalogCachePublication full(CatalogCacheSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        return new CatalogCachePublication(Kind.FULL, snapshot.key(), snapshot.key().catalogBinding(), snapshot.revision(), snapshot.entries().values().stream()
            .map(CatalogCachePublication::fromEntry).toList());
    }

    public static CatalogCachePublication full(CatalogCacheSnapshot snapshot, CatalogBinding binding) {
        Objects.requireNonNull(snapshot, "snapshot");
        return new CatalogCachePublication(Kind.FULL, snapshot.key(), binding, snapshot.revision(), snapshot.entries().values().stream()
            .map(CatalogCachePublication::fromEntry).toList());
    }

    public static CatalogCachePublication from(CatalogCacheSnapshot snapshot) {
        return full(snapshot);
    }

    public static CatalogCachePublication delta(CatalogCacheSnapshot previous, CatalogCacheSnapshot current) {
        Objects.requireNonNull(previous, "previous");
        Objects.requireNonNull(current, "current");
        if (!previous.key().equals(current.key())) {
            throw new IllegalArgumentException("Catalog publication delta keys must match");
        }
        if (current.revision() <= previous.revision()) {
            throw new IllegalArgumentException("Catalog publication delta revision must advance");
        }
        Map<ContractRef<NodeId>, CatalogCacheEntry> previousEntries = previous.entries();
        Map<ContractRef<NodeId>, CatalogCacheEntry> currentEntries = current.entries();
        Map<ContractRef<NodeId>, Entry> changes = new LinkedHashMap<>();
        for (Map.Entry<ContractRef<NodeId>, CatalogCacheEntry> entry : currentEntries.entrySet()) {
            CatalogCacheEntry previousEntry = previousEntries.get(entry.getKey());
            if (!entry.getValue().equals(previousEntry)) {
                changes.put(entry.getKey(), fromEntry(entry.getValue()));
            }
        }
        for (ContractRef<NodeId> key : previousEntries.keySet()) {
            if (!currentEntries.containsKey(key)) {
                changes.put(key, Entry.tombstone(key, current.revision()));
            }
        }
        return new CatalogCachePublication(Kind.DELTA, current.key(), current.key().catalogBinding(), current.revision(), changes.values());
    }

    public static CatalogCachePublication delta(CatalogCacheSnapshot previous, CatalogCacheSnapshot current,
                                                CatalogBinding binding) {
        CatalogCachePublication publication = delta(previous, current);
        return new CatalogCachePublication(publication.kind(), publication.key(), binding, publication.revision(),
            publication.entries(), publication.unknown());
    }

    public static CatalogCachePublication fromSnapshot(CatalogCacheSnapshot snapshot) {
        return full(snapshot);
    }

    public static Entry fromEntry(CatalogCacheEntry entry) {
        Objects.requireNonNull(entry, "entry");
        if (entry.tombstone()) {
            return Entry.tombstone(entry.definitionKey(), entry.revision(), entry.unknown());
        }
        CatalogCacheDefinition definition = Objects.requireNonNull(entry.definition(), "entry definition");
        boolean opaque = definition.opaque() != null;
        CatalogCacheOpaque data = opaque
            ? canonicalData(definition.opaque())
            : canonicalDescriptorData(definition);
        return Entry.present(entry.definitionKey(), entry.revision(), definition.state(), definition.requiredCapabilities(), opaque, data,
            entry.unknown());
    }

    public long catalogGeneration() {
        return key.catalogGeneration();
    }

    public ServerId serverId() {
        return key.serverId();
    }

    public ContentHash snapshotChecksum() {
        return key.snapshotChecksum();
    }

    public CatalogProjectionVersion projectionVersion() {
        return key.projectionVersion();
    }

    public CatalogBinding binding() {
        return catalogBinding;
    }

    public ContentHash bindingManifestHash() {
        return catalogBinding == null ? null : catalogBinding.bindingManifestHash();
    }

    public boolean hasAuthoringPublication() {
        return authoringPublication != null;
    }

    public ContentHash authoringPublicationChecksum() {
        return authoringPublication == null ? null : CatalogCachePublicationCodec.authoringPublicationChecksum(authoringPublication);
    }

    public CatalogCachePublication withAuthoringPublication(CatalogAuthoringPublication value) {
        return new CatalogCachePublication(kind, key, catalogBinding, revision, entries, value, unknown);
    }

    public enum Kind {
        FULL,
        DELTA
    }

    public record Entry(
        ContractRef<NodeId> definitionKey,
        long revision,
        boolean tombstone,
        CatalogCacheState state,
        Set<ContractRef<CapabilityId>> requiredCapabilities,
        boolean opaque,
        CatalogCacheOpaque data,
        Map<String, Object> unknown
    ) {
        public Entry {
            definitionKey = Objects.requireNonNull(definitionKey, "definitionKey");
            if (revision < 0) {
                throw new IllegalArgumentException("Catalog publication entry revision cannot be negative");
            }
            requiredCapabilities = immutableCapabilities(requiredCapabilities);
            unknown = IdentitySupport.unknown(unknown, "catalog publication entry unknown data");
            if (tombstone) {
                if (state != null || !requiredCapabilities.isEmpty() || opaque || data != null) {
                    throw new IllegalArgumentException("Tombstone publications cannot carry definition data");
                }
            } else {
                state = Objects.requireNonNull(state, "state");
                data = canonicalData(Objects.requireNonNull(data, "data"));
                if (opaque && state != CatalogCacheState.UNAVAILABLE) {
                    throw new IllegalArgumentException("Opaque publications must be unavailable");
                }
            }
            rejectUnknownCollisions(unknown, Set.of("definitionKey", "revision", "tombstone", "state",
                "requiredCapabilities", "opaque", "data"));
        }

        public Entry(ContractRef<NodeId> definitionKey, long revision, boolean tombstone,
                     CatalogCacheState state, Set<ContractRef<CapabilityId>> requiredCapabilities,
                     boolean opaque, CatalogCacheOpaque data) {
            this(definitionKey, revision, tombstone, state, requiredCapabilities, opaque, data, Map.of());
        }

        public static Entry present(ContractRef<NodeId> definitionKey, long revision, CatalogCacheState state,
                                    Set<ContractRef<CapabilityId>> requiredCapabilities, boolean opaque,
                                    CatalogCacheOpaque data) {
            return new Entry(definitionKey, revision, false, state, requiredCapabilities, opaque, data, Map.of());
        }

        public static Entry present(ContractRef<NodeId> definitionKey, long revision, CatalogCacheState state,
                                    Set<ContractRef<CapabilityId>> requiredCapabilities, boolean opaque,
                                    CatalogCacheOpaque data, Map<String, Object> unknown) {
            return new Entry(definitionKey, revision, false, state, requiredCapabilities, opaque, data, unknown);
        }

        public static Entry tombstone(ContractRef<NodeId> definitionKey, long revision) {
            return new Entry(definitionKey, revision, true, null, Set.of(), false, null, Map.of());
        }

        public static Entry tombstone(ContractRef<NodeId> definitionKey, long revision, Map<String, Object> unknown) {
            return new Entry(definitionKey, revision, true, null, Set.of(), false, null, unknown);
        }

        public boolean present() {
            return !tombstone;
        }
    }

    private static List<Entry> immutableEntries(Collection<Entry> values, long revision) {
        Objects.requireNonNull(values, "entries");
        if (values instanceof CatalogCachePublicationCodec.DecodedEntries decoded) {
            return decoded.validated(revision);
        }
        List<Entry> entries = new ArrayList<>(values.size());
        List<String> canonicalKeys = new ArrayList<>(values.size());
        Set<String> unique = new HashSet<>(values.size());
        String previousKey = null;
        boolean ordered = true;
        for (Entry entry : values) {
            Objects.requireNonNull(entry, "entries contains null");
            if (entry.revision() > revision) {
                throw new IllegalArgumentException("Catalog publication entry revision exceeds publication revision");
            }
            String canonicalKey = entry.definitionKey().canonicalText();
            if (!unique.add(canonicalKey)) {
                throw new IllegalArgumentException("Duplicate catalog publication entry: " + canonicalKey);
            }
            ordered &= previousKey == null || previousKey.compareTo(canonicalKey) < 0;
            previousKey = canonicalKey;
            entries.add(entry);
            canonicalKeys.add(canonicalKey);
        }
        if (ordered) {
            return List.copyOf(entries);
        }
        List<CanonicalEntry> sorted = new ArrayList<>(entries.size());
        for (int index = 0; index < entries.size(); index++) {
            sorted.add(new CanonicalEntry(canonicalKeys.get(index), entries.get(index)));
        }
        sorted.sort(Comparator.comparing(CanonicalEntry::key));
        return sorted.stream().map(CanonicalEntry::entry).toList();
    }

    private static CatalogCacheOpaque canonicalData(CatalogCacheOpaque data) {
        return Objects.requireNonNull(data, "data");
    }

    private static Set<ContractRef<CapabilityId>> immutableCapabilities(Set<ContractRef<CapabilityId>> values) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        if (values.size() > MAX_REQUIRED_CAPABILITIES) {
            throw new IllegalArgumentException("Catalog publication entry requires too many capabilities");
        }
        if (values instanceof CatalogCachePublicationCodec.DecodedCapabilities decoded) {
            return decoded;
        }
        List<ContractRef<CapabilityId>> capabilities = new ArrayList<>(values.size());
        List<String> canonicalKeys = new ArrayList<>(values.size());
        Set<String> unique = new HashSet<>(values.size());
        String previousKey = null;
        boolean ordered = true;
        for (ContractRef<CapabilityId> capability : values) {
            ContractRef<CapabilityId> value = Objects.requireNonNull(capability,
                "Catalog publication required capability is required");
            String canonicalKey = value.canonicalText();
            if (!unique.add(canonicalKey)) {
                throw new IllegalArgumentException("Duplicate catalog publication required capability: " + canonicalKey);
            }
            ordered &= previousKey == null || previousKey.compareTo(canonicalKey) < 0;
            previousKey = canonicalKey;
            capabilities.add(value);
            canonicalKeys.add(canonicalKey);
        }
        if (ordered) {
            return Collections.unmodifiableSet(new LinkedHashSet<>(capabilities));
        }
        List<CanonicalCapability> sorted = new ArrayList<>(capabilities.size());
        for (int index = 0; index < capabilities.size(); index++) {
            sorted.add(new CanonicalCapability(canonicalKeys.get(index), capabilities.get(index)));
        }
        sorted.sort(Comparator.comparing(CanonicalCapability::key));
        LinkedHashSet<ContractRef<CapabilityId>> result = new LinkedHashSet<>(sorted.size());
        sorted.forEach(capability -> result.add(capability.value()));
        return Collections.unmodifiableSet(result);
    }

    private static CatalogCacheOpaque canonicalDescriptorData(CatalogCacheDefinition definition) {
        CatalogCacheOpaque descriptorContent = definition.canonicalDescriptor();
        if (descriptorContent != null) {
            return canonicalData(descriptorContent);
        }
        return canonicalData(CatalogCacheOpaque.of(CatalogCanonicalizer.canonicalNodeContent(definition.descriptor()).getBytes(StandardCharsets.UTF_8)));
    }

    private static void rejectUnknownCollisions(Map<String, Object> unknown, Set<String> known) {
        for (String field : unknown.keySet()) {
            if (known.contains(field)) {
                throw new IllegalArgumentException("Unknown catalog publication field collides with known field: " + field);
            }
        }
    }

    private record CanonicalEntry(String key, Entry entry) {
    }

    private record CanonicalCapability(String key, ContractRef<CapabilityId> value) {
    }
}
