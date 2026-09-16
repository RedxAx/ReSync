package restudio.resync.flow.registry;

import org.junit.jupiter.api.Test;
import restudio.flow.data.FlowDataType;
import restudio.resync.flow.identity.PinId;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NodeDefinitionPinRuntimeNameTest {
    @Test
    void programmaticPinsDefaultRuntimeNameToStableId() {
        NodeDefinition.PinDefinition pin = new NodeDefinition.PinBuilder(PinId.of("output_flow"), "Flow",
            NodeDefinition.PinType.FLOW, NodeDefinition.PinDirection.OUTPUT, FlowDataType.EXECUTION).build();

        assertEquals("output_flow", pin.getName());
        assertEquals("output_flow", pin.getRuntimeName());
        assertEquals(NodeDefinition.PinDirection.OUTPUT, pin.getDirection());
    }

    @Test
    void authoredRuntimeNameIsRetainedAlongsideStableIdAndDirection() {
        NodeDefinition.PinDefinition pin = new NodeDefinition.PinBuilder(PinId.of("output_flow"), "Flow",
            NodeDefinition.PinType.FLOW, NodeDefinition.PinDirection.OUTPUT, FlowDataType.EXECUTION)
            .runtimeName("flow")
            .build();

        assertEquals("output_flow", pin.getName());
        assertEquals("flow", pin.getRuntimeName());
        assertEquals(NodeDefinition.PinDirection.OUTPUT, pin.getDirection());
    }

    @Test
    void copiedDefinitionRetainsAuthoredRuntimeNames() {
        NodeDefinition definition = new NodeDefinition.Builder("runtime.names", "Runtime Names", NodeDefinition.NodeCategory.DATA)
            .input(new NodeDefinition.PinBuilder(PinId.of("value"), "Value", NodeDefinition.PinType.DATA,
                NodeDefinition.PinDirection.INPUT, FlowDataType.STRING).runtimeName("input_value").build())
            .output(new NodeDefinition.PinBuilder(PinId.of("result"), "Result", NodeDefinition.PinType.DATA,
                NodeDefinition.PinDirection.OUTPUT, FlowDataType.STRING).runtimeName("output_value").build())
            .build();

        NodeDefinition copy = definition.copy();

        assertEquals("input_value", copy.getInputs().getFirst().getRuntimeName());
        assertEquals("output_value", copy.getOutputs().getFirst().getRuntimeName());
    }

    @Test
    void loaderRetainsAuthoredRuntimeNameWithoutInferringPinMigration() {
        NodeDefinitionLoader loader = new NodeDefinitionLoader();
        String source = "[{\"id\":\"runtime.names\",\"displayName\":\"Runtime Names\","
            + "\"category\":\"DATA\",\"description\":\"A valid runtime name fixture.\","
            + "\"domain\":\"automation\",\"family\":\"runtime\",\"lifecycle\":\"active\","
            + "\"handlerCapability\":\"runtime.names\",\"selectorIntent\":\"none\","
            + "\"inspectorIntent\":\"generic\",\"inputs\":[{\"id\":\"value\","
            + "\"displayName\":\"Value\",\"name\":\"input_value\",\"dataType\":\"string\","
            + "\"description\":\"Provides a value.\"}],\"outputs\":[{\"id\":\"result\","
            + "\"displayName\":\"Result\",\"dataType\":\"string\","
            + "\"description\":\"Returns a result.\"}]}]";

        List<NodeDefinition> definitions = loader.parseReplacement(
            new ByteArrayInputStream(source.getBytes(StandardCharsets.UTF_8)), "runtime-names.json");

        assertEquals(1, definitions.size());
        assertTrue(loader.getDiagnostics().stream()
            .noneMatch(value -> value.severity() == NodeDefinitionDiagnostic.Severity.ERROR), loader.getDiagnostics().toString());
        NodeDefinition definition = definitions.getFirst();
        assertEquals("value", definition.getInputs().getFirst().getName());
        assertEquals("input_value", definition.getInputs().getFirst().getRuntimeName());
        assertEquals("result", definition.getOutputs().getFirst().getName());
        assertEquals("result", definition.getOutputs().getFirst().getRuntimeName());
    }

    @Test
    void runtimeNameMustBeCanonical() {
        NodeDefinition.PinBuilder builder = new NodeDefinition.PinBuilder(PinId.of("value"), "Value",
            NodeDefinition.PinType.DATA, NodeDefinition.PinDirection.INPUT, FlowDataType.STRING)
            .runtimeName("Input Value");

        assertThrows(IllegalArgumentException.class, builder::build);
    }
}
