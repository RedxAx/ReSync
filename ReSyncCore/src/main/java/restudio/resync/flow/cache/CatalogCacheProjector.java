package restudio.resync.flow.cache;

import restudio.resync.contract.cache.CatalogProjectionVersion;
import restudio.resync.flow.catalog.CatalogCanonicalizer;
import restudio.resync.flow.catalog.CatalogContribution;
import restudio.resync.flow.catalog.CatalogNodeDescriptor;
import restudio.resync.flow.catalog.CatalogOwned;
import restudio.resync.flow.catalog.CatalogSnapshot;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.ServerId;

import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class CatalogCacheProjector {
    public static CatalogAuthoringPublication projectAuthoring(CatalogSnapshot snapshot,
                                                                Set<ContractRef<CapabilityId>> supportedCapabilities) {
        return CatalogAuthoringPublication.project(snapshot, supportedCapabilities);
    }

    public static CatalogAuthoringPublication projectAuthoring(CatalogSnapshot snapshot,
                                                                Set<ContractRef<CapabilityId>> supportedCapabilities,
                                                                Set<CatalogAuthoringPublication.Section> acknowledgedSections) {
        return CatalogAuthoringPublication.project(snapshot, supportedCapabilities, acknowledgedSections);
    }

    public static CatalogAuthoringPublication projectAuthoring(CatalogSnapshot snapshot,
                                                                Set<ContractRef<CapabilityId>> supportedCapabilities,
                                                                CatalogProjectionVersion projectionVersion) {
        return CatalogAuthoringPublication.project(snapshot, supportedCapabilities, projectionVersion);
    }

    public static CatalogAuthoringPublication projectAuthoring(CatalogSnapshot snapshot,
                                                                Set<ContractRef<CapabilityId>> supportedCapabilities,
                                                                Set<CatalogAuthoringPublication.Section> acknowledgedSections,
                                                                CatalogProjectionVersion projectionVersion) {
        return CatalogAuthoringPublication.project(snapshot, supportedCapabilities, acknowledgedSections,
            projectionVersion);
    }

    public static CatalogAuthoringPublication projectAuthoring(CatalogBinding binding, CatalogSnapshot snapshot,
                                                                Set<ContractRef<CapabilityId>> supportedCapabilities) {
        return CatalogAuthoringPublication.project(binding, snapshot, supportedCapabilities);
    }

    public static CatalogAuthoringPublication projectAuthoring(CatalogBinding binding, CatalogSnapshot snapshot,
                                                                Set<ContractRef<CapabilityId>> supportedCapabilities,
                                                                Set<CatalogAuthoringPublication.Section> acknowledgedSections) {
        return CatalogAuthoringPublication.project(binding, snapshot, supportedCapabilities, acknowledgedSections);
    }

    public static CatalogAuthoringPublication projectAuthoring(CatalogBinding binding, CatalogSnapshot snapshot,
                                                                Set<ContractRef<CapabilityId>> supportedCapabilities,
                                                                CatalogProjectionVersion projectionVersion) {
        return CatalogAuthoringPublication.project(binding, snapshot, supportedCapabilities, projectionVersion);
    }

    public static CatalogAuthoringPublication projectAuthoring(CatalogBinding binding, CatalogSnapshot snapshot,
                                                                Set<ContractRef<CapabilityId>> supportedCapabilities,
                                                                Set<CatalogAuthoringPublication.Section> acknowledgedSections,
                                                                CatalogProjectionVersion projectionVersion) {
        return CatalogAuthoringPublication.project(binding, snapshot, supportedCapabilities, acknowledgedSections,
            projectionVersion);
    }

    public static CatalogCacheSnapshot project(ServerId serverId, long revision, CatalogSnapshot snapshot,
                                                Set<ContractRef<CapabilityId>> supportedCapabilities,
                                                CatalogProjectionVersion projectionVersion) {
        Objects.requireNonNull(serverId, "serverId");
        Objects.requireNonNull(snapshot, "snapshot");
        CatalogBinding binding = new CatalogBinding(snapshot.generation(), snapshot.contentChecksum(), snapshot.bindingManifestHash());
        CatalogCacheKey key = new CatalogCacheKey(serverId, binding,
            Objects.requireNonNull(projectionVersion, "projectionVersion"));
        return project(key, revision, snapshot, supportedCapabilities);
    }

    public static CatalogCacheSnapshot project(ServerId serverId, long revision, CatalogSnapshot snapshot,
                                               Set<ContractRef<CapabilityId>> supportedCapabilities) {
        return project(serverId, revision, snapshot, supportedCapabilities, CatalogProjectionVersion.current());
    }

    public static CatalogCacheSnapshot project(CatalogCacheKey key, long revision, CatalogSnapshot snapshot,
                                                Set<ContractRef<CapabilityId>> supportedCapabilities) {
        Objects.requireNonNull(key, "key");
        if (revision < 0) {
            throw new IllegalArgumentException("Catalog cache revision cannot be negative");
        }
        Objects.requireNonNull(snapshot, "snapshot");
        if (!key.snapshotChecksum().equals(snapshot.contentChecksum())) {
            throw new IllegalArgumentException("Catalog snapshot checksum does not match cache key");
        }
        if (key.catalogGeneration() != snapshot.generation()) {
            throw new IllegalArgumentException("Catalog snapshot generation does not match cache key");
        }
        CatalogBinding binding = new CatalogBinding(snapshot.generation(), snapshot.contentChecksum(), snapshot.bindingManifestHash());
        if (key.projectionVersion().requiresCatalogBinding() && !binding.equals(key.catalogBinding())) {
            throw new IllegalArgumentException("Catalog cache key does not carry the exact catalog binding");
        }
        Set<ContractRef<CapabilityId>> supported = capabilities(supportedCapabilities);
        List<CatalogCacheEntry> entries = snapshot.definitions().stream()
            .sorted(Comparator.comparing(value -> definitionKey(value).canonicalText()))
            .map(value -> {
                CatalogCacheDefinition projection = projectDefinition(value, contribution(snapshot, value), supported);
                return CatalogCacheEntry.present(projection.key(), revision, projection);
            })
            .toList();
        return new CatalogCacheSnapshot(key, revision, entries);
    }

    public static CatalogCacheDefinition projectDefinition(CatalogOwned<CatalogNodeDescriptor> definition,
                                                            Set<ContractRef<CapabilityId>> supportedCapabilities) {
        Objects.requireNonNull(definition, "definition");
        CatalogNodeDescriptor descriptor = Objects.requireNonNull(definition.descriptor(), "descriptor");
        ContractRef<NodeId> key = definitionKey(definition);
        Set<ContractRef<CapabilityId>> requiredCapabilities = Set.copyOf(descriptor.requiredCapabilities());
        Set<ContractRef<CapabilityId>> supported = capabilities(supportedCapabilities);
        CatalogCacheState state = descriptor.lifecycle() == CatalogNodeDescriptor.Lifecycle.MIGRATION_ONLY
            ? CatalogCacheState.UNAVAILABLE
            : supported.containsAll(requiredCapabilities) ? CatalogCacheState.ACTIVE : CatalogCacheState.READ_ONLY;
        return CatalogCacheDefinition.known(key, descriptor, requiredCapabilities, state);
    }

    public static CatalogCacheDefinition projectDefinition(CatalogOwned<CatalogNodeDescriptor> definition,
                                                            CatalogContribution contribution,
                                                            Set<ContractRef<CapabilityId>> supportedCapabilities) {
        Objects.requireNonNull(contribution, "contribution");
        CatalogCacheDefinition projection = projectDefinition(definition, supportedCapabilities);
        CatalogCacheOpaque canonicalDescriptor = CatalogCacheOpaque.of(
            CatalogCanonicalizer.canonicalNodeContent(definition.descriptor(), contribution)
                .getBytes(StandardCharsets.UTF_8));
        return CatalogCacheDefinition.known(projection.key(), projection.descriptor(), projection.requiredCapabilities(),
            projection.state(), canonicalDescriptor);
    }

    private static Set<ContractRef<CapabilityId>> capabilities(Set<ContractRef<CapabilityId>> capabilities) {
        return Set.copyOf(Objects.requireNonNull(capabilities, "supportedCapabilities"));
    }

    private static ContractRef<NodeId> definitionKey(CatalogOwned<CatalogNodeDescriptor> definition) {
        ContractRef<?> ownedKey = definition.key();
        ContractRef<NodeId> typedKey = definition.descriptor().reference(ownedKey.owner());
        if (!typedKey.equals(ownedKey)) {
            throw new IllegalArgumentException("Catalog definition key does not match its descriptor identity");
        }
        return typedKey;
    }

    private static CatalogContribution contribution(CatalogSnapshot snapshot, CatalogOwned<CatalogNodeDescriptor> definition) {
        return snapshot.contributions().stream()
            .filter(value -> value.ownerId().equals(definition.key().owner()))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Catalog definition owner is not present in the snapshot contributions"));
    }
}
