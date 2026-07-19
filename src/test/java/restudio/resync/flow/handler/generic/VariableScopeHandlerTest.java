package restudio.resync.flow.handler.generic;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowNode;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.FlowRuntime;
import restudio.resync.flow.TypeAdapterRegistry;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VariableScopeHandlerTest {
    private VariableScopeHandler handler;
    private FlowGraph graph;
    private FlowRuntime runtime;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        handler = new VariableScopeHandler();
        graph = new FlowGraph();
        graph.setId("variable-scope-test");
        runtime = new FlowRuntime(graph, new TypeAdapterRegistry(), new HashMap<>());
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void variableAccessSupportsEveryAdvertisedMode() {
        execute("set", Map.of("value", 8.0));
        assertEquals(8.0, output("value"));

        execute("get", Map.of());
        assertEquals(8.0, output("value"));
        assertEquals(true, output("exists"));

        execute("increment", Map.of("amount", 2.0));
        assertEquals(10.0, output("value"));

        execute("decrement", Map.of("amount", 3.0));
        assertEquals(7.0, output("value"));

        execute("multiply", Map.of("amount", 2.0));
        assertEquals(14.0, output("value"));

        execute("divide", Map.of("amount", 7.0));
        assertEquals(2.0, output("value"));

        execute("list", Map.of());
        assertTrue(((List<?>) output("variables")).contains("score"));

        execute("delete", Map.of());
        assertNull(output("value"));
        assertEquals(false, output("exists"));
    }

    @Test
    void missingPersistentPlayerValueIsAValidGetResult() {
        FlowNode node = node("get", Map.of("persist", true, "scope", "player", "player", MockBukkit.getMock().addPlayer()));

        assertDoesNotThrow(() -> handler.execute(new FlowContext(runtime, null, null), node));
        assertNull(runtime.getNodeOutput("node", "value"));
        assertEquals(false, runtime.getNodeOutput("node", "exists"));
    }

    private void execute(String mode, Map<String, Object> additions) {
        FlowNode node = node(mode, additions);
        assertDoesNotThrow(() -> handler.execute(new FlowContext(runtime, null, null), node));
    }

    private FlowNode node(String mode, Map<String, Object> additions) {
        Map<String, Object> inputs = new HashMap<>();
        inputs.put("mode", mode);
        inputs.put("scope", "local");
        inputs.put("name", "score");
        inputs.put("persist", false);
        inputs.putAll(additions);
        FlowNode node = new FlowNode("variable.access", 0, 0, inputs);
        node.setHandlerConfig(Map.of("operation", "variable_access"));
        graph.getNodes().put("node", node);
        return node;
    }

    private Object output(String pin) {
        return runtime.getNodeOutput("node", pin);
    }
}
