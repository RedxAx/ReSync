package restudio.resync.flow.cache;

import restudio.resync.flow.catalog.CatalogNodeDescriptor;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.NodeId;

import java.util.Objects;
import java.util.Set;

public record CatalogCacheDefinition(
    ContractRef<NodeId> key,
    CatalogNodeDescriptor descriptor,
    CatalogCacheOpaque opaque,
    Set<ContractRef<CapabilityId>> requiredCapabilities,
    CatalogCacheState state,
    CatalogCacheOpaque canonicalDescriptor
) {
    public CatalogCacheDefinition {
        key = Objects.requireNonNull(key, "key");
        requiredCapabilities = requiredCapabilities == null ? Set.of() : Set.copyOf(requiredCapabilities);
        state = Objects.requireNonNull(state, "state");
        if (descriptor == null && opaque == null) {
            throw new IllegalArgumentException("Catalog cache definitions require a descriptor or opaque data");
        }
        if (descriptor != null && !key.id().equals(descriptor.id())) {
            throw new IllegalArgumentException("Catalog cache definition key does not match its descriptor");
        }
        if (opaque != null) {
            if (state != CatalogCacheState.UNAVAILABLE) {
                throw new IllegalArgumentException("Opaque catalog cache data must be unavailable");
            }
            if (canonicalDescriptor != null) {
                throw new IllegalArgumentException("Opaque catalog cache definitions cannot carry typed descriptor content");
            }
        }
        if (canonicalDescriptor != null && descriptor == null) {
            throw new IllegalArgumentException("Canonical descriptor content requires a typed descriptor");
        }
    }

    public CatalogCacheDefinition(ContractRef<NodeId> key, CatalogNodeDescriptor descriptor, CatalogCacheOpaque opaque,
                                  Set<ContractRef<CapabilityId>> requiredCapabilities, CatalogCacheState state) {
        this(key, descriptor, opaque, requiredCapabilities, state, null);
    }

    public static CatalogCacheDefinition known(ContractRef<NodeId> key, CatalogNodeDescriptor descriptor,
                                                Set<ContractRef<CapabilityId>> requiredCapabilities, CatalogCacheState state) {
        return new CatalogCacheDefinition(key, Objects.requireNonNull(descriptor, "descriptor"), null, requiredCapabilities, state);
    }

    public static CatalogCacheDefinition known(ContractRef<NodeId> key, CatalogNodeDescriptor descriptor,
                                               Set<ContractRef<CapabilityId>> requiredCapabilities, CatalogCacheState state,
                                               CatalogCacheOpaque canonicalDescriptor) {
        return new CatalogCacheDefinition(key, Objects.requireNonNull(descriptor, "descriptor"), null, requiredCapabilities,
            state, Objects.requireNonNull(canonicalDescriptor, "canonicalDescriptor"));
    }

    public static CatalogCacheDefinition known(ContractRef<NodeId> key, CatalogNodeDescriptor descriptor, CatalogCacheState state) {
        Objects.requireNonNull(descriptor, "descriptor");
        return known(key, descriptor, descriptor.requiredCapabilities(), state);
    }

    public static CatalogCacheDefinition opaqueUnavailable(ContractRef<NodeId> key, CatalogCacheOpaque opaque,
                                                            Set<ContractRef<CapabilityId>> requiredCapabilities) {
        return new CatalogCacheDefinition(key, null, Objects.requireNonNull(opaque, "opaque"), requiredCapabilities, CatalogCacheState.UNAVAILABLE);
    }

    public static CatalogCacheDefinition opaqueUnavailable(ContractRef<NodeId> key, CatalogCacheOpaque opaque) {
        return opaqueUnavailable(key, opaque, Set.of());
    }

    public boolean selectable() {
        return state != CatalogCacheState.UNAVAILABLE;
    }

    public boolean editable() {
        return state == CatalogCacheState.ACTIVE;
    }
}
