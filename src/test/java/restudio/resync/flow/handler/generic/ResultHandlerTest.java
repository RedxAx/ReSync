package restudio.resync.flow.handler.generic;

import org.junit.jupiter.api.Test;
import restudio.flow.data.FlowNode;
import restudio.flow.data.FlowOperationResult;
import restudio.resync.flow.FlowContext;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResultHandlerTest {
    @Test
    void successPreservesTheTypedValue() {
        TestFlowContext context = execute("success", Map.of("value", "reward"));

        FlowOperationResult<?> result = assertInstanceOf(FlowOperationResult.class, context.outputs.get("result"));

        assertTrue(result.success());
        assertEquals("reward", result.value());
    }

    @Test
    void matchBranchesToFailureAndPublishesItsDetails() {
        FlowOperationResult<Object> failure = FlowOperationResult.failure("NOT_FOUND", "Reward Not Found", Map.of("id", "daily"));
        TestFlowContext context = execute("match", Map.of("result", failure));

        assertEquals("failure", context.triggeredOutput);
        assertEquals("NOT_FOUND", context.outputs.get("error_code"));
        assertEquals("Reward Not Found", context.outputs.get("message"));
        assertEquals(Map.of("id", "daily"), context.outputs.get("details"));
    }

    @Test
    void mapResultsRemainCompatibleAcrossSerializationBoundaries() {
        TestFlowContext context = execute("is_success", Map.of("result", Map.of(
            "success", false,
            "errorCode", "DENIED",
            "message", "Access Denied",
            "details", Map.of()
        )));

        assertFalse((Boolean) context.outputs.get("success"));
    }

    private TestFlowContext execute(String operation, Map<String, Object> inputs) {
        FlowNode node = new FlowNode("result." + operation, 0, 0, Map.of());
        node.setHandlerConfig(Map.of("operation", operation));
        TestFlowContext context = new TestFlowContext(inputs);

        new ResultHandler().execute(context, node);

        return context;
    }

    private static class TestFlowContext extends FlowContext {
        private final Map<String, Object> inputs;
        private final Map<String, Object> outputs = new HashMap<>();
        private String triggeredOutput;

        private TestFlowContext(Map<String, Object> inputs) {
            super(null, null, null);
            this.inputs = inputs;
        }

        @Override
        public <T> T getInputValue(FlowNode node, String pinName, Class<T> type, T defaultValue) {
            Object value = inputs.get(pinName);
            return value != null ? type.cast(value) : defaultValue;
        }

        @Override
        public void setOutput(FlowNode node, String pinName, Object value) {
            outputs.put(pinName, value);
        }

        @Override
        public void triggerOutput(String pinName) {
            triggeredOutput = pinName;
        }
    }
}
