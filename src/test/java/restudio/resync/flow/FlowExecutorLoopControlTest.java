package restudio.resync.flow;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import restudio.flow.data.FlowConnection;
import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowNode;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.generic.FlowControlHandler;

import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FlowExecutorLoopControlTest {
    private HandlerRegistry handlers;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        handlers = new HandlerRegistry();
        new FlowControlHandler().registerTo(handlers);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void nestedLoopTerminalBreakTargetsTheOuterLoop() {
        AtomicInteger innerCompletions = new AtomicInteger();
        AtomicInteger outerCompletions = new AtomicInteger();
        handlers.register("inner_completion", (context, node) -> innerCompletions.incrementAndGet());
        handlers.register("outer_completion", (context, node) -> outerCompletions.incrementAndGet());
        FlowGraph graph = graph();
        graph.getNodes().put("outer", controlNode("loop_count", Map.of("count", 3)));
        graph.getNodes().put("inner", controlNode("loop_count", Map.of("count", 0)));
        graph.getNodes().put("inner_completion", new FlowNode("inner_completion", 0, 0, Map.of()));
        graph.getNodes().put("break", controlNode("break_loop", Map.of()));
        graph.getNodes().put("outer_completion", new FlowNode("outer_completion", 0, 0, Map.of()));
        graph.getConnections().add(new FlowConnection("outer", "loop", "inner", "flow"));
        graph.getConnections().add(new FlowConnection("inner", "done", "inner_completion", "flow"));
        graph.getConnections().add(new FlowConnection("inner_completion", "flow", "break", "flow"));
        graph.getConnections().add(new FlowConnection("outer", "done", "outer_completion", "flow"));

        executor().execute(graph, "outer", null, null, Map.of()).join();

        assertEquals(1, innerCompletions.get());
        assertEquals(1, outerCompletions.get());
    }

    @Test
    void breakOnTopLevelLoopTerminalPathFailsOutsideLoop() {
        FlowGraph graph = graph();
        graph.getNodes().put("loop", controlNode("loop_count", Map.of("count", 0)));
        graph.getNodes().put("break", controlNode("break_loop", Map.of()));
        graph.getConnections().add(new FlowConnection("loop", "done", "break", "flow"));

        assertThrows(CompletionException.class,
            () -> executor().execute(graph, "loop", null, null, Map.of()).join());
    }

    @Test
    void breakStopsRemainingBranchAllOutputsInTheCurrentLoopBody() {
        AtomicInteger laterBranches = new AtomicInteger();
        AtomicInteger completions = new AtomicInteger();
        handlers.register("later_branch", (context, node) -> laterBranches.incrementAndGet());
        handlers.register("completion", (context, node) -> completions.incrementAndGet());
        FlowGraph graph = graph();
        graph.getNodes().put("loop", controlNode("loop_count", Map.of("count", 1)));
        graph.getNodes().put("branches", controlNode("branch_all", Map.of()));
        graph.getNodes().put("break", controlNode("break_loop", Map.of()));
        graph.getNodes().put("later_branch", new FlowNode("later_branch", 0, 0, Map.of()));
        graph.getNodes().put("completion", new FlowNode("completion", 0, 0, Map.of()));
        graph.getConnections().add(new FlowConnection("loop", "loop", "branches", "flow"));
        graph.getConnections().add(new FlowConnection("branches", "branch_0", "break", "flow"));
        graph.getConnections().add(new FlowConnection("branches", "branch_1", "later_branch", "flow"));
        graph.getConnections().add(new FlowConnection("loop", "done", "completion", "flow"));

        executor().execute(graph, "loop", null, null, Map.of()).join();

        assertEquals(0, laterBranches.get());
        assertEquals(1, completions.get());
    }

    @Test
    void continueStopsRemainingBranchesAndStartsTheNextIteration() {
        AtomicInteger startedIterations = new AtomicInteger();
        AtomicInteger laterBranches = new AtomicInteger();
        AtomicInteger completions = new AtomicInteger();
        handlers.register("iteration", (context, node) -> startedIterations.incrementAndGet());
        handlers.register("later_branch", (context, node) -> laterBranches.incrementAndGet());
        handlers.register("completion", (context, node) -> completions.incrementAndGet());
        FlowGraph graph = graph();
        graph.getNodes().put("loop", controlNode("loop_count", Map.of("count", 2)));
        graph.getNodes().put("branches", controlNode("branch_all", Map.of()));
        graph.getNodes().put("iteration", new FlowNode("iteration", 0, 0, Map.of()));
        graph.getNodes().put("continue", controlNode("continue_loop", Map.of()));
        graph.getNodes().put("later_branch", new FlowNode("later_branch", 0, 0, Map.of()));
        graph.getNodes().put("completion", new FlowNode("completion", 0, 0, Map.of()));
        graph.getConnections().add(new FlowConnection("loop", "loop", "branches", "flow"));
        graph.getConnections().add(new FlowConnection("branches", "branch_0", "iteration", "flow"));
        graph.getConnections().add(new FlowConnection("iteration", "flow", "continue", "flow"));
        graph.getConnections().add(new FlowConnection("branches", "branch_1", "later_branch", "flow"));
        graph.getConnections().add(new FlowConnection("loop", "done", "completion", "flow"));

        executor().execute(graph, "loop", null, null, Map.of()).join();

        assertEquals(2, startedIterations.get());
        assertEquals(0, laterBranches.get());
        assertEquals(1, completions.get());
    }

    @Test
    void loopCompletionBoundaryCanRestartAnEarlierExecutionSection() {
        AtomicInteger bodies = new AtomicInteger();
        AtomicInteger decisions = new AtomicInteger();
        AtomicInteger completions = new AtomicInteger();
        handlers.register("body", (context, node) -> bodies.incrementAndGet());
        handlers.register("decision", (context, node) -> context.triggerOutput(
            decisions.getAndIncrement() == 0 ? "restart" : "terminal"));
        handlers.register("completion", (context, node) -> completions.incrementAndGet());
        FlowGraph graph = graph();
        graph.getNodes().put("loop", controlNode("loop_count", Map.of("count", 1)));
        graph.getNodes().put("body", new FlowNode("body", 0, 0, Map.of()));
        graph.getNodes().put("decision", new FlowNode("decision", 0, 0, Map.of()));
        graph.getNodes().put("completion", new FlowNode("completion", 0, 0, Map.of()));
        graph.getConnections().add(new FlowConnection("loop", "loop", "body", "flow"));
        graph.getConnections().add(new FlowConnection("loop", "done", "decision", "flow"));
        graph.getConnections().add(new FlowConnection("decision", "restart", "loop", "flow"));
        graph.getConnections().add(new FlowConnection("decision", "terminal", "completion", "flow"));

        executor().execute(graph, "loop", null, null, Map.of()).join();

        assertEquals(2, bodies.get());
        assertEquals(2, decisions.get());
        assertEquals(1, completions.get());
    }

    private FlowExecutor executor() {
        return new FlowExecutor(handlers, new TypeAdapterRegistry(), Map.of());
    }

    private FlowGraph graph() {
        FlowGraph graph = new FlowGraph();
        graph.setId("loop-control");
        return graph;
    }

    private FlowNode controlNode(String operation, Map<String, Object> inputs) {
        FlowNode node = new FlowNode("FlowControlHandler", 0, 0, inputs);
        node.setHandlerConfig(Map.of("operation", operation));
        return node;
    }
}
