package restudio.resync.flow.handler.generic;

import org.junit.jupiter.api.Test;
import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowNode;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.NodeHandler;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void publishedCollectionPinsDriveFilteringMappingAndReduction() {
        List<Map<String, Object>> values = List.of(Map.of("name", "Low", "score", 2), Map.of("name", "High", "score", 8));

        TestFlowContext filter = execute("filter", Map.of("list", values, "property_name", "score", "operator", "greater_than", "compare_value", 4));
        TestFlowContext map = execute("map", Map.of("list", values, "transformation_type", "property:name"));
        TestFlowContext reduce = execute("reduce", Map.of("list", List.of("A", "B", "C"), "operation", "concat", "separator", "-"));

        assertEquals(List.of(values.get(1)), filter.outputs.get("filtered_list"));
        assertEquals(List.of("Low", "High"), map.outputs.get("transformed_list"));
        assertEquals("A-B-C", reduce.outputs.get("result"));
    }

    @Test
    void groupingAndQuantifiersUseTheSamePredicateContract() {
        List<Map<String, Object>> values = List.of(Map.of("team", "red", "score", 2), Map.of("team", "blue", "score", 8), Map.of("team", "red", "score", 10));

        TestFlowContext groups = execute("group_by", Map.of("list", values, "property_name", "team"));
        TestFlowContext any = execute("any", Map.of("list", values, "property_name", "score", "operator", "greater_than", "compare_value", 9));
        TestFlowContext all = execute("all", Map.of("list", values, "property_name", "score", "operator", "greater_than", "compare_value", 1));
        TestFlowContext none = execute("none", Map.of("list", values, "property_name", "team", "operator", "equals", "compare_value", "green"));

        assertEquals(List.of(values.get(0), values.get(2)), ((Map<?, ?>) groups.outputs.get("groups")).get("red"));
        assertTrue((Boolean) any.outputs.get("matches"));
        assertTrue((Boolean) all.outputs.get("matches"));
        assertTrue((Boolean) none.outputs.get("matches"));
        assertTrue((Boolean) execute("all", Map.of("list", List.of(), "operator", "equals", "compare_value", 1)).outputs.get("matches"));
        assertFalse((Boolean) execute("any", Map.of("list", List.of(), "operator", "equals", "compare_value", 1)).outputs.get("matches"));
    }

    @Test
    void mutationNodesCopyConnectedListsBeforeChangingThem() {
        List<String> source = List.of("first");

        TestFlowContext context = execute("add", Map.of("list", source, "value", "second"));

        assertEquals(List.of("first"), source);
        assertEquals(List.of("first", "second"), context.outputs.get("list"));
    }

    @Test
    void embeddedFilterSubflowsComposeWithoutBlockingTheHandler() {
        CompletableFuture<Boolean> first = new CompletableFuture<>();
        CompletableFuture<Boolean> second = new CompletableFuture<>();
        AsyncSubFlowContext context = new AsyncSubFlowContext(Map.of("list", List.of("first", "second")), Map.of(0, first, 1, second));
        HandlerRegistry registry = new HandlerRegistry();
        new GenericListHandler().registerTo(registry);
        FlowNode node = new FlowNode("list.filter", 0, 0, Map.of());
        node.setHandlerConfig(Map.of("operation", "filter"));

        registry.getHandler("GenericListHandler").execute(context, node);

        assertFalse(context.awaited.isDone());
        assertFalse(context.outputs.containsKey("filtered_list"));
        first.complete(true);
        assertFalse(context.awaited.isDone());
        second.complete(false);
        context.awaited.join();
        assertEquals(List.of("first"), context.outputs.get("filtered_list"));
    }

    private TestFlowContext execute(String operation, Map<String, Object> inputs) {
        HandlerRegistry registry = new HandlerRegistry();
        new GenericListHandler().registerTo(registry);
        FlowNode node = new FlowNode("list." + operation, 0, 0, Map.of());
        node.setHandlerConfig(Map.of("operation", operation));
        TestFlowContext context = new TestFlowContext(inputs);
        registry.getHandler("GenericListHandler").execute(context, node);
        return context;
    }

    private static class TestFlowContext extends FlowContext {
        private final Map<String, Object> inputs;
        protected final Map<String, Object> outputs = new HashMap<>();
        private String triggeredOutput;

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
        public FlowGraph extractSubGraph(FlowNode node, String pinName) {
            return null;
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

    private static final class AsyncSubFlowContext extends TestFlowContext {
        private final FlowGraph subGraph = new FlowGraph();
        private final Map<Integer, CompletableFuture<Boolean>> results;
        private CompletableFuture<Void> awaited = CompletableFuture.completedFuture(null);

        private AsyncSubFlowContext(Map<String, Object> inputs, Map<Integer, CompletableFuture<Boolean>> results) {
            super(inputs);
            this.results = results;
            subGraph.getNodes().put("terminal", new FlowNode("test", 0, 0, Map.of()));
        }

        @Override
        public FlowGraph extractSubGraph(FlowNode node, String pinName) {
            return subGraph;
        }

        @Override
        public CompletableFuture<Boolean> executeSubFlowBooleanAsync(FlowGraph graph, FlowNode node, Map<String, Object> extraInputs) {
            return results.get(((Number) extraInputs.get("index")).intValue());
        }

        @Override
        public CompletableFuture<Void> awaitBeforeContinuation(CompletableFuture<?> operation) {
            awaited = operation.thenApply(ignored -> null);
            return awaited;
        }
    }
}
