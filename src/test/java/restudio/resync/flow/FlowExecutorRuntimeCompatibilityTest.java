package restudio.resync.flow;

import org.junit.jupiter.api.Test;
import restudio.flow.data.FlowNode;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.NodeHandler;
import restudio.resync.flow.registry.NodeDefinition;
import restudio.resync.flow.registry.NodeDefinitionRegistry;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FlowExecutorRuntimeCompatibilityTest {
    @Test
    void executionRehydratesHandlerConfigFromNodeDefinition() throws Exception {
        HandlerRegistry handlers = new HandlerRegistry();
        NodeDefinitionRegistry definitions = new NodeDefinitionRegistry();
        AtomicReference<String> operation = new AtomicReference<>();

        handlers.register("testHandler", (ctx, node) -> operation.set(node.getHandlerConfig().getString("operation")));
        definitions.register(new NodeDefinition.Builder("test_node", "Test Node", NodeDefinition.NodeCategory.UTILITY)
            .handler("testHandler")
            .handlerConfig(Map.of("operation", "expectedOperation"))
            .build());

        FlowNode node = new FlowNode("test_node", 0, 0, Map.of());
        FlowExecutor executor = new FlowExecutor(handlers, definitions, new TypeAdapterRegistry(), Map.of());
        Method resolveHandler = FlowExecutor.class.getDeclaredMethod("resolveHandler", FlowNode.class);
        resolveHandler.setAccessible(true);

        Object handler = resolveHandler.invoke(executor, node);
        ((NodeHandler) handler).execute(null, node);

        assertEquals("expectedOperation", operation.get());
    }
}
