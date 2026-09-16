package restudio.resync.flow;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.flow.data.FlowDataType;
import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowNode;
import restudio.resync.api.OptionCatalogRegistry;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.generic.CustomFunctionCallHandler;
import restudio.resync.flow.identity.FunctionParameterId;
import restudio.resync.flow.identity.PinId;
import restudio.resync.flow.registry.NodeDefinition;
import restudio.resync.flow.registry.NodeDefinitionRegistry;
import restudio.resync.flow.registry.NodeDefinitionValidator;
import restudio.resync.flow.validation.FlowGraphValidator;
import restudio.resync.storage.AssetTransactionCoordinator;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CustomFunctionNodeDefinitionsTest {
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
        assertEquals("Runs the Library:calculate Reward custom Function with its declared inputs and outputs.", definition.getDescription());
        assertEquals("Starts this custom Function call.", definition.getInputs().getFirst().getDescription());
        assertEquals("Provides amount as an input to this custom Function.", definition.getInputs().get(1).getDescription());
        assertEquals("Continues after this custom Function returns.", definition.getOutputs().getFirst().getDescription());
        assertEquals("Returns reward as an output from this custom Function.", definition.getOutputs().get(1).getDescription());
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
        assertEquals("Runs the Legacy Function custom Function with its declared inputs and outputs.",
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
            FlowStorage storage = new FlowStorage(tempDir.toFile(), coordinator);
            FlowGraph valid = new FlowGraph();
            valid.setId("valid_function");
            valid.setFunction(true);
            FlowGraph malformed = new FlowGraph();
            malformed.setId("malformed_function");
            malformed.setFunction(true);
            malformed.setFunctionInputs(List.of(new FlowGraph.FunctionParameter("value", FlowDataType.STRING, "future_widget", "", "")));
            storage.saveGraph(valid);
            storage.saveGraph(malformed);
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
            FlowStorage storage = new FlowStorage(tempDir.toFile(), coordinator);
            FlowGraph function = new FlowGraph();
            function.setId("Name_Color_Select");
            function.setFunction(true);
            storage.saveGraph(function);
        }

        try (AssetTransactionCoordinator coordinator = coordinator()) {
            FlowStorage reopened = new FlowStorage(tempDir.toFile(), coordinator);
            NodeDefinitionRegistry definitions = new NodeDefinitionRegistry();
            HandlerRegistry handlers = new HandlerRegistry();
            new CustomFunctionCallHandler().registerTo(handlers);
            CustomFunctionNodeDefinitions.rebuild(definitions, reopened);
            definitions.register(new NodeDefinition.Builder("event.resync.command", "Command Start", NodeDefinition.NodeCategory.EVENT)
                .input("command", NodeDefinition.PinType.DATA, FlowDataType.STRING)
                .trigger(true)
                .eventType("resync.command")
                .build());
            reopened.setGraphValidator(new FlowGraphValidator(definitions, handlers, new TypeAdapterRegistry(), new OptionCatalogRegistry()));
            FlowGraph command = new FlowGraph();
            command.setId("name");
            command.setResourceType("command");
            command.setNodes(new HashMap<>(Map.of(
                "start", new FlowNode("event.resync.command", 0, 0, Map.of("command", "name")),
                "select", new FlowNode("custom_function:Name_Color_Select", 240, 0, Map.of())
            )));

            reopened.saveGraph(command);

            assertEquals(List.of("Name_Color_Select"), reopened.listGraphIds("function"));
            assertEquals(List.of("name"), reopened.listGraphIds("command"));
        }
    }

    private AssetTransactionCoordinator coordinator() {
        try {
            return AssetTransactionCoordinator.open(tempDir.resolve("assets"), new Gson());
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to create the test asset coordinator", exception);
        }
    }
}
