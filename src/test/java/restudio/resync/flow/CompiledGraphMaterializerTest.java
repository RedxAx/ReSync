package restudio.resync.flow;

import org.junit.jupiter.api.Test;
import restudio.flow.data.FlowConnection;
import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowNode;
import restudio.flow.data.FlowSerializer;
import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.function.FunctionLocator;
import restudio.resync.flow.function.FunctionRevision;
import restudio.resync.flow.function.FunctionSignature;
import restudio.resync.flow.function.FunctionSourceDocument;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.graph.GraphConnection;
import restudio.resync.flow.graph.GraphEndpoint;
import restudio.resync.flow.graph.GraphNode;
import restudio.resync.flow.graph.OpaqueData;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.ConnectionId;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.NodeInstanceId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.PinId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.identity.SnapshotId;
import restudio.resync.flow.runtime.RuntimeAuthority;
import restudio.resync.flow.runtime.RuntimeBindingManifest;
import restudio.resync.flow.runtime.RuntimeBindingRegistry;
import restudio.resync.flow.runtime.CompiledRuntimeContext;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypeReference;
import restudio.resync.flow.type.TypedValue;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompiledGraphMaterializerTest {
    private static final ServerId SERVER = new ServerId(UUID.fromString("11111111-1111-4111-8111-111111111111"));
    private static final CatalogBinding BINDING = new CatalogBinding(7, new ContentHash("0".repeat(64)), new ContentHash("1".repeat(64)));
    private static final SnapshotId SNAPSHOT = new SnapshotId(UUID.fromString("22222222-2222-5222-8222-222222222222"));
    private static final NodeInstanceId SOURCE = NodeInstanceId.of(UUID.fromString("33333333-3333-4333-8333-333333333333"));
    private static final NodeInstanceId TARGET = NodeInstanceId.of(UUID.fromString("44444444-4444-4444-8444-444444444444"));

    @Test
    void coreMaterializationReturnsTheExistingGraphWithOpaqueDataAndExplicitIds() {
        GraphNode source = new GraphNode(SOURCE, ContractRef.of(new OwnerId("builtin"), NodeId.of("source")), 1,
            null, Map.of(), Map.of(), List.of(), List.of(), null, 4, 8,
            OpaqueData.of(Map.of("nodeFuture", Map.of("keep", true))));
        GraphNode target = new GraphNode(TARGET, ContractRef.of(new OwnerId("builtin"), NodeId.of("target")), 1,
            null, Map.of(), Map.of(), List.of(), List.of(), null, 12, 16, OpaqueData.empty());
        ConnectionId connectionId = ConnectionId.deterministic("core-connection");
        GraphConnection connection = new GraphConnection(connectionId,
            new GraphEndpoint(SOURCE, PinId.of("result")),
            new GraphEndpoint(TARGET, PinId.of("input")),
            OpaqueData.of(Map.of("connectionFuture", Map.of("keep", true))));
        GraphDocument document = new GraphDocument(new CatalogVersion(1, 0), resource("flow", "core-flow"), 4,
            BINDING, Set.of(), List.of(source, target), List.of(connection), List.of(), List.of(),
            OpaqueData.of(Map.of("rootFuture", Map.of("keep", true))));

        CompiledGraphMaterializer.Result result = new CompiledGraphMaterializer().materialize(document);

        assertTrue(result.materialized(), result.diagnostics()::toString);
        assertSame(document, result.document());
        assertEquals(SOURCE, result.document().nodes().getFirst().instanceId());
        assertEquals(connectionId, result.document().connections().getFirst().connectionId());
        assertEquals(document.unknown(), result.document().unknown());
        assertEquals(source.unknown(), result.document().nodes().getFirst().unknown());
        assertEquals(connection.unknown(), result.document().connections().getFirst().unknown());
        assertFalse(result.document().unknown().contains("compiledSnapshotId"));
    }

    @Test
    void coreFunctionMaterializationRequiresAValidatedMatchingSourceDocument() {
        ServerResourceLocator resource = resource("function", "core-function");
        GraphDocument graph = new GraphDocument(resource, 9, BINDING, List.of(), List.of());
        FunctionSignature signature = new FunctionSignature(new FunctionLocator(resource), new FunctionRevision(9), List.of(), List.of());
        FunctionSourceDocument source = new FunctionSourceDocument(signature, graph, OpaqueData.of(Map.of("sourceFuture", true)));

        CompiledGraphMaterializer materializer = new CompiledGraphMaterializer();
        CompiledGraphMaterializer.Result accepted = materializer.materialize(graph, source);
        CompiledGraphMaterializer.Result missing = materializer.materialize(graph);
        GraphDocument different = new GraphDocument(graph.schemaVersion(), graph.resource(), graph.revision(), graph.catalogBinding(),
            graph.requiredCapabilities(), graph.nodes(), graph.connections(), graph.variables(), graph.functions(),
            OpaqueData.of(Map.of("different", true)));
        FunctionSourceDocument mismatched = new FunctionSourceDocument(signature, different);
        CompiledGraphMaterializer.Result rejected = materializer.materialize(graph, mismatched);

        assertTrue(accepted.materialized(), accepted.diagnostics()::toString);
        assertSame(graph, accepted.document());
        assertFalse(missing.materialized());
        assertEquals("FUNCTION.SOURCE_MISSING", missing.diagnostics().getFirst().code());
        assertFalse(rejected.materialized());
        assertEquals("GRAPH.OPAQUE_UNAVAILABLE", rejected.diagnostics().getFirst().code());
        assertEquals("functionSource", rejected.diagnostics().getFirst().evidence().get("field"));
    }

    @Test
    void coreMaterializationRejectsForeignOwnerAndUnsupportedType() {
        GraphDocument foreignOwner = new GraphDocument(resource("foreign.owner", "flow", "foreign-owner"), 4, BINDING,
            List.of(), List.of());
        GraphDocument unsupportedType = new GraphDocument(resource("restudio.resync", "unsupported", "unsupported-type"), 4, BINDING,
            List.of(), List.of());
        CompiledGraphMaterializer materializer = new CompiledGraphMaterializer();

        CompiledGraphMaterializer.Result foreignResult = materializer.materialize(foreignOwner);
        CompiledGraphMaterializer.Result typeResult = materializer.materialize(unsupportedType);

        assertFalse(foreignResult.materialized());
        assertEquals("foreign.owner", foreignResult.diagnostics().getFirst().evidence().get("actualOwner"));
        assertFalse(typeResult.materialized());
        assertEquals("unsupported", typeResult.diagnostics().getFirst().evidence().get("actualType"));
    }

    @Test
    void completeMetadataMaterializesStableCanonicalGraphIdentity() {
        FlowGraph graph = graph();
        CompiledGraphMetadata metadata = metadata(true);

        CompiledGraphMaterializer.Result first = new CompiledGraphMaterializer().materialize(graph, metadata);
        CompiledGraphMaterializer.Result second = new CompiledGraphMaterializer().materialize(graph.copy(), metadata);

        assertTrue(first.materialized(), first.diagnostics()::toString);
        assertTrue(second.materialized(), second.diagnostics()::toString);
        GraphDocument document = first.document();
        assertNotNull(document);
        assertEquals(metadata.resource(), document.resource());
        assertEquals(metadata.catalogBinding(), document.catalogBinding());
        assertEquals(document.checksum(), second.document().checksum());
        assertEquals(SOURCE, document.nodes().getFirst().instanceId());
        assertEquals(ConnectionId.deterministic("simple-connection"), document.connections().getFirst().connectionId());
        assertFalse(document.unknown().contains("compiledSnapshotId"));
    }

    @Test
    void incompleteMetadataFailsClosedWithStructuredDiagnostic() {
        CompiledGraphMaterializer.Result result = new CompiledGraphMaterializer().materialize(graph(), metadata(false));

        assertFalse(result.materialized());
        assertTrue(result.diagnostics().stream().anyMatch(diagnostic -> diagnostic.code().equals("GRAPH.OPAQUE_UNAVAILABLE")));
        assertTrue(result.diagnostics().getFirst().toMap().containsKey("resource"));
    }

    @Test
    void materializesExactCanonicalHandlerConfigurationWithoutWritingItIntoTheGraph() {
        FlowGraph graph = graph();
        graph.getNodes().get("source").setHandlerConfig(Map.of("operation", "run"));
        CompiledGraphMetadata metadata = metadata(true, Map.of(
            "source", CanonicalJson.canonicalize(Map.of("operation", "run")),
            "target", "{}"));

        CompiledGraphMaterializer.Result result = new CompiledGraphMaterializer().materialize(graph, metadata);

        assertTrue(result.materialized(), result.diagnostics()::toString);
        assertEquals("{\"operation\":\"run\"}", metadata.handlerConfigCanonical().get("source"));
        assertFalse(result.document().nodes().getFirst().unknown().fields().containsKey("handlerConfig"));
    }

    @Test
    void rejectsHandlerConfigurationThatDiffersFromTheActiveCanonicalDefinition() {
        FlowGraph graph = graph();
        graph.getNodes().get("source").setHandlerConfig(Map.of("operation", "tampered"));
        CompiledGraphMetadata metadata = metadata(true, Map.of(
            "source", CanonicalJson.canonicalize(Map.of("operation", "run")),
            "target", "{}"));

        CompiledGraphMaterializer.Result result = new CompiledGraphMaterializer().materialize(graph, metadata);

        assertFalse(result.materialized());
        assertTrue(result.diagnostics().stream().anyMatch(diagnostic -> "handlerConfig".equals(diagnostic.evidence().get("field"))));
    }

    @Test
    void rejectsDistinctPersistedInputsThatMapToOneCanonicalPin() {
        FlowGraph graph = graph();
        graph.getNodes().get("source").setInputValues(Map.of("first", "hello", "second", "world"));
        CompiledGraphMetadata base = metadata(true);
        Map<CompiledGraphMetadata.PinAddress, PinId> pins = new LinkedHashMap<>(base.pins());
        pins.remove(new CompiledGraphMetadata.PinAddress("source", "value"));
        pins.put(new CompiledGraphMetadata.PinAddress("source", "first"), PinId.of("shared"));
        pins.put(new CompiledGraphMetadata.PinAddress("source", "second"), PinId.of("shared"));
        Map<CompiledGraphMetadata.PinAddress, TypedValue> inputValues = Map.of(
            new CompiledGraphMetadata.PinAddress("source", "first"), typedString("hello"),
            new CompiledGraphMetadata.PinAddress("source", "second"), typedString("world"));
        CompiledGraphMetadata metadata = new CompiledGraphMetadata(base.resource(), base.catalogBinding(), base.snapshotId(),
            base.nodeInstances(), base.definitions(), pins, base.connections(), inputValues, base.handlerConfigCanonical());

        CompiledGraphMaterializer.Result result = new CompiledGraphMaterializer().materialize(graph, metadata);

        assertFalse(result.materialized());
        assertTrue(result.diagnostics().stream().anyMatch(diagnostic -> "shared".equals(diagnostic.evidence().get("pinId"))));
    }

    @Test
    void persistedFunctionGraphFailsClosedWithStableDiagnostic() {
        CompiledGraphMaterializer materializer = new CompiledGraphMaterializer();
        CompiledGraphMaterializer.Result first = materializer.materialize(persistedFunctionGraph(), metadata(true, "function", "persisted-function"));
        CompiledGraphMaterializer.Result second = materializer.materialize(persistedFunctionGraph(), metadata(true, "function", "persisted-function"));

        assertFalse(first.materialized());
        assertFalse(second.materialized());
        assertEquals(1, first.diagnostics().size());
        assertEquals(1, second.diagnostics().size());
        assertEquals("GRAPH.OPAQUE_UNAVAILABLE", first.diagnostics().getFirst().code());
        assertEquals("function", first.diagnostics().getFirst().evidence().get("field"));
        assertEquals("function-source-v1", first.diagnostics().getFirst().evidence().get("boundary"));
        assertEquals(false, first.diagnostics().getFirst().evidence().get("functionSourceProvided"));
        assertTrue(((List<?>) first.diagnostics().getFirst().evidence().get("requiredMetadata")).contains("immutableParameterIds"));
        assertEquals(2, ((Map<?, ?>) first.diagnostics().getFirst().evidence().get("legacyMetadataAvailable")).get("nodeCount"));
        assertEquals(first.diagnostics().getFirst().toMap(), second.diagnostics().getFirst().toMap());
    }

    @Test
    void compiledFunctionMaterializationRejectionDoesNotFallbackToLegacyExecution() {
        AtomicBoolean legacyInvoked = new AtomicBoolean();
        HandlerRegistry handlers = new HandlerRegistry();
        handlers.register("source", (context, node) -> legacyInvoked.set(true));
        FlowExecutor executor = new FlowExecutor(handlers, new TypeAdapterRegistry(), Map.of());
        CompiledGraphMetadata metadata = metadata(true, "function", "persisted-function");
        CompiledCoreFlowExecutionBridge bridge = new CompiledCoreFlowExecutionBridge(
            () -> {
                throw new AssertionError("Catalog resolution must not follow Function materialization rejection");
            },
            () -> {
                throw new AssertionError("Runtime binding resolution must not follow Function materialization rejection");
            },
            new RuntimeBindingRegistry(),
            RuntimeAuthority.anonymous());
        FlowExecutionBridge.MappingContext mapping = new FlowExecutionBridge.MappingContext(
            metadata.resource(), metadata.catalogBinding(), metadata.snapshotId(),
            Map.of("source", NodeId.of("source"), "target", NodeId.of("target")),
            Map.of("value", PinId.of("value"), "result", PinId.of("result"), "input", PinId.of("input")),
            List.of());
        FlowExecutor.CompiledExecutionAuthority authority = new FlowExecutor.CompiledExecutionAuthority(
            CompiledCoreFlowExecutionBridge.authorityHash(), metadata.catalogBinding().catalogChecksum(), metadata.catalogBinding().bindingManifestHash());

        try {
            CompletionException thrown = assertThrows(CompletionException.class,
                () -> executor.executeCompiled(persistedFunctionGraph(), "source", null, null, Map.of(), mapping, metadata, authority, bridge).join());
            FlowExecutor.FlowExecutionException failure = (FlowExecutor.FlowExecutionException) thrown.getCause();

            assertEquals("CORE_EXECUTION_UNSUPPORTED", failure.getCode());
            assertFalse(legacyInvoked.get());
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void configuredCompiledBridgeRejectsMissingMetadataWithoutLegacyFallback() {
        CompiledCoreFlowExecutionBridge bridge = new CompiledCoreFlowExecutionBridge(
            () -> null,
            () -> RuntimeBindingManifest.create(List.of(), List.of(), Map.of(), List.of()),
            new RuntimeBindingRegistry(),
            RuntimeAuthority.anonymous());

        FlowExecutionBridge.Result result = bridge.execute(new FlowExecutionBridge.Context(graph(), "source", null, null, Map.of())).toCompletableFuture().join();

        assertEquals(FlowExecutionBridge.Status.UNSUPPORTED, result.status());
        assertTrue(result.diagnostics().stream().anyMatch(diagnostic -> diagnostic.code().equals("GRAPH.OPAQUE_UNAVAILABLE")));
    }

    @Test
    void configuredCompiledBridgeRejectsUnrepresentableLegacyExecutionContext() {
        CompiledCoreFlowExecutionBridge bridge = new CompiledCoreFlowExecutionBridge(
            () -> null,
            () -> RuntimeBindingManifest.create(List.of(), List.of(), Map.of(), List.of()),
            new RuntimeBindingRegistry(),
            RuntimeAuthority.anonymous());

        FlowExecutionBridge.Result result = bridge.execute(new FlowExecutionBridge.Context(
            graph(), "source", null, null, Map.of("event.value", "ready"))).toCompletableFuture().join();

        assertEquals(FlowExecutionBridge.Status.UNSUPPORTED, result.status());
        assertTrue(result.diagnostics().stream().anyMatch(diagnostic -> diagnostic.code().equals("GRAPH.OPAQUE_UNAVAILABLE")
            && "context".equals(diagnostic.evidence().get("boundary"))));
    }

    @Test
    void configuredCompiledBridgeRejectsTypedContextUntilBindingsAreAuthoritative() {
        CompiledCoreFlowExecutionBridge bridge = new CompiledCoreFlowExecutionBridge(
            () -> null,
            () -> RuntimeBindingManifest.create(List.of(), List.of(), Map.of(), List.of()),
            new RuntimeBindingRegistry(),
            RuntimeAuthority.anonymous());
        CompiledRuntimeContext context = new CompiledRuntimeContext(
            new CompiledRuntimeContext.PlayerIdentity(UUID.fromString("11111111-1111-4111-8111-111111111111"), "Player"),
            null,
            Map.of());

        FlowExecutionBridge.Result result = bridge.execute(new FlowExecutionBridge.Context(
            graph(), "source", null, metadata(true), context)).toCompletableFuture().join();

        assertEquals(FlowExecutionBridge.Status.FAILED, result.status());
        assertTrue(result.diagnostics().stream().anyMatch(diagnostic -> diagnostic.code().equals("GRAPH.CATALOG_REQUIRED")));
    }

    private static FlowGraph graph() {
        FlowGraph graph = new FlowGraph();
        graph.setId("simple-flow");
        graph.setResourceType("flow");
        graph.setResourceRevision(4);
        graph.getNodes().put("source", new FlowNode("source", 0, 0, Map.of("value", "hello")));
        graph.getNodes().put("target", new FlowNode("target", 100, 0, Map.of()));
        graph.getConnections().add(new FlowConnection("source", "result", "target", "input"));
        return graph;
    }

    private static ServerResourceLocator resource(String type, String id) {
        return resource("restudio.resync", type, id);
    }

    private static ServerResourceLocator resource(String owner, String type, String id) {
        return new ServerResourceLocator(SERVER,
            ContractRef.of(new OwnerId(owner), new ResourceTypeId(type)), id);
    }

    private static TypedValue typedString(String value) {
        return TypedValue.value(TypeExpr.named(TypeReference.of("builtin", "string")), value);
    }

    private static FlowGraph persistedFunctionGraph() {
        FlowGraph graph = graph();
        graph.setId("persisted-function");
        graph.setResourceType("function");
        graph.setResourceRevision(9);
        graph.setFunction(true);
        return FlowSerializer.deserialize(FlowSerializer.serialize(graph));
    }

    private static CompiledGraphMetadata metadata(boolean complete) {
        return metadata(complete, "flow", "simple-flow");
    }

    private static CompiledGraphMetadata metadata(boolean complete, Map<String, String> handlerConfigCanonical) {
        return metadata(complete, "flow", "simple-flow", handlerConfigCanonical);
    }

    private static CompiledGraphMetadata metadata(boolean complete, String resourceType, String resourceId) {
        return metadata(complete, resourceType, resourceId,
            complete ? Map.of("source", "{}", "target", "{}") : Map.of());
    }

    private static CompiledGraphMetadata metadata(boolean complete, String resourceType, String resourceId,
                                                  Map<String, String> handlerConfigCanonical) {
        ServerResourceLocator resource = new ServerResourceLocator(SERVER,
            ContractRef.of(new OwnerId("restudio.resync"), new ResourceTypeId(resourceType)), resourceId);
        Map<String, NodeInstanceId> nodes = complete
            ? Map.of("source", SOURCE, "target", TARGET)
            : Map.of();
        Map<String, ContractRef<NodeId>> definitions = complete
            ? Map.of("source", ContractRef.of(new OwnerId("builtin"), NodeId.of("source")),
                "target", ContractRef.of(new OwnerId("builtin"), NodeId.of("target")))
            : Map.of();
        Map<CompiledGraphMetadata.PinAddress, PinId> pins = complete
            ? Map.of(new CompiledGraphMetadata.PinAddress("source", "value"), PinId.of("value"),
                new CompiledGraphMetadata.PinAddress("source", "result"), PinId.of("result"),
                new CompiledGraphMetadata.PinAddress("target", "input"), PinId.of("input"))
            : Map.of();
        Map<CompiledGraphMetadata.ConnectionAddress, ConnectionId> connections = complete
            ? Map.of(new CompiledGraphMetadata.ConnectionAddress("source", "result", "target", "input"),
                ConnectionId.deterministic("simple-connection"))
            : Map.of();
        Map<CompiledGraphMetadata.PinAddress, TypedValue> values = complete
            ? Map.of(new CompiledGraphMetadata.PinAddress("source", "value"),
                TypedValue.value(TypeExpr.named(TypeReference.of("builtin", "string")), "hello"))
            : Map.of();
        return new CompiledGraphMetadata(resource, BINDING, SNAPSHOT, nodes, definitions, pins, connections, values,
            handlerConfigCanonical);
    }
}
