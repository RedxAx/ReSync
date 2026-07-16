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

class GenericListHandlerTest {
    @Test
    void getPublishesValueThroughRegisteredOutputPin() {
        HandlerRegistry registry = new HandlerRegistry();
        new GenericListHandler().registerTo(registry);
        NodeHandler handler = registry.getHandler("GenericListHandler");
        FlowNode node = new FlowNode("list.get", 0, 0, Map.of());
        node.setHandlerConfig(Map.of("operation", "get"));
        TestFlowContext context = new TestFlowContext(Map.of("list", List.of("name", "#AAAAAA"), "index", 1));

        handler.execute(context, node);

        assertEquals("#AAAAAA", context.outputs.get("value"));
        assertEquals("flow", context.triggeredOutput);
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
