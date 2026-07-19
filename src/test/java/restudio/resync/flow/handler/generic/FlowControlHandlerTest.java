package restudio.resync.flow.handler.generic;

import org.junit.jupiter.api.Test;
import restudio.flow.data.FlowNode;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.NodeHandler;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowControlHandlerTest {
    @Test
    void switchCaseContinuesFlowWithMatchedIndex() {
        TestFlowContext context = executeSwitchCase("second", List.of("first", "second", "third"));

        assertEquals(true, context.outputs.get("matched"));
        assertEquals(1, context.outputs.get("index"));
        assertEquals("flow", context.triggeredOutput);
    }

    @Test
    void switchCaseContinuesFlowWithoutMatch() {
        TestFlowContext context = executeSwitchCase("missing", List.of("first", "second", "third"));

        assertEquals(false, context.outputs.get("matched"));
        assertEquals(-1, context.outputs.get("index"));
        assertEquals("flow", context.triggeredOutput);
    }

    @Test
    void switchCaseMatchesEquivalentNumericRepresentations() {
        TestFlowContext context = executeSwitchCase(2.0D, List.of(1, 2L, 3.0F));

        assertEquals(true, context.outputs.get("matched"));
        assertEquals(1, context.outputs.get("index"));
        assertEquals("flow", context.triggeredOutput);
    }

    @Test
    void executorManagedLoopOperationsAreAdvertisedToValidation() {
        HandlerRegistry registry = new HandlerRegistry();
        new FlowControlHandler().registerTo(registry);

        for (String operation : List.of("loop", "loop_count", "loop_for_each", "loop_for_each_player", "loop_for_each_entity", "loop_interval", "loop_while")) {
            assertTrue(registry.hasOperation("FlowControlHandler", operation), operation);
        }
    }

    private TestFlowContext executeSwitchCase(Object value, List<?> cases) {
        HandlerRegistry registry = new HandlerRegistry();
        new FlowControlHandler().registerTo(registry);
        NodeHandler handler = registry.getHandler("FlowControlHandler");
        FlowNode node = new FlowNode("flow.switch_case", 0, 0, Map.of());
        node.setHandlerConfig(Map.of("operation", "switch_case"));
        TestFlowContext context = new TestFlowContext(Map.of("value", value, "cases", cases));

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
        public void triggerOutput(String pinName) {
            triggeredOutput = pinName;
        }
    }
}
