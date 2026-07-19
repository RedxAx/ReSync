package restudio.resync.flow;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.flow.data.FlowDataType;
import restudio.flow.data.FlowGraph;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.generic.CustomFunctionCallHandler;
import restudio.resync.flow.registry.NodeDefinition;
import restudio.resync.flow.registry.NodeDefinitionRegistry;
import restudio.resync.flow.registry.NodeDefinitionValidator;

import java.util.List;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        assertEquals(CustomFunctionCallHandler.HANDLER_ID, definition.getHandler());
        assertEquals(CustomFunctionCallHandler.OPERATION, definition.getHandlerConfig().get("operation"));
        assertEquals("library:calculate_reward", definition.getHandlerConfig().get("functionId"));
        assertTrue(handlers.hasOperation(definition.getHandler(), CustomFunctionCallHandler.OPERATION));
        assertTrue(validation.valid(), validation.errors().toString());
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
    void malformedFunctionMetadataDoesNotSuppressValidSiblings() {
        FlowStorage storage = new FlowStorage(tempDir.toFile());
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
