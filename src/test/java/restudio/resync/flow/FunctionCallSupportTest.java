package restudio.resync.flow;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import restudio.flow.data.FlowGraph;
import restudio.resync.flow.handler.FlowHandlerException;
import restudio.resync.flow.handler.HandlerRegistry;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FunctionCallSupportTest {
    @Test
    void configuredCallsFailWhenTheExecutorIsUnavailable() {
        JsonObject call = new JsonObject();
        call.addProperty("functionId", "library:reward");

        FlowHandlerException failure = assertThrows(FlowHandlerException.class,
            () -> FunctionCallSupport.execute(null, null, call, null, null, Map.of()));

        assertEquals("FUNCTION_EXECUTOR_UNAVAILABLE", failure.getCode());
    }

    @Test
    void configuredCallsFailWhenTheFunctionIsUnavailable() {
        JsonObject call = new JsonObject();
        call.addProperty("functionId", "library:missing");
        FlowExecutor executor = new FlowExecutor(new HandlerRegistry(), new TypeAdapterRegistry(), Map.of());

        FlowHandlerException failure = assertThrows(FlowHandlerException.class,
            () -> FunctionCallSupport.execute(null, executor, call, null, null, Map.of()));

        assertEquals("FUNCTION_NOT_FOUND", failure.getCode());
        executor.shutdown();
    }

    @Test
    void evaluationPreservesStructuredExecutorFailures() {
        FlowGraph function = new FlowGraph();
        function.setId("inline_missing_start");
        JsonObject call = new JsonObject();
        call.addProperty("type", "inlineFunction");
        call.add("graph", new Gson().toJsonTree(function));
        FlowExecutor executor = new FlowExecutor(new HandlerRegistry(), new TypeAdapterRegistry(), Map.of());

        FlowHandlerException failure = assertThrows(FlowHandlerException.class,
            () -> FunctionCallSupport.evaluate(null, executor, call, null, null, Map.of()));

        assertEquals("FUNCTION_START_MISSING", failure.getCode());
        executor.shutdown();
    }
}
