package restudio.resync.flow.handler.generic;

import org.junit.jupiter.api.Test;
import restudio.flow.data.FlowNode;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.handler.NodeHandler;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NodeValueCompatibilityTest {
    @Test
    void randomIntegersSupportTheEntireInclusiveRangeAndReversedBounds() {
        RandomHandler handler = new RandomHandler();
        for (String operation : List.of("random_int", "random_number")) {
            for (int i = 0; i < 100; i++) {
                Object number = execute(handler, operation, Map.of("min", Integer.MAX_VALUE, "max", Integer.MIN_VALUE)).outputs.get(operation.equals("random_int") ? "number" : "result");
                assertTrue(number instanceof Integer);
            }
            Context only = execute(handler, operation, Map.of("min", Integer.MAX_VALUE, "max", Integer.MAX_VALUE));
            assertEquals(Integer.MAX_VALUE, only.outputs.get(operation.equals("random_int") ? "number" : "result"));
        }
    }

    @Test
    void weightedChoiceAcceptsIntegerAndLongWeightsAndNeverSelectsZeroWeight() {
        GenericMathHandler handler = new GenericMathHandler();
        for (int i = 0; i < 100; i++) {
            Context context = execute(handler, "random_choice_weighted", Map.of("items_list", List.of("zero", "chosen"), "weights_list", List.of(0L, 7)));
            assertEquals("chosen", context.outputs.get("chosen_item"));
        }
        Context large = execute(handler, "random_choice_weighted", Map.of("items_list", List.of("first", "second"), "weights_list", List.of(Double.MAX_VALUE, Double.MAX_VALUE)));
        assertTrue(List.of("first", "second").contains(large.outputs.get("chosen_item")));
        Context zero = execute(handler, "random_choice_weighted", Map.of("items_list", List.of("zero"), "weights_list", List.of(0)));
        assertTrue(zero.outputs.containsKey("chosen_item"));
        assertEquals(null, zero.outputs.get("chosen_item"));
    }

    @Test
    void invalidWeightsAndUnboundedRandomHexFailBeforePublishing() {
        GenericMathHandler math = new GenericMathHandler();
        for (Object invalid : List.of(-1, Double.NaN, Double.POSITIVE_INFINITY, "1")) {
            assertThrows(IllegalArgumentException.class, () -> execute(math, "random_choice_weighted", Map.of("items_list", List.of("item"), "weights_list", List.of(invalid))));
        }
        RandomHandler random = new RandomHandler();
        assertThrows(IllegalArgumentException.class, () -> execute(random, "random_hex", Map.of("length", Integer.MAX_VALUE)));
        assertThrows(IllegalArgumentException.class, () -> execute(random, "random_double", Map.of("min", Double.NaN)));
    }

    @Test
    void scalarOverflowFailsAndDistanceAvoidsIntermediateOverflow() {
        GenericMathHandler handler = new GenericMathHandler();
        assertThrows(IllegalArgumentException.class, () -> execute(handler, "add", Map.of("a", Double.MAX_VALUE, "b", Double.MAX_VALUE)));
        assertThrows(IllegalArgumentException.class, () -> execute(handler, "multiply", Map.of("a", Double.MAX_VALUE, "b", 2.0)));
        assertThrows(IllegalArgumentException.class, () -> execute(handler, "hypotenuse", Map.of("a", Double.MAX_VALUE, "b", Double.MAX_VALUE)));
        assertEquals(0.0, execute(handler, "lerp", Map.of("a", -Double.MAX_VALUE, "b", Double.MAX_VALUE, "t", 0.5)).outputs.get("result"));
        assertEquals(Double.MAX_VALUE, execute(handler, "lerp", Map.of("a", Double.MAX_VALUE, "b", Double.MAX_VALUE, "t", 0.5)).outputs.get("result"));
        for (String operation : List.of("round", "round_decimal")) {
            assertEquals(Double.MAX_VALUE, execute(handler, operation, Map.of("value", Double.MAX_VALUE, "decimal_places", 15)).outputs.get("rounded"));
            assertEquals(-1.0, execute(handler, operation, Map.of("value", -1.5)).outputs.get("rounded"));
        }
        for (String operation : List.of("random", "random_range")) {
            assertTrue(Double.isFinite((Double) execute(handler, operation, Map.of("min", -Double.MAX_VALUE, "max", Double.MAX_VALUE)).outputs.get("result")));
            assertEquals(Double.MAX_VALUE, execute(handler, operation, Map.of("min", Double.MAX_VALUE, "max", Double.MAX_VALUE)).outputs.get("result"));
        }
        assertEquals(Double.MAX_VALUE, execute(new RandomHandler(), "random_double", Map.of("min", Double.MAX_VALUE, "max", Double.MAX_VALUE)).outputs.get("number"));
        Context distance = execute(handler, "distance", Map.of("x2", 1.0E200, "y2", 1.0E200));
        assertEquals(Math.hypot(1.0E200, 1.0E200), distance.outputs.get("result"));
        assertEquals(2.0, execute(handler, "min_list", Map.of("values_list", List.of(4, 2L, 3.5))).outputs.get("min"));
        assertEquals(4.0, execute(handler, "max_list", Map.of("values_list", List.of(4, 2L, 3.5))).outputs.get("max"));
    }

    @Test
    void convertedCollectionsAndPrimitiveArraysAreIndependentMutableLists() {
        ConversionHandler handler = new ConversionHandler();
        List<String> original = List.of("first");
        Context copied = execute(handler, "to_list", Map.of("value_or_separator", original));
        List<Object> output = (List<Object>) copied.outputs.get("list");
        assertNotSame(original, output);
        output.add("second");
        assertEquals(List.of("first"), original);
        assertEquals(List.of(1, 2), execute(handler, "to_list", Map.of("value_or_separator", new int[] {1, 2})).outputs.get("list"));
    }

    private Context execute(NodeHandler handler, String operation, Map<String, Object> inputs) {
        FlowNode node = new FlowNode(operation, 0, 0, Map.of());
        node.setHandlerConfig(Map.of("operation", operation));
        Context context = new Context(inputs);
        handler.execute(context, node);
        return context;
    }

    private static final class Context extends FlowContext {
        private final Map<String, Object> inputs;
        private final Map<String, Object> outputs = new HashMap<>();

        private Context(Map<String, Object> inputs) {
            super(null, null, null);
            this.inputs = inputs;
        }

        @Override
        public <T> T getInputValue(FlowNode node, String name, Class<T> type, T fallback) {
            Object value = inputs.get(name);
            return value == null ? fallback : type.cast(value);
        }

        @Override
        public void setOutput(FlowNode node, String name, Object value) {
            outputs.put(name, value);
        }

        @Override
        public void triggerOutput(String name) {
        }
    }
}
