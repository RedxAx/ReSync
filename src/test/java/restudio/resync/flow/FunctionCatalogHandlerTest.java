package restudio.resync.flow;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.flow.data.FlowDataType;
import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowNode;
import restudio.flow.data.FlowOperationResult;
import restudio.resync.flow.handler.generic.FunctionCatalogHandler;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FunctionCatalogHandlerTest {
    @TempDir
    File directory;

    @Test
    void functionsCanBeListedFoundFilteredAndIndexed() {
        FlowStorage storage = storage();
        FunctionCatalogHandler handler = new FunctionCatalogHandler(storage);

        TestFlowContext listed = execute(handler, "list", Map.of());
        assertEquals(List.of("alpha_reward", "daily_reward"), listed.outputs.get("functions"));

        TestFlowContext found = execute(handler, "find", Map.of("name", "DAILY_REWARD"));
        assertEquals("daily_reward", found.outputs.get("function"));
        assertTrue((Boolean) found.outputs.get("found"));
        assertTrue(assertInstanceOf(FlowOperationResult.class, found.outputs.get("result")).success());

        TestFlowContext filtered = execute(handler, "filter", Map.of("query", "reward"));
        assertEquals(List.of("alpha_reward", "daily_reward"), filtered.outputs.get("functions"));

        TestFlowContext indexed = execute(handler, "index", Map.of("functions", List.of("other", "daily_reward"), "function", "daily_reward"));
        assertEquals(1, indexed.outputs.get("index"));
        assertTrue((Boolean) indexed.outputs.get("found"));

        TestFlowContext atIndex = execute(handler, "at_index", Map.of("functions", List.of("alpha_reward"), "index", 4));
        assertFalse((Boolean) atIndex.outputs.get("found"));
        assertFalse(assertInstanceOf(FlowOperationResult.class, atIndex.outputs.get("result")).success());
    }

    @Test
    void functionDetailsExposeNamedTypes() {
        FunctionCatalogHandler handler = new FunctionCatalogHandler(storage());

        TestFlowContext context = execute(handler, "describe", Map.of("function", "daily_reward"));

        assertEquals(Map.of("player", "player"), context.outputs.get("inputs"));
        assertEquals(Map.of("granted", "boolean"), context.outputs.get("outputs"));
    }

    private FlowStorage storage() {
        FlowStorage storage = new FlowStorage(directory);
        storage.saveGraph(function("daily_reward"));
        storage.saveGraph(function("alpha_reward"));
        FlowGraph flow = new FlowGraph();
        flow.setId("not_callable");
        storage.saveGraph(flow);
        return storage;
    }

    private FlowGraph function(String id) {
        FlowGraph graph = new FlowGraph();
        graph.setId(id);
        graph.setFunction(true);
        graph.setFunctionInputs(List.of(new FlowGraph.FunctionParameter("player", FlowDataType.PLAYER)));
        graph.setFunctionOutputs(List.of(new FlowGraph.FunctionParameter("granted", FlowDataType.BOOLEAN)));
        return graph;
    }

    private TestFlowContext execute(FunctionCatalogHandler handler, String operation, Map<String, Object> inputs) {
        FlowNode node = new FlowNode("function." + operation, 0, 0, Map.of());
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
            return value != null ? type.cast(value) : defaultValue;
        }

        @Override
        public void setOutput(FlowNode node, String pinName, Object value) {
            outputs.put(pinName, value);
        }
    }
}
