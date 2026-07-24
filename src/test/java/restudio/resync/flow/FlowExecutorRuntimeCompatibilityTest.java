package restudio.resync.flow;

import org.junit.jupiter.api.Test;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import restudio.flow.data.FlowDataType;
import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowNode;
import restudio.flow.data.FlowOperationResult;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.FlowHandlerException;
import restudio.resync.flow.handler.NodeHandler;
import restudio.resync.flow.registry.NodeDefinition;
import restudio.resync.flow.registry.NodeDefinitionRegistry;

import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
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
    void programmaticFunctionArgumentsRemainUnlessAnExplicitPinOverridesThem() throws Exception {
        Map<String, Object> values = new HashMap<>();
        values.put("arguments", Map.of("player", "map-player", "item", "map-item", "function", "nested-function"));
        values.put("player", "pin-player");
        FlowNode node = new FlowNode("call.function", 0, 0, values);
        FlowGraph caller = new FlowGraph();
        caller.getNodes().put("call", node);
        FlowGraph function = new FlowGraph();
        function.setFunctionInputs(List.of(
            new FlowGraph.FunctionParameter("player", FlowDataType.STRING),
            new FlowGraph.FunctionParameter("item", FlowDataType.STRING),
            new FlowGraph.FunctionParameter("function", FlowDataType.STRING)
        ));
        FlowRuntime runtime = new FlowRuntime(caller, new TypeAdapterRegistry(), Map.of());
        FlowExecutor executor = new FlowExecutor(new HandlerRegistry(), new TypeAdapterRegistry(), Map.of());
        Method resolveFunctionInputs = FlowExecutor.class.getDeclaredMethod(
            "resolveFunctionInputs", FlowRuntime.class, FlowNode.class, String.class, FlowGraph.class);
        resolveFunctionInputs.setAccessible(true);

        Map<?, ?> resolved = assertInstanceOf(Map.class, resolveFunctionInputs.invoke(executor, runtime, node, "call", function));

        assertEquals("pin-player", resolved.get("player"));
        assertEquals("map-item", resolved.get("item"));
        assertEquals("nested-function", resolved.get("function"));
        executor.shutdown();
    }

    @Test
    void namedCallArgumentsMustMatchTheSelectedFunctionContract() throws Exception {
        FlowNode node = new FlowNode("call.function", 0, 0, Map.of(
            "__call_parameters", List.of(Map.of("name", "amount", "type", "integer"))
        ));
        FlowGraph function = new FlowGraph();
        function.setId("reward");
        function.setFunctionInputs(List.of(new FlowGraph.FunctionParameter("amount", FlowDataType.INTEGER)));
        FlowExecutor executor = new FlowExecutor(new HandlerRegistry(), new TypeAdapterRegistry(), Map.of());
        Method validateContract = FlowExecutor.class.getDeclaredMethod(
            "validateFunctionCallContract", FlowNode.class, FlowGraph.class, String.class);
        validateContract.setAccessible(true);

        assertNull(validateContract.invoke(executor, node, function, "call"));

        node.getInputValues().put("__call_parameters", List.of(Map.of("name", "amount", "type", "string")));
        FlowExecutor.FlowExecutionException failure = assertInstanceOf(FlowExecutor.FlowExecutionException.class,
            validateContract.invoke(executor, node, function, "call"));

        assertEquals("FUNCTION_CALL_ARGUMENT_TYPE_MISMATCH", failure.getCode());
        executor.shutdown();
    }

    @Test
    void oneFunctionInputAcceptsADirectValue() throws Exception {
        FlowNode node = new FlowNode("call.function", 0, 0, Map.of("arguments", "Alex"));
        FlowGraph caller = new FlowGraph();
        caller.getNodes().put("call", node);
        FlowGraph function = new FlowGraph();
        function.setFunctionInputs(List.of(new FlowGraph.FunctionParameter("player", FlowDataType.PLAYER)));
        FlowRuntime runtime = new FlowRuntime(caller, new TypeAdapterRegistry(), Map.of());
        FlowExecutor executor = new FlowExecutor(new HandlerRegistry(), new TypeAdapterRegistry(), Map.of());
        Method resolveFunctionInputs = FlowExecutor.class.getDeclaredMethod(
            "resolveFunctionInputs", FlowRuntime.class, FlowNode.class, String.class, FlowGraph.class);
        resolveFunctionInputs.setAccessible(true);

        Map<?, ?> resolved = assertInstanceOf(Map.class, resolveFunctionInputs.invoke(executor, runtime, node, "call", function));

        assertEquals("Alex", resolved.get("player"));
        executor.shutdown();
    }

    @Test
    void multipleFunctionInputsRequireNamedArguments() throws Exception {
        FlowNode node = new FlowNode("call.function", 0, 0, Map.of("arguments", "Alex"));
        FlowGraph caller = new FlowGraph();
        caller.getNodes().put("call", node);
        FlowGraph function = new FlowGraph();
        function.setId("reward");
        function.setFunctionInputs(List.of(
            new FlowGraph.FunctionParameter("player", FlowDataType.PLAYER),
            new FlowGraph.FunctionParameter("item", FlowDataType.ITEM)
        ));
        FlowRuntime runtime = new FlowRuntime(caller, new TypeAdapterRegistry(), Map.of());
        FlowExecutor executor = new FlowExecutor(new HandlerRegistry(), new TypeAdapterRegistry(), Map.of());
        Method resolveFunctionInputs = FlowExecutor.class.getDeclaredMethod(
            "resolveFunctionInputs", FlowRuntime.class, FlowNode.class, String.class, FlowGraph.class);
        resolveFunctionInputs.setAccessible(true);

        InvocationTargetException invocation = assertThrows(InvocationTargetException.class,
            () -> resolveFunctionInputs.invoke(executor, runtime, node, "call", function));
        FlowExecutor.FlowExecutionException failure = assertInstanceOf(FlowExecutor.FlowExecutionException.class, invocation.getCause());

        assertEquals("FUNCTION_ARGUMENTS_NEED_NAMES", failure.getCode());
        executor.shutdown();
    }

    @Test
    void dynamicArgumentsAreCheckedAgainstTheSelectedFunctionSignature() throws Exception {
        FlowGraph function = new FlowGraph();
        function.setId("reward");
        function.setFunction(true);
        FlowGraph.FunctionParameter amount = new FlowGraph.FunctionParameter("amount", FlowDataType.INTEGER);
        FlowGraph.FunctionParameter reason = new FlowGraph.FunctionParameter("reason", FlowDataType.STRING);
        reason.setDefaultValue("daily");
        function.setFunctionInputs(List.of(amount, reason));
        FlowExecutor executor = new FlowExecutor(new HandlerRegistry(), new TypeAdapterRegistry(), Map.of());
        Method validateFunctionInputs = FlowExecutor.class.getDeclaredMethod(
            "validateFunctionInputs", FlowGraph.class, Map.class, String.class);
        validateFunctionInputs.setAccessible(true);

        Map<String, Object> valid = new HashMap<>(Map.of("amount", 4));
        assertNull(validateFunctionInputs.invoke(executor, function, valid, "call"));
        assertEquals("daily", valid.get("reason"));

        FlowExecutor.FlowExecutionException missing = assertInstanceOf(FlowExecutor.FlowExecutionException.class,
            validateFunctionInputs.invoke(executor, function, new HashMap<>(), "call"));
        assertEquals("FUNCTION_ARGUMENT_REQUIRED", missing.getCode());

        FlowExecutor.FlowExecutionException unknown = assertInstanceOf(FlowExecutor.FlowExecutionException.class,
            validateFunctionInputs.invoke(executor, function, new HashMap<>(Map.of("amount", 4, "extra", true)), "call"));
        assertEquals("FUNCTION_ARGUMENT_UNKNOWN", unknown.getCode());
        executor.shutdown();
    }

    @Test
    void recoverableFunctionCallsPublishAFailedResult() throws Exception {
        FlowGraph graph = new FlowGraph();
        FlowNode node = new FlowNode("call.function", 0, 0, Map.of("continue_on_failure", true));
        graph.getNodes().put("call", node);
        FlowRuntime runtime = new FlowRuntime(graph, new TypeAdapterRegistry(), Map.of());
        FlowExecutor executor = new FlowExecutor(new HandlerRegistry(), new TypeAdapterRegistry(), Map.of());
        Method recoverFunctionCall = FlowExecutor.class.getDeclaredMethod(
            "recoverFunctionCall", FlowRuntime.class, String.class, int.class, Throwable.class,
            Player.class, Event.class, int.class);
        recoverFunctionCall.setAccessible(true);
        FlowExecutor.FlowExecutionException failure = new FlowExecutor.FlowExecutionException(
            "FUNCTION_NOT_FOUND", "Function Not Found", null, "call", "Select a function");

        Object future = recoverFunctionCall.invoke(executor, runtime, "call", 0, failure, null, null, 0);
        assertInstanceOf(CompletableFuture.class, future);
        ((CompletableFuture<?>) future).get();
        FlowOperationResult<?> result = assertInstanceOf(FlowOperationResult.class, runtime.getNodeOutput("call", "result"));

        assertEquals("FUNCTION_NOT_FOUND", result.errorCode());
        assertEquals(Map.of(), runtime.getNodeOutput("call", "results"));
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
