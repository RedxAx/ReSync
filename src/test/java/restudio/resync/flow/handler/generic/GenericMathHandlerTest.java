package restudio.resync.flow.handler.generic;

import org.junit.jupiter.api.Test;
import restudio.flow.data.FlowNode;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.NodeHandler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    @Test
    void sqrtRejectsNegativeInputWithStableFailure() {
        NodeHandler handler = registeredHandler();
        FlowNode node = nodeFor("sqrt");
        TestFlowContext context = new TestFlowContext(Map.of("value", -1.0));

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> handler.execute(context, node));

        assertEquals("Square root input must be a finite non-negative number", failure.getMessage());
        assertTrue(context.outputs.isEmpty());
    }

    @Test
    void selectedScalarOperationsRejectNonFiniteInputsAtHandlerBoundary() {
        Map<String, List<String>> inputPins = Map.ofEntries(
            Map.entry("abs", List.of("value")),
            Map.entry("floor", List.of("value")),
            Map.entry("ceil", List.of("value")),
            Map.entry("sqrt", List.of("value")),
            Map.entry("cbrt", List.of("value")),
            Map.entry("signum", List.of("value")),
            Map.entry("to_radians", List.of("degrees")),
            Map.entry("to_degrees", List.of("radians")),
            Map.entry("add", List.of("a", "b")),
            Map.entry("subtract", List.of("a", "b")),
            Map.entry("multiply", List.of("a", "b")),
            Map.entry("negate", List.of("value")),
            Map.entry("hypotenuse", List.of("a", "b")),
            Map.entry("sin", List.of("angle_degrees")),
            Map.entry("cos", List.of("angle_degrees")),
            Map.entry("tan", List.of("angle_degrees")),
            Map.entry("atan", List.of("value")),
            Map.entry("clamp", List.of("value", "min", "max")),
            Map.entry("lerp", List.of("a", "b", "t")),
            Map.entry("round", List.of("value")),
            Map.entry("asin", List.of("value")),
            Map.entry("acos", List.of("value")),
            Map.entry("atan2", List.of("y", "x")),
            Map.entry("distance", List.of("x1", "y1", "z1", "x2", "y2", "z2")),
            Map.entry("log", List.of("value")),
            Map.entry("log10", List.of("value")),
            Map.entry("pow", List.of("base", "exponent")),
            Map.entry("power", List.of("base", "exponent")),
            Map.entry("round_decimal", List.of("value")),
            Map.entry("divide", List.of("a", "b")),
            Map.entry("modulo", List.of("a", "b")));
        NodeHandler handler = registeredHandler();

        for (Map.Entry<String, List<String>> entry : inputPins.entrySet()) {
            for (String pinName : entry.getValue()) {
                for (double value : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
                    FlowNode node = nodeFor(entry.getKey());
                    TestFlowContext context = new TestFlowContext(Map.of(pinName, value));

                    IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> handler.execute(context, node),
                        entry.getKey() + " should reject " + pinName + "=" + value);

                    assertTrue(failure.getMessage().endsWith("must be finite"), entry.getKey() + " should report a finite input requirement");
                    assertTrue(context.outputs.isEmpty(), entry.getKey() + " should not publish a non-finite result");
                }
            }
        }
    }

    @Test
    void cbrtAndSignumPublishFiniteResults() {
        NodeHandler handler = registeredHandler();

        TestFlowContext cbrtContext = new TestFlowContext(Map.of("value", -8.0));
        handler.execute(cbrtContext, nodeFor("cbrt"));
        assertEquals(-2.0, cbrtContext.outputs.get("cbrt"));

        for (Map.Entry<Double, Double> entry : Map.of(-4.0, -1.0, 0.0, 0.0, 4.0, 1.0).entrySet()) {
            TestFlowContext signumContext = new TestFlowContext(Map.of("value", entry.getKey()));
            handler.execute(signumContext, nodeFor("signum"));
            assertEquals(entry.getValue(), signumContext.outputs.get("sign"));
        }
    }

    @Test
    void dataOnlyScalarOperationsPublishExactResultsWithoutFlow() {
        NodeHandler handler = registeredHandler();
        Map<String, Map<String, Object>> inputs = Map.ofEntries(
            Map.entry("add", Map.of("a", 2.0, "b", 3.0)),
            Map.entry("subtract", Map.of("a", 7.0, "b", 2.0)),
            Map.entry("multiply", Map.of("a", 2.0, "b", 3.0)),
            Map.entry("negate", Map.of("value", -3.0)),
            Map.entry("abs", Map.of("value", -4.0)),
            Map.entry("floor", Map.of("value", 4.8)),
            Map.entry("ceil", Map.of("value", 4.2)),
            Map.entry("sqrt", Map.of("value", 9.0)),
            Map.entry("cbrt", Map.of("value", -8.0)),
            Map.entry("signum", Map.of("value", -4.0)),
            Map.entry("to_radians", Map.of("degrees", 180.0)),
            Map.entry("to_degrees", Map.of("radians", Math.PI)),
            Map.entry("hypotenuse", Map.of("a", 3.0, "b", 4.0)),
            Map.entry("sin", Map.of("angle_degrees", 90.0)),
            Map.entry("cos", Map.of("angle_degrees", 0.0)),
            Map.entry("tan", Map.of("angle_degrees", 45.0)),
            Map.entry("atan", Map.of("value", 1.0)),
            Map.entry("clamp", Map.of("value", 5.0, "min", 0.0, "max", 3.0)),
            Map.entry("lerp", Map.of("a", 0.0, "b", 10.0, "t", 1.5)),
            Map.entry("round", Map.of("value", 123.456, "decimal_places", 2)),
            Map.entry("asin", Map.of("value", 2.0)),
            Map.entry("acos", Map.of("value", -2.0)),
            Map.entry("atan2", Map.of("y", 1.0, "x", 0.0)),
            Map.entry("distance", Map.of("x1", 0.0, "y1", 0.0, "z1", 0.0, "x2", 3.0, "y2", 4.0, "z2", 12.0)),
            Map.entry("min", Map.of("values_list", List.of(4.0, 2.0, -1.0))),
            Map.entry("max", Map.of("values_list", List.of(4.0, 2.0, -1.0))),
            Map.entry("log", Map.of("value", Math.E)),
            Map.entry("log10", Map.of("value", 100.0)),
            Map.entry("pow", Map.of("base", 2.0, "exponent", 3.0)),
            Map.entry("power", Map.of("base", 2.0, "exponent", 3.0)),
            Map.entry("round_decimal", Map.of("value", 123.456, "decimal_places", 2)),
            Map.entry("divide", Map.of("a", 6.0, "b", 2.0)),
            Map.entry("modulo", Map.of("a", 7.0, "b", 2.0)));
        Map<String, Map<String, Object>> expected = Map.ofEntries(
            Map.entry("add", Map.of("result", 5.0)),
            Map.entry("subtract", Map.of("result", 5.0)),
            Map.entry("multiply", Map.of("result", 6.0)),
            Map.entry("negate", Map.of("result", 3.0)),
            Map.entry("abs", Map.of("absolute", 4.0)),
            Map.entry("floor", Map.of("floored", 4.0)),
            Map.entry("ceil", Map.of("ceiling", 5.0)),
            Map.entry("sqrt", Map.of("sqrt", 3.0)),
            Map.entry("cbrt", Map.of("cbrt", -2.0)),
            Map.entry("signum", Map.of("sign", -1.0)),
            Map.entry("to_radians", Map.of("radians", Math.PI)),
            Map.entry("to_degrees", Map.of("degrees", 180.0)),
            Map.entry("hypotenuse", Map.of("hypotenuse", 5.0)),
            Map.entry("sin", Map.of("sin", 1.0)),
            Map.entry("cos", Map.of("cos", 1.0)),
            Map.entry("tan", Map.of("tan", 1.0)),
            Map.entry("atan", Map.of("angle_degrees", 45.0)),
            Map.entry("clamp", Map.of("clamped", 3.0)),
            Map.entry("lerp", Map.of("result", 10.0)),
            Map.entry("round", Map.of("rounded", 123.46)),
            Map.entry("asin", Map.of("angle_degrees", 90.0)),
            Map.entry("acos", Map.of("angle_degrees", 180.0)),
            Map.entry("atan2", Map.of("angle_degrees", 90.0)),
            Map.entry("distance", Map.of("result", 13.0)),
            Map.entry("min", Map.of("min", -1.0)),
            Map.entry("max", Map.of("max", 4.0)),
            Map.entry("log", Map.of("log", 1.0)),
            Map.entry("log10", Map.of("log10", 2.0)),
            Map.entry("pow", Map.of("result", 8.0)),
            Map.entry("power", Map.of("result", 8.0)),
            Map.entry("round_decimal", Map.of("rounded", 123.46)),
            Map.entry("divide", Map.of("result", 3.0)),
            Map.entry("modulo", Map.of("result", 1.0)));

        for (String operation : inputs.keySet()) {
            TestFlowContext context = new TestFlowContext(inputs.get(operation));
            handler.execute(context, nodeFor(operation));

            Map<String, Object> actual = context.outputs;
            assertEquals(expected.get(operation).keySet(), actual.keySet(), operation + " output pins");
            for (Map.Entry<String, Object> output : expected.get(operation).entrySet()) {
                assertEquals(((Number) output.getValue()).doubleValue(), ((Number) actual.get(output.getKey())).doubleValue(),
                    1.0E-12, operation + " output " + output.getKey());
            }
            assertTrue(context.triggeredOutputs.isEmpty(), operation + " should not trigger flow");
        }
    }

    @Test
    void nextDataOnlyOperationsPreserveDefaultsWithoutFlow() {
        NodeHandler handler = registeredHandler();
        Map<String, Map<String, Double>> expected = Map.ofEntries(
            Map.entry("clamp", Map.of("clamped", 0.0)),
            Map.entry("lerp", Map.of("result", 0.0)),
            Map.entry("round", Map.of("rounded", 0.0)),
            Map.entry("asin", Map.of("angle_degrees", 0.0)),
            Map.entry("acos", Map.of("angle_degrees", 90.0)),
            Map.entry("atan2", Map.of("angle_degrees", 0.0)),
            Map.entry("distance", Map.of("result", 0.0)),
            Map.entry("min", Map.of("min", 0.0)),
            Map.entry("max", Map.of("max", 0.0)),
            Map.entry("log", Map.of("log", 0.0)),
            Map.entry("log10", Map.of("log10", 0.0)),
            Map.entry("pow", Map.of("result", 0.0)),
            Map.entry("power", Map.of("result", 1.0)),
            Map.entry("round_decimal", Map.of("rounded", 0.0)),
            Map.entry("divide", Map.of("result", 0.0)),
            Map.entry("modulo", Map.of("result", 0.0)));

        for (Map.Entry<String, Map<String, Double>> entry : expected.entrySet()) {
            TestFlowContext context = new TestFlowContext(Map.of());
            handler.execute(context, nodeFor(entry.getKey()));

            assertEquals(entry.getValue().keySet(), context.outputs.keySet(), entry.getKey() + " default output pins");
            for (Map.Entry<String, Double> output : entry.getValue().entrySet()) {
                assertEquals(output.getValue(), context.outputs.get(output.getKey()));
            }
            assertTrue(context.triggeredOutputs.isEmpty(), entry.getKey() + " defaults should not trigger flow");
        }
    }

    @Test
    void authoredScalarClampsRemainStable() {
        NodeHandler handler = registeredHandler();

        TestFlowContext clampContext = new TestFlowContext(Map.of("value", 5.0, "min", 10.0, "max", 1.0));
        handler.execute(clampContext, nodeFor("clamp"));
        assertEquals(10.0, clampContext.outputs.get("clamped"));
        assertTrue(clampContext.triggeredOutputs.isEmpty());

        TestFlowContext roundHighContext = new TestFlowContext(Map.of("value", 123.456, "decimal_places", 20));
        handler.execute(roundHighContext, nodeFor("round"));
        assertEquals(123.456, ((Number) roundHighContext.outputs.get("rounded")).doubleValue(), 1.0E-12);

        TestFlowContext roundLowContext = new TestFlowContext(Map.of("value", 123.456, "decimal_places", -20));
        handler.execute(roundLowContext, nodeFor("round"));
        assertEquals(0.0, roundLowContext.outputs.get("rounded"));

        TestFlowContext roundDecimalHighContext = new TestFlowContext(Map.of("value", 123.456, "decimal_places", 20));
        handler.execute(roundDecimalHighContext, nodeFor("round_decimal"));
        assertEquals(123.456, ((Number) roundDecimalHighContext.outputs.get("rounded")).doubleValue(), 1.0E-12);
        assertTrue(roundDecimalHighContext.triggeredOutputs.isEmpty());

        TestFlowContext roundDecimalLowContext = new TestFlowContext(Map.of("value", 123.456, "decimal_places", -20));
        handler.execute(roundDecimalLowContext, nodeFor("round_decimal"));
        assertEquals(0.0, roundDecimalLowContext.outputs.get("rounded"));
        assertTrue(roundDecimalLowContext.triggeredOutputs.isEmpty());
    }

    @Test
    void logarithmsRejectNonPositiveInputsWithStableFailures() {
        NodeHandler handler = registeredHandler();
        Map<String, String> messages = Map.of(
            "log", "Log input must be greater than zero",
            "log10", "Log10 input must be greater than zero");

        for (Map.Entry<String, String> entry : messages.entrySet()) {
            for (double value : new double[] {0.0, -1.0}) {
                TestFlowContext context = new TestFlowContext(Map.of("value", value));

                IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> handler.execute(context, nodeFor(entry.getKey())), entry.getKey() + " should reject " + value);

                assertEquals(entry.getValue(), failure.getMessage());
                assertTrue(context.outputs.isEmpty());
                assertTrue(context.triggeredOutputs.isEmpty());
            }
        }
    }

    @Test
    void exponentiationRejectsNonFiniteResultsWithoutFlow() {
        NodeHandler handler = registeredHandler();

        for (Map.Entry<String, String> entry : Map.of("pow", "Pow result must be finite", "power", "Power result must be finite").entrySet()) {
            TestFlowContext context = new TestFlowContext(Map.of("base", Double.MAX_VALUE, "exponent", 2.0));

            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> handler.execute(context, nodeFor(entry.getKey())), entry.getKey() + " should reject an overflowing result");

            assertEquals(entry.getValue(), failure.getMessage());
            assertTrue(context.outputs.isEmpty());
            assertTrue(context.triggeredOutputs.isEmpty());
        }
    }

    @Test
    void minAndMaxIgnoreNonNumbersAndReturnFiniteExtremaWithoutFlow() {
        NodeHandler handler = registeredHandler();
        List<Object> mixedValues = List.of("ignored", 4, 2.5, -1L, true);

        TestFlowContext minContext = new TestFlowContext(Map.of("values_list", mixedValues));
        handler.execute(minContext, nodeFor("min"));
        assertEquals(-1.0, minContext.outputs.get("min"));
        assertTrue(minContext.triggeredOutputs.isEmpty());

        TestFlowContext maxContext = new TestFlowContext(Map.of("values_list", mixedValues));
        handler.execute(maxContext, nodeFor("max"));
        assertEquals(4.0, maxContext.outputs.get("max"));
        assertTrue(maxContext.triggeredOutputs.isEmpty());

        for (String operation : List.of("min", "max")) {
            TestFlowContext noNumericContext = new TestFlowContext(Map.of("values_list", List.of("ignored", false)));
            handler.execute(noNumericContext, nodeFor(operation));
            assertEquals(0.0, noNumericContext.outputs.get(operation));
            assertTrue(noNumericContext.triggeredOutputs.isEmpty());
        }
    }

    @Test
    void minAndMaxRejectNonFiniteNumericElements() {
        NodeHandler handler = registeredHandler();

        for (String operation : List.of("min", "max")) {
            for (double value : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
                TestFlowContext context = new TestFlowContext(Map.of("values_list", List.of(1.0, value, 2.0)));

                IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> handler.execute(context, nodeFor(operation)), operation + " should reject " + value);

                assertTrue(failure.getMessage().endsWith("must be finite"));
                assertTrue(context.outputs.isEmpty());
                assertTrue(context.triggeredOutputs.isEmpty());
            }
        }
    }

    @Test
    void divideAndModuloPreserveZeroAndDefaultDivisorsWithoutFlow() {
        NodeHandler handler = registeredHandler();

        for (String operation : List.of("divide", "modulo")) {
            TestFlowContext zeroContext = new TestFlowContext(Map.of("a", 7.0, "b", 0.0));
            handler.execute(zeroContext, nodeFor(operation));
            assertEquals(Double.doubleToLongBits(0.0), Double.doubleToLongBits((Double) zeroContext.outputs.get("result")));
            assertTrue(zeroContext.triggeredOutputs.isEmpty());
        }

        TestFlowContext divideDefaultContext = new TestFlowContext(Map.of("a", 3.0));
        handler.execute(divideDefaultContext, nodeFor("divide"));
        assertEquals(3.0, divideDefaultContext.outputs.get("result"));
        assertTrue(divideDefaultContext.triggeredOutputs.isEmpty());

        TestFlowContext moduloDefaultContext = new TestFlowContext(Map.of("a", 3.0));
        handler.execute(moduloDefaultContext, nodeFor("modulo"));
        assertEquals(0.0, moduloDefaultContext.outputs.get("result"));
        assertTrue(moduloDefaultContext.triggeredOutputs.isEmpty());
    }

    @Test
    void divideRejectsNonFiniteResultWithoutFlow() {
        NodeHandler handler = registeredHandler();
        TestFlowContext context = new TestFlowContext(Map.of("a", Double.MAX_VALUE, "b", Double.MIN_VALUE));

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
            () -> handler.execute(context, nodeFor("divide")));

        assertEquals("Divide result must be finite", failure.getMessage());
        assertTrue(context.outputs.isEmpty());
        assertTrue(context.triggeredOutputs.isEmpty());
    }

    @Test
    void otherMathOperationsStillTriggerFlow() {
        NodeHandler handler = registeredHandler();
        TestFlowContext context = new TestFlowContext(Map.of());

        handler.execute(context, nodeFor("random"));

        assertEquals(List.of("flow"), context.triggeredOutputs);
    }

    private static NodeHandler registeredHandler() {
        HandlerRegistry registry = new HandlerRegistry();
        new GenericMathHandler().registerTo(registry);
        return registry.getHandler("GenericMathHandler");
    }

    private static FlowNode nodeFor(String operation) {
        FlowNode node = new FlowNode("math." + operation, 0, 0, Map.of());
        node.setHandlerConfig(Map.of("operation", operation));
        return node;
    }

    private static class TestFlowContext extends FlowContext {
        private final Map<String, Object> inputs;
        private final Map<String, Object> outputs = new HashMap<>();
        private final List<String> triggeredOutputs = new ArrayList<>();

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
            triggeredOutputs.add(pinName);
        }
    }
}
