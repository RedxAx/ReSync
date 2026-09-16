package restudio.resync.flow.function;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.graph.GraphConnection;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.graph.GraphEndpoint;
import restudio.resync.flow.graph.GraphNode;
import restudio.resync.flow.graph.OpaqueData;
import restudio.resync.flow.graph.PinValue;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ConnectionId;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.FunctionParameterId;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.NodeInstanceId;
import restudio.resync.flow.identity.OperationId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.PinId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypeReference;
import restudio.resync.flow.type.TypedValue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TypedFunctionCompilerTest {
    private static final ServerId SERVER = new ServerId(UUID.fromString("11111111-1111-4111-8111-111111111111"));
    private static final ServerResourceLocator RESOURCE = new ServerResourceLocator(SERVER,
        ContractRef.of(new OwnerId("resync"), new ResourceTypeId("function")), "typed-function");
    private static final CatalogBinding BINDING = new CatalogBinding(1, new ContentHash("0".repeat(64)), new ContentHash("1".repeat(64)));
    private static final TypeExpr TEXT = TypeExpr.named(new TypeReference("builtin", "text"));
    private static final FunctionParameterId INPUT = FunctionParameterId.of(UUID.fromString("22222222-2222-4222-8222-222222222222"));
    private static final FunctionParameterId OUTPUT = FunctionParameterId.of(UUID.fromString("33333333-3333-4333-8333-333333333333"));
    private static final NodeInstanceId SOURCE = NodeInstanceId.of(UUID.fromString("44444444-4444-4444-8444-444444444444"));
    private static final NodeInstanceId TARGET = NodeInstanceId.of(UUID.fromString("55555555-5555-4555-8555-555555555555"));
    private static final ContractRef<CapabilityId> CAPABILITY = ContractRef.of(new OwnerId("typed"), CapabilityId.of("function-test"));
    private static final ContractRef<OperationId> OPERATION = ContractRef.of(new OwnerId("typed"), OperationId.of("copy"));
    private static final PinId VALUE = PinId.of("value");
    private static final PinId RESULT = PinId.of("result");

    @Test
    void compilesTypedSourceAndExecutesThroughTypedBoundary() {
        FunctionSourceDocument source = source(graph(4, Set.of(), List.of(node(SOURCE, "source", VALUE, TypedValue.value(TEXT, "hello"))), List.of()));
        TypedFunctionCapabilitySet capabilities = capabilities(source, List.of(capability(SOURCE, 0, Set.of(), node ->
            frame -> frame.withOutput(OUTPUT, frame.input(INPUT)))));

        TypedFunctionCompiler.Result compiled = new TypedFunctionCompiler().compile(source.signature(), source.graph(), capabilities);

        assertTrue(compiled.compiled(), compiled.diagnostics()::toString);
        assertEquals(source.checksum().canonicalText(), compiled.function().body().metadata().get("sourceChecksum"));
        TypedFunctionResolver resolver = new TypedFunctionResolver(List.of(new TypedFunctionResolver.Registration(source, capabilities)));
        FunctionExecutionRequest request = new FunctionExecutionRequest(source.signature(), UUID.fromString("66666666-6666-4666-8666-666666666666"),
            new FunctionInputMap(Map.of(INPUT, TypedValue.value(TEXT, "hello"))));

        FunctionResult result = new TypedFunctionExecutionBoundary(resolver).execute(request);

        assertTrue(result.successful());
        assertEquals(TypedValue.value(TEXT, "hello"), result.outputs().value(OUTPUT));
        assertSame(resolver.resolve(source.signature().function(), source.signature().revision()).orElseThrow(),
            resolver.resolve(source.signature().function(), source.signature().revision()).orElseThrow());
    }

    @Test
    void rejectsMissingNodeCapabilityAndDoesNotProduceACompiledBody() {
        FunctionSourceDocument source = source(graph(4, Set.of(CAPABILITY), List.of(node(SOURCE, "source", VALUE, TypedValue.value(TEXT, "hello"))), List.of()));
        TypedFunctionCompiler.Result result = new TypedFunctionCompiler().compile(source,
            new TypedFunctionCapabilitySet(BINDING, Set.of(CAPABILITY), List.of()));

        assertFalse(result.compiled());
        assertTrue(result.diagnostics().stream().anyMatch(value -> value.code().equals("FUNCTION.COMPILER_NODE_CAPABILITY_MISSING")));
    }

    @Test
    void rejectsUnownedConnectionsInsteadOfInferringExecutionOrder() {
        GraphNode sourceNode = node(SOURCE, "source", RESULT, TypedValue.value(TEXT, "hello"));
        GraphNode targetNode = node(TARGET, "target", VALUE, TypedValue.absent(TEXT));
        ConnectionId connectionId = ConnectionId.deterministic("source-target");
        GraphConnection connection = new GraphConnection(connectionId, new GraphEndpoint(SOURCE, RESULT), new GraphEndpoint(TARGET, VALUE));
        FunctionSourceDocument source = source(graph(4, Set.of(), List.of(sourceNode, targetNode), List.of(connection)));
        TypedFunctionCapabilitySet capabilities = capabilities(source, List.of(
            capability(SOURCE, 0, Set.of(), node -> frame -> frame),
            capability(TARGET, 1, Set.of(), node -> frame -> frame)));

        TypedFunctionCompiler.Result result = new TypedFunctionCompiler().compile(source, capabilities);

        assertFalse(result.compiled());
        assertEquals("FUNCTION.COMPILER_CONNECTION_UNHANDLED", result.diagnostics().getFirst().code());
    }

    @Test
    void rejectsCapabilityStepFailureAndResolverNeverFallsBack() {
        FunctionSourceDocument source = source(graph(4, Set.of(), List.of(node(SOURCE, "source", VALUE, TypedValue.value(TEXT, "hello"))), List.of()));
        TypedFunctionCapabilitySet capabilities = capabilities(source, List.of(capability(SOURCE, 0, Set.of(), node -> {
            throw new IllegalStateException("not typed");
        })));
        TypedFunctionResolver resolver = new TypedFunctionResolver(List.of(new TypedFunctionResolver.Registration(source, capabilities)));

        TypedFunctionResolver.Resolution resolution = resolver.resolveResult(source.signature().function(), source.signature().revision());

        assertFalse(resolution.resolved());
        assertEquals("FUNCTION.COMPILER_STEP_FAILURE", resolution.diagnostics().getFirst().code());
        assertTrue(resolver.resolve(source.signature().function(), source.signature().revision()).isEmpty());
        assertTrue(resolver.resolveResult(source.signature().function(), new FunctionRevision(99)).diagnostics().stream()
            .anyMatch(value -> value.code().equals("FUNCTION.NOT_FOUND")));
    }

    private static FunctionSourceDocument source(GraphDocument graph) {
        FunctionSignature signature = new FunctionSignature(new FunctionLocator(RESOURCE), new FunctionRevision(graph.revision()),
            List.of(new FunctionParameterContract(INPUT, TEXT, true)),
            List.of(new FunctionParameterContract(OUTPUT, TEXT, true)));
        return new FunctionSourceMaterializer().materialize(signature, graph).source();
    }

    private static TypedFunctionCapabilitySet capabilities(FunctionSourceDocument source, List<TypedFunctionNodeCapability> nodes) {
        return new TypedFunctionCapabilitySet(source.graph().catalogBinding(), nodes);
    }

    private static TypedFunctionNodeCapability capability(NodeInstanceId nodeId, int order, Set<ConnectionId> connections,
                                                          TypedFunctionNodeCompiler compiler) {
        String definition = nodeId.equals(SOURCE) ? "source" : "target";
        return new TypedFunctionNodeCapability(nodeId, ContractRef.of(new OwnerId("typed"), NodeId.of(definition)), 1,
            order, CAPABILITY, OPERATION, new ContentHash("2".repeat(64)), connections, compiler);
    }

    private static GraphDocument graph(long revision, Set<ContractRef<CapabilityId>> requiredCapabilities,
                                       List<GraphNode> nodes, List<GraphConnection> connections) {
        return new GraphDocument(new CatalogVersion(1, 0), RESOURCE, revision, BINDING, requiredCapabilities,
            nodes, connections, List.of(), List.of(), OpaqueData.empty());
    }

    private static GraphNode node(NodeInstanceId id, String definition, PinId pin, TypedValue value) {
        return new GraphNode(id, ContractRef.of(new OwnerId("typed"), NodeId.of(definition)), 1,
            Map.of(pin, new PinValue(pin, value)));
    }
}
