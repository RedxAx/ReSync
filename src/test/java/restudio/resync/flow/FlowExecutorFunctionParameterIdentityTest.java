package restudio.resync.flow;

import org.junit.jupiter.api.Test;
import restudio.flow.data.FlowDataType;
import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowNode;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.identity.FunctionParameterId;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class FlowExecutorFunctionParameterIdentityTest {
    @Test
    void reorderedRenamedArgumentsRemainBoundByParameterId() throws Exception {
        FunctionParameterId firstId = FunctionParameterId.deterministic("executor-first");
        FunctionParameterId secondId = FunctionParameterId.deterministic("executor-second");
        FlowGraph function = new FlowGraph();
        function.setId("reordered-function");
        function.setFunctionInputs(List.of(
            new FlowGraph.FunctionParameter(secondId, "renamedSecond", FlowDataType.STRING),
            new FlowGraph.FunctionParameter(firstId, "renamedFirst", FlowDataType.STRING)));

        FlowGraph caller = new FlowGraph();
        FlowNode call = new FlowNode("call.function", 0, 0, Map.of("arguments", Map.of(
            secondId.canonicalText(), "second-value",
            firstId.canonicalText(), "first-value")));
        caller.getNodes().put("call", call);
        FlowRuntime runtime = new FlowRuntime(caller, new TypeAdapterRegistry(), Map.of());
        FlowExecutor executor = new FlowExecutor(new HandlerRegistry(), new TypeAdapterRegistry(), Map.of());

        Method resolve = FlowExecutor.class.getDeclaredMethod("resolveFunctionInputFrame", FlowRuntime.class,
            FlowNode.class, String.class, FlowGraph.class);
        resolve.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<FunctionParameterId, Object> frame = (Map<FunctionParameterId, Object>) resolve.invoke(executor, runtime, call, "call", function);

        assertEquals("first-value", frame.get(firstId));
        assertEquals("second-value", frame.get(secondId));

        Method validate = FlowExecutor.class.getDeclaredMethod("validateFunctionInputFrame", FlowGraph.class, Map.class, String.class);
        validate.setAccessible(true);
        assertNull(validate.invoke(executor, function, frame, "call"));
        assertEquals(List.of(secondId, firstId), frame.keySet().stream().toList());
        executor.shutdown();
    }

    @Test
    void modernContractDoesNotResolveRenamedArgumentsByDisplayName() throws Exception {
        FunctionParameterId firstId = FunctionParameterId.deterministic("executor-contract-first");
        FunctionParameterId secondId = FunctionParameterId.deterministic("executor-contract-second");
        FlowGraph function = new FlowGraph();
        function.setId("reordered-contract-function");
        function.setFunctionInputs(List.of(
            new FlowGraph.FunctionParameter(secondId, "renamedSecond", FlowDataType.STRING),
            new FlowGraph.FunctionParameter(firstId, "renamedFirst", FlowDataType.STRING)));
        FlowNode call = new FlowNode("call.function", 0, 0, Map.of(
            "__call_parameters", List.of(Map.of("name", "renamedFirst", "type", "string"))));
        FlowExecutor executor = new FlowExecutor(new HandlerRegistry(), new TypeAdapterRegistry(), Map.of());

        Method validate = FlowExecutor.class.getDeclaredMethod("validateFunctionCallContractById", FlowNode.class,
            FlowGraph.class, String.class);
        validate.setAccessible(true);
        FlowExecutor.FlowExecutionException failure = assertInstanceOf(FlowExecutor.FlowExecutionException.class,
            validate.invoke(executor, call, function, "call"));

        assertNotNull(failure);
        assertEquals("FUNCTION_CALL_ARGUMENT_UNKNOWN", failure.getCode());
        executor.shutdown();
    }

    @Test
    void legacyCompatibilityBoundaryResolvesSpacedNamesThroughFunctionMetadata() throws Exception {
        FlowGraph function = new FlowGraph();
        function.setId("legacy-spaced-function");
        function.setFunction(true);
        function.setFunctionInputs(List.of(new FlowGraph.FunctionParameter("Reward Amount", FlowDataType.STRING)));
        FlowNode call = new FlowNode("call.function", 0, 0, Map.of("arguments", Map.of("Reward Amount", "value")));
        FlowGraph caller = new FlowGraph();
        caller.getNodes().put("call", call);
        FlowRuntime runtime = new FlowRuntime(caller, new TypeAdapterRegistry(), Map.of());
        FlowExecutor executor = new FlowExecutor(new HandlerRegistry(), new TypeAdapterRegistry(), Map.of());

        Method resolve = FlowExecutor.class.getDeclaredMethod("resolveLegacyFunctionInputFrame", FlowRuntime.class,
            FlowNode.class, String.class, FlowGraph.class);
        resolve.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<FunctionParameterId, Object> frame = (Map<FunctionParameterId, Object>) resolve.invoke(executor, runtime, call,
            "call", function);

        FunctionParameterId id = function.getFunctionInputs().getFirst().getParameterId();
        assertEquals("value", frame.get(id));
        executor.shutdown();
    }
}
