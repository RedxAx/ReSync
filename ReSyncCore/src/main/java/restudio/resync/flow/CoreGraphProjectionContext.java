package restudio.resync.flow;

import restudio.resync.flow.catalog.CatalogNodeDescriptor;
import restudio.resync.flow.catalog.CatalogOwned;
import restudio.resync.flow.catalog.CatalogSnapshot;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.PinId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.type.TypeDescriptor;
import restudio.resync.flow.type.TypeReference;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class CoreGraphProjectionContext {
    private final ServerResourceLocator resource;
    private final CatalogSnapshot catalog;
    private final CatalogBinding binding;
    private final long resourceRevision;
    private final UUID identityNamespace;
    private final Map<ContractRef<NodeId>, ResolvedNode> nodes;
    private final Map<TypeReference, TypeDescriptor> types;

    public CoreGraphProjectionContext(ServerResourceLocator resource, CatalogSnapshot catalog, CatalogBinding binding,
                                      long resourceRevision, UUID identityNamespace) {
        this.resource = Objects.requireNonNull(resource, "resource");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.binding = Objects.requireNonNull(binding, "binding");
        if (resourceRevision < 0) {
            throw new IllegalArgumentException("Resource revision cannot be negative");
        }
        this.resourceRevision = resourceRevision;
        this.identityNamespace = Objects.requireNonNull(identityNamespace, "identityNamespace");
        requireBinding(catalog, binding);
        this.nodes = indexNodes(catalog);
        this.types = indexTypes(catalog);
    }

    public ServerResourceLocator resource() {
        return resource;
    }

    public CatalogSnapshot catalog() {
        return catalog;
    }

    public CatalogBinding binding() {
        return binding;
    }

    public CatalogVersion catalogVersion() {
        return catalog.contractVersion();
    }

    public long resourceRevision() {
        return resourceRevision;
    }

    public UUID identityNamespace() {
        return identityNamespace;
    }

    public ResolvedNode requireNode(ContractRef<NodeId> reference) {
        ResolvedNode node = nodes.get(Objects.requireNonNull(reference, "reference"));
        if (node == null) {
            throw new IllegalArgumentException("Catalog node is unavailable: " + reference.canonicalText());
        }
        return node;
    }

    public TypeDescriptor requireType(TypeReference reference) {
        TypeDescriptor descriptor = types.get(Objects.requireNonNull(reference, "reference"));
        if (descriptor == null) {
            throw new IllegalArgumentException("Catalog type is unavailable: " + reference.canonicalKey());
        }
        return descriptor;
    }

    private static void requireBinding(CatalogSnapshot catalog, CatalogBinding binding) {
        if (binding.generation() != catalog.generation()
            || !binding.catalogChecksum().equals(catalog.contentChecksum())
            || !binding.bindingManifestHash().equals(catalog.bindingManifestHash())) {
            throw new IllegalArgumentException("Catalog binding does not match the captured catalog snapshot");
        }
    }

    private static Map<ContractRef<NodeId>, ResolvedNode> indexNodes(CatalogSnapshot catalog) {
        Map<ContractRef<NodeId>, ResolvedNode> result = new LinkedHashMap<>();
        for (CatalogOwned<CatalogNodeDescriptor> owned : catalog.definitions()) {
            ContractRef<NodeId> reference = ContractRef.of(owned.key().owner(), owned.descriptor().id());
            if (!reference.equals(owned.key())) {
                throw new IllegalArgumentException("Catalog node key does not match its descriptor: " + owned.key().canonicalText());
            }
            ResolvedNode node = new ResolvedNode(reference, owned.descriptor());
            if (result.putIfAbsent(reference, node) != null) {
                throw new IllegalArgumentException("Duplicate catalog node identity: " + reference.canonicalText());
            }
        }
        return Map.copyOf(result);
    }

    private static Map<TypeReference, TypeDescriptor> indexTypes(CatalogSnapshot catalog) {
        Map<TypeReference, TypeDescriptor> result = new LinkedHashMap<>();
        for (CatalogOwned<TypeDescriptor> owned : catalog.types()) {
            TypeDescriptor descriptor = owned.descriptor();
            ContractRef<CapabilityId> expected = ContractRef.of(OwnerId.of(descriptor.id().ownerId()),
                CapabilityId.of(descriptor.id().localId()));
            if (!expected.equals(owned.key())) {
                throw new IllegalArgumentException("Catalog type key does not match its descriptor: " + owned.key().canonicalText());
            }
            if (result.putIfAbsent(descriptor.id(), descriptor) != null) {
                throw new IllegalArgumentException("Duplicate catalog type identity: " + descriptor.id().canonicalKey());
            }
        }
        return Map.copyOf(result);
    }

    public static final class ResolvedNode {
        private final ContractRef<NodeId> reference;
        private final CatalogNodeDescriptor descriptor;
        private final Map<PinId, CatalogNodeDescriptor.Pin> pins;

        private ResolvedNode(ContractRef<NodeId> reference, CatalogNodeDescriptor descriptor) {
            this.reference = reference;
            this.descriptor = descriptor;
            Map<PinId, CatalogNodeDescriptor.Pin> indexed = new LinkedHashMap<>();
            for (CatalogNodeDescriptor.Pin pin : descriptor.pins()) {
                if (indexed.putIfAbsent(pin.id(), pin) != null) {
                    throw new IllegalArgumentException("Duplicate catalog pin identity: " + reference.canonicalText() + "/" + pin.id().canonicalText());
                }
            }
            this.pins = Map.copyOf(indexed);
        }

        public ContractRef<NodeId> reference() {
            return reference;
        }

        public CatalogNodeDescriptor descriptor() {
            return descriptor;
        }

        public CatalogNodeDescriptor.Pin requirePin(PinId pinId) {
            CatalogNodeDescriptor.Pin pin = pins.get(Objects.requireNonNull(pinId, "pinId"));
            if (pin == null) {
                throw new IllegalArgumentException("Catalog pin is unavailable: " + reference.canonicalText() + "/" + pinId.canonicalText());
            }
            return pin;
        }

        public Optional<CatalogNodeDescriptor.Pin> pin(PinId pinId) {
            return Optional.ofNullable(pins.get(Objects.requireNonNull(pinId, "pinId")));
        }
    }
}
