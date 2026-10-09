package restudio.resync.flow;

import com.google.gson.JsonObject;
import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowSerializer;
import restudio.flow.data.FlowTypeRef;
import restudio.resync.contract.canonical.JsonValue;
import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.catalog.CatalogStartupIndex;
import restudio.resync.flow.command.CommandGraphContract;
import restudio.resync.flow.command.CommandGraphMetadata;
import restudio.resync.flow.graph.GraphConnection;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.graph.GraphEndpoint;
import restudio.resync.flow.graph.GraphNode;
import restudio.resync.flow.graph.GraphVariable;
import restudio.resync.flow.graph.OpaqueData;
import restudio.resync.flow.graph.PinValue;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ConnectionId;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.NodeInstanceId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.PinId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.protocol.ResourceActivationState;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypeReference;
import restudio.resync.flow.type.TypeValueCodec;
import restudio.resync.flow.type.TypedValue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class RewrittenGraphConverter {
    private static final Set<String> GRAPH_FIELDS = Set.of("schemaVersion", "resource", "revision", "catalogBinding",
        "requiredCapabilities", "nodes", "connections", "passthroughs", "variables", "functions");
    private static final Set<String> NODE_FIELDS = Set.of("instanceId", "definition", "definitionVersion", "modeId",
        "values", "inspector", "branches", "repeatables", "inspectorState", "position");
    private static final Set<String> ENVELOPE_FIELDS = Set.of("resourceType", "contentHash", "formatVersion", "hash",
        "mutationId", "payloadHash", "payloadKind", "payloadVersion", "resourceHash", "resourceMutationId",
        "resourceRevision", "type", "id");

    private RewrittenGraphConverter() {
    }

    public static CoreGraphStorageBoundary.Decoded convert(JsonObject verifiedRaw, ServerResourceLocator resource,
                                                           long nextRevision, UUID migrationId,
                                                           CatalogStartupIndex.CommittedSnapshot catalog) {
        Objects.requireNonNull(verifiedRaw, "Verified rewritten graph is required");
        Objects.requireNonNull(resource, "Graph resource is required");
        Objects.requireNonNull(migrationId, "Graph migration ID is required");
        Objects.requireNonNull(catalog, "Authenticated committed catalog is required");
        Map<String, Object> raw = object(CanonicalJson.parse(verifiedRaw.toString()), "rewritten graph");
        String type = resource.resourceType().value();
        if (!"restudio.resync".equals(resource.owner().value()) || !Set.of("flow", "command").contains(type)
            || !resource.id().equals(text(raw, "id")) || !type.equals(text(raw, "resourceType"))
            || integer(raw, "version") != FlowGraph.CURRENT_VERSION
            || integer(raw, "assetFormatVersion") != CoreGraphStorageBoundary.LEGACY_ASSET_FORMAT_VERSION
            || nextRevision < 1) {
            throw rejected(resource, "The rewritten graph identity or version is not supported");
        }
        if (bool(raw, "function") || !array(raw, "functionInputs").isEmpty() || !array(raw, "functionOutputs").isEmpty()) {
            throw rejected(resource, "Function graphs require an explicit immutable typed Function source");
        }
        if (!array(raw, "editorPassthroughs").isEmpty()) {
            throw rejected(resource, "Editor passthrough associations require an explicit typed graph source");
        }
        List<Map<String, Object>> definitions = array(catalog.content(),
            "definitions").stream().map(value -> object(value, "catalog definition")).toList();
        Map<String, ConvertedNode> converted = new LinkedHashMap<>();
        Set<ContractRef<CapabilityId>> capabilities = new LinkedHashSet<>();
        for (Map.Entry<String, Object> entry : object(raw.get("nodes"), "graph nodes").entrySet()) {
            Map<String, Object> node = object(entry.getValue(), "graph node " + entry.getKey());
            String authoredType = text(node, "type");
            boolean commandAlias = "command".equals(type) && CommandGraphContract.isLegacyStart(authoredType);
            Map<String, Object> definition = definition(definitions, commandAlias
                ? CommandGraphContract.CANONICAL_START.canonicalText() : authoredType, resource);
            ContractRef<NodeId> identity = ContractRef.of(OwnerId.of(text(definition, "ownerId")), NodeId.of(text(definition, "id")));
            int schema = integer(definition, "schemaVersion");
            int authoredVersion = integer(node, "version");
            if (authoredVersion != schema && !(commandAlias && authoredVersion >= 1 && authoredVersion <= 2)) {
                throw rejected(resource, "Node " + entry.getKey() + " requires an explicit schema migration for " + authoredType);
            }
            Map<String, Object> metadata = object(definition.get("metadata"), "definition metadata");
            Map<String, Object> source = metadata.get("authoredSource") instanceof Map<?, ?>
                ? object(metadata.get("authoredSource"), "authored node source") : metadata;
            Map<String, Object> config = node.containsKey("handlerConfig")
                ? object(node.get("handlerConfig"), "authored handler configuration") : Map.of();
            Map<String, Object> defaults = source.get("handlerConfig") instanceof Map<?, ?>
                ? object(source.get("handlerConfig"), "catalog handler configuration") : Map.of();
            if (!config.isEmpty() && !CanonicalJson.canonicalize(config).equals(CanonicalJson.canonicalize(defaults))) {
                throw rejected(resource, "Node " + entry.getKey() + " has handler configuration outside its authenticated catalog contract");
            }
            if (source.containsKey("functionId") || defaults.containsKey("functionId")) {
                throw rejected(resource, "Node " + entry.getKey() + " requires an immutable typed Function binding");
            }
            Map<String, Pin> pins = pins(definition);
            Map<PinId, PinValue> values = new LinkedHashMap<>();
            for (Map.Entry<String, Object> input : object(node.get("inputValues"), "node input values").entrySet()) {
                Pin pin = pins.get(input.getKey());
                if (pin == null || !"input".equals(pin.direction())) {
                    throw rejected(resource, "Node " + entry.getKey() + " has an unknown authored input " + input.getKey());
                }
                PinId pinId = PinId.of(input.getKey());
                values.put(pinId, new PinValue(pinId, literal(pin.type(), input.getValue(), resource)));
            }
            Map<String, Object> unknown = without(node, NODE_FIELDS);
            GraphNode document = new GraphNode(NodeInstanceId.parseCanonicalText(entry.getKey()), identity, schema,
                null, values, Map.of(), List.of(), List.of(), null, number(node, "x"), number(node, "y"), OpaqueData.of(unknown));
            converted.put(entry.getKey(), new ConvertedNode(document, pins));
            for (Object capability : array(definition, "requiredCapabilities")) {
                Map<String, Object> value = object(capability, "required capability");
                capabilities.add(ContractRef.of(OwnerId.of(text(value, "ownerId")), CapabilityId.of(text(value, "localId"))));
            }
        }
        List<GraphConnection> connections = connections(raw, converted, resource);
        List<GraphVariable> variables = variables(raw, resource);
        Map<String, Object> unknown = without(raw, GRAPH_FIELDS);
        unknown.keySet().removeIf(field -> ENVELOPE_FIELDS.contains(field)
            || field.startsWith("asset") || field.startsWith("corePayload"));
        if (unknown.containsKey("rewrittenGraphSource")) {
            throw rejected(resource, "The source already contains reserved rewrite provenance");
        }
        unknown.put("rewrittenGraphSource", raw);
        GraphDocument graph = new GraphDocument(GraphDocument.CURRENT_SCHEMA_VERSION, resource, nextRevision,
            catalog.binding(), capabilities, converted.values().stream().map(ConvertedNode::document).toList(), connections,
            variables, List.of(), OpaqueData.of(unknown));
        if ("command".equals(type)) {
            FlowGraph authored = FlowSerializer.deserialize(verifiedRaw.toString(), resource.serverId().canonicalText());
            TypedCommandGraphAdapter.CommandBinding header = TypedCommandGraphAdapter.read(authored);
            Map<String, Object> fields = new LinkedHashMap<>(graph.unknown().fields());
            fields.put("aliases", header.aliases());
            fields.put("permission", header.permission());
            fields.put("permissionMessage", header.permissionMessage());
            fields.put("description", header.description());
            fields.put("usage", header.usage());
            graph = new GraphDocument(graph.schemaVersion(), graph.resource(), graph.revision(), graph.catalogBinding(),
                graph.requiredCapabilities(), graph.nodes(), graph.connections(), graph.passthroughs(), graph.variables(),
                graph.functions(), OpaqueData.of(fields));
            graph = new CommandGraphMetadata(header.command(), header.structured(),
                header.commandPaths().stream().map(path -> String.join(" ", path)).toList()).apply(graph);
            TypedCommandGraphAdapter.read(graph, bool(raw, "enabled"));
        }
        CoreGraphStorageBoundary boundary = new CoreGraphStorageBoundary();
        ResourceActivationState activation = bool(raw, "enabled") ? ResourceActivationState.ACTIVE : ResourceActivationState.INACTIVE;
        return boundary.decode(boundary.encode(graph, new CoreGraphStorageBoundary.AssetMetadata(type, nextRevision,
            migrationId, activation), resource), resource);
    }

    private static Map<String, Object> definition(List<Map<String, Object>> definitions, String type,
                                                 ServerResourceLocator resource) {
        String qualified = type.contains("/") ? type : type.contains(":") ? type.replaceFirst(":", "/") : null;
        List<Map<String, Object>> matches = definitions.stream().filter(value -> qualified == null
            ? type.equals(value.get("id")) : qualified.equals(value.get("ownerId") + "/" + value.get("id"))).toList();
        if (matches.size() != 1) {
            throw rejected(resource, "Node definition has no unique authenticated catalog owner: " + type);
        }
        Map<String, Object> definition = matches.getFirst();
        Map<String, Object> metadata = object(definition.get("metadata"), "definition metadata");
        if (metadata.get("authoredSource") instanceof Map<?, ?>) {
            Map<String, Object> source = object(metadata.get("authoredSource"), "authored definition source");
            if (!definition.get("id").equals(source.get("id")) || !definition.get("ownerId").equals(source.get("owner"))) {
                throw rejected(resource, "Node definition source differs from its authenticated catalog owner: " + type);
            }
        }
        if (!"active".equals(definition.get("lifecycle"))) {
            throw rejected(resource, "Node definition is not active in the authenticated catalog: " + type);
        }
        return definition;
    }

    private static Map<String, Pin> pins(Map<String, Object> definition) {
        Map<String, Pin> pins = new LinkedHashMap<>();
        for (Object value : array(definition, "pins")) {
            Map<String, Object> pin = object(value, "catalog pin");
            String id = text(pin, "id");
            if (pins.putIfAbsent(id, new Pin(text(pin, "direction"),
                TypeValueCodec.INSTANCE.decodeType(JsonValue.fromJava(pin.get("type"))))) != null) {
                throw new IllegalArgumentException("Authenticated catalog contains duplicate pin " + id);
            }
        }
        return pins;
    }

    private static List<GraphConnection> connections(Map<String, Object> raw, Map<String, ConvertedNode> nodes,
                                                     ServerResourceLocator resource) {
        List<GraphConnection> result = new ArrayList<>();
        Set<String> addresses = new LinkedHashSet<>();
        for (Object value : array(raw, "connections")) {
            Map<String, Object> connection = object(value, "graph connection");
            if (connection.get("editorSourceNodeId") != null || connection.get("editorSourcePin") != null
                || connection.get("editorSourcePinId") != null) {
                throw rejected(resource, "Editor connection associations require an explicit typed graph source");
            }
            String sourceId = text(connection, "sourceNodeId");
            String targetId = text(connection, "targetNodeId");
            String sourcePin = pinText(connection, "sourcePin");
            String targetPin = pinText(connection, "targetPin");
            ConvertedNode source = nodes.get(sourceId);
            ConvertedNode target = nodes.get(targetId);
            Pin from = source == null ? null : source.pins().get(sourcePin);
            Pin to = target == null ? null : target.pins().get(targetPin);
            if (from == null || to == null || !"output".equals(from.direction()) || !"input".equals(to.direction())) {
                throw rejected(resource, "A connection endpoint does not match its authenticated node contract");
            }
            if (!from.type().equals(to.type())) {
                throw rejected(resource, "A connection requires an explicit typed conversion contract");
            }
            String address = sourceId + "\u0000" + sourcePin + "\u0000" + targetId + "\u0000" + targetPin;
            if (!addresses.add(address)) {
                throw rejected(resource, "Duplicate connections cannot receive one immutable identity");
            }
            ConnectionId id = ConnectionId.deterministic("rewritten-graph|" + resource.key().canonicalText() + "|" + address);
            result.add(new GraphConnection(id, new GraphEndpoint(source.document().instanceId(), PinId.of(sourcePin)),
                new GraphEndpoint(target.document().instanceId(), PinId.of(targetPin)),
                OpaqueData.of(without(connection, Set.of("connectionId", "source", "target")))));
        }
        return result;
    }

    private static List<GraphVariable> variables(Map<String, Object> raw, ServerResourceLocator resource) {
        List<GraphVariable> result = new ArrayList<>();
        for (Object value : array(raw, "localVariables")) {
            Map<String, Object> variable = object(value, "graph variable");
            Map<String, String> policies = Map.of("scope", "local", "lifetime", "execution", "owner", "graph",
                "absencePolicy", "use_default", "concurrencyPolicy", "isolated");
            for (Map.Entry<String, String> policy : policies.entrySet()) {
                if (variable.containsKey(policy.getKey()) && !policy.getValue().equals(variable.get(policy.getKey()))) {
                    throw rejected(resource, "Variable policy requires an explicit typed contract: " + policy.getKey());
                }
            }
            String name = text(variable, "name");
            TypeExpr type = type(FlowTypeRef.parse(text(variable, "type")));
            UUID id = variable.get("variableId") instanceof String text ? UUID.fromString(text)
                : UUID.nameUUIDFromBytes(CanonicalJson.canonicalBytes(List.of("rewritten-variable", resource.key().canonicalText(), name)));
            result.add(new GraphVariable(id, name, type, literal(type, variable.get("initialValue"), resource),
                OpaqueData.of(without(variable, Set.of("variableId", "name", "type", "value")))));
        }
        return result;
    }

    private static TypeExpr type(FlowTypeRef reference) {
        List<TypeExpr> arguments = reference.getArguments().stream().map(RewrittenGraphConverter::type).toList();
        return switch (reference.getTypeId()) {
            case "optional" -> TypeExpr.optional(arguments.getFirst());
            case "list" -> TypeExpr.list(arguments.getFirst());
            case "map" -> TypeExpr.map(arguments.getFirst(), arguments.get(1));
            case "result" -> TypeExpr.result(arguments.getFirst(), arguments.get(1));
            default -> {
                String[] parts = reference.getTypeId().split(":", 2);
                yield TypeExpr.named(TypeReference.of(parts.length == 2 ? parts[0] : "builtin",
                    parts.length == 2 ? parts[1] : parts[0]), arguments);
            }
        };
    }

    private static TypedValue literal(TypeExpr type, Object raw, ServerResourceLocator resource) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("type", type.canonicalValue());
        value.put("state", raw == null ? "null" : "value");
        if (raw != null) {
            value.put("value", raw);
        }
        return TypedResourceReferenceBoundary.requireValue(type, TypeValueCodec.INSTANCE.decode(JsonValue.fromJava(value)), resource.serverId());
    }

    private static Map<String, Object> without(Map<String, Object> source, Set<String> known) {
        Map<String, Object> result = new LinkedHashMap<>(source);
        known.forEach(result::remove);
        return result;
    }

    private static Map<String, Object> object(Object value, String label) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException(label + " must be an object");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, item) -> {
            if (!(key instanceof String text)) {
                throw new IllegalArgumentException(label + " contains a non-text field");
            }
            result.put(text, item);
        });
        return result;
    }

    private static List<?> array(Map<String, Object> value, String field) {
        if (!(value.get(field) instanceof List<?> list)) {
            throw new IllegalArgumentException(field + " must be an array");
        }
        return list;
    }

    private static String text(Map<String, Object> value, String field) {
        if (!(value.get(field) instanceof String text) || text.isBlank() || !text.equals(text.strip())) {
            throw new IllegalArgumentException(field + " must contain canonical text");
        }
        return text;
    }

    private static String pinText(Map<String, Object> value, String field) {
        return value.get(field + "Id") instanceof String text && !text.isBlank() ? text(value, field + "Id") : text(value, field);
    }

    private static int integer(Map<String, Object> value, String field) {
        if (!(value.get(field) instanceof Number number)) {
            throw new IllegalArgumentException(field + " must be an integer");
        }
        return new BigDecimal(number.toString()).intValueExact();
    }

    private static double number(Map<String, Object> value, String field) {
        return ((Number) Objects.requireNonNull(value.get(field), field)).doubleValue();
    }

    private static boolean bool(Map<String, Object> value, String field) {
        if (!(value.get(field) instanceof Boolean result)) {
            throw new IllegalArgumentException(field + " must be boolean");
        }
        return result;
    }

    private static IllegalArgumentException rejected(ServerResourceLocator resource, String reason) {
        return new IllegalArgumentException("Rewritten graph " + resource.key().canonicalText() + " cannot be converted: " + reason);
    }

    private record Pin(String direction, TypeExpr type) {
    }

    private record ConvertedNode(GraphNode document, Map<String, Pin> pins) {
    }
}
