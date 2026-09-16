package restudio.resync.flow.handler.generic;

import org.junit.jupiter.api.Test;
import restudio.flow.data.FlowNode;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.NodeHandler;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GenericMapHandlerTest {
    @Test
    void setCopiesConnectedMapAndPublishesUpdatedValue() {
        Map<String, Object> source = Map.of("first", 1);
        TestFlowContext context = execute("set", Map.of("map", source, "key", "second", "value", 2));

        assertEquals(Map.of("first", 1), source);
        assertEquals(Map.of("first", 1, "second", 2), context.outputs.get("output_map"));
    }

    @Test
    void mergeReadsAuthoredInputIdsAndPublishesOutputMapResult() {
        TestFlowContext context = execute("merge", Map.of(
            "map_a", Map.of("first", 1),
            "map_b", Map.of("second", 2)
        ));

        assertEquals(Map.of("first", 1, "second", 2), context.outputs.get("output_map"));
    }

    @Test
    void mapMutationOperationsPublishAuthoredOutputId() {
        assertEquals(Map.of("first", 1), execute("remove", Map.of(
            "map", Map.of("first", 1, "second", 2), "key", "second"
        )).outputs.get("output_map"));
        assertEquals(Map.of(), execute("clear", Map.of(
            "map", Map.of("first", 1)
        )).outputs.get("output_map"));
        assertEquals(Map.of("first", 1, "second", 2), execute("put_all", Map.of(
            "map", Map.of("first", 1), "other", Map.of("second", 2)
        )).outputs.get("output_map"));
    }

    @Test
    void containsValueReadsPublishedValuePin() {
        TestFlowContext context = execute("contains_value", Map.of("map", Map.of("key", "value"), "value", "value"));

        assertEquals(true, context.outputs.get("contains"));
    }

    @Test
    void unknownOperationFailsExplicitly() {
        assertThrows(IllegalArgumentException.class, () -> execute("missing", Map.of()));
    }

    private TestFlowContext execute(String operation, Map<String, Object> inputs) {
        HandlerRegistry registry = new HandlerRegistry();
        new GenericMapHandler().registerTo(registry);
        NodeHandler handler = registry.getHandler("GenericMapHandler");
        FlowNode node = new FlowNode("core.map." + operation, 0, 0, Map.of());
        node.setHandlerConfig(Map.of("operation", operation));
        TestFlowContext context = new TestFlowContext(inputs);
        handler.execute(context, node);
        return context;
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
            if (value == null) return defaultValue;
            return type != null ? type.cast(value) : (T) value;
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
