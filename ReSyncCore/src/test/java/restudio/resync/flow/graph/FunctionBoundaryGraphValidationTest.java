package restudio.resync.flow.graph;

import org.junit.jupiter.api.Test;
import restudio.resync.flow.catalog.CatalogBindingProof;
import restudio.resync.flow.catalog.CatalogCapabilityDescriptor;
import restudio.resync.flow.catalog.CatalogCategoryDescriptor;
import restudio.resync.flow.catalog.CatalogCompiler;
import restudio.resync.flow.catalog.CatalogContractRange;
import restudio.resync.flow.catalog.CatalogContribution;
import restudio.resync.flow.catalog.CatalogNodeDescriptor;
import restudio.resync.flow.catalog.CatalogProvenance;
import restudio.resync.flow.catalog.CatalogSnapshot;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.function.FunctionLocator;
import restudio.resync.flow.function.FunctionParameterContract;
import restudio.resync.flow.function.FunctionRevision;
import restudio.resync.flow.function.FunctionSignature;
import restudio.resync.flow.function.FunctionSourceDocument;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.CatalogBinding;
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
import restudio.resync.flow.inspector.InspectorFallback;
import restudio.resync.flow.runtime.RuntimeFailureContract;
import restudio.resync.flow.runtime.RuntimeOperationDescriptor;
import restudio.resync.flow.runtime.RuntimeSemantics;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypeReference;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FunctionBoundaryGraphValidationTest {
    private static final CatalogVersion SCHEMA = new CatalogVersion(1, 0);
    private static final OwnerId OWNER = OwnerId.of("restudio.resync");
    private static final ServerId SERVER = ServerId.deterministic("function-boundary-graph-validation");
    private static final ContractRef<NodeId> START = ContractRef.of(OWNER, NodeId.of("function_start"));
    private static final ContractRef<NodeId> END = ContractRef.of(OWNER, NodeId.of("function_end"));
    private static final ContractRef<NodeId> PROPERTIES = ContractRef.of(OWNER, NodeId.of("player_properties"));
    private static final NodeInstanceId START_NODE = NodeInstanceId.deterministic("function-boundary-start");
    private static final NodeInstanceId END_NODE = NodeInstanceId.deterministic("function-boundary-end");
    private static final NodeInstanceId PROPERTIES_NODE = NodeInstanceId.deterministic("function-boundary-properties");
    private static final FunctionParameterId PLAYER = FunctionParameterId.of(
        UUID.fromString("11111111-1111-4111-8111-111111111111"));
    private static final FunctionParameterId RESULT = FunctionParameterId.of(
        UUID.fromString("22222222-2222-4222-8222-222222222222"));
    private static final TypeExpr EXECUTION = TypeExpr.named(TypeReference.of("builtin", "execution"));
    private static final TypeExpr PLAYER_TYPE = TypeExpr.named(TypeReference.of("builtin", "player"));
    private static final TypeExpr BOOLEAN = TypeExpr.named(TypeReference.of("builtin", "boolean"));

    @Test
    void validatesAndCompilesSignaturePinsOnFunctionBoundaries() {
        CatalogSnapshot catalog = catalog();
        FunctionSourceDocument source = source(catalog, playerPin(), resultPin());

        ValidationResult validation = new GraphValidator().validate(source, catalog);
        GraphCompilationResult compilation = new GraphCompiler().compileResult(source, catalog);

        assertTrue(validation.valid(), validation.diagnostics()::toString);
        assertTrue(compilation.compiled(), compilation.validation().diagnostics()::toString);
        CompiledExecutionStep start = compilation.plan().steps().stream()
            .filter(step -> step.nodeId().equals(START_NODE)).findFirst().orElseThrow();
        CompiledExecutionStep properties = compilation.plan().steps().stream()
            .filter(step -> step.nodeId().equals(PROPERTIES_NODE)).findFirst().orElseThrow();
        assertTrue(start.outputBindings().get(playerPin()).stream()
            .anyMatch(endpoint -> endpoint.nodeId().equals(PROPERTIES_NODE) && endpoint.pinId().equals(PinId.of("target"))));
        assertTrue(properties.outputBindings().get(PinId.of("sneak")).stream()
            .anyMatch(endpoint -> endpoint.nodeId().equals(END_NODE) && endpoint.pinId().equals(resultPin())));
        assertEquals(List.of(PinId.of("flow"), playerPin()), List.copyOf(start.outputBindings().keySet()));
        CompiledExecutionStep end = compilation.plan().steps().stream()
            .filter(step -> step.nodeId().equals(END_NODE)).findFirst().orElseThrow();
        assertEquals(List.of(PinId.of("flow"), resultPin()), List.copyOf(end.inputBindings().keySet()));
    }

    @Test
    void rejectsAnUnknownFunctionBoundaryPin() {
        CatalogSnapshot catalog = catalog();
        FunctionSourceDocument source = source(catalog, PinId.of("function-output-33333333-3333-4333-8333-333333333333"),
            resultPin());

        ValidationResult validation = new GraphValidator().validate(source, catalog);

        assertFalse(validation.valid());
        assertTrue(validation.diagnostics().stream().anyMatch(value -> "GRAPH.ENDPOINT_PIN_MISSING".equals(value.code())));
    }

    private static FunctionSourceDocument source(CatalogSnapshot catalog, PinId startParameterPin,
                                                  PinId endParameterPin) {
        ServerResourceLocator resource = new ServerResourceLocator(SERVER,
            ContractRef.of(OWNER, ResourceTypeId.of("function")), "sneaking");
        CatalogBinding binding = CatalogBinding.of(catalog.generation(), catalog.contentChecksum(),
            catalog.bindingManifestHash());
        List<GraphNode> nodes = List.of(
            new GraphNode(START_NODE, START, 1, Map.of()),
            new GraphNode(END_NODE, END, 1, Map.of()),
            new GraphNode(PROPERTIES_NODE, PROPERTIES, 1, Map.of()));
        List<GraphConnection> connections = List.of(
            connection("flow", START_NODE, PinId.of("flow"), END_NODE, PinId.of("flow")),
            connection("player", START_NODE, startParameterPin, PROPERTIES_NODE, PinId.of("target")),
            connection("result", PROPERTIES_NODE, PinId.of("sneak"), END_NODE, endParameterPin));
        GraphDocument graph = new GraphDocument(SCHEMA, resource, 1L, binding, Set.of(), nodes, connections,
            List.of(), List.of(), OpaqueData.empty());
        FunctionSignature signature = new FunctionSignature(FunctionLocator.of(resource), FunctionRevision.of(1L),
            List.of(new FunctionParameterContract(PLAYER, PLAYER_TYPE)),
            List.of(new FunctionParameterContract(RESULT, BOOLEAN)));
        return new FunctionSourceDocument(signature, graph);
    }

    private static GraphConnection connection(String scope, NodeInstanceId sourceNode, PinId sourcePin,
                                              NodeInstanceId targetNode, PinId targetPin) {
        return new GraphConnection(ConnectionId.deterministic("function-boundary-" + scope),
            new GraphEndpoint(sourceNode, sourcePin), new GraphEndpoint(targetNode, targetPin));
    }

    private static PinId playerPin() {
        return PinId.of("function-output-" + PLAYER.canonicalText());
    }

    private static PinId resultPin() {
        return PinId.of("function-input-" + RESULT.canonicalText());
    }

    private static CatalogSnapshot catalog() {
        List<CatalogNodeDescriptor> definitions = List.of(
            node("function_start", "function_start", List.of(pin("flow", CatalogNodeDescriptor.Direction.OUTPUT,
                EXECUTION)), Map.of("functionBoundary", Map.of("role", "inputs", "flowPin", "flow"))),
            node("function_end", "function_end", List.of(pin("flow", CatalogNodeDescriptor.Direction.INPUT,
                EXECUTION)), Map.of("functionBoundary", Map.of("role", "outputs", "flowPin", "flow"))),
            node("player_properties", "player_properties", List.of(
                pin("target", CatalogNodeDescriptor.Direction.INPUT, PLAYER_TYPE),
                pin("sneak", CatalogNodeDescriptor.Direction.OUTPUT, BOOLEAN)), Map.of()));
        List<RuntimeOperationDescriptor> requirements = definitions.stream()
            .map(FunctionBoundaryGraphValidationTest::requirement).toList();
        CatalogContribution contribution = CatalogContribution.builder(OWNER, "1.0.0",
                new CatalogContractRange(SCHEMA, SCHEMA), CatalogProvenance.fromText(
                    CatalogProvenance.SourceKind.BUNDLED, "classpath:/nodes/function-boundary-test.json", "1.0.0",
                    "test", "function-boundary"))
            .categories(List.of(new CatalogCategoryDescriptor("flow", "Flow",
                "Operations that compose into a reusable Function graph.", 1)))
            .capabilities(List.of(
                new CatalogCapabilityDescriptor(CapabilityId.of("flow-execute"), 1, false, InspectorFallback.GENERIC),
                new CatalogCapabilityDescriptor(CapabilityId.of("generic-editor"), 1, false,
                    InspectorFallback.READ_ONLY_FIELD)))
            .definitions(definitions)
            .runtimeRequirements(requirements)
            .build();
        Map<restudio.resync.flow.runtime.RuntimeBindingKey, ContentHash> fingerprints = new LinkedHashMap<>();
        requirements.forEach(requirement -> fingerprints.put(requirement.key(), requirement.executionFingerprint()));
        return new CatalogCompiler(SCHEMA, CatalogBindingProof.fixed(fingerprints, new ContentHash("f".repeat(64))))
            .compile(List.of(contribution), 1L).snapshot().orElseThrow();
    }

    private static CatalogNodeDescriptor node(String id, String operation, List<CatalogNodeDescriptor.Pin> pins,
                                              Map<String, Object> metadata) {
        ContractRef<CapabilityId> execute = ContractRef.of(OWNER, CapabilityId.of("flow-execute"));
        return CatalogNodeDescriptor.builder(id)
            .domain("flow")
            .family("operation")
            .displayName(id.replace('_', ' '))
            .description("Executes the typed " + id.replace('_', ' ') + " operation for a Function graph.")
            .category(ContractRef.of(OWNER, CapabilityId.of("flow")))
            .pins(pins)
            .branches(List.of(new CatalogNodeDescriptor.Branch("failed", "Failed",
                "Describes a structured operation failure.", List.of(new CatalogNodeDescriptor.Case("failure",
                "Failure", "The operation completed with a structured failure.")))))
            .handler(new CatalogNodeDescriptor.Handler(execute, ContractRef.of(OWNER, OperationId.of(operation))))
            .semantics(semantics())
            .requiredCapabilities(Set.of(execute))
            .metadata(metadata)
            .build();
    }

    private static CatalogNodeDescriptor.Pin pin(String id, CatalogNodeDescriptor.Direction direction, TypeExpr type) {
        return new CatalogNodeDescriptor.Pin(PinId.of(id), direction, type, id,
            "Provides the typed " + id + " value for this operation.", CatalogNodeDescriptor.Requirement.REQUIRED,
            null, ContractRef.of(OWNER, CapabilityId.of("generic-editor")), null, null, null);
    }

    private static RuntimeOperationDescriptor requirement(CatalogNodeDescriptor node) {
        List<RuntimeOperationDescriptor.Pin> pins = node.pins().stream().map(pin -> new RuntimeOperationDescriptor.Pin(
            pin.id(), pin.direction() == CatalogNodeDescriptor.Direction.INPUT
                ? RuntimeOperationDescriptor.Direction.INPUT : RuntimeOperationDescriptor.Direction.OUTPUT,
            pin.type())).toList();
        return new RuntimeOperationDescriptor(node.handler().capability(), node.handler().operation(), pins,
            node.semantics());
    }

    private static RuntimeSemantics semantics() {
        ContractRef<CapabilityId> authorization = ContractRef.of(OWNER, CapabilityId.of("flow-execute"));
        return new RuntimeSemantics(RuntimeSemantics.Effect.PURE, RuntimeSemantics.ThreadMode.CURRENT, authorization,
            RuntimeSemantics.Cancellation.NONE, 0, 0, 0, RuntimeSemantics.UnloadPolicy.DRAIN,
            RuntimeSemantics.Retry.NEVER, RuntimeSemantics.Idempotency.INTRINSIC, RuntimeSemantics.Audit.NONE,
            RuntimeSemantics.Confirmation.NONE, RuntimeSemantics.SensitiveData.NONE,
            RuntimeSemantics.Determinism.DETERMINISTIC, Set.of(), Set.of("failed"), Set.of(),
            new RuntimeFailureContract(BOOLEAN, Set.of("RUNTIME.FAILURE"), Set.of("failed"),
                RuntimeFailureContract.CommitBoundary.NO_MUTATION), Set.of(), Set.of());
    }
}
