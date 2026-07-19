package restudio.resync.flow.handler.generic;

import org.junit.jupiter.api.Test;
import restudio.flow.data.FlowNode;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.NodeHandler;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class GenericMathHandlerTest {
    @Test
    void visibleUnaryAndBoundedOperationsPublishTheirDeclaredPins() {
        Map<String, String> outputs = Map.ofEntries(
            Map.entry("clamp", "clamped"),
            Map.entry("abs", "absolute"),
            Map.entry("min", "min"),
            Map.entry("max", "max"),
            Map.entry("round", "rounded"),
            Map.entry("floor", "floored"),
            Map.entry("ceil", "ceiling"),
            Map.entry("sqrt", "sqrt"),
            Map.entry("to_radians", "radians"),
            Map.entry("to_degrees", "degrees"),
            Map.entry("sin", "sin"),
            Map.entry("cos", "cos"),
            Map.entry("tan", "tan")
        );
        HandlerRegistry registry = new HandlerRegistry();
        new GenericMathHandler().registerTo(registry);
        NodeHandler handler = registry.getHandler("GenericMathHandler");

        for (Map.Entry<String, String> entry : outputs.entrySet()) {
            FlowNode node = new FlowNode("math." + entry.getKey(), 0, 0, Map.of());
            node.setHandlerConfig(Map.of("operation", entry.getKey()));
            TestFlowContext context = new TestFlowContext(Map.ofEntries(
                Map.entry("value", 4.0),
                Map.entry("values_list", List.of(4.0, 2.0)),
                Map.entry("decimal_places", 2),
                Map.entry("min", 1.0),
                Map.entry("max", 3.0),
                Map.entry("degrees", 180.0),
                Map.entry("radians", Math.PI),
                Map.entry("angle_degrees", 90.0)
            ));

            handler.execute(context, node);

            assertTrue(context.outputs.containsKey(entry.getValue()), entry.getKey() + " should publish " + entry.getValue());
        }
    }

    private static class TestFlowContext extends FlowContext {
        private final Map<String, Object> inputs;
        private final Map<String, Object> outputs = new HashMap<>();

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
        }
    }
}
