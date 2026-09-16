package restudio.resync.flow;

import org.junit.jupiter.api.Test;
import restudio.flow.data.FlowConnection;
import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowNode;
import restudio.flow.data.FlowDataType;
import restudio.resync.flow.identity.PinId;
import restudio.resync.flow.registry.NodeDefinition;
import restudio.resync.flow.registry.NodeDefinitionRegistry;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowRuntimePinAliasTest {
    @Test
    void connectionsAndNodeOutputsAcceptRuntimeNamesAndStableIds() {
        NodeDefinition definition = definition();
        NodeDefinitionRegistry definitions = new NodeDefinitionRegistry(false);
        definitions.register(definition);
        FlowNode source = new FlowNode("alias.node", 0, 0, Map.of());
        FlowNode target = new FlowNode("alias.node", 0, 0, Map.of());
        FlowGraph graph = new FlowGraph("pin-aliases", Map.of("source", source, "target", target),
            List.of(new FlowConnection("source", "output_result", "target", "input_value")), List.of());
        FlowRuntime runtime = new FlowRuntime(graph, new TypeAdapterRegistry(), Map.of(), Map.of(), definitions);

        runtime.setNodeOutput("source", "output_result", "ready");

        assertEquals("ready", runtime.getNodeOutput("source", "result"));
        assertEquals("ready", runtime.getNodeOutput("source", "output_result"));
        assertEquals("ready", runtime.resolveInput(target, "value"));
        assertEquals("ready", runtime.resolveInput(target, "input_value"));
    }

    @Test
    void inputAndOutputAliasesRemainDirectionAware() {
        NodeDefinition definition = definition();

        assertTrue(FlowRuntime.inputPinMatches(definition, "input_value", "value"));
        assertTrue(FlowRuntime.outputPinMatches(definition, "output_result", "result"));
        assertFalse(FlowRuntime.inputPinMatches(definition, "flow", "output_flow"));
        assertFalse(FlowRuntime.outputPinMatches(definition, "input_flow", "flow"));
    }

    @Test
    void repeatableRuntimePrefixesMapToStableIdentities() {
        NodeDefinition definition = new NodeDefinition.Builder("repeatable.alias", "Repeatable Alias", NodeDefinition.NodeCategory.DATA)
            .input(new NodeDefinition.PinBuilder(PinId.of("values"), "Values", NodeDefinition.PinType.DATA,
                NodeDefinition.PinDirection.INPUT, FlowDataType.STRING).runtimeName("value").repeatable("values", 0, 3, "Value").build())
            .build();
        NodeDefinitionRegistry definitions = new NodeDefinitionRegistry(false);
        definitions.register(definition);
        FlowNode node = new FlowNode("repeatable.alias", 0, 0, Map.of(
            "__repeatable_count:values", 2,
            "value_2", "second"));
        FlowGraph graph = new FlowGraph("repeatable-aliases", Map.of("node", node), List.of(), List.of());
        FlowRuntime runtime = new FlowRuntime(graph, new TypeAdapterRegistry(), Map.of(), Map.of(), definitions);

        assertEquals("second", runtime.resolveInput(node, "values_2"));
        assertEquals("second", runtime.resolveInput(node, "value_2"));
        assertEquals(List.of("second"), new FlowContext(runtime, null, null).getRepeatableInputValues(node, "value", String.class));
    }

    @Test
    void triggeredRuntimeOutputIsStoredAsStableIdentity() {
        NodeDefinition definition = definition();
        NodeDefinitionRegistry definitions = new NodeDefinitionRegistry(false);
        definitions.register(definition);
        FlowNode node = new FlowNode("alias.node", 0, 0, Map.of());
        FlowGraph graph = new FlowGraph("trigger-alias", Map.of("node", node), List.of(), List.of());
        FlowRuntime runtime = new FlowRuntime(graph, new TypeAdapterRegistry(), Map.of(), Map.of(), definitions);

        runtime.triggerOutput(node, "flow");

        assertEquals("output_flow", runtime.consumeTriggeredOutput());
    }

    private NodeDefinition definition() {
        return new NodeDefinition.Builder("alias.node", "Alias Node", NodeDefinition.NodeCategory.DATA)
            .input(new NodeDefinition.PinBuilder(PinId.of("flow"), "Input Flow", NodeDefinition.PinType.FLOW,
                NodeDefinition.PinDirection.INPUT, FlowDataType.EXECUTION).runtimeName("input_flow").build())
            .input(new NodeDefinition.PinBuilder(PinId.of("value"), "Value", NodeDefinition.PinType.DATA,
                NodeDefinition.PinDirection.INPUT, FlowDataType.STRING).runtimeName("input_value").build())
            .output(new NodeDefinition.PinBuilder(PinId.of("output_flow"), "Output Flow", NodeDefinition.PinType.FLOW,
                NodeDefinition.PinDirection.OUTPUT, FlowDataType.EXECUTION).runtimeName("flow").build())
            .output(new NodeDefinition.PinBuilder(PinId.of("result"), "Result", NodeDefinition.PinType.DATA,
                NodeDefinition.PinDirection.OUTPUT, FlowDataType.STRING).runtimeName("output_result").build())
            .build();
    }
}
