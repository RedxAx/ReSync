package restudio.resync.flow;

import org.junit.jupiter.api.Test;
import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowNode;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.FlowHandlerException;
import restudio.resync.flow.handler.NodeHandler;
import restudio.resync.flow.registry.NodeDefinition;
import restudio.resync.flow.registry.NodeDefinitionRegistry;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

    @Test
    void runtimeRejectsConfiguredOperationsWithoutExecutableDeclarations() throws Exception {
        HandlerRegistry handlers = new HandlerRegistry();
        NodeDefinitionRegistry definitions = new NodeDefinitionRegistry();
        handlers.register("metadataOnly", (context, node) -> {
        });
        definitions.register(new NodeDefinition.Builder("test_node", "Test Node", NodeDefinition.NodeCategory.UTILITY)
            .handler("metadataOnly")
            .handlerConfig(Map.of("operation", "mutate"))
            .build());
        FlowNode node = new FlowNode("test_node", 0, 0, Map.of());
        FlowExecutor executor = new FlowExecutor(handlers, definitions, new TypeAdapterRegistry(), Map.of());
        Method resolveHandler = FlowExecutor.class.getDeclaredMethod("resolveHandler", FlowNode.class);
        resolveHandler.setAccessible(true);
        resolveHandler.invoke(executor, node);
        Method validateOperation = FlowExecutor.class.getDeclaredMethod("validateHandlerOperation", FlowNode.class, String.class);
        validateOperation.setAccessible(true);

        FlowExecutor.FlowExecutionException failure = assertInstanceOf(FlowExecutor.FlowExecutionException.class,
            validateOperation.invoke(executor, node, "node"));

        assertEquals("OPERATION_UNAVAILABLE", failure.getCode());
        assertEquals("metadataOnly", failure.getDetails().get("handler"));
        assertEquals("mutate", failure.getDetails().get("operation"));
    }

    @Test
    void runtimePreservesStructuredHandlerFailures() throws Exception {
        FlowExecutor executor = new FlowExecutor(new HandlerRegistry(), new TypeAdapterRegistry(), Map.of());
        Method handlerFailure = FlowExecutor.class.getDeclaredMethod("handlerFailure", Exception.class, String.class, String.class, String.class, String.class);
        handlerFailure.setAccessible(true);

        FlowExecutor.FlowExecutionException failure = assertInstanceOf(FlowExecutor.FlowExecutionException.class,
            handlerFailure.invoke(executor, new FlowHandlerException("FUNCTION_NOT_FOUND", "Function not found", "Select a function",
                Map.of("functionId", "missing")), "node", "call.function", "HANDLER_EXECUTION_FAILED", "executing"));

        assertEquals("FUNCTION_NOT_FOUND", failure.getCode());
        assertEquals("Select a function", failure.getRemediation());
        assertEquals("missing", failure.getDetails().get("functionId"));
    }

    @Test
    void publicFunctionExecutionRejectsMissingStartBeforeDispatch() {
        FlowGraph function = new FlowGraph();
        function.setFunction(true);
        FlowExecutor executor = new FlowExecutor(new HandlerRegistry(), new TypeAdapterRegistry(), Map.of());

        ExecutionException failure = assertThrows(ExecutionException.class,
            () -> executor.executeFunction(function, null, null, Map.of(), Map.of()).get());

        FlowExecutor.FlowExecutionException executionFailure = assertInstanceOf(FlowExecutor.FlowExecutionException.class, failure.getCause());
        assertEquals("FUNCTION_START_MISSING", executionFailure.getCode());
        executor.shutdown();
    }

    @Test
    void migratedFunctionCallsResolveDefinitionBackedOperations() throws Exception {
        FlowNode node = new FlowNode("call.function", 0, 0, Map.of("function", "library:reward"));
        FlowGraph graph = new FlowGraph();
        graph.getNodes().put("call", node);
        FlowRuntime runtime = new FlowRuntime(graph, new TypeAdapterRegistry(), Map.of());
        NodeDefinition definition = new NodeDefinition.Builder("call.function", "Call Function", NodeDefinition.NodeCategory.FUNCTION)
            .handler("FunctionHandler")
            .handlerConfig(Map.of("operation", "call_function"))
            .build();
        FlowExecutor executor = new FlowExecutor(new HandlerRegistry(), new TypeAdapterRegistry(), Map.of());
        Method resolveFunctionCallId = FlowExecutor.class.getDeclaredMethod(
            "resolveFunctionCallId", FlowRuntime.class, FlowNode.class, NodeDefinition.class);
        resolveFunctionCallId.setAccessible(true);

        assertEquals("library:reward", resolveFunctionCallId.invoke(executor, runtime, node, definition));

        FlowNode extensionNode = new FlowNode("extension.call", 0, 0, Map.of("function", "extension:target"));
        graph.getNodes().put("extension", extensionNode);
        NodeDefinition extensionDefinition = new NodeDefinition.Builder("extension.call", "Extension Call", NodeDefinition.NodeCategory.FUNCTION)
            .handler("ExtensionHandler")
            .handlerConfig(Map.of("operation", "call_function"))
            .build();
        assertNull(resolveFunctionCallId.invoke(executor, runtime, extensionNode, extensionDefinition));
        executor.shutdown();
    }

    @Test
    void functionRecursionPolicyRejectsAnotherFrameAtTheConfiguredLimit() throws Exception {
        FlowExecutor executor = new FlowExecutor(new HandlerRegistry(), null, new TypeAdapterRegistry(), Map.of(), 100, false, 30_000L, 1);
        FlowRuntime runtime = new FlowRuntime(new FlowGraph(), new TypeAdapterRegistry(), Map.of());
        runtime.callFunction(new FlowGraph(), "caller");
        Method functionDepthFailure = FlowExecutor.class.getDeclaredMethod("functionDepthFailure", FlowRuntime.class, String.class);
        functionDepthFailure.setAccessible(true);

        FlowExecutor.FlowExecutionException failure = assertInstanceOf(FlowExecutor.FlowExecutionException.class,
            functionDepthFailure.invoke(executor, runtime, "recursive"));

        assertEquals("FUNCTION_RECURSION_LIMIT", failure.getCode());
        assertEquals(1, failure.getDetails().get("callDepth"));
        executor.shutdown();
    }
}
