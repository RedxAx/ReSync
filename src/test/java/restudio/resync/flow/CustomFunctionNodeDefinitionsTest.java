package restudio.resync.flow;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.flow.data.FlowDataType;
import restudio.flow.data.FlowGraph;
import restudio.resync.flow.catalog.CatalogNodeDescriptor;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.command.CommandGraphContract;
import restudio.resync.flow.command.CommandGraphMetadata;
import restudio.resync.flow.function.FunctionLocator;
import restudio.resync.flow.function.FunctionParameterContract;
import restudio.resync.flow.function.FunctionRevision;
import restudio.resync.flow.function.FunctionSignature;
import restudio.resync.flow.function.FunctionSourceDocument;
import restudio.resync.flow.graph.FunctionBinding;
import restudio.resync.flow.graph.GraphConnection;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.graph.GraphEndpoint;
import restudio.resync.flow.graph.GraphNode;
import restudio.resync.flow.graph.OpaqueData;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.generic.CustomFunctionCallHandler;
import restudio.resync.flow.identity.FunctionParameterId;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.ConnectionId;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.NodeInstanceId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.PinId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.migration.LegacyRuntimeActivationGate;
import restudio.resync.flow.protocol.ResourceActivationState;
import restudio.resync.flow.registry.NodeDefinition;
import restudio.resync.flow.registry.NodeDefinitionRegistry;
import restudio.resync.flow.registry.NodeDefinitionValidator;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypeReference;
import restudio.resync.modules.FlowModule;
import restudio.resync.storage.AssetPersistenceGate;
import restudio.resync.storage.AssetTransactionCoordinator;
import restudio.resync.storage.CanonicalProjectMetadataFixture;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CustomFunctionNodeDefinitionsTest {
    private static final OwnerId OWNER = OwnerId.of("restudio.resync");
    private static final CatalogBinding BINDING = new CatalogBinding(1, ContentHash.of("a".repeat(64)), ContentHash.of("b".repeat(64)));
    @TempDir
    Path tempDir;

    @Test
    void generatedFunctionsAdvertiseTheirExecutorOwnedRuntimeBinding() {
        FlowGraph graph = new FlowGraph();
        graph.setId("library:calculate_reward");
        graph.setFunction(true);
        graph.setFunctionInputs(List.of(new FlowGraph.FunctionParameter("amount", FlowDataType.NUMBER)));
        graph.setFunctionOutputs(List.of(new FlowGraph.FunctionParameter("reward", FlowDataType.NUMBER)));
        HandlerRegistry handlers = new HandlerRegistry();
        new CustomFunctionCallHandler().registerTo(handlers);

        NodeDefinition definition = CustomFunctionNodeDefinitions.buildDefinition(graph);
        NodeDefinitionValidator.ValidationResult validation = new NodeDefinitionValidator(handlers, true).validate(definition);

        assertEquals("custom_function:library:calculate_reward", definition.getId());
        assertEquals("server", definition.getOwner());
        assertEquals(CustomFunctionCallHandler.HANDLER_ID, definition.getHandler());
        assertEquals(CustomFunctionCallHandler.OPERATION, definition.getHandlerConfig().get("operation"));
        assertEquals("library:calculate_reward", definition.getHandlerConfig().get("functionId"));
        assertEquals("server", definition.getHandlerConfig().get("functionOwner"));
        assertEquals("local", definition.getHandlerConfig().get("functionNamespace"));
        assertEquals(PinId.of("flow"), definition.getInputs().getFirst().getId());
        assertEquals("flow", definition.getInputs().getFirst().getDisplayName());
        assertEquals("flow", definition.getOutputs().getFirst().getRuntimeName());
        assertEquals("amount", definition.getInputs().get(1).getRuntimeName());
        assertEquals("reward", definition.getOutputs().get(1).getRuntimeName());
        assertEquals("Calls Library:calculate Reward and continues when it finishes. Inputs: amount. Outputs: reward.", definition.getDescription());
        assertEquals("Starts this custom Function call.", definition.getInputs().getFirst().getDescription());
        assertEquals("Passes amount to this Function. Type: Number.", definition.getInputs().get(1).getDescription());
        assertEquals("Continues after this custom Function returns.", definition.getOutputs().getFirst().getDescription());
        assertEquals("Returns reward from this Function. Type: Number.", definition.getOutputs().get(1).getDescription());
        assertTrue(handlers.hasOperation(definition.getHandler(), CustomFunctionCallHandler.OPERATION));
        assertTrue(validation.valid(), validation.errors().toString());
    }

    @Test
    void authoredFunctionDescriptionsRemainExactWhileLegacyPlaceholdersAreReplaced() {
        FlowGraph authored = new FlowGraph();
        authored.setId("authored_function");
        authored.setFunction(true);
        authored.setFunctionDescription("Calculates the final reward for the current player.");
        FlowGraph placeholder = new FlowGraph();
        placeholder.setId("legacy_function");
        placeholder.setFunction(true);
        placeholder.setFunctionDescription("Run legacy_function.");

        assertEquals("Calculates the final reward for the current player.",
            CustomFunctionNodeDefinitions.buildDefinition(authored).getDescription());
        assertEquals("Calls Legacy Function and continues when it finishes. Takes no inputs. Returns no values.",
            CustomFunctionNodeDefinitions.buildDefinition(placeholder).getDescription());
    }

    @Test
    void ordinaryCallSchemaDoesNotChangeWhenTheFunctionResourceRevisionAdvances() {
        FlowGraph first = new FlowGraph();
        first.setId("stable_call_schema");
        first.setFunction(true);
        first.setFunctionVersion(1);
        first.setFunctionInputs(List.of(new FlowGraph.FunctionParameter("value", FlowDataType.STRING)));
        FlowGraph rebound = first.copy();
        rebound.setFunctionVersion(42);

        NodeDefinition original = CustomFunctionNodeDefinitions.buildDefinition(first);
        NodeDefinition projected = CustomFunctionNodeDefinitions.buildDefinition(rebound);

        assertEquals(1, original.getSchemaVersion());
        assertEquals(original.getSchemaVersion(), projected.getSchemaVersion());
        assertEquals(original.getInputs().stream().map(NodeDefinition.PinDefinition::getId).toList(),
            projected.getInputs().stream().map(NodeDefinition.PinDefinition::getId).toList());
        assertEquals(original.getOutputs().stream().map(NodeDefinition.PinDefinition::getId).toList(),
            projected.getOutputs().stream().map(NodeDefinition.PinDefinition::getId).toList());
        assertEquals(original.getHandlerConfig(), projected.getHandlerConfig());
    }

    @Test
    void generatedFunctionsPreserveParameterRuntimeNamesAcrossStableIdentityChanges() {
        FunctionParameterId inputId = FunctionParameterId.deterministic("custom-function-input");
        FunctionParameterId outputId = FunctionParameterId.deterministic("custom-function-output");
        FlowGraph graph = new FlowGraph();
        graph.setId("stable_function");
        graph.setFunction(true);
        graph.setFunctionInputs(List.of(new FlowGraph.FunctionParameter(inputId, "renamed_input", FlowDataType.STRING)));
        graph.setFunctionOutputs(List.of(new FlowGraph.FunctionParameter(outputId, "renamed_output", FlowDataType.STRING)));

        NodeDefinition definition = CustomFunctionNodeDefinitions.buildDefinition(graph);

        assertEquals(PinId.of("function-input-" + inputId.canonicalText()), definition.getInputs().get(1).getId());
        assertEquals("renamed_input", definition.getInputs().get(1).getRuntimeName());
        assertEquals(PinId.of("function-output-" + outputId.canonicalText()), definition.getOutputs().get(1).getId());
        assertEquals("renamed_output", definition.getOutputs().get(1).getRuntimeName());
    }

    @Test
    void identityBackedDisplayNamesUseStablePinIdsWhenTheyAreNotCanonical() {
        FunctionParameterId inputId = FunctionParameterId.deterministic("custom-function-spaced-input");
        FunctionParameterId outputId = FunctionParameterId.deterministic("custom-function-spaced-output");
        FlowGraph graph = new FlowGraph();
        graph.setId("spaced_function");
        graph.setFunction(true);
        graph.setFunctionInputs(List.of(new FlowGraph.FunctionParameter(inputId, "Reward Amount", FlowDataType.NUMBER)));
        graph.setFunctionOutputs(List.of(new FlowGraph.FunctionParameter(outputId, "Total Reward", FlowDataType.NUMBER)));

        NodeDefinition definition = CustomFunctionNodeDefinitions.buildDefinition(graph);

        NodeDefinition.PinDefinition input = definition.getInputs().get(1);
        NodeDefinition.PinDefinition output = definition.getOutputs().get(1);
        assertEquals("Reward Amount", input.getDisplayName());
        assertEquals(input.getId().value(), input.getRuntimeName());
        assertEquals("Total Reward", output.getDisplayName());
        assertEquals(output.getId().value(), output.getRuntimeName());
    }

    @Test
    void legacySpacedDisplayNamesReceiveStableIdsBeforeDefinitionConstruction() {
        FlowGraph graph = new FlowGraph();
        graph.setId("legacy_spaced_function");
        graph.setFunction(true);
        graph.setFunctionInputs(List.of(new FlowGraph.FunctionParameter("Reward Amount", FlowDataType.NUMBER)));

        NodeDefinition definition = CustomFunctionNodeDefinitions.buildDefinition(graph);

        NodeDefinition.PinDefinition input = definition.getInputs().get(1);
        assertTrue(input.getId().value().startsWith("function-input-"));
        assertEquals(input.getId().value(), input.getRuntimeName());
        assertEquals("Reward Amount", input.getDisplayName());
    }

    @Test
    void customFunctionParameterCompatibilityPrefersIdsAndRejectsAmbiguousNames() {
        FunctionParameterId firstId = FunctionParameterId.deterministic("custom-function-ambiguous-first");
        FunctionParameterId secondId = FunctionParameterId.deterministic("custom-function-ambiguous-second");
        FlowGraph graph = new FlowGraph();
        graph.setId("ambiguous_function");
        graph.setFunction(true);
        FlowGraph.FunctionParameter first = new FlowGraph.FunctionParameter(firstId, "Shared Value", FlowDataType.STRING);
        FlowGraph.FunctionParameter second = new FlowGraph.FunctionParameter(secondId, "Shared Value", FlowDataType.STRING);
        graph.setFunctionInputs(List.of(first, second));

        assertSame(first, CustomFunctionNodeDefinitions.parameterForKey(graph, firstId.canonicalText(),
            NodeDefinition.PinDirection.INPUT, true));
        assertThrows(IllegalArgumentException.class, () -> CustomFunctionNodeDefinitions.parameterForKey(graph, "Shared Value",
            NodeDefinition.PinDirection.INPUT, true));
    }

    @Test
    void generatedFunctionsRejectDuplicateParameterRuntimeNamesPerDirection() {
        FlowGraph graph = new FlowGraph();
        graph.setId("duplicate_runtime_names");
        graph.setFunction(true);
        graph.setFunctionInputs(List.of(
            new FlowGraph.FunctionParameter(FunctionParameterId.deterministic("first"), "value", FlowDataType.STRING),
            new FlowGraph.FunctionParameter(FunctionParameterId.deterministic("second"), "value", FlowDataType.STRING)));

        assertThrows(IllegalArgumentException.class, () -> CustomFunctionNodeDefinitions.buildDefinition(graph));
    }

    @Test
    void generatedFunctionsRequireAStableCallableIdentity() {
        FlowGraph ordinaryGraph = new FlowGraph();
        ordinaryGraph.setId("ordinary");
        FlowGraph unnamedFunction = new FlowGraph();
        unnamedFunction.setId("");
        unnamedFunction.setFunction(true);

        assertThrows(IllegalArgumentException.class, () -> CustomFunctionNodeDefinitions.buildDefinition(ordinaryGraph));
        assertThrows(IllegalArgumentException.class, () -> CustomFunctionNodeDefinitions.buildDefinition(unnamedFunction));
    }

    @Test
    void malformedFunctionMetadataDoesNotSuppressValidSiblings() throws Exception {
        try (AssetTransactionCoordinator coordinator = coordinator()) {
            CanonicalProjectMetadataFixture.seed(coordinator);
            FlowStorage storage = storage(coordinator);
            FunctionSourceDocument valid = function("valid_function", List.of(), List.of());
            FunctionParameterContract parameter = new FunctionParameterContract(FunctionParameterId.deterministic("malformed-widget"),
                TypeExpr.named(TypeReference.of("builtin", "string")), true, null, Map.of("name", "value", "widget", "future_widget"));
            FunctionSourceDocument malformed = function("malformed_function", List.of(parameter), List.of());
            storage.saveCoreGraph(valid, ResourceActivationState.INACTIVE, UUID.randomUUID(), 0L);
            storage.saveCoreGraph(malformed, ResourceActivationState.INACTIVE, UUID.randomUUID(), 0L);
            NodeDefinitionRegistry definitions = new NodeDefinitionRegistry();

            List<NodeDefinition> generated = CustomFunctionNodeDefinitions.rebuild(definitions, storage);

            assertEquals(1, generated.size());
            assertEquals("custom_function:valid_function", generated.getFirst().getId());
        }
    }

    @Test
    void legacyBooleanWidgetBuildsAsToggle() {
        FlowGraph graph = new FlowGraph();
        graph.setId("predicate");
        graph.setFunction(true);
        graph.setFunctionOutputs(List.of(
            new FlowGraph.FunctionParameter("result", FlowDataType.BOOLEAN, "boolean", "", "false")));

        NodeDefinition definition = CustomFunctionNodeDefinitions.buildDefinition(graph);
        NodeDefinition.PinDefinition result = definition.getOutputs().stream()
            .filter(pin -> "result".equals(pin.getDisplayName()))
            .findFirst()
            .orElseThrow();

        assertEquals(NodeDefinition.WidgetType.TOGGLE, result.getWidgetType());
    }

    @Test
    void persistedFunctionRemainsCallableFromCommandAfterStorageReload() throws Exception {
        try (AssetTransactionCoordinator coordinator = coordinator()) {
            CanonicalProjectMetadataFixture.seed(coordinator);
            storage(coordinator).saveCoreGraph(function("Name_Color_Select", List.of(), List.of()),
                ResourceActivationState.ACTIVE, UUID.randomUUID(), 0L);
        }

        try (AssetTransactionCoordinator coordinator = coordinator()) {
            FlowStorage reopened = storage(coordinator);
            NodeDefinitionRegistry definitions = new NodeDefinitionRegistry();
            HandlerRegistry handlers = new HandlerRegistry();
            new CustomFunctionCallHandler().registerTo(handlers);
            NodeDefinition definition = CustomFunctionNodeDefinitions.rebuild(definitions, reopened).getFirst();
            assertTrue(new NodeDefinitionValidator(handlers, true).validate(definition).valid());
            CatalogNodeDescriptor callable = catalog(definition, handlers);
            NodeInstanceId startId = NodeInstanceId.deterministic("name-command-start");
            NodeInstanceId callId = NodeInstanceId.deterministic("name-command-call");
            GraphNode start = new GraphNode(startId, CommandGraphContract.CANONICAL_START, 2, Map.of());
            GraphNode call = new GraphNode(callId, ContractRef.of(OWNER, callable.id()), callable.schemaVersion(), Map.of());
            GraphConnection connection = new GraphConnection(ConnectionId.deterministic("name-command-call-edge"),
                new GraphEndpoint(startId, PinId.of("flow")), new GraphEndpoint(callId, PinId.of("flow")));
            FunctionBinding binding = new FunctionBinding(resource("function", "Name_Color_Select"), 1, List.of(), List.of());
            GraphDocument command = new CommandGraphMetadata("name", false, List.of()).apply(new GraphDocument(
                new CatalogVersion(1, 0), resource("command", "name"), 1, BINDING, Set.of(), List.of(start, call), List.of(connection),
                List.of(), List.of(binding), OpaqueData.empty()));

            reopened.saveCoreGraph(command, ResourceActivationState.ACTIVE, UUID.randomUUID(), 0L);

            assertEquals(List.of("Name_Color_Select"), reopened.listGraphIds("function"));
            assertEquals(List.of("name"), reopened.listGraphIds("command"));
            GraphDocument stored = reopened.getCoreGraph("command", "name").orElseThrow().graphDocument();
            assertEquals(call.definition(), stored.nodes().stream().filter(node -> callId.equals(node.instanceId())).findFirst().orElseThrow().definition());
            assertEquals(command.checksum(), stored.checksum());
            assertEquals(binding.function(), stored.functions().getFirst().function());
            assertEquals(binding.revision(), stored.functions().getFirst().revision());
            assertEquals(List.of(connection), stored.connections());
            TypedCommandGraphAdapter.Snapshot commands = reopened.getTypedCommandGraphSnapshot();
            assertTrue(commands.rejections().isEmpty(), commands.rejections().toString());
            assertEquals(List.of("name"), commands.activeBindings().stream().map(TypedCommandGraphAdapter.CommandBinding::command).toList());
            GraphDocument competing = new CommandGraphMetadata("name", false, List.of()).apply(new GraphDocument(
                command.schemaVersion(), resource("command", "competing"), 1, BINDING, Set.of(), command.nodes(), command.connections(),
                command.passthroughs(), command.variables(), command.functions(), OpaqueData.empty()));
            assertThrows(IllegalArgumentException.class,
                () -> reopened.saveCoreGraph(competing, ResourceActivationState.ACTIVE, UUID.randomUUID(), 0L));
            assertEquals(List.of("name"), reopened.listGraphIds("command"));
            assertEquals(command.checksum(), reopened.getCoreGraph("command", "name").orElseThrow().graphDocument().checksum());
        }
    }

    @Test
    void persistedNestedFunctionRebuildsWithoutDiscardingItsStaticBinding() throws Exception {
        FunctionBinding dependency = new FunctionBinding(resource("function", "child"), 1, List.of(), List.of());
        FunctionSourceDocument parent = function("parent", List.of(), List.of(dependency));
        try (AssetTransactionCoordinator coordinator = coordinator()) {
            CanonicalProjectMetadataFixture.seed(coordinator);
            FlowStorage storage = storage(coordinator);
            storage.saveCoreGraph(function("child", List.of(), List.of()), ResourceActivationState.ACTIVE, UUID.randomUUID(), 0L);
            storage.saveCoreGraph(parent, ResourceActivationState.ACTIVE, UUID.randomUUID(), 0L);
        }
        try (AssetTransactionCoordinator coordinator = coordinator()) {
            FlowStorage storage = storage(coordinator);
            List<NodeDefinition> definitions = CustomFunctionNodeDefinitions.rebuild(new NodeDefinitionRegistry(false), storage);
            assertEquals(List.of("custom_function:child", "custom_function:parent"), definitions.stream().map(NodeDefinition::getId).toList());
            FunctionSourceDocument restored = storage.getCoreGraph("function", "parent").orElseThrow().functionSourceDocument();
            assertEquals(parent.checksum(), restored.checksum());
            assertEquals(dependency.function(), restored.graph().functions().getFirst().function());
            assertEquals(dependency.revision(), restored.graph().functions().getFirst().revision());
            assertEquals("Core graph static function bindings require compiled execution",
                assertThrows(IllegalStateException.class, () -> TypedCommandGraphAdapter.materialize(restored.graph(), restored, true, "mutation")).getMessage());
        }
    }

    private FlowStorage storage(AssetTransactionCoordinator coordinator) {
        return new FlowStorage(tempDir.toFile(), LegacyRuntimeActivationGate.runtime(tempDir),
            new AssetPersistenceGate(tempDir), CanonicalProjectMetadataFixture.serverId(), coordinator);
    }

    private static FunctionSourceDocument function(String id, List<FunctionParameterContract> inputs, List<FunctionBinding> dependencies) {
        ServerResourceLocator resource = resource("function", id);
        GraphDocument graph = new GraphDocument(new CatalogVersion(1, 0), resource, 1, BINDING, Set.of(), List.of(), List.of(),
            List.of(), dependencies, OpaqueData.empty());
        return new FunctionSourceDocument(new FunctionSignature(FunctionLocator.of(resource), FunctionRevision.of(1), inputs, List.of()), graph);
    }

    private static ServerResourceLocator resource(String type, String id) {
        return new ServerResourceLocator(CanonicalProjectMetadataFixture.serverId(), ContractRef.of(OWNER, ResourceTypeId.of(type)), id);
    }

    private static CatalogNodeDescriptor catalog(NodeDefinition definition, HandlerRegistry handlers) throws Exception {
        Method method = FlowModule.class.getDeclaredMethod("catalogNode", OwnerId.class, NodeDefinition.class, Map.class, Map.class,
            Map.class, List.class, HandlerRegistry.class);
        method.setAccessible(true);
        return (CatalogNodeDescriptor) method.invoke(null, OWNER, definition, new HashMap<>(), new HashMap<>(), new HashMap<>(), new ArrayList<>(), handlers);
    }

    private AssetTransactionCoordinator coordinator() {
        try {
            return AssetTransactionCoordinator.open(tempDir.resolve("assets"), new Gson());
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to create the test asset coordinator", exception);
        }
    }
}
