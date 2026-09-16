package restudio.resync.flow;

import com.google.gson.Gson;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.flow.data.FlowDataType;
import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowNode;
import restudio.flow.data.FlowOperationResult;
import restudio.resync.flow.handler.generic.FunctionCatalogHandler;
import restudio.resync.storage.AssetTransactionCoordinator;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FunctionCatalogHandlerTest {
    @TempDir
    File directory;
    private final List<AssetTransactionCoordinator> coordinators = new ArrayList<>();

    @AfterEach
    void closeCoordinators() throws Exception {
        for (AssetTransactionCoordinator coordinator : coordinators.reversed()) {
            coordinator.close();
        }
        coordinators.clear();
    }

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
    void catalogLookupUsesCasePreferenceAndReportsCaseFoldCollisions() {
        FlowStorage storage = catalogStorage("alpha", "Alpha", "ALPHA", "beta");
        FunctionCatalogHandler handler = new FunctionCatalogHandler(storage);

        TestFlowContext listed = execute(handler, "list", Map.of());
        assertEquals(List.of("ALPHA", "Alpha", "alpha", "beta"), listed.outputs.get("functions"));

        TestFlowContext exact = execute(handler, "find", Map.of("name", "ALPHA"));
        assertEquals("ALPHA", exact.outputs.get("function"));
        assertTrue((Boolean) exact.outputs.get("found"));

        TestFlowContext ambiguous = execute(handler, "find", Map.of("name", "aLpHa"));
        assertEquals("", ambiguous.outputs.get("function"));
        assertFalse((Boolean) ambiguous.outputs.get("found"));
        FlowOperationResult<?> ambiguousResult = assertInstanceOf(FlowOperationResult.class, ambiguous.outputs.get("result"));
        assertEquals("FUNCTION_NAME_AMBIGUOUS", ambiguousResult.errorCode());
        assertEquals(List.of("ALPHA", "Alpha", "alpha"), ambiguousResult.details().get("matches"));

        TestFlowContext exists = execute(handler, "exists", Map.of("function", "aLpHa"));
        assertFalse((Boolean) exists.outputs.get("exists"));
        TestFlowContext exactExists = execute(handler, "exists", Map.of("function", "Alpha"));
        assertTrue((Boolean) exactExists.outputs.get("exists"));

        TestFlowContext index = execute(handler, "index", Map.of("function", "aLpHa"));
        assertEquals(-1, index.outputs.get("index"));
        assertFalse((Boolean) index.outputs.get("found"));

        TestFlowContext atIndex = execute(handler, "at_index", Map.of("index", 1));
        assertEquals("Alpha", atIndex.outputs.get("function"));
        assertTrue((Boolean) atIndex.outputs.get("found"));
    }

    @Test
    void filterUsesRootLocaleAndReturnsDetachedCatalogLists() {
        FlowStorage storage = catalogStorage("Iota", "item", "other");
        FunctionCatalogHandler handler = new FunctionCatalogHandler(storage);
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            TestFlowContext filtered = execute(handler, "filter", Map.of("query", "i"));
            List<?> output = assertInstanceOf(List.class, filtered.outputs.get("functions"));
            assertEquals(List.of("Iota", "item"), output);
            assertNotSame(storage.listGraphIds("function"), output);
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void explicitListsStayEmptyAndAreNotMutated() {
        FunctionCatalogHandler handler = new FunctionCatalogHandler(storage());
        List<String> values = new ArrayList<>(List.of("custom_first", "custom_second"));

        TestFlowContext indexed = execute(handler, "index", Map.of("functions", values, "function", "CUSTOM_SECOND"));
        assertEquals(1, indexed.outputs.get("index"));
        assertEquals(List.of("custom_first", "custom_second"), values);

        TestFlowContext atIndex = execute(handler, "at_index", Map.of("functions", values, "index", 0));
        assertEquals("custom_first", atIndex.outputs.get("function"));
        assertEquals(List.of("custom_first", "custom_second"), values);

        TestFlowContext empty = execute(handler, "at_index", Map.of("functions", List.of(), "index", 0));
        assertEquals("", empty.outputs.get("function"));
        assertFalse((Boolean) empty.outputs.get("found"));
        assertEquals("FUNCTION_INDEX_OUT_OF_RANGE", assertInstanceOf(FlowOperationResult.class, empty.outputs.get("result")).errorCode());
    }

    @Test
    void invalidIndicesFailWithoutTruncation() {
        FunctionCatalogHandler handler = new FunctionCatalogHandler(storage());

        for (Number value : List.of(1.5d, Double.NaN, Double.POSITIVE_INFINITY, Long.MAX_VALUE)) {
            TestFlowContext context = execute(handler, "at_index", Map.of("functions", List.of("one", "two"), "index", value));
            assertEquals("", context.outputs.get("function"));
            assertFalse((Boolean) context.outputs.get("found"));
            FlowOperationResult<?> result = assertInstanceOf(FlowOperationResult.class, context.outputs.get("result"));
            assertEquals("FUNCTION_INDEX_INVALID", result.errorCode());
            assertEquals(value, result.details().get("index"));
        }
    }

    @Test
    void absentStorageKeepsEmptyAndFailureContracts() {
        FunctionCatalogHandler handler = new FunctionCatalogHandler(null);

        TestFlowContext listed = execute(handler, "list", Map.of());
        assertEquals(List.of(), listed.outputs.get("functions"));
        TestFlowContext filtered = execute(handler, "filter", Map.of("query", "anything"));
        assertEquals(List.of(), filtered.outputs.get("functions"));
        TestFlowContext exists = execute(handler, "exists", Map.of("function", "anything"));
        assertFalse((Boolean) exists.outputs.get("exists"));
        TestFlowContext found = execute(handler, "find", Map.of("name", "anything"));
        assertFalse((Boolean) found.outputs.get("found"));
        assertEquals("FUNCTION_NOT_FOUND", assertInstanceOf(FlowOperationResult.class, found.outputs.get("result")).errorCode());
        TestFlowContext indexed = execute(handler, "index", Map.of("function", "anything"));
        assertEquals(-1, indexed.outputs.get("index"));
        assertFalse((Boolean) indexed.outputs.get("found"));
    }

    @Test
    void catalogOperationsOnlyWriteDeclaredOutputs() {
        FunctionCatalogHandler handler = new FunctionCatalogHandler(storage());
        assertEquals(Set.of("functions"), execute(handler, "list", Map.of()).outputs.keySet());
        assertEquals(Set.of("function", "found", "result"), execute(handler, "find", Map.of("name", "daily_reward")).outputs.keySet());
        assertEquals(Set.of("exists"), execute(handler, "exists", Map.of("function", "daily_reward")).outputs.keySet());
        assertEquals(Set.of("index", "found"), execute(handler, "index", Map.of("function", "daily_reward")).outputs.keySet());
        assertEquals(Set.of("function", "found", "result"), execute(handler, "at_index", Map.of("index", 0)).outputs.keySet());
        assertEquals(Set.of("functions"), execute(handler, "filter", Map.of("query", "reward")).outputs.keySet());
    }

    @Test
    void functionDetailsExposeNamedTypes() {
        FunctionCatalogHandler handler = new FunctionCatalogHandler(storage());

        TestFlowContext context = execute(handler, "describe", Map.of("function", "daily_reward"));

        assertEquals(Map.of("player", "player"), context.outputs.get("inputs"));
        assertEquals(Map.of("granted", "boolean"), context.outputs.get("outputs"));
        assertEquals(Set.of("inputs", "outputs", "result"), context.outputs.keySet());
    }

    private FlowStorage storage() {
        FlowStorage storage = new FlowStorage(directory, coordinator());
        storage.saveGraph(function("daily_reward"));
        storage.saveGraph(function("alpha_reward"));
        FlowGraph flow = new FlowGraph();
        flow.setId("not_callable");
        storage.saveGraph(flow);
        return storage;
    }

    private FlowStorage catalogStorage(String... ids) {
        Map<String, FlowGraph> graphs = new LinkedHashMap<>();
        for (String id : ids) {
            graphs.put(id, function(id));
        }
        return new CatalogStorage(directory, graphs, coordinator());
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

    private AssetTransactionCoordinator coordinator() {
        try {
            AssetTransactionCoordinator coordinator = AssetTransactionCoordinator.open(directory.toPath().resolve("assets"), new Gson());
            coordinators.add(coordinator);
            return coordinator;
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to create the test asset coordinator", exception);
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
    }

    private static class CatalogStorage extends FlowStorage {
        private final Map<String, FlowGraph> graphs;

        private CatalogStorage(File directory, Map<String, FlowGraph> graphs,
                               AssetTransactionCoordinator coordinator) {
            super(directory, coordinator);
            this.graphs = graphs;
        }

        @Override
        public List<String> listGraphIds(String resourceType) {
            return List.copyOf(graphs.keySet());
        }

        @Override
        public FlowGraph getGraph(String resourceType, String id) {
            return graphs.get(id);
        }
    }
}
