package restudio.resync.upgrade.flow;

import restudio.resync.contract.canonical.JsonValue;
import restudio.resync.contract.canonical.JsonValue.JsonArray;
import restudio.resync.contract.canonical.JsonValue.JsonBoolean;
import restudio.resync.contract.canonical.JsonValue.JsonNumber;
import restudio.resync.contract.canonical.JsonValue.JsonObject;
import restudio.resync.contract.canonical.JsonValue.JsonString;
import restudio.resync.flow.CoreGraphProjectionContext;
import restudio.resync.flow.catalog.CatalogNodeDescriptor;
import restudio.resync.flow.graph.GraphConnection;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.graph.GraphEndpoint;
import restudio.resync.flow.graph.GraphNode;
import restudio.resync.flow.graph.InspectorState;
import restudio.resync.flow.graph.OpaqueData;
import restudio.resync.flow.graph.PinValue;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.NodeInstanceId;
import restudio.resync.flow.identity.PinId;
import restudio.resync.flow.identity.UuidIdentity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public final class CanonicalLegacyGraphMaterializer {
    private static final String RAW_NAMESPACE = "restudio.resync.legacyGraph";
    private static final List<String> UNSUPPORTED_ROOT_FIELDS = List.of("contentProperties", "editorPassthroughs", "functionDescription",
        "functionInputs", "functionNamespace", "functionOutputs", "functionOwner", "functionVersion", "localVariables");

    private CanonicalLegacyGraphMaterializer() {
    }

    public static Result materialize(RawLegacyGraphAdmission.Admitted admitted, LegacyNodeReferenceResolver resolver) {
        return materialize(admitted, resolver, false);
    }

    public static Result materialize(RawLegacyGraphAdmission.Admitted admitted, LegacyNodeReferenceResolver resolver,
                                     boolean validationOnly) {
        Objects.requireNonNull(admitted, "admitted");
        Objects.requireNonNull(resolver, "resolver");
        if (admitted.context() != resolver.context()) {
            return quarantine(admitted, "resolver-context-mismatch");
        }
        if (!"flow".equals(admitted.outer().resourceType())) {
            return quarantine(admitted, "unsupported-resource-type:" + admitted.outer().resourceType());
        }
        JsonObject source = admitted.innerJson();
        for (String field : UNSUPPORTED_ROOT_FIELDS) {
            JsonValue value = source.value(field);
            if (unsupportedRootValue(field, value)) {
                return quarantine(admitted, "unsupported-root-field:" + field);
            }
        }
        JsonValue function = source.value("function");
        if (function != null && (!(function instanceof JsonBoolean booleanValue) || booleanValue.value())) {
            return quarantine(admitted, "function-requires-function-source-document");
        }
        try {
            Map<String, ResolvedNode> nodes = resolveNodes(source, admitted, resolver);
            List<GraphConnection> connections = resolveConnections(admitted, nodes);
            List<GraphNode> graphNodes = nodes.values().stream().map(ResolvedNode::graphNode)
                .sorted(Comparator.comparing(GraphNode::instanceId)).toList();
            CoreGraphProjectionContext context = admitted.context();
            Set<ContractRef<CapabilityId>> requiredCapabilities = validationOnly ? Set.of() : nodes.values().stream()
                .flatMap(value -> value.definition().descriptor().requiredCapabilities().stream())
                .collect(Collectors.toUnmodifiableSet());
            Map<String, Object> opaque = new LinkedHashMap<>();
            opaque.put("format", 2);
            opaque.put("inner", admitted.canonicalInner());
            opaque.put("outer", admitted.outer().canonicalValue());
            opaque.put("raw", admitted.canonicalRaw());
            GraphDocument document = new GraphDocument(context.catalogVersion(), context.resource(),
                admitted.outer().effectiveRevision(context.resourceRevision()),
                context.binding(), requiredCapabilities, graphNodes, connections, List.of(), List.of(),
                OpaqueData.of(Map.of(RAW_NAMESPACE, opaque)));
            return new Materialized(document, admitted.canonicalRaw());
        } catch (UnsupportedLegacyState exception) {
            return quarantine(admitted, exception.getMessage());
        } catch (IllegalArgumentException exception) {
            return quarantine(admitted, stableReason(exception));
        }
    }

    private static boolean unsupportedRootValue(String field, JsonValue value) {
        if (value == null || value instanceof JsonValue.JsonNull) {
            return false;
        }
        return switch (field) {
            case "functionDescription" -> !textEquals(value, "");
            case "functionOwner" -> !textEquals(value, "server");
            case "functionNamespace" -> !textEquals(value, "local");
            case "functionVersion" -> !numberEquals(value, 1);
            case "functionInputs", "functionOutputs", "localVariables", "editorPassthroughs" -> !emptyArray(value);
            case "contentProperties" -> !emptyObject(value);
            default -> true;
        };
    }

    private static boolean textEquals(JsonValue value, String expected) {
        return value instanceof JsonString string && expected.equals(string.value());
    }

    private static boolean numberEquals(JsonValue value, int expected) {
        if (!(value instanceof JsonNumber number)) {
            return false;
        }
        try {
            return number.value().intValueExact() == expected;
        } catch (ArithmeticException exception) {
            return false;
        }
    }

    private static boolean emptyArray(JsonValue value) {
        return value instanceof JsonArray array && array.values().isEmpty();
    }

    private static boolean emptyObject(JsonValue value) {
        return value instanceof JsonObject object && object.values().isEmpty();
    }

    private static Map<String, ResolvedNode> resolveNodes(JsonObject source, RawLegacyGraphAdmission.Admitted admitted,
                                                           LegacyNodeReferenceResolver resolver) {
        JsonValue element = source.value("nodes");
        if (!(element instanceof JsonObject rawNodes)) {
            throw new IllegalArgumentException("Legacy graph requires nodes");
        }
        Map<String, ResolvedNode> result = new LinkedHashMap<>();
        for (Map.Entry<String, JsonValue> entry : rawNodes.fields().entrySet()) {
            if (!(entry.getValue() instanceof JsonObject raw)) {
                throw new IllegalArgumentException("Legacy node must be an object: " + entry.getKey());
            }
            String type = string(raw, "type");
            CoreGraphProjectionContext.ResolvedNode resolved = resolver.resolve(type);
            int version = integer(raw, "version");
            if (version != resolved.descriptor().schemaVersion()) {
                throw unsupported("node-schema-migration-required:" + entry.getKey());
            }
            Map<PinId, PinValue> values = inputValues(raw, resolved, entry.getKey());
            JsonValue handlerConfig = raw.value("handlerConfig");
            if (handlerConfig != null && !(handlerConfig instanceof JsonValue.JsonNull) && !empty(handlerConfig)) {
                throw unsupported("handler-config-migration-required:" + entry.getKey());
            }
            double x = number(raw, "x");
            double y = number(raw, "y");
            NodeInstanceId identity = admitted.nodes().get(entry.getKey());
            if (identity == null) {
                throw new IllegalArgumentException("Admitted node identity is unavailable: " + entry.getKey());
            }
            GraphNode node = new GraphNode(identity, resolved.reference(), version, null, values, Map.of(), List.of(), List.of(),
                InspectorState.empty(), x, y, nodeUnknown(raw));
            if (result.putIfAbsent(entry.getKey(), new ResolvedNode(resolved, node)) != null) {
                throw new IllegalArgumentException("Duplicate materialized node: " + entry.getKey());
            }
        }
        return Map.copyOf(result);
    }

    private static OpaqueData nodeUnknown(JsonObject source) {
        Set<String> known = Set.of("handlerConfig", "inputValues", "type", "version", "x", "y");
        Map<String, Object> result = new LinkedHashMap<>();
        source.fields().entrySet().stream().filter(entry -> !known.contains(entry.getKey()))
            .sorted(Map.Entry.comparingByKey()).forEach(entry -> result.put(entry.getKey(), value(entry.getValue())));
        return OpaqueData.of(result);
    }

    private static Object value(JsonValue element) {
        if (element == null || element instanceof JsonValue.JsonNull) {
            return null;
        }
        if (element instanceof JsonObject object) {
            Map<String, Object> result = new LinkedHashMap<>();
            object.fields().entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> result.put(entry.getKey(), value(entry.getValue())));
            return result;
        }
        if (element instanceof JsonArray array) {
            List<Object> result = new ArrayList<>();
            array.values().forEach(entry -> result.add(value(entry)));
            return result;
        }
        if (element instanceof JsonBoolean booleanValue) {
            return booleanValue.value();
        }
        if (element instanceof JsonNumber number) {
            return number.value();
        }
        if (element instanceof JsonString string) {
            return string.value();
        }
        throw new IllegalArgumentException("Unsupported JSON value: " + element.getClass().getName());
    }

    private static Map<PinId, PinValue> inputValues(JsonObject raw, CoreGraphProjectionContext.ResolvedNode node,
                                                    String legacyNodeId) {
        JsonValue element = raw.value("inputValues");
        if (element == null || element instanceof JsonValue.JsonNull) {
            return Map.of();
        }
        if (!(element instanceof JsonObject inputValues)) {
            throw new IllegalArgumentException("Legacy node inputValues must be an object: " + legacyNodeId);
        }
        Map<PinId, PinValue> result = new LinkedHashMap<>();
        for (Map.Entry<String, JsonValue> entry : inputValues.fields().entrySet()) {
            PinId pinId;
            try {
                pinId = PinId.of(entry.getKey());
            } catch (IllegalArgumentException exception) {
                throw unsupported("legacy-input-pin-migration-required:" + legacyNodeId + ":" + entry.getKey());
            }
            CatalogNodeDescriptor.Pin pin = node.requirePin(pinId);
            if (pin.direction() != CatalogNodeDescriptor.Direction.INPUT) {
                throw unsupported("legacy-input-pin-direction-mismatch:" + legacyNodeId + ":" + entry.getKey());
            }
            var typed = TypedLegacyLiteralCodec.decode(pin.type(), entry.getValue());
            if (typed.isEmpty()) {
                throw unsupported("typed-input-migration-required:" + legacyNodeId + ":" + entry.getKey());
            }
            if (result.putIfAbsent(pinId, new PinValue(pinId, typed.orElseThrow(), OpaqueData.empty())) != null) {
                throw new IllegalArgumentException("Duplicate typed input pin: " + pinId);
            }
        }
        return Map.copyOf(result);
    }

    private static List<GraphConnection> resolveConnections(RawLegacyGraphAdmission.Admitted admitted,
                                                             Map<String, ResolvedNode> nodes) {
        Map<NodeInstanceId, ResolvedNode> byId = new HashMap<>();
        nodes.values().forEach(node -> byId.put(node.graphNode().instanceId(), node));
        List<GraphConnection> result = new ArrayList<>();
        for (RawLegacyGraphAdmission.AdmittedConnection connection : admitted.connections()) {
            ResolvedNode source = requireNode(byId, connection.sourceNode());
            ResolvedNode target = requireNode(byId, connection.targetNode());
            PinId sourcePin = PinId.of(connection.sourcePin());
            PinId targetPin = PinId.of(connection.targetPin());
            requireDirection(source.definition().requirePin(sourcePin), CatalogNodeDescriptor.Direction.OUTPUT);
            requireDirection(target.definition().requirePin(targetPin), CatalogNodeDescriptor.Direction.INPUT);
            result.add(new GraphConnection(connection.identity(), new GraphEndpoint(connection.sourceNode(), sourcePin),
                new GraphEndpoint(connection.targetNode(), targetPin)));
        }
        result.sort(Comparator.comparing(GraphConnection::connectionId));
        return List.copyOf(result);
    }

    private static ResolvedNode requireNode(Map<NodeInstanceId, ResolvedNode> nodes, NodeInstanceId identity) {
        ResolvedNode node = nodes.get(identity);
        if (node == null) {
            throw new IllegalArgumentException("Connection node was not materialized: " + identity);
        }
        return node;
    }

    private static void requireDirection(CatalogNodeDescriptor.Pin pin, CatalogNodeDescriptor.Direction direction) {
        if (pin.direction() != direction) {
            throw unsupported("connection-pin-direction-mismatch:" + pin.id().canonicalText());
        }
    }

    private static boolean empty(JsonValue value) {
        return value instanceof JsonArray array && array.values().isEmpty()
            || value instanceof JsonObject object && object.values().isEmpty();
    }

    private static String string(JsonObject value, String field) {
        JsonValue element = value.value(field);
        if (!(element instanceof JsonString string) || string.value().isBlank()) {
            throw new IllegalArgumentException("Legacy node requires string field: " + field);
        }
        return string.value();
    }

    private static int integer(JsonObject value, String field) {
        JsonValue element = value.value(field);
        if (!(element instanceof JsonNumber number)) {
            throw new IllegalArgumentException("Legacy node requires numeric field: " + field);
        }
        try {
            return number.value().intValueExact();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Legacy node version must be an integer", exception);
        }
    }

    private static double number(JsonObject value, String field) {
        JsonValue element = value.value(field);
        if (!(element instanceof JsonNumber number)) {
            throw new IllegalArgumentException("Legacy node requires numeric field: " + field);
        }
        double result = number.value().doubleValue();
        if (!Double.isFinite(result)) {
            throw new IllegalArgumentException("Legacy node position must be finite");
        }
        return result;
    }

    private static Quarantined quarantine(RawLegacyGraphAdmission.Admitted admitted, String reason) {
        return new Quarantined(stableReason(reason), admitted.canonicalRaw(), admitted.canonicalInner());
    }

    private static String stableReason(Throwable exception) {
        String message = exception.getMessage();
        return stableReason(message == null || message.isBlank() ? "legacy-materialization-failed" : message);
    }

    private static String stableReason(String reason) {
        return reason == null || reason.isBlank() ? "legacy-materialization-failed" : reason.strip();
    }

    private static UnsupportedLegacyState unsupported(String reason) {
        return new UnsupportedLegacyState(reason);
    }

    public sealed interface Result permits Materialized, Quarantined {
    }

    public record Materialized(GraphDocument document, String canonicalRaw) implements Result {
        public Materialized {
            document = Objects.requireNonNull(document, "document");
            canonicalRaw = Objects.requireNonNull(canonicalRaw, "canonicalRaw");
        }
    }

    public record Quarantined(String reason, String canonicalRaw, String canonicalInner) implements Result {
        public Quarantined {
            reason = Objects.requireNonNull(reason, "reason");
            canonicalRaw = Objects.requireNonNull(canonicalRaw, "canonicalRaw");
            canonicalInner = Objects.requireNonNull(canonicalInner, "canonicalInner");
        }

        public String recordId() {
            return UuidIdentity.deterministic(UuidIdentity.MIGRATION_NAMESPACE, "legacy-flow-quarantine-v1",
                reason + "\u0000" + canonicalRaw).toString();
        }
    }

    private record ResolvedNode(CoreGraphProjectionContext.ResolvedNode definition, GraphNode graphNode) {
    }

    private static final class UnsupportedLegacyState extends RuntimeException {
        private UnsupportedLegacyState(String message) {
            super(message);
        }
    }
}
