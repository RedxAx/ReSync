package restudio.resync.flow.graph;

import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.type.TypeReference;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class CompiledExecutionPlan {
    private final UUID planId;
    private final ServerResourceLocator graph;
    private final long graphRevision;
    private final CatalogBinding catalogBinding;
    private final ContentHash graphHash;
    private final List<CompiledExecutionStep> steps;
    private final List<GraphConnection> connections;
    private final List<ConversionRoute> conversionRoutes;
    private final List<StructuralRoute> structuralRoutes;
    private final List<FunctionBinding> functionBindings;
    private final List<ProviderLease> providerLeases;
    private final ContentHash planHash;
    private final OpaqueData unknown;

    public CompiledExecutionPlan(UUID planId, ServerResourceLocator graph, long graphRevision, CatalogBinding catalogBinding,
                                 ContentHash graphHash, List<CompiledExecutionStep> steps, List<GraphConnection> connections,
                                 List<FunctionBinding> functionBindings, OpaqueData unknown) {
        this(planId, graph, graphRevision, catalogBinding, graphHash, steps, connections, List.of(), List.of(), functionBindings, List.of(), unknown);
    }

    public CompiledExecutionPlan(UUID planId, ServerResourceLocator graph, long graphRevision, CatalogBinding catalogBinding,
                                 ContentHash graphHash, List<CompiledExecutionStep> steps, List<GraphConnection> connections,
                                 List<ConversionRoute> conversionRoutes, List<StructuralRoute> structuralRoutes,
                                 List<FunctionBinding> functionBindings, List<ProviderLease> providerLeases, OpaqueData unknown) {
        this.planId = Objects.requireNonNull(planId, "planId");
        this.graph = Objects.requireNonNull(graph, "graph");
        if (graphRevision < 0) {
            throw new IllegalArgumentException("Graph revision cannot be negative");
        }
        this.graphRevision = graphRevision;
        this.catalogBinding = Objects.requireNonNull(catalogBinding, "catalogBinding");
        this.graphHash = Objects.requireNonNull(graphHash, "graphHash");
        this.steps = List.copyOf(steps != null ? steps : List.of());
        this.connections = List.copyOf(connections != null ? connections : List.of());
        this.conversionRoutes = List.copyOf(conversionRoutes != null ? conversionRoutes : List.of());
        this.structuralRoutes = List.copyOf(structuralRoutes != null ? structuralRoutes : List.of());
        this.functionBindings = List.copyOf(functionBindings != null ? functionBindings : List.of());
        this.providerLeases = List.copyOf(providerLeases != null ? providerLeases : List.of());
        this.unknown = unknown != null ? unknown : OpaqueData.empty();
        this.planHash = new ContentHash(CanonicalJson.sha256(CanonicalJson.PLAN_HASH_DOMAIN, canonicalValueWithoutHash()));
    }

    public UUID planId() {
        return planId;
    }

    public ServerResourceLocator graph() {
        return graph;
    }

    public long graphRevision() {
        return graphRevision;
    }

    public CatalogBinding catalogBinding() {
        return catalogBinding;
    }

    public ContentHash graphHash() {
        return graphHash;
    }

    public List<CompiledExecutionStep> steps() {
        return steps;
    }

    public List<GraphConnection> connections() {
        return connections;
    }

    public List<ConversionRoute> conversionRoutes() {
        return conversionRoutes;
    }

    public List<TypeReference> conversions() {
        return conversionRoutes.stream()
            .flatMap(route -> route.conversionIds().stream())
            .distinct()
            .sorted(Comparator.comparing(TypeReference::canonicalKey))
            .toList();
    }

    public List<StructuralRoute> structuralRoutes() {
        return structuralRoutes;
    }

    public List<FunctionBinding> functionBindings() {
        return functionBindings;
    }

    public List<ProviderLease> providerLeases() {
        return providerLeases;
    }

    public ContentHash planHash() {
        return planHash;
    }

    public ContentHash checksum() {
        return planHash;
    }

    public String canonicalJson() {
        return CanonicalJson.canonicalize(canonicalValue());
    }

    public byte[] canonicalBytes() {
        return CanonicalJson.canonicalBytes(canonicalValue());
    }

    public OpaqueData unknown() {
        return unknown;
    }

    Map<String, Object> canonicalValue() {
        LinkedHashMap<String, Object> values = new LinkedHashMap<>(canonicalValueWithoutHash());
        values.put("planHash", planHash.canonicalText());
        return values;
    }

    private Map<String, Object> canonicalValueWithoutHash() {
        if (unknown.contains("planHash")) {
            throw new IllegalArgumentException("Unknown data collides with known field: planHash");
        }
        LinkedHashMap<String, Object> values = new LinkedHashMap<>();
        values.put("kind", "execution-plan");
        values.put("planId", planId.toString());
        values.put("graph", Map.of("serverId", graph.serverId().canonicalText(),
            "type", Map.of("ownerId", graph.type().owner().canonicalText(), "localId", graph.type().id().canonicalText()),
            "id", graph.id()));
        values.put("graphRevision", graphRevision);
        values.put("catalogBinding", Map.of("generation", catalogBinding.generation(),
            "catalogChecksum", catalogBinding.catalogChecksum().canonicalText(),
            "bindingManifestHash", catalogBinding.bindingManifestHash().canonicalText()));
        values.put("graphHash", graphHash.canonicalText());
        values.put("steps", steps.stream().sorted(Comparator.comparing(CompiledExecutionStep::stepId)).map(CompiledExecutionStep::canonicalValue).toList());
        values.put("connections", connections.stream().sorted(Comparator.comparing(GraphConnection::connectionId)).map(GraphConnection::canonicalValue).toList());
        values.put("conversions", conversions().stream().map(TypeReference::canonicalValue).toList());
        values.put("conversionRoutes", conversionRoutes.stream().sorted(Comparator.comparing(route -> route.connectionId().canonicalText())).map(ConversionRoute::canonicalValue).toList());
        values.put("structuralRoutes", structuralRoutes.stream().sorted(Comparator.comparing(StructuralRoute::routeId)).map(StructuralRoute::canonicalValue).toList());
        values.put("functionBindings", functionBindings.stream().sorted(Comparator.comparing(binding -> binding.function().canonicalText())).map(FunctionBinding::canonicalValue).toList());
        values.put("providerLeases", providerLeases.stream().sorted(Comparator.comparing(ProviderLease::leaseId)).map(ProviderLease::canonicalValue).toList());
        return OpaqueData.mergeKnownFields(unknown, values);
    }
}
