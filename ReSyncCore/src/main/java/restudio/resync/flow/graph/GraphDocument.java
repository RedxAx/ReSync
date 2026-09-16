package restudio.resync.flow.graph;

import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ConnectionId;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.NodeInstanceId;
import restudio.resync.flow.identity.ServerResourceLocator;

import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class GraphDocument {
    public static final CatalogVersion CURRENT_SCHEMA_VERSION = new CatalogVersion(1, 0);

    private final CatalogVersion schemaVersion;
    private final ServerResourceLocator resource;
    private final long revision;
    private final CatalogBinding catalogBinding;
    private final Set<ContractRef<CapabilityId>> requiredCapabilities;
    private final List<GraphNode> nodes;
    private final List<GraphConnection> connections;
    private final List<GraphPassthrough> passthroughs;
    private final List<GraphVariable> variables;
    private final List<FunctionBinding> functions;
    private final OpaqueData unknown;

    public GraphDocument(CatalogVersion schemaVersion, ServerResourceLocator resource, long revision, CatalogBinding catalogBinding,
                         Set<ContractRef<CapabilityId>> requiredCapabilities, List<GraphNode> nodes, List<GraphConnection> connections,
                         List<FunctionBinding> functions, OpaqueData unknown) {
        this(schemaVersion, resource, revision, catalogBinding, requiredCapabilities, nodes, connections, List.of(), List.of(), functions, unknown);
    }

    public GraphDocument(CatalogVersion schemaVersion, ServerResourceLocator resource, long revision, CatalogBinding catalogBinding,
                         Set<ContractRef<CapabilityId>> requiredCapabilities, List<GraphNode> nodes, List<GraphConnection> connections,
                         List<GraphVariable> variables, List<FunctionBinding> functions, OpaqueData unknown) {
        this(schemaVersion, resource, revision, catalogBinding, requiredCapabilities, nodes, connections, List.of(), variables, functions, unknown);
    }

    public GraphDocument(CatalogVersion schemaVersion, ServerResourceLocator resource, long revision, CatalogBinding catalogBinding,
                         Set<ContractRef<CapabilityId>> requiredCapabilities, List<GraphNode> nodes, List<GraphConnection> connections,
                         List<GraphPassthrough> passthroughs, List<GraphVariable> variables, List<FunctionBinding> functions,
                         OpaqueData unknown) {
        this.schemaVersion = Objects.requireNonNull(schemaVersion, "schemaVersion");
        this.resource = Objects.requireNonNull(resource, "resource");
        if (revision < 0) {
            throw new IllegalArgumentException("Graph revision cannot be negative");
        }
        this.revision = revision;
        this.catalogBinding = Objects.requireNonNull(catalogBinding, "catalogBinding");
        this.requiredCapabilities = immutableReferences(requiredCapabilities);
        this.nodes = List.copyOf(nodes != null ? nodes : List.of());
        this.connections = List.copyOf(connections != null ? connections : List.of());
        this.passthroughs = List.copyOf(passthroughs != null ? passthroughs : List.of());
        this.variables = List.copyOf(variables != null ? variables : List.of());
        this.functions = List.copyOf(functions != null ? functions : List.of());
        validateIds(this.nodes, this.connections, this.passthroughs, this.variables);
        this.unknown = unknown != null ? unknown : OpaqueData.empty();
        this.unknown.rejectKnownFields("schemaVersion", "resource", "revision", "catalogBinding", "requiredCapabilities", "nodes", "connections", "passthroughs", "variables", "functions");
    }

    public GraphDocument(ServerResourceLocator resource, long revision, CatalogBinding catalogBinding, List<GraphNode> nodes, List<GraphConnection> connections) {
        this(CURRENT_SCHEMA_VERSION, resource, revision, catalogBinding, Set.of(), nodes, connections, List.of(), OpaqueData.empty());
    }

    public CatalogVersion schemaVersion() {
        return schemaVersion;
    }

    public ServerResourceLocator resource() {
        return resource;
    }

    public long revision() {
        return revision;
    }

    public CatalogBinding catalogBinding() {
        return catalogBinding;
    }

    public Set<ContractRef<CapabilityId>> requiredCapabilities() {
        return requiredCapabilities;
    }

    public List<GraphNode> nodes() {
        return nodes;
    }

    public List<GraphConnection> connections() {
        return connections;
    }

    public List<GraphPassthrough> passthroughs() {
        return passthroughs;
    }

    public List<GraphVariable> variables() {
        return variables;
    }

    public List<FunctionBinding> functions() {
        return functions;
    }

    public OpaqueData unknown() {
        return unknown;
    }

    public ContentHash checksum() {
        return new ContentHash(CanonicalJson.sha256(CanonicalJson.GRAPH_HASH_DOMAIN, canonicalValue()));
    }

    public String canonicalJson() {
        return CanonicalJson.canonicalize(canonicalValue());
    }

    public byte[] canonicalBytes() {
        return CanonicalJson.canonicalBytes(canonicalValue());
    }

    Map<String, Object> canonicalValue() {
        LinkedHashMap<String, Object> values = new LinkedHashMap<>();
        values.put("schemaVersion", Map.of("generation", schemaVersion.generation(), "minor", schemaVersion.minor()));
        values.put("resource", resource.canonicalValue());
        values.put("revision", revision);
        values.put("catalogBinding", Map.of("generation", catalogBinding.generation(),
            "catalogChecksum", catalogBinding.catalogChecksum().canonicalText(),
            "bindingManifestHash", catalogBinding.bindingManifestHash().canonicalText()));
        values.put("requiredCapabilities", requiredCapabilities.stream().sorted().map(ContractRef::canonicalValue).toList());
        values.put("nodes", nodes.stream().sorted(Comparator.comparing(GraphNode::instanceId)).map(GraphNode::canonicalValue).toList());
        values.put("connections", connections.stream().sorted(Comparator.comparing(GraphConnection::connectionId)).map(GraphConnection::canonicalValue).toList());
        if (!passthroughs.isEmpty()) {
            values.put("passthroughs", passthroughs.stream()
                .sorted(Comparator.comparing(GraphPassthrough::nodeId).thenComparing(GraphPassthrough::inputPin))
                .map(GraphPassthrough::canonicalValue).toList());
        }
        if (!variables.isEmpty()) {
            values.put("variables", variables.stream().sorted(Comparator.comparing(GraphVariable::variableId)).map(GraphVariable::canonicalValue).toList());
        }
        if (!functions.isEmpty()) {
            values.put("functions", functions.stream().sorted(Comparator.comparing(binding -> binding.function().canonicalText())).map(FunctionBinding::canonicalValue).toList());
        }
        return OpaqueData.mergeKnownFields(unknown, values);
    }

    private static Set<ContractRef<CapabilityId>> immutableReferences(Set<ContractRef<CapabilityId>> source) {
        if (source == null || source.isEmpty()) {
            return Set.of();
        }
        HashSet<ContractRef<CapabilityId>> copy = new HashSet<>();
        source.forEach(reference -> copy.add(Objects.requireNonNull(reference, "required capability")));
        return Collections.unmodifiableSet(copy);
    }

    private static void validateIds(List<GraphNode> nodes, List<GraphConnection> connections,
                                    List<GraphPassthrough> passthroughs, List<GraphVariable> variables) {
        Set<NodeInstanceId> nodeIds = new HashSet<>();
        nodes.forEach(node -> {
            GraphNode value = Objects.requireNonNull(node, "node");
            if (!nodeIds.add(value.instanceId())) {
                throw new IllegalArgumentException("Duplicate graph node ID: " + value.instanceId());
            }
        });
        Set<ConnectionId> connectionIds = new HashSet<>();
        connections.forEach(connection -> {
            GraphConnection value = Objects.requireNonNull(connection, "connection");
            if (!connectionIds.add(value.connectionId())) {
                throw new IllegalArgumentException("Duplicate graph connection ID: " + value.connectionId());
            }
        });
        Set<UUID> variableIds = new HashSet<>();
        Set<String> variableNames = new HashSet<>();
        variables.forEach(variable -> {
            GraphVariable value = Objects.requireNonNull(variable, "variable");
            if (!variableIds.add(value.variableId())) {
                throw new IllegalArgumentException("Duplicate graph variable ID: " + value.variableId());
            }
            if (!variableNames.add(value.name())) {
                throw new IllegalArgumentException("Duplicate graph variable name: " + value.name());
            }
        });
        Set<String> passthroughIdentities = new HashSet<>();
        Set<ConnectionId> passthroughConnections = new HashSet<>();
        passthroughs.forEach(passthrough -> {
            GraphPassthrough value = Objects.requireNonNull(passthrough, "passthrough");
            if (!nodeIds.contains(value.nodeId())) {
                throw new IllegalArgumentException("Passthrough node does not exist: " + value.nodeId());
            }
            String identity = value.nodeId().canonicalText() + "\u0000" + value.inputPin().canonicalText();
            if (!passthroughIdentities.add(identity)) {
                throw new IllegalArgumentException("Duplicate graph passthrough: " + identity);
            }
            value.connectionIds().forEach(connectionId -> {
                if (!connectionIds.contains(connectionId)) {
                    throw new IllegalArgumentException("Passthrough connection does not exist: " + connectionId);
                }
                if (!passthroughConnections.add(connectionId)) {
                    throw new IllegalArgumentException("Connection belongs to multiple graph passthroughs: " + connectionId);
                }
            });
        });
    }
}
