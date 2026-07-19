package restudio.resync.flow;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import restudio.flow.data.FlowConnection;
import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowNode;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.NodeHandler;
import restudio.resync.flow.handler.generic.ColorHandler;
import restudio.resync.flow.handler.generic.ConversionHandler;
import restudio.resync.flow.handler.generic.FlowControlHandler;
import restudio.resync.flow.handler.generic.GenericListHandler;
import restudio.resync.flow.handler.generic.GenericMapHandler;
import restudio.resync.flow.handler.generic.GenericMathHandler;
import restudio.resync.flow.handler.generic.GenericStringHandler;
import restudio.resync.flow.handler.generic.JsonHandler;
import restudio.resync.flow.handler.generic.LogicHandler;
import restudio.resync.flow.handler.generic.TextFormatHandler;
import restudio.resync.flow.handler.generic.TimeHandler;
import restudio.resync.flow.handler.generic.UuidHandler;
import restudio.resync.flow.handler.generic.VariableScopeHandler;
import restudio.resync.flow.registry.NodeDefinitionLoader;
import restudio.resync.flow.registry.NodeDefinitionRegistry;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SuppressWarnings("unchecked")
class FlowExecutorProductionMegaGraphTest {
    private HandlerRegistry handlers;
    private NodeDefinitionRegistry definitions;
    private FlowExecutor executor;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        handlers = new HandlerRegistry();
        new GenericMathHandler().registerTo(handlers);
        new GenericStringHandler().registerTo(handlers);
        new GenericListHandler().registerTo(handlers);
        new GenericMapHandler().registerTo(handlers);
        new LogicHandler().registerTo(handlers);
        new ConversionHandler().registerTo(handlers);
        new FlowControlHandler().registerTo(handlers);
        new JsonHandler().registerTo(handlers);
        new TimeHandler().registerTo(handlers);
        new UuidHandler().registerTo(handlers);
        new ColorHandler().registerTo(handlers);
        new TextFormatHandler().registerTo(handlers);
        new VariableScopeHandler().registerTo(handlers);
        definitions = new NodeDefinitionRegistry();
        definitions.registerAll("production", new NodeDefinitionLoader().loadFromClasspath("nodes"));
        executor = new FlowExecutor(handlers, definitions, new TypeAdapterRegistry(), Map.of());
    }

    @AfterEach
    void tearDown() {
        if (executor != null) executor.shutdown();
        if (handlers != null) handlers.clear();
        MockBukkit.unmock();
    }

    @Test
    void largeProductionGraphExecutesDataControlAsyncAndUtilityFamiliesTogether() {
        AtomicInteger completedBranches = new AtomicInteger();
        List<String> branchOrder = new CopyOnWriteArrayList<>();
        registerCurrent("mega.entry", (context, node) -> context.triggerOutput("flow"));
        registerCurrent("mega.async", (context, node) -> {
            branchOrder.add("async");
            context.getAsyncOperations().put("mega", CompletableFuture.completedFuture(null));
        });
        registerCurrent("mega.branch", (context, node) -> {
            branchOrder.add(context.getInputValue(node, "name", String.class));
            completedBranches.incrementAndGet();
        });
        registerCurrent("mega.assert", (context, node) -> {
            assertEquals(42.0, context.getInputValue(node, "score", Double.class));
            assertEquals(true, context.getInputValue(node, "score_exists", Boolean.class));
            assertEquals(1, context.getInputValue(node, "switch_index", Integer.class));
            assertEquals(true, context.getInputValue(node, "switch_matched", Boolean.class));
            assertEquals("RESYNC RUNTIME TEST", context.getInputValue(node, "decoded", String.class));
            assertEquals("RESYNC|RUNTIME|TEST", context.getInputValue(node, "joined", String.class));
            assertEquals(3, context.getInputValue(node, "word_count", Integer.class));
            assertEquals("RESYNC", context.getInputValue(node, "first_word", String.class));
            assertEquals("TEST", context.getInputValue(node, "last_word", String.class));
            assertEquals(true, context.getInputValue(node, "contains_runtime", Boolean.class));
            assertEquals(64, context.getInputValue(node, "hash", String.class).length());
            assertEquals(20.0, context.getInputValue(node, "sum", Double.class));
            assertEquals(5.0, context.getInputValue(node, "average", Double.class));
            assertEquals(40.0, context.getInputValue(node, "map_value", Double.class));
            assertTrue(context.getInputValue(node, "map_keys", List.class).containsAll(List.of("alpha", "beta")));
            assertEquals(42.0, context.getInputValue(node, "json_score", Double.class));
            assertEquals(true, context.getInputValue(node, "json_has", Boolean.class));
            assertTrue(context.getInputValue(node, "json_string", String.class).contains("score"));
            assertEquals(42.5, context.getInputValue(node, "converted_number", Double.class));
            assertEquals(true, context.getInputValue(node, "converted_boolean", Boolean.class));
            assertEquals("42.0", context.getInputValue(node, "converted_string", String.class));
            assertEquals(true, context.getInputValue(node, "logic", Boolean.class));
            assertEquals("#CC9966", context.getInputValue(node, "color", String.class));
            assertEquals("123e4567-e89b-12d3-a456-426614174000", context.getInputValue(node, "uuid", String.class));
            assertEquals(1, context.getInputValue(node, "uuid_version", Integer.class));
            assertEquals("2026-07-18 12:34:56", context.getInputValue(node, "formatted_time", String.class));
            assertEquals(true, context.getInputValue(node, "time_valid", Boolean.class));
            assertEquals(6.0, context.getInputValue(node, "vector_x", Double.class));
            assertEquals(8.0, context.getInputValue(node, "vector_y", Double.class));
            assertEquals(10.0, context.getInputValue(node, "hypotenuse", Double.class));
            assertTrue(context.getInputValue(node, "component", String.class).contains("Mega Flow"));
            branchOrder.add("assert");
            completedBranches.incrementAndGet();
        });

        FlowGraph graph = graph();
        executionNodes(graph);
        stringNodes(graph);
        collectionNodes(graph);
        structuredDataNodes(graph);
        utilityNodes(graph);
        assertionConnections(graph);

        executor.execute(graph, "entry", null, null, Map.of()).join();

        assertEquals(4, completedBranches.get());
        assertEquals(List.of("async", "assert", "second", "third", "fourth"), branchOrder);
        assertTrue(definitions.getAllDefinitions().size() >= 1_300);
    }

    private void executionNodes(FlowGraph graph) {
        graph.getNodes().put("entry", direct("mega.entry", Map.of()));
        graph.getNodes().put("score_set", node("variable.access", Map.of("mode", "set", "scope", "local", "persist", false, "name", "score")));
        graph.getNodes().put("score_increment", node("variable.access", Map.of("mode", "increment", "scope", "local", "persist", false, "name", "score", "amount", 2.0)));
        graph.getNodes().put("score_get", node("variable.access", Map.of("mode", "get", "scope", "local", "persist", false, "name", "score")));
        graph.getNodes().put("switch", node("logic.switch_case", Map.of("cases", List.of(41, 42L, 43.0))));
        graph.getNodes().put("branches", node("branch.all", Map.of()));
        graph.getNodes().put("async", direct("mega.async", Map.of()));
        graph.getNodes().put("assert", direct("mega.assert", Map.of()));
        graph.getNodes().put("second", direct("mega.branch", Map.of("name", "second")));
        graph.getNodes().put("third", direct("mega.branch", Map.of("name", "third")));
        graph.getNodes().put("fourth", direct("mega.branch", Map.of("name", "fourth")));
        connect(graph, "entry", "flow", "score_set", "flow");
        connect(graph, "math_multiply", "result", "score_set", "value");
        connect(graph, "score_set", "flow", "score_increment", "flow");
        connect(graph, "score_increment", "flow", "score_get", "flow");
        connect(graph, "score_get", "flow", "switch", "flow");
        connect(graph, "score_get", "value", "switch", "value");
        connect(graph, "switch", "flow", "branches", "flow");
        connect(graph, "branches", "branch_0", "async", "flow");
        connect(graph, "async", "flow", "assert", "flow");
        connect(graph, "branches", "branch_1", "second", "flow");
        connect(graph, "branches", "branch_2", "third", "flow");
        connect(graph, "branches", "branch_3", "fourth", "flow");
    }

    private void stringNodes(FlowGraph graph) {
        graph.getNodes().put("trim", node("string.trim", Map.of("value", "  resync flow test  ")));
        graph.getNodes().put("upper", node("string.upper", Map.of()));
        graph.getNodes().put("replace", node("string.replace", Map.of("target", "FLOW", "replacement", "RUNTIME")));
        graph.getNodes().put("encode", node("string.base64_encode", Map.of()));
        graph.getNodes().put("decode", node("string.base64_decode", Map.of()));
        graph.getNodes().put("split", node("string.split", Map.of("delimiter", " ")));
        graph.getNodes().put("unique", node("list.unique", Map.of()));
        graph.getNodes().put("sort", node("list.sort", Map.of("sort_order", "ascending")));
        graph.getNodes().put("join", node("list.join", Map.of("separator", "|")));
        graph.getNodes().put("word_count", node("list.size", Map.of()));
        graph.getNodes().put("first_word", node("list.first", Map.of()));
        graph.getNodes().put("last_word", node("list.last", Map.of()));
        graph.getNodes().put("contains_runtime", node("list.contains", Map.of("value", "RUNTIME")));
        graph.getNodes().put("hash", node("string.sha256", Map.of()));
        connect(graph, "trim", "result", "upper", "value");
        connect(graph, "upper", "result", "replace", "value");
        connect(graph, "replace", "result", "encode", "text");
        connect(graph, "encode", "encoded", "decode", "encoded");
        connect(graph, "decode", "decoded", "split", "value");
        connect(graph, "split", "result", "unique", "list");
        connect(graph, "unique", "unique_list", "sort", "list");
        connect(graph, "sort", "sorted_list", "join", "list");
        connect(graph, "sort", "sorted_list", "word_count", "list");
        connect(graph, "sort", "sorted_list", "first_word", "list");
        connect(graph, "sort", "sorted_list", "last_word", "list");
        connect(graph, "sort", "sorted_list", "contains_runtime", "list");
        connect(graph, "decode", "decoded", "hash", "text");
    }

    private void collectionNodes(FlowGraph graph) {
        graph.getNodes().put("sum", node("list.sum", Map.of("list", List.of(2, 4L, 6.0, 8.0F))));
        graph.getNodes().put("average", node("list.average", Map.of("list", List.of(2, 4L, 6.0, 8.0F))));
        graph.getNodes().put("math_add", node("math.add", Map.of("a", 12.0, "b", 8.0)));
        graph.getNodes().put("math_multiply", node("math.multiply", Map.of("b", 2.0)));
        graph.getNodes().put("map_set", node("map.set", Map.of("map", Map.of("alpha", 1), "key", "beta")));
        graph.getNodes().put("map_get", node("map.get", Map.of("key", "beta")));
        graph.getNodes().put("map_keys", node("map.keys", Map.of()));
        connect(graph, "math_add", "result", "math_multiply", "a");
        connect(graph, "math_multiply", "result", "map_set", "value");
        connect(graph, "map_set", "map", "map_get", "map");
        connect(graph, "map_set", "map", "map_keys", "map");
    }

    private void structuredDataNodes(FlowGraph graph) {
        graph.getNodes().put("json_parse", node("json.parse", Map.of("json_string", "{\"player\":{\"score\":42},\"tags\":[\"mega\",\"flow\"]}")));
        graph.getNodes().put("json_get", node("json.get", Map.of("path", "player.score")));
        graph.getNodes().put("json_has", node("json.has", Map.of("path", "player.score")));
        graph.getNodes().put("json_string", node("json.to.string", Map.of()));
        graph.getNodes().put("converted_number", node("to.number", Map.of("value", "42.5")));
        graph.getNodes().put("converted_boolean", node("to.boolean", Map.of("value", "yes")));
        graph.getNodes().put("converted_string", node("to.string", Map.of()));
        graph.getNodes().put("equals", node("logic.compare_equals", Map.of()));
        graph.getNodes().put("greater", node("logic.compare_greater", Map.of("b", 41.0)));
        graph.getNodes().put("logic", node("logic.logic_and", Map.of()));
        connect(graph, "json_parse", "object", "json_get", "object");
        connect(graph, "json_parse", "object", "json_has", "object");
        connect(graph, "json_parse", "object", "json_string", "object");
        connect(graph, "score_get", "value", "converted_string", "value");
        connect(graph, "score_get", "value", "equals", "a");
        connect(graph, "json_get", "value", "equals", "b");
        connect(graph, "score_get", "value", "greater", "a");
        connect(graph, "equals", "result", "logic", "a");
        connect(graph, "greater", "result", "logic", "b");
    }

    private void utilityNodes(FlowGraph graph) {
        graph.getNodes().put("color_from", node("color.from.hex", Map.of("hex_string", "#336699")));
        graph.getNodes().put("color_invert", node("color.invert", Map.of()));
        graph.getNodes().put("color_to", node("color.to.hex", Map.of()));
        graph.getNodes().put("uuid_from", node("uuid.from.string", Map.of("uuid_string", "123e4567-e89b-12d3-a456-426614174000")));
        graph.getNodes().put("uuid_to", node("uuid.to.string", Map.of()));
        graph.getNodes().put("uuid_version", node("utility.uuid_version", Map.of()));
        graph.getNodes().put("time_parse", node("time.parse", Map.of("string", "2026-07-18 12:34:56", "format", "uuuu-MM-dd HH:mm:ss", "time_zone", "UTC")));
        graph.getNodes().put("time_format", node("time.format", Map.of("format", "uuuu-MM-dd HH:mm:ss", "time_zone", "UTC")));
        graph.getNodes().put("vector_create", node("math.vector_create", Map.of("x", 3.0, "y", 4.0, "z", 0.0)));
        graph.getNodes().put("vector_multiply", node("math.vector_multiply", Map.of("scalar", 2.0)));
        graph.getNodes().put("vector_split", node("math.vector_split", Map.of()));
        graph.getNodes().put("hypotenuse", node("math.hypotenuse", Map.of()));
        graph.getNodes().put("component", node("text.format_mini_message", Map.of("text", "<green>Mega Flow</green>")));
        connect(graph, "color_from", "color", "color_invert", "color");
        connect(graph, "color_invert", "inverted_color", "color_to", "color");
        connect(graph, "uuid_from", "uuid_object", "uuid_to", "uuid_object");
        connect(graph, "uuid_from", "uuid_object", "uuid_version", "uuid_object");
        connect(graph, "time_parse", "time", "time_format", "time");
        connect(graph, "vector_create", "vector", "vector_multiply", "vector");
        connect(graph, "vector_multiply", "result_vector", "vector_split", "vector");
        connect(graph, "vector_split", "x", "hypotenuse", "a");
        connect(graph, "vector_split", "y", "hypotenuse", "b");
    }

    private void assertionConnections(FlowGraph graph) {
        Map<String, String[]> inputs = Map.ofEntries(
            Map.entry("score", pair("score_get", "value")), Map.entry("score_exists", pair("score_get", "exists")),
            Map.entry("switch_index", pair("switch", "index")), Map.entry("switch_matched", pair("switch", "matched")),
            Map.entry("decoded", pair("decode", "decoded")), Map.entry("joined", pair("join", "string")),
            Map.entry("word_count", pair("word_count", "size")), Map.entry("first_word", pair("first_word", "item")),
            Map.entry("last_word", pair("last_word", "item")), Map.entry("contains_runtime", pair("contains_runtime", "contains")),
            Map.entry("hash", pair("hash", "hash")), Map.entry("sum", pair("sum", "sum")), Map.entry("average", pair("average", "average")),
            Map.entry("map_value", pair("map_get", "value")), Map.entry("map_keys", pair("map_keys", "keys")),
            Map.entry("json_score", pair("json_get", "value")), Map.entry("json_has", pair("json_has", "has")),
            Map.entry("json_string", pair("json_string", "string")), Map.entry("converted_number", pair("converted_number", "number")),
            Map.entry("converted_boolean", pair("converted_boolean", "boolean")), Map.entry("converted_string", pair("converted_string", "string")),
            Map.entry("logic", pair("logic", "result")), Map.entry("color", pair("color_to", "hex_string")),
            Map.entry("uuid", pair("uuid_to", "uuid_string")), Map.entry("uuid_version", pair("uuid_version", "version")),
            Map.entry("formatted_time", pair("time_format", "string")), Map.entry("time_valid", pair("time_format", "valid")),
            Map.entry("vector_x", pair("vector_split", "x")), Map.entry("vector_y", pair("vector_split", "y")),
            Map.entry("hypotenuse", pair("hypotenuse", "hypotenuse")), Map.entry("component", pair("component", "result"))
        );
        inputs.forEach((targetPin, source) -> connect(graph, source[0], source[1], "assert", targetPin));
    }

    private FlowGraph graph() {
        FlowGraph graph = new FlowGraph();
        graph.setId("production-mega-graph");
        return graph;
    }

    private FlowNode node(String type, Map<String, Object> inputs) {
        assertTrue(definitions.get(type) != null, type);
        return new FlowNode(type, 0, 0, inputs);
    }

    private FlowNode direct(String type, Map<String, Object> inputs) {
        return new FlowNode(type, 0, 0, inputs);
    }

    private void connect(FlowGraph graph, String sourceNode, String sourcePin, String targetNode, String targetPin) {
        graph.getConnections().add(new FlowConnection(sourceNode, sourcePin, targetNode, targetPin));
    }

    private String[] pair(String node, String pin) {
        return new String[]{node, pin};
    }

    private void registerCurrent(String type, BiConsumer<FlowContext, FlowNode> operation) {
        handlers.register(type, new NodeHandler() {
            @Override
            public void execute(FlowContext context, FlowNode node) {
                operation.accept(context, node);
            }

            @Override
            public ThreadPolicy getThreadPolicy() {
                return ThreadPolicy.CURRENT;
            }

            @Override
            public Set<String> getSupportedOperations() {
                return Set.of();
            }
        });
    }
}
