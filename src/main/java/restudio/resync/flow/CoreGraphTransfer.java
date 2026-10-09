package restudio.resync.flow;

import restudio.resync.contract.canonical.CanonicalCodec;
import restudio.resync.contract.canonical.JsonValue;
import restudio.resync.flow.function.FunctionLocator;
import restudio.resync.flow.function.FunctionParameterContract;
import restudio.resync.flow.function.FunctionRevision;
import restudio.resync.flow.function.FunctionSignature;
import restudio.resync.flow.function.FunctionSourceDocument;
import restudio.resync.flow.graph.BranchBinding;
import restudio.resync.flow.graph.BranchCase;
import restudio.resync.flow.graph.FunctionBinding;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.graph.GraphNode;
import restudio.resync.flow.graph.GraphVariable;
import restudio.resync.flow.graph.PinValue;
import restudio.resync.flow.graph.RepeatableBinding;
import restudio.resync.flow.graph.RepeatableElement;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.PinId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.type.TypedValue;
import restudio.resync.modules.flow.FlowResourceAdapter;

import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class CoreGraphTransfer {
    private static final CoreGraphStorageBoundary BOUNDARY = new CoreGraphStorageBoundary();
    private static final ServerId SHARED = ServerId.deterministic("resync-core-graph-transfer-v1");
    private static final UUID SHARED_MUTATION = UUID.nameUUIDFromBytes("resync-core-graph-transfer-v1".getBytes(StandardCharsets.UTF_8));
    private static final OwnerId OWNER = OwnerId.of("restudio.resync");
    private static final Set<String> TYPES = Set.of("flow", "function", "command");

    private CoreGraphTransfer() {
    }

    public static String serialize(CoreGraphStorageBoundary.Decoded source) {
        GraphDocument graph = graph(source);
        ServerResourceLocator shared = new ServerResourceLocator(SHARED, graph.resource().key(), graph.resource().unknown());
        CatalogBinding portable = new CatalogBinding(1L, graph.catalogBinding().catalogChecksum(), graph.catalogBinding().bindingManifestHash());
        return new String(BOUNDARY.encode(bind(source, shared, 0L, SHARED_MUTATION, portable)), StandardCharsets.UTF_8);
    }

    public static CoreGraphStorageBoundary.Decoded decode(String payload, String type, String id) {
        CoreGraphStorageBoundary.Decoded decoded = decode(payload, type);
        if (!graph(decoded).resource().id().equals(id)) {
            throw new IllegalArgumentException("Shared Core graph does not match its resource identity");
        }
        return decoded;
    }

    public static CoreGraphStorageBoundary.Decoded decode(String payload, String type) {
        CoreGraphStorageBoundary.Decoded decoded;
        try {
            decoded = BOUNDARY.decodeText(payload);
        } catch (IllegalArgumentException failure) {
            JsonValue value = CanonicalCodec.decodePermissive(payload);
            if (value instanceof JsonValue.JsonObject object && bareLegacyGraph(object, type)) {
                throw new BlockedTransfer(type, ((JsonValue.JsonString) object.value("id")).value());
            }
            throw failure;
        }
        GraphDocument graph = graph(decoded);
        if (!OWNER.equals(graph.resource().owner()) || !TYPES.contains(type)
            || !type.equals(graph.resource().resourceType().value())) {
            throw new IllegalArgumentException("Shared Core graph does not match its resource identity");
        }
        return decoded;
    }

    public static final class BlockedTransfer extends FlowResourceAdapter.BlockedNetworkResource {
        private BlockedTransfer(String resourceType, String resourceId) {
            super(resourceType, resourceId,
                "Legacy graph has no verified Core source. Hub data is preserved. Export or archive it and recreate it before synchronization");
        }
    }

    private static boolean bareLegacyGraph(JsonValue.JsonObject graph, String type) {
        if (!TYPES.contains(type) || !(graph.value("id") instanceof JsonValue.JsonString id) || id.value().isBlank()
            || !number(graph.value("version"), 2L) || !(graph.value("nodes") instanceof JsonValue.JsonObject nodes)
            || !(graph.value("connections") instanceof JsonValue.JsonArray connections)
            || !(graph.value("localVariables") instanceof JsonValue.JsonArray)) {
            return false;
        }
        if (graph.fields().keySet().stream().anyMatch(field -> field.startsWith("asset") || field.startsWith("corePayload")
            || Set.of("catalogBinding", "resource", "signature", "graph").contains(field))) {
            return false;
        }
        for (String flag : List.of("enabled", "function")) {
            if (graph.contains(flag) && !(graph.value(flag) instanceof JsonValue.JsonBoolean)) {
                return false;
            }
        }
        for (String array : List.of("localVariables", "functionInputs", "functionOutputs", "editorPassthroughs")) {
            if (graph.contains(array)) {
                if (!(graph.value(array) instanceof JsonValue.JsonArray values)
                    || values.values().stream().anyMatch(value -> !(value instanceof JsonValue.JsonObject))) {
                    return false;
                }
            }
        }
        for (String text : List.of("functionOwner", "functionNamespace", "functionDescription", "resourceHash", "resourceMutationId")) {
            if (graph.contains(text) && !(graph.value(text) instanceof JsonValue.JsonString)
                && !(graph.value(text) instanceof JsonValue.JsonNull)) {
                return false;
            }
        }
        if (graph.contains("resourceRevision") && (!(graph.value("resourceRevision") instanceof JsonValue.JsonNumber revision)
            || revision.value().scale() > 0 || revision.value().signum() < 0)) {
            return false;
        }
        if (graph.contains("resourceType") && (!(graph.value("resourceType") instanceof JsonValue.JsonString resourceType)
            || !resourceType.value().isBlank() && !TYPES.contains(resourceType.value()))) {
            return false;
        }
        for (Map.Entry<String, JsonValue> entry : nodes.fields().entrySet()) {
            if (entry.getKey().isBlank() || !(entry.getValue() instanceof JsonValue.JsonObject node)
                || !(node.value("type") instanceof JsonValue.JsonString definition) || definition.value().isBlank()
                || !(node.value("version") instanceof JsonValue.JsonNumber version) || version.value().scale() > 0
                || version.value().signum() < 1 || !(node.value("inputValues") instanceof JsonValue.JsonObject)
                || node.contains("handlerConfig") && !(node.value("handlerConfig") instanceof JsonValue.JsonObject)) {
                return false;
            }
            for (String coordinate : List.of("x", "y")) {
                if (node.contains(coordinate) && !(node.value(coordinate) instanceof JsonValue.JsonNumber)) {
                    return false;
                }
            }
        }
        for (JsonValue value : connections.values()) {
            if (!(value instanceof JsonValue.JsonObject connection)) {
                return false;
            }
            for (String field : List.of("sourceNodeId", "sourcePin", "targetNodeId", "targetPin")) {
                if (!(connection.value(field) instanceof JsonValue.JsonString text) || text.value().isBlank()) {
                    return false;
                }
            }
            if (!nodes.contains(((JsonValue.JsonString) connection.value("sourceNodeId")).value())
                || !nodes.contains(((JsonValue.JsonString) connection.value("targetNodeId")).value())) {
                return false;
            }
        }
        return true;
    }

    private static boolean number(JsonValue value, long expected) {
        return value instanceof JsonValue.JsonNumber number && number.value().compareTo(BigDecimal.valueOf(expected)) == 0;
    }

    public static CoreGraphStorageBoundary.Decoded bind(CoreGraphStorageBoundary.Decoded source,
                                                       ServerResourceLocator target, long revision, UUID mutationId) {
        return bind(source, target, revision, mutationId, graph(source).catalogBinding());
    }

    public static CoreGraphStorageBoundary.Decoded bind(CoreGraphStorageBoundary.Decoded source,
                                                       ServerResourceLocator target, long revision, UUID mutationId,
                                                       CatalogBinding binding) {
        GraphDocument graph = graph(source);
        if (!OWNER.equals(target.owner()) || !TYPES.contains(target.resourceType().value())
            || !graph.resource().key().equals(target.key())) {
            throw new IllegalArgumentException("Shared Core graph cannot change its resource key");
        }
        if (!graph.catalogBinding().catalogChecksum().equals(binding.catalogChecksum())
            || !graph.catalogBinding().bindingManifestHash().equals(binding.bindingManifestHash())) {
            throw new IllegalArgumentException("Shared Core graph requires a matching catalog and runtime binding");
        }
        ServerId origin = graph.resource().serverId();
        ServerId server = target.serverId();
        GraphDocument bound = new GraphDocument(graph.schemaVersion(), target, revision, binding,
            graph.requiredCapabilities(), graph.nodes().stream().map(node -> node(node, origin, server)).toList(),
            graph.connections(), graph.passthroughs(), graph.variables().stream().map(variable -> new GraphVariable(variable.variableId(),
                variable.name(), variable.type(), variable.value() == null ? null : value(variable.value(), origin, server), variable.unknown())).toList(),
            graph.functions().stream().map(function -> new FunctionBinding(locator(function.function(), origin, server), function.revision(),
                function.inputs(), function.outputs(), function.unknown())).toList(), graph.unknown());
        CoreGraphStorageBoundary.AssetMetadata metadata = new CoreGraphStorageBoundary.AssetMetadata(
            target.resourceType().value(), revision, mutationId, source.envelope().assetActivationState());
        byte[] encoded;
        if (source.functionSourceDocument() == null) {
            encoded = BOUNDARY.encode(bound, metadata, target);
        } else {
            FunctionSourceDocument function = source.functionSourceDocument();
            FunctionSignature signature = function.signature();
            FunctionSourceDocument rebound = new FunctionSourceDocument(new FunctionSignature(new FunctionLocator(target),
                FunctionRevision.of(revision), signature.inputs().stream().map(parameter -> parameter(parameter, origin, server)).toList(),
                signature.outputs().stream().map(parameter -> parameter(parameter, origin, server)).toList(), signature.unknown()), bound, function.unknown());
            encoded = BOUNDARY.encode(rebound, metadata, target);
        }
        return BOUNDARY.decode(encoded, target);
    }

    private static GraphDocument graph(CoreGraphStorageBoundary.Decoded source) {
        return source.graphDocument() == null ? source.functionSourceDocument().graph() : source.graphDocument();
    }

    private static FunctionParameterContract parameter(FunctionParameterContract parameter, ServerId origin, ServerId target) {
        return new FunctionParameterContract(parameter.id(), parameter.type(), parameter.required(),
            parameter.defaultValue() == null ? null : value(parameter.defaultValue(), origin, target), parameter.unknown());
    }

    private static GraphNode node(GraphNode node, ServerId origin, ServerId target) {
        Map<Object, Object> inspector = new LinkedHashMap<>();
        node.inspector().forEach((pin, value) -> inspector.put(pin, new PinValue(pin, value(value.value(), origin, target), value.unknown())));
        node.inspectorFields().forEach((field, value) -> inspector.put(field, value(value, origin, target)));
        return new GraphNode(node.instanceId(), node.definition(), node.definitionVersion(), node.modeId(), pins(node.values(), origin, target),
            inspector, node.branches().stream().map(branch -> new BranchBinding(branch.branchId(), branch.selectedCaseId(),
                branch.cases().stream().map(branchCase -> new BranchCase(branchCase.caseId(), pins(branchCase.values(), origin, target),
                    branchCase.inspectorState(), branchCase.unknown())).toList(), branch.unknown())).toList(),
            node.repeatables().stream().map(repeatable -> new RepeatableBinding(repeatable.groupId(), repeatable.ordered(),
                repeatable.elements().stream().map(element -> new RepeatableElement(element.elementId(), pins(element.values(), origin, target),
                    element.unknown())).toList(), repeatable.unknown())).toList(), node.inspectorState(), node.x(), node.y(), node.unknown());
    }

    private static Map<PinId, PinValue> pins(Map<PinId, PinValue> pins, ServerId origin, ServerId target) {
        Map<PinId, PinValue> result = new LinkedHashMap<>();
        pins.forEach((pin, value) -> result.put(pin, new PinValue(pin, value(value.value(), origin, target), value.unknown())));
        return result;
    }

    private static TypedValue value(TypedValue value, ServerId origin, ServerId target) {
        return new TypedValue(value.type(), value.state(), value.variantId(), material(value.value(), origin, target),
            value.locator() == null ? null : locator(value.locator(), origin, target), value.unknown());
    }

    private static Object material(Object value, ServerId origin, ServerId target) {
        if (value instanceof ServerResourceLocator locator) {
            return locator(locator, origin, target);
        }
        if (value instanceof List<?> values) {
            return values.stream().map(entry -> material(entry, origin, target)).toList();
        }
        if (value instanceof Map<?, ?> values) {
            Map<Object, Object> result = new LinkedHashMap<>();
            values.forEach((key, entry) -> result.put(key, material(entry, origin, target)));
            return result;
        }
        return value;
    }

    private static ServerResourceLocator locator(ServerResourceLocator locator, ServerId origin, ServerId target) {
        return locator.serverId().equals(origin) ? new ServerResourceLocator(target, locator.key(), locator.unknown()) : locator;
    }
}
