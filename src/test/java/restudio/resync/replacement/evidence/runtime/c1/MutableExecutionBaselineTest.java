package restudio.resync.replacement.evidence.runtime.c1;

import org.junit.jupiter.api.Test;
import restudio.flow.data.FlowDataType;
import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowNode;
import restudio.resync.flow.FlowExecutor;
import restudio.resync.flow.FlowRuntime;
import restudio.resync.flow.TypeAdapterRegistry;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.registry.NodeDefinition;
import restudio.resync.flow.registry.NodeDefinitionRegistry;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MutableExecutionBaselineTest {
    @Test
    void handlerResolutionMutatesTheCallerNodeFromTheLiveDefinition() throws Exception {
        HandlerRegistry handlers = new HandlerRegistry();
        NodeDefinitionRegistry definitions = new NodeDefinitionRegistry();
        handlers.register("runtime", (context, node) -> { });
        definitions.register(new NodeDefinition.Builder("canonical", "Canonical", NodeDefinition.NodeCategory.UTILITY)
            .handler("runtime")
            .handlerConfig(Map.of("operation", "current"))
            .build());
        FlowNode node = new FlowNode("legacy", 0, 0, Map.of());
        FlowExecutor executor = new FlowExecutor(handlers, definitions, new TypeAdapterRegistry(), Map.of());

        Method resolveHandler = FlowExecutor.class.getDeclaredMethod("resolveHandler", FlowNode.class);
        resolveHandler.setAccessible(true);
        definitions.register(new NodeDefinition.Builder("legacy", "Legacy", NodeDefinition.NodeCategory.UTILITY)
            .handler("runtime")
            .handlerConfig(Map.of("operation", "current"))
            .build());
        resolveHandler.invoke(executor, node);

        assertEquals("legacy", node.getType());
        assertEquals("current", node.getHandlerConfig().getString("operation"));
        executor.shutdown();
    }

    @Test
    void missingLiteralReadsTheCurrentDefinitionDefault() {
        NodeDefinitionRegistry definitions = new NodeDefinitionRegistry();
        definitions.register(new NodeDefinition.Builder("node", "Node", NodeDefinition.NodeCategory.UTILITY)
            .input(new NodeDefinition.PinBuilder("value", NodeDefinition.PinType.DATA, NodeDefinition.PinDirection.INPUT, FlowDataType.STRING)
                .defaultValue("first").build())
            .build());
        FlowNode node = new FlowNode("node", 0, 0, Map.of());
        FlowRuntime runtime = new FlowRuntime(new FlowGraph(), new TypeAdapterRegistry(), Map.of(), Map.of(), definitions);

        assertEquals("first", runtime.resolveInput(node, "value", String.class));
        definitions.unregisterPlugin(definitions.defaultPluginId());
        definitions.register(new NodeDefinition.Builder("node", "Node", NodeDefinition.NodeCategory.UTILITY)
            .input(new NodeDefinition.PinBuilder("value", NodeDefinition.PinType.DATA, NodeDefinition.PinDirection.INPUT, FlowDataType.STRING)
                .defaultValue("second").build())
            .build());
        assertEquals("second", runtime.resolveInput(node, "value", String.class));
    }
}
