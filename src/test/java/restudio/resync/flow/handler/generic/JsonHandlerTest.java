package restudio.resync.flow.handler.generic;

import org.junit.jupiter.api.Test;
import restudio.flow.data.FlowNode;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.NodeHandler;
import restudio.resync.modules.flow.FlowPacketSender;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonHandlerTest {
    @Test
    void parseAcceptsObjectsArraysPrimitivesAndNull() {
        TestFlowContext object = execute("json_parse", Map.of("json_string", "{\"name\":\"ReSync\",\"enabled\":true}"));
        TestFlowContext array = execute("json_parse", Map.of("json_string", "[\"first\",2,false]"));
        TestFlowContext string = execute("json_parse", Map.of("json_string", "\"value\""));
        TestFlowContext number = execute("json_parse", Map.of("json_string", "12.5"));
        TestFlowContext bool = execute("json_parse", Map.of("json_string", "true"));
        TestFlowContext nullValue = execute("json_parse", Map.of("json_string", "null"));

        assertEquals(Map.of("name", "ReSync", "enabled", true), object.outputs.get("object"));
        assertEquals(List.of("first", new BigDecimal("2"), false), array.outputs.get("object"));
        assertEquals("value", string.outputs.get("object"));
        assertEquals("12.5", number.outputs.get("object").toString());
        assertEquals(true, bool.outputs.get("object"));
        assertTrue(nullValue.outputs.containsKey("object"));
        assertEquals(null, nullValue.outputs.get("object"));

        for (TestFlowContext context : List.of(object, array, string, number, bool, nullValue)) {
            assertEquals(List.of("flow"), context.triggeredOutputs);
        }
    }

    @Test
    void parseRejectsDuplicateKeysDepthAndOversizedInputWithoutPartialState() {
        assertFailure("json_parse", Map.of("json_string", "{\"key\":1,\"key\":2}"));

        String tooDeep = "[".repeat(65) + "0" + "]".repeat(65);
        assertFailure("json_parse", Map.of("json_string", tooDeep));

        String oversized = "\"" + "x".repeat(FlowPacketSender.MAX_STRING_LENGTH) + "\"";
        assertFailure("json_parse", Map.of("json_string", oversized));
    }

    @Test
    void stringifySortsObjectKeysRecursively() {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("z", "last");
        nested.put("a", true);

        Map<String, Object> object = new LinkedHashMap<>();
        object.put("z", "last");
        object.put("nested", nested);
        object.put("array", List.of(Map.of("z", "z", "a", "a")));
        object.put("a", "first");

        TestFlowContext context = execute("json_to_string", Map.of("object", object));

        assertEquals("{\"a\":\"first\",\"array\":[{\"a\":\"a\",\"z\":\"z\"}],\"nested\":{\"a\":true,\"z\":\"last\"},\"z\":\"last\"}",
            context.outputs.get("string"));
        assertEquals(List.of("flow"), context.triggeredOutputs);
    }

    @Test
    void stringifyRejectsUnsupportedCyclicAndNonFiniteValuesWithoutPartialState() {
        assertFailure("json_to_string", Map.of("object", new Object()));

        Map<String, Object> cyclic = new HashMap<>();
        cyclic.put("self", cyclic);
        assertFailure("json_to_string", Map.of("object", cyclic));

        assertFailure("json_to_string", Map.of("object", Double.NaN));
        assertFailure("json_to_string", Map.of("object", Double.POSITIVE_INFINITY));
    }

    @Test
    void getAndHasAcceptValidDottedPaths() {
        Map<String, Object> object = Map.of("root", Map.of("branch", Map.of("leaf", "value")));

        TestFlowContext get = execute("json_get", Map.of("object", object, "path", "root.branch.leaf"));
        TestFlowContext has = execute("json_has", Map.of("object", object, "path", "root.branch.leaf"));

        assertEquals("value", get.outputs.get("value"));
        assertEquals(true, has.outputs.get("has"));
        assertEquals(List.of("flow"), get.triggeredOutputs);
        assertEquals(List.of("flow"), has.triggeredOutputs);
    }

    @Test
    void getAndHasRejectInvalidPathsWithoutPartialState() {
        Map<String, Object> object = Map.of("root", Map.of("branch", Map.of("leaf", "value")), "scalar", "text");
        List<String> invalidPaths = List.of(
            "",
            "   ",
            "root..leaf",
            ".root",
            "root.",
            tooDeepPath(),
            "scalar.child");

        for (String path : invalidPaths) {
            assertFailure("json_get", Map.of("object", object, "path", path));
            assertFailure("json_has", Map.of("object", object, "path", path));
        }
    }

    @Test
    void keysReturnsAnIndependentCopy() {
        Map<String, Object> object = new LinkedHashMap<>();
        object.put("first", 1);
        object.put("second", 2);

        TestFlowContext context = execute("json_keys", Map.of("object", object));
        List<?> keys = (List<?>) context.outputs.get("keys");

        assertEquals(List.of("first", "second"), keys);
        assertNotSame(object.keySet(), keys);
        ((List<Object>) keys).add("third");
        assertEquals(List.of("first", "second"), new ArrayList<>(object.keySet()));
        assertEquals(List.of("flow"), context.triggeredOutputs);
    }

    @Test
    void mergeIsShallowAndDoesNotMutateCallers() {
        Map<String, Object> nested = new HashMap<>();
        nested.put("nested", true);
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("first", "one");
        first.put("shared", "first");
        first.put("nested", nested);
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("second", "two");
        second.put("shared", "second");

        TestFlowContext context = execute("json_merge", Map.of("object1", first, "object2", second));
        Map<?, ?> merged = (Map<?, ?>) context.outputs.get("merged");

        assertEquals(Map.of("first", "one", "second", "two", "shared", "second", "nested", nested), merged);
        assertEquals(Map.of("first", "one", "shared", "first", "nested", nested), first);
        assertEquals(Map.of("second", "two", "shared", "second"), second);
        assertEquals(List.of("flow"), context.triggeredOutputs);
    }

    @Test
    void createReturnsFreshEmptyMaps() {
        TestFlowContext first = execute("json_create", Map.of());
        TestFlowContext second = execute("json_create", Map.of());

        Map<?, ?> firstObject = (Map<?, ?>) first.outputs.get("object");
        Map<?, ?> secondObject = (Map<?, ?>) second.outputs.get("object");
        assertEquals(Map.of(), firstObject);
        assertEquals(Map.of(), secondObject);
        assertNotSame(firstObject, secondObject);
        ((Map<String, Object>) firstObject).put("key", "value");
        assertEquals(Map.of(), secondObject);
        assertEquals(List.of("flow"), first.triggeredOutputs);
        assertEquals(List.of("flow"), second.triggeredOutputs);
    }

    @Test
    void setArrayReturnsAnIndependentCopy() {
        List<Object> source = new ArrayList<>(List.of("first", "second"));

        TestFlowContext context = execute("json_set_array", Map.of("values", source));
        List<?> array = (List<?>) context.outputs.get("array");

        assertEquals(source, array);
        assertNotSame(source, array);
        ((List<Object>) array).add("third");
        assertEquals(List.of("first", "second"), source);
        assertEquals(List.of("flow"), context.triggeredOutputs);
    }

    @Test
    void allActiveOperationsTriggerFlowOnSuccess() {
        Map<String, Map<String, Object>> inputs = Map.of(
            "json_parse", Map.of("json_string", "{}"),
            "json_to_string", Map.of("object", Map.of()),
            "json_get", Map.of("object", Map.of("key", "value"), "path", "key"),
            "json_has", Map.of("object", Map.of("key", "value"), "path", "key"),
            "json_keys", Map.of("object", Map.of("key", "value")),
            "json_merge", Map.of("object1", Map.of("first", 1), "object2", Map.of("second", 2)),
            "json_create", Map.of(),
            "json_set_array", Map.of("values", List.of("value")));

        for (Map.Entry<String, Map<String, Object>> entry : inputs.entrySet()) {
            TestFlowContext context = execute(entry.getKey(), entry.getValue());
            assertEquals(List.of("flow"), context.triggeredOutputs, entry.getKey());
        }
    }

    private String tooDeepPath() {
        return IntStream.range(0, 65).mapToObj(index -> "root").collect(Collectors.joining("."));
    }

    private void assertFailure(String operation, Map<String, Object> inputs) {
        TestFlowContext context = new TestFlowContext(inputs);
        assertThrows(IllegalArgumentException.class, () -> execute(operation, context), operation + " " + inputs);
        assertTrue(context.outputs.isEmpty(), operation + " emitted partial output");
        assertTrue(context.triggeredOutputs.isEmpty(), operation + " triggered flow on failure");
    }

    private TestFlowContext execute(String operation, Map<String, Object> inputs) {
        TestFlowContext context = new TestFlowContext(inputs);
        execute(operation, context);
        return context;
    }

    private void execute(String operation, TestFlowContext context) {
        HandlerRegistry registry = new HandlerRegistry();
        new JsonHandler().registerTo(registry);
        NodeHandler handler = registry.getHandler("JsonHandler");
        FlowNode node = new FlowNode("json." + operation, 0, 0, Map.of());
        node.setHandlerConfig(Map.of("operation", operation));
        handler.execute(context, node);
    }

    private static final class TestFlowContext extends FlowContext {
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
