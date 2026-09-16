package restudio.resync.flow.handler.generic;

import org.bukkit.Color;
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

class ColorHandlerTest {
    private static final List<String> ACTIVE_OPERATIONS = List.of(
        "color_from_rgb",
        "color_from_hex",
        "color_to_hex",
        "color_to_rgb",
        "color_blend",
        "color_invert",
        "color_brighten",
        "color_darken",
        "color_random",
        "color_distance");

    @Test
    void activeOperationsPublishWithoutFlow() {
        NodeHandler handler = registeredHandler();

        for (String operation : ACTIVE_OPERATIONS) {
            TestFlowContext context = new TestFlowContext(Map.of());

            handler.execute(context, nodeFor(operation));

            assertTrue(!context.outputs.isEmpty(), operation + " should publish an output");
            assertTrue(context.triggeredOutputs.isEmpty(), operation + " should not trigger flow");
        }
    }

    @Test
    void rgbChannelsAcceptNumbersAndClampAfterIntConversion() {
        TestFlowContext context = new TestFlowContext(Map.of(
            "red", 300.9,
            "green", -1.2,
            "blue", 128.9));

        registeredHandler().execute(context, nodeFor("color_from_rgb"));

        assertEquals(Color.fromRGB(255, 0, 128).asRGB(), ((Color) context.outputs.get("color")).asRGB());
        assertTrue(context.triggeredOutputs.isEmpty());
    }

    @Test
    void hexParsingPreservesThreeAndSixDigitFormsAndHexOutputIsUppercase() {
        NodeHandler handler = registeredHandler();
        TestFlowContext shortContext = new TestFlowContext(Map.of("hex_string", " #aBc "));
        handler.execute(shortContext, nodeFor("color_from_hex"));
        assertEquals(Color.fromRGB(0xAABBCC).asRGB(), ((Color) shortContext.outputs.get("color")).asRGB());

        TestFlowContext sixDigitContext = new TestFlowContext(Map.of("hex_string", "abcdef"));
        handler.execute(sixDigitContext, nodeFor("color_from_hex"));
        assertEquals(Color.fromRGB(0xABCDEF).asRGB(), ((Color) sixDigitContext.outputs.get("color")).asRGB());

        TestFlowContext outputContext = new TestFlowContext(Map.of("color", Color.fromRGB(0x01aBcD)));
        handler.execute(outputContext, nodeFor("color_to_hex"));
        assertEquals("#01ABCD", outputContext.outputs.get("hex_string"));
    }

    @Test
    void colorConversionsAndDistancePublishDeclaredValues() {
        NodeHandler handler = registeredHandler();

        TestFlowContext channels = new TestFlowContext(Map.of("color", Color.fromRGB(0x123456)));
        handler.execute(channels, nodeFor("color_to_rgb"));
        assertEquals(Map.of("red", 0x12, "green", 0x34, "blue", 0x56), channels.outputs);

        TestFlowContext inverted = new TestFlowContext(Map.of("color", Color.fromRGB(0x123456)));
        handler.execute(inverted, nodeFor("color_invert"));
        assertEquals(Color.fromRGB(0xEDCBA9).asRGB(), ((Color) inverted.outputs.get("inverted_color")).asRGB());

        TestFlowContext distance = new TestFlowContext(Map.of(
            "color1", Color.WHITE,
            "color2", Color.BLACK));
        handler.execute(distance, nodeFor("color_distance"));
        assertEquals(Math.sqrt(3 * 255 * 255), (Double) distance.outputs.get("distance"), 1.0E-12);
    }

