package restudio.resync.flow.handler.generic;

import org.junit.jupiter.api.Test;
import restudio.flow.data.FlowNode;
import restudio.flow.data.FlowOperationResult;
import restudio.resync.flow.FlowContext;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ScheduleHandlerTest {
    @Test
    void invalidCronPatternProducesStructuredFailureBranch() {
        TestFlowContext context = execute("cron", Map.of(
            "expression", "61 12 * * *",
            "time_zone", "UTC",
            "flow_id", "daily"
        ));

        FlowOperationResult<?> result = assertInstanceOf(FlowOperationResult.class, context.outputs.get("result"));
        assertFalse(result.success());
        assertEquals("SCHEDULE_INPUT_INVALID", context.outputs.get("error_code"));
        assertEquals("failed", context.triggeredOutput);
    }

    @Test
    void overflowingDelayProducesStructuredFailureBranch() {
        TestFlowContext context = execute("delay", Map.of("seconds", Long.MAX_VALUE));

        assertEquals("SCHEDULE_OVERFLOW", context.outputs.get("error_code"));
        assertEquals("failed", context.triggeredOutput);
    }

    @Test
    void convertsFractionalSecondsWithoutUsingServerTicks() {
        ScheduleHandler handler = new ScheduleHandler(null);

        assertEquals(1500L, handler.delayMillis(1.5D));
        assertThrows(IllegalArgumentException.class, () -> handler.delayMillis(-2D));
    }

    @Test
    void rejectsNonFiniteDelayDurations() {
        ScheduleHandler handler = new ScheduleHandler(null);

        assertThrows(IllegalArgumentException.class, () -> handler.delayMillis(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> handler.delayMillis(Double.POSITIVE_INFINITY));
    }

    private TestFlowContext execute(String operation, Map<String, Object> inputs) {
        ScheduleHandler handler = new ScheduleHandler(null, Clock.fixed(Instant.parse("2026-07-16T00:00:00Z"), ZoneOffset.UTC));
        FlowNode node = new FlowNode("schedule.test", 0, 0, Map.of());
        node.setHandlerConfig(Map.of("operation", operation));
        TestFlowContext context = new TestFlowContext(inputs);
        handler.execute(context, node);
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
        public Object getOutput(FlowNode node, String pinName) {
            return outputs.get(pinName);
        }

        @Override
        public void triggerOutput(String pinName) {
            triggeredOutput = pinName;
        }
    }
}
