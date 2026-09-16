package restudio.resync.flow.function;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.NodeInstanceId;

public record TypedFunctionCapabilitySet(
    CatalogBinding catalogBinding,
    Set<ContractRef<CapabilityId>> grantedCapabilities,
    List<TypedFunctionNodeCapability> nodes
) {
    public TypedFunctionCapabilitySet {
        catalogBinding = Objects.requireNonNull(catalogBinding, "Typed Function Catalog Binding Is Required");
        grantedCapabilities = grantedCapabilities == null ? Set.of() : Set.copyOf(grantedCapabilities);
        Objects.requireNonNull(nodes, "Typed Function Node Capabilities Are Required");
        ArrayList<TypedFunctionNodeCapability> copy = new ArrayList<>(nodes.size());
        nodes.forEach(value -> copy.add(Objects.requireNonNull(value, "Typed Function Node Capability Cannot Be Null")));
        if (copy.stream().map(TypedFunctionNodeCapability::nodeId).distinct().count() != copy.size()) {
            throw new IllegalArgumentException("Typed Function Capability Set Contains Duplicate Node IDs");
        }
        nodes = List.copyOf(copy);
    }

    public TypedFunctionCapabilitySet(CatalogBinding catalogBinding, List<TypedFunctionNodeCapability> nodes) {
        this(catalogBinding, nodes.stream().map(TypedFunctionNodeCapability::capability).collect(Collectors.toUnmodifiableSet()), nodes);
    }

    public Map<NodeInstanceId, TypedFunctionNodeCapability> byNode() {
        LinkedHashMap<NodeInstanceId, TypedFunctionNodeCapability> values = new LinkedHashMap<>();
        nodes.stream().sorted(Comparator.comparing(TypedFunctionNodeCapability::nodeId))
            .forEach(value -> values.put(value.nodeId(), value));
        return Map.copyOf(values);
    }

    public ContentHash fingerprint() {
        return ContentHash.of(CanonicalJson.sha256("typed-function-capabilities", canonicalValue()));
    }

    public Map<String, Object> canonicalValue() {
        return Map.of(
            "catalogBinding", Map.of(
                "bindingManifestHash", catalogBinding.bindingManifestHash().canonicalText(),
                "catalogChecksum", catalogBinding.catalogChecksum().canonicalText(),
                "generation", catalogBinding.generation()),
            "grantedCapabilities", grantedCapabilities.stream().sorted().map(reference -> Map.of(
                "ownerId", reference.owner().canonicalText(), "localId", reference.id().canonicalText())).toList(),
            "nodes", nodes.stream().sorted(Comparator.comparing(TypedFunctionNodeCapability::nodeId))
                .map(TypedFunctionNodeCapability::canonicalValue).toList());
    }
}