    @Test
    void blendBrightenAndDarkenClampFiniteValues() {
        NodeHandler handler = registeredHandler();

        TestFlowContext blendLow = new TestFlowContext(Map.of(
            "color1", Color.fromRGB(10, 20, 30),
            "color2", Color.fromRGB(200, 210, 220),
            "ratio", -4));
        handler.execute(blendLow, nodeFor("color_blend"));
        assertEquals(Color.fromRGB(10, 20, 30).asRGB(), ((Color) blendLow.outputs.get("mixed_color")).asRGB());

        TestFlowContext blendHigh = new TestFlowContext(Map.of(
            "color1", Color.fromRGB(10, 20, 30),
            "color2", Color.fromRGB(200, 210, 220),
            "ratio", 4.0));
        handler.execute(blendHigh, nodeFor("color_blend"));
        assertEquals(Color.fromRGB(200, 210, 220).asRGB(), ((Color) blendHigh.outputs.get("mixed_color")).asRGB());

        TestFlowContext brighten = new TestFlowContext(Map.of(
            "color", Color.fromRGB(100, 50, 10),
            "amount", 0.5));
        handler.execute(brighten, nodeFor("color_brighten"));
        assertEquals(Color.fromRGB(150, 75, 15).asRGB(), ((Color) brighten.outputs.get("brightened_color")).asRGB());

        TestFlowContext darken = new TestFlowContext(Map.of(
            "color", Color.fromRGB(100, 50, 10),
            "amount", 1));
        handler.execute(darken, nodeFor("color_darken"));
        assertEquals(Color.BLACK.asRGB(), ((Color) darken.outputs.get("darkened_color")).asRGB());
    }

    @Test
    void nonFiniteAmountsAndRatiosFailBeforeOutputOrFlow() {
        NodeHandler handler = registeredHandler();
        Map<String, List<String>> inputs = Map.of(
            "color_brighten", List.of("amount"),
            "color_darken", List.of("amount"),
            "color_blend", List.of("ratio"),
            "mix", List.of("ratio"));

        for (Map.Entry<String, List<String>> entry : inputs.entrySet()) {
            for (String pin : entry.getValue()) {
                for (double value : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
                    Map<String, Object> inputValues = new HashMap<>();
                    inputValues.put(pin, value);
                    TestFlowContext context = new TestFlowContext(inputValues);

                    IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                        () -> handler.execute(context, nodeFor(entry.getKey())));

                    assertTrue(failure.getMessage().endsWith("must be finite"));
                    assertTrue(context.outputs.isEmpty(), entry.getKey() + " should not publish non-finite output");
                    assertTrue(context.triggeredOutputs.isEmpty(), entry.getKey() + " should not trigger flow on failure");
                }
            }
        }
    }

    @Test
    void randomColorsUseFreshBoundedChannels() {
        NodeHandler handler = registeredHandler();

        for (int index = 0; index < 128; index++) {
            TestFlowContext context = new TestFlowContext(Map.of());
            handler.execute(context, nodeFor("color_random"));
            Color color = (Color) context.outputs.get("color");

            assertTrue(color.getRed() >= 0 && color.getRed() <= 255);
            assertTrue(color.getGreen() >= 0 && color.getGreen() <= 255);
            assertTrue(color.getBlue() >= 0 && color.getBlue() <= 255);
            assertTrue(context.triggeredOutputs.isEmpty());
        }
    }

    @Test
    void undeclaredMixStillTriggersFlow() {
        TestFlowContext context = new TestFlowContext(Map.of(
            "color1", Color.BLACK,
            "color2", Color.WHITE,
            "ratio", 0.5));

        registeredHandler().execute(context, nodeFor("mix"));

        assertEquals(Color.fromRGB(127, 127, 127).asRGB(), ((Color) context.outputs.get("mixed_color")).asRGB());
        assertEquals(List.of("flow"), context.triggeredOutputs);
    }

    @Test
    void invalidHexFailsWithoutOutputOrFlow() {
        NodeHandler handler = registeredHandler();

        for (String value : List.of("#12", "##ABC", "A#BCDEF", "#ABC#", "12G456")) {
            TestFlowContext context = new TestFlowContext(Map.of("hex_string", value));

            assertThrows(IllegalArgumentException.class, () -> handler.execute(context, nodeFor("color_from_hex")), value);

            assertTrue(context.outputs.isEmpty(), value);
            assertTrue(context.triggeredOutputs.isEmpty(), value);
        }
    }

    private static NodeHandler registeredHandler() {
        HandlerRegistry registry = new HandlerRegistry();
        new ColorHandler().registerTo(registry);
        return registry.getHandler("ColorHandler");
    }

    private static FlowNode nodeFor(String operation) {
        FlowNode node = new FlowNode("color." + operation, 0, 0, Map.of());
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
