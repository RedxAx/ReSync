package restudio.resync.flow;

import restudio.flow.data.FlowConnection;
import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowNode;
import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.diagnostic.Diagnostic;
import restudio.resync.flow.diagnostic.DiagnosticPhase;
import restudio.resync.flow.diagnostic.DiagnosticSeverity;
import restudio.resync.flow.graph.GraphConnection;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.graph.GraphEndpoint;
import restudio.resync.flow.graph.GraphNode;
import restudio.resync.flow.graph.OpaqueData;
import restudio.resync.flow.graph.PinValue;
import restudio.resync.flow.function.FunctionSourceDocument;
import restudio.resync.flow.function.FunctionSourceMaterializer;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ConnectionId;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.NodeInstanceId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.PinId;
import restudio.resync.flow.type.TypedValue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public final class CompiledGraphMaterializer {
    private static final OwnerId RESOURCE_OWNER = OwnerId.of("restudio.resync");
    private static final Set<String> GRAPH_TYPES = Set.of("flow", "function", "command", "custom_content");

    public Result materialize(GraphDocument document) {
        return materialize(document, null);
    }

    public Result materialize(GraphDocument document, FunctionSourceDocument functionSource) {
        if (document == null) {
            return new Result(null, List.of(diagnostic((GraphDocument) null,
                "A complete Core graph document is required", Map.of())));
        }
        String resourceType = document.resource().resourceType().value();
        if (!RESOURCE_OWNER.equals(document.resource().owner()) || !GRAPH_TYPES.contains(resourceType)) {
            return new Result(null, List.of(diagnostic(document,
                "The Core graph resource owner and type must match the authoritative graph envelope", Map.of(
                    "expectedOwner", RESOURCE_OWNER.value(),
                    "actualOwner", document.resource().owner().value(),
                    "expectedTypes", GRAPH_TYPES,
                    "actualType", resourceType))));
        }
        boolean function = "function".equals(resourceType);
        if (functionSource != null && !function) {
            return new Result(null, List.of(diagnostic(document,
                "A Function source document can only accompany a function graph", Map.of(
                    "field", "functionSource",
                    "boundary", FunctionSourceMaterializer.BOUNDARY,
                    "resourceType", document.resource().resourceType().value()))));
        }
        if (!function) {
            return new Result(document, List.of());
        }
        if (functionSource == null) {
            return new Result(null, new FunctionSourceMaterializer().validate(null).diagnostics().stream()
                .map(value -> value.diagnostic())
                .toList());
        }
        if (!functionSource.graph().resource().equals(document.resource())
            || functionSource.graph().revision() != document.revision()) {
            return new Result(null, List.of(diagnostic(document,
                "The Function source document must match the graph resource identity and revision", Map.of(
                    "field", "functionSource",
                    "boundary", FunctionSourceMaterializer.BOUNDARY,
                    "expectedResource", document.resource().canonicalText(),
                    "actualResource", functionSource.graph().resource().canonicalText(),
                    "expectedRevision", document.revision(),
                    "actualRevision", functionSource.graph().revision()))));
        }
        if (!functionSource.graph().checksum().equals(document.checksum())) {
            return new Result(null, List.of(diagnostic(document,
                "The Function source document must contain the exact graph source", Map.of(
                    "field", "functionSource",
                    "boundary", FunctionSourceMaterializer.BOUNDARY,
                    "expectedGraphChecksum", document.checksum().canonicalText(),
                    "actualGraphChecksum", functionSource.graph().checksum().canonicalText()))));
        }
        FunctionSourceMaterializer.Result validation;
        try {
            validation = new FunctionSourceMaterializer().validate(functionSource);
        } catch (RuntimeException failure) {
            return new Result(null, List.of(diagnostic(document,
                "The matching Function source document could not be validated", Map.of(
                    "field", "functionSource",
                    "boundary", FunctionSourceMaterializer.BOUNDARY,
                    "failureType", failure.getClass().getName(),
                    "failure", value(failure.getMessage())))));
        }
        if (!validation.materialized()) {
            return new Result(null, validation.diagnostics().stream()
                .map(value -> value.diagnostic())
                .toList());
        }
        return new Result(document, List.of());
    }

    public Result materialize(FlowGraph graph, CompiledGraphMetadata metadata) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        if (graph == null || metadata == null) {
            diagnostics.add(diagnostic(metadata, graph, "A complete compiled graph boundary is required", Map.of()));
            return new Result(null, diagnostics);
        }
        if (!metadata.resource().id().equals(graph.getId())) {
            diagnostics.add(diagnostic(metadata, graph, "The canonical resource ID does not match the legacy graph ID", Map.of(
                "legacyGraphId", value(graph.getId()),
                "canonicalResource", metadata.resource().canonicalText())));
        }
        if (!GRAPH_TYPES.contains(graph.getResourceType())
            || !RESOURCE_OWNER.equals(metadata.resource().owner())
            || !metadata.resource().resourceType().value().equals(value(graph.getResourceType()))) {
            diagnostics.add(diagnostic(metadata, graph, "The compiled resource owner and type must match the authoritative graph envelope", Map.of(
                "expectedOwner", RESOURCE_OWNER.value(),
                "actualOwner", metadata.resource().owner().value(),
                "expectedType", value(graph.getResourceType()),
                "actualType", metadata.resource().resourceType().value())));
        }
        if (graph.isFunction() || "function".equals(graph.getResourceType())) {
            diagnostics.add(diagnostic(metadata, graph, "Function graphs require an explicit immutable Core Function source document before compiled execution", Map.of(
                "field", "function",
                "boundary", "function-source-v1",
                "functionSourceProvided", metadata.functionSource() != null,
                "requiredMetadata", List.of(
                    "typedFunctionLocator",
                    "functionRevision",
                    "immutableParameterIds",
                    "typedParameterDefaults",
                    "immutableBodyNodeIds",
                    "immutablePinIds",
                    "typedBodyLiterals",
                    "durableConnectionIds",
                    "requiredCapabilityIdentities",
                    "runtimeBindingFingerprint"),
                "legacyMetadataAvailable", Map.of(
                    "functionMarker", graph.isFunction(),
                    "resourceType", value(graph.getResourceType()),
                    "inputParameterCount", graph.getFunctionInputs().size(),
                    "outputParameterCount", graph.getFunctionOutputs().size(),
                    "nodeCount", graph.getNodes().size(),
                    "connectionCount", graph.getConnections().size()))));
        }
        if (!graph.getLocalVariables().isEmpty()) {
            diagnostics.add(diagnostic(metadata, graph, "Local variables are not part of the supported simple graph boundary", Map.of("field", "localVariables")));
        }
        if (!graph.getFunctionInputs().isEmpty() || !graph.getFunctionOutputs().isEmpty()) {
            diagnostics.add(diagnostic(metadata, graph, "Function signatures are not part of the supported simple graph boundary", Map.of("field", "functionSignature")));
        }
        if (!graph.getEditorPassthroughs().isEmpty()) {
            diagnostics.add(diagnostic(metadata, graph, "Editor passthroughs are not executable graph structure", Map.of("field", "editorPassthroughs")));
        }
        if (!graph.getContentProperties().isEmpty()) {
            diagnostics.add(diagnostic(metadata, graph, "Unclassified content properties cannot be discarded during compiled materialization", Map.of("field", "contentProperties")));
        }
        Map<String, FlowNode> nodes = graph.getNodes();
        if (nodes.isEmpty()) {
            diagnostics.add(diagnostic(metadata, graph, "The compiled graph must contain at least one node", Map.of("field", "nodes")));
        }
        if (!metadata.nodeInstances().keySet().equals(nodes.keySet())) {
            diagnostics.add(diagnostic(metadata, graph, "Node instance metadata must exactly cover the graph nodes", Map.of(
                "metadataNodeCount", metadata.nodeInstances().size(),
                "graphNodeCount", nodes.size())));
        }
        if (!metadata.definitions().keySet().equals(nodes.keySet())) {
            diagnostics.add(diagnostic(metadata, graph, "Qualified node definition metadata must exactly cover the graph nodes", Map.of(
                "metadataDefinitionCount", metadata.definitions().size(),
                "graphNodeCount", nodes.size())));
        }
        if (!metadata.handlerConfigCanonical().keySet().equals(nodes.keySet())) {
            diagnostics.add(diagnostic(metadata, graph, "Handler configuration metadata must exactly cover the graph nodes", Map.of(
                "metadataHandlerConfigCount", metadata.handlerConfigCanonical().size(),
                "graphNodeCount", nodes.size())));
        }
        List<GraphNode> materializedNodes = new ArrayList<>();
        if (diagnostics.isEmpty()) {
            nodes.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> materializeNode(entry.getKey(), entry.getValue(), metadata, materializedNodes, diagnostics));
        }
        List<GraphConnection> materializedConnections = new ArrayList<>();
        materializeConnections(graph, metadata, materializedConnections, diagnostics);
        if (!diagnostics.isEmpty()) {
            return new Result(null, diagnostics);
        }
        try {
            GraphDocument document = new GraphDocument(
                metadata.resource(),
                graph.getResourceRevision(),
                metadata.catalogBinding(),
                materializedNodes,
                materializedConnections
            );
            return new Result(document, List.of());
        } catch (RuntimeException failure) {
            diagnostics.add(diagnostic(metadata, graph, "The complete graph boundary could not be materialized", Map.of(
                "failureType", failure.getClass().getName(),
                "failure", value(failure.getMessage()))));
            return new Result(null, diagnostics);
        }
    }

    private static void materializeNode(String legacyNodeId, FlowNode node, CompiledGraphMetadata metadata,
                                        List<GraphNode> materialized, List<Diagnostic> diagnostics) {
        if (node == null) {
            diagnostics.add(diagnostic(metadata, null, "The graph contains a null node", Map.of("nodeId", legacyNodeId)));
            return;
        }
        NodeInstanceId instanceId = metadata.nodeInstances().get(legacyNodeId);
        ContractRef<NodeId> definition = metadata.definitions().get(legacyNodeId);
        if (instanceId == null || definition == null) {
            diagnostics.add(diagnostic(metadata, null, "A node is missing complete immutable identity metadata", Map.of("nodeId", legacyNodeId)));
            return;
        }
        if (node.getType() == null || !node.getType().equals(definition.id().value())) {
            diagnostics.add(diagnostic(metadata, null, "Legacy node type must exactly match its qualified compiled definition", Map.of(
                "nodeId", legacyNodeId,
                "legacyType", value(node.getType()),
                "definition", definition.canonicalText())));
            return;
        }
        if (node.getVersion() < 1) {
            diagnostics.add(diagnostic(metadata, null, "Node definition version must be positive", Map.of("nodeId", legacyNodeId)));
            return;
        }
        String expectedHandlerConfig = metadata.handlerConfigCanonical().get(legacyNodeId);
        if (expectedHandlerConfig == null) {
            diagnostics.add(diagnostic(metadata, null, "A node is missing explicit canonical handler configuration metadata", Map.of(
                "nodeId", legacyNodeId,
                "field", "handlerConfig")));
            return;
        }
        Map<String, Object> authoredConfig = node.getHandlerConfigValues();
        String persistedHandlerConfig;
        try {
            persistedHandlerConfig = CanonicalJson.canonicalize(ItemStackPropertySelector.canonicalHandlerConfig(
                node.getType(), authoredConfig));
        } catch (RuntimeException failure) {
            diagnostics.add(diagnostic(metadata, null, "Persisted handler configuration is not canonical JSON", Map.of(
                "nodeId", legacyNodeId,
                "field", "handlerConfig",
                "failureType", failure.getClass().getName(),
                "failure", value(failure.getMessage()))));
            return;
        }
        if (!expectedHandlerConfig.equals(persistedHandlerConfig)) {
            diagnostics.add(diagnostic(metadata, null, "Persisted handler configuration does not exactly match the active node definition", Map.of(
                "nodeId", legacyNodeId,
                "field", "handlerConfig",
                "expected", expectedHandlerConfig,
                "actual", persistedHandlerConfig)));
            return;
        }
        Map<PinId, PinValue> values = new LinkedHashMap<>();
        Map<String, Object> inputValues = ItemStackPropertySelector.effectiveInputs(
            node.getType(), node.getInputValues(), authoredConfig);
        Set<CompiledGraphMetadata.PinAddress> expected = inputValues.keySet().stream()
            .map(pin -> new CompiledGraphMetadata.PinAddress(legacyNodeId, pin))
            .collect(Collectors.toSet());
        Set<CompiledGraphMetadata.PinAddress> supplied = metadata.inputValues().keySet().stream()
            .filter(address -> address.nodeId().equals(legacyNodeId))
            .collect(Collectors.toSet());
        if (!expected.equals(supplied)) {
            diagnostics.add(diagnostic(metadata, null, "Typed input metadata must exactly cover every configured legacy input", Map.of(
                "nodeId", legacyNodeId,
                "configuredInputCount", expected.size(),
                "typedInputCount", supplied.size())));
            return;
        }
        for (CompiledGraphMetadata.PinAddress address : expected) {
            PinId pinId = metadata.pins().get(address);
            TypedValue value = metadata.inputValues().get(address);
            if (pinId == null || value == null) {
                diagnostics.add(diagnostic(metadata, null, "Configured input is missing explicit pin or typed value metadata", Map.of(
                    "nodeId", legacyNodeId,
                    "pin", address.pin())));
                continue;
            }
            if (values.containsKey(pinId)) {
                diagnostics.add(diagnostic(metadata, null, "Distinct persisted input names cannot map to one canonical pin identity", Map.of(
                    "nodeId", legacyNodeId,
                    "pin", address.pin(),
                    "pinId", pinId.value())));
                return;
            }
            values.put(pinId, new PinValue(pinId, value));
        }
        materialized.add(new GraphNode(instanceId, definition, node.getVersion(), null, values, Map.of(), List.of(), List.of(), null, node.getX(), node.getY(), OpaqueData.empty()));
    }

    private static void materializeConnections(FlowGraph graph, CompiledGraphMetadata metadata,
                                                List<GraphConnection> materialized, List<Diagnostic> diagnostics) {
        List<FlowConnection> connections = graph.getConnections();
        Set<CompiledGraphMetadata.ConnectionAddress> addresses = new HashSet<>();
        for (FlowConnection connection : connections) {
            if (connection == null) {
                diagnostics.add(diagnostic(metadata, graph, "The graph contains a null connection", Map.of("field", "connections")));
                continue;
            }
            if (connection.getEditorSourceNodeId() != null || connection.getEditorSourcePin() != null) {
                diagnostics.add(diagnostic(metadata, graph, "Editor-only connection metadata cannot be silently mapped into execution", Map.of("field", "editorSource")));
                continue;
            }
            if (connection.getSourceNodeId() == null || connection.getSourcePin() == null
                || connection.getTargetNodeId() == null || connection.getTargetPin() == null) {
                diagnostics.add(diagnostic(metadata, graph, "Connection endpoints require explicit node and pin identities", Map.of("field", "connectionEndpoint")));
                continue;
            }
            CompiledGraphMetadata.ConnectionAddress address = new CompiledGraphMetadata.ConnectionAddress(
                connection.getSourceNodeId(), connection.getSourcePin(), connection.getTargetNodeId(), connection.getTargetPin());
            if (!addresses.add(address)) {
                diagnostics.add(diagnostic(metadata, graph, "Duplicate connection endpoints cannot receive one canonical connection identity", Map.of(
                    "connection", address.canonicalText())));
                continue;
            }
            ConnectionId connectionId = metadata.connections().get(address);
            PinId sourcePin = metadata.pins().get(new CompiledGraphMetadata.PinAddress(connection.getSourceNodeId(), connection.getSourcePin()));
            PinId targetPin = metadata.pins().get(new CompiledGraphMetadata.PinAddress(connection.getTargetNodeId(), connection.getTargetPin()));
            NodeInstanceId sourceNode = metadata.nodeInstances().get(connection.getSourceNodeId());
            NodeInstanceId targetNode = metadata.nodeInstances().get(connection.getTargetNodeId());
            if (connectionId == null || sourcePin == null || targetPin == null || sourceNode == null || targetNode == null) {
                diagnostics.add(diagnostic(metadata, graph, "Connection metadata must provide explicit immutable IDs for both endpoints", Map.of(
                    "connection", address.canonicalText())));
                continue;
            }
            materialized.add(new GraphConnection(connectionId,
                new GraphEndpoint(sourceNode, sourcePin),
                new GraphEndpoint(targetNode, targetPin)));
        }
        if (metadata.connections().size() != addresses.size()) {
            diagnostics.add(diagnostic(metadata, graph, "Connection metadata must exactly cover every legacy connection", Map.of(
                "metadataConnectionCount", metadata.connections().size(),
                "graphConnectionCount", addresses.size())));
        }
    }

    private static Diagnostic diagnostic(CompiledGraphMetadata metadata, FlowGraph graph, String reason, Map<String, ?> evidence) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("reason", reason);
        if (graph != null && graph.getId() != null) {
            values.put("legacyGraphId", graph.getId());
        }
        if (metadata != null) {
            values.put("snapshotId", metadata.snapshotId().canonicalText());
            values.put("catalogBinding", metadata.catalogBinding().canonicalText());
            values.putAll(evidence);
        }
        String identity = "compiled-materializer\u0000" + (metadata == null ? "" : metadata.snapshotId().canonicalText()) + "\u0000" + reason;
        return Diagnostic.builder("GRAPH.OPAQUE_UNAVAILABLE", DiagnosticSeverity.ERROR, DiagnosticPhase.CAPABILITY, "graph")
            .messageKey(ContractRef.of(OwnerId.of("resync"), CapabilityId.of("graph-opaque-unavailable")))
            .resource(metadata == null ? null : metadata.resource())
            .catalogGeneration(metadata == null ? null : metadata.catalogBinding().generation())
            .evidence(values)
            .correlationId(UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)))
            .build();
    }

    private static Diagnostic diagnostic(GraphDocument graph, String reason, Map<String, ?> evidence) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("reason", reason);
        if (graph != null) {
            values.put("resource", graph.resource().canonicalText());
            values.put("revision", graph.revision());
        }
        values.putAll(evidence);
        String identity = "compiled-materializer\u0000" + (graph == null ? "" : graph.resource().canonicalText())
            + "\u0000" + (graph == null ? "" : graph.revision()) + "\u0000" + reason;
        var builder = Diagnostic.builder("GRAPH.OPAQUE_UNAVAILABLE", DiagnosticSeverity.ERROR, DiagnosticPhase.CAPABILITY, "graph")
            .messageKey(ContractRef.of(OwnerId.of("resync"), CapabilityId.of("graph-opaque-unavailable")))
            .evidence(values)
            .correlationId(UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)));
        if (graph != null) {
            builder.resource(graph.resource()).catalogGeneration(graph.catalogBinding().generation());
        }
        return builder.build();
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }

    public record Result(GraphDocument document, List<Diagnostic> diagnostics) {
        public Result {
            diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "Materialization Diagnostics Are Required"));
            if (document != null && !diagnostics.isEmpty()) {
                throw new IllegalArgumentException("A Materialized Graph Cannot Contain Diagnostics");
            }
            if (document == null && diagnostics.isEmpty()) {
                throw new IllegalArgumentException("An Unsupported Graph Requires Diagnostics");
            }
        }

        public boolean materialized() {
            return document != null;
        }
    }
}
