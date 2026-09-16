package restudio.resync.flow.function;

import org.junit.jupiter.api.Test;
import restudio.resync.flow.graph.FunctionBinding;
import restudio.resync.flow.graph.GraphConnection;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.graph.GraphNode;
import restudio.resync.flow.graph.OpaqueData;
import restudio.resync.flow.graph.PinValue;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.identity.ConnectionId;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.FunctionParameterId;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.NodeInstanceId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.identity.PinId;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypeReference;
import restudio.resync.flow.type.TypedValue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FunctionSourceMaterializerTest {
    private static final ServerId SERVER = new ServerId(UUID.fromString("11111111-1111-4111-8111-111111111111"));
    private static final ServerResourceLocator RESOURCE = new ServerResourceLocator(SERVER,
        ContractRef.of(new OwnerId("resync"), new ResourceTypeId("function")), "typed-function");
    private static final TypeExpr TEXT = TypeExpr.named(new TypeReference("builtin", "text"));
    private static final FunctionParameterId INPUT = FunctionParameterId.of(UUID.fromString("22222222-2222-4222-8222-222222222222"));
    private static final FunctionParameterId OUTPUT = FunctionParameterId.of(UUID.fromString("33333333-3333-4333-8333-333333333333"));
    private static final NodeInstanceId SOURCE = NodeInstanceId.of(UUID.fromString("44444444-4444-4444-8444-444444444444"));
    private static final NodeInstanceId TARGET = NodeInstanceId.of(UUID.fromString("55555555-5555-4555-8555-555555555555"));
    private static final PinId VALUE = PinId.of("value");
    private static final PinId RESULT = PinId.of("result");
    private static final PinId INPUT_PIN = PinId.of("input");

    @Test
    void materializesAnImmutableTypedSourceWithoutCreatingExecutableLegacySteps() {
        FunctionSourceMaterializer.Result result = new FunctionSourceMaterializer().materialize(signature(4), graph(4));

        assertTrue(result.materialized(), result.diagnostics()::toString);
        assertEquals(RESOURCE, result.source().graph().resource());
        assertEquals(4, result.source().signature().revision().value());
        assertEquals(result.source().checksum(), new FunctionSourceMaterializer().materialize(signature(4), graph(4)).source().checksum());
        assertTrue(result.source().signature().parameters().containsKey(INPUT));
        assertTrue(result.source().graph().nodes().stream().allMatch(node -> node.instanceId() != null));
    }

    @Test
    void rejectsDanglingAndDuplicateConnectionEndpointsWithStableDiagnostics() {
        GraphDocument dangling = new GraphDocument(RESOURCE, 4, binding(),
            List.of(node(SOURCE, VALUE, TypedValue.value(TEXT, "hello"))),
            List.of(new GraphConnection(ConnectionId.deterministic("dangling"),
                new restudio.resync.flow.graph.GraphEndpoint(SOURCE, RESULT),
                new restudio.resync.flow.graph.GraphEndpoint(TARGET, INPUT_PIN))));
        FunctionSourceMaterializer.Result danglingResult = new FunctionSourceMaterializer().materialize(signature(4), dangling);

        assertFalse(danglingResult.materialized());
        assertEquals("FUNCTION.BODY_ENDPOINT_MISSING", danglingResult.diagnostics().getFirst().code());

        GraphConnection first = new GraphConnection(ConnectionId.deterministic("first"),
            new restudio.resync.flow.graph.GraphEndpoint(SOURCE, RESULT),
            new restudio.resync.flow.graph.GraphEndpoint(TARGET, INPUT_PIN));
        GraphConnection second = new GraphConnection(ConnectionId.deterministic("second"),
            new restudio.resync.flow.graph.GraphEndpoint(SOURCE, RESULT),
            new restudio.resync.flow.graph.GraphEndpoint(TARGET, INPUT_PIN));
        FunctionSourceMaterializer.Result duplicateResult = new FunctionSourceMaterializer().materialize(signature(4),
            new GraphDocument(RESOURCE, 4, binding(), List.of(
                node(SOURCE, VALUE, TypedValue.value(TEXT, "hello")),
                node(TARGET, INPUT_PIN, TypedValue.absent(TEXT))), List.of(first, second)));

        assertFalse(duplicateResult.materialized());
        assertTrue(duplicateResult.diagnostics().stream().anyMatch(value -> value.code().equals("FUNCTION.BODY_CONNECTION_AMBIGUOUS")));
    }

    @Test
    void rejectsOpaqueTypedBodyMaterialAndWrongNestedFunctionIdentity() {
        TypeExpr.OpaqueType opaque = TypeExpr.opaque(new TypeReference("extension", "unknown"));
        GraphDocument opaqueGraph = new GraphDocument(RESOURCE, 4, binding(),
            List.of(node(SOURCE, VALUE, TypedValue.opaque((TypeExpr.OpaqueType) opaque, Map.of("raw", true)))), List.of());
        FunctionSourceMaterializer.Result opaqueResult = new FunctionSourceMaterializer().materialize(signature(4), opaqueGraph);

        assertFalse(opaqueResult.materialized());
        assertEquals("FUNCTION.TYPED_VALUE_OPAQUE", opaqueResult.diagnostics().getFirst().code());

        ServerResourceLocator wrongType = new ServerResourceLocator(SERVER,
            ContractRef.of(new OwnerId("resync"), new ResourceTypeId("flow")), "nested");
        GraphDocument nestedGraph = new GraphDocument(new CatalogVersion(1, 0), RESOURCE, 4, binding(), Set.of(), List.of(), List.of(),
            List.of(new FunctionBinding(wrongType, 1, List.of(), List.of())), OpaqueData.empty());
        FunctionSourceMaterializer.Result nestedResult = new FunctionSourceMaterializer().materialize(signature(4), nestedGraph);

        assertFalse(nestedResult.materialized());
        assertEquals("FUNCTION.NESTED_RESOURCE_TYPE_MISMATCH", nestedResult.diagnostics().getFirst().code());
    }

    @Test
    void rejectsSignatureAndGraphIdentityMismatchesBeforeBodyValidation() {
        ServerResourceLocator other = new ServerResourceLocator(SERVER,
            ContractRef.of(new OwnerId("resync"), new ResourceTypeId("function")), "other");
        FunctionSignature wrongLocator = new FunctionSignature(new FunctionLocator(other), new FunctionRevision(4),
            signature(4).inputs(), signature(4).outputs());
        FunctionSourceMaterializer.Result locatorResult = new FunctionSourceMaterializer().materialize(wrongLocator, graph(4));
        FunctionSourceMaterializer.Result revisionResult = new FunctionSourceMaterializer().materialize(signature(3), graph(4));

        assertEquals("FUNCTION.RESOURCE_MISMATCH", locatorResult.diagnostics().getFirst().code());
        assertEquals("FUNCTION.REVISION_MISMATCH", revisionResult.diagnostics().getFirst().code());
    }

    private static FunctionSignature signature(long revision) {
        return new FunctionSignature(new FunctionLocator(RESOURCE), new FunctionRevision(revision),
            List.of(new FunctionParameterContract(INPUT, TEXT, true)),
            List.of(new FunctionParameterContract(OUTPUT, TEXT, true)));
    }

    private static GraphDocument graph(long revision) {
        return new GraphDocument(RESOURCE, revision, binding(), List.of(
            node(SOURCE, VALUE, TypedValue.value(TEXT, "hello")),
            node(TARGET, INPUT_PIN, TypedValue.absent(TEXT))), List.of(
            new GraphConnection(ConnectionId.deterministic("source-target"),
                new restudio.resync.flow.graph.GraphEndpoint(SOURCE, RESULT),
                new restudio.resync.flow.graph.GraphEndpoint(TARGET, INPUT_PIN))));
    }

    private static GraphNode node(NodeInstanceId id, PinId pin, TypedValue value) {
        return new GraphNode(id, ContractRef.of(new OwnerId("builtin"), NodeId.of(id.equals(SOURCE) ? "source" : "target")),
            1, Map.of(pin, new PinValue(pin, value)));
    }

    private static CatalogBinding binding() {
        return new CatalogBinding(1, new ContentHash("0".repeat(64)), new ContentHash("1".repeat(64)));
    }
}
