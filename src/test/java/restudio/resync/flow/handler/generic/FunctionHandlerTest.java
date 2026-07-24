package restudio.resync.flow.handler.generic;

import org.junit.jupiter.api.Test;
import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowNode;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.FlowRuntime;
import restudio.resync.flow.TypeAdapterRegistry;
import restudio.resync.flow.handler.FlowHandlerException;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FunctionHandlerTest {
    @Test
    void directFunctionDispatchFailsInsteadOfMutatingRuntimeState() {
        FunctionHandler handler = new FunctionHandler();
        FlowNode node = node("call_function", Map.of("function", "library:missing"));

        FlowHandlerException failure = assertThrows(FlowHandlerException.class, () -> handler.execute(context(), node));

        assertEquals("FUNCTION_DISPATCH_INVALID", failure.getCode());
    }

    @Test
    void returnOutsideAFunctionFailsExplicitly() {
        FunctionHandler handler = new FunctionHandler();
        FlowNode node = node("return_value", Map.of("value", "done"));

        FlowHandlerException failure = assertThrows(FlowHandlerException.class, () -> handler.execute(context(), node));

        assertEquals("FUNCTION_RETURN_OUTSIDE_CALL", failure.getCode());
    }

    @Test
    void unnamedFunctionBoundariesFailExplicitly() {
        FunctionHandler handler = new FunctionHandler();

        FlowHandlerException inputFailure = assertThrows(FlowHandlerException.class,
            () -> handler.execute(context(), node("function_input", Map.of())));
        FlowHandlerException outputFailure = assertThrows(FlowHandlerException.class,
            () -> handler.execute(context(), node("function_output", Map.of("value", "done"))));

        assertEquals("FUNCTION_INPUT_NAME_REQUIRED", inputFailure.getCode());
        assertEquals("FUNCTION_OUTPUT_NAME_REQUIRED", outputFailure.getCode());
    }

    @Test
    void namedArgumentsCanBeBuiltAndFunctionResultsCanBeRead() {
        FunctionHandler handler = new FunctionHandler();
        FlowNode argument = node("argument", Map.of(
            "arguments", Map.of("player", "Alex"),
            "name", "item",
            "value", "diamond"
        ));
        FlowContext argumentContext = context("argument", argument);

        handler.execute(argumentContext, argument);

        assertEquals(Map.of("player", "Alex", "item", "diamond"), argumentContext.getOutput(argument, "arguments"));

        FlowNode result = node("result", Map.of(
            "results", Map.of("message", "Done"),
            "name", "message"
        ));
        FlowContext resultContext = context("result", result);

        handler.execute(resultContext, result);

        assertEquals("Done", resultContext.getOutput(result, "value"));
    }

    private FlowContext context() {
        FlowGraph graph = new FlowGraph("test", Map.of(), List.of(), List.of());
        return new FlowContext(new FlowRuntime(graph, new TypeAdapterRegistry(), Map.of()), null, null);
    }

    private FlowContext context(String nodeId, FlowNode node) {
        FlowGraph graph = new FlowGraph("test", Map.of(nodeId, node), List.of(), List.of());
        return new FlowContext(new FlowRuntime(graph, new TypeAdapterRegistry(), Map.of()), null, null);
    }

    private FlowNode node(String operation, Map<String, Object> inputs) {
        FlowNode node = new FlowNode("test", 0, 0, inputs);
        node.setHandlerConfig(Map.of("operation", operation));
        return node;
    }
}
