package restudio.resync.flow;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import restudio.flow.data.FlowConnection;
import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowNode;
import restudio.resync.flow.handler.HandlerRegistry;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FlowExecutorParallelBranchIsolationTest {
    @BeforeEach
    void setUp() {
        MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void parallelTargetsKeepLocalAndOutputFramesIsolatedUntilTheyComplete() {
        CompletableFuture<Void> gate = new CompletableFuture<>();
        AtomicReference<String> delayedValue = new AtomicReference<>();
        AtomicReference<String> immediateValue = new AtomicReference<>();
        HandlerRegistry handlers = new HandlerRegistry();
        handlers.register("source", (context, node) -> {
        });
        handlers.register("delayed", (context, node) -> {
            context.getLocalVariables().put("branch", "delayed");
            context.setOutput(node, "value", "delayed-output");
            context.getAsyncOperations().put("gate", gate);
        });
        handlers.register("immediate", (context, node) -> {
            context.getLocalVariables().put("branch", "immediate");
            context.setOutput(node, "value", "immediate-output");
        });
        handlers.register("delayed_observer", (context, node) -> delayedValue.set(
            context.getLocalVariables().get("branch") + ":" + context.getInputValue(node, "value", String.class)));
        handlers.register("immediate_observer", (context, node) -> immediateValue.set(
            context.getLocalVariables().get("branch") + ":" + context.getInputValue(node, "value", String.class)));

        FlowGraph graph = new FlowGraph();
        graph.setId("parallel-frame-isolation");
        graph.getNodes().put("source", new FlowNode("source", 0, 0, Map.of()));
        graph.getNodes().put("delayed", new FlowNode("delayed", 100, 0, Map.of()));
        graph.getNodes().put("immediate", new FlowNode("immediate", 100, 100, Map.of()));
        graph.getNodes().put("delayed_observer", new FlowNode("delayed_observer", 200, 0, Map.of()));
        graph.getNodes().put("immediate_observer", new FlowNode("immediate_observer", 200, 100, Map.of()));
        graph.getConnections().add(new FlowConnection("source", "flow", "delayed", "flow"));
        graph.getConnections().add(new FlowConnection("source", "flow", "immediate", "flow"));
        graph.getConnections().add(new FlowConnection("delayed", "flow", "delayed_observer", "flow"));
        graph.getConnections().add(new FlowConnection("delayed", "value", "delayed_observer", "value"));
        graph.getConnections().add(new FlowConnection("immediate", "flow", "immediate_observer", "flow"));
        graph.getConnections().add(new FlowConnection("immediate", "value", "immediate_observer", "value"));

        CompletableFuture<Void> execution = new FlowExecutor(handlers, new TypeAdapterRegistry(), Map.of())
            .execute(graph, "source", null, null, Map.of());

        assertEquals("immediate:immediate-output", immediateValue.get());
        assertNull(delayedValue.get());
        assertFalse(execution.isDone());

        gate.complete(null);
        execution.join();

        assertEquals("delayed:delayed-output", delayedValue.get());
    }

    @Test
    void completedBranchesMergeMapDeltasInConnectionOrderAndShareExecutionScopes() {
        FlowGraph graph = new FlowGraph();
        graph.setId("parallel-merge");
        FlowRuntime runtime = new FlowRuntime(graph, new TypeAdapterRegistry(), new HashMap<>(), new HashMap<>());
        runtime.getLocalVariables().put("conflict", "base");
        runtime.getLocalVariables().put("removed", "base");
        runtime.setNodeOutput("source", "conflict", "base");

        FlowRuntime first = runtime.forkBranch();
        FlowRuntime second = runtime.forkBranch();
        first.getLocalVariables().put("conflict", "first");
        first.getLocalVariables().put("firstOnly", 1);
        first.getLocalVariables().remove("removed");
        first.setNodeOutput("source", "conflict", "first");
        second.getLocalVariables().put("conflict", "second");
        second.getLocalVariables().put("secondOnly", 2);
        second.setNodeOutput("source", "conflict", "second");
        first.getGlobalVariables().put("global", "shared");
        second.getEventVariables().put("event.value", "shared");

        runtime.mergeCompletedBranches(List.of(first, second));

        assertEquals("second", runtime.getLocalVariables().get("conflict"));
        assertEquals(1, runtime.getLocalVariables().get("firstOnly"));
        assertEquals(2, runtime.getLocalVariables().get("secondOnly"));
        assertFalse(runtime.getLocalVariables().containsKey("removed"));
        assertEquals("second", runtime.getNodeOutput("source", "conflict"));
        assertEquals("shared", runtime.getGlobalVariables().get("global"));
        assertEquals("shared", runtime.getEventVariables().get("event.value"));
        assertSame(runtime.getGlobalVariables(), first.getGlobalVariables());
        assertSame(runtime.getEventVariables(), second.getEventVariables());
        assertEquals(runtime.getInvocationId(), first.getInvocationId());
    }

    @Test
    void incompatibleTerminalFunctionFramesFailClosed() {
        FlowGraph graph = new FlowGraph();
        graph.setId("parallel-frame-divergence");
        FlowRuntime runtime = new FlowRuntime(graph, new TypeAdapterRegistry(), new HashMap<>(), new HashMap<>());
        FlowRuntime enteredFunction = runtime.forkBranch();
        FlowRuntime unchanged = runtime.forkBranch();
        FlowGraph function = new FlowGraph();
        function.setId("function");
        enteredFunction.callFunction(function, "caller", Map.of());

        IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> runtime.mergeCompletedBranches(List.of(enteredFunction, unchanged)));

        assertEquals("Parallel Flow Branch Control Frames Diverged", failure.getMessage());
        assertSame(graph, runtime.getGraph());

        FlowRuntime alsoEnteredFunction = runtime.forkBranch();
        alsoEnteredFunction.callFunction(function, "caller", Map.of());

        IllegalStateException matchingEscape = assertThrows(IllegalStateException.class,
            () -> runtime.mergeCompletedBranches(List.of(enteredFunction, alsoEnteredFunction)));

        assertEquals("Parallel Flow Branch Control Frames Diverged", matchingEscape.getMessage());
        assertSame(graph, runtime.getGraph());
    }
}
